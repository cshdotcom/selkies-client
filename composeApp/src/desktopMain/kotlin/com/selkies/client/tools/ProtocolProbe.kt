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

        // ===== 第二阶段：新功能端到端验证（光标/分辨率/输入注入/文件传输）=====
        var cursorEchoOk = false
        var cursorShapeOk = false
        var resolutionChangeOk = false
        var filesOk = false
        var filesDetail = ""
        if (ok && frameCount.get() >= 3 && session.status.value == SelkiesSession.Status.CONNECTED) {
            println()
            println("=== 第二阶段：输入注入 / 光标 / 分辨率 / 文件 ===")
            kotlinx.coroutines.runBlocking {
            // 1) 鼠标绝对移动 → 本地光标回显更新（输入路径真实验证）
            session.sendMouseMove(500, 400, 0)
            delay(400)
            session.sendMouseMove(900, 600, 0)
            delay(600)
            cursorEchoOk = session.cursorX.value >= 899f && session.cursorY.value >= 599f
            println("[光标回显] x=${session.cursorX.value} y=${session.cursorY.value} → ${if (cursorEchoOk) "OK" else "FAIL"}")
            cursorShapeOk = session.cursorShape.value != null
            println("[光标形状] ${session.cursorShape.value?.let { "handle=${it.handle} ${it.width}x${it.height} hot=(${it.hotX},${it.hotY})" } ?: "未收到"}")

            // 2) 点击（按下+抬起）
            session.sendMouseMove(900, 600, 1)
            delay(150)
            session.sendMouseMove(900, 600, 0)
            delay(300)
            println("[点击注入] 已发送（远程光标形状 handle=${session.cursorShape.value?.handle ?: "-"}）")

            // 3) 分辨率切换
            val before = session.realizedResolution.value
            session.sendResolution(1024, 768)
            var waited = 0
            while (waited < 8000 && session.realizedResolution.value == before) {
                delay(250); waited += 250
            }
            resolutionChangeOk = session.realizedResolution.value?.first == 1024
            println("[分辨率] $before → ${session.realizedResolution.value} → ${if (resolutionChangeOk) "OK" else "FAIL"}")
            // 切回原分辨率
            before?.let { session.sendResolution(it.first, it.second) }
            delay(1500)

            // 4) 文件传输：列表 → 上传 → 认证下载（旧版 401/400 回归验证）
            run {
                val stamp = System.currentTimeMillis()
                val probeName = "e2e-probe-$stamp.txt"
                val probeBytes = "selkies e2e download probe $stamp\n".toByteArray()
                val result = runCatching {
                    val entries = session.filesClient.list("").getOrThrow()
                    println("[文件] 根目录 ${entries.size} 条（目录 ${entries.count { it.isDir }} 个）")
                    val tmpUp = java.io.File.createTempFile(probeName, null)
                    tmpUp.writeBytes(probeBytes)
                    val upOk = uploadProbe(session, tmpUp, probeName)
                    tmpUp.delete()
                    if (!upOk) return@run "上传失败"
                    val destDir = java.io.File(System.getProperty("java.io.tmpdir"), "selkies-e2e-dl")
                    destDir.mkdirs()
                    var err: String? = null
                    session.filesClient.download(probeName, probeName, destDir)
                    val t0 = System.currentTimeMillis()
                    while (System.currentTimeMillis() - t0 < 30_000) {
                        val t = session.filesClient.transfers.value.lastOrNull { it.fileName == probeName }
                        if (t != null && (t.status == "done" || t.status == "error")) {
                            if (t.status == "error") err = t.message
                            break
                        }
                        delay(200)
                    }
                    if (err != null) return@run "下载失败: $err"
                    val dl = java.io.File(destDir, probeName)
                    if (!dl.exists()) return@run "下载文件不存在"
                    if (!dl.readBytes().contentEquals(probeBytes)) return@run "下载内容不一致"
                    dl.delete()
                    true
                }.getOrElse { "异常: ${it.message}" }
                filesOk = result == true
                filesDetail = if (result == true) "上传+认证下载+内容校验通过" else result.toString()
                println("[文件] $filesDetail → ${if (filesOk) "OK" else "FAIL"}")
            }
            }
        }
        observer.cancel()

        val frames = frameCount.get()
        val codec = firstFrameCodec.get()
        println()
        println("=== 结果 ===")
        println("最终状态      : ${session.status.value} (${session.statusDetail.value})")
        println("分辨率协商    : ${if (sawResolution.get()) "成功" else "未收到"}")
        println("视频帧        : $frames 帧${codec?.let { "，编码 $it" } ?: ""}")
        println("光标回显      : ${if (cursorEchoOk) "OK" else "FAIL"}")
        println("光标形状      : ${if (cursorShapeOk) "OK" else "未收到"}")
        println("分辨率切换    : ${if (resolutionChangeOk) "OK" else "FAIL"}")
        println("文件传输      : ${if (filesOk) "OK" else "FAIL"} ${filesDetail}")
        println("统计          : ${session.stats.value}")
        println("剪贴板下行    : ${clipboardTexts.size} 条")
        if (session.lastError.value != null) {
            println("错误          : ${session.lastError.value}")
            println("技术细节      : ${session.lastErrorTechnical.value}")
        }
        session.disconnect()

        val success = ok && frames >= 3 && codec != null && cursorEchoOk && resolutionChangeOk && filesOk
        println(if (success) "✅ 协议端到端验证通过" else "❌ 协议验证失败")
        kotlin.system.exitProcess(if (success) 0 else 1)
    }

    /** 同步等待上传完成 */
    private fun uploadProbe(session: SelkiesSession, file: java.io.File, remoteName: String): Boolean {
        session.filesClient.upload(file, remoteName)
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 30_000) {
            val t = session.filesClient.transfers.value.lastOrNull { it.fileName == file.name }
            if (t != null) {
                if (t.status == "done") return true
                if (t.status == "error") {
                    println("[上传失败] ${t.message}")
                    return false
                }
            }
            Thread.sleep(200)
        }
        return false
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
            override fun printPdf(pdfBytes: ByteArray, fileName: String, onResult: (Boolean, String?) -> Unit) { onResult(true, null) }
        }
        override fun vibrate(ms: Long) {}
        override fun openUrl(url: String) {}
        override fun pickFiles(mimeTypes: List<String>, onResult: (List<PickedFile>) -> Unit) {
            onResult(emptyList())
        }

        override fun saveFileWithPicker(
            suggestedName: String,
            mime: String,
            onResult: (com.selkies.client.platform.PickedSaveTarget?) -> Unit
        ) { onResult(null) }
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
        override fun appVersion(): String = "probe"
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
