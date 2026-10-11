package com.selkies.client.platform

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.VibrationEffect
import android.os.Vibrator
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.selkies.client.audio.AndroidAudioPlayer
import com.selkies.client.audio.AndroidMicCapture
import com.selkies.client.video.AndroidVideoSink
import com.selkies.client.webcam.AndroidWebcamCapture
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Android 平台服务实现
 */
object AndroidPlatform : PlatformServices {
    override val platformName = "Android"

    private var appContext: Context? = null
    private var activityRef: ComponentActivity? = null

    private var pendingPick: ((List<PickedFile>) -> Unit)? = null
    private var pendingSave: ((Boolean) -> Unit)? = null
    private var pendingSaveBytes: ByteArray? = null
    private var pendingSaveName: String? = null
    private var pickLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null
    private var saveLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null
    private var permLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
    private var pendingPerm: ((Boolean) -> Unit)? = null

    private val clipboardBridge = AndroidClipboardBridge()

    fun init(activity: ComponentActivity) {
        appContext = activity.applicationContext
        activityRef = activity
        pickLauncher = activity.registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            val cb = pendingPick
            pendingPick = null
            val files = ArrayList<PickedFile>()
            for (uri in uris) {
                runCatching {
                    activity.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (c.moveToFirst()) {
                            val name = if (nameIdx >= 0) c.getString(nameIdx) else uri.lastPathSegment ?: "file"
                            val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                            // ⚠️ content:// URI 不能直接 new File() 读（v2.0.1 Android 上传损坏的根因）：
                            //    先拷贝到应用缓存目录，返回真实文件路径，分片上传才可用
                            val cache = File(activity.cacheDir, "pick_${System.currentTimeMillis()}_$name")
                            activity.contentResolver.openInputStream(uri)?.use { input ->
                                cache.outputStream().use { input.copyTo(it) }
                            }
                            if (cache.exists() && cache.length() > 0) {
                                files.add(PickedFile(name, cache.absolutePath, if (size > 0) size else cache.length()))
                            }
                        }
                    }
                }
            }
            cb?.invoke(files)
        }
        saveLauncher = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val cb = pendingSave
            val bytes = pendingSaveBytes
            val ok = uri != null && bytes != null
            if (ok) {
                runCatching {
                    activity.contentResolver.openOutputStream(uri!!)?.use { it.write(bytes!!) }
                }
            }
            pendingSave = null
            pendingSaveBytes = null
            cb?.invoke(ok)
        }
        permLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val cb = pendingPerm
            pendingPerm = null
            cb?.invoke(grants.values.all { it })
        }
    }

    fun release() {
        activityRef = null
    }

    /** 供同文件内部使用 */
    internal fun appCtx(): Context? = appContext

    fun ensurePermissions(perms: List<String>, onResult: (Boolean) -> Unit) {
        val act = activityRef ?: run { onResult(false); return }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(act, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            onResult(true)
            return
        }
        pendingPerm = onResult
        permLauncher?.launch(needed.toTypedArray())
    }

    fun activity(): Activity? = activityRef

    override fun configDir(): String {
        val ctx = appContext ?: return File("/data/local/tmp").absolutePath
        return ctx.filesDir.absolutePath
    }

    override fun clipboard(): ClipboardBridge = clipboardBridge

    override fun print(): PrintBridge = AndroidPrintBridge

    override fun vibrate(ms: Long) {
        runCatching {
            val vib = appContext?.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(ms)
            }
        }
    }

    override fun openUrl(url: String) {
        runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext?.startActivity(intent)
        }
    }

    override fun pickFiles(mimeTypes: List<String>, onResult: (List<PickedFile>) -> Unit) {
        val launcher = pickLauncher ?: run { onResult(emptyList()); return }
        pendingPick = onResult
        launcher.launch(mimeTypes.firstOrNull() ?: "*/*")
    }

    override fun readFileBytes(path: String): ByteArray? = runCatching {
        when {
            path.startsWith("content://") ->
                appContext?.contentResolver?.openInputStream(android.net.Uri.parse(path))?.use { it.readBytes() }
            path.startsWith("file://") -> File(android.net.Uri.parse(path).path ?: return@runCatching null).readBytes()
            else -> File(path).takeIf { it.exists() }?.readBytes()
        }
    }.getOrNull()

    override fun exitApp() {
        activityRef?.finish()
    }

    override fun saveFile(suggestedName: String, bytes: ByteArray, onDone: (Boolean) -> Unit) {
        val launcher = saveLauncher
        val act = activityRef
        if (launcher == null || act == null) {
            // 无 Activity（后台）时保存到应用目录
            val ok = runCatching {
                val dir = File(configDir(), "downloads")
                dir.mkdirs()
                File(dir, suggestedName).writeBytes(bytes)
                true
            }.getOrDefault(false)
            onDone(ok)
            return
        }
        pendingSave = onDone
        pendingSaveBytes = bytes
        pendingSaveName = suggestedName
        launcher.launch(suggestedName)
    }

    override fun makeVideoSink(): VideoSink = AndroidVideoSink()

    override fun makeAudioPlayer(): AudioPlayer = AndroidAudioPlayer()

    override fun makeMicCapture(): MicCapture = AndroidMicCapture()

    override fun makeWebcamCapture(): WebcamCapture = AndroidWebcamCapture()

    override fun hasHardwareKeyboard(): Boolean {
        val cfg = appContext?.resources?.configuration ?: return false
        return cfg.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS &&
                cfg.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
    }

    override fun setFullscreen(enabled: Boolean) {
        val act = activityRef ?: return
        val window = act.window
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val insetsController = window.insetsController
                if (insetsController != null) {
                    if (enabled) {
                        insetsController.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                        insetsController.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    } else {
                        insetsController.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val decorView = window.decorView
                if (enabled) {
                    @Suppress("DEPRECATION")
                    window.setFlags(
                        android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
                    )
                } else {
                    @Suppress("DEPRECATION")
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                }
                decorView.systemUiVisibility = if (enabled)
                    (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
                else 0
            }
            if (enabled) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    override fun screenDpi(): Float = (appContext?.resources?.displayMetrics?.densityDpi ?: 160).toFloat()

    override fun appVersion(): String {
        return runCatching {
            val ctx = appContext!!
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            pi.versionName ?: "?"
        }.getOrDefault("2.0.0")
    }

    override fun showSoftInput(show: Boolean) {
        val act = activityRef ?: return
        val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val view = act.window.currentFocus ?: act.window.decorView
        if (show) {
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        } else {
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    override fun isTouchDevice(): Boolean {
        val cfg = appContext?.resources?.configuration ?: return true
        return cfg.touchscreen != android.content.res.Configuration.TOUCHSCREEN_NOTOUCH
    }

    override fun nativeKeyCodeToKeysym(nativeKeyCode: Int, unicodeChar: Int): Int? {
        return AndroidKeys.keyCodeToKeysym(nativeKeyCode)
    }

    override fun setPhysicalKeyHandler(handler: ((Int, Boolean) -> Unit)?) {
        // Android 使用 Compose onPreviewKeyEvent 路径，无需 AWT 分发器
    }
}

/** 平台 HttpClient（OkHttp 引擎，可选信任所有证书）
 *  ⚠️ 必须安装 WebSockets 插件，否则 webSocketSession 直接抛异常（v2.0.0 连不上的根因）
 *  ⚠️【断连根因修复】绝不能设置 pingInterval：OkHttp 发 WS ping 后服务器经 Cloudflare 不回 pong，
 *     OkHttp 会在一个 ping 周期内主动杀掉连接——浏览器不发 ping 所以网页版稳定（实测验证 87s+ 稳定）。
 *     保活由应用层保证：CLIENT_FRAME_ACK 每 50ms（与官方前端一致）持续有上行流量，CF 不会闲置掐线。
 *  ⚠️ readTimeout 必须为 0（无限）：WS 建立后静态画面期间服务器可能长时间无下行数据，
 *     有限读超时会误杀空闲连接；探测请求用协程 withTimeout 控制超时。
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
                readTimeout(java.time.Duration.ZERO)
            }
        }
    }
}

/** Android 键事件 → keysym（Android 键码） */
actual fun androidx.compose.ui.input.key.KeyEvent.keysymOrNull(): Int? =
    AndroidKeys.keyCodeToKeysym(this.nativeKeyEvent.keyCode)

/** Android 按钮状态：触屏交互由手势系统处理（单击/长按/双击），此处恒 false */
actual fun androidx.compose.ui.input.pointer.PointerEvent.primaryPressed(): Boolean = false

actual fun androidx.compose.ui.input.pointer.PointerEvent.secondaryPressed(): Boolean = false

actual fun androidx.compose.ui.input.pointer.PointerEvent.tertiaryPressed(): Boolean = false

/** Android 剪贴板桥接 */
private class AndroidClipboardBridge : ClipboardBridge {
    private val cm: ClipboardManager?
        get() = appCtxRef()?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private fun appCtxRef(): Context? = AndroidPlatform.appCtx()

    override fun getText(): String? {
        val ctx = appCtxRef()
        val clip = cm?.primaryClip ?: return null
        for (i in 0 until clip.itemCount) {
            val t = clip.getItemAt(i)?.coerceToText(ctx) ?: continue
            val s = t.toString()
            if (s.isNotEmpty()) return s
        }
        return null
    }

    override fun setText(text: String) {
        val c = cm ?: return
        c.setPrimaryClip(ClipData.newPlainText("selkies", text))
    }

    override fun hasImage(): Boolean {
        val clip = cm?.primaryClip ?: return false
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            if (item?.uri != null || item?.intent != null) return true
        }
        return false
    }

    override fun getImagePng(): ByteArray? {
        // Android 剪贴板图像读取复杂且少见，返回 null（远端图像 → 本地已支持 PNG 写入）
        return null
    }

    override fun setImagePng(png: ByteArray) {
        runCatching {
            val ctx = appCtxRef() ?: return
            val file = File(ctx.cacheDir, "clip_${System.currentTimeMillis()}.png")
            file.writeBytes(png)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", file
            )
            cm?.setPrimaryClip(ClipData.newUri(ctx.contentResolver, "selkies-image", uri))
        }
    }

    override fun setListener(listener: (() -> Unit)?) {
        val c = cm ?: return
        c.removePrimaryClipChangedListener(clipListener)
        pendingListener = listener
        if (listener != null) {
            c.addPrimaryClipChangedListener(clipListener)
        }
    }

    private var pendingListener: (() -> Unit)? = null
    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        pendingListener?.invoke()
    }
}

/** Android 键码 → X11 keysym */
object AndroidKeys {
    fun keyCodeToKeysym(keyCode: Int): Int? {
        // 字母
        if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            return ('a' + (keyCode - KeyEvent.KEYCODE_A)).code
        }
        // 数字行
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            return ('0' + (keyCode - KeyEvent.KEYCODE_0)).code
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> 0x20
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 0xff0d
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> 0xff1b
            KeyEvent.KEYCODE_DEL -> 0xff08
            KeyEvent.KEYCODE_FORWARD_DEL -> 0xffff
            KeyEvent.KEYCODE_TAB -> 0xff09
            KeyEvent.KEYCODE_SHIFT_LEFT -> 0xffe1
            KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xffe2
            KeyEvent.KEYCODE_CTRL_LEFT -> 0xffe3
            KeyEvent.KEYCODE_CTRL_RIGHT -> 0xffe4
            KeyEvent.KEYCODE_ALT_LEFT -> 0xffe9
            KeyEvent.KEYCODE_ALT_RIGHT -> 0xffea
            KeyEvent.KEYCODE_META_LEFT -> 0xffeb
            KeyEvent.KEYCODE_META_RIGHT -> 0xffec
            KeyEvent.KEYCODE_CAPS_LOCK -> 0xffe5
            KeyEvent.KEYCODE_NUM_LOCK -> 0xff7f
            KeyEvent.KEYCODE_SCROLL_LOCK -> 0xff14
            KeyEvent.KEYCODE_DPAD_UP -> 0xff52
            KeyEvent.KEYCODE_DPAD_DOWN -> 0xff54
            KeyEvent.KEYCODE_DPAD_LEFT -> 0xff51
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0xff53
            KeyEvent.KEYCODE_MOVE_HOME -> 0xff50
            KeyEvent.KEYCODE_MOVE_END -> 0xff57
            KeyEvent.KEYCODE_PAGE_UP -> 0xff55
            KeyEvent.KEYCODE_PAGE_DOWN -> 0xff56
            KeyEvent.KEYCODE_INSERT -> 0xff63
            KeyEvent.KEYCODE_F1 -> 0xffbe
            KeyEvent.KEYCODE_F2 -> 0xffbf
            KeyEvent.KEYCODE_F3 -> 0xffc0
            KeyEvent.KEYCODE_F4 -> 0xffc1
            KeyEvent.KEYCODE_F5 -> 0xffc2
            KeyEvent.KEYCODE_F6 -> 0xffc3
            KeyEvent.KEYCODE_F7 -> 0xffc4
            KeyEvent.KEYCODE_F8 -> 0xffc5
            KeyEvent.KEYCODE_F9 -> 0xffc6
            KeyEvent.KEYCODE_F10 -> 0xffc7
            KeyEvent.KEYCODE_F11 -> 0xffc8
            KeyEvent.KEYCODE_F12 -> 0xffc9
            KeyEvent.KEYCODE_COMMA -> 0x2c
            KeyEvent.KEYCODE_PERIOD -> 0x2e
            KeyEvent.KEYCODE_MINUS -> 0x2d
            KeyEvent.KEYCODE_EQUALS -> 0x3d
            KeyEvent.KEYCODE_SLASH -> 0x2f
            KeyEvent.KEYCODE_BACKSLASH -> 0x5c
            KeyEvent.KEYCODE_SEMICOLON -> 0x3b
            KeyEvent.KEYCODE_APOSTROPHE -> 0x27
            KeyEvent.KEYCODE_GRAVE -> 0x60
            KeyEvent.KEYCODE_AT -> 0x40
            KeyEvent.KEYCODE_PLUS -> 0x2b
            KeyEvent.KEYCODE_STAR -> 0x2a
            KeyEvent.KEYCODE_LEFT_BRACKET -> 0x5b
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x5d
            KeyEvent.KEYCODE_POUND -> 0x23
            KeyEvent.KEYCODE_NUMPAD_0 -> 0xffb0
            KeyEvent.KEYCODE_NUMPAD_1 -> 0xffb1
            KeyEvent.KEYCODE_NUMPAD_2 -> 0xffb2
            KeyEvent.KEYCODE_NUMPAD_3 -> 0xffb3
            KeyEvent.KEYCODE_NUMPAD_4 -> 0xffb4
            KeyEvent.KEYCODE_NUMPAD_5 -> 0xffb5
            KeyEvent.KEYCODE_NUMPAD_6 -> 0xffb6
            KeyEvent.KEYCODE_NUMPAD_7 -> 0xffb7
            KeyEvent.KEYCODE_NUMPAD_8 -> 0xffb8
            KeyEvent.KEYCODE_NUMPAD_9 -> 0xffb9
            KeyEvent.KEYCODE_NUMPAD_DOT -> 0xffae
            KeyEvent.KEYCODE_NUMPAD_ADD -> 0xffab
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 0xffad
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 0xffaa
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 0xffaf
            KeyEvent.KEYCODE_VOLUME_UP -> 0x1008ff13
            KeyEvent.KEYCODE_VOLUME_DOWN -> 0x1008ff11
            KeyEvent.KEYCODE_VOLUME_MUTE -> 0x1008ff12
            KeyEvent.KEYCODE_MUTE -> 0x1008ff12
            KeyEvent.KEYCODE_POWER -> 0x1008ff2a
            KeyEvent.KEYCODE_MENU -> 0xff67
            KeyEvent.KEYCODE_SEARCH -> 0xff65
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> 0x1008ff14
            KeyEvent.KEYCODE_HOME -> null
            else -> null
        }
    }
}

