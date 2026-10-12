package com.selkies.client.webcam

import com.selkies.client.platform.WebcamCapture
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 桌面摄像头上行：FFmpegFrameGrabber（v4l2/dshow/avfoundation）→ JPEG (MJPEG codec 0) → 0x06 帧
 * v2.4.0：支持指定设备与目标分辨率。
 */
class DesktopWebcamCapture : WebcamCapture {

    private var thread: Thread? = null
    @Volatile
    private var running = false
    @Volatile
    private var keyframeRequest = false
    @Volatile
    private var camW = 1280
    @Volatile
    private var camH = 720

    override val active: Boolean get() = running
    override val width: Int get() = camW
    override val height: Int get() = camH

    override fun start(
        encoder: String,
        deviceId: String,
        targetW: Int,
        targetH: Int,
        onFrame: (codecId: Int, keyframe: Boolean, data: ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        if (running) return
        camW = targetW.coerceIn(160, 1920)
        camH = targetH.coerceIn(120, 1080)
        running = true
        thread = Thread({
            var grabber: FFmpegFrameGrabber? = null
            val converter = Java2DFrameConverter()
            try {
                val os = System.getProperty("os.name").lowercase()
                // 指定设备 → 单设备列表；否则按平台枚举候选
                val deviceList = if (deviceId.isNotBlank()) {
                    listOf(deviceId)
                } else when {
                    os.contains("win") -> dshowDevices()
                    os.contains("mac") -> listOf("0")
                    else -> v4l2Devices()
                }
                var lastError = "未找到摄像头设备"
                for (device in deviceList) {
                    runCatching {
                        val g = if (device.startsWith("video=")) FFmpegFrameGrabber(device) else FFmpegFrameGrabber(device)
                        when {
                            os.contains("win") -> { g.format = "dshow" }
                            os.contains("mac") -> { g.format = "avfoundation" }
                            else -> { g.format = "v4l2" }
                        }
                        g.imageWidth = camW
                        g.imageHeight = camH
                        g.frameRate = 15.0
                        g.start()
                        grabber = g
                    }.onFailure { lastError = it.message ?: it.toString() }
                    if (grabber != null) break
                }
                if (grabber == null) {
                    throw IllegalStateException(lastError)
                }
                val g = grabber!!
                if (g.imageWidth > 0) camW = g.imageWidth
                if (g.imageHeight > 0) camH = g.imageHeight
                while (running) {
                    val frame = g.grab() ?: break
                    if (frame.image == null) continue
                    val img: BufferedImage = converter.convert(frame) ?: continue
                    val out = ByteArrayOutputStream()
                    ImageIO.write(img, "jpg", out)
                    val jpg = out.toByteArray()
                    // MJPEG：每帧都是关键帧
                    onFrame(0, true, jpg)
                    // ~15fps
                    Thread.sleep(66)
                }
            } catch (e: Exception) {
                if (running) onError("摄像头错误：${e.message ?: e.toString()}")
            } finally {
                running = false
                runCatching { grabber?.stop(); grabber?.release() }
                runCatching { converter.close() }
            }
        }, "selkies-webcam").apply { isDaemon = true; start() }
    }

    override fun requestKeyframe() {
        keyframeRequest = true
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun v4l2Devices(): List<String> =
        (0 until 8).map { "/dev/video$it" }.filter { java.io.File(it).exists() }
            .ifEmpty { listOf("/dev/video0") }

    private fun dshowDevices(): List<String> {
        // 常见默认设备名尝试；详细枚举需要 ffmpeg -list_devices
        return listOf("video=Integrated Camera", "video=USB Camera", "0")
    }
}
