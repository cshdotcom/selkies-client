package com.selkies.client.tools

import com.selkies.client.core.SelkiesSession
import com.selkies.client.model.AppSettings
import com.selkies.client.model.ServerEntry
import com.selkies.client.platform.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * v2.7.5 端到端综合验证（真实服务器）：
 *
 * A. 认证闭环：无凭据 401 → authPromptEvent；错误凭据 → authPromptEvent(rejected)；
 *    applyCredentialsAndReconnect → 自动重连成功（完整「弹窗→输入→回填→重连」链路）
 * B. 渲染停滞看门狗（频繁黑屏根治）：帧在到达但 sink.lastRenderAtMs 停滞 →
 *    自动 REQUEST_KEYFRAME；连续 3 次无改善 → sink.reset() 释放重建解码器
 * C. needsKeyframe 消费：解码端置位 → 会话看门狗请求关键帧
 * D. 剪贴板图像双向往返：sendClipboardImageNow(PNG) → 服务器剪贴板 →
 *    REQUEST_CLIPBOARD → remoteClipboardImage 字节一致
 * E. 分辨率变化非关键帧 → 自动请求关键帧（黑屏修复路径）
 * F. 连接中途切换只读（switchViewOnly → viewer 角色重连成功）
 * G. 麦克风断线重连自动恢复（micWanted → MODE 处理器重启采集）
 * H. 音频计量：DesktopAudioPlayer renderedPcmBytes/opusFramesFed 计数正确
 */
object V275Probe {

