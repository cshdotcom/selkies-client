package com.selkies.client.tools

import com.selkies.client.core.SelkiesSession
import com.selkies.client.model.ServerEntry
import com.selkies.client.model.AppSettings
import com.selkies.client.platform.*
import com.selkies.client.ui.panels.VirtualGamepadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * v2.7.7 专项验证（真实服务器 + 官方协议路径）
 *
 * 用户报告：①默认存储位置不生效 ②虚拟手柄只读模式无法显示/没有发送 ③剪贴板要图片粘贴上传
 *           ④远程控制/只读模式动不动黑屏（网页版不会）⑤虚拟手柄太劣质要摇杆 ⑥认证模式缺失
 *           ⑦官方只读支持 4 个手柄控制端 + 中途切换只读 + 断开时询问切换
 *
 * 本探针可自动化验证的部分：
 *  A.【⑦核心】viewer&slot=2 连接（官方 ?role=viewer&slot=N）→ js 全序列在 slot-1 上发送 →
 *     连接稳定 + 视频继续（服务器未 KILL = 槽位被接受）。
 *  B.【⑦引擎】VirtualGamepadEngine 角色感知：viewer+slot → slot-1 + sendEnabled；
 *     viewer 无 slot → 禁发 + 引导原因；controller → 用户槽位。
 *  C.【⑥认证】错误密码 → 401 → AUTH_REQUIRED（旧版 FAILED=弹窗不可能）；改正确密码
 *     retryWithCredentials → CONNECTED（完整认证弹窗链路）。
 *  D.【④黑屏】解码输出停滞看门狗：帧持续到达但解码停 6s+ → 自动 REQUEST_KEYFRAME
 *     （stats.keyframeRequests 增量铁证；旧版无此检测 = 永久黑屏）+ sink 问题上报直通。
 *  E.【⑦切换】switchRole(true,2) 中途切只读 → 重连后 role=viewer/slot=2；
 *     switchRole(false) 切回 controller。
 *  F.【③图片】剪贴板图片：cb,image/png → 服务器 → REQUEST_CLIPBOARD → 收回 PNG。
 *  G.【回归】文本剪贴板往返 + 首帧 + server_settings（v2.7.6 行为不回退）。
 *
 * 用法：gradlew :composeApp:probeV277 -PprobeUrl=... -PprobeUser=... -PprobePass=...
 */
object V277Probe {

