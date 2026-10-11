package com.selkies.client.tools

import com.selkies.client.core.SelkiesSession
import com.selkies.client.model.AppSettings
import com.selkies.client.model.ServerEntry
import com.selkies.client.platform.AudioPlayer
import com.selkies.client.platform.ClipboardBridge
import com.selkies.client.platform.MicCapture
import com.selkies.client.platform.PickedFile
import com.selkies.client.platform.PlatformServices
import com.selkies.client.platform.PrintBridge
import com.selkies.client.platform.VideoFrameHeader
import com.selkies.client.platform.VideoSink
import com.selkies.client.platform.WebcamCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 无头端到端协议验证工具（开发者工具）
 *
 * 用真实的 SelkiesSession/SelkiesConnection 代码路径连接真实服务器，
 * 验证：握手 → SETTINGS → MODE → r → START_VIDEO → VIDEO_STARTED → H264 帧。
 * 不渲染视频（计数 sink），不弹任何 UI——可在 CI/服务器上运行。
 *
 * 用法：gradlew :composeApp:probeProtocol -PprobeUrl=https://host -PprobeUser=u -PprobePass=p
 */
object ProtocolProbe {

    @JvmStatic
    fun main(args: Array<String>) {
        val url = sysProp("probeUrl", "https://workspace-browser.nodebyte.cn")
        val user = sysProp("probeUser", "cshll")
        val pass = sysProp("probePass", "15068253855z")
        val timeoutSec = sysProp("probeTimeout", "40").toLong()

        println("=== Selkies 协议端到端验证 ===")
        println("服务器: $url  用户: $user  超时: ${timeoutSec}s")

        // 环境诊断（DNS 差异排查）
        runCatching {
            val host = java.net.URI(url).host ?: "unknown"
            val addrs = java.net.InetAddress.getAllByName(host)
            println("[诊断] 直接解析 $host → ${addrs.joinToString { it.hostAddress }}")
        }.onFailure { println("[诊断] 直接解析失败: $it") }
        listOf("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY", "no_proxy", "NO_PROXY",
            "RES_OPTIONS", "LOCALDOMAIN").forEach { k ->
            System.getenv(k)?.let { println("[诊断] env $k=$it") }
        }
        println("[诊断] java.io.tmpdir=${System.getProperty("java.io.tmpdir")}")

        val server = ServerEntry(
            id = "probe", name = "probe", url = url,
            username = user, password = pass,
            framerate = 30, encoder = "h264enc", audioEnabled = true
        )
        val settings = AppSettings()
        val platform = ProbePlatform()
        val clipboardTexts = java.util.concurrent.ConcurrentLinkedQueue<String>()

        val session = SelkiesSession(
            server = server,
            platform = platform,
            settingsProvider = { settings },
            onClipboardFromRemote = { text, mime -> if (text != null) clipboardTexts.add(text) }
        )
        session.videoSink = platform.sink

        val done = CountDownLatch(1)
        val frameCount = AtomicInteger(0)
        val firstFrameCodec = AtomicReference<String?>(null)
        val sawResolution = java.util.concurrent.atomic.AtomicBoolean(false)

        // 状态观察
        val observer = CoroutineScope(Dispatchers.Default).launch {
            session.status.collect { st -> println("[状态] $st — ${session.statusDetail.value}") }
        }
        CoroutineScope(Dispatchers.Default).launch {
            session.realizedResolutionFlow.collect { res ->
                if (res != null && !sawResolution.getAndSet(true)) println("[分辨率] ${res.first}x${res.second}")
            }
        }

        // 帧到达回调（通过 sink 计数）
        platform.sink.onFirstFrame = { codec ->
            firstFrameCodec.compareAndSet(null, codec)
            frameCount.incrementAndGet()
        }
        platform.sink.onFrame = { frameCount.incrementAndGet() }

        session.connect()

        val watcher = CoroutineScope(Dispatchers.Default).launch {
            val deadline = System.currentTimeMillis() + timeoutSec * 1000
            while (System.currentTimeMillis() < deadline) {
                if (frameCount.get() >= 3) {
                    delay(1500) // 再多收一会儿，顺带验证持续流
                    done.countDown()
                    return@launch
                }
                delay(200)
            }
            done.countDown()
        }

        val ok = done.await(timeoutSec + 5, TimeUnit.SECONDS)
        watcher.cancel()
        observer.cancel()

        val frames = frameCount.get()
        val codec = firstFrameCodec.get()
        println()
        println("=== 结果 ===")
        println("最终状态      : ${session.status.value} (${session.statusDetail.value})")
        println("分辨率协商    : ${if (sawResolution.get()) "成功" else "未收到"}")
        println("视频帧        : $frames 帧${codec?.let { "，编码 $it" } ?: ""}")
        println("统计          : ${session.stats.value}")
        println("剪贴板下行    : ${clipboardTexts.size} 条")
        if (session.lastError.value != null) {
            println("错误          : ${session.lastError.value}")
            println("技术细节      : ${session.lastErrorTechnical.value}")
        }
        session.disconnect()

        val success = ok && frames >= 3 && codec != null
        println(if (success) "✅ 协议端到端验证通过" else "❌ 协议验证失败")
        kotlin.system.exitProcess(if (success) 0 else 1)
    }

