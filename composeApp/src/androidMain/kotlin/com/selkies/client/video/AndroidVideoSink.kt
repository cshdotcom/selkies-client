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
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Android 视频解码：MediaCodec 硬解 → Surface
 * 【v2.6.0】多编码器：H.264 / H.265(HEVC) / VP8 / VP9 / AV1（设备支持时）
 *   JPEG 条带模式回退：BitmapFactory → Surface Canvas。
 *
 * 【v2.7.5·频繁黑屏根治】三类「帧在到达却黑屏」的解码层缺陷全部修复：
 *  1. 输入缓冲繁忙丢帧（dequeueInputBuffer 返回 -1 时旧版直接丢弃该帧）→ 参考链断裂，
 *     无限 GOP 下永不自愈 = 网页正常、安卓动不动黑屏的主力根因。现在**绝不丢帧**：
 *     帧挂起（carry）下轮重试；超大帧按输入缓冲容量分段续写（Annex-B 字节流允许任意切分）。
 *  2. 解码器异常 → releaseCodec 后旧版静默丢弃后续所有帧（无关键帧请求）→ 永久黑屏。
 *     现在 releaseCodec 时置 needsKeyframe=true，会话看门狗消费后强制 REQUEST_KEYFRAME，
 *     关键帧到达即重建解码器（帧头 keyframe 触发 reconfigure）。
 *  3. 队列溢出 clear() 后参考链已断 → 同样置 needsKeyframe。
 * 另：每次成功上屏更新 lastRenderAtMs（会话看门狗监测「帧在到达但渲染停滞」三级恢复）。
 */
class AndroidVideoSink : VideoSink {

    @Volatile
    var surface: Surface? = null
        private set

    private var running = true
    private var decodeThread: Thread? = null
    private val queue = LinkedBlockingDeque<FrameBuf>(240)
    private var codec: MediaCodec? = null
    private var codecWidth = 0
    private var codecHeight = 0
    private var codecReady = false
    /** 当前解码器 codecId（1=h264 2=vp8 3=vp9 4=av1 5=h265）；0 = 未建 */
    private var codecActiveId = 0
    private var lastConfiguredCsd: ByteArray? = null

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