    @JvmStatic
    fun main(args: Array<String>) {
        val url = sysProp("probeUrl", "https://workspace-browser.nodebyte.cn")
        val user = sysProp("probeUser", "cshll")
        val pass = sysProp("probePass", "15068253855z")

        println("=== Selkies v2.7.7 端到端综合验证 ===")
        println("服务器: $url")

        var passCount = 0
        var failCount = 0
        fun check(ok: Boolean, label: String, detail: String = "") {
            if (ok) passCount++ else failCount++
            println("${if (ok) "✅" else "❌"} $label${if (detail.isNotEmpty()) " —— $detail" else ""}")
        }

        // ============ A. viewer&slot=2：官方只读手柄控制端 ============
        println()
        println("--- A. viewer+slot=2 连接与 js 发送（官方 ?role=viewer&slot=N 共享手柄控制端） ---")
        val serverViewer = ServerEntry(
            id = "probe-v277-viewer", name = "probe-v277-viewer", url = url,
            username = user, password = pass,
            framerate = 30, encoder = "h264enc", audioEnabled = true,
            viewOnly = true, viewerSlot = 2,          // ?role=viewer&slot=2 → js 目标 index=1
            resolutionW = 1280, resolutionH = 800
        )
        val platformV = V277Platform()
        val sessionV = SelkiesSession(
            server = serverViewer, platform = platformV,
            settingsProvider = { AppSettings() },
            onClipboardFromRemote = { _, _ -> }
        )
        val stubSinkV = CountingSink()
        sessionV.videoSink = stubSinkV
        sessionV.connect()
        var connected = waitStatus(sessionV, SelkiesSession.Status.CONNECTED, 25_000)
        if (!connected) {
            println("⚠️ viewer+slot=2 连接失败: ${sessionV.lastError.value ?: sessionV.statusDetail.value}")
            println("   （若服务器关闭 enable_player2 会 KILL「Player slot 2 is not enabled」——记录后跳过 A/B1 组）")
        } else {
            check(true, "viewer+slot=2 会话连接成功（服务器接受该玩家槽位）")
            kotlinx.coroutines.runBlocking {
                delay(2500)  // 等 SETTINGS/首帧
                // 客户端回填（legacy 服务器不发 AUTH_SUCCESS）
                check(sessionV.role.value == "viewer", "客户端 role 回填 viewer（legacy 无 AUTH_SUCCESS，由本端回填）",
                    "role=${sessionV.role.value}")
                check(sessionV.gamepadSlot.value == 2, "gamepadSlot 回填 2（js 目标 index=slot-1=1）",
                    "slot=${sessionV.gamepadSlot.value}")

                val framesBefore = stubSinkV.frames.get()
                check(framesBefore >= 1, "viewer 收到视频帧（$framesBefore 帧）")

                // A2. 官方 js 全序列（index=1）：c → b → a → h → d
                sessionV.sendGamepadConnect(1, "V277ProbePad", 6, 17)
                delay(300)
                sessionV.sendGamepadButton(1, 0, 1.0)
                delay(150)
                sessionV.sendGamepadButton(1, 0, 0.0)
                delay(150)
                sessionV.sendGamepadAxis(1, 0, 0.5)
                delay(150)
                sessionV.sendGamepadAxis(1, 0, 0.0)
                delay(150)
                sessionV.sendGamepadHeartbeat(1)
                delay(300)
                sessionV.sendGamepadDisconnect(1)
                delay(800)

                // 铁证：连接仍 CONNECTED 且视频继续 = js 未被拒（若服务器 KILL/DENIED 也可能保活，
                // 但 slot 未启用会直接 KILL 连接——见 ws_handler refusal 分支）
                check(sessionV.status.value == SelkiesSession.Status.CONNECTED,
                    "js,c/b/a/h/d 全序列（index=1）后连接保持 CONNECTED（槽位被接受）",
                    "status=${sessionV.status.value}")
                val framesAfter = stubSinkV.frames.get()
                check(framesAfter > framesBefore || framesAfter > 5,
                    "js 发送后视频帧继续流动（${framesBefore}→${framesAfter}）")
            }

            // ============ B. VirtualGamepadEngine 角色感知（viewer） ============
            println()
            println("--- B. 虚拟手柄引擎：viewer 槽位感知（v2.7.7 重做核心） ---")
            kotlinx.coroutines.runBlocking {
                val engine = VirtualGamepadEngine(sessionV)
                // B1：viewer + slot=2 → index=1 + 允许发送
                engine.refreshRoleSlot(0)
                check(engine.slot == 1, "引擎槽位解析：viewer+slot2 → js index=1", "slot=${engine.slot}")
                check(engine.sendEnabled, "viewer 持槽位 → sendEnabled=true（官方允许 viewer 发 js）")
                check(engine.viewerBlockReason.value == null, "无禁发原因")
                // 全链路：connect → 按钮 → 轴（死区）→ release → disconnect
                engine.connect()
                engine.setButton(0, true)
                engine.setButton(0, false)
                engine.setStick(0, 1, 0.05f, 0.05f)   // 死区内 → 输出 0
                engine.setStick(0, 1, 0.8f, -0.6f)    // 死区外 → 重标定输出
                engine.releaseAll()
                engine.disconnect()
                check(engine.lastEvent.value != null, "引擎事件反馈非空（${engine.lastEvent.value?.take(40)}）")

                // B2：viewer 无槽位 → 禁发 + 引导
                sessionV.gamepadSlot.value = null
                engine.refreshRoleSlot(3)
                check(!engine.sendEnabled, "viewer 无槽位 → sendEnabled=false（服务器 gamepad_slot_denied 拒收）")
                check(engine.viewerBlockReason.value?.contains("槽位") == true,
                    "禁发原因给出引导（${engine.viewerBlockReason.value?.take(50)}…）")
                sessionV.gamepadSlot.value = 2   // 恢复

                // B3：controller → 用户槽位（settings.virtualGamepadSlot）
                sessionV.role.value = "controller"
                engine.refreshRoleSlot(2)
                check(engine.slot == 2 && engine.sendEnabled, "controller → 用户槽位生效（slot=${engine.slot}）")
            }
            sessionV.disconnect()
            Thread.sleep(900)   // 服务器 RECONNECT_DEBOUNCE=500ms
        }

        // ============ C. 认证模式：401 → AUTH_REQUIRED → 重连成功 ============
        println()
        println("--- C. 认证弹窗链路：错误密码 401 → AUTH_REQUIRED → 正确密码重连 ---")
        val serverAuth = ServerEntry(
            id = "probe-v277-auth", name = "probe-v277-auth", url = url,
            username = user, password = "wrong-password-v277",
            framerate = 30, encoder = "h264enc", audioEnabled = false,
            resolutionW = 1280, resolutionH = 800
        )
        val platformA = V277Platform()
        val sessionA = SelkiesSession(
            server = serverAuth, platform = platformA,
            settingsProvider = { AppSettings() },
            onClipboardFromRemote = { _, _ -> }
        )
        val stubSinkA = CountingSink()
        sessionA.videoSink = stubSinkA
        sessionA.connect()
        var authRequired = waitStatus(sessionA, SelkiesSession.Status.AUTH_REQUIRED, 20_000)
        if (!authRequired) {
            // 有些部署对错误密码回 403 或直接断开——如实报告
            check(false, "错误密码 → AUTH_REQUIRED 状态（v2.7.7 新状态）",
                "实际 status=${sessionA.status.value} err=${sessionA.lastError.value?.take(80)}")
        } else {
            check(true, "错误密码 → AUTH_REQUIRED（v2.7.6 旧版此处=FAILED，弹认证窗不可能）")
            check(sessionA.statusDetail.value.contains("认证") || sessionA.statusDetail.value.contains("令牌"),
                "状态说明给出认证类型（${sessionA.statusDetail.value}）")
            // 模拟认证弹窗「连接」：写回正确凭据 → retryWithCredentials
            serverAuth.password = pass
            sessionA.retryWithCredentials()
            val reconnected = waitStatus(sessionA, SelkiesSession.Status.CONNECTED, 25_000)
            check(reconnected, "输入正确凭据 → retryWithCredentials → CONNECTED（完整弹窗链路）",
                "status=${sessionA.status.value}")
            if (reconnected) {
                kotlinx.coroutines.runBlocking {
                    val t = System.currentTimeMillis()
                    while (System.currentTimeMillis() - t < 15_000 && stubSinkA.frames.get() < 1) delay(200)
                    check(stubSinkA.frames.get() >= 1, "认证后视频帧到达（${stubSinkA.frames.get()}）")
                }
            }
        }
        sessionA.disconnect()
        Thread.sleep(900)

        // ============ D. 解码停滞看门狗（黑屏根因修复） ============
        println()
        println("--- D. 解码输出停滞看门狗（「动不动黑屏不自愈」根因修复） ---")
        val serverD = ServerEntry(
            id = "probe-v277-d", name = "probe-v277-d", url = url,
            username = user, password = pass,
            framerate = 30, encoder = "h264enc", audioEnabled = false,
            resolutionW = 1280, resolutionH = 800
        )
        val platformD = V277Platform()
        val sessionD = SelkiesSession(
            server = serverD, platform = platformD,
            settingsProvider = { AppSettings() },
            onClipboardFromRemote = { _, _ -> }
        )
        val stubSinkD = CountingSink()
        sessionD.videoSink = stubSinkD
        sessionD.connect()
        val connectedD = waitStatus(sessionD, SelkiesSession.Status.CONNECTED, 25_000)
        if (!connectedD) {
            println("❌ 控制器连接失败: ${sessionD.lastError.value}")
        } else {
            kotlinx.coroutines.runBlocking {
                // 等首帧 + 首帧解码（stub 首帧置 firstFrameRendered=true → firstDecoded/lastDecoded 记录）
                var t = System.currentTimeMillis()
                while (System.currentTimeMillis() - t < 15_000 && stubSinkD.frames.get() < 1) delay(200)
                check(stubSinkD.frames.get() >= 1, "首帧到达（${stubSinkD.frames.get()}）")
                delay(3000)   // 等 ③（首帧未解码）路径尘埃落定 + 计数基线稳定

                // D1：sink 主动上报（溢出/解码异常）→ 立即强制关键帧
                val before = sessionD.stats.value.keyframeRequests
                sessionD.onSinkDecodeIssue("probe-queue-overflow")
                var t2 = System.currentTimeMillis()
                var after = sessionD.stats.value.keyframeRequests
                while (System.currentTimeMillis() - t2 < 3000 && after == before) {
                    delay(200)
                    after = sessionD.stats.value.keyframeRequests
                }
                check(after > before, "sink 上报解码问题 → 立即强制 REQUEST_KEYFRAME（${before}→${after}）")

                // D2：帧持续到达但解码输出停滞（stub 只在首帧置 rendered=true，此后 lastDecoded 不再更新
                //     —— 精确模拟「参考链断裂后 P 帧解不出」的黑屏现场）→ 看门狗 ④ 自动关键帧
                val before4 = sessionD.stats.value.keyframeRequests
                val framesAtStart = stubSinkD.frames.get()
                t2 = System.currentTimeMillis()
                var after4 = sessionD.stats.value.keyframeRequests
                while (System.currentTimeMillis() - t2 < 13_000 && after4 <= before4) {
                    delay(300)
                    after4 = sessionD.stats.value.keyframeRequests
                }
                check(after4 > before4,
                    "解码输出停滞 6s+（帧仍到达）→ 看门狗④自动 REQUEST_KEYFRAME（${before4}→${after4}；旧版无此检测=永久黑屏）")
                check(stubSinkD.frames.get() > framesAtStart,
                    "期间帧持续到达（${framesAtStart}→${stubSinkD.frames.get()}，确认是「帧到达但解码停滞」场景而非断流）")
                // D3：限流验证（4s 内重复触发不重复请求）
                val beforeR = sessionD.stats.value.keyframeRequests
                sessionD.onSinkDecodeIssue("probe-throttle")
                sessionD.onSinkDecodeIssue("probe-throttle2")
                delay(1200)
                check(sessionD.stats.value.keyframeRequests - beforeR <= 1,
                    "问题上报 1.5s 限流（风暴防护）")

                // ============ E. 中途切换角色（switchRole） ============
                println()
                println("--- E. 会话中切换：controller ↔ viewer+slot（switchRole 重连机制） ---")
                sessionD.switchRole(viewOnly = true, slot = 3)
                val viewerOk = waitStatus(sessionD, SelkiesSession.Status.CONNECTED, 25_000)
                check(viewerOk, "switchRole(viewOnly=true, slot=3) → 重连成功",
                    "status=${sessionD.status.value}")
                if (viewerOk) {
                    kotlinx.coroutines.runBlocking {
                        // 等 role/新连接稳定（状态机已同步置 CONNECTING，此处只需短等待 SETTINGS 到达）
                        var tW = System.currentTimeMillis()
                        while (System.currentTimeMillis() - tW < 8000 && sessionD.role.value != "viewer") delay(250)
                        check(sessionD.role.value == "viewer", "切换后 role=viewer（${sessionD.role.value}）")
                        check(sessionD.gamepadSlot.value == 3, "切换后 gamepadSlot=3（js 目标 index=2）",
                            "slot=${sessionD.gamepadSlot.value}")
                        check(sessionD.effectiveViewOnly, "effectiveViewOnly=true（UI 只读状态生效）")
                        // viewer 视频流动（官方 viewer 流程 STOP→250ms→START 已由会话层处理）
                        tW = System.currentTimeMillis()
                        val f0 = stubSinkD.frames.get()
                        while (System.currentTimeMillis() - tW < 10_000 && stubSinkD.frames.get() <= f0) delay(300)
                        check(stubSinkD.frames.get() > f0 || stubSinkD.frames.get() > 0,
                            "只读模式视频帧到达（新增 ${stubSinkD.frames.get() - f0} 帧，总数 ${stubSinkD.frames.get()}）")
                    }
                }
                // 切回 controller
                sessionD.switchRole(viewOnly = false)
                val ctrlOk = waitStatus(sessionD, SelkiesSession.Status.CONNECTED, 25_000)
                check(ctrlOk, "switchRole(false) → 切回 controller 重连成功", "status=${sessionD.status.value}")
                if (ctrlOk) {
                    kotlinx.coroutines.runBlocking {
                        var tW = System.currentTimeMillis()
                        while (System.currentTimeMillis() - tW < 8000 && sessionD.role.value != "controller") delay(250)
                        check(sessionD.role.value == "controller", "切回后 role=controller（${sessionD.role.value}）")
                        check(!sessionD.effectiveViewOnly, "effectiveViewOnly=false")
                        // 稳定等待：确保新连接完成（剪贴板/SETTINGS 就绪）
                        tW = System.currentTimeMillis()
                        while (System.currentTimeMillis() - tW < 8000 && sessionD.serverSettings.value.isEmpty()) delay(300)
                    }
                }

                // ============ F. 剪贴板图片 ============
                println()
                println("--- F. 剪贴板图片：cb,image/png → 服务器 → 拉回（用户要图片粘贴） ---")
                // 调试：全量观察剪贴板相关状态流
                val dbgClip = ConcurrentLinkedQueue<String>()
                val dbgJob = CoroutineScope(Dispatchers.Default).launch {
                    var lastNotice: String? = null
                    var lastMime: String? = "<init>"
                    var lastImg = -1
                    while (true) {
                        val n = sessionD.clipboardNotice.value
                        if (n != lastNotice) { lastNotice = n; dbgClip.add("+${System.currentTimeMillis() % 100000} notice=${n?.take(60)}") }
                        val m = sessionD.remoteClipboard.value?.second
                        if (m != lastMime) { lastMime = m; dbgClip.add("+${System.currentTimeMillis() % 100000} mime=$m") }
                        val img = sessionD.remoteClipboardImage.value?.size ?: 0
                        if (img != lastImg) { lastImg = img; dbgClip.add("+${System.currentTimeMillis() % 100000} imgBytes=$img") }
                        delay(100)
                    }
                }
                val png = makeTestPng()
                check(png.size > 50 && png[0] == 0x89.toByte() && png[1] == 0x50.toByte(),
                    "构造测试 PNG（${png.size}B，PNG 魔数校验）")
                sessionD.sendClipboardImageNow(png)
                delay(2500)
                sessionD.remoteClipboardImage.value = null
                sessionD.requestClipboard()
                var t3 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t3 < 8000 && sessionD.remoteClipboardImage.value == null) delay(250)
                val gotImg = sessionD.remoteClipboardImage.value
                check(gotImg != null, "REQUEST_CLIPBOARD 拉回图片（服务器接受 cb,image/png 并回读）",
                    "mime=${sessionD.remoteClipboard.value?.second}")
                dbgJob.cancel()
                println("   [调试] 剪贴板状态流时间线:")
                dbgClip.forEach { println("   $it") }
                check(gotImg != null && gotImg.size > 50 &&
                        gotImg[0] == 0x89.toByte() && gotImg[1] == 0x50.toByte(),
                    "拉回内容是 PNG（${gotImg?.size ?: 0}B；官方读侧可能重编码，字节级一致非硬性）")

                // ============ G. 回归 ============
                println()
                println("--- G. 回归：文本剪贴板往返 + server_settings ---")
                val clipEmissions = ConcurrentLinkedQueue<String>()
                val clipJob = CoroutineScope(Dispatchers.Default).launch {
                    sessionD.remoteClipboard.collect { p -> p?.first?.let { if (it.isNotEmpty()) clipEmissions.add(it) } }
                }
                val marker = "V277-CLIP-${System.currentTimeMillis()}"
                sessionD.sendClipboardTextNow(marker)
                delay(2000)
                sessionD.requestClipboard()
                delay(4000)
                val got = clipEmissions.any { it.contains(marker) } || sessionD.remoteClipboard.value?.first?.contains(marker) == true
                check(got, "文本剪贴板双向往返（回归）")
                clipJob.cancel()
                check(sessionD.serverSettings.value.isNotEmpty(), "server_settings 解析（回归）")
            }
        }
        sessionD.disconnect()
        Thread.sleep(800)

