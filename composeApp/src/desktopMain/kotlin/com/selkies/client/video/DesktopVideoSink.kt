package com.selkies.client.video

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.selkies.client.platform.VideoFrameHeader
import com.selkies.client.platform.VideoSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_HEVC
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_VP8
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_VP9
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AV1
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ImageInfo
import java.awt.image.BufferedImage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/**
 * 桌面视频解码：FFmpeg avcodec（H.264/H.265/VP8/VP9/AV1）→ swscale BGRA → Skia Bitmap → Compose
 * JPEG 条带模式：ImageIO 解码 → 合成 → 发布
 * 【v2.6.0】多编码器支持：旧版仅 H.264（codecId!=1 直接丢帧），切到 H.265/VP8/VP9 后
 * 黑屏且截图无帧 —— 与画质面板提供的编码器选项严重不一致。
 */
class DesktopVideoSink : VideoSink {

    override val firstFrameRendered = MutableStateFlow(false)

    /** 【v2.7.7】解码问题上报（队列溢出/解码失败）→ 会话层强制 REQUEST_KEYFRAME（黑屏自愈） */
    @Volatile override var onDecodeIssue: ((String) -> Unit)? = null

    /** 诊断计数（无头验证用） */
    @Volatile var diagFramesIn: Long = 0
    @Volatile var diagDecodeErrors: Long = 0
    @Volatile var diagFramesDecoded: Long = 0
    @Volatile var diagFramesPublished: Long = 0
    @Volatile var diagLastError: String? = null

    // ---- 解码计时（统计面板） ----
    @Volatile private var emaDecodeMs = 0.0
    @Volatile private var emaPresentMs = 0.0
    @Volatile private var droppedFrames = 0L
    override val avgDecodeMs: Double get() = emaDecodeMs
    override val avgPresentMs: Double get() = emaPresentMs
    override val framesDropped: Long get() = droppedFrames

    // ---- v2.7.5：渲染进度 + 解码端关键帧请求（与 AndroidVideoSink 同语义，会话看门狗消费） ----
    @Volatile private var lastRenderAt = 0L
    override val lastRenderAtMs: Long get() = lastRenderAt
    @Volatile private var needKf = false
    override val needsKeyframe: Boolean get() = needKf
    override fun clearNeedsKeyframe() { needKf = false }
    // v2.7.10：参考链丢失标记（reset 后等待关键帧重同步；解码线程独占写）
    private var awaitingKf = true
    // v2.7.10 诊断：等待关键帧而跳过的帧数（重置风暴观察窗口）
    internal var diagSkippedAwaitingKf = 0L
        private set

    /** 最新解码帧（UI 观察绘制） */
    val bitmapFlow = MutableStateFlow<ImageBitmap?>(null)

    private val queue = LinkedBlockingQueue<FrameBuf>(240)
    private val running = AtomicBoolean(true)
    private var decodeThread: Thread? = null
    // v2.7.10 诊断开关（-Dselkies.videodbg）
    private val VIDEO_DBG = System.getProperty("selkies.videodbg") != null

    // FFmpeg 状态（解码线程独占）
    private var codecCtx: AVCodecContext? = null
    private var packet: AVPacket? = null
    private var frame: AVFrame? = null
    private var bgraFrame: AVFrame? = null
    private var bgraPtr: BytePointer? = null
    // v2.7.10：BGRA 缓冲（javacpp 分配；强引用防 GC，释放走 deallocate）
    private var bgraAlloc: BytePointer? = null
    private var bgraBytes: ByteArray? = null
    private var sws: SwsContext? = null
    private var ctxW = 0
    private var ctxH = 0
    private var ctxCodecId = 0
    // v2.7.10【原生堆越界根治】：BGRA 输出缓冲的真实尺寸跟踪。
    //   旧版用「帧头尺寸」配缓冲，但 sws_scale 按「解码器实际输出尺寸」写入——
    //   两者不一致时（分辨率切换、编码对齐 410×862 vs 416×864、头先到 SPS 后到）
    //   每帧越界写原生堆 → G1 humongous 分配时段错误（hs_err: SIGSEGV in
    //   humongous_obj_allocate_initialize_regions, si_addr=0xff000000ff000000
    //   = BGRA 像素 0xFF 字节模式写入堆元数据）+「解码输出停滞（帧仍在到达）」
    //   看门狗连报（发布前 bytes.size>=w*h*4 守卫失败 → 永不发布）。
    private var bufW = 0
    private var bufH = 0

