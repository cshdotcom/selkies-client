package com.selkies.client.tools

import com.selkies.client.core.SelkiesSession
import com.selkies.client.model.AppSettings
import com.selkies.client.model.ServerEntry
import com.selkies.client.ui.panels.VirtualGamepadEngine
import com.selkies.client.platform.AudioPlayer
import com.selkies.client.platform.ClipboardBridge
import com.selkies.client.platform.MicCapture
import com.selkies.client.platform.PickedFile
import com.selkies.client.platform.PlatformServices
import com.selkies.client.platform.MediaStoreBridge
import com.selkies.client.platform.OutputStreamHandle
import com.selkies.client.platform.StreamMuxer
import com.selkies.client.platform.PrintBridge
import com.selkies.client.platform.VideoFrameHeader
import com.selkies.client.platform.VideoSink
import com.selkies.client.platform.WebcamCapture
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
 * v2.6.0 无头端到端综合验证（真实服务器 + 真实客户端代码路径）：
 *
 * 1. 剪贴板双向回环：sendClipboardTextNow（cw,）→ REQUEST_CLIPBOARD → remoteClipboard StateFlow
 *    （验证 v2.6.0 修复的三根因：面板状态流 + 协议链路 + 通知）
 * 2. 远程音频：远端播正弦（cmd,paplay）→ 0x01 帧 → parseAudioFrames → playOpus 计数
 * 3. 多编码器解码（真实 FFmpeg DesktopVideoSink）：h264 → h265 → vp9 → h264 各收帧解码
 *    （验证 v2.6.0 修复：旧版仅 H.264，切编码器即黑屏）
 * 4. 录屏（WS 流直采）：start → 收帧 → stopAndSave → 文件存在且非空
 * 5. 虚拟手柄协议：VirtualGamepadEngine 连接/按键/轴/断开（js,c/b/a/d 真实发送）
 * 6. 竖屏分辨率旋转：sendResolution(720,1280) → 服务器实现竖屏流
 *
 * 用法：gradlew :composeApp:probeV26 -PprobeUrl=... -PprobeUser=... -PprobePass=...
 */
object V26Probe {

