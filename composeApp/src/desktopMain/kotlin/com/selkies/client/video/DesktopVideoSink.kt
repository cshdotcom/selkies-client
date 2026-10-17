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

    /** 最新解码帧（UI 观察绘制） */
    val bitmapFlow = MutableStateFlow<ImageBitmap?>(null)

    private val queue = LinkedBlockingQueue<FrameBuf>(240)
    private val running = AtomicBoolean(true)
    private var decodeThread: Thread? = null

    // FFmpeg 状态（解码线程独占）
    private var codecCtx: AVCodecContext? = null
    private var packet: AVPacket? = null
    private var frame: AVFrame? = null
    private var bgraFrame: AVFrame? = null
    private var bgraPtr: BytePointer? = null
    private var bgraBytes: ByteArray? = null
    private var sws: SwsContext? = null
    private var ctxW = 0
    private var ctxH = 0
    private var ctxCodecId = 0

    // 位图三缓冲轮换
    // （v2.6.0+ 修复后已废弃位图三缓冲池——并发 realloc 竞态是渲染崩溃根因，见 publishBgra 注释）

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
        // 下一关键帧会重新配置
        ctxW = 0
        ctxH = 0
        ctxCodecId = 0
    }

    override fun close() {
        running.set(false)
        decodeThread?.interrupt()
        releaseCodec()
    }

    private fun releaseCodec() {
        runCatching {
            // ⚠️【double-free 修复】JavaCPP 分配对象（avcodec_alloc_context3/AVPacket()/AVFrame()）
            //    自带释放器，手动调 C 释放函数会与 GC 释放器双重释放 → "free(): invalid pointer"。
            //    统一改用 Pointer.deallocate()（幂等）。
            codecCtx?.deallocate()
            packet?.deallocate()
            frame?.deallocate()
            bgraFrame?.deallocate()
            bgraPtr?.let { avutil.av_free(it) }
            sws?.let { swscale.sws_freeContext(it) }
        }
        codecCtx = null
        packet = null
        frame = null
        bgraFrame = null
        bgraPtr = null
        bgraBytes = null
        sws = null
        ctxW = 0
        ctxH = 0
        ctxCodecId = 0
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
        val size = avutil.av_image_get_buffer_size(AV_PIX_FMT_BGRA, w, h, 1)
        bgraPtr = BytePointer(avutil.av_malloc(size.toLong()))
        bgraBytes = ByteArray(size)
        val f = bgraFrame!!
        f.data(0, bgraPtr)
        f.linesize(0, w * 4)
        ctxW = w
        ctxH = h
        ctxCodecId = codecId
    }

    private fun decoderLoop() {
        while (running.get()) {
            val fb = try {
                queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            } catch (e: InterruptedException) {
                break
            }
            if (fb.header.codecId !in 1..5) continue
            val decodeStartNs = System.nanoTime()
            runCatching {
                ensureDecoder(fb.header.width.coerceAtLeast(16), fb.header.height.coerceAtLeast(16), fb.header.codecId)
                val ctx = codecCtx!!
                val pkt = packet!!
                avcodec.av_packet_unref(pkt)
                avcodec.av_new_packet(pkt, fb.data.size)
                // ⚠️【BufferOverflow 修复】JavaCPP 从 getter 拿到的 BytePointer 无容量信息，
                //    asByteBuffer() 视图容量为 0，任何 put 都溢出——必须用 BytePointer.put(byte[])
                //    （原生 memcpy，不做 NIO 容量检查，bytedeco 官方示例同款模式）
                pkt.data().position(0).put(fb.data, 0, fb.data.size)
                var ret = avcodec.avcodec_send_packet(ctx, pkt)
                if (ret < 0) {
                    reset()
                    return@runCatching
                }
                while (true) {
                    ret = avcodec.avcodec_receive_frame(ctx, frame)
                    if (ret < 0) break
                    val f = frame!!
                    val w = f.width()
                    val h = f.height()
                    if (w <= 0 || h <= 0) continue
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
                        bgraPtr!!.position(0).get(bytes, 0, w * h * 4)
                        publishBgra(bytes, w, h)
                        diagFramesDecoded++
                    }
                    avutil.av_frame_unref(f)
                }
            }.onFailure {
                diagDecodeErrors++
                diagLastError = "${it.javaClass.simpleName}: ${it.message}"
                reset()
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
        //  渲染线程持有的引用天然安全；旧帧由 GC 回收（短期存活，年轻代代价低）。
        val bmp = Bitmap()
        bmp.allocN32Pixels(w, h)
        bmp.installPixels(ImageInfo.makeN32Premul(w, h), bytes.copyOf(), w * 4)
        bmp.setImmutable()
        bitmapFlow.value = bmp.asComposeImageBitmap()
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