    // 位图三缓冲轮换
    // （v2.6.0+ 修复后已废弃位图三缓冲池——并发 realloc 竞态是渲染崩溃根因，见 publishBgra 注释）

    // v2.7.10【堆崩溃根治】：最近 8 帧位图保留窗（防止 GC 释放仍在被软渲染器读取的原生像素）
    private val bmpRing = ArrayDeque<androidx.compose.ui.graphics.ImageBitmap>()

    /** 最新解码帧快照（锁保护的复用缓冲——供截图使用，消除跨线程读 Skia 池化位图的竞态）
     *  v2.6.0+：旧版 captureVideoFrame 直接对 bitmapFlow 里的池化 Bitmap 调 readPixels，
     *  而解码线程同时会 allocN32Pixels/installPixels 同一池对象（3 缓冲轮换）→
     *  像素缓冲被 realloc 抽走 → 读到垃圾/崩溃/冻结（间歇性）。
     *  新方案：解码线程每帧持锁 arraycopy 进复用缓冲（4MB≈1ms，远小于帧间隔）；
     *  截图时持锁拷出一次——静态桌面无新帧也能拿到最后一帧。 */
    private val snapLock = Object()
    private var snapW = 0
    private var snapH = 0
    private var snapBytes = ByteArray(0)

    /** 截图：最新帧 BGRA 快照 → PNG（任意线程安全；不再触碰 Skia 位图） */
    fun captureLatestFramePng(): ByteArray? {
        val snap: Triple<Int, Int, ByteArray> = synchronized(snapLock) {
            if (snapW == 0 || snapH == 0 || snapBytes.isEmpty()) return null
            Triple(snapW, snapH, snapBytes.copyOf())
        }
        val (w, h, bgra) = snap
        return runCatching {
            val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val argb = IntArray(w * h)
            for (i in 0 until w * h) {
                val b = bgra[i * 4].toInt() and 0xff
                val g = bgra[i * 4 + 1].toInt() and 0xff
                val r = bgra[i * 4 + 2].toInt() and 0xff
                argb[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
            img.setRGB(0, 0, w, h, argb, 0, w)
            val out = java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(img, "png", out)
            out.toByteArray()
        }.getOrNull()
    }

    // JPEG 合成画布
    private var jpegCanvas: BufferedImage? = null

    private class FrameBuf(val header: VideoFrameHeader, val data: ByteArray, val arrivedAt: Long)

    init {
        avutil.av_log_set_level(avutil.AV_LOG_ERROR)
        decodeThread = Thread(this::decoderLoop, "selkies-video-decode").apply {
            isDaemon = true
            start()
        }
    }

    override fun onVideoFrame(header: VideoFrameHeader, data: ByteArray, offset: Int, length: Int) {
        if (header.stripeY != 0) return
        // ⚠️【视频黑屏修复】length 为整条二进制消息总长（含 12B 帧头，见 SelkiesSession.onBinary/
        //    Protocol.parseVideoFrameHeader），payload 区间是 [offset, length)。
        //    此前写成 copyOfRange(offset, offset + length) 恒定越界 12 字节，抛出
        //    IndexOutOfBoundsException("toIndex (N+12) is greater than size (N)")，
        //    异常沿 WS 接收循环上抛导致连接被判定中断并无限重连（每次重连拿到首个视频帧再次崩溃），
        //    视频区永远黑屏。
        if (length <= offset) return
        diagFramesIn++
        val copy = data.copyOfRange(offset, length)
        if (!queue.offer(FrameBuf(header, copy, System.nanoTime()))) {
            droppedFrames++
            needKf = true  // v2.7.5：溢出后参考链已断，请求关键帧
            queue.clear()
            // 【v2.7.7】溢出消空队列 → 参考链必断 → 立即上报（会话层强制关键帧）
            onDecodeIssue?.invoke("queue-overflow")
            queue.offer(FrameBuf(header, copy, System.nanoTime()))
        }
        if (header.keyframe) needKf = false
    }

    override fun onJpegStripe(frameId: Int, stripeY: Int, data: ByteArray, offset: Int, length: Int) {
        runCatching {
            val img = ImageIO.read(java.io.ByteArrayInputStream(data, offset, length)) ?: return
            val w = img.width
            val h = stripeY + img.height
            var canvas = jpegCanvas
            if (canvas == null || canvas.width < w || canvas.height < h) {
                canvas = BufferedImage(w.coerceAtLeast(16), h.coerceAtLeast(16), BufferedImage.TYPE_3BYTE_BGR)
                jpegCanvas = canvas
            }
            val g = canvas.graphics
            g.drawImage(img, 0, stripeY, null)
            g.dispose()
            publishBufferedImage(canvas)
        }
    }

    override fun reset() {
        // v2.7.10【彻底重写语义】：重置 = 参考链丢失标记（等待关键帧重同步），
        //   不再拆解重建解码器！旧版清零 ctxW/ctxH/codecId → 下一帧触发 ensureDecoder 的
        //   releaseCodec+avcodec 重建 → 实测【原生堆损坏】（NativeCycleProbe 真实数据
        //   第 2 轮必现 free(): invalid next size；ResetCycleProbe/真实服务器 GUI 均复现）。
        //   H264/H265/VP8/VP9/AV1 解码器本身就能在下一个关键帧自重建参考链（官方 web
        //   客户端从不重建解码器）——拆解码器不但没必要，还是闪退根源。
        awaitingKf = true
        needKf = true
    }

    override fun close() {
        running.set(false)
        decodeThread?.interrupt()
        releaseCodec()
    }

    private fun releaseCodec() {
        runCatching {
            // ⚠️【v2.7.10 泄漏根治】旧版 codecCtx?.deallocate() 是 no-op（Pointer 包装构造器
            //   不注册释放器——实测地址不变）→ 每次重开泄漏整只 H264 解码器（含 DPB ~20MB），
            //   连续重开触发 javacpp maxPhysicalBytes 上限 → 「Native allocator returned 0」
            //   解码停滞。改为显式 avcodec_free_context（正确关闭+释放内部状态）。
            codecCtx?.let { ctx ->
                avcodec.avcodec_close(ctx)
                avcodec.avcodec_free_context(ctx)
            }
            packet?.deallocate()
            frame?.deallocate()
            bgraFrame?.deallocate()
            bgraAlloc?.let { runCatching { it.deallocate() } }
            sws?.let { swscale.sws_freeContext(it) }
        }
        codecCtx = null
        packet = null
        frame = null
        bgraFrame = null
        bgraPtr = null
        bgraAlloc = null
        bgraBytes = null
        sws = null
        ctxW = 0
        ctxH = 0
        ctxCodecId = 0
        bufW = 0
        bufH = 0
    }

    /** codecId → FFmpeg 解码器（1=h264 2=vp8 3=vp9 4=av1 5=h265） */
    private fun ffCodecId(codecId: Int): Int = when (codecId) {
        2 -> AV_CODEC_ID_VP8
        3 -> AV_CODEC_ID_VP9
        4 -> AV_CODEC_ID_AV1
        5 -> AV_CODEC_ID_HEVC
        else -> AV_CODEC_ID_H264
    }

    private fun codecLabel(codecId: Int): String = when (codecId) {
        2 -> "VP8"; 3 -> "VP9"; 4 -> "AV1"; 5 -> "H.265"; else -> "H.264"
    }

    private fun ensureDecoder(w: Int, h: Int, codecId: Int) {
        if (codecCtx != null && ctxW == w && ctxH == h && ctxCodecId == codecId) return
        if (VIDEO_DBG) println("[VideoDbg] ensureDecoder: $ctxW x $ctxH c$ctxCodecId -> $w x $h c$codecId (releaseCodec+reopen)")
        releaseCodec()
        val ffId = ffCodecId(codecId)
        val codec = avcodec.avcodec_find_decoder(ffId)
            ?: throw IllegalStateException("未找到 ${codecLabel(codecId)} 解码器")
        // ⚠️【SIGSEGV 修复】AVCodecContext(codec) 匹配的是 Pointer 包装构造器（不分配内存），
        //    把 AVCodec 结构当 AVCodecContext 用会越界写直接段错误——必须用 avcodec_alloc_context3 分配。
        //    此前从未触发：无头探针用 no-op 桩，真机走 Android MediaCodec。
        val ctx = AVCodecContext(avcodec.avcodec_alloc_context3(codec))
        ctx.width(w)
        ctx.height(h)
        ctx.flags2(ctx.flags2() or avcodec.AV_CODEC_FLAG2_FAST)
        if (avcodec.avcodec_open2(ctx, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) < 0) {
            throw IllegalStateException("${codecLabel(codecId)} 解码器初始化失败")
        }
        codecCtx = ctx
        packet = AVPacket()
        frame = AVFrame()
        bgraFrame = AVFrame()
        // v2.7.10：BGRA 缓冲改由 ensureBgraBuffer(实际解码尺寸) 在 sws_scale 前配齐
        //   （旧版此处按 header 尺寸分配 = 越界根因；见 decoderLoop 注释）
        ctxW = w
        ctxH = h
        ctxCodecId = codecId
    }

    /** v2.7.10【越界根治】按解码器实际输出尺寸（重新）配齐 BGRA 缓冲。
     *  ⚠️根因（真实服务器 GUI/探针双重实测）：sws_scale 的 SIMD 写入按 64 字节块对齐——
     *    行距 w*4（如 410×4=1640）非 64 对齐时，每行尾段被 64 字节整块写入覆盖，最后一行
     *    溢出缓冲区末尾 ~24 字节（实测 tail-dump：ff ff 00 ff 像素模式覆写下一 chunk 头 →
     *    glibc free 时检测 invalid next size → SIGABRT；随机污染 G1 堆时 = SIGSEGV）。
     *    FFmpeg 官方 av_image_alloc 本就对 linesize 做 SIMD 对齐——旧版手工设 w*4 是反模式。
     *    修复：行距 64 字节对齐（rowStride），缓冲=rowStride×h；读出时逐行紧凑拷贝。 */
    private fun ensureBgraBuffer(w: Int, h: Int) {
        if (bgraPtr != null && bgraFrame != null && bufW == w && bufH == h) return
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384) return
        if (VIDEO_DBG) println("[VideoDbg] ensureBgraBuffer: $bufW x $bufH -> $w x $h (realloc, stride aligned + pads)")
        // v2.7.10：改用 javacpp 自身分配（带 GC 释放器+显式 deallocate 语义清晰；
        //   av_malloc+BytePointer(long) 在 Kotlin 重载解析下会误选「按 size 分配」构造器）
        bgraAlloc?.let { runCatching { (it as BytePointer).deallocate() } }
        val rowStride = (w * 4 + 63) and 63.inv()
        val size = rowStride.toLong() * h + 128L   // 前垫 64 + 后垫 64（sws SIMD 双向越界吸收）
        val bp = BytePointer(size)
        bp.position(64)
        bgraAlloc = bp
        bgraPtr = bp
        bgraBytes = ByteArray(w * h * 4)
        if (bgraFrame == null) bgraFrame = AVFrame()
        val f = bgraFrame!!
        f.data(0, bp)      // setter 取 address+position → data[0] = base+64（前垫生效）
        f.linesize(0, rowStride)
        bufW = w
        bufH = h
    }

    private fun decoderLoop() {
        // v2.7.10【诊断】解码循环透视（-Dselkies.videodbg 或容器诊断默认开）：
        //   每 2s 打印 队列/入帧/解码/错误/尺寸/最近错误码 —— 定位「帧到达但解码无输出」的停滞点
        var lastDbgAt = 0L
        var lastSendRet = 0
        var lastRecvRet = 0
        var lastHdr = ""
        var frameSeq = 0L
        while (running.get()) {
            val fb = try {
                queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            } catch (e: InterruptedException) {
                break
            }
            if (fb.header.codecId !in 1..5) continue
            // ⚠️【v2.7.10 重置风暴根治】参考链丢失期间（reset/发送失败/中途加入 GOP）
            //    P 帧不可能解出——旧版每帧都走 ensureDecoder 的 releaseCodec+reopen
            //    （每秒 60 次全量解码器重建：原生堆狂捞滥放+泄漏 → glibc 检测堆损坏 abort
            //    = 「点录屏后闪退」链条；同时重置期间解码永久停滞）。
            //    现在：等待携带参数集的关键帧，期间 P 帧直接跳过（并持续要关键帧），
            //    解码器本体不动（关键帧自重同步——与官方 web 客户端同策略）。
            if (awaitingKf && !fb.header.keyframe) {
                needKf = true
                diagSkippedAwaitingKf++
                continue
            }
            if (fb.header.keyframe) awaitingKf = false
            val decodeStartNs = System.nanoTime()
            runCatching {
                ensureDecoder(fb.header.width.coerceAtLeast(16), fb.header.height.coerceAtLeast(16), fb.header.codecId)
                lastHdr = "${fb.header.width}x${fb.header.height}c${fb.header.codecId}${if (fb.header.keyframe) "K" else ""}"
                val ctx = codecCtx!!
                val pkt = packet!!
                avcodec.av_packet_unref(pkt)
                avcodec.av_new_packet(pkt, fb.data.size)
                // ⚠️【BufferOverflow 修复】JavaCPP 从 getter 拿到的 BytePointer 无容量信息，
                //    asByteBuffer() 视图容量为 0，任何 put 都溢出——必须用 BytePointer.put(byte[])
                //    （原生 memcpy，不做 NIO 容量检查，bytedeco 官方示例同款模式）
                pkt.data().position(0).put(fb.data, 0, fb.data.size)
                var ret = avcodec.avcodec_send_packet(ctx, pkt)
                lastSendRet = ret
                if (ret < 0) {
                    diagLastError = "send_packet ret=$ret hdr=$lastHdr"
                    // v2.7.10：发送失败 = 参考链/状态异常 → 等关键帧重同步（不拆解码器）
                    awaitingKf = true
                    needKf = true
                    return@runCatching
                }
                while (true) {
                    ret = avcodec.avcodec_receive_frame(ctx, frame)
                    lastRecvRet = ret
                    if (ret < 0) break
                    val f = frame!!
                    val w = f.width()
                    val h = f.height()
                    if (w <= 0 || h <= 0) continue
                    // ⚠️【v2.7.10 原生堆越界根治】先按实际解码尺寸配齐输出缓冲，再 sws_scale。
                    //   旧版顺序相反（缓冲按 header 尺寸）→ 尺寸不一致时每帧越界写 
                    //   → 原生堆/G1 堆元数据被 BGRA 像素覆写 → 随机 SIGSEGV/SIGABRT 闪退。
                    ensureBgraBuffer(w, h)
                    if (bufW != w || bufH != h) { avutil.av_frame_unref(f); continue }
                    sws = swscale.sws_getCachedContext(
                        sws, w, h, f.format(), w, h, AV_PIX_FMT_BGRA,
                        swscale.SWS_BILINEAR, null as org.bytedeco.ffmpeg.swscale.SwsFilter?,
                        null as org.bytedeco.ffmpeg.swscale.SwsFilter?,
                        null as org.bytedeco.javacpp.DoublePointer?
                    )
                    if (sws == null) continue
                    val bf = bgraFrame!!
                    swscale.sws_scale(sws, f.data() as org.bytedeco.javacpp.PointerPointer, f.linesize() as org.bytedeco.javacpp.IntPointer, 0, h, bf.data() as org.bytedeco.javacpp.PointerPointer, bf.linesize() as org.bytedeco.javacpp.IntPointer)
                    val bytes = bgraBytes!!
                    if (bytes.size >= w * h * 4) {
                        // v2.7.10：行距对齐后逐行紧凑读出（SIMD 安全行距 ≠ 紧凑布局；
                        //   bgraPtr 是 base+position 视图——行偏移叠加 position 基准 64）
                        val stride = (w * 4 + 63) and 63.inv()
                        val bp = bgraPtr!!
                        bp.position(64)
                        for (r in 0 until h) {
                            bp.position((64 + r * stride).toLong()).get(bytes, r * w * 4, w * 4)
                        }
                        publishBgra(bytes, w, h)
                        diagFramesDecoded++
                    }
                    avutil.av_frame_unref(f)
                }
            }.onFailure {
                diagDecodeErrors++
                diagLastError = "${it.javaClass.simpleName}: ${it.message}"
                onDecodeIssue?.invoke("decode-error:${it.javaClass.simpleName}")
                reset()
            }
            // v2.7.10【诊断】解码透视：前 60 帧逐帧 + 之后每 500ms（-Dselkies.videodbg 开启）
            frameSeq++
            val nowMs = System.currentTimeMillis()
            if (VIDEO_DBG && (frameSeq <= 60 || nowMs - lastDbgAt > 500)) {
                lastDbgAt = nowMs
                println("[VideoDbg#$frameSeq] in=$diagFramesIn dec=$diagFramesDecoded pub=$diagFramesPublished err=$diagDecodeErrors skipKf=$diagSkippedAwaitingKf q=${queue.size} ctx=${ctxW}x${ctxH}c${ctxCodecId} buf=${bufW}x${bufH} hdr=$lastHdr send=$lastSendRet recv=$lastRecvRet last=$diagLastError javacppPhys=${org.bytedeco.javacpp.Pointer.physicalBytes() / 1048576}MB")
            }
            // 计时（EMA）
            if (diagFramesDecoded > 0) {
                val nowNs = System.nanoTime()
                val presentMs = (nowNs - fb.arrivedAt) / 1e6
                val decodeMs = (nowNs - decodeStartNs) / 1e6
                if (emaPresentMs == 0.0) { emaPresentMs = presentMs; emaDecodeMs = decodeMs }
                else {
                    emaPresentMs = emaPresentMs * 0.9 + presentMs * 0.1
                    emaDecodeMs = emaDecodeMs * 0.9 + decodeMs * 0.1
                }
            }
        }
    }

    private fun publishBgra(bytes: ByteArray, w: Int, h: Int) {
        // 截图快照（锁内 arraycopy ≈1ms；源 bytes 是解码线程独占缓冲，持锁拷贝无竞态）
        synchronized(snapLock) {
            if (snapBytes.size != w * h * 4) snapBytes = ByteArray(w * h * 4)
            System.arraycopy(bytes, 0, snapBytes, 0, w * h * 4)
            snapW = w
            snapH = h
        }
        // ⚠️【渲染崩溃/冻结根因修复】每帧发布全新不可变位图，彻底废弃旧版三缓冲池：
        //  旧版池在解码线程 realloc/installPixels 同一 Bitmap，而渲染线程（EDT）每帧
        //  Image.makeFromBitmap(bmp) 转换—— bitmap 恰好在 realloc 瞬间被读 →
        //  “Failed to Image::makeFromBitmap” RuntimeException → 未捕获 → 应用崩溃/
        //  窗口冻结（与 Win7/8 崩溃同一根因）。不可变位图发布后永不被改写，
        //  渲染线程持有的引用天然安全。
        // v2.7.10【堆崩溃根治】：旧帧由 GC 回收被实证不可靠——60fps 下每帧 1.4MB 的
        //   G1 humongous 分配风暴 + 软渲染器对上一帧位图的读取与 GC 释放原生像素竞态
        //   → 原生堆元数据被覆写 → 「double free or corruption」/G1 SIGSEGV/deopt free 段错误
        //   （真实服务器 GUI 实测 4 次复现；无头 probe 同流不崩 = UI 侧根因）。
        //   修复：保留最近 8 帧位图的强引用（渲染器可能仍在读取的在速位图不会被 GC 释放
        //   原生像素），仅超过窗口的真正旧帧才允许回收——同时限制年轻代压力。
        val bmp: Bitmap
        if (System.getProperty("selkies.nobitmap") == null) {
            bmp = Bitmap()
            bmp.allocN32Pixels(w, h)
            bmp.installPixels(ImageInfo.makeN32Premul(w, h), bytes.copyOf(), w * 4)
            bmp.setImmutable()
            val img = bmp.asComposeImageBitmap()
            synchronized(bmpRing) {
                bmpRing.addLast(img)
                while (bmpRing.size > 8) bmpRing.removeFirst()
            }
            bitmapFlow.value = img
        } else {
            bmp = Bitmap()  // 诊断模式：不建真实位图（隔离 skia 位图生命周期）
        }
        diagFramesPublished++
        lastRenderAt = System.currentTimeMillis()  // v2.7.5：会话看门狗渲染停滞判定依据
        if (!firstFrameRendered.value) firstFrameRendered.value = true
    }

    private fun publishBufferedImage(img: BufferedImage) {
        val w = img.width
        val h = img.height
        val argb = img.getRGB(0, 0, w, h, null, 0, w)
        val bgra = ByteArray(w * h * 4)
        for (i in 0 until w * h) {
            val p = argb[i]
            bgra[i * 4] = ((p shr 16) and 0xff).toByte()
            bgra[i * 4 + 1] = ((p shr 8) and 0xff).toByte()
            bgra[i * 4 + 2] = (p and 0xff).toByte()
            bgra[i * 4 + 3] = 0xff.toByte()
        }
        publishBgra(bgra, w, h)
    }
}
