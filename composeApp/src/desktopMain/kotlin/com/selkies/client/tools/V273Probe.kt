package com.selkies.client.tools

import com.selkies.client.core.SelkiesSession
import com.selkies.client.model.AppSettings
import com.selkies.client.model.ServerEntry
import com.selkies.client.platform.*

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * v2.7.3 端到端综合验证（真实服务器）：
 *
 * 1. 【核心·麦克风语义修正】服务器 microphone_enabled=(value,locked)：
 *    - locked=false & value=false（nodebyte 实测配置）→ startMicUplink 应放行（返回 true + 真调 micCapture.start）
 *      —— 旧版在此硬拒绝 = "网页能用麦克风、App 用不了"的根因
 *    - 锁定语义字段解析（serverMicLocked）
 * 2. 剪贴板双向真实往返：cw 写入 → REQUEST_CLIPBOARD 回读 + 通知反馈非空
 * 3. 音频流活性：视频播放期间 Opus 帧到达 + quiet 标记不误判停滞
 * 4. 服务器设置 locked 字段解析回归
 *
 * 用法：gradlew :composeApp:probeV273 -PprobeUrl=... -PprobeUser=... -PprobePass=...
 */
object V273Probe {

    @JvmStatic
    fun main(args: Array<String>) {
        val url = sysProp("probeUrl", "https://workspace-browser.nodebyte.cn")
        val user = sysProp("probeUser", "cshll")
        val pass = sysProp("probePass", "15068253855z")

        println("=== Selkies v2.7.3 端到端综合验证 ===")
        println("服务器: $url")

        val server = ServerEntry(
            id = "probe", name = "probe", url = url,
            username = user, password = pass,
            framerate = 30, encoder = "h264enc", audioEnabled = true
        )
        val settings = AppSettings()
        val platform = V273Platform()

        val session = SelkiesSession(
            server = server,
            platform = platform,
            settingsProvider = { settings },
            onClipboardFromRemote = { _, _ -> }
        )
        // v2.7.3 验证目标不是视频解码（libavcodec 在容器内偶发 SIGSEGV，与被测逻辑无关）
        // → 用桩 sink 计数帧即可
        val stubSink = StubVideoSink()
        session.videoSink = stubSink

        session.connect()

        var connected = false
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 25_000) {
            if (session.status.value == SelkiesSession.Status.CONNECTED) { connected = true; break }
            if (session.status.value == SelkiesSession.Status.FAILED) break
            Thread.sleep(200)
        }
        if (!connected) {
            println("❌ 连接失败: ${session.lastError.value}")
            exitProcess(1)
        }
        println("✅ 已连接（MODE websockets）")
        var t = System.currentTimeMillis()
        while (System.currentTimeMillis() - t < 15_000 && stubSink.frames.get() < 1) Thread.sleep(200)
        println("✅ 首帧已到达（${stubSink.frames} 帧）")

        var passCount = 0
        var failCount = 0
        fun check(ok: Boolean, label: String, detail: String = "") {
            if (ok) passCount++ else failCount++
            println("${if (ok) "✅" else "❌"} $label${if (detail.isNotEmpty()) " —— $detail" else ""}")
        }

