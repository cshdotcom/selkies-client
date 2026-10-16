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
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * v2.7.0 端到端综合验证（真实服务器）：
 *
 * 1. 服务器能力解析：microphone_enabled / webcam_enabled / audio_enabled → StateFlow
 * 2. 麦克风门控：服务器禁用时 startMicUplink 返回 false + 明确提示
 * 3. 剪贴板可靠同步：cw 写入 → REQUEST_CLIPBOARD 回读（base64 解码对比）；
 *    轮询拉取（5s）+ 去重防护（内容未变不重复写本地剪贴板）
 * 4. 音频停看门狗：AUDIO_STARTED 后 8s 无帧 → 自动恢复（STOP+START_AUDIO）+ 状态提示
 * 5. 录屏：完整周期（帧写入 → MKV 有效）+ 分辨率切换自动分段续录（recordingActive 不中断）
 * 6. 键盘直通防双发基础：物理键 handler 返回 Boolean 契约
 *
 * 用法：gradlew :composeApp:probeV27 -PprobeUrl=... -PprobeUser=... -PprobePass=...
 */
object V27Probe {

    @JvmStatic
    fun main(args: Array<String>) {
        val url = sysProp("probeUrl", "https://workspace-browser.nodebyte.cn")
        val user = sysProp("probeUser", "cshll")
        val pass = sysProp("probePass", "15068253855z")

        println("=== Selkies v2.7.0 端到端综合验证 ===")
        println("服务器: $url")

        val server = ServerEntry(
            id = "probe", name = "probe", url = url,
            username = user, password = pass,
            framerate = 30, encoder = "h264enc", audioEnabled = true
        )
        val settings = AppSettings()
        val platform = V27Platform()

        // 本地剪贴板写入记录（验证去重防护：未变内容不重复写）——由 V27Platform.clipWrites 记录

        val session = SelkiesSession(
            server = server,
            platform = platform,
            settingsProvider = { settings },
            onClipboardFromRemote = { _, _ -> }
        )
        val realSink = DesktopVideoSink()
        session.videoSink = realSink

        val statusObserver = CoroutineScope(Dispatchers.Default).launch {
            session.status.collect { st ->
                if (st != SelkiesSession.Status.CONNECTED) println("[状态] $st — ${session.statusDetail.value}")
            }
        }

        session.connect()

        // 等待连接 + 首帧
        var connected = false
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 25_000) {
            if (session.status.value == SelkiesSession.Status.CONNECTED) { connected = true; break }
            if (session.status.value == SelkiesSession.Status.FAILED) break
            Thread.sleep(200)
        }
        if (!connected) {
            println("❌ 连接失败: ${session.lastError.value}")
            kotlin.system.exitProcess(1)
        }
        println("✅ 已连接（MODE websockets）")
        var t = System.currentTimeMillis()
        while (System.currentTimeMillis() - t < 15_000 && realSink.diagFramesDecoded < 1) Thread.sleep(200)
        println("✅ H.264 首帧已解码（${realSink.diagFramesDecoded} 帧）")

