package com.selkies.client.tools

import com.selkies.client.platform.DesktopStreamMuxer
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * v2.7.9 桌面 JPEG 录制路径崩溃复现探针（hs_err: writeJpegFrame → writeHeader →
 * avformat_write_header SIGSEGV __libc_free）。无服务器纯本地合成 JPEG 帧：
 *  A) 正路：addVideoTrack(0) → writeJpegFrame（MJPEG ctx + 直接写）
 *  B) 复现候选：不 addVideoTrack 直接 writeJpegFrame（videoCodec 默认 1 → 内部转 0 → MJPEG ctx → writeHeader）
 *  C) 混路：addVideoTrack(1 h264) → writeJpegFrame（ctx 已存在 h264 → 应 return false 不崩）
 */
object V279MuxerProbe {

    private fun synthJpeg(w: Int, h: Int, shade: Int): ByteArray {
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_3BYTE_BGR)
        for (y in 0 until h) for (x in 0 until w) {
            img.setRGB(x, y, (shade shl 16) or ((x * 255 / w) shl 8) or (y * 255 / h))
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val which = args.firstOrNull() ?: "all"
        var pts = 0L
        fun runCase(name: String, block: () -> Unit) {
            println("=== $name ===")
            runCatching(block).onFailure { println("  [EXCEPTION] ${it.javaClass.simpleName}: ${it.message}") }
            println("  case done, JVM still alive ✓")
        }

        if (which == "all" || which == "A") runCase("A: addVideoTrack(0)+writeJpegFrame") {
            val m = DesktopStreamMuxer("/tmp/v279-mux-a.mkv")
            println("  addTrack(0)=${m.addVideoTrack(0, 640, 480)}")
            var ok = 0
            for (i in 0 until 10) {
                pts += 33_333
                if (m.writeJpegFrame(listOf(0 to synthJpeg(640, 480, i * 20)), pts)) ok++
            }
            println("  written=$ok/10 close=${m.close()}")
        }

        if (which == "all" || which == "B") runCase("B: 直接 writeJpegFrame（无 addVideoTrack）") {
            val m = DesktopStreamMuxer("/tmp/v279-mux-b.mkv")
            var ok = 0
            for (i in 0 until 10) {
                pts += 33_333
                if (m.writeJpegFrame(listOf(0 to synthJpeg(640, 480, i * 20)), pts)) ok++
            }
            println("  written=$ok/10 close=${m.close()}")
        }

        if (which == "all" || which == "C") runCase("C: addVideoTrack(1)+writeJpegFrame") {
            val m = DesktopStreamMuxer("/tmp/v279-mux-c.mkv")
            println("  addTrack(1)=${m.addVideoTrack(1, 640, 480)}")
            var ok = 0
            for (i in 0 until 10) {
                pts += 33_333
                if (m.writeJpegFrame(listOf(0 to synthJpeg(640, 480, i * 20)), pts)) ok++
            }
            println("  written=$ok/10 close=${m.close()}")
        }

        println("PROBE COMPLETE")
    }
}
