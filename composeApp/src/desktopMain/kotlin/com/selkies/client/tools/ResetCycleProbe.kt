package com.selkies.client.tools

import com.selkies.client.core.SelkiesSession
import com.selkies.client.model.AppSettings
import com.selkies.client.model.ServerEntry
import com.selkies.client.platform.*
import com.selkies.client.video.DesktopVideoSink
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * v2.7.10【录屏闪退根因定位】重置循环探针：
 * 真实服务器解码 + 每 N 秒强制 videoSink.reset()（模拟服务器 PIPELINE_RESETTING /
 * 看门狗重置 → ensureDecoder releaseCodec+reopen 路径）。
 * 无头运行：隔离 UI（无 Compose/Skiko 渲染）。
 * -Dselkies.resetinterval=5000 重置间隔毫秒（默认 5000）
 * 若崩溃 → 腐蚀源在 FFmpeg releaseCodec/reopen 路径（与 UI 无关）
 * 若不崩 → 腐蚀源在 UI 侧（位图保留环/渲染）
 */
object ResetCycleProbe {

    @JvmStatic
    fun main(args: Array<String>) {
        fun p(n: String, d: String) = System.getProperty(n) ?: d
        val holdSecs = args.getOrNull(0) ?: p("holdSecs", "60")
        val intervalMs = (System.getProperty("selkies.resetinterval") ?: "5000").toLong()
        val url = args.getOrNull(1) ?: p("url", "https://workspace-browser.nodebyte.cn")
        val user = args.getOrNull(2) ?: p("user", "cshll")
        val pass = args.getOrNull(3) ?: p("pass", "15068253855z")
        val probeRes = (System.getProperty("selkies.proberes") ?: "410x862").split("x")
        val rw = probeRes.getOrNull(0)?.toIntOrNull() ?: 410
        val rh = probeRes.getOrNull(1)?.toIntOrNull() ?: 862

        val sink = DesktopVideoSink()
        val server = ServerEntry(
            id = "resetprobe", name = "resetprobe", url = url,
            username = user, password = pass,
            framerate = 60, encoder = "h264enc", audioEnabled = true,
            resolutionW = rw, resolutionH = rh
        )
        val session = SelkiesSession(server, VideoDecodeProbe.ProbePlatform(sink), { AppSettings() })
        session.videoSink = sink

        println("=== 重置循环探针：每 ${intervalMs}ms reset（复现录屏点击后的 releaseCodec+reopen） ===")
        runBlocking {
            session.connect()
            var resets = 0
            val deadline = System.currentTimeMillis() + holdSecs.toLong() * 1000
            var lastIn = 0L
            while (System.currentTimeMillis() < deadline) {
                delay(intervalMs)
                val st = session.status.value
                val inn = sink.diagFramesIn
                println("[t+${(deadline - System.currentTimeMillis()) / 1000}s 剩余] 状态=$st 进帧=$inn 解码=${sink.diagFramesDecoded} 发布=${sink.diagFramesPublished} 错误=${sink.diagDecodeErrors}")
                if (st == SelkiesSession.Status.CONNECTED && inn > lastIn) {
                    lastIn = inn
                    resets++
                    println("[reset #$resets] 强制 videoSink.reset()（模拟 PIPELINE_RESETTING）")
                    sink.reset()
                }
            }
            println("=== 完成：$resets 次重置，全程 ${sink.diagFramesIn} 帧 / ${sink.diagFramesDecoded} 解码 / ${sink.diagDecodeErrors} 错误 ===")
            session.disconnect()
        }
    }
}