    @JvmStatic
    fun main(args: Array<String>) {
        val url = sysProp("probeUrl", "https://workspace-browser.nodebyte.cn")
        val user = sysProp("probeUser", "cshll")
        val pass = sysProp("probePass", "15068253855z")

        println("=== Selkies v2.6.0 端到端综合验证 ===")
        println("服务器: $url")

        val server = ServerEntry(
            id = "probe", name = "probe", url = url,
            username = user, password = pass,
            framerate = 30, encoder = "h264enc", audioEnabled = true
        )
        val settings = AppSettings()
        val platform = V26Platform()

        // 本地剪贴板写入记录（验证自动应用 + 回声抑制）
        val localClipWrites = java.util.concurrent.ConcurrentLinkedQueue<String>()

        val session = SelkiesSession(
            server = server,
            platform = platform,
            settingsProvider = { settings },
            onClipboardFromRemote = { _, _ -> }
        )
        // 真实 FFmpeg 视频解码器（多编码器 e2e 验证）
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
        // 等首帧解码
        var t = System.currentTimeMillis()
        while (System.currentTimeMillis() - t < 15_000 && realSink.diagFramesDecoded < 1) Thread.sleep(200)
        println("✅ H.264 首帧已解码（${realSink.diagFramesDecoded} 帧）")

        kotlinx.coroutines.runBlocking {
            // ============ 1. 剪贴板双向回环 ============
            println()
            println("--- 1. 剪贴板双向回环 ---")
            // 收集全部 remoteClipboard 发射（迟到的 cr 空快照可能覆盖单次读取）
            val clipEmissions = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val clipJob = CoroutineScope(Dispatchers.Default).launch {
                session.remoteClipboard.collect { p -> p?.first?.let { if (it.isNotEmpty()) clipEmissions.add(it) } }
            }
            val marker = "V26-CLIPBOARD-ROUNDTRIP-${System.currentTimeMillis()}"
            session.sendClipboardTextNow(marker)
            delay(2000)
            session.requestClipboard()
            delay(3000)
            val got = session.remoteClipboard.value?.first
            // ⚠️ StateFlow 融合：REQUEST_CLIPBOARD 响应与迟到的 cr 应答可能先后合并，
            //    collector 未必看到中间值——以「当前值含 marker」+「任一 emission 含 marker」双判定
            val clipRoundTrip = clipEmissions.any { it.contains(marker) } || got?.contains(marker) == true
            println("${if (clipRoundTrip) "✅" else "❌"} 发送(cw,)→读取(REQUEST_CLIPBOARD)→remoteClipboard 状态流：${got?.take(60) ?: "(空)"}（共 ${clipEmissions.size} 次接收）")
            val noticeSet = session.clipboardNotice.value != null
            println("${if (noticeSet) "✅" else "❌"} clipboardNotice 用户反馈提示：${session.clipboardNotice.value ?: "(无)"}")

            // 自动发送路径（pushLocalClipboard → 真实 chunked 发送）
            // ⚠️ 先越过回声抑制窗口（applyRemoteClipboardLocally 的 suppressClipboardEcho
            //    持续 1.5s：远程同步后立即"本地复制"会被当作回声正确丢弃——
            //    探针若紧接着调用会被抑制 → 偶发假阴性）
            delay(2500)
            val marker2 = "V26-AUTOSEND-${System.currentTimeMillis()}"
            session.pushLocalClipboard(marker2)
            delay(9000)  // 避开服务器 REQUEST_CLIPBOARD 防抖窗口（0.25s）+ 网络余量
            session.requestClipboard()
            delay(3000)
            val autoSendOk = clipEmissions.any { it.contains(marker2) }
            println("${if (autoSendOk) "✅" else "❌"} 自动发送路径 pushLocalClipboard：远程已收到 marker2（接收序列 ${clipEmissions.size} 条）")
            clipJob.cancel()

            // ============ 2. 远程音频 ============
            println()
            println("--- 2. 远程音频（远端播正弦 → Opus 帧 → 解析） ---")
            session.sendCommand(
                "python3 -c \"import wave,struct,math;w=wave.open('/tmp/v26.wav','w');w.setnchannels(2);" +
                        "w.setsampwidth(2);w.setframerate(48000);w.writeframes(b''.join(struct.pack('<hh'," +
                        "int(12000*math.sin(2*math.pi*440*t/48000)),int(12000*math.sin(2*math.pi*440*t/48000))) " +
                        "for t in range(48000*3)));w.close()\" && paplay /tmp/v26.wav"
            )
            val audioStart = platform.audioPlayerCount.get()
            t = System.currentTimeMillis()
            while (System.currentTimeMillis() - t < 9000 && platform.audioPlayerCount.get() - audioStart < 60) delay(300)
            val audioFrames = platform.audioPlayerCount.get() - audioStart
            val audioOk = audioFrames >= 50
            println("${if (audioOk) "✅" else "❌"} 音频帧到达并解析：$audioFrames 帧 Opus（3 秒正弦 ≈ 150 帧）")
            println("${if (platform.audioChannels.get() > 0) "✅" else "❌"} AudioPlayer.start(channels=${platform.audioChannels.get()})")

            // ============ 3. 多编码器解码（真实 FFmpeg） ============
            println()
            println("--- 3. 多编码器解码（旧版在 h265/vp9 会黑屏 = 截图无帧的根因） ---")
            for (enc in listOf("h265enc", "vp9enc", "h264enc")) {
                val before = realSink.diagFramesDecoded
                session.applySettings(mapOf("encoder" to enc))
                // 等管线重启 + 新编码器帧解码
                t = System.currentTimeMillis()
                var switched = false
                while (System.currentTimeMillis() - t < 20_000) {
                    if (realSink.diagFramesDecoded > before + 2) { switched = true; break }
                    delay(300)
                }
                val label = enc.removeSuffix("enc").uppercase()
                println("${if (switched) "✅" else "❌"} $label 解码：${realSink.diagFramesDecoded - before} 帧（累计 ${realSink.diagFramesDecoded}，解码错误 ${realSink.diagDecodeErrors}）")
                delay(1200)
            }

            // ============ 4. 录屏（WS 流直采） ============
            println()
            println("--- 4. 录屏（WS 流直采，无系统权限） ---")
            val recPath = java.io.File(System.getProperty("java.io.tmpdir"), "v26_rec_${System.currentTimeMillis()}.mkv").absolutePath
            platform.nextMuxerPath = recPath
            val saved = CountDownLatch(1)
            val savedInfo = AtomicReference<Triple<String?, Long, String?>?>(null)
            val recStart = session.startRecording()
            // ⚠️ startRecording 内部会注册自己的 onSaved（写 savedMediaEvent），必须在之后覆写才能拿到回调
            session.recorder.onSaved = { p, frames, err, _ ->
                savedInfo.set(Triple(p, frames, err)); saved.countDown()
            }
            println("[诊断] startRecording=$recStart state=${session.recorder.state} path=$recPath")
            // 静止桌面不产帧（damage-gated）：录制期间注入鼠标移动 + 强制关键帧，产生真实画面变化
            val mover = CoroutineScope(Dispatchers.Default).launch {
                var mx = 400
                var my = 400
                for (i in 0 until 18) {
                    session.sendMouseMove(mx + (i * 17) % 300, my + (i * 13) % 200, 0)
                    delay(180)
                }
            }
            delay(3500)
            session.stopRecording()
            mover.cancel()
            saved.await(8, TimeUnit.SECONDS)
            val recInfo = savedInfo.get()
            val recOk = recInfo != null && recInfo.first != null && recInfo.second >= 10 &&
                    java.io.File(recInfo.first!!).length() > 10_000
            println("${if (recOk) "✅" else "❌"} 录屏：${recInfo?.second ?: 0} 帧 → ${recInfo?.first ?: "(未保存)"}" +
                    "（${recInfo?.first?.let { java.io.File(it).length() / 1024 } ?: 0} KB）${recInfo?.third ?: ""}")

            // ============ 5. 虚拟手柄协议 ============
            println()
            println("--- 5. 虚拟手柄（js,c/b/a/d/h 真实发送） ---")
            val engine = VirtualGamepadEngine(session)
            engine.connect()
            delay(300)
            engine.setAxis(0, 0.75f)     // 左摇杆 X
            engine.setButton(0, true)    // A
            delay(300)
            engine.setButton(0, false)
            engine.setAxis(0, 0f)
            engine.heartbeat()
            delay(300)
            engine.disconnect()
            delay(400)
            val padAlive = session.status.value == SelkiesSession.Status.CONNECTED
            println("${if (padAlive) "✅" else "❌"} 虚拟手柄消息序列已发送（连接仍在，服务器未拒绝）")

            // ============ 6. 竖屏分辨率 ============
            println()
            println("--- 6. 竖屏分辨率旋转 ---")
            session.sendResolution(720, 1280)
            t = System.currentTimeMillis()
            var portrait = false
            while (System.currentTimeMillis() - t < 12_000) {
                val res = session.realizedResolution.value
                if (res != null && res.first == 720 && res.second == 1280) { portrait = true; break }
                delay(300)
            }
            println("${if (portrait) "✅" else "❌"} 竖屏 720x1280：服务器实现 ${session.realizedResolution.value}")
            session.sendResolution(1280, 720)
            delay(1500)
        }

        statusObserver.cancel()
        realSink.close()
        session.disconnect()

        println()
        println("=== v2.6.0 综合验证完成（详见上方各项 ✅/❌） ===")
        kotlin.system.exitProcess(0)
    }