/** Android 打印桥接：PDF 字节 → 系统打印界面 */
private object AndroidPrintBridge : PrintBridge {
    override fun printPdf(pdfBytes: ByteArray, fileName: String) {
        val act = AndroidPlatform.activity() ?: return
        runCatching {
            val printManager = act.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
            val jobName = "Selkies: $fileName"
            printManager.print(jobName, PdfPrintAdapter(act, pdfBytes, fileName), PrintAttributes.Builder().build())
        }
    }

    private class PdfPrintAdapter(
        private val context: Context,
        private val pdfBytes: ByteArray,
        private val fileName: String
    ) : PrintDocumentAdapter() {

        override fun onWrite(
            pages: Array<out android.print.PageRange>?,
            destination: ParcelFileDescriptor?,
            cancellationSignal: android.os.CancellationSignal?,
            callback: WriteResultCallback?
        ) {
            runCatching {
                PdfRenderer(ParcelFileDescriptor.open(
                    java.io.File(context.cacheDir, "print_tmp_${System.currentTimeMillis()}.pdf").apply {
                        writeBytes(pdfBytes)
                    }, ParcelFileDescriptor.MODE_READ_ONLY
                )).use { renderer ->
                    val printAttrs = PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                        .build()
                    val document = PrintedPdfDocument(context, printAttrs)
                    try {
                        val pageCount = renderer.pageCount
                        for (i in 0 until pageCount) {
                            if (cancellationSignal?.isCanceled == true) {
                                callback?.onWriteCancelled()
                                return
                            }
                            renderer.openPage(i).use { page ->
                                val pdfPage = document.startPage(i)
                                val scale = minOf(
                                    pdfPage.canvas.width.toFloat() / page.width,
                                    pdfPage.canvas.height.toFloat() / page.height
                                )
                                val bitmap = Bitmap.createBitmap(
                                    page.width, page.height, Bitmap.Config.ARGB_8888
                                )
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                pdfPage.canvas.save()
                                pdfPage.canvas.scale(scale, scale)
                                pdfPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                                pdfPage.canvas.restore()
                                bitmap.recycle()
                                document.finishPage(pdfPage)
                            }
                        }
                        document.writeTo(FileOutputStream(destination!!.fileDescriptor))
                        callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                    } finally {
                        document.close()
                    }
                }
            }.onFailure {
                callback?.onWriteFailed(it.message)
            }
        }

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: android.os.CancellationSignal?,
            callback: LayoutResultCallback?,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback?.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(fileName ?: "document.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build()
            callback?.onLayoutFinished(info, true)
        }
    }
}

/** Android 平台服务入口 */
actual fun getPlatformServices(): PlatformServices = AndroidPlatform
