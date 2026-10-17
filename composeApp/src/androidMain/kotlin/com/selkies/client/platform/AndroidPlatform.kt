package com.selkies.client.platform

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.VibrationEffect
import android.os.Vibrator
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.asImageBitmap
import com.selkies.client.audio.AndroidAudioPlayer
import com.selkies.client.audio.AndroidMicCapture
import com.selkies.client.model.HardwareDevice
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

    /** 应用焦点（Android 10+ 后台剪贴板限制判定） */
    @Volatile
    var hasFocus = true
        private set

    fun setFocus(gained: Boolean) {
        hasFocus = gained
    }

    /** 最近创建的视频 sink（PixelCopy 截图取其 Surface） */
    @Volatile
    private var lastVideoSink: com.selkies.client.video.AndroidVideoSink? = null

    /** v2.6.0 当前视频 TextureView（getBitmap 截图直取；SurfaceView 时代的 PixelCopy 兼容回退） */
    @Volatile
    private var videoTextureView: android.view.TextureView? = null

    fun setVideoTextureView(tv: android.view.TextureView?) {
        videoTextureView = tv
    }

    private var pendingPick: ((List<PickedFile>) -> Unit)? = null
    private var pendingSave: ((Boolean) -> Unit)? = null
    private var pendingSaveBytes: ByteArray? = null
    private var pendingSaveName: String? = null
    private var pickLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null
    private var saveLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null
    private var permLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
    private var pendingPerm: ((Boolean) -> Unit)? = null
    // v2.7.4：另存为选择器（SAF CreateDocument，按 MIME 分类型）
    private var savePickerLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null
    private var pendingSaveTarget: ((PickedSaveTarget?) -> Unit)? = null
    // v2.7.5：默认存储位置选择器（SAF OpenDocumentTree + 持久授权）
    private var dirPickerLauncher: androidx.activity.result.ActivityResultLauncher<android.net.Uri?>? = null
    private var pendingDirPick: ((PickedDirectory?) -> Unit)? = null

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
        // v2.7.4：另存为选择器——用户选定位置后回传可写目标（不落字节，由调用方流式写入）
        savePickerLauncher = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val cb = pendingSaveTarget
            pendingSaveTarget = null
            if (uri == null) { cb?.invoke(null); return@registerForActivityResult }
            val resolver = activity.contentResolver
            val target = PickedSaveTarget(
                displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "file",
                open = { runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull() },
                displayPath = uri.toString()
            )
            cb?.invoke(target)
        }
        // v2.7.5：默认存储位置选择器——SAF 目录树选择，持久授权后跨重启可读写
        dirPickerLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val cb = pendingDirPick
            pendingDirPick = null
            if (uri == null) { cb?.invoke(null); return@registerForActivityResult }
            runCatching {
                // 持久授权（重启后仍可读写该目录树）
                activity.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val display = SafPaths.resolveDisplayPath(activity, uri)
            cb?.invoke(PickedDirectory(displayPath = display, treeUri = uri.toString()))
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

    /** 用系统查看器打开本地文件（FileProvider 授权）。
     *     v2.7.5【打开文件没反应根治】：
     *     ① 扩展名→MIME 显式映射（系统 MimeTypeMap 不认 mkv/webm 等 → 旧版回退「任意类型」→
     *        无应用可打开且 runCatching 靜默吞掉 ActivityNotFoundException = 「没反应」）
     *     ② 公共媒体目录文件不再走 FileProvider 的 external-path 缺失异常（file_paths.xml 已补）
     *     ③ 无查看器时回退系统选择器；仍失败返回 false（UI 提示） */
    override fun openFile(path: String): Boolean {
        val ctx = appContext ?: return false
        return runCatching {
            val uri = resolveShareUri(ctx, path) ?: return false
            val name = path.substringAfterLast('/')
            val mime = mimeForFile(name) ?: ctx.contentResolver.getType(uri) ?: "*/*"
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                ctx.startActivity(intent)
                true
            } catch (e: android.content.ActivityNotFoundException) {
                // 无单应用可处理 → 系统选择器（FileProvider 授权传递）
                val chooser = android.content.Intent.createChooser(intent, "打开 $name").apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { ctx.startActivity(chooser) }.isSuccess
            }
        }.getOrDefault(false)
    }

    /** 系统分享面板（v2.7.5：同样修复 MIME 与无应用的反馈） */
    override fun shareFile(path: String, displayName: String) {
        runCatching {
            val ctx = appContext ?: return
            val uri = resolveShareUri(ctx, path) ?: return
            val mime = mimeForFile(displayName) ?: ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mime
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(android.content.Intent.createChooser(intent, "分享 $displayName").apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    /** path → 可授权 Uri：content:// 直接用；文件路径走 FileProvider（失败时拷贝到缓存后重试）
     *  v2.7.5：公共媒体目录（Pictures/Movies/Download 下的 Selkies）已入 file_paths.xml external-path */
    private fun resolveShareUri(ctx: Context, path: String): android.net.Uri? {
        if (path.startsWith("content://")) return android.net.Uri.parse(path)
        val file = File(path)
        if (!file.exists()) return null
        val direct = runCatching {
            androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        }.getOrNull()
        if (direct != null) return direct
        // 路径不在 FileProvider 范围（应用私有目录等特殊位置）：拷到缓存再授权
        return runCatching {
            val cache = File(ctx.cacheDir, "share_${System.currentTimeMillis()}_${file.name}")
            file.copyTo(cache, overwrite = true)
            androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", cache)
        }.getOrNull()
    }

    /** v2.7.5：扩展名 → MIME 显式映射（系统 MimeTypeMap 缺 mkv/webm/flac 等 = 打开没反应根因之一） */
    private fun mimeForFile(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heif"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/x-wav"
            "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"
            "m4a", "aac" -> "audio/mp4"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "html", "htm" -> "text/html"
            "xml" -> "text/xml"
            "json" -> "application/json"
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            "tar", "gz" -> "application/x-gtar"
            "apk" -> "application/vnd.android.package-archive"
            "exe" -> "application/vnd.microsoft.portable-executable"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> null
        }
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

    /** v2.7.4：另存为（SAF）——用户选任意位置（含 U 盘/网盘/任意目录），流式写入 */
    override fun saveFileWithPicker(
        suggestedName: String,
        mime: String,
        onResult: (PickedSaveTarget?) -> Unit
    ) {
        val launcher = savePickerLauncher
        val act = activityRef
        if (launcher == null || act == null) { onResult(null); return }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            pendingSaveTarget = onResult
            launcher.launch(suggestedName)
        }
    }

    /** v2.7.5：选择默认存储目录（SAF OpenDocumentTree；返回 treeUri + 尽量解析的真实绝对路径） */
    override fun pickDirectory(onResult: (PickedDirectory?) -> Unit) {
        val launcher = dirPickerLauncher
        val act = activityRef
        if (launcher == null || act == null) { onResult(null); return }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            pendingDirPick = onResult
            runCatching { launcher.launch(null) }.onFailure {
                pendingDirPick = null
                onResult(null)
            }
        }
    }

    override fun makeVideoSink(): VideoSink {
        val sink = com.selkies.client.video.AndroidVideoSink()
        lastVideoSink = sink
        return sink
    }

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

    override fun setPhysicalKeyHandler(handler: ((Int, Boolean) -> Boolean)?) {
        // Android 使用 Compose onPreviewKeyEvent 路径，无需 AWT 分发器
    }

    // ---- v2.4.0 硬件选择 ----

    override fun enumerateMics(): List<HardwareDevice> = AndroidMicCapture.devices()

    override fun enumerateCameras(): List<HardwareDevice> = AndroidWebcamCapture.devices()

    override fun enumerateSpeakers(): List<HardwareDevice> {
        val ctx = appContext ?: return emptyList()
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return emptyList()
        return runCatching {
            am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                .filter {
                    it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
                            it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE
                }
                .mapIndexed { idx, d ->
                    val kind = when (d.type) {
                        android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
                        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
                        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙"
                        else -> "USB"
                    }
                    HardwareDevice(
                        id = d.address?.ifBlank { "speaker-$idx" } ?: "speaker-$idx",
                        name = d.productName?.toString()?.ifBlank { kind } ?: kind,
                        type = "speaker",
                        detail = kind
                    )
                }
        }.getOrDefault(emptyList())
    }

    override fun showOnScreenKeyboard(): Boolean {
        // Android：系统输入法即屏幕键盘
        showSoftInput(true)
        return true
    }

    override fun setSpeakerDevice(deviceId: String) {
        // Android 输出路由：蓝牙 SCO 需要专门开启；有线/扬声器由系统自动切换
        runCatching {
            val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
            if (deviceId.contains("sco", true)) {
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
            } else if (am.isBluetoothScoOn) {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
            }
        }
    }

    override fun savePrintPdf(fileName: String, pdfBytes: ByteArray): String? {
        return runCatching {
            val ctx = appContext ?: return null
            // 1) 公共下载目录（MediaStore，用户可见）
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = ctx.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { it.write(pdfBytes) }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    FileOutputStream(File(dir, fileName)).use { it.write(pdfBytes) }
                }
            }
            // 2) 应用可访问副本（供打开/分享——FileProvider）
            val appDir = File(
                ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir,
                "prints"
            )
            appDir.mkdirs()
            val f = File(appDir, fileName)
            FileOutputStream(f).use { it.write(pdfBytes) }
            f.absolutePath
        }.getOrNull()
    }

    override fun openLocalFile(path: String): Boolean {
        return runCatching {
            val ctx = appContext ?: return false
            val uri = runCatching {
                androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(path))
            }.getOrNull() ?: android.net.Uri.fromFile(File(path))
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    // ---- v2.5.0 媒体/手柄/焦点 ----

    override fun openFileLocation(path: String): Boolean {
        // v2.7.5【打开文件夹没反应根治】：三级回退——
        //  ① DocumentsContract 目录 URI（ExternalStorageProvider，多数国产 ROM 文件管理器支持
        //     vnd.android.document/directory 定位到真实目录）
        //  ② Download 目录文件 → 系统「下载」应用列表
        //  ③ 打开文件本身（至少有响应）
        val ctx = appContext ?: return false
        // 先把 content URI 还原成真实路径（MediaStore DATA 列）
        val realPath: String = if (path.startsWith("content://")) {
            runCatching {
                ctx.contentResolver.query(
                    android.net.Uri.parse(path),
                    arrayOf(MediaStore.MediaColumns.DATA), null, null, null
                )?.use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" } ?: ""
            }.getOrDefault("") ?: ""
        } else path
        // ① DocumentsContract 目录视图（真实绝对路径 /storage/emulated/0/<相对>）
        if (realPath.startsWith("/storage/emulated/0/")) {
            val rel = realPath.removePrefix("/storage/emulated/0/").substringBeforeLast('/')
            if (rel.isNotBlank()) {
                val dirUri = runCatching {
                    android.provider.DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents", "primary:$rel"
                    )
                }.getOrNull()
                if (dirUri != null) {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(dirUri, "vnd.android.document/directory")
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (runCatching { ctx.startActivity(intent) }.isSuccess) return true
                }
            }
        }
        // ② 下载目录 → 系统「下载」应用
        if (realPath.contains("/Download/", true) || path.contains("Downloads", true)) {
            val ok = runCatching {
                ctx.startActivity(android.content.Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return true
        }
        // ③ 兜底：打开文件本身
        return openFile(path)
    }

    override fun rumbleGamepad(slot: Int, strong: Float, weak: Float, ms: Int) {
        com.selkies.client.core.GamepadBridge.rumble(slot, strong, weak, ms)
    }

    override fun captureVideoFrame(onDone: (ByteArray?) -> Unit) {
        // v2.6.0：优先 TextureView.getBitmap（与解码 Surface 同源，直读 GPU 纹理，零权限零兼容问题）
        val tv = videoTextureView
        if (tv != null && tv.isAvailable) {
            runCatching {
                val bmp = tv.bitmap ?: return@runCatching
                if (bmp.width <= 0 || bmp.height <= 0) {
                    bmp.recycle()
                    return@runCatching
                }
                Thread {
                    runCatching {
                        val out = java.io.ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                        bmp.recycle()
                        onDone(out.toByteArray())
                    }.onFailure { onDone(null) }
                }.apply { isDaemon = true; name = "shot-encode" }.start()
                return
            }
        }
        // 回退：PixelCopy 解码 Surface（旧路径）
        val sink = lastVideoSink
        val surface = sink?.surface
        if (sink == null || surface == null || !surface.isValid) {
            onDone(null)
            return
        }
        runCatching {
            val w = sink.streamWidth.coerceAtLeast(16)
            val h = sink.streamHeight.coerceAtLeast(16)
            val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val latch = java.util.concurrent.CountDownLatch(1)
            var ok = false
            android.view.PixelCopy.request(
                surface, bitmap,
                { result -> ok = (result == android.view.PixelCopy.SUCCESS); latch.countDown() },
                android.os.Handler(android.os.Looper.getMainLooper())
            )
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            if (ok) {
                Thread {
                    runCatching {
                        val out = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                        onDone(out.toByteArray())
                    }.onFailure { onDone(null) }
                }.start()
            } else onDone(null)
        }.onFailure { onDone(null) }
    }

    override fun mediaStore(): MediaStoreBridge {
        val ctx = appContext
            ?: return object : MediaStoreBridge {
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
        // v2.7.5：每次读取最新设置（storageRootTreeUri 热生效）：settings.json → storageRootTreeUri 字段
        //   （与桌面端 storageRoot 的读法一致；SAF 授权失效时桥内部自动回退默认 MediaStore）
        val treeUri = runCatching {
            val f = File(configDir(), "settings.json")
            if (f.exists()) {
                val txt = f.readText()
                val m = Regex("\"storageRootTreeUri\"\\s*:\\s*\"([^\"]*)\"").find(txt)
                m?.groupValues?.get(1) ?: ""
            } else ""
        }.getOrDefault("")
        return AndroidMediaStoreBridge(ctx) { treeUri }
    }

    override fun makeStreamMuxer(outputPath: String): StreamMuxer = AndroidStreamMuxer(outputPath)

    override fun notifyAppFocus(gained: Boolean) {
        setFocus(gained)
    }

    override fun hasAppFocus(): Boolean = hasFocus

    override fun setRecordingActive(active: Boolean) {
        val ctx = appContext ?: return
        if (active) RecordingService.start(ctx) else RecordingService.stop(ctx)
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
        // v2.7.5【图片剪贴板根因修复】：旧版直接 return null = 「本地复制/截图的图片永远发不出去」。
        // 现在真正读取：ClipData item 的 uri（图库/截图复制）/ intent（ACTION_SEND EXTRA_STREAM）
        // → 读字节 → 非 PNG（JPEG/WebP/HEIC）经 BitmapFactory 解码再压 PNG。
        // 注意：此方法做磁盘/Resolver IO，调用方应放在 IO 线程。
        return runCatching {
            val ctx = appCtxRef() ?: return null
            val cm = this.cm ?: return null
            val clip = cm.primaryClip ?: return null
            for (i in 0 until clip.itemCount) {
                val item = clip.getItemAt(i) ?: continue
                // ① 直接 URI（ClipData.newUri / 图库复制）
                val uri = item.uri
                    ?: runCatching { item.intent?.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM) }.getOrNull()
                if (uri != null) {
                    val bytes = runCatching {
                        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                    if (bytes != null && bytes.size > 8) {
                        if (bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
                            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
                        ) return bytes
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            val out = java.io.ByteArrayOutputStream()
                            if (bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) {
                                bmp.recycle()
                                return out.toByteArray()
                            }
                            bmp.recycle()
                        }
                    }
                }
                // ② 文本化 URI（部分 ROM 把 content URI coerce 成文本）
                val t = item.text?.toString() ?: continue
                if (t.startsWith("content://") || t.startsWith("file://")) {
                    val u = android.net.Uri.parse(t.trim())
                    val bytes = runCatching {
                        ctx.contentResolver.openInputStream(u)?.use { it.readBytes() }
                    }.getOrNull()
                    if (bytes != null && bytes.size > 8) {
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            val out = java.io.ByteArrayOutputStream()
                            if (bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) {
                                bmp.recycle()
                                return out.toByteArray()
                            }
                            bmp.recycle()
                        }
                    }
                }
            }
            null
        }.getOrNull()
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
        if (listener != null) addListener(listener) else listeners.toSet().forEach { removeListener(it) }
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    override fun addListener(listener: () -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
        cm?.addPrimaryClipChangedListener(clipListener)
    }

    override fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
        if (listeners.isEmpty()) cm?.removePrimaryClipChangedListener(clipListener)
    }

    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        listeners.forEach { runCatching { it() } }
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

/** Android 打印桥接：PDF 字节直通系统打印（v2.4.0 重写——不再重栅格化，保留矢量文字）
 *  旧版把 PDF 逐页渲染成位图再重新封装，导致文字模糊、大文档 OOM、部分打印机服务报错。
 */
private object AndroidPrintBridge : PrintBridge {
    override fun printPdf(pdfBytes: ByteArray, fileName: String, onResult: (Boolean, String?) -> Unit) {
        val act = AndroidPlatform.activity() ?: run {
            onResult(false, "无可用的 Activity（应用已退到后台）")
            return
        }
        runCatching {
            val printManager = act.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: run {
                onResult(false, "系统打印服务不可用")
                return
            }
            val jobName = "Selkies: $fileName"
            printManager.print(jobName, PdfPrintAdapter(pdfBytes, fileName, onResult), PrintAttributes.Builder().build())
        }.onFailure {
            onResult(false, "无法调起系统打印：${it.message}")
        }
    }

    /** PDF 直通适配器：onLayout 读页数元数据（不渲染内容），onWrite 原样拷贝字节 */
    private class PdfPrintAdapter(
        private val pdfBytes: ByteArray,
        private val fileName: String,
        private val onResult: (Boolean, String?) -> Unit
    ) : PrintDocumentAdapter() {

        private var pageCount = 1

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
            // 仅读取页数元数据（不渲染内容，代价极小）
            runCatching {
                val tmp = File(AndroidPlatform.appCtx()?.cacheDir, "print_meta.pdf")
                tmp.writeBytes(pdfBytes)
                ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { pageCount = it.pageCount }
                }
                tmp.delete()
            }
            val info = PrintDocumentInfo.Builder(fileName.ifBlank { "document.pdf" })
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(pageCount)
                .build()
            callback?.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out android.print.PageRange>?,
            destination: ParcelFileDescriptor?,
            cancellationSignal: android.os.CancellationSignal?,
            callback: WriteResultCallback?
        ) {
            runCatching {
                if (destination == null) {
                    callback?.onWriteFailed("无效的输出目标")
                    onResult(false, "无效的输出目标")
                    return
                }
                java.io.FileOutputStream(destination.fileDescriptor).use { it.write(pdfBytes) }
                callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                onResult(true, null)
            }.onFailure {
                callback?.onWriteFailed(it.message)
                onResult(false, it.message)
            }
        }
    }
}

/** Android 平台服务入口 */
actual fun getPlatformServices(): PlatformServices = AndroidPlatform

/** Android PNG 解码：BitmapFactory → asImageBitmap */
@Suppress("USELESS_CAST")
actual fun decodePngToImageBitmap(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap? =
    runCatching {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return@runCatching null
        bmp.asImageBitmap()
    }.getOrNull()