    // ---- v2.7.5：渲染进度 + 关键帧请求（会话看门狗消费） ----
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
        if (!queue.offer(FrameBuf(header, copy, System.nanoTime()))) {
            // v2.7.5：队列满 → 清旧帧后必须请求关键帧（参考链已断，旧版只丢帧不请求 = 黑屏）
            droppedFrames++
            needKf = true
            queue.clear()
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

    /** 该设备是否有对应解码器 */
    private fun hasDecoderFor(codecId: Int): Boolean {
        val mime = mimeOf(codecId) ?: return false
        return runCatching {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            list.codecInfos.any { info ->
                !info.isEncoder && runCatching { info.supportedTypes.any { it.equals(mime, true) } }.getOrDefault(false)
            }
        }.getOrDefault(false)
    }

    private fun decoderLoop() {
        // v2.7.5：输入繁忙未入队的帧（绝不丢弃——丢一帧断参考链，无限 GOP 下=永久黑屏）
        var carry: FrameBuf? = null
        // v2.7.5：输入持续繁忙计数（跨 carry 重试累计；成功入队/新帧到达时清零）
        var busyRetries = 0
        while (running) {
            val fromCarry = carry != null
            val fb = carry ?: try {
                queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            } catch (e: InterruptedException) {
                break
            } ?: continue
            carry = null
            if (!fromCarry) busyRetries = 0  // 新帧到达才清零（carry 重试不清零，持续繁忙累计到释放重建）
            val s = surface ?: continue
            if (fb.header.codecId !in 1..5) continue
            try {
                val mime = mimeOf(fb.header.codecId)
                if (mime == null) continue
                if (fb.header.keyframe) {
                    // v2.7.5：关键帧到达 → 解码器将重建，清除待请求标志
                    needKf = false
                    // H.264/H.265 需要 NAL 参数集作 csd；VP8/VP9/AV1 关键帧自包含无需 csd
                    val csd: ByteArray? = when (fb.header.codecId) {
                        1 -> extractNals(fb.data, setOf(7, 8))
                        5 -> extractNals(fb.data, setOf(32, 33, 34))
                        else -> ByteArray(0)
                    }
                    val needReconfigure = !codecReady ||
                            fb.header.codecId != codecActiveId ||
                            fb.header.width != codecWidth || fb.header.height != codecHeight ||
                            (csd != null && csd.isNotEmpty() && !csd.contentEquals(lastConfiguredCsd ?: ByteArray(0)))
                    if (needReconfigure) {
                        if (fb.header.codecId != codecActiveId && codecActiveId != 0 && !hasDecoderFor(fb.header.codecId)) {
                            // 设备无此解码器：跳过（上层设置面板不应允许切到不支持的编码）
                            continue
                        }
                        releaseCodec()
                        val format = MediaFormat.createVideoFormat(mime, fb.header.width, fb.header.height)
                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            runCatching { format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1) }
                        }
                        if (csd != null && csd.isNotEmpty()) {
                            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))
                            lastConfiguredCsd = csd
                        } else if (codecActiveId == 0 && fb.header.codecId == 1) {
                            // H.264 关键帧无参数集（罕见）：等待下一个携带 SPS/PPS 的关键帧
                            continue
                        }
                        val c = MediaCodec.createDecoderByType(mime)
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
                // v2.7.5【核心修复】：输入缓冲繁忙不再丢帧。
                //   旧版 idx<0 直接跳过该帧 → 参考链断裂 → 后续 P 帧解码为黑屏/花屏，
                //   无限 GOP（服务器默认 keyframe_interval=0）下没有关键帧自愈 = 永久黑屏。
                //   现在：挂起到 carry 下轮重试（限次防死循环）；容量不足则分段写入。
                var pending: FrameBuf? = fb
                while (running && pending != null) {
                    val idx = c.dequeueInputBuffer(15_000) // 15ms
                    if (idx < 0) {
                        busyRetries++
                        if (busyRetries > 200) {
                            // 解码器长时间不接受输入（内部死锁）→ 释放重建 + 请求关键帧
                            runCatching { releaseCodec() }
                            needKf = true
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
                    if (n < pending.data.size) {
                        // 超大帧分段：Annex-B 字节流可任意切分（解码器按字节流消费）
                        pending = FrameBuf(pending.header, pending.data.copyOfRange(n, pending.data.size), pending.arrivedAt)
                        busyRetries = 0
                    } else {
                        pending = null
                    }
                }
                drainOutput(c)
                // 到达→出帧耗时 = present；解码段（含输入等待）= decode（粗粒度但趋势正确）
                val nowNs = System.nanoTime()
                val presentMs = (nowNs - fb.arrivedAt) / 1e6
                val decodeMs = (nowNs - decodeStartNs) / 1e6
                if (emaPresentMs == 0.0) { emaPresentMs = presentMs; emaDecodeMs = decodeMs }
                else {
                    emaPresentMs = emaPresentMs * 0.9 + presentMs * 0.1
                    emaDecodeMs = emaDecodeMs * 0.9 + decodeMs * 0.1
                }
            } catch (_: Throwable) {
                // v2.7.0：解码错误防线收严到 Throwable（MediaCodec native 层可抛 Error 而非 Exception，
                // 旧版仅捕 Exception → 解码线程直接死亡 = 后续视频永久黑屏/闪退风险）
                // v2.7.5：释放后置 needsKeyframe——会话看门狗请求关键帧 → 帧头 keyframe 触发重建。
                //   旧版释放后静默丢帧 = 「解码器一挂就永久黑屏」根因。
                runCatching { releaseCodec() }
                needKf = true
            }
        }
    }

    private fun drainOutput(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = c.dequeueOutputBuffer(info, 0)
            when {
                outIdx >= 0 -> {
                    val doRender = info.size > 0
                    c.releaseOutputBuffer(outIdx, doRender)
                    if (doRender) {
                        // v2.7.5：真正上屏才更新渲染时间戳（会话看门狗的停滞判定依据）
                        lastRenderAt = System.currentTimeMillis()
                        if (!firstFrameRendered.value) firstFrameRendered.value = true
                    }
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* 正常 */ }
                else -> break
            }
        }
    }

    /** 提取 Annex-B 流中指定类型的连续 NAL（含起始码）作 csd
     *  H.264: SPS(7)+PPS(8)；H.265: VPS(32)+SPS(33)+PPS(34) */
    private fun extractNals(data: ByteArray, wanted: Set<Int>): ByteArray? {
        val nals = ArrayList<Pair<Int, Int>>() // (start, end)
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                val start = i + 3
                var j = start
                while (j + 3 < data.size) {
                    if (data[j] == 0.toByte() && data[j + 1] == 0.toByte() && data[j + 2] == 1.toByte()) break
                    j++
                }
                nals.add(start to j)
                i = j
            } else {
                i++
            }
        }
        val picked = nals.filter { (s, _) ->
            val nalType = if (wanted.first() >= 32) {
                // HEVC NAL 头 2 字节：type = (data[s] >> 1) & 0x3f
                (data[s].toInt() and 0xff) shr 1 and 0x3f
            } else {
                data[s].toInt() and 0x1f
            }
            nalType in wanted
        }
        if (picked.isEmpty()) return null
        val from = picked.first().first - 4
        val to = picked.last().second
        return data.copyOfRange(from.coerceAtLeast(0), to.coerceAtMost(data.size))
    }

    override fun close() {
        running = false
        decodeThread?.interrupt()
        releaseCodec()
        surface = null
    }
}
