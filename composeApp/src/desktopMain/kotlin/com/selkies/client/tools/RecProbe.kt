package com.selkies.client.tools

import com.selkies.client.core.SelkiesSession
import com.selkies.client.core.StreamRecorder
import com.selkies.client.model.AppSettings
import com.selkies.client.model.ServerEntry
import com.selkies.client.platform.*
import com.selkies.client.video.DesktopVideoSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 录屏专项诊断：逐秒打印 会话收帧数 vs 录制器写帧数 + muxer 状态
 * 定位 V26Probe 录屏 0 帧的确切断点
 */
object RecProbe {

    @JvmStatic
    fun main(args: Array<String>) {
        val url = sysProp("probeUrl", "https://workspace-browser.nodebyte.cn")
        val user = sysProp("probeUser", "cshll")
        val pass = sysProp("probePass", "15068253855z")

        val server = ServerEntry(
            id = "probe", name = "probe", url = url,
            username = user, password = pass,
            framerate = 30, encoder = "h264enc", audioEnabled = true
        )
        val settings = AppSettings()
        val platform = object : PlatformServices {
            var nextMuxerPath: String = ""
            override val platformName = "RecProbe"
            override fun configDir(): String = System.getProperty("java.io.tmpdir")
            override fun clipboard(): ClipboardBridge = object : ClipboardBridge {
                override fun getText(): String? = null
                override fun setText(text: String) {}
                override fun hasImage(): Boolean = false
                override fun getImagePng(): ByteArray? = null
                override fun setImagePng(png: ByteArray) {}
            }
            override fun print(): PrintBridge = object : PrintBridge {
                override fun printPdf(pdfBytes: ByteArray, fileName: String, onResult: (Boolean, String?) -> Unit) { onResult(true, null) }
            }
            override fun vibrate(ms: Long) {}
            override fun openUrl(url: String) {}
            override fun pickFiles(mimeTypes: List<String>, onResult: (List<PickedFile>) -> Unit) { onResult(emptyList()) }

        override fun saveFileWithPicker(
            suggestedName: String,
            mime: String,
            onResult: (com.selkies.client.platform.PickedSaveTarget?) -> Unit
        ) { onResult(null) }
            override fun saveFile(suggestedName: String, bytes: ByteArray, onDone: (Boolean) -> Unit) { onDone(true) }
            override fun makeVideoSink(): VideoSink = object : VideoSink {
                override val firstFrameRendered = MutableStateFlow(false)
                override fun onVideoFrame(header: VideoFrameHeader, data: ByteArray, offset: Int, length: Int) {}
                override fun onJpegStripe(frameId: Int, stripeY: Int, data: ByteArray, offset: Int, length: Int) {}
                override fun reset() {}
                override fun close() {}
            }
            override fun mediaStore(): MediaStoreBridge = object : MediaStoreBridge {
                override fun screenshotsDir() = ""
                override fun recordingsDir() = ""
                override fun downloadsDir() = ""
                override fun saveScreenshot(png: ByteArray, fileName: String): String? = null
                override fun saveDownload(fileName: String, bytes: ByteArray): String? = null
                override fun openDownloadStream(fileName: String): OutputStreamHandle? = null
                override fun createRecordingFile(fileName: String): String? = nextMuxerPath
                override fun listMedia() = emptyList<com.selkies.client.model.SavedMediaEntry>()
                override fun deleteMedia(entry: com.selkies.client.model.SavedMediaEntry): Boolean = false
            }
            override fun makeStreamMuxer(outputPath: String): StreamMuxer {
                println("[muxer] 创建 DesktopStreamMuxer: $outputPath")
                return com.selkies.client.platform.DesktopStreamMuxer(outputPath)
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
            override fun appVersion(): String = "recprobe"
            override fun showSoftInput(show: Boolean) {}
            override fun isTouchDevice(): Boolean = false
            override fun nativeKeyCodeToKeysym(nativeKeyCode: Int, unicodeChar: Int): Int? = null
            override fun setPhysicalKeyHandler(handler: ((Int, Boolean) -> Boolean)?) {}
            override fun readFileBytes(path: String): ByteArray? = null
            override fun exitApp() {}
            override fun openFile(path: String): Boolean = false
            override fun shareFile(path: String, displayName: String) {}
        }

        val session = SelkiesSession(server, platform, { settings })
        val realSink = DesktopVideoSink()
        session.videoSink = realSink

        session.connect()

        var t = System.currentTimeMillis()
        while (System.currentTimeMillis() - t < 25_000) {
            if (session.status.value == SelkiesSession.Status.CONNECTED) break
            if (session.status.value == SelkiesSession.Status.FAILED) {
                println("❌ 连接失败: ${session.lastError.value}"); return
            }
            Thread.sleep(200)
        }
        println("✅ 已连接")

        t = System.currentTimeMillis()
        while (System.currentTimeMillis() - t < 15_000 && realSink.diagFramesDecoded < 1) Thread.sleep(200)
        println("✅ 首帧解码（${realSink.diagFramesDecoded} 帧）")

        val recPath = java.io.File(System.getProperty("java.io.tmpdir"), "recprobe_${System.currentTimeMillis()}.mkv").absolutePath
        platform.nextMuxerPath = recPath

        val saved = CountDownLatch(1)
        val savedInfo = AtomicReference<Triple<String?, Long, String?>?>(null)

        val ok = session.startRecording()
        session.recorder.onSaved = { p, frames, err, _ ->
            savedInfo.set(Triple(p, frames, err)); saved.countDown()
        }
        println("[录制] startRecording=$ok state=${session.recorder.state} path=$recPath")

        // 逐秒诊断
        kotlinx.coroutines.runBlocking {
            val mover = CoroutineScope(Dispatchers.Default).launch {
                var i = 0
                while (true) {
                    session.sendMouseMove(300 + (i * 31) % 400, 300 + (i * 19) % 200, 0)
                    i++
                    delay(200)
                }
            }
            var lastStatsFrames = 0L
            for (sec in 1..10) {
                delay(1000)
                val statsFrames = session.stats.value.videoFrames
                val recFrames = session.recorder.videoFramesWritten
                val recAudio = session.recorder.audioFramesWritten
                val recBytes = session.recorder.bytesCaptured
                val decoded = realSink.diagFramesDecoded
                println("[诊断 ${sec}s] 会话收帧=${statsFrames}(+${statsFrames - lastStatsFrames}) 解码=$decoded 录制写帧=$recFrames 录音帧=$recAudio 字节=$recBytes state=${session.recorder.state}")
                lastStatsFrames = statsFrames
                if (sec == 3 && session.recorder.videoFramesWritten == 0L) {
                    println("[补救] 3s 无写帧 → 再发 REQUEST_KEYFRAME")
                    session.forceKeyframe()
                }
            }
            mover.cancel()
            session.stopRecording()
        }

        saved.await(8, TimeUnit.SECONDS)
        val info = savedInfo.get()
        println()
        println("=== 录屏结果 ===")
        println("路径: ${info?.first}")
        println("帧数: ${info?.second}")
        println("错误: ${info?.third}")
        info?.first?.let { p ->
            val f = java.io.File(p)
            if (f.exists()) println("文件大小: ${f.length() / 1024} KB")
        }
        session.disconnect()
        kotlin.system.exitProcess(0)
    }

    private fun sysProp(name: String, default: String): String =
        System.getProperty(name) ?: System.getenv(name) ?: default
}