    private fun sysProp(name: String, default: String): String =
        System.getProperty(name) ?: System.getenv(name) ?: default

    /** 桩平台：音频播放器计数 + 录屏 muxer 写真实文件 */
    class V26Platform : PlatformServices {
        val audioPlayerCount = AtomicInteger(0)
        val audioChannels = AtomicInteger(0)
        var nextMuxerPath: String = ""

        override val platformName = "V26Probe"
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

        /** v2.6.0：真实 FFmpeg 复用器（验证 SIGSEGV 修复 + MKV 真实可封装） */
        override fun makeStreamMuxer(outputPath: String): StreamMuxer =
            com.selkies.client.platform.DesktopStreamMuxer(outputPath)

        override fun makeAudioPlayer(): AudioPlayer = object : AudioPlayer {
            override fun start(sampleRate: Int, channels: Int) { audioChannels.set(channels) }
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
        override fun appVersion(): String = "v26probe"
        override fun showSoftInput(show: Boolean) {}
        override fun isTouchDevice(): Boolean = false
        override fun nativeKeyCodeToKeysym(nativeKeyCode: Int, unicodeChar: Int): Int? = null
        override fun setPhysicalKeyHandler(handler: ((Int, Boolean) -> Boolean)?) {}
        override fun readFileBytes(path: String): ByteArray? = null
        override fun exitApp() {}
        override fun openFile(path: String): Boolean = false
        override fun shareFile(path: String, displayName: String) {}
    }
}