    private fun sysProp(name: String, default: String): String =
        System.getProperty(name) ?: System.getenv(name) ?: default

    /** 视频帧计数 sink */
    class ProbeVideoSink : VideoSink {
        var onFirstFrame: ((String) -> Unit)? = null
        var onFrame: (() -> Unit)? = null
        private val first = MutableStateFlow(false)
        override val firstFrameRendered: StateFlow<Boolean> = first

        override fun onVideoFrame(header: VideoFrameHeader, data: ByteArray, offset: Int, length: Int) {
            if (!first.value) {
                first.value = true
                onFirstFrame?.invoke(header.codecName)
            } else {
                onFrame?.invoke()
            }
        }

        override fun onJpegStripe(frameId: Int, stripeY: Int, data: ByteArray, offset: Int, length: Int) {
            if (!first.value) {
                first.value = true
                onFirstFrame?.invoke("jpeg-stripe")
            } else onFrame?.invoke()
        }

        override fun reset() {}
        override fun close() {}
    }

    /** 无头平台桩：全部 no-op，仅视频 sink 计数 */
    class ProbePlatform : PlatformServices {
        val sink = ProbeVideoSink()
        override val platformName = "ProtocolProbe"
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
            override fun printPdf(pdfBytes: ByteArray, fileName: String) {}
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
        override fun makeAudioPlayer(): AudioPlayer = object : AudioPlayer {
            override fun start(sampleRate: Int, channels: Int) {}
            override fun playOpus(opus: ByteArray) {}
            override fun onQuiet() {}
            override fun setVolume(volume: Float) {}
            override fun stop() {}
            override val bufferLevelMs: Int = 0
        }
        override fun makeMicCapture(): MicCapture = object : MicCapture {
            override fun start(onOpusFrame: (ByteArray) -> Unit, onError: (String) -> Unit) {}
            override fun stop() {}
            override val active: Boolean = false
        }
        override fun makeWebcamCapture(): WebcamCapture = object : WebcamCapture {
            override fun start(encoder: String, onFrame: (Int, Boolean, ByteArray) -> Unit, onError: (String) -> Unit) {}
            override fun requestKeyframe() {}
            override fun stop() {}
            override val active: Boolean = false
            override val width: Int = 0
            override val height: Int = 0
        }
        override fun hasHardwareKeyboard(): Boolean = true
        override fun setFullscreen(enabled: Boolean) {}
        override fun screenDpi(): Float = 96f
        override fun appVersion(): String = "probe"
        override fun showSoftInput(show: Boolean) {}
        override fun isTouchDevice(): Boolean = false
        override fun nativeKeyCodeToKeysym(nativeKeyCode: Int, unicodeChar: Int): Int? = null
        override fun setPhysicalKeyHandler(handler: ((Int, Boolean) -> Unit)?) {}
        override fun readFileBytes(path: String): ByteArray? = null
        override fun exitApp() {}
    }
}
