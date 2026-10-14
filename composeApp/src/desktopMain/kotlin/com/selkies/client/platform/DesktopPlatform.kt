package com.selkies.client.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.KeyEvent
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import javax.print.PrintServiceLookup
import javax.print.attribute.HashPrintRequestAttributeSet
import javax.print.attribute.standard.JobName
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import com.selkies.client.model.HardwareDevice

/**
 * 桌面平台服务实现（Linux/macOS/Windows，x86_64 + ARM64）
 */
object DesktopPlatform : PlatformServices {
    override val platformName = "Desktop (${System.getProperty("os.name")} ${System.getProperty("os.arch")})"

    var windowFrame: java.awt.Frame? = null
    private var fullscreenState = false
    private var savedBounds: java.awt.Rectangle? = null

    override fun configDir(): String {
        val os = System.getProperty("os.name").lowercase()
        val home = System.getProperty("user.home")
        val base = when {
            os.contains("win") -> File(home, "AppData/Roaming/SelkiesClient")
            os.contains("mac") -> File(home, "Library/Application Support/SelkiesClient")
            else -> File(home, ".config/selkies-client")
        }
        base.mkdirs()
        return base.absolutePath
    }

    override fun clipboard(): ClipboardBridge = AwtClipboardBridge

    override fun print(): PrintBridge = DesktopPrintBridge

    override fun vibrate(ms: Long) { /* 桌面无震动 */ }

