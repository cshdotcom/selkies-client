package com.selkies.client.video

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.selkies.client.platform.VideoFrameHeader
import com.selkies.client.platform.VideoSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Android 视频解码：MediaCodec H.264 硬解 → Surface
 * JPEG 条带模式回退：BitmapFactory → Surface Canvas
 */
class AndroidVideoSink : VideoSink {

    @Volatile
    var surface: Surface? = null
        private set

    private var running = true
    private var decodeThread: Thread? = null
    private val queue = LinkedBlockingQueue<FrameBuf>(240)
    private var codec: MediaCodec? = null
    private var codecWidth = 0
    private var codecHeight = 0
    private var codecReady = false
    private var lastConfiguredCsd: ByteArray? = null

    override val firstFrameRendered = MutableStateFlow(false)

    private class FrameBuf(val header: VideoFrameHeader, val data: ByteArray)

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
        val copy = data.copyOfRange(offset, offset + length)
        if (!queue.offer(FrameBuf(header, copy))) {
            // 队列满：丢帧并请求关键帧由上层处理
            queue.clear()
            queue.offer(FrameBuf(header, copy))
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
        }
    }

    override fun reset() {
        releaseCodec()
    }

    private fun releaseCodec() {
        codecReady = false
        codecWidth = 0
        codecHeight = 0
        codec?.let { c ->
            runCatching {
                c.stop()
                c.release()
            }
        }
        codec = null
    }

    private fun decoderLoop() {
        while (running) {
            val fb = try {
                queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            } catch (e: InterruptedException) {
                break
            }
            val s = surface ?: continue
            if (fb.header.codecId != 1) {
                // 仅 H.264 走硬解；其他编码器由上层请求切换为 h264enc/jpeg
                continue
            }
            try {
                if (fb.header.keyframe) {
                    val csd = extractSpsPps(fb.data)
                    val needReconfigure = !codecReady ||
                            fb.header.width != codecWidth || fb.header.height != codecHeight ||
                            (csd != null && !csd.contentEquals(lastConfiguredCsd ?: ByteArray(0)))
                    if (needReconfigure && csd != null) {
                        releaseCodec()
                        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, fb.header.width, fb.header.height)
                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            runCatching { format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1) }
                        }
                        format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))
                        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        c.configure(format, s, null, 0)
                        c.start()
                        codec = c
                        codecWidth = fb.header.width
                        codecHeight = fb.header.height
                        codecReady = true
                        lastConfiguredCsd = csd
                    }
                }
                val c = codec ?: continue
                if (!codecReady) continue
                val idx = c.dequeueInputBuffer(15_000_000) // 15ms
                if (idx >= 0) {
                    val buf = c.getInputBuffer(idx)
                    if (buf != null) {
                        buf.clear()
                        if (buf.capacity() >= fb.data.size) {
                            buf.put(fb.data)
                            c.queueInputBuffer(idx, 0, fb.data.size, System.nanoTime() / 1000, 0)
                        } else {
                            c.queueInputBuffer(idx, 0, 0, System.nanoTime() / 1000, 0)
                        }
                    }
                }
                drainOutput(c)
            } catch (e: Exception) {
                // 解码错误：重建（下一个关键帧恢复）
                releaseCodec()
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
                    if (doRender && !firstFrameRendered.value) firstFrameRendered.value = true
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* 正常 */ }
                else -> break
            }
        }
    }

    /** 提取 Annex-B 流中的 SPS+PPS（含起始码）作为 csd */
    private fun extractSpsPps(data: ByteArray): ByteArray? {
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
        val sps = nals.firstOrNull { (s, _) -> (data[s].toInt() and 0x1f) == 7 }
        val pps = nals.firstOrNull { (s, _) -> (data[s].toInt() and 0x1f) == 8 }
        if (sps == null) return null
        val sppsStart = sps.first - 4
        val ppsEnd = pps?.second ?: sps.second
        return data.copyOfRange(sppsStart.coerceAtLeast(0), ppsEnd.coerceAtMost(data.size))
    }

    override fun close() {
        running = false
        decodeThread?.interrupt()
        releaseCodec()
        surface = null
    }
}
