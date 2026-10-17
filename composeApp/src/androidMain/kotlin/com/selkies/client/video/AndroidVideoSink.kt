package com.selkies.client.video

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import com.selkies.client.platform.VideoFrameHeader
import com.selkies.client.platform.VideoSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Android 视频解码：MediaCodec 硬解 → Surface
 * 【v2.6.0】多编码器：H.264 / H.265(HEVC) / VP8 / VP9 / AV1（设备支持时）
 *
 * 【v2.7.6】H264/H265 黑屏根因修复（用户反馈「控制界面黑屏」）：
 *  1. AVC 的 csd 必须拆分：csd-0=SPS、csd-1=PPS（旧版把 SPS+PPS 拼接塞进 csd-0——
 *     大量设备的硬件 AVC 解码器直接 configure 失败/静默无输出 = 黑屏；桌面端 FFmpeg
 *     接受拼接体所以桌面正常、安卓黑屏）。HEVC 维持 csd-0=VPS+SPS+PPS 拼接（Android 官方约定）。
 *  2. 解码器创建：硬件优先 → 失败/连续出错自动回退软件解码器（c2.android 系列 / OMX.google 系列）。
 *     旧版 createDecoderByType 挑到问题硬件实现后只会无限重建同一个坏解码器 = 永久黑屏。
 *  3. KEY_MAX_INPUT_SIZE 显式给大（旧版不设，部分硬解输入缓冲默认仅 ~几百 KB，
 *     高码率关键帧塞不下被静默丢弃 → 永远解不出首帧 = 黑屏）。
 *
 * 【v2.7.8（v2.7.5 侧并行工作并入）】
 *  4. 输入绝不丢帧：输入缓冲繁忙（dequeueInputBuffer<0）旧版直接丢帧 → 参考链断裂 →
 *     无限 GOP（keyframe_interval=0）下无关键帧自愈 = 永久黑屏的另一主力根因。
 *     现在挂起 carry 下轮重试（~3s 持续繁忙才释放重建）；超大帧按容量分段续写。
 *  5. lastRenderAtMs/needsKeyframe 轮询通道：与 v2.7.7 的 onDecodeIssue 回调并存，
 *     会话渲染停滞看门狗双保险。
 */
class AndroidVideoSink : VideoSink {

    @Volatile
    var surface: Surface? = null
        private set

    /** 解码错误/不可用 → 会话层提示（无可用解码器时告知用户切换编码） */
    var onDecodeError: ((String) -> Unit)? = null

    /** 【v2.7.7】解码问题上报（队列溢出/解码异常）→ 会话层强制 REQUEST_KEYFRAME（黑屏自愈） */
    @Volatile override var onDecodeIssue: ((String) -> Unit)? = null

    private var running = true
    private var decodeThread: Thread? = null
    private val queue = LinkedBlockingQueue<FrameBuf>(240)
    private var codec: MediaCodec? = null
    private var codecWidth = 0
    private var codecHeight = 0
    private var codecReady = false
    /** 当前解码器 codecId（1=h264 2=vp8 3=vp9 4=av1 5=h265）；0 = 未建 */
    private var codecActiveId = 0
    private var lastConfiguredCsd: ByteArray? = null
    /** v2.7.6：当前是否已回退到软件解码器（失败升级策略） */
    private var usingSoftwareDecoder = false
    /** v2.7.6：连续解码失败计数（成功出帧归零；≥3 → 切软件解码器） */
    private var consecutiveFailures = 0
    private var lastErrorNotifiedAt = 0L

    override val firstFrameRendered = MutableStateFlow(false)

    /** 当前流尺寸（截图 PixelCopy 用） */
    @Volatile var streamWidth: Int = 1280
        private set
    @Volatile var streamHeight: Int = 720
        private set

    // ---- 解码计时（统计面板：decode/present tile，EMA 平滑） ----
    @Volatile private var emaDecodeMs = 0.0
    @Volatile private var emaPresentMs = 0.0
    @Volatile private var droppedFrames = 0L
    override val avgDecodeMs: Double get() = emaDecodeMs
    override val avgPresentMs: Double get() = emaPresentMs
    override val framesDropped: Long get() = droppedFrames

