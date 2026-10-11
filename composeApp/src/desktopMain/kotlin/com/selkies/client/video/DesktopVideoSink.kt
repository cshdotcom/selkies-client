package com.selkies.client.video

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.selkies.client.platform.VideoFrameHeader
import com.selkies.client.platform.VideoSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
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
 * 桌面视频解码：FFmpeg avcodec H.264 → swscale BGRA → Skia Bitmap → Compose
 * JPEG 条带模式：ImageIO 解码 → 合成 → 发布
 */
class DesktopVideoSink : VideoSink {

    override val firstFrameRendered = MutableStateFlow(false)

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

    // 位图三缓冲轮换
    private val pool = ArrayDeque<Bitmap>(3)
    private val poolBytes = ArrayDeque<ByteArray>(3)
    private var poolW = 0
    private var poolH = 0

    // JPEG 合成画布
    private var jpegCanvas: BufferedImage? = null

    private class FrameBuf(val header: VideoFrameHeader, val data: ByteArray)

    init {
        avutil.av_log_set_level(avutil.AV_LOG_ERROR)
        decodeThread = Thread(this::decoderLoop, "selkies-video-decode").apply {
            isDaemon = true
            start()
        }
    }

    override fun onVideoFrame(header: VideoFrameHeader, data: ByteArray, offset: Int, length: Int) {
        if (header.stripeY != 0) return
        val copy = data.copyOfRange(offset, offset + length)
        if (!queue.offer(FrameBuf(header, copy))) {
            queue.clear()
            queue.offer(FrameBuf(header, copy))
        }
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
    }

    override fun close() {
        running.set(false)
        decodeThread?.interrupt()
        releaseCodec()
    }

    private fun releaseCodec() {
        runCatching {
            codecCtx?.let {
                avcodec.avcodec_free_context(it)
            }
            packet?.let { avcodec.av_packet_free(it) }
            frame?.let { avutil.av_frame_free(it) }
            bgraFrame?.let { avutil.av_frame_free(it) }
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
    }

    private fun ensureDecoder(w: Int, h: Int) {
        if (codecCtx != null && ctxW == w && ctxH == h) return
        releaseCodec()
        val codec = avcodec.avcodec_find_decoder(AV_CODEC_ID_H264)
            ?: throw IllegalStateException("未找到 H.264 解码器")
        val ctx = AVCodecContext(codec)
        ctx.width(w)
        ctx.height(h)
        ctx.flags2(ctx.flags2() or avcodec.AV_CODEC_FLAG2_FAST)
        if (avcodec.avcodec_open2(ctx, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) < 0) {
            throw IllegalStateException("H.264 解码器初始化失败")
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
    }

    private fun decoderLoop() {
        while (running.get()) {
            val fb = try {
                queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            } catch (e: InterruptedException) {
                break
            }
            if (fb.header.codecId != 1) continue // 仅 H.264；其他编码由设置层切换
            runCatching {
                ensureDecoder(fb.header.width.coerceAtLeast(16), fb.header.height.coerceAtLeast(16))
                val ctx = codecCtx!!
                val pkt = packet!!
                avcodec.av_packet_unref(pkt)
                avcodec.av_new_packet(pkt, fb.data.size)
                pkt.data().position(0).asByteBuffer().put(fb.data)
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
                        bgraPtr!!.position(0).asBuffer().get(bytes, 0, w * h * 4)
                        publishBgra(bytes, w, h)
                    }
                    avutil.av_frame_unref(f)
                }
            }.onFailure {
                reset()
            }
        }
    }

    private fun publishBgra(bytes: ByteArray, w: Int, h: Int) {
        if (poolW != w || poolH != h) {
            pool.clear()
            poolBytes.clear()
            poolW = w
            poolH = h
            repeat(3) {
                pool.addLast(Bitmap())
                poolBytes.addLast(ByteArray(w * h * 4))
            }
        }
        val bmp = pool.removeFirst()
        val dst = poolBytes.removeFirst()
        System.arraycopy(bytes, 0, dst, 0, w * h * 4)
        bmp.allocN32Pixels(w, h)
        bmp.installPixels(ImageInfo.makeN32Premul(w, h), dst, w * 4)
        pool.addLast(bmp)
        poolBytes.addLast(dst)
        bitmapFlow.value = bmp.asComposeImageBitmap()
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
