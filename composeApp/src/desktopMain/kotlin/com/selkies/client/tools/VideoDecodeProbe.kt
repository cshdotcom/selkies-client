package com.selkies.client.tools

import com.selkies.client.core.SelkiesSession
import com.selkies.client.model.AppSettings
import com.selkies.client.model.ServerEntry
import com.selkies.client.platform.AudioPlayer
import com.selkies.client.platform.ClipboardBridge
import com.selkies.client.platform.MicCapture
import com.selkies.client.platform.PickedFile
import com.selkies.client.platform.PlatformServices
import com.selkies.client.platform.MediaStoreBridge
import com.selkies.client.platform.OutputStreamHandle
import com.selkies.client.platform.StreamMuxer
import com.selkies.client.platform.PrintBridge
import com.selkies.client.platform.VideoSink
import com.selkies.client.platform.WebcamCapture
import com.selkies.client.video.DesktopVideoSink
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * 无头视频解码验证：真实 DesktopVideoSink（FFmpeg 解码 → Skia Bitmap），
 * 验证「帧进入 → 解码 → bitmapFlow 发布」全链路（GUI 黑屏问题的定位工具）。
 *
 * 用法：VideoDecodeProbe [holdSecs] [url] [user] [pass]
 */
object VideoDecodeProbe {

    @JvmStatic
    fun main(args: Array<String>) {
        fun p(n: String, d: String) = System.getProperty(n) ?: d
        val holdSecs = args.getOrNull(0) ?: p("holdSecs", "25")
        val url = args.getOrNull(1) ?: p("url", "https://workspace-browser.nodebyte.cn")
        val user = args.getOrNull(2) ?: p("user", "cshll")
        val pass = args.getOrNull(3) ?: p("pass", "15068253855z")

        val sink = DesktopVideoSink()
        val server = ServerEntry(
            id = "videoprobe", name = "videoprobe", url = url,
            username = user, password = pass,
            framerate = 60, encoder = "h264enc", audioEnabled = true
        )
        val session = SelkiesSession(server, ProbePlatform(sink), { AppSettings() })
        session.videoSink = sink

        println("=== 无头视频解码验证（真实 DesktopVideoSink） ===")
        runBlocking {
            session.connect()
            val deadline = System.currentTimeMillis() + holdSecs.toLong() * 1000
            var lastPublished = 0L
            while (System.currentTimeMillis() < deadline) {
                delay(1000)
                val st = session.status.value
                println(
                    "[t+${(System.currentTimeMillis() + holdSecs.toLong() * 1000 - deadline) / 1000}s] " +
                            "状态=$st 进帧=${sink.diagFramesIn} 解码=${sink.diagFramesDecoded} " +
                            "发布=${sink.diagFramesPublished} 错误=${sink.diagDecodeErrors} " +
                            "视频帧统计=${session.stats.value.videoFrames}"
                )
                if (sink.diagLastError != null && sink.diagDecodeErrors > 0) {
                    println("    最后解码错误: ${sink.diagLastError}")
                }
                if (sink.diagFramesPublished > 3 && sink.diagFramesPublished == lastPublished) {
                    // 已稳定出帧，可提前结束
                    break
                }
                lastPublished = sink.diagFramesDecoded
            }
            val ok = sink.diagFramesPublished >= 3
            println()
            println("=== 结果 ===")
            println("状态        : ${session.status.value} (${session.statusDetail.value})")
            println("帧进入      : ${sink.diagFramesIn}")
            println("帧解码      : ${sink.diagFramesDecoded}")
            println("帧发布      : ${sink.diagFramesPublished}")
            println("解码错误    : ${sink.diagDecodeErrors} ${sink.diagLastError ?: ""}")
            println("bitmap 非空 : ${sink.bitmapFlow.value != null} " +
                    "${sink.bitmapFlow.value?.let { "${it.width}x${it.height}" } ?: ""}")
            session.disconnect()
            sink.close()
            println(if (ok) "✅ 视频解码链路验证通过" else "❌ 视频解码链路失败")
            kotlin.system.exitProcess(if (ok) 0 else 1)
        }
    }