    // ---- v2.7.8（v2.7.5 侧并入）：渲染进度 + 轮询式关键帧请求（会话看门狗消费；
    //      与 v2.7.7 的 onDecodeIssue 回调机制并存——两条通道都会触发 REQUEST_KEYFRAME） ----
    @Volatile private var lastRenderAt = 0L
    override val lastRenderAtMs: Long get() = lastRenderAt
    @Volatile private var needKf = false
    override val needsKeyframe: Boolean get() = needKf
    override fun clearNeedsKeyframe() { needKf = false }

    private class FrameBuf(val header: VideoFrameHeader, val data: ByteArray, val arrivedAt: Long)

    init {
        decodeThread = Thread(this::decoderLoop, "selkies-video-decode").apply {
            isDaemon = true
            start()
        }
    }

    fun attachSurface(s: Surface) {
        surface = s
        reset()
    }

    fun detachSurface() {
        surface = null
        reset()
    }

    override fun onVideoFrame(header: VideoFrameHeader, data: ByteArray, offset: Int, length: Int) {
        // 全帧模式（stripeY=0）；条带模式暂按整帧缓冲策略丢弃非 0 条带的增量
        if (header.stripeY != 0) return
        // ⚠️【视频黑屏修复】length 为整条二进制消息总长（含 12B 帧头），payload 区间是 [offset, length)，
        //    copyOfRange(offset, offset + length) 会恒定越界 12 字节（见 DesktopVideoSink 同步修复）。
        if (header.width > 0 && header.height > 0) {
            streamWidth = header.width
            streamHeight = header.height
        }
        if (length <= offset) return
        val copy = data.copyOfRange(offset, length)
        if (header.keyframe) needKf = false  // 关键帧到达：解码器将重建
        if (!queue.offer(FrameBuf(header, copy, System.nanoTime()))) {
            // 【v2.7.7】队列满：清空后参考链必断 → 立即上报强制关键帧（旧版只丢帧不自愈 = 黑屏）
            droppedFrames++
            needKf = true  // v2.7.8：轮询通道并行置位
            queue.clear()
            onDecodeIssue?.invoke("queue-overflow")
            queue.offer(FrameBuf(header, copy, System.nanoTime()))
        }
    }

    override fun onJpegStripe(frameId: Int, stripeY: Int, data: ByteArray, offset: Int, length: Int) {
        val s = surface ?: return
        runCatching {
            val bmp = BitmapFactory.decodeByteArray(data, offset, length) ?: return
            val canvas: Canvas? = s.lockCanvas(null)
            if (canvas != null) {
                try {
                    canvas.drawBitmap(bmp, 0f, stripeY.toFloat(), null)
                } finally {
                    s.unlockCanvasAndPost(canvas)
                }
            }
            bmp.recycle()
            if (!firstFrameRendered.value) firstFrameRendered.value = true
            lastRenderAt = System.currentTimeMillis()
        }
    }

    override fun reset() {
        releaseCodec()
    }

    private fun releaseCodec() {
        codecReady = false
        codecWidth = 0
        codecHeight = 0
        codecActiveId = 0
        codec?.let { c ->
            runCatching {
                c.stop()
                c.release()
            }
        }
        codec = null
    }

    /** codecId → MediaCodec mime；null = 不支持 */
    private fun mimeOf(codecId: Int): String? = when (codecId) {
        1 -> MediaFormat.MIMETYPE_VIDEO_AVC
        5 -> MediaFormat.MIMETYPE_VIDEO_HEVC
        2 -> "video/x-vnd.on2.vp8"
        3 -> "video/x-vnd.on2.vp9"
        4 -> if (android.os.Build.VERSION.SDK_INT >= 29) "video/av01" else null
        else -> null
    }