    override fun openUrl(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(java.net.URI(url))
            }
        }
    }

    override fun pickFiles(mimeTypes: List<String>, onResult: (List<PickedFile>) -> Unit) {
        Thread {
            runCatching {
                val chooser = JFileChooser()
                chooser.isMultiSelectionEnabled = true
                chooser.fileSelectionMode = JFileChooser.FILES_ONLY
                chooser.dialogTitle = "选择要上传的文件"
                val r = chooser.showOpenDialog(windowFrame)
                if (r == JFileChooser.APPROVE_OPTION) {
                    onResult(chooser.selectedFiles.map {
                        PickedFile(it.name, it.absolutePath, it.length())
                    })
                } else {
                    onResult(emptyList())
                }
            }.onFailure { onResult(emptyList()) }
        }.apply { isDaemon = true }.start()
    }

    override fun readFileBytes(path: String): ByteArray? = runCatching {
        File(path).takeIf { it.exists() }?.readBytes()
    }.getOrNull()

    override fun openFile(path: String) {
        runCatching {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(File(path))
            }
        }
    }

    override fun shareFile(path: String, displayName: String) {
        // 桌面无系统分享面板：用 xdg-open 打开所在目录并选中
        runCatching {
            val dir = File(path).parentFile ?: return
            if (System.getProperty("os.name").lowercase().contains("win")) {
                Runtime.getRuntime().exec(arrayOf("explorer", "/select,", path))
            } else {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(dir)
                }
            }
        }
    }

    override fun exitApp() {
        kotlin.system.exitProcess(0)
    }

    override fun saveFile(suggestedName: String, bytes: ByteArray, onDone: (Boolean) -> Unit) {
        Thread {
            runCatching {
                val chooser = JFileChooser()
                chooser.dialogTitle = "保存文件"
                chooser.selectedFile = File(suggestedName)
                val r = chooser.showSaveDialog(windowFrame)
                if (r == JFileChooser.APPROVE_OPTION) {
                    chooser.selectedFile.writeBytes(bytes)
                    onDone(true)
                } else onDone(false)
            }.onFailure { onDone(false) }
        }.apply { isDaemon = true }.start()
    }

    override fun makeAudioPlayer(): AudioPlayer = com.selkies.client.audio.DesktopAudioPlayer()

    override fun makeMicCapture(): MicCapture = com.selkies.client.audio.DesktopMicCapture()

    override fun makeWebcamCapture(): WebcamCapture = com.selkies.client.webcam.DesktopWebcamCapture()

    override fun hasHardwareKeyboard(): Boolean = true

    override fun setFullscreen(enabled: Boolean) {
        SwingUtilities.invokeLater {
            val frame = windowFrame ?: return@invokeLater
            runCatching {
                if (enabled && !fullscreenState) {
                    savedBounds = frame.bounds
                    frame.isVisible = false
                    frame.isUndecorated = true
                    frame.bounds = java.awt.GraphicsEnvironment
                        .getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
                    frame.isVisible = true
                    fullscreenState = true
                } else if (!enabled && fullscreenState) {
                    frame.isVisible = false
                    frame.isUndecorated = false
                    savedBounds?.let { frame.bounds = it }
                    frame.isVisible = true
                    fullscreenState = false
                }
            }
        }
    }

    override fun screenDpi(): Float = Toolkit.getDefaultToolkit().screenResolution.toFloat()

    override fun appVersion(): String {
        // 优先取 fat JAR 清单里的 Implementation-Version（desktopFatJar 注入 2.1.1），
        // 开发运行（gradle run）无清单时回退内置常量
        return runCatching {
            val cls = DesktopPlatform::class.java
            val v = cls.`package`?.implementationVersion
            if (v.isNullOrBlank()) "2.1.1" else v
        }.getOrDefault("2.1.1")
    }

    override fun showSoftInput(show: Boolean) { /* 桌面无软键盘 */ }

    override fun isTouchDevice(): Boolean = false

    override fun nativeKeyCodeToKeysym(nativeKeyCode: Int, unicodeChar: Int): Int? {
        return AwtKeys.keyCodeToKeysym(nativeKeyCode)
    }

    /** AWT 键盘分发器：桌面物理键直通 */
    @Volatile
    private var physicalKeyHandler: ((Int, Boolean) -> Unit)? = null
    private var keyDispatcherInstalled = false

    override fun setPhysicalKeyHandler(handler: ((Int, Boolean) -> Unit)?) {
        physicalKeyHandler = handler
        if (handler != null && !keyDispatcherInstalled) {
            keyDispatcherInstalled = true
            runCatching {
                java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .addKeyEventDispatcher { e ->
                        val h = physicalKeyHandler ?: return@addKeyEventDispatcher false
                        when (e.id) {
                            java.awt.event.KeyEvent.KEY_PRESSED -> {
                                h(e.keyCode, true)
                                // 不消费：让本地 UI（如文本框）也能收到——仅当无会话时
                                false
                            }
                            java.awt.event.KeyEvent.KEY_RELEASED -> {
                                h(e.keyCode, false)
                                false
                            }
                            else -> false
                        }
                    }
            }
        }
    }

    fun init() {
        // 设置根目录提供者：从全局设置读取（App 启动后由 AppState 注入；这里默认空 = ~/Selkies）
    }

    fun shutdown() {
        // 退出前保存录屏（「退出默认视为保存」；与 JVM shutdown hook 双保险）
        com.selkies.client.core.RecordingShutdownHook.autosave()
    }

    // ---- v2.4.0 硬件选择 ----

    /** 枚举麦克风：Java Sound 中支持 TargetDataLine 的 mixer */
    override fun enumerateMics(): List<HardwareDevice> {
        return runCatching {
            AudioSystem.getMixerInfo().mapIndexedNotNull { idx, info ->
                val mixer = AudioSystem.getMixer(info)
                val ok = runCatching {
                    mixer.isLineSupported(DataLine.Info(TargetDataLine::class.java, javax.sound.sampled.AudioFormat(48000f, 16, 1, true, false)))
                }.getOrDefault(false)
                if (ok) HardwareDevice(
                    id = "mixer:$idx",
                    name = info.name.ifBlank { "麦克风 ${idx + 1}" },
                    type = "mic",
                    detail = info.vendor?.ifBlank { "" } ?: ""
                ) else null
            }
        }.getOrDefault(emptyList())
    }

    /** 枚举摄像头：v4l2 / dshow / avfoundation 探测 */
    override fun enumerateCameras(): List<HardwareDevice> {
        val os = System.getProperty("os.name").lowercase()
        return runCatching {
            when {
                os.contains("win") -> listDshowDevices()
                os.contains("mac") -> listOf(HardwareDevice("0", "默认摄像头（avfoundation）", "camera", "macOS 摄像头"))
                else -> {
                    (0 until 8).mapNotNull { i ->
                        val p = "/dev/video$i"
                        if (File(p).exists()) {
                            val name = runCatching { File("/sys/class/video4linux/video$i/name").readText().trim() }.getOrNull() ?: p
                            HardwareDevice(p, name.ifBlank { p }, "camera", "v4l2 设备")
                        } else null
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    /** 枚举扬声器：Java Sound 中支持 SourceDataLine 的 mixer */
    override fun enumerateSpeakers(): List<HardwareDevice> {
        return runCatching {
            AudioSystem.getMixerInfo().mapIndexedNotNull { idx, info ->
                val mixer = AudioSystem.getMixer(info)
                val ok = runCatching {
                    mixer.isLineSupported(DataLine.Info(SourceDataLine::class.java, javax.sound.sampled.AudioFormat(48000f, 16, 2, true, false)))
                }.getOrDefault(false)
                if (ok) HardwareDevice(
                    id = "mixer:$idx",
                    name = info.name.ifBlank { "扬声器 ${idx + 1}" },
                    type = "speaker",
                    detail = info.vendor?.ifBlank { "" } ?: ""
                ) else null
            }
        }.getOrDefault(emptyList())
    }

    /** 唤起操作系统屏幕键盘 */
    override fun showOnScreenKeyboard(): Boolean {
        val os = System.getProperty("os.name").lowercase()
        return runCatching {
            val proc = when {
                os.contains("win") -> ProcessBuilder("cmd", "/c", "start", "osk.exe").start()
                os.contains("mac") -> ProcessBuilder("open", "-a", "KeyboardViewer").start()
                else -> {
                    // Linux: 依次尝试常见屏幕键盘
                    val candidates = listOf("onboard", "cellwriter", "wvkbd-mobintl")
                    var started: Process? = null
                    for (c in candidates) {
                        val p = runCatching { ProcessBuilder("sh", "-c", "command -v $c >/dev/null 2>&1 && $c &").start() }.getOrNull()
                        if (p != null) { started = p; break }
                    }
                    started ?: throw IllegalStateException("未找到可用屏幕键盘（已尝试 onboard/cellwriter/wvkbd）")
                }
            }
            proc != null
        }.getOrDefault(false)
    }

    override fun setSpeakerDevice(deviceId: String) {
        // Java Sound 的输出设备选择由 DesktopAudioPlayer 的 mixer 参数处理
    }

    override fun savePrintPdf(fileName: String, pdfBytes: ByteArray): String? {
        return runCatching {
            val home = System.getProperty("user.home")
            val dir = File(home, "Downloads")
            dir.mkdirs()
            val f = File(dir, fileName.ifBlank { "document.pdf" })
            f.writeBytes(pdfBytes)
            f.absolutePath
        }.getOrNull()
    }

    override fun openLocalFile(path: String): Boolean {
        return runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(File(path).toURI())
                true
            } else false
        }.getOrDefault(false)
    }

    // ---- v2.5.0 媒体/手柄/焦点 ----

    @Volatile
    private var lastDesktopSink: com.selkies.client.video.DesktopVideoSink? = null

    /** v2.6.0 修复：VideoSurface 直接构造 sink（不经 makeVideoSink），必须在此注册供截图使用
     *  （旧版 lastDesktopSink 恒 null = 桌面截图「暂无可截取的视频帧」根因） */
    fun registerVideoSink(sink: com.selkies.client.video.DesktopVideoSink?) {
        lastDesktopSink = sink
    }
    @Volatile
    private var desktopFocus = true
    private var shutdownHookRegistered = false
    private var settingsRoot: () -> String = { "" }

    /** 注入设置根目录提供者（App 启动时） */
    fun setStorageRootProvider(provider: () -> String) {
        settingsRoot = provider
    }

    override fun makeVideoSink(): VideoSink {
        val sink = com.selkies.client.video.DesktopVideoSink()
        lastDesktopSink = sink
        return sink
    }

    override fun openFileLocation(path: String) {
        runCatching {
            val file = File(path)
            if (!file.exists()) return
            val os = System.getProperty("os.name", "").lowercase()
            when {
                os.contains("win") -> {
                    // explorer /select 定位到文件
                    ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
                }
                os.contains("mac") || os.contains("darwin") -> {
                    ProcessBuilder("open", "-R", file.absolutePath).start()
                }
                else -> {
                    // Linux：打开所在目录
                    ProcessBuilder("xdg-open", file.parentFile?.absolutePath ?: file.absolutePath).start()
                }
            }
        }
    }

    override fun rumbleGamepad(slot: Int, strong: Float, weak: Float, ms: Int) {
        // 桌面无通用手柄振动 API（需 JNI/hid）；留给后续版本
    }

    override fun captureVideoFrame(onDone: (ByteArray?) -> Unit) {
        val sink = lastDesktopSink
        if (sink == null) {
            onDone(null)
            return
        }
        Thread {
            // v2.6.0+：走解码线程的防御性快照（latestFrameSnapshot），
            // 消除旧版跨线程 readPixels 池化 Skia 位图的竞态（截图瞬间 UI 冻结的根因）
            val png = runCatching { sink.captureLatestFramePng() }.getOrNull()
            onDone(png)
        }.apply { isDaemon = true; name = "shot-encode" }.start()
    }

    override fun mediaStore(): MediaStoreBridge {
        // 每次读取最新设置（storageRoot 热生效）：settings.json → storageRoot 字段
        val root = runCatching {
            val f = java.io.File(configDir(), "settings.json")
            if (f.exists()) {
                val txt = f.readText()
                val m = Regex("\"storageRoot\"\\s*:\\s*\"([^\"]*)\"").find(txt)
                m?.groupValues?.get(1) ?: ""
            } else ""
        }.getOrDefault("")
        return DesktopMediaStoreBridge { root }
    }

    override fun makeStreamMuxer(outputPath: String): StreamMuxer = DesktopStreamMuxer(outputPath)

    override fun notifyAppFocus(gained: Boolean) {
        desktopFocus = gained
    }

    override fun hasAppFocus(): Boolean = desktopFocus

    override fun setRecordingActive(active: Boolean) {
        if (active && !shutdownHookRegistered) {
            shutdownHookRegistered = true
            Runtime.getRuntime().addShutdownHook(Thread {
                com.selkies.client.core.RecordingShutdownHook.autosave()
            })
        }
    }

    /** Windows dshow 设备枚举：解析 ffmpeg -list_devices 输出（无 ffmpeg 时回退常见默认名） */
    private fun listDshowDevices(): List<HardwareDevice> {
        val out = runCatching {
            val ffmpeg = sequenceOf("ffmpeg", "avconv").firstOrNull { bin ->
                runCatching { ProcessBuilder("sh", "-c", "command -v $bin").start().waitFor() == 0 }.getOrDefault(false)
            } ?: return emptyList()
            val p = ProcessBuilder(ffmpeg, "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy").start()
            p.errorStream.readBytes().toString(Charsets.UTF_8)  // ffmpeg 输出在 stderr
        }.getOrDefault("")
        val devices = ArrayList<HardwareDevice>()
        val regex = Regex("\"([^\"]+)\" \\(video\\)")
        for (m in regex.findAll(out)) {
            devices.add(HardwareDevice("video=" + m.groupValues[1], m.groupValues[1], "camera", "DirectShow 设备"))
        }
        if (devices.isEmpty()) {
            devices.add(HardwareDevice("video=Integrated Camera", "集成摄像头", "camera", "DirectShow 设备（猜测）"))
        }
        return devices
    }
}

/** AWT 键码 → X11 keysym */
object AwtKeys {
    fun keyCodeToKeysym(keyCode: Int): Int? {
        if (keyCode in KeyEvent.VK_A..KeyEvent.VK_Z) return ('a' + (keyCode - KeyEvent.VK_A)).code
        if (keyCode in KeyEvent.VK_0..KeyEvent.VK_9) return ('0' + (keyCode - KeyEvent.VK_0)).code
        return when (keyCode) {
            KeyEvent.VK_SPACE -> 0x20
            KeyEvent.VK_ENTER -> 0xff0d
            KeyEvent.VK_BACK_SPACE -> 0xff08
            KeyEvent.VK_TAB -> 0xff09
            KeyEvent.VK_ESCAPE -> 0xff1b
            KeyEvent.VK_DELETE -> 0xffff
            KeyEvent.VK_SHIFT -> 0xffe1
            KeyEvent.VK_CONTROL -> 0xffe3
            KeyEvent.VK_ALT -> 0xffe9
            KeyEvent.VK_META -> 0xffeb
            KeyEvent.VK_ALT_GRAPH -> 0xffea
            KeyEvent.VK_CAPS_LOCK -> 0xffe5
            KeyEvent.VK_NUM_LOCK -> 0xff7f
            KeyEvent.VK_SCROLL_LOCK -> 0xff14
            KeyEvent.VK_UP -> 0xff52
            KeyEvent.VK_DOWN -> 0xff54
            KeyEvent.VK_LEFT -> 0xff51
            KeyEvent.VK_RIGHT -> 0xff53
            KeyEvent.VK_HOME -> 0xff50
            KeyEvent.VK_END -> 0xff57
            KeyEvent.VK_PAGE_UP -> 0xff55
            KeyEvent.VK_PAGE_DOWN -> 0xff56
            KeyEvent.VK_INSERT -> 0xff63
            KeyEvent.VK_F1 -> 0xffbe
            KeyEvent.VK_F2 -> 0xffbf
            KeyEvent.VK_F3 -> 0xffc0
            KeyEvent.VK_F4 -> 0xffc1
            KeyEvent.VK_F5 -> 0xffc2
            KeyEvent.VK_F6 -> 0xffc3
            KeyEvent.VK_F7 -> 0xffc4
            KeyEvent.VK_F8 -> 0xffc5
            KeyEvent.VK_F9 -> 0xffc6
            KeyEvent.VK_F10 -> 0xffc7
            KeyEvent.VK_F11 -> 0xffc8
            KeyEvent.VK_F12 -> 0xffc9
            KeyEvent.VK_COMMA -> 0x2c
            KeyEvent.VK_PERIOD -> 0x2e
            KeyEvent.VK_MINUS -> 0x2d
            KeyEvent.VK_EQUALS -> 0x3d
            KeyEvent.VK_SLASH -> 0x2f
            KeyEvent.VK_BACK_SLASH -> 0x5c
            KeyEvent.VK_SEMICOLON -> 0x3b
            KeyEvent.VK_QUOTE -> 0x27
            KeyEvent.VK_BACK_QUOTE -> 0x60
            KeyEvent.VK_BRACELEFT -> 0x5b
            KeyEvent.VK_BRACERIGHT -> 0x5d
            KeyEvent.VK_PLUS -> 0x2b
            KeyEvent.VK_MULTIPLY -> 0x2a
            KeyEvent.VK_NUMPAD0 -> 0xffb0
            KeyEvent.VK_NUMPAD1 -> 0xffb1
            KeyEvent.VK_NUMPAD2 -> 0xffb2
            KeyEvent.VK_NUMPAD3 -> 0xffb3
            KeyEvent.VK_NUMPAD4 -> 0xffb4
            KeyEvent.VK_NUMPAD5 -> 0xffb5
            KeyEvent.VK_NUMPAD6 -> 0xffb6
            KeyEvent.VK_NUMPAD7 -> 0xffb7
            KeyEvent.VK_NUMPAD8 -> 0xffb8
            KeyEvent.VK_NUMPAD9 -> 0xffb9
            KeyEvent.VK_SEPARATOR -> 0xffac
            KeyEvent.VK_SUBTRACT -> 0xffad
            KeyEvent.VK_DIVIDE -> 0xffaf
            else -> null
        }
    }
}

/** AWT 剪贴板桥接 */
private object AwtClipboardBridge : ClipboardBridge {
    private val cb get() = Toolkit.getDefaultToolkit().systemClipboard

    override fun getText(): String? {
        return runCatching {
            cb.getData(DataFlavor.stringFlavor) as? String
        }.getOrNull()
    }

    override fun setText(text: String) {
        runCatching { cb.setContents(StringSelection(text), null) }
    }

    override fun hasImage(): Boolean {
        return runCatching {
            cb.isDataFlavorAvailable(DataFlavor.imageFlavor)
        }.getOrDefault(false)
    }

    override fun getImagePng(): ByteArray? {
        return runCatching {
            val img = cb.getData(DataFlavor.imageFlavor) as? java.awt.image.BufferedImage ?: return null
            val out = java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(img, "png", out)
            out.toByteArray()
        }.getOrNull()
    }

    override fun setImagePng(png: ByteArray) {
        runCatching {
            val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(png)) ?: return
            val sel = object : Transferable {
                override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
                override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
                override fun getTransferData(flavor: DataFlavor): Any = img
            }
            cb.setContents(sel, null)
        }
    }

    // ---- v2.6.0 多监听器（修复：旧版只覆写 setListener，addListener/removeListener 落到
    //      接口空默认实现 → 会话层注册的监听是空操作，本地复制永远检测不到 = 发送方向失效根因） ----
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    override fun setListener(listener: (() -> Unit)?) {
        if (listener != null) addListener(listener) else {
            listeners.toSet().forEach { removeListener(it) }
        }
    }

    override fun addListener(listener: () -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
        ensurePollThread()
    }

    override fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    @Volatile private var pollThreadStarted = false
    private fun ensurePollThread() {
        if (pollThreadStarted) return
        synchronized(this) {
            if (pollThreadStarted) return
            pollThreadStarted = true
            Thread {
                // 500ms 轮询系统剪贴板文本（AWT 在桌面平台无变更通知，只能轮询）
                var last = getText()
                var lastImageSig = hasImage()
                while (true) {
                    Thread.sleep(400)
                    val cur = runCatching { getText() }.getOrNull()
                    if (cur != last) {
                        last = cur
                        listeners.forEach { runCatching { it() } }
                    } else if (cur == null) {
                        // 文本为空时看图像是否出现/消失（图片复制场景）
                        val img = runCatching { hasImage() }.getOrDefault(false)
                        if (img != lastImageSig) {
                            lastImageSig = img
                            listeners.forEach { runCatching { it() } }
                        }
                    }
                }
            }.apply { isDaemon = true; name = "clip-poll" }.start()
        }
    }
}

/** 桌面打印桥接（javax.print，PDF 直通 + 打印对话框 + 结果回调） */
private object DesktopPrintBridge : PrintBridge {
    override fun printPdf(pdfBytes: ByteArray, fileName: String, onResult: (Boolean, String?) -> Unit) {
        Thread {
            runCatching {
                val services = PrintServiceLookup.lookupPrintServices(
                    javax.print.DocFlavor.BYTE_ARRAY.PDF, null
                )
                if (services.isEmpty()) {
                    // 无 PDF 直印服务：保存到下载目录让用户自行打印
                    val dir = File(DesktopPlatform.configDir(), "prints")
                    dir.mkdirs()
                    val f = File(dir, fileName.ifBlank { "document.pdf" })
                    f.writeBytes(pdfBytes)
                    DesktopPlatform.openUrl(f.toURI().toString())
                    onResult(true, "已保存到 ${f.absolutePath}（系统无 PDF 直印服务）")
                    return@runCatching
                }
                val service = javax.print.ServiceUI.printDialog(
                    null, 100, 100,
                    services, services.first(), javax.print.DocFlavor.BYTE_ARRAY.PDF,
                    HashPrintRequestAttributeSet().apply { add(JobName(fileName, null)) }
                ) ?: run {
                    onResult(false, "已取消打印对话框")
                    return@runCatching
                }
                val doc = javax.print.SimpleDoc(pdfBytes, javax.print.DocFlavor.BYTE_ARRAY.PDF, null)
                service.createPrintJob().print(doc, HashPrintRequestAttributeSet())
                onResult(true, null)
            }.onFailure {
                onResult(false, it.message ?: it.toString())
            }
        }.apply { isDaemon = true }.start()
    }
}

/** 平台 HttpClient（OkHttp 引擎）
 *  ⚠️ 必须安装 WebSockets 插件，否则 webSocketSession 直接抛异常（v2.0.0 连不上的根因）
 */
actual fun createHttpClient(allowUntrustedCert: Boolean): HttpClient {
    return HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = false
        install(WebSockets)
        engine {
            config {
                if (allowUntrustedCert) {
                    val trustAll = object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    }
                    val sslContext = SSLContext.getInstance("TLS")
                    sslContext.init(null, arrayOf(trustAll), SecureRandom())
                    sslSocketFactory(sslContext.socketFactory, trustAll)
                    hostnameVerifier { _, _ -> true }
                }
                connectTimeout(java.time.Duration.ofSeconds(12))
                // ⚠️ 不设 pingInterval：OkHttp ping 无 pong 会被主动杀连接（与 Android 同根因）
                // ⚠️ readTimeout=0：WS 空闲期不误杀；探测超时由协程 withTimeout 控制
                readTimeout(java.time.Duration.ZERO)
            }
        }
    }
}

