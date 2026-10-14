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
 * 【v2.6.0】多摄像头合成：deviceId 为逗号分隔列表时，每设备独立抓帧线程 +
 *   合成线程把多路画面横向拼接（等高缩放）为一路虚拟摄像头画面上行——
 *   服务器虚拟摄像头只有一路，多选因此必须在客户端合成（与麦克风多路混音同思路）。
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

    /** 一路设备的最新帧（合成线程读取） */
    private class CamSlot(val deviceId: String) {
        @Volatile
        var latest: BufferedImage? = null
        @Volatile
        var alive = false
    }

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
            val os = System.getProperty("os.name").lowercase()
            val deviceList = if (deviceId.isNotBlank()) {
                deviceId.split(',').filter { it.isNotBlank() }
            } else when {
                os.contains("win") -> dshowDevices()
                os.contains("mac") -> listOf("0")
                else -> v4l2Devices()
            }
            val slots = deviceList.map { CamSlot(it.trim()) }
            val grabThreads = ArrayList<Thread>()

            // ---- 每设备独立抓帧线程：持续更新 latest ----
            for (slot in slots) {
                val t = Thread({
                    var grabber: FFmpegFrameGrabber? = null
                    val converter = Java2DFrameConverter()
                    try {
                        val g = FFmpegFrameGrabber(slot.deviceId)
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
                        slot.alive = true
                        while (running) {
                            val frame = g.grab() ?: break
                            if (frame.image == null) continue
                            val img = converter.convert(frame) ?: continue
                            slot.latest = img
                        }
                    } catch (_: Throwable) {
                        // 该设备打开失败：合成线程自动跳过
                    } finally {
                        slot.alive = false
                        runCatching { grabber?.stop(); grabber?.release() }
                        runCatching { converter.close() }
                    }
                }, "selkies-webcam-${slot.deviceId.takeLast(12)}").apply {
                    isDaemon = true
                    start()
                }
                grabThreads.add(t)
            }

            // ---- 等待至少一路出帧 ----
            try {
                val deadline = System.currentTimeMillis() + 8000
                while (running && slots.none { it.latest != null }) {
                    if (System.currentTimeMillis() > deadline) {
                        throw IllegalStateException("未找到可用摄像头设备（${slots.size} 路均无输出）")
                    }
                    Thread.sleep(100)
                }

                // ---- 合成线程主循环：多路拼接 → JPEG ----
                while (running) {
                    val frames = slots.mapNotNull { it.latest }
                    if (frames.isEmpty()) {
                        Thread.sleep(66)
                        continue
                    }
                    val composite: BufferedImage = if (frames.size == 1) {
                        frames[0]
                    } else {
                        // 横向拼接：等高缩放（高度取目标 camH）
                        val h = camH
                        val widths = frames.map { (it.width.toDouble() / it.height * h).toInt().coerceAtLeast(16) }
                        val totalW = widths.sum()
                        val img = BufferedImage(totalW, h, BufferedImage.TYPE_3BYTE_BGR)
                        val g2 = img.createGraphics()
                        var x = 0
                        for ((i, f) in frames.withIndex()) {
                            g2.drawImage(f, x, 0, widths[i], h, null)
                            x += widths[i]
                        }
                        g2.dispose()
                        img
                    }
                    camW = composite.width
                    camH = composite.height
                    val out = ByteArrayOutputStream()
                    ImageIO.write(composite, "jpg", out)
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
                grabThreads.forEach { it.interrupt() }
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