    /** v2.7.6：软件解码器名（Codec2 优先，OMX 兜底；API 29+ 才有 c2 系） */
    private fun softwareDecoderName(codecId: Int): String? = when {
        android.os.Build.VERSION.SDK_INT >= 29 -> when (codecId) {
            1 -> "c2.android.avc.decoder"
            5 -> "c2.android.hevc.decoder"
            2 -> "c2.android.vp8.decoder"
            3 -> "c2.android.vp9.decoder"
            4 -> if (android.os.Build.VERSION.SDK_INT >= 29) "c2.android.av1.decoder" else null
            else -> null
        }
        else -> when (codecId) {
            1 -> "OMX.google.h264.decoder"
            5 -> "OMX.google.hevc.decoder"
            2 -> "OMX.google.vp8.decoder"
            3 -> "OMX.google.vp9.decoder"
            else -> null
        }
    }

    /** v2.7.6：创建解码器——默认按类型（硬件优先）；回退模式显式软件解码器 */
    private fun createDecoderFor(codecId: Int): MediaCodec? {
        val mime = mimeOf(codecId) ?: return null
        if (usingSoftwareDecoder) {
            val swName = softwareDecoderName(codecId) ?: return null
            runCatching { MediaCodec.createByCodecName(swName) }.getOrNull()?.let { return it }
        }
        return runCatching { MediaCodec.createDecoderByType(mime) }.getOrNull()
    }

    /** 该设备是否有对应解码器（v2.7.6：硬件或软件任一可用即可） */
    private fun hasDecoderFor(codecId: Int): Boolean {
        val mime = mimeOf(codecId) ?: return false
        val hasAny = runCatching {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            list.codecInfos.any { info ->
                !info.isEncoder && runCatching { info.supportedTypes.any { it.equals(mime, true) } }.getOrDefault(false)
            }
        }.getOrDefault(false)
        return hasAny || softwareDecoderName(codecId) != null
    }

