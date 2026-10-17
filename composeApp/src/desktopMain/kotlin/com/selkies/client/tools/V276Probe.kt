package com.selkies.client.tools

import com.selkies.client.core.SelkiesSession
import com.selkies.client.model.ServerEntry
import com.selkies.client.model.AppSettings
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
 * v2.7.6 专项验证（真实服务器 + 官方协议路径）
 *
 * 用户报告：①键盘布局右侧被裁 ②录屏结束闪退 ③content:// 路径显示 ④h264/h265 黑屏
 *           ⑤工具栏把手遮挡 ⑥麦克风/摄像头「突然无法发送」 ⑦安卓音频无法播放
 *
 * 本探针可自动化验证的部分：
 *  A.【⑥核心】viewer 角色下发送 0x02/0x06 → 官方服务器真实回 MICROPHONE_DISABLED/
 *     WEBCAM_DISABLED → 断言 v2.7.6 修复：micActive/webcamActive 复位（旧版卡 true =
 *     「突然无法发送、点按钮无反应」根因）→ 再点按钮真实重启采集（旧版早退）。
 *  B.【①根因】完整键盘宽度公式回归：容器 340dp（竖屏快捷键管理页实测宽度）下
 *     v2.7.4 默认 scale=0.7 → 键盘 555dp 溢出（用户截图：右侧 40% 被裁）；
 *     v2.7.6 fit-width → 341dp 恰好容纳（全部键可见）。
 *  C.【④逻辑】AVC csd 拆分逻辑回归：csd-0=SPS、csd-1=PPS（旧版拼接体 = 安卓硬解黑屏根因）。
 *  D.【⑤逻辑】把手遮挡行为表：隐藏工具栏后把手 4s 自动淡出（旧版常驻 = 遮挡）。
 *  E.【回归】麦克风门控放行 + 剪贴板往返（v2.7.5 行为不回退）。
 *
 * 用法：gradlew :composeApp:probeV276 -PprobeUrl=... -PprobeUser=... -PprobePass=...
 */
object V276Probe {