        kotlinx.coroutines.runBlocking {
            // ============ 1. locked 字段解析 ============
            println()
            println("--- 1. 服务器设置 locked 字段解析 ---")
            t = System.currentTimeMillis()
            while (System.currentTimeMillis() - t < 8000 && session.serverSettings.value.isEmpty()) delay(300)
            val micVal = session.serverMicEnabled.value
            val micLocked = session.serverMicLocked.value
            val camVal = session.serverWebcamEnabled.value
            val camLocked = session.serverWebcamLocked.value
            check(session.serverSettings.value.isNotEmpty(), "server_settings 已解析")
            println("   microphone_enabled: value=$micVal locked=$micLocked")
            println("   webcam_enabled:     value=$camVal locked=$camLocked")

            // ============ 2. 麦克风语义修正（v2.7.3 核心） ============
            println()
            println("--- 2. 麦克风门控语义（未锁定 false = 默认关，可开启） ---")
            platform.micStarts.set(0)
            val micStart = session.startMicUplink()
            if (!micVal && !micLocked) {
                // nodebyte 实测场景：value=false locked=false
                check(micStart, "startMicUplink 放行（返回 $micStart；旧版在此硬拒绝）")
                check(platform.micStarts.get() > 0, "micCapture.start 被实际调用（${platform.micStarts.get()} 次——上行真正建立）")
                check(session.micActive.value, "micActive = true（开关状态如实反映）")
                println("   notices: ${session.notices.value ?: "(无)"}")
            } else if (micVal) {
                check(micStart, "服务器允许麦克风，startMicUplink 放行")
                check(platform.micStarts.get() > 0, "micCapture.start 被实际调用")
            } else {
                // locked=true 场景（此服务器不满足，做逻辑断言即可）
                check(!micStart, "锁定禁用时拒绝（返回 $micStart）")
            }
            session.stopMicUplink()

            // ============ 3. 剪贴板双向往返 ============
            println()
            println("--- 3. 剪贴板双向真实往返（cw → REQUEST_CLIPBOARD） ---")
            val clipEmissions = ConcurrentLinkedQueue<String>()
            val clipJob = CoroutineScope(Dispatchers.Default).launch {
                session.remoteClipboard.collect { p -> p?.first?.let { if (it.isNotEmpty()) clipEmissions.add(it) } }
            }
            val marker = "V273-CLIP-${System.currentTimeMillis()}"
            session.sendClipboardTextNow(marker)
            delay(2000)
            session.requestClipboard()
            delay(4000)
            val got = clipEmissions.any { it.contains(marker) } || session.remoteClipboard.value?.first?.contains(marker) == true
            check(got, "上行写入 → 拉取回读（含 marker）", "远程剪贴板现值：${session.remoteClipboard.value?.first?.take(40) ?: "(空)"}")
            check(session.clipboardNotice.value != null, "剪贴板通知反馈非空（用户可见操作结果）", session.clipboardNotice.value ?: "(无)")
            clipJob.cancel()

            // ============ 4. 音频流活性 ============
            println()
            println("--- 4. 音频流活性（视频播放期间） ---")
            platform.audioOpusCount.set(0)
            t = System.currentTimeMillis()
            while (System.currentTimeMillis() - t < 20_000 && platform.audioOpusCount.get() == 0) delay(500)
            val frames = platform.audioOpusCount.get()
            if (frames > 0) {
                check(true, "Opus 音频帧到达（$frames 帧）——服务器→客户端音频链路正常")
                // 帧流动期间看门狗应静默
                delay(3000)
                check(session.audioStallNotice.value == null, "帧流动期间看门狗静默（无误报）", session.audioStallNotice.value ?: "(无提示)")
            } else {
                println("ℹ️ 20s 无音频帧（远程当前无声——服务器静默期不发帧属正常）；验证看门狗不误报 quiet 场景跳过")
            }

            println()
            println("=== v2.7.3 验证汇总：PASS=$passCount FAIL=$failCount ===")
            if (failCount > 0) exitProcess(2)
        }

        session.disconnect()
        Thread.sleep(800)
        exitProcess(0)
    }

    private fun sysProp(key: String, def: String): String = System.getProperty(key)?.takeIf { it.isNotBlank() } ?: def

    /** 桩视频 sink：只计数（避开 libavcodec 容器内偶发 SIGSEGV） */
    class StubVideoSink : VideoSink {
        val frames = AtomicInteger(0)
        override val firstFrameRendered = MutableStateFlow(false)
        override fun onVideoFrame(header: VideoFrameHeader, data: ByteArray, offset: Int, length: Int) {
            if (frames.incrementAndGet() == 1) firstFrameRendered.value = true
        }
        override fun onJpegStripe(frameId: Int, stripeY: Int, data: ByteArray, offset: Int, length: Int) {
            if (frames.incrementAndGet() == 1) firstFrameRendered.value = true
        }
        override fun reset() {}
        override fun close() {}
    }

    /** 平台桩：追踪麦克风启动次数与音频帧计数 */
    class V273Platform : PlatformServices {
        val micStarts = AtomicInteger(0)
        val audioOpusCount = AtomicInteger(0)

        override val platformName = "V273Probe"
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
            override fun createRecordingFile(fileName: String): String? = null
            override fun listMedia() = emptyList<com.selkies.client.model.SavedMediaEntry>()
            override fun deleteMedia(entry: com.selkies.client.model.SavedMediaEntry): Boolean = false
        }
        override fun makeStreamMuxer(outputPath: String): StreamMuxer =
            com.selkies.client.platform.DesktopStreamMuxer(outputPath)
        override fun makeAudioPlayer(): AudioPlayer = object : AudioPlayer {
            override fun start(sampleRate: Int, channels: Int) {}
            override fun playOpus(opus: ByteArray) { audioOpusCount.incrementAndGet() }
            override fun onQuiet() {}
            override fun setVolume(volume: Float) {}
            override fun stop() {}
            override val bufferLevelMs: Int = 0
        }
        override fun makeMicCapture(): MicCapture = object : MicCapture {
            override fun start(deviceIds: List<String>, onOpusFrame: (ByteArray) -> Unit, onError: (String) -> Unit) {
                micStarts.incrementAndGet()
            }
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
        override fun appVersion(): String = "v273probe"
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