    @JvmStatic
    fun main(args: Array<String>) {
        val url = sysProp("probeUrl", "https://workspace-browser.nodebyte.cn")
        val user = sysProp("probeUser", "cshll")
        val pass = sysProp("probePass", "15068253855z")

        println("=== Selkies v2.7.5 端到端综合验证 ===")
        println("服务器: $url")

        var passCount = 0
        var failCount = 0
        fun check(ok: Boolean, label: String, detail: String = "") {
            if (ok) passCount++ else failCount++
            println("${if (ok) "✅" else "❌"} $label${if (detail.isNotEmpty()) " —— $detail" else ""}")
        }

        // ============ A. 认证闭环 ============
        println()
        println("--- A. 认证弹窗闭环（401 → AuthPrompt → 回填重连） ---")
        run {
            val server = ServerEntry(
                id = "auth1", name = "auth1", url = url,
                username = "", password = "",   // 无凭据
                framerate = 30, encoder = "h264enc"
            )
            val settings = AppSettings()
            val platform = V275Platform()
            val session = SelkiesSession(server, platform, { settings })
            session.videoSink = CountingSink()
            session.connect()
            waitFor(20_000) {
                session.status.value == SelkiesSession.Status.FAILED &&
                        session.authPromptEvent.value != null
            }
            val p1 = session.authPromptEvent.value
            check(p1 != null && !p1.rejected, "A1 无凭据 401 → authPromptEvent(rejected=false)",
                p1?.let { "msg=${it.message.take(40)}" } ?: "(未弹出；status=${session.status.value} err=${session.lastError.value})")
            session.disconnect()
            Thread.sleep(400)
        }
        run {
            val server = ServerEntry(
                id = "auth2", name = "auth2", url = url,
                username = user, password = "definitely-wrong-password",
                framerate = 30, encoder = "h264enc"
            )
            val settings = AppSettings()
            val platform = V275Platform()
            val session = SelkiesSession(server, platform, { settings })
            session.videoSink = CountingSink()
            session.connect()
            waitFor(20_000) {
                session.status.value == SelkiesSession.Status.FAILED &&
                        session.authPromptEvent.value != null
            }
            val p2 = session.authPromptEvent.value
            check(p2 != null && p2.rejected, "A2 错误凭据 401 → authPromptEvent(rejected=true)",
                p2?.let { "预填用户名=${it.serverName.isNotBlank()}" } ?: "(未弹出)")
            session.disconnect()
            Thread.sleep(400)
        }
        run {
            val server = ServerEntry(
                id = "auth3", name = "auth3", url = url,
                username = "", password = "",
                framerate = 30, encoder = "h264enc", audioEnabled = true
            )
            val settings = AppSettings()
            val platform = V275Platform()
            val session = SelkiesSession(server, platform, { settings })
            val sink = CountingSink()
            session.videoSink = sink
            session.connect()
            waitFor(20_000) { session.authPromptEvent.value != null }
            check(session.authPromptEvent.value != null, "A3a 认证窗口已触发")
            // 模拟用户在弹窗输入正确凭据并提交
            session.applyCredentialsAndReconnect(user, pass)
            val connected = waitFor(25_000) { session.status.value == SelkiesSession.Status.CONNECTED }
            check(connected, "A3b applyCredentialsAndReconnect → 重连成功",
                if (connected) "server.username=${session.server.username}" else "status=${session.status.value} err=${session.lastError.value}")
            // 凭据已回填到条目
            check(session.server.username == user && session.server.password == pass, "A3c 凭据已回填服务器条目")
            session.disconnect()
            Thread.sleep(400)
        }

        // ============ 主会话（真实凭据） ============
        val server = ServerEntry(
            id = "probe", name = "probe", url = url,
            username = user, password = pass,
            framerate = 30, encoder = "h264enc", audioEnabled = true
        )
        val settings = AppSettings()
        val platform = V275Platform()
        val session = SelkiesSession(server, platform, { settings })
        val goodSink = CountingSink()
        session.videoSink = goodSink
        session.connect()
        val connected = waitFor(25_000) { session.status.value == SelkiesSession.Status.CONNECTED }
        if (!connected) {
            println("❌ 主连接失败: ${session.lastError.value}")
            exitProcess(1)
        }
        println("✅ 主会话已连接")
        val framesBefore = waitFor(20_000) { goodSink.frames.get() > 30 }
        check(framesBefore, "0 视频帧到达基线", "frames=${goodSink.frames.get()}")

        kotlinx.coroutines.runBlocking {
            // ============ B. 渲染停滞看门狗 ============
            println()
            println("--- B. 渲染停滞看门狗（频繁黑屏根治路径） ---")
            val stallSink = StallSink()
            val kfBefore = session.stats.value.keyframeRequests
            session.videoSink = stallSink
            // 帧继续到达（StallSink 计数），渲染时间戳永远停滞（60s 前）
            val kfGrew = waitFor(15_000) { session.stats.value.keyframeRequests > kfBefore }
            check(kfGrew, "B1 帧到达但渲染停滞 → 自动 REQUEST_KEYFRAME",
                "keyframeRequests ${kfBefore} → ${session.stats.value.keyframeRequests}")
            // 连续 3 次无改善 → sink.reset()（解码器释放重建）
            val resetDone = waitFor(30_000) { stallSink.resets.get() >= 1 }
            check(resetDone, "B2 3 次无改善 → sink.reset() 解码器重建", "resets=${stallSink.resets.get()} strikes 限流 5s/次")
            session.videoSink = goodSink
            delay(1500)

            // ============ C. needsKeyframe 消费 ============
            println()
            println("--- C. 解码端主动关键帧请求 ---")
            val kfBeforeC = session.stats.value.keyframeRequests
            goodSink.wantKf = true
            val kfGrewC = waitFor(8_000) { session.stats.value.keyframeRequests > kfBeforeC }
            check(kfGrewC, "C1 sink.needsKeyframe → 会话看门狗请求关键帧",
                "keyframeRequests ${kfBeforeC} → ${session.stats.value.keyframeRequests}")
            check(!goodSink.wantKf, "C2 消费后标志已清除")

            // ============ D. 剪贴板图像双向往返 ============
            println()
            println("--- D. 剪贴板图像往返（cb / clipboard_binary） ---")
            val png = makeTestPng()
            session.remoteClipboardImage.value = null
            session.sendClipboardImageNow(png)
            delay(1500)
            session.remoteClipboard.value = null
            session.requestClipboard()
            val gotImage = waitFor(10_000) { session.remoteClipboardImage.value != null }
            if (gotImage) {
                val back = session.remoteClipboardImage.value!!
                check(back.contentEquals(png), "D1 图像上传→服务器剪贴板→拉取回读 字节一致",
                    "发送 ${png.size}B / 回读 ${back.size}B")
            } else {
                check(false, "D1 图像往返", "REQUEST_CLIPBOARD 后 10s 无图像回读（文本=${session.remoteClipboard.value?.first?.take(20) ?: "无"}）")
            }

            // ============ E. 分辨率变化 → 关键帧 ============
            println()
            println("--- E. 分辨率变化自动关键帧（黑屏修复路径） ---")
            val kfBeforeE = session.stats.value.keyframeRequests
            session.sendResolution(960, 600)
            val resChanged = waitFor(15_000) {
                val r = session.realizedResolution.value
                r != null && (r.first != 1280 && r.second != 720) && r.first == 960
            }
            check(resChanged, "E1 服务器已切换分辨率",
                session.realizedResolution.value?.let { "${it.first}x${it.second}" } ?: "未变")
            // 非关键帧到达的新分辨率帧会触发 requestKeyframe（限流 3s）——观察计数增长
            val kfGrewE = waitFor(12_000) { session.stats.value.keyframeRequests > kfBeforeE }
            check(kfGrewE, "E2 分辨率切换期间关键帧请求已发出",
                "keyframeRequests ${kfBeforeE} → ${session.stats.value.keyframeRequests}")
            session.sendResolution(1280, 720)
            delay(2500)

            // ============ F. 连接中途切换只读 ============
            println()
            println("--- F. 中途切换只读模式（switchViewOnly） ---")
            session.switchViewOnly(true)
            val viewerConnected = waitFor(25_000) { session.status.value == SelkiesSession.Status.CONNECTED }
            check(viewerConnected, "F1 切换到只读 → viewer 角色重连成功",
                if (viewerConnected) "role=${session.role.value}" else "status=${session.status.value}")
            check(session.role.value == "viewer", "F2 角色已为 viewer", "role=${session.role.value}")
            val viewerBase = goodSink.frames.get()
            val viewerFrames = waitFor(15_000) { goodSink.frames.get() > viewerBase + 10 }
            check(viewerFrames, "F3 viewer 视频流恢复", "frames=${goodSink.frames.get()}")
            // 切回控制模式
            session.switchViewOnly(false)
            val backConnected = waitFor(25_000) { session.status.value == SelkiesSession.Status.CONNECTED }
            check(backConnected, "F4 切回控制模式 → 重连成功", "role=${session.role.value}")

            // ============ G. 麦克风断线重连自动恢复 ============
            println()
            println("--- G. 麦克风重连自动恢复（远程硬件保活） ---")
            platform.micStarts.set(0)
            session.startMicUplink()
            delay(600)
            check(platform.micStarts.get() >= 1, "G1 麦克风上行已启动", "starts=${platform.micStarts.get()}")
            check(session.micActive.value, "G2 micActive=true")
            // 触发重连周期（switchViewOnly(false) 走同一重连路径：MODE 到达 → micWanted 自动重启）
            session.switchViewOnly(false)
            val micRestored = waitFor(30_000) {
                session.status.value == SelkiesSession.Status.CONNECTED && platform.micStarts.get() >= 2
            }
            check(micRestored, "G3 重连后麦克风自动恢复",
                "starts=${platform.micStarts.get()} status=${session.status.value}")
            session.stopMicUplink()

            // ============ H. 音频计量（DesktopAudioPlayer） ============
            println()
            println("--- H. 音频渲染计量（renderedPcmBytes / opusFramesFed） ---")
            val player = com.selkies.client.audio.DesktopAudioPlayer()
            val errs = java.util.concurrent.ConcurrentLinkedQueue<String>()
            player.onError = { errs.add(it) }
            val audioFrames = synthOpusFrames()
            if (audioFrames.isEmpty()) {
                check(false, "H0 Opus 测试帧合成（ffmpeg CLI）", "合成 0 帧（宿主机缺 ffmpeg CLI？）")
            } else {
                player.start(48000, 2)
                for (f in audioFrames) {
                    player.playOpus(f)
                    Thread.sleep(8)
                }
                val fedOk = waitFor(5_000) { player.opusFramesFed >= audioFrames.size }
                check(fedOk, "H1 opusFramesFed 计数正确", "fed=${player.opusFramesFed}/${audioFrames.size}")
                val renderedOk = waitFor(5_000) { player.renderedPcmBytes > 0 }
                check(renderedOk, "H2 renderedPcmBytes > 0（解码输出确认）",
                    "bytes=${player.renderedPcmBytes} decoder=${player.decoderDescription}")
                // 无声卡容器属环境限制（错误文案即优雅降级路径）；仅其它错误才算失败
                val realErrs = errs.filter { !it.contains("音频输出") && !it.contains("音频设备") }
                check(realErrs.isEmpty(), "H3 无播放器逻辑错误（容器无声卡属环境限制）",
                    realErrs.joinToString().take(80))
                player.stop()
            }

            println()
            println("=== v2.7.5 验证汇总：PASS=$passCount FAIL=$failCount ===")
            if (failCount > 0) exitProcess(2)
        }

        session.disconnect()
        Thread.sleep(800)
        exitProcess(0)
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            if (cond()) return true
            Thread.sleep(200)
        }
        return cond()
    }

    private fun sysProp(key: String, def: String): String =
        System.getProperty(key)?.takeIf { it.isNotBlank() } ?: def

    /** 64x64 红色 PNG 测试图 */
    private fun makeTestPng(): ByteArray {
        val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(220, 40, 60)
        g.fillRect(0, 0, 64, 64)
        g.color = java.awt.Color(40, 200, 90)
        g.fillOval(16, 16, 32, 32)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    /** FFmpeg CLI 合成 Opus 帧序列（与 AudioSinkProbe 同法） */
    private fun synthOpusFrames(): List<ByteArray> {
        return runCatching {
            val tmp = java.io.File(System.getProperty("java.io.tmpdir"), "v275probe")
            tmp.mkdirs()
            val ogg = java.io.File(tmp, "sine.opus")
            val rc = ProcessBuilder("ffmpeg", "-y", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-t", "1", "-ac", "2", "-c:a", "libopus", "-b:a", "128k", "-f", "ogg", ogg.absolutePath)
                .redirectErrorStream(true).start()
            rc.inputStream.readBytes()
            rc.waitFor()
            if (!ogg.exists() || ogg.length() < 100) return emptyList()
            val data = ogg.readBytes()
            val frames = ArrayList<ByteArray>()
            var i = 0
            while (i + 27 <= data.size) {
                if (data[i] == 'O'.code.toByte() && data[i + 1] == 'g'.code.toByte() &&
                    data[i + 2] == 'g'.code.toByte() && data[i + 3] == 'S'.code.toByte()
                ) {
                    val segCount = data[i + 26].toInt() and 0xFF
                    var packetLen = 0
                    val j = i + 27
                    val segs = data.copyOfRange(j, j + segCount)
                    for (s in segs) packetLen += s.toInt() and 0xFF
                    val payloadStart = j + segCount
                    if (packetLen > 0 && payloadStart + packetLen <= data.size) {
                        var off = payloadStart
                        var cur = ByteArray(0)
                        for (s in segs) {
                            val v = s.toInt() and 0xFF
                            cur += data.copyOfRange(off, off + v)
                            off += v
                            if (v < 255) {
                                if (cur.size > 4) frames.add(cur)
                                cur = ByteArray(0)
                            }
                        }
                    }
                    i = payloadStart + packetLen
                } else i++
            }
            frames.drop(2)
        }.getOrDefault(emptyList())
    }

    /** 普通计数 sink（真实渲染语义） */
    class CountingSink : VideoSink {
        val frames = AtomicInteger(0)
        @Volatile var wantKf = false
        override val firstFrameRendered = MutableStateFlow(false)
        override val lastRenderAtMs: Long get() = renderAt
        @Volatile private var renderAt: Long = 0L
        override val needsKeyframe: Boolean get() = wantKf
        override fun clearNeedsKeyframe() { wantKf = false }
        fun snapshot(): Int = frames.get()
        override fun onVideoFrame(header: VideoFrameHeader, data: ByteArray, offset: Int, length: Int) {
            if (frames.incrementAndGet() == 1) firstFrameRendered.value = true
            renderAt = System.currentTimeMillis()   // 正常渲染：时间戳持续刷新
        }
        override fun onJpegStripe(frameId: Int, stripeY: Int, data: ByteArray, offset: Int, length: Int) {
            if (frames.incrementAndGet() == 1) firstFrameRendered.value = true
            renderAt = System.currentTimeMillis()
        }
        override fun reset() {}
        override fun close() {}
    }

    /** 渲染停滞 sink（模拟解码器挂掉：帧到达但不上屏） */
    class StallSink : VideoSink {
        val frames = AtomicInteger(0)
        val resets = AtomicInteger(0)
        override val firstFrameRendered = MutableStateFlow(true)
        override val lastRenderAtMs: Long get() = System.currentTimeMillis() - 60_000  // 永远停滞
        @Volatile var wantKf = true
        override val needsKeyframe: Boolean get() = wantKf
        override fun clearNeedsKeyframe() { wantKf = false }
        override fun onVideoFrame(header: VideoFrameHeader, data: ByteArray, offset: Int, length: Int) {
            frames.incrementAndGet()
        }
        override fun onJpegStripe(frameId: Int, stripeY: Int, data: ByteArray, offset: Int, length: Int) {
            frames.incrementAndGet()
        }
        override fun reset() { resets.incrementAndGet() }
        override fun close() {}
    }

    class V275Platform : PlatformServices {
        val micStarts = AtomicInteger(0)
        override val platformName = "V275Probe"
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
        override fun saveFileWithPicker(suggestedName: String, mime: String, onResult: (PickedSaveTarget?) -> Unit) { onResult(null) }
        override fun makeVideoSink(): VideoSink = CountingSink()
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
            override fun playOpus(opus: ByteArray) {}
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
        override fun appVersion(): String = "v275probe"
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
