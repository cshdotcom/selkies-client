package com.selkies.client.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
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
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

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

    override fun makeVideoSink(): VideoSink = com.selkies.client.video.DesktopVideoSink()

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

    override fun appVersion(): String = "2.0.0"

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

    fun init() {}

    fun shutdown() {}
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

    @Volatile
    private var listener: (() -> Unit)? = null
    private val owner = object : java.awt.datatransfer.ClipboardOwner {
        override fun lostOwnership(clipboard: java.awt.datatransfer.Clipboard, contents: java.awt.datatransfer.Transferable) {}
    }

    override fun setListener(listener: (() -> Unit)?) {
        this.listener = listener
        if (listener != null) {
            Thread {
                var last = getText()
                while (this.listener != null) {
                    Thread.sleep(500)
                    val cur = runCatching { getText() }.getOrNull()
                    if (cur != last) {
                        last = cur
                        this.listener?.invoke()
                    }
                }
            }.apply { isDaemon = true; name = "clip-poll" }.start()
        }
    }
}

/** 桌面打印桥接（javax.print） */
private object DesktopPrintBridge : PrintBridge {
    override fun printPdf(pdfBytes: ByteArray, fileName: String) {
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
                    return@runCatching
                }
                val service = javax.print.ServiceUI.printDialog(
                    null, 100, 100,
                    services, services.first(), javax.print.DocFlavor.BYTE_ARRAY.PDF,
                    HashPrintRequestAttributeSet().apply { add(JobName(fileName, null)) }
                ) ?: return@runCatching
                val doc = javax.print.SimpleDoc(pdfBytes, javax.print.DocFlavor.BYTE_ARRAY.PDF, null)
                service.createPrintJob().print(doc, HashPrintRequestAttributeSet())
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
                readTimeout(java.time.Duration.ofSeconds(30))
                pingInterval(java.time.Duration.ofMillis(20000))
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