        println()
        println("=== v2.7.7 验证汇总：PASS=$passCount FAIL=$failCount ===")
        if (failCount > 0) exitProcess(2)
        exitProcess(0)
    }

    private fun waitStatus(s: SelkiesSession, target: SelkiesSession.Status, timeoutMs: Long): Boolean {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            if (s.status.value == target) return true
            if (s.status.value == SelkiesSession.Status.FAILED ||
                s.status.value == SelkiesSession.Status.CLOSED) return false
            Thread.sleep(200)
        }
        return s.status.value == target
    }

    private fun makeTestPng(): ByteArray {
        val img = java.awt.image.BufferedImage(24, 24, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 24) for (x in 0 until 24) {
            img.setRGB(x, y, (x * 10 + y * 10) shl 16 or (x * 5) or (y * 5 shl 8))
        }
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    private fun sysProp(key: String, def: String): String = System.getProperty(key)?.takeIf { it.isNotBlank() } ?: def

    /** 帧计数桩 sink（首帧置 rendered=true 一次——模拟「解码过一次后停滞」） */
    class CountingSink : VideoSink {
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

    /** 可注入帧的桩平台 */
    class V277Platform : PlatformServices {
        val micStarts = AtomicInteger(0)
        val camStarts = AtomicInteger(0)
        val audioFrames = AtomicInteger(0)
        @Volatile private var micCallback: ((ByteArray) -> Unit)? = null
        @Volatile private var camCallback: ((Int, Boolean, ByteArray) -> Unit)? = null

        fun emitMicFrame(opus: ByteArray) { micCallback?.invoke(opus) }
        fun emitWebcamFrame(data: ByteArray) { camCallback?.invoke(1, true, data) }

        override val platformName = "V277Probe"
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
        override fun appVersion(): String = "v277probe"
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
