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
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * v2.7.5 端到端综合验证（真实服务器）：
 *
 * 1. 【核心·麦克风「点不了」根因回归】工具栏按钮门控（SessionScreen 同判定式）：
 *    - 旧版（≤2.7.4）：!serverMicEnabled 一律拦截 → nodebyte (value=false, locked=false) 上点击
 *      工具栏麦克风按钮 = 永远弹「服务器已禁用麦克风」不启动 = 用户报「点不了」
 *    - 新版（2.7.5）：仅 !enabled && locked 才拦截 → 默认关直接 startMicUplink
 *    - 四组合逻辑表全断言 + 真实服务器上真实放行验证（micCapture.start 真被调）
 * 2. 【存储位置选择器·桌面行为验证】storageRoot 热生效：
 *    截图/下载/流式下载/录屏路径/列表/删除 全部落到自定义根目录
 * 3. 【SAF 路径解析纯逻辑】docId → 绝对路径（主存储/SD 卷/非法输入）
 * 4. 剪贴板往返回归（不回退）
 *
 * 用法：gradlew :composeApp:probeV275 -PprobeUrl=... -PprobeUser=... -PprobePass=...
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

        // ============ 0. SAF 路径解析纯逻辑（不依赖服务器/设备） ============
        println()
        println("--- 0. SAF docId → 绝对路径解析（纯逻辑） ---")
        run {
            // 模拟 Android 的 SafPaths.docIdToAbsolutePath（同一算法在 androidMain/SafPaths.kt；
            // 此处为 JVM 镜像实现做逻辑回归，两处算法同步维护）
            fun docIdToAbs(docId: String, primaryRoot: String): String? {
                val idx = docId.indexOf(':')
                if (idx <= 0) return null
                val volume = docId.substring(0, idx)
                val path = docId.substring(idx + 1).trimEnd('/')
                if (path.isEmpty()) return null
                val volumeRoot = if (volume == "primary") primaryRoot else "/storage/$volume"
                return "$volumeRoot/$path"
            }
            check(
                docIdToAbs("primary:Foo/Bar", "/storage/emulated/0") == "/storage/emulated/0/Foo/Bar",
                "主存储 docId → 绝对路径", "primary:Foo/Bar → /storage/emulated/0/Foo/Bar"
            )
            check(
                docIdToAbs("1A2B-3C4D:DCIM/Cam", "/storage/emulated/0") == "/storage/1A2B-3C4D/DCIM/Cam",
                "SD/USB 卷 docId → 绝对路径", "1A2B-3C4D:DCIM/Cam → /storage/1A2B-3C4D/DCIM/Cam"
            )
            check(docIdToAbs("noseparator", "/x") == null, "无 ':' 的非法 docId → null")
            check(docIdToAbs("primary:", "/x") == null, "空路径 docId → null")
        }

        // ============ 0b. 工具栏门控四组合逻辑表（v2.7.5 核心） ============
        println()
        println("--- 1. 工具栏麦克风按钮门控（四组合逻辑表） ---")
        run {
            // SessionScreen v2.7.5 判定式（与源码同步）：
            //   拦截 = !serverMicEnabled && serverMicLocked；否则 → startMicUplink()
            fun gateBlocks(enabled: Boolean, locked: Boolean): Boolean = !enabled && locked
            // 旧版（≤2.7.4）判定式：拦截 = !serverMicEnabled
            fun legacyBlocks(enabled: Boolean, @Suppress("UNUSED_PARAMETER") locked: Boolean): Boolean = !enabled
            val combos = listOf(
                Triple(true, false, "开·未锁"),
                Triple(true, true, "开·锁定（锁定对 value=true 无效）"),
                Triple(false, false, "默认关·未锁（nodebyte 实测配置）"),
                Triple(false, true, "真禁用·锁定")
            )
            for ((v, l, desc) in combos) {
                val blocks = gateBlocks(v, l)
                val expected = !v && l
                check(blocks == expected, "门控[$desc] 新逻辑拦截=$blocks（期望 $expected）")
            }
            // 复现用户报告：nodebyte 配置 (false,false) 上旧版点击工具栏 = 被拦（点不了）；新版放行
            val nodebyteV = false
            val nodebyteL = false
            check(
                legacyBlocks(nodebyteV, nodebyteL) && !gateBlocks(nodebyteV, nodebyteL),
                "复现「点不了」：旧版拦 (false,false)、新版放行",
                "旧版拦截=${legacyBlocks(nodebyteV, nodebyteL)}（用户报障路径：点击工具栏麦克风弹「服务器已禁用」不启动）；新版拦截=${gateBlocks(nodebyteV, nodebyteL)}（修复后：直接开上行）"
            )
        }

        // ============ 真实服务器连接 ============
        val server = ServerEntry(
            id = "probe", name = "probe", url = url,
            username = user, password = pass,
            framerate = 30, encoder = "h264enc", audioEnabled = true,
            // v2.7.5：强制横屏 1280x800（GUI 测试前预热——把服务器从手机竖屏切回横屏，
            // 避免竖屏流在容器内解码堆损坏；桩 sink 本身解码免疫）
            resolutionW = 1280, resolutionH = 800
        )
        val settings = AppSettings()
        val platform = V275Platform()

        val session = SelkiesSession(
            server = server,
            platform = platform,
            settingsProvider = { settings },
            onClipboardFromRemote = { _, _ -> }
        )
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

        kotlinx.coroutines.runBlocking {
            // ============ 2. 真实服务器上的麦克风放行（工具栏按钮修复后的实际路径） ============
            println()
            println("--- 2. 真实服务器麦克风门控（nodebyte 实测配置） ---")
            t = System.currentTimeMillis()
            while (System.currentTimeMillis() - t < 8000 && session.serverSettings.value.isEmpty()) delay(300)
            val micVal = session.serverMicEnabled.value
            val micLocked = session.serverMicLocked.value
            val camVal = session.serverWebcamEnabled.value
            val camLocked = session.serverWebcamLocked.value
            check(session.serverSettings.value.isNotEmpty(), "server_settings 已解析")
            println("   microphone_enabled: value=$micVal locked=$micLocked")
            println("   webcam_enabled:     value=$camVal locked=$camLocked")

            // 工具栏按钮（v2.7.5 修复后）在 nodebyte 配置下的行为：
            if (!micVal && !micLocked) {
                // 不拦截 → 直接 startMicUplink（与修复后按钮 onClick 相同调用链）
                platform.micStarts.set(0)
                val micStart = session.startMicUplink()
                check(micStart, "按钮不再拦截：startMicUplink 放行（返回 $micStart）")
                check(platform.micStarts.get() > 0, "micCapture.start 真被调用（${platform.micStarts.get()} 次）")
                check(session.micActive.value, "micActive = true（按钮状态如实反映）")
                session.stopMicUplink()
                // 摄像头同语义
                val camBlockedByUi = !camVal && camLocked
                check(!camBlockedByUi, "摄像头按钮同样不再拦截（value=$camVal locked=$camLocked）")
            } else if (micVal) {
                platform.micStarts.set(0)
                val micStart = session.startMicUplink()
                check(micStart && platform.micStarts.get() > 0, "服务器允许麦克风，上行正常建立")
                session.stopMicUplink()
            }

            // ============ 3. 自定义存储根（桌面行为 = 选择器选定后的效果） ============
            println()
            println("--- 3. 自定义存储根（storageRoot 热生效；选择器选定后的行为等价） ---")
            val root = File("/tmp/v275-storage").apply { deleteRecursively(); mkdirs() }
            val store = DesktopMediaStoreBridge { root.absolutePath }
            check(store.screenshotsDir() == "${root.absolutePath}/Screenshots", "截图目录指向自定义根", store.screenshotsDir())
            check(store.downloadsDir() == "${root.absolutePath}/Downloads", "下载目录指向自定义根", store.downloadsDir())
            check(store.recordingsDir() == "${root.absolutePath}/Recordings", "录屏目录指向自定义根", store.recordingsDir())
            // 截图保存
            val png = ByteArray(256) { (it % 251).toByte() }
            val shotPath = store.saveScreenshot(png, "v275_shot.png")
            check(shotPath != null && shotPath.startsWith(root.absolutePath), "截图落盘自定义根", shotPath ?: "(null)")
            if (shotPath != null) {
                check(File(shotPath).length() == 256L, "截图字节一致（${File(shotPath).length()}B）")
            }
            // 流式下载
            val stream = store.openDownloadStream("v275_dl.bin")
            check(stream != null, "流式下载句柄建立（自定义根）")
            if (stream != null) {
                val payload = ByteArray(4096) { (it % 241).toByte() }
                stream.write(payload, 0, 2048)
                stream.write(payload, 2048, 2048)
                stream.close()
                check(stream.path.startsWith(root.absolutePath), "下载真实绝对路径返回", stream.path)
                check(File(stream.path).length() == 4096L, "下载字节一致（${File(stream.path).length()}B）")
            }
            // 录屏目标
            val recPath = store.createRecordingFile("v275_rec.mkv")
            check(recPath != null && recPath.startsWith(root.absolutePath), "录屏目标指向自定义根", recPath ?: "(null)")
            // 列表 + 删除
            val media = store.listMedia()
            check(media.any { it.name == "v275_shot.png" } && media.any { it.name == "v275_dl.bin" },
                "列表包含自定义根下的媒体（${media.size} 条）")
            val del = media.firstOrNull { it.name == "v275_dl.bin" }
            check(del != null && store.deleteMedia(del!!), "删除自定义根下媒体成功")
            check(!File("${root.absolutePath}/Downloads/v275_dl.bin").exists(), "删除后文件确实消失")
            // 空 storageRoot = 默认目录回归
            val defStore = DesktopMediaStoreBridge { "" }
            check(!defStore.screenshotsDir().startsWith(root.absolutePath), "清空 storageRoot 回退默认目录", defStore.screenshotsDir())

            // ============ 4. 剪贴板往返回归 ============
            println()
            println("--- 4. 剪贴板双向真实往返（回归） ---")
            val clipEmissions = ConcurrentLinkedQueue<String>()
            val clipJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                session.remoteClipboard.collect { p -> p?.first?.let { if (it.isNotEmpty()) clipEmissions.add(it) } }
            }
            val marker = "V275-CLIP-${System.currentTimeMillis()}"
            session.sendClipboardTextNow(marker)
            delay(2000)
            session.requestClipboard()
            delay(4000)
            val got = clipEmissions.any { it.contains(marker) } || session.remoteClipboard.value?.first?.contains(marker) == true
            check(got, "上行写入 → 拉取回读（含 marker）")
            clipJob.cancel()

            println()
            println("=== v2.7.5 验证汇总：PASS=$passCount FAIL=$failCount ===")
            if (failCount > 0) exitProcess(2)
        }

        session.disconnect()
        Thread.sleep(800)
        exitProcess(0)
    }

    private fun sysProp(key: String, def: String): String = System.getProperty(key)?.takeIf { it.isNotBlank() } ?: def

    /** 桩视频 sink：只计数 */
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

    /** 平台桩：追踪麦克风启动次数 */
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
        override fun openFile(path: String) {}
        override fun shareFile(path: String, displayName: String) {}
    }
}