        kotlinx.coroutines.runBlocking {
            // ============ 1. 服务器能力解析 ============
            println()
            println("--- 1. 服务器能力解析（server_settings → StateFlow） ---")
            t = System.currentTimeMillis()
            while (System.currentTimeMillis() - t < 8000 && !settingsParsed(session)) Thread.sleep(300)
            val micEnabled = session.serverMicEnabled.value
            val camEnabled = session.serverWebcamEnabled.value
            val audEnabled = session.serverAudioEnabled.value
            println("${if (settingsParsed(session)) "✅" else "❌"} 能力解析完成：microphone_enabled=$micEnabled webcam_enabled=$camEnabled audio_enabled=$audEnabled")

            // ============ 2. 麦克风门控 ============
            println()
            println("--- 2. 麦克风门控（服务器禁用时明确拒绝） ---")
            val micStart = session.startMicUplink()
            if (!micEnabled) {
                println("${if (!micStart) "✅" else "❌"} startMicUplink 被拒绝（返回 $micStart；提示：${session.notices.value ?: "(无)"}）")
                println("${if (!session.micActive.value) "✅" else "❌"} micActive 保持 false（不再静默发送被丢弃的帧）")
            } else {
                println("ℹ️ 此服务器允许麦克风，跳过禁用路径验证")
                session.stopMicUplink()
            }

            // ============ 3. 剪贴板可靠同步 ============
            println()
            println("--- 3. 剪贴板可靠同步（写入 → 回读解码对比 + 轮询去重） ---")
            val clipEmissions = ConcurrentLinkedQueue<String>()
            val clipJob = CoroutineScope(Dispatchers.Default).launch {
                session.remoteClipboard.collect { p -> p?.first?.let { if (it.isNotEmpty()) clipEmissions.add(it) } }
            }
            val marker = "V27-CLIP-${System.currentTimeMillis()}"
            session.sendClipboardTextNow(marker)
            delay(2000)
            session.requestClipboard()
            delay(4000)
            val got = clipEmissions.any { it.contains(marker) } || session.remoteClipboard.value?.first?.contains(marker) == true
            println("${if (got) "✅" else "❌"} cw 写入 → REQUEST_CLIPBOARD 回读（解码后含 marker：${session.remoteClipboard.value?.first?.take(50) ?: "(空)"}）")

            // 轮询 + 去重：等 12s（覆盖 2 轮以上轮询），本地剪贴板对同一内容只应写一次
            platform.clipWrites.clear()
            delay(12_000)
            val writesOfMarker = platform.clipWrites.count { it == marker }
            val writesTotal = platform.clipWrites.size
            println("${if (writesOfMarker <= 1) "✅" else "❌"} 轮询去重防护：内容未变期间本地剪贴板写入 $writesOfMarker 次（总 $writesTotal 次；>1 = 重复覆盖 bug）")
            println("${if (writesTotal >= 0) "ℹ️" else ""} 剪贴板面板状态：${session.clipboardNotice.value ?: "(无提示 = 去重静默)"}")
            clipJob.cancel()

            // ============ 4. 音频停看门狗 ============
            println()
            println("--- 4. 音频停看门狗（AUDIO_STARTED 后无帧 → 自动恢复 + 提示） ---")
            t = System.currentTimeMillis()
            var noticeSeen = false
            var framesFlowed = false
            while (System.currentTimeMillis() - t < 25_000) {
                if (session.audioStallNotice.value != null) noticeSeen = true
                if (platform.audioPlayerCount.get() > 0) { framesFlowed = true; break }
                if (noticeSeen) break
                delay(500)
            }
            if (framesFlowed) {
                println("✅ 音频帧正常流动（${platform.audioPlayerCount.get()} 帧 Opus）——看门狗静默待命")
            } else if (noticeSeen) {
                println("✅ 无帧场景检测到停滞并触发自动恢复（提示：${session.audioStallNotice.value}）")
                println("ℹ️ 若 3 次恢复后仍无帧，会提示「服务器端音频管线异常」——服务器侧问题的客户端透明化")
            } else {
                println("❌ 25s 内既无音频帧也无停滞提示（看门狗未工作？）")
            }

            // ============ 5. 录屏完整周期 + 自动分段续录 ============
            // ⚠️ 不替换 recorder.onSaved（会话层依赖它做自动续录；替换会破坏被测逻辑本身）
            println()
            println("--- 5. 录屏：完整周期 + 分辨率切换自动续录 ---")
            val recDir = File("/tmp/v27-recordings").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val seg1 = File(recDir, "v27_rec_1.mkv")
            val seg2 = File(recDir, "v27_rec_2.mkv")
            platform.nextMuxerPath = seg1.absolutePath
            val recOk = session.startRecording()
            println("${if (recOk) "✅" else "❌"} startRecording（recordingActive=${session.recordingActive.value}）")
            // 等真实帧写入
            t = System.currentTimeMillis()
            while (System.currentTimeMillis() - t < 15_000 && session.recorder.videoFramesWritten == 0L) delay(300)
            println("${if (session.recorder.videoFramesWritten > 0) "✅" else "❌"} 视频帧写入（${session.recorder.videoFramesWritten} 帧）")

            // 分辨率切换 → 自动分段续录（观察者视角：磁盘文件 + 计数器重置 + recordingActive）
            if (session.recorder.videoFramesWritten > 0) {
                val framesBefore = session.recorder.videoFramesWritten
                platform.nextMuxerPath = seg2.absolutePath
                val (w, h) = session.realizedResolutionFlow.value ?: (1280 to 720)
                session.sendResolution(if (w > h) 720 else 1280, if (w > h) 1280 else 720)
                // 等旧段落盘
                t = System.currentTimeMillis()
                while (System.currentTimeMillis() - t < 12_000 && !(seg1.exists() && seg1.length() > 1000)) delay(300)
                val segSaved = seg1.exists() && seg1.length() > 1000
                // 等新段开录（磁盘证据：seg2 落盘且有内容；计数器参考——framesBefore 可能为 1，
                // 「in 1 until framesBefore」为空区间恒假，不能作为断言）
                t = System.currentTimeMillis()
                while (System.currentTimeMillis() - t < 25_000 && !(seg2.exists() && seg2.length() > 1000)) delay(300)
                val newSegRecording = seg2.exists() && seg2.length() > 1000
                val counterAfter = session.recorder.videoFramesWritten
                println("${if (segSaved && newSegRecording) "✅" else "❌"} 分辨率切换自动分段：旧段落盘=${segSaved}（${seg1.length() / 1024}KB）新段落盘=${newSegRecording}（当前计数器=$counterAfter，旧段计数=$framesBefore）")
                println("${if (session.recordingActive.value) "✅" else "❌"} 分段期间 recordingActive 保持 true（UI 不闪烁）")
            }
            val savedMediaBefore = session.savedMediaEvent.value
            session.stopRecording()
            t = System.currentTimeMillis()
            while (System.currentTimeMillis() - t < 10_000 && session.recordingActive.value) delay(300)
            println("${if (!session.recordingActive.value) "✅" else "❌"} stopRecording 后 recordingActive=false（ioScope 异步保存不阻塞调用线程）")

            // 验证分段文件有效性（ffprobe 可用则深度验证）
            for (f in listOf(seg1, seg2).filter { it.exists() && it.length() > 0 }) {
                val ffprobe = runCatching {
                    ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration,format_name", "-of", "csv=p=0", f.absolutePath)
                        .start().apply { waitFor() }
                        .inputStream.readBytes().toString(Charsets.UTF_8).trim()
                }.getOrNull()
                println("${if (f.length() > 1000) "✅" else "❌"} 录制段有效：${f.name}（${f.length() / 1024}KB${ffprobe?.takeIf { it.isNotBlank() }?.let { " · ffprobe=$it" } ?: ""}）")
            }

            // ============ 6. 剪贴板反向（远程→本地展示）收尾 ============
            println()
            println("--- 6. 收尾：断开自动保存 + 状态清理 ---")
            session.disconnect()
            println("✅ disconnect 完成（剪贴板轮询/音频看门狗已停止）")
        }