/** 桌面键事件 → keysym：物理键盘由 AWT 分发器直通，Compose 路径返回 null 不拦截 */
actual fun androidx.compose.ui.input.key.KeyEvent.keysymOrNull(): Int? = null

/** 桌面鼠标按钮状态（skiko PointerButtons 值类扩展） */
private fun androidx.compose.ui.input.pointer.PointerEvent.buttonsMask(): Int {
    var mask = 0
    val buttons = this.buttons
    if (buttons.isPrimaryPressed) mask = mask or 1
    if (buttons.isSecondaryPressed) mask = mask or 2
    if (buttons.isTertiaryPressed) mask = mask or 4
    return mask
}

actual fun androidx.compose.ui.input.pointer.PointerEvent.primaryPressed(): Boolean =
    (buttonsMask() and 1) != 0

actual fun androidx.compose.ui.input.pointer.PointerEvent.secondaryPressed(): Boolean =
    (buttonsMask() and 2) != 0

actual fun androidx.compose.ui.input.pointer.PointerEvent.tertiaryPressed(): Boolean =
    (buttonsMask() and 4) != 0

/** 桌面平台服务入口 */
actual fun getPlatformServices(): PlatformServices = DesktopPlatform

/** Desktop PNG 解码：Skia Image.makeFromEncoded → toComposeImageBitmap */
actual fun decodePngToImageBitmap(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap? =
    runCatching {
        val skiaImage = org.jetbrains.skia.Image.makeFromEncoded(bytes)
        val out = androidx.compose.ui.graphics.ImageBitmap(skiaImage.width, skiaImage.height)
        val canvas = androidx.compose.ui.graphics.Canvas(out)
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        val skiaCanvas = canvas.nativeCanvas
        skiaCanvas.drawImage(skiaImage, 0f, 0f, org.jetbrains.skia.Paint())
        out
    }.getOrNull()