    private fun notifyDecodeErrorOnce(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastErrorNotifiedAt > 10_000) {
            lastErrorNotifiedAt = now
            onDecodeError?.invoke(msg)
        }
    }

    private fun decoderLoop() {
        // v2.7.8：输入繁忙挂起帧（绝不丢——丢一帧断参考链，无限 GOP 下=永久黑屏）+ 跨轮繁忙计数
        var carry: FrameBuf? = null
        var busyRetries = 0
        while (running) {
            val fromCarry = carry != null
            val fb = carry ?: try {
                queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            } catch (e: InterruptedException) {
                break
            } ?: continue
            carry = null
            if (!fromCarry) busyRetries = 0  // 新帧到达才清零（carry 重试累计持续繁忙）
            val s = surface ?: continue
            if (fb.header.codecId !in 1..5) continue
            try {
                val mime = mimeOf(fb.header.codecId)
                if (mime == null) continue
                if (fb.header.keyframe) {
                    // H.264/H.265 需要 NAL 参数集作 csd；VP8/VP9/AV1 关键帧自包含无需 csd
                    val nals: List<Pair<Int, ByteArray>>? = when (fb.header.codecId) {
                        1 -> extractNalList(fb.data, setOf(7, 8), false)
                        5 -> extractNalList(fb.data, setOf(32, 33, 34), true)
                        else -> null
                    }
                    val csdSig = nals?.joinToString(",") { (t, b) -> "$t:${b.size}" } ?: ""
                    val needReconfigure = !codecReady ||
                            fb.header.codecId != codecActiveId ||
                            fb.header.width != codecWidth || fb.header.height != codecHeight ||
                            (csdSig.isNotEmpty() && csdSig != (lastConfiguredCsd?.decodeToString() ?: ""))
                    if (needReconfigure) {
                        if (fb.header.codecId != codecActiveId && codecActiveId != 0 && !hasDecoderFor(fb.header.codecId)) {
                            // v2.7.6：设备硬软均无此解码器 → 透明告知（旧版静默跳过 = 黑屏无解释）
                            notifyDecodeErrorOnce("此设备不支持解码 ${fb.header.codecName} 流——请在服务器设置切换到 H.264/H.265/VP8/VP9")
                            continue
                        }
                        releaseCodec()
                        val format = MediaFormat.createVideoFormat(mime, fb.header.width, fb.header.height)
                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            runCatching { format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1) }
                        }
                        // v2.7.6：显式大输入缓冲（高码率关键帧可达数 MB；部分硬解默认过小 → 帧被丢 = 黑屏）
                        runCatching {
                            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,
                                maxOf(2_000_000, fb.header.width * fb.header.height / 2))
                        }
                        var csdApplied = false
                        if (nals != null && nals.isNotEmpty()) {
                            if (fb.header.codecId == 1) {
                                // 【v2.7.6 核心修复】AVC：csd-0=SPS、csd-1=PPS 分开（拼接体在大量
                                // 硬解上 configure 失败/无输出 = 安卓 H264 黑屏根因）
                                nals.firstOrNull { it.first == 7 }?.let { (_, b) ->
                                    format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(b))
                                }
                                nals.firstOrNull { it.first == 8 }?.let { (_, b) ->
                                    format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(b))
                                }
                            } else {
                                // HEVC：csd-0 = VPS+SPS+PPS 拼接（Android 官方约定，有效）
                                val merged = nals.sortedBy { it.first }.fold(ByteArray(0)) { acc, (_, b) -> acc + b }
                                format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(merged))
                            }
                            lastConfiguredCsd = csdSig.toByteArray()
                            csdApplied = true
                        }
                        if ((fb.header.codecId == 1 || fb.header.codecId == 5) && !csdApplied) {
                            // H264/H265 关键帧无参数集（罕见）：等待下一个携带参数集的关键帧
                            continue
                        }
                        val c = createDecoderFor(fb.header.codecId) ?: continue
                        c.configure(format, s, null, 0)
                        c.start()
                        codec = c
                        codecWidth = fb.header.width
                        codecHeight = fb.header.height
                        codecActiveId = fb.header.codecId
                        codecReady = true
                    }
                }
                val c = codec
                if (c == null) {
                    // 解码器未建（等关键帧）——置标志让会话层请求关键帧，帧本身只能丢弃
                    needKf = true
                    continue
                }
                if (!codecReady) continue
                val decodeStartNs = System.nanoTime()
                // 【v2.7.8 核心并入·输入绝不丢帧】旧逻辑（含 v2.7.6）在输入缓冲繁忙（idx<0）或
                // 容量不足时直接丢帧 → 参考链断裂 → 无限 GOP 下无关键帧自愈 = 永久黑屏。
                // 现在：繁忙挂起 carry 下轮重试（跨轮累计 busyRetries，~3s 持续繁忙才释放重建）；
                // 容量不足按缓冲容量分段续写（Annex-B 字节流允许任意切分）。
                var frameFed = false
                var pending: FrameBuf? = fb
                while (running && pending != null) {
                    val idx = c.dequeueInputBuffer(15_000) // 15ms
                    if (idx < 0) {
                        busyRetries++
                        if (busyRetries > 200) {
                            // 解码器持续 ~3s 不接受输入（内部死锁）→ 释放重建 + 请求关键帧
                            runCatching { releaseCodec() }
                            needKf = true
                            onDecodeIssue?.invoke("input-stall")
                            pending = null
                            break
                        }
                        carry = pending
                        pending = null
                        break
                    }
                    val buf = c.getInputBuffer(idx)
                    if (buf == null) {
                        carry = pending
                        pending = null
                        break
                    }
                    buf.clear()
                    val n = minOf(buf.capacity(), pending.data.size)
                    buf.put(pending.data, 0, n)
                    c.queueInputBuffer(idx, 0, n, System.nanoTime() / 1000, 0)
                    frameFed = true
                    busyRetries = 0
                    if (n < pending.data.size) {
                        // 超大帧分段：剩余部分下一输入缓冲续写
                        pending = FrameBuf(pending.header, pending.data.copyOfRange(n, pending.data.size), pending.arrivedAt)
                    } else {
                        pending = null
                    }
                }
                val rendered = drainOutput(c)
                if (rendered) {
                    // v2.7.6：成功出帧 → 失败计数清零
                    consecutiveFailures = 0
                } else if (!frameFed) {
                    consecutiveFailures++
                    if (consecutiveFailures >= 3 && !usingSoftwareDecoder) {
                        // v2.7.6：硬件解码器持续不出帧 → 升级到软件解码器重建（旧版无限循环坏硬解）
                        usingSoftwareDecoder = true
                        consecutiveFailures = 0
                        releaseCodec()
                        notifyDecodeErrorOnce("硬件解码异常，已切换软件解码（兼容模式）")
                    }
                }
                // 到达→出帧耗时 = present；解码段（含输入等待）= decode（粗粒度但趋势正确）
                val nowNs = System.nanoTime()
                val presentMs = (nowNs - fb.arrivedAt) / 1e6
                val decodeMs = (nowNs - decodeStartNs) / 1e6
                if (emaPresentMs == 0.0) { emaPresentMs = presentMs; emaDecodeMs = decodeMs }
                else {
                    emaPresentMs = emaPresentMs * 0.9 + presentMs * 0.1
                    emaDecodeMs = emaDecodeMs * 0.9 + decodeMs * 0.1
                }
            } catch (t: Throwable) {
                // v2.7.0：解码错误防线收严到 Throwable（MediaCodec native 层可抛 Error 而非 Exception，
                // 旧版仅捕 Exception → 解码线程直接死亡 = 后续视频永久黑屏/闪退风险）
                // v2.7.6：连续异常也计入失败升级（触发软件解码回退）
                consecutiveFailures++
                needKf = true  // v2.7.8：释放后由会话看门狗强制关键帧重建
                onDecodeIssue?.invoke("decode-exception:${t.javaClass.simpleName}")
                if (consecutiveFailures >= 3 && !usingSoftwareDecoder) {
                    usingSoftwareDecoder = true
                    notifyDecodeErrorOnce("硬件解码器异常，已切换软件解码（兼容模式）")
                }
                runCatching { releaseCodec() }
            }
        }
    }

    /** 是否有帧渲染出来 */
    private fun drainOutput(c: MediaCodec): Boolean {
        val info = MediaCodec.BufferInfo()
        var renderedAny = false
        while (true) {
            val outIdx = c.dequeueOutputBuffer(info, 0)
            when {
                outIdx >= 0 -> {
                    val doRender = info.size > 0
                    c.releaseOutputBuffer(outIdx, doRender)
                    if (doRender) {
                        renderedAny = true
                        lastRenderAt = System.currentTimeMillis()  // v2.7.8：会话看门狗渲染停滞判定依据
                        if (!firstFrameRendered.value) firstFrameRendered.value = true
                    }
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* 正常 */ }
                else -> break
            }
        }
        return renderedAny
    }

    /**
     * 提取 Annex-B 流中指定类型的 NAL 列表（各自含起始码，保持出现顺序）
     *  H.264: SPS(7) PPS(8)；H.265: VPS(32) SPS(33) PPS(34)
     *  【v2.7.6】返回逐个 NAL（旧 extractNals 返回拼接体——AVC csd 必须拆 csd-0/csd-1）
     */
    private fun extractNalList(data: ByteArray, wanted: Set<Int>, hevc: Boolean): List<Pair<Int, ByteArray>>? {
        val nals = ArrayList<Triple<Int, Int, Int>>() // (type, start, end)  start=起始码后首字节
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                val start = i + 3
                var j = start
                while (j + 3 < data.size) {
                    if (data[j] == 0.toByte() && data[j + 1] == 0.toByte() && data[j + 2] == 1.toByte()) break
                    j++
                }
                val nalType = if (hevc) {
                    (data[start].toInt() and 0xff) shr 1 and 0x3f
                } else {
                    data[start].toInt() and 0x1f
                }
                if (nalType in wanted) {
                    // 含 4 字节起始码（00 00 00 01 若前置零填充也计入）
                    val codeStart = if (i > 0 && data[i - 1] == 0.toByte()) i - 1 else i
                    nals.add(Triple(nalType, codeStart, j))
                }
                i = j
            } else {
                i++
            }
        }
        if (nals.isEmpty()) return null
        return nals.map { (t, s, e) -> t to data.copyOfRange(s, e) }
    }

    override fun close() {
        running = false
        decodeThread?.interrupt()
        releaseCodec()
        surface = null
    }
}