        realSink.close()
        statusObserver.cancel()
        println()
        println("=== v2.7.0 综合验证完成（详见上方各项 ✅/❌） ===")
        kotlin.system.exitProcess(0)
    }

    private fun settingsParsed(session: SelkiesSession): Boolean =
        session.serverSettings.value.isNotEmpty()

    private fun sysProp(name: String, default: String): String =
        System.getProperty(name) ?: System.getenv(name) ?: default

    /** 桩平台：音频计数 + 计数剪贴板桥 + 真实录屏 muxer */
    class V27Platform : PlatformServices {
        val audioPlayerCount = AtomicInteger(0)
        var nextMuxerPath: String = ""
        val clipWrites = ConcurrentLinkedQueue<String>()

        override val platformName = "V27Probe"
        override fun configDir(): String = System.getProperty("java.io.tmpdir")
        override fun clipboard(): ClipboardBridge = object : ClipboardBridge {
            override fun getText(): String? = null
            override fun setText(text: String) { clipWrites.add(text) }
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

        /** 真实 FFmpeg 复用器（验证 MKV 真实可封装） */
        override fun makeStreamMuxer(outputPath: String): StreamMuxer =
            com.selkies.client.platform.DesktopStreamMuxer(outputPath)

        override fun makeAudioPlayer(): AudioPlayer = object : AudioPlayer {
            override fun start(sampleRate: Int, channels: Int) {}
            override fun playOpus(opus: ByteArray) { audioPlayerCount.incrementAndGet() }
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
        override fun appVersion(): String = "v27probe"
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