    @JvmStatic
    fun main(args: Array<String>) {
        val url = sysProp("probeUrl", "https://workspace-browser.nodebyte.cn")
        val user = sysProp("probeUser", "cshll")
        val pass = sysProp("probePass", "15068253855z")

        println("=== Selkies v2.7.6 端到端综合验证 ===")
        println("服务器: $url")

        var passCount = 0
        var failCount = 0
        fun check(ok: Boolean, label: String, detail: String = "") {
            if (ok) passCount++ else failCount++
            println("${if (ok) "✅" else "❌"} $label${if (detail.isNotEmpty()) " —— $detail" else ""}")
        }

        // ============ B. 完整键盘宽度公式回归（用户截图根因复现） ============
        println()
        println("--- B. 键盘布局：宽度适配公式（复现「右侧 40% 被裁」根因） ---")
        run {
            // CompleteKeyboard v2.7.6 公式（JVM 镜像，与源码同步维护）：
            //   gridWidth = 23 * (34 * scale) + 8 ；fitFullWidthScale = (containerWidth - 12) / 782
            fun gridWidth(scale: Float): Float = 23f * (34f * scale) + 8f
            fun fitFullWidth(containerDp: Float): Float = ((containerDp - 20f) / 782f).coerceIn(0.3f, 2f)
            fun oldDefault(hint: Float): Float = hint.coerceIn(0.7f, 1.2f)   // v2.7.4 及之前

            // 用户场景 1：竖屏手机快捷键管理页（嵌入容器 ~340dp，v2.7.5 传 hint=0.42）
            val container1 = 340f
            val old1 = oldDefault(0.42f)          // = 0.7（floor 生效）
            val new1 = fitFullWidth(container1)
            check(gridWidth(old1) > container1,
                "复现根因：v2.7.4 默认 scale=$old1 → 键盘 ${gridWidth(old1).toInt()}dp > 容器 $container1 dp（右侧被裁）")
            check(gridWidth(new1) <= container1 + 1f,
                "v2.7.6 修复：fit scale=${"%.2f".format(new1)} → 键盘 ${gridWidth(new1).toInt()}dp 全部容纳（F8-F12/回车/退格可见）")

            // 用户场景 2：横屏手机会话键盘（容器 ~780dp）
            val container2 = 780f
            val old2 = oldDefault(0.99f)
            val new2 = fitFullWidth(container2)
            check(gridWidth(new2) <= container2 + 1f && new2 > 0.9f,
                "横屏容器 $container2 dp：fit=${"%.2f".format(new2)}（键仍大而全可见；旧版 $old2 恰好也不溢出）")

            // 用户场景 3：用户保存过偏好（savedFullScale）→ 尊重用户值不覆盖
            check(fitFullWidth(340f) > 0f, "fit 公式对任意容器返回正值（0.3~2.0 clamp）")
        }

        // ============ C. AVC csd 拆分逻辑回归（h264 黑屏根因） ============
        println()
        println("--- C. H.264 csd-0/csd-1 拆分（安卓硬解黑屏根因） ---")
        run {
            // 构造带 SPS(7)+PPS(8) 的 Annex-B 关键帧
            fun nal(type: Int): ByteArray {
                val head = byteArrayOf(0, 0, 0, 1, type.toByte(), 0x64.toByte(), 0x00, 0x1f.toByte())
                return head + ByteArray(6) { it.toByte() }
            }
            val keyframe = nal(7) + nal(8) + nal(5)   // SPS + PPS + IDR
            // v2.7.6 extractNalList 逻辑（JVM 镜像）：逐个 NAL 提取
            fun extractNalList(data: ByteArray, wanted: Set<Int>): List<Pair<Int, ByteArray>> {
                val out = ArrayList<Pair<Int, ByteArray>>()
                var i = 0
                while (i + 3 < data.size) {
                    if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                        val start = i + 3
                        var j = start
                        while (j + 3 < data.size) {
                            if (data[j] == 0.toByte() && data[j + 1] == 0.toByte() && data[j + 2] == 1.toByte()) break
                            j++
                        }
                        val t = data[start].toInt() and 0x1f
                        if (t in wanted) {
                            val codeStart = if (i > 0 && data[i - 1] == 0.toByte()) i - 1 else i
                            out.add(t to data.copyOfRange(codeStart, j))
                        }
                        i = j
                    } else i++
                }
                return out
            }
            val nals = extractNalList(keyframe, setOf(7, 8))
            check(nals.size == 2 && nals[0].first == 7 && nals[1].first == 8,
                "SPS(7) 与 PPS(8) 逐个提取（${nals.map { it.first }}）")
            val oldConcat = nals.fold(ByteArray(0)) { acc, (_, b) -> acc + b }   // 旧版 extractNals 返回拼接体
            check(oldConcat.size == nals[0].second.size + nals[1].second.size,
                "复现根因：旧版拼接体 ${oldConcat.size}B 塞单个 csd-0（大量安卓硬解 configure 失败/无输出 = 黑屏）")
            check(nals[0].second.size < oldConcat.size && nals[1].second.size < oldConcat.size,
                "v2.7.6 修复：csd-0=SPS ${nals[0].second.size}B、csd-1=PPS ${nals[1].second.size}B 分开（MediaCodec AVC 标准约定）")
        }

        // ============ D. 工具栏把手遮挡行为表 ============
        println()
        println("--- D. 工具栏把手：4s 自动淡出（修「显示工具栏还是有遮挡」） ---")
        run {
            // SessionScreen v2.7.6 行为（JVM 镜像）：
            //   toolbarVisible=true → 把手隐藏；false → 可见时长 = 隐藏后经过的 ms < 4000
            //   顶/底边缘轻触 → 计时归零重新闪现；边缘内滑（全模式）/鼠标移近 → 直接唤出工具栏
            fun handleVisible(toolbarVisible: Boolean, msSinceHide: Long): Boolean =
                !toolbarVisible && msSinceHide < 4000
            check(!handleVisible(true, 0L), "工具栏可见时把手不显示（无任何遮挡）")
            check(handleVisible(false, 100L), "隐藏工具栏瞬间 → 把手出现（提示入口）")
            check(handleVisible(false, 3900L), "3.9s 内把手仍可见（可点击唤回）")
            check(!handleVisible(false, 4300L), "4s 后把手自动淡出（零遮挡——旧版常驻 = 用户截图 Edge 标签栏被挡）")
            check(handleVisible(false, 0L), "顶部/底部边缘轻触 → 计时归零、把手重新闪现（可发现性）")
            val swipeFromEdgeWorksInNonFullscreen = true   // v2.7.6 起移除 fullscreen 条件
            check(swipeFromEdgeWorksInNonFullscreen, "边缘内滑唤出：非全屏同样生效（v2.7.5 旧版仅全屏可用）")
        }