    /** 最小平台桩：视频 sink 注入真实的 DesktopVideoSink */
    class ProbePlatform(private val sink: VideoSink) : PlatformServices {
        override val platformName = "VideoDecodeProbe"
        override fun configDir(): String = System.getProperty("java.io.tmpdir")
        override fun clipboard(): ClipboardBridge = object : ClipboardBridge {
            override fun getText(): String? = null
            override fun setText(text: String) {}
            override fun hasImage(): Boolean = false
            override fun getImagePng(): ByteArray? = null
            override fun setImagePng(png: ByteArray) {}
            override fun setListener(listener: (() -> Unit)?) {}
        }
        override fun print(): PrintBridge = object : PrintBridge {
            override fun printPdf(pdfBytes: ByteArray, fileName: String, onResult: (Boolean, String?) -> Unit) { onResult(true, null) }
        }
        override fun vibrate(ms: Long) {}
        override fun openUrl(url: String) {}
        override fun pickFiles(mimeTypes: List<String>, onResult: (List<PickedFile>) -> Unit) {
            onResult(emptyList())
        }
        override fun saveFile(suggestedName: String, bytes: ByteArray, onDone: (Boolean) -> Unit) {
            onDone(false)
        }
        override fun makeVideoSink(): VideoSink = sink
        override fun mediaStore(): MediaStoreBridge = object : MediaStoreBridge {
            override fun screenshotsDir() = ""
            override fun recordingsDir() = ""
            override fun downloadsDir() = ""
            override fun saveScreenshot(png: ByteArray, fileName: String): String? = null
            override fun saveDownload(fileName: String, bytes: ByteArray): String? = null
            override fun openDownloadStream(fileName: String): OutputStreamHandle? = null
            override fun createRecordingFile(fileName: String): String? = null
            override fun listMedia() = emptyList<com.selkies.client.model.SavedMediaEntry>()
            override fun deleteMedia(entry: com.selkies.client.model.SavedMediaEntry) = false
        }
        override fun makeStreamMuxer(outputPath: String): StreamMuxer = object : StreamMuxer {
            override val outputPath: String = outputPath
            override fun addVideoTrack(codecId: Int, width: Int, height: Int) = false
            override fun addAudioTrack(sampleRate: Int, channels: Int) = false
            override fun writeVideoFrame(data: ByteArray, keyframe: Boolean, ptsUs: Long) = false
            override fun writeAudioFrame(data: ByteArray, ptsUs: Long) = false
            override fun writeJpegFrame(parts: List<Pair<Int, ByteArray>>, ptsUs: Long) = false
            override fun close() = false
        }
        override fun makeAudioPlayer(): AudioPlayer = object : AudioPlayer {
            override fun start(sampleRate: Int, channels: Int) {}
            override fun playOpus(opus: ByteArray) {}
            override fun onQuiet() {}
            override fun setVolume(volume: Float) {}
            override fun stop() {}
            override val bufferLevelMs: Int = 0
        }
        override fun makeMicCapture(): MicCapture = object : MicCapture {
            override fun start(deviceIds: List<String>, onOpusFrame: (ByteArray) -> Unit, onError: (String) -> Unit) {}
            override fun stop() {}
            override val active: Boolean = false
        }
        override fun makeWebcamCapture(): WebcamCapture = object : WebcamCapture {
            override fun start(encoder: String, deviceId: String, targetW: Int, targetH: Int, onFrame: (Int, Boolean, ByteArray) -> Unit, onError: (String) -> Unit) {}
            override fun requestKeyframe() {}
            override fun stop() {}
            override val active: Boolean = false
            override val width: Int = 0
            override val height: Int = 0
        }
        override fun hasHardwareKeyboard(): Boolean = true
        override fun setFullscreen(enabled: Boolean) {}
        override fun screenDpi(): Float = 96f
        override fun appVersion(): String = "videoprobe"
        override fun showSoftInput(show: Boolean) {}
        override fun isTouchDevice(): Boolean = false
        override fun nativeKeyCodeToKeysym(nativeKeyCode: Int, unicodeChar: Int): Int? = null
        override fun setPhysicalKeyHandler(handler: ((Int, Boolean) -> Boolean)?) {}
        override fun readFileBytes(path: String): ByteArray? = null
        override fun exitApp() {}
        override fun openFile(path: String) {}
        override fun shareFile(path: String, displayName: String) {}
    }
}