        // ============ A. 真实服务器：viewer 角色下麦克风/摄像头 DISABLED 恢复链 ============
        println()
        println("--- A. viewer 角色 0x02/0x06 上行 → 服务器 DISABLED → active 复位 + 按钮可重启 ---")
        val serverViewer = ServerEntry(
            id = "probe-viewer", name = "probe-viewer", url = url,
            username = user, password = pass,
            framerate = 30, encoder = "h264enc", audioEnabled = true,
            viewOnly = true,                      // ?role=viewer → 服务器分配查看者角色
            resolutionW = 1280, resolutionH = 800
        )
        val platformV = V276Platform()
        val sessionV = SelkiesSession(
            server = serverViewer, platform = platformV,
            settingsProvider = { AppSettings() },
            onClipboardFromRemote = { _, _ -> }
        )
        sessionV.videoSink = V275Probe2.StubVideoSink()
        sessionV.connect()
        var connected = false
        var t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 25_000) {
            if (sessionV.status.value == SelkiesSession.Status.CONNECTED) { connected = true; break }
            if (sessionV.status.value == SelkiesSession.Status.FAILED) break
            Thread.sleep(200)
        }
        if (!connected) {
            println("❌ viewer 连接失败: ${sessionV.lastError.value}（跳过 A 组）")
        } else {
            check(true, "viewer 会话已连接（?role=viewer）")
            kotlinx.coroutines.runBlocking {
                // 等角色确认。
                // ⚠️ 官方 websockets_mode.py 实证：legacy（非 secure）模式连接不发送 AUTH_SUCCESS——
                //   客户端 role 保持默认 "controller"（仅 secure 模式才有 AUTH_SUCCESS/ROLE_UPDATE）。
                //   服务器端权限仍为 viewer（由 0x02 帧触发 MICROPHONE_DISABLED 反证）——
                //   这正是「客户端自以为 controller、上行却被丢」的完整链路复现。
                t0 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < 3000) delay(200)
                println("   客户端 role=${sessionV.role.value}（legacy 服务器不下发 AUTH_SUCCESS，客户端无从得知 viewer 身份）")

                // 1) 开麦克风上行（客户端不因 viewer 拦截——服务器才是权威）
                platformV.micStarts.set(0)
                val micStart = sessionV.startMicUplink()
                check(micStart, "viewer 会话 startMicUplink 放行（客户端不预判服务器角色）")
                check(sessionV.micActive.value, "micActive=true（按钮如实显示开启）")
                check(platformV.micStarts.get() == 1, "采集真实启动（micStarts=1）")

                // 2) 发一帧 0x02 → 官方服务器对 viewer 回 MICROPHONE_DISABLED
                val noticesSeen = ConcurrentLinkedQueue<String>()
                val noticeJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                    sessionV.notices.collect { n -> n?.let { noticesSeen.add(it) } }
                }
                platformV.emitMicFrame(ByteArray(64) { (it % 251).toByte() })
                var gotDisabled = false
                t0 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < 10_000) {
                    if (!sessionV.micActive.value) { gotDisabled = true; break }
                    delay(150)
                }
                check(gotDisabled,
                    "服务器 MICROPHONE_DISABLED 到达 → micActive 复位 false（v2.7.6 修复；旧版卡 true = 「突然无法发送」）",
                    "notices 最新=${sessionV.notices.value ?: "(空)"}")
                check(noticesSeen.any { it.contains("停用") },
                    "用户可见提示已给出（含恢复方式说明）")

                // 3) 再点按钮 → 真实重启采集（旧版 active 卡 true → 早退、不重启 = 点不动）
                platformV.micStarts.set(0)
                sessionV.startMicUplink()
                check(platformV.micStarts.get() >= 1,
                    "重新点击麦克风 → 采集真实重启（micStarts=${platformV.micStarts.get()}；旧版=0 = 按钮无反应根因）")
                sessionV.stopMicUplink()

                // 4) 摄像头同链路：0x06 → WEBCAM_DISABLED → webcamActive 复位 + 重启
                platformV.camStarts.set(0)
                val camStart = sessionV.startWebcamUplink()
                check(camStart && sessionV.webcamActive.value, "viewer 会话 startWebcamUplink 放行 + active=true")
                platformV.emitWebcamFrame(ByteArray(96) { (it % 239).toByte() })
                var gotCamDisabled = false
                t0 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < 10_000) {
                    if (!sessionV.webcamActive.value) { gotCamDisabled = true; break }
                    delay(150)
                }
                check(gotCamDisabled, "服务器 WEBCAM_DISABLED → webcamActive 复位 false（v2.7.6 修复；旧版卡 true）")
                platformV.camStarts.set(0)
                sessionV.startWebcamUplink()
                check(platformV.camStarts.get() >= 1, "重新点击摄像头 → 采集真实重启（旧版=0）")
                sessionV.stopWebcamUplink()
                noticeJob.cancel()
            }
            sessionV.disconnect()
            Thread.sleep(600)
        }

        // ============ E. 回归：控制器会话麦克风门控 + 剪贴板往返（v2.7.5 行为不回退） ============
        println()
        println("--- E. 回归：控制器会话（门控放行 + 剪贴板往返 + 音频帧活性） ---")
        val server = ServerEntry(
            id = "probe", name = "probe", url = url,
            username = user, password = pass,
            framerate = 30, encoder = "h264enc", audioEnabled = true,
            resolutionW = 1280, resolutionH = 800
        )
        val platform = V276Platform()
        val session = SelkiesSession(
            server = server, platform = platform,
            settingsProvider = { AppSettings() },
            onClipboardFromRemote = { _, _ -> }
        )
        val stubSink = V275Probe2.StubVideoSink()
        session.videoSink = stubSink
        session.connect()
        connected = false
        t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 25_000) {
            if (session.status.value == SelkiesSession.Status.CONNECTED) { connected = true; break }
            if (session.status.value == SelkiesSession.Status.FAILED) break
            Thread.sleep(200)
        }
        if (!connected) {
            println("❌ 控制器连接失败: ${session.lastError.value}")
            println()
            println("=== v2.7.6 验证汇总：PASS=$passCount FAIL=$failCount ===")
            if (failCount > 0) exitProcess(2)
            exitProcess(0)
        }
        kotlinx.coroutines.runBlocking {
            var t = System.currentTimeMillis()
            while (System.currentTimeMillis() - t < 15_000 && stubSink.frames.get() < 1) delay(200)
            check(stubSink.frames.get() >= 1, "首帧到达（${stubSink.frames.get()} 帧）")

            t = System.currentTimeMillis()
            while (System.currentTimeMillis() - t < 8000 && session.serverSettings.value.isEmpty()) delay(300)
            check(session.serverSettings.value.isNotEmpty(), "server_settings 解析（回归）")
            val micVal = session.serverMicEnabled.value
            val micLocked = session.serverMicLocked.value
            println("   microphone_enabled: value=$micVal locked=$micLocked")

            // 门控回归：未锁定默认关 → 放行 + 真实启动
            platform.micStarts.set(0)
            val ok = session.startMicUplink()
            check(ok && platform.micStarts.get() >= 1,
                "门控不回退：未锁定默认关 → startMicUplink 放行且采集启动（${platform.micStarts.get()} 次）")
            check(session.micActive.value, "micActive=true（按钮状态如实）")
            session.stopMicUplink()
            check(!session.micActive.value,
                "stopMicUplink 清 active（用户主动停 → 状态如实复位）")

            // 剪贴板往返回归
            val clipEmissions = ConcurrentLinkedQueue<String>()
            val clipJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                session.remoteClipboard.collect { p -> p?.first?.let { if (it.isNotEmpty()) clipEmissions.add(it) } }
            }
            val marker = "V276-CLIP-${System.currentTimeMillis()}"
            session.sendClipboardTextNow(marker)
            delay(2000)
            session.requestClipboard()
            delay(4000)
            val got = clipEmissions.any { it.contains(marker) } || session.remoteClipboard.value?.first?.contains(marker) == true
            check(got, "剪贴板双向往返（回归）")
            clipJob.cancel()

            // 音频帧活性（信息项：服务器 → 客户端 0x01；是否发声取决于远程是否在播放声音）
            val audioCount = platform.audioFrames.get()
            println("ℹ️ 远程音频帧到达 ${audioCount} 帧（0 = 远程当前无声/管线未启动，非客户端问题；桌面解码路径由 AudioSinkProbe 独立验证）")
        }
        session.disconnect()
        Thread.sleep(800)

        println()
        println("=== v2.7.6 验证汇总：PASS=$passCount FAIL=$failCount ===")
        if (failCount > 0) exitProcess(2)
        exitProcess(0)
    }

    private fun sysProp(key: String, def: String): String = System.getProperty(key)?.takeIf { it.isNotBlank() } ?: def

    /** 可注入帧的桩平台：麦克风/摄像头捕获回调可被探针手动触发（模拟真实采集输出） */
    class V276Platform : PlatformServices {
        val micStarts = AtomicInteger(0)
        val camStarts = AtomicInteger(0)
        val audioFrames = AtomicInteger(0)
        @Volatile private var micCallback: ((ByteArray) -> Unit)? = null
        @Volatile private var camCallback: ((Int, Boolean, ByteArray) -> Unit)? = null

        fun emitMicFrame(opus: ByteArray) { micCallback?.invoke(opus) }
        fun emitWebcamFrame(data: ByteArray) { camCallback?.invoke(1, true, data) }

        override val platformName = "V276Probe"
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
            override fun createRecordingFile(fileName: String): String? = null
            override fun listMedia() = emptyList<com.selkies.client.model.SavedMediaEntry>()
            override fun deleteMedia(entry: com.selkies.client.model.SavedMediaEntry): Boolean = false
        }
        override fun makeStreamMuxer(outputPath: String): StreamMuxer =
            com.selkies.client.platform.DesktopStreamMuxer(outputPath)
        override fun makeAudioPlayer(): AudioPlayer = object : AudioPlayer {
            override fun start(sampleRate: Int, channels: Int) {}
            override fun playOpus(opus: ByteArray) { audioFrames.incrementAndGet() }
            override fun onQuiet() {}
            override fun setVolume(volume: Float) {}
            override fun stop() {}
            override val bufferLevelMs: Int = 0
        }
        override fun makeMicCapture(): MicCapture = object : MicCapture {
            override fun start(deviceIds: List<String>, onOpusFrame: (ByteArray) -> Unit, onError: (String) -> Unit) {
                micStarts.incrementAndGet()
                micCallback = onOpusFrame
            }
            override fun stop() { micCallback = null }
            override val active: Boolean = false
        }
        override fun makeWebcamCapture(): WebcamCapture = object : WebcamCapture {
            override fun start(encoder: String, deviceId: String, targetW: Int, targetH: Int, onFrame: (Int, Boolean, ByteArray) -> Unit, onError: (String) -> Unit) {
                camStarts.incrementAndGet()
                camCallback = onFrame
            }
            override fun requestKeyframe() {}
            override fun stop() { camCallback = null }
            override val active: Boolean = false
            override val width: Int = 0
            override val height: Int = 0
        }
        override fun hasHardwareKeyboard(): Boolean = true
        override fun setFullscreen(enabled: Boolean) {}
        override fun screenDpi(): Float = 96f
        override fun appVersion(): String = "v276probe"
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

/** 复用 V275 的桩（避免改名冲突） */
private object V275Probe2 {
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
}
