package com.selkies.client.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selkies.client.AppState
import com.selkies.client.core.Keysyms
import com.selkies.client.core.Protocol
import com.selkies.client.core.SelkiesSession
import com.selkies.client.core.attachGamepadSession
import com.selkies.client.model.ServerEntry
import com.selkies.client.platform.keysymOrNull
import com.selkies.client.platform.primaryPressed
import com.selkies.client.platform.secondaryPressed
import com.selkies.client.platform.tertiaryPressed
import com.selkies.client.ui.panels.AppsPanel
import com.selkies.client.ui.panels.ClipboardPanel
import com.selkies.client.ui.panels.CompleteKeyboard
import com.selkies.client.ui.panels.FilesPanel
import com.selkies.client.ui.panels.GamepadPanel
import com.selkies.client.ui.panels.HardwarePanel
import com.selkies.client.ui.panels.HotkeysPanel
import com.selkies.client.ui.panels.ImeHostPanel
import com.selkies.client.ui.panels.PrintJobsPanel
import com.selkies.client.ui.panels.QuickShortcutBar
import com.selkies.client.ui.panels.SavedMediaPanel
import com.selkies.client.ui.panels.SessionSettingsPanel
import com.selkies.client.ui.panels.StatsPanel
import com.selkies.client.ui.panels.VirtualGamepadOverlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** 底部面板类型 */
enum class SessionPanel { NONE, KEYBOARD, HOTKEYS, CLIPBOARD, FILES, STATS, APPS, HARDWARE, PRINT, SETTINGS, GAMEPAD, MEDIA }

/** 触控输入模式 */
enum class InputMode { TOUCH, TRACKPAD }

private const val TOUCH_SCROLL_NOTCH_PX = 48f
private const val PINCH_NOTCH_RATIO = 1.189207115002721 // 2^(1/4)
private const val TAP_SLOP_PX = 18f          // 单指位移容差（超过视为拖动而非点击）
private const val LONG_PRESS_MS = 480L       // 长按右键阈值
private const val TAP_MAX_MS = 280L         // 轻触最大时长
private const val TWO_FINGER_TAP_MS = 260L  // 双指轻点右键窗口

/** 视频渲染表面（平台实现：Android=SurfaceView+MediaCodec，Desktop=Skia 位图） */
@Composable
expect fun VideoSurface(session: SelkiesSession, modifier: Modifier)

/**
 * 远程桌面会话界面：
 * - 视频等比自适应（aspectRatio 容器，点击坐标与画面严格一致）
 * - 零延迟触屏（Initial 通道直发：按下即 m 事件，无系统双击等待）
 * - 本地光标覆盖层（服务器 cursor 形状 + 本地位置回显）
 * - 悬浮键盘（分类/完整双模式）+ 系统输入法（缓冲+发送）+ 快捷按钮栏
 * - 可横向滑动工具栏
 */
@Composable
fun SessionScreen(state: AppState, server: ServerEntry) {
    val platform = state.platform
    var toast by remember { mutableStateOf<String?>(null) }
    val session = remember(server.id) {
        SelkiesSession(
            server = server,
            platform = platform,
            settingsProvider = { state.store.settings.value },
            onPrintDocument = { name, size ->
                toast = "收到远程打印文档：$name（${if (size > 1024) "${size / 1024}KB" else "$size B"}），已调起系统打印"
            }
        )
    }

    val status by session.status.collectAsState()
    val statusDetail by session.statusDetail.collectAsState()
    val stats by session.stats.collectAsState()
    val realizedRes by session.realizedResolution.collectAsState()
    val lastError by session.lastError.collectAsState()
    val role by session.role.collectAsState()
    val notices by session.notices.collectAsState()
    val cursorX by session.cursorX.collectAsState()
    val cursorY by session.cursorY.collectAsState()
    val cursorShape by session.cursorShape.collectAsState()
    // v2.4.0：打印任务/硬件状态（会话层真实状态驱动 UI）
    val printJobs by session.printJobs.collectAsState()
    val micSessionOn by session.micActive.collectAsState()
    val camSessionOn by session.webcamActive.collectAsState()
    var now by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    val connectAt = remember { System.currentTimeMillis() }

    var panel by remember { mutableStateOf(SessionPanel.NONE) }
    var fullscreen by remember { mutableStateOf(false) }
    var toolbarVisible by remember { mutableStateOf(true) }
    // v2.7.0：全屏工具栏自动隐藏（不遮挡远程控制区；边缘唤出）
    var toolbarShowAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var floatingKb by remember { mutableStateOf(false) }
    var quickBar by remember { mutableStateOf(false) }
    var showInputPicker by remember { mutableStateOf(false) }
    var inputMode by remember { mutableStateOf(if (state.store.settings.value.touchMouseMode) InputMode.TRACKPAD else InputMode.TOUCH) }
    var showModeSelector by remember { mutableStateOf(false) }
    var exitConfirm by remember { mutableStateOf(false) }
    var pendingDrag by remember { mutableStateOf(false) } // 触摸板双击后按住拖动

    // v2.5.0：截图/录屏/音量/保存引导
    var recording by remember { mutableStateOf(false) }
    var recordingSec by remember { mutableStateOf(0) }
    // v2.7.0：会话级录制状态（自动分段切换不闪烁；异常终止自动复位）
    val sessionRecording by session.recordingActive.collectAsState()
    var showVolumePopup by remember { mutableStateOf(false) }
    val savedEvent by session.savedMediaEvent.collectAsState()
    val appMuted by session.appMuted.collectAsState()
    val appVolume by session.appVolume.collectAsState()

    // v2.6.0：虚拟手柄（屏上手柄；无物理手柄也能玩远程游戏）
    var showVirtualPad by remember { mutableStateOf(false) }
    var virtualPadKbMap by remember { mutableStateOf(false) }
    val virtualEngine = remember(session) { com.selkies.client.ui.panels.VirtualGamepadEngine(session) }

    // v2.6.0：本地缩放/放大镜（只改本地显示，不动远程分辨率）
    var localZoom by remember { mutableStateOf(1f) }
    var zoomPan by remember { mutableStateOf(Offset.Zero) }  // 缩放后平移（视图 px）
    var showZoomControls by remember { mutableStateOf(false) }

    // v2.7.0：服务器能力（麦克风/摄像头/音频是否被服务器禁用；UI 据此禁用入口）
    val serverMicEnabled by session.serverMicEnabled.collectAsState()
    val serverWebcamEnabled by session.serverWebcamEnabled.collectAsState()
    val serverAudioEnabled by session.serverAudioEnabled.collectAsState()
    val audioStallNotice by session.audioStallNotice.collectAsState()

    // v2.6.0：工具栏自定义顺序
    val toolbarOrder = remember(state.store.settings.value.toolbarOrder) {
        val order = state.store.settings.value.toolbarOrder
            .split(',').filter { it.isNotBlank() }
        ToolbarDefaults.ALL.filter { it in order } + ToolbarDefaults.ALL.filter { it !in order }
    }

    // 视口几何
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var streamSize by remember { mutableStateOf(realizedRes ?: Pair(1280, 720)) }

    /** 平移范围限制：缩放后内容必须始终覆盖整个视图（放大镜拖拽不会把画面拖丢） */
    fun clampZoomPan(p: Offset, z: Float): Offset {
        val mx = ((z - 1f) * viewSize.width / 2f).coerceAtLeast(0f)
        val my = ((z - 1f) * viewSize.height / 2f).coerceAtLeast(0f)
        return Offset(p.x.coerceIn(-mx, mx), p.y.coerceIn(-my, my))
    }

    /** 在视图焦点 f 处缩放到新倍率（保持焦点下的内容不动） */
    fun zoomAt(f: Offset, newZoom: Float) {
        val nz = newZoom.coerceIn(1f, 5f)
        if (nz == localZoom) return
        val cx = viewSize.width / 2f
        val cy = viewSize.height / 2f
        // 内容点 q = c + (f - c - pan)/z 保持位于 f：pan' = f - c - (f - c - pan)*(z'/z)
        var np = Offset(
            (f.x - cx - (f.x - cx - zoomPan.x) * (nz / localZoom)),
            (f.y - cy - (f.y - cy - zoomPan.y) * (nz / localZoom))
        )
        if (nz == 1f) np = Offset.Zero
        zoomPan = clampZoomPan(np, nz)
        localZoom = nz
    }

    /** 本地平移（放大镜拖拽/方向按钮；纯本地，不控制远程） */
    fun panLocalBy(d: Offset) {
        if (localZoom <= 1.01f) return
        zoomPan = clampZoomPan(zoomPan + d, localZoom)
    }
    LaunchedEffect(realizedRes) {
        realizedRes?.let {
            streamSize = it
            println("[Selkies] 流分辨率: ${it.first}x${it.second}")
        }
    }

    // v2.6.0：竖屏/横屏自动旋转远端分辨率（可开关；仅自适应模式；手动分辨率不干预）
    var lastPortraitView by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(viewSize.width, viewSize.height) {
        if (viewSize.width <= 0 || viewSize.height <= 0) return@LaunchedEffect
        if (!state.store.settings.value.autoRotatePortraitRes) return@LaunchedEffect
        if (server.resolutionW > 0 || server.resolutionH > 0) return@LaunchedEffect
        if (status != SelkiesSession.Status.CONNECTED) return@LaunchedEffect
        val portrait = viewSize.height > viewSize.width
        if (lastPortraitView == null) {
            lastPortraitView = portrait
            return@LaunchedEffect  // 初始连接的分辨率已由 desiredResolution 处理
        }
        if (portrait != lastPortraitView) {
            lastPortraitView = portrait
            val (w, h) = streamSize
            if (w > 0 && h > 0 && w != h) {
                session.sendResolution(h, w)
                toast = if (portrait) "已切换竖屏分辨率 ${h}×${w}" else "已切换横屏分辨率 ${h}×${w}"
            }
        }
    }

    // 手势累积器
    val acc = remember { ScrollAccumulator() }
    val scope = rememberCoroutineScope()

    val viewOnly = server.viewOnly || role == "viewer"

    // 提示自动消失
    LaunchedEffect(toast) {
        if (toast != null) { delay(3500); toast = null }
    }
    // 会话级通知（打印/硬件/传输）→ toast
    LaunchedEffect(notices) {
        if (notices != null) { toast = notices; session.notices.value = null }
    }
    // 连接时长每秒刷新
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    // 返回键：逐层退出（面板→悬浮键盘/快捷栏→虚拟手柄→选择器→全屏→确认断开）
    // v2.6.0+：桌面端 ESC = 关闭最上层（enabled 仅在有可关闭层时）——
    //   根层 ESC 不消费，继续作为远程按键直通（游戏内 ESC 语义保持）；
    //   Android 真返回键恒启用（根层弹出退出确认，行为与旧版一致）
    val hasDismissibleLayer = panel != SessionPanel.NONE || showVolumePopup || showZoomControls ||
            showVirtualPad || floatingKb || quickBar || showModeSelector || showInputPicker || fullscreen
    PlatformBackHandler(enabled = hasDismissibleLayer || platform.isTouchDevice()) {
        when {
            panel != SessionPanel.NONE -> panel = SessionPanel.NONE
            showVolumePopup -> showVolumePopup = false
            showZoomControls -> showZoomControls = false
            showVirtualPad -> showVirtualPad = false
            floatingKb -> floatingKb = false
            quickBar -> quickBar = false
            showModeSelector -> showModeSelector = false
            showInputPicker -> showInputPicker = false
            fullscreen -> fullscreen = false
            else -> if (platform.isTouchDevice()) exitConfirm = true
        }
    }

    // 录屏中：每秒刷新时长 + 与真实录制状态同步（v2.7.0：观察会话级 recordingActive，
    // 分辨率切换自动分段期间保持 true，UI 不闪烁）
    LaunchedEffect(sessionRecording) {
        if (sessionRecording) {
            recording = true
            while (sessionRecording) {
                delay(1000)
                recordingSec = session.recorder.durationSec().toInt()
            }
        } else if (recording) {
            recording = false
        }
    }

    // 连接生命周期 + 剪贴板监听 + 桌面物理键直通 + 手柄挂载 + 焦点追赶
    DisposableEffect(session) {
        // ⚠️ 等待首次布局拿到真实视口尺寸再连接：SETTINGS 的 initialClientWidth/Height
        //    与自适应分辨率依赖它（旧版在布局前连接，恒发 1280x720）。
        val job = scope.launch {
            var waited = 0
            while (viewSize == IntSize.Zero && waited < 1500) { delay(50); waited += 50 }
            session.screenWidthPx = viewSize.width.takeIf { it > 0 } ?: 1280
            session.screenHeightPx = viewSize.height.takeIf { it > 0 } ?: 720
            session.connect()
            session.initAudioVolume()
        }

        // 焦点追踪（Android 后台剪贴板限制 → 回前台追赶）
        session.hasWindowFocus = { platform.hasAppFocus() }

        // 服务器端光标渲染进视频流（官方 SET_NATIVE_CURSOR_RENDERING）+ 本地覆盖层双保险
        session.post { session.connection.sendText("SET_NATIVE_CURSOR_RENDERING,1") }

        // Android 手柄 → js 指令
        if (state.store.settings.value.gamepadEnabled) {
            attachGamepadSession(session)
        }

        // 【剪贴板修复】多监听器：面板/UI 共存，不再互相顶掉
        val clip = platform.clipboard()
        var lastText: String? = clip.getText()
        var lastImageSig = clip.hasImage()
        val clipListener: () -> Unit = {
            val t = clip.getText()
            if (t != null && t != lastText) {
                lastText = t
                session.pushLocalClipboard(t)
            } else if (t == null) {
                // 文本为空但出现图像 → 图片剪贴板同步（本地截图/复制图片 → 远程）
                val img = clip.hasImage()
                if (img != lastImageSig) {
                    lastImageSig = img
                    if (img) {
                        clip.getImagePng()?.let { png -> session.pushLocalImage(png) }
                    }
                }
            }
        }
        clip.addListener(clipListener)

        // 焦点变化：剪贴板追赶（双向）
        // （平台层在 Activity/Window 回调里驱动 focus 标志；这里轮询触发会话层追赶）
        val focusJob = scope.launch {
            var lastFocus = true
            while (true) {
                delay(300)
                val f = platform.hasAppFocus()
                if (f != lastFocus) {
                    lastFocus = f
                    session.onAppFocusChanged(f)
                }
            }
        }

        // 桌面（AWT 分发器）物理键直通；无本地面板打开时才转发，避免在文本框输入时打到远程
        // v2.6.0：虚拟手柄键盘映射优先拦截（桌面无触屏的替代操作方案）
        // v2.7.0：返回 true = 已转发 → AWT 分发器消费（防 Compose 路径双发）；
        //        quickBar/放大镜控制等交互层也纳入门控
        platform.setPhysicalKeyHandler { code, down ->
            if (!viewOnly && panel == SessionPanel.NONE && !floatingKb && !quickBar &&
                !showZoomControls && !showVirtualPad && !showVolumePopup && !showInputPicker && !showModeSelector &&
                state.store.settings.value.physicalKeyboardPassthrough
            ) {
                val ks = platform.nativeKeyCodeToKeysym(code, 0)
                if (ks != null && ks > 0) {
                    // 虚拟手柄键盘映射：消费则不再发送远程
                    if (virtualEngine.isConnected && virtualPadKbMap &&
                        virtualEngine.handleKeysym(ks.toLong(), down)
                    ) {
                        return@setPhysicalKeyHandler true
                    }
                    session.sendKey(ks.toLong(), down)
                    return@setPhysicalKeyHandler true
                }
            }
            false
        }

        onDispose {
            job.cancel()
            focusJob.cancel()
            platform.setPhysicalKeyHandler(null)
            attachGamepadSession(null)
            clip.removeListener(clipListener)
            session.disconnect()
            platform.setFullscreen(false)
        }
    }

    // 全屏模式：工具栏闲置 4 秒自动隐藏（顶部/底部边缘触碰或鼠标移近唤出）
    LaunchedEffect(fullscreen, toolbarVisible) {
        if (fullscreen && toolbarVisible) {
            while (fullscreen && toolbarVisible) {
                delay(500)
                if (System.currentTimeMillis() - toolbarShowAt >= 4000) {
                    toolbarVisible = false
                }
            }
        }
    }

    LaunchedEffect(fullscreen) { platform.setFullscreen(fullscreen) }

    // ---------- 输入方式选择（软键盘 / 系统输入法 / 快捷按钮）----------
    fun openInputMethod(kind: String) {
        when (kind) {
            "float" -> { floatingKb = true; quickBar = false; panel = SessionPanel.NONE }
            "ime" -> { panel = SessionPanel.KEYBOARD; floatingKb = false; quickBar = false }
            "quick" -> { quickBar = true; floatingKb = false; panel = SessionPanel.NONE }
        }
        state.store.updateSettings { it.lastInputMethod = kind }
        showInputPicker = false
    }

    val onKeyboardButton: () -> Unit = {
        val last = state.store.settings.value.lastInputMethod
        if (last in listOf("float", "ime", "quick")) openInputMethod(last)
        else showInputPicker = true
    }
    val onKeyboardButtonLong: () -> Unit = { showInputPicker = true }

    // ---------- 坐标映射（视频等比容器内，画面与坐标严格一致；v2.6.0 本地缩放感知）----------
    // 视图点 p → 内容点 q：q = c + (p - c - pan) / zoom（c = 视图中心）
    // 内容点 q → 视图点 p：p = c + (q - c) * zoom + pan
    fun viewToContent(p: Offset): Offset {
        val cx = viewSize.width / 2f
        val cy = viewSize.height / 2f
        return Offset(cx + (p.x - cx - zoomPan.x) / localZoom, cy + (p.y - cy - zoomPan.y) / localZoom)
    }

    fun contentView(q: Offset): Offset {
        val cx = viewSize.width / 2f
        val cy = viewSize.height / 2f
        return Offset(cx + (q.x - cx) * localZoom + zoomPan.x, cy + (q.y - cy) * localZoom + zoomPan.y)
    }

    fun mapToStream(pos: Offset): Pair<Int, Int> {
        val q = viewToContent(pos)
        val vw = viewSize.width.toFloat()
        val vh = viewSize.height.toFloat()
        if (vw <= 0 || vh <= 0) return 0 to 0
        val sw = streamSize.first.toFloat()
        val sh = streamSize.second.toFloat()
        val scale = min(vw / sw, vh / sh)
        val dx = (vw - sw * scale) / 2f
        val dy = (vh - sh * scale) / 2f
        val x = ((q.x - dx) / scale).toInt().coerceIn(0, streamSize.first - 1)
        val y = ((q.y - dy) / scale).toInt().coerceIn(0, streamSize.second - 1)
        return x to y
    }

    /** 流坐标 → 视图坐标（光标覆盖层用；含本地缩放变换） */
    fun mapFromStream(sx: Float, sy: Float): Offset {
        val vw = viewSize.width.toFloat()
        val vh = viewSize.height.toFloat()
        if (vw <= 0 || vh <= 0) return Offset.Zero
        val sw = streamSize.first.toFloat()
        val sh = streamSize.second.toFloat()
        val scale = min(vw / sw, vh / sh)
        val dx = (vw - sw * scale) / 2f
        val dy = (vh - sh * scale) / 2f
        return contentView(Offset(sx * scale + dx, sy * scale + dy))
    }

    /** 视图像素位移 → 流坐标位移（触摸板相对移动用；缩放感知） */
    fun deltaToStream(d: Offset): Offset {
        val vw = viewSize.width.toFloat()
        val vh = viewSize.height.toFloat()
        if (vw <= 0 || vh <= 0) return d
        val scale = min(vw / streamSize.first.toFloat(), vh / streamSize.second.toFloat())
        if (scale <= 0f) return d
        return Offset(d.x / scale / localZoom, d.y / scale / localZoom)
    }

    // ---------- 点击原语 ----------
    fun leftClickAt(pos: Offset) {
        if (viewOnly) return
        val (x, y) = mapToStream(pos)
        session.sendMouseMove(x, y, Protocol.BUTTON_LEFT)
        session.sendMouseMove(x, y, 0)
    }

    fun rightClickAt(pos: Offset) {
        if (viewOnly) return
        val (x, y) = mapToStream(pos)
        session.sendMouseMove(x, y, Protocol.BUTTON_RIGHT)
        session.sendMouseMove(x, y, 0)
    }

    fun middleClickAt(pos: Offset) {
        if (viewOnly) return
        val (x, y) = mapToStream(pos)
        session.sendMouseMove(x, y, Protocol.BUTTON_MIDDLE)
        session.sendMouseMove(x, y, 0)
    }

    fun trackpadClick(mask: Int) {
        if (viewOnly) return
        session.sendMouseMoveRelative(0, 0, mask)
        session.sendMouseMoveRelative(0, 0, 0)
    }

    fun scrollNotches(vertical: Boolean, notches: Int) {
        if (viewOnly || notches == 0) return
        session.sendScroll(0, vertical, notches)
    }

    fun pinchNotches(n: Int) {
        if (viewOnly || n == 0) return
        session.post {
            session.connection.sendText(Protocol.keyEvent(true, Keysyms.XK_Control_L))
            session.sendScroll(0, true, n)
            session.connection.sendText(Protocol.keyEvent(false, Keysyms.XK_Control_L))
        }
    }

    /** 是否有本地交互层打开（此时物理键不得发往远程——文本框/面板要接收键盘） */
    fun inputOverlayOpen(): Boolean =
        panel != SessionPanel.NONE || floatingKb || quickBar || showZoomControls || showVirtualPad ||
                showVolumePopup || showInputPicker || showModeSelector || exitConfirm || savedEvent != null

    fun handleKeyEvent(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (viewOnly) return false
        if (!state.store.settings.value.physicalKeyboardPassthrough) return false
        // v2.7.0 修复：面板/键盘/弹窗打开时物理键必须留给本地 UI（文本框输入）。
        // 旧版无条件直发远程 → IME 面板/剪贴板/搜索等一切文本输入被拦截 = “无法录入”根因。
        if (inputOverlayOpen()) return false
        if (event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp) {
            val keysym = event.keysymOrNull()
            if (keysym != null && keysym > 0) {
                session.sendKey(keysym.toLong(), event.type == KeyEventType.KeyDown)
                return true
            }
        }
        return false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewSize = it }
            .onPreviewKeyEvent { handleKeyEvent(it) }
    ) {
        // ============ 视频与输入区域 ============
        Box(
            modifier = Modifier
                .fillMaxSize()
                // ⚠️【无法点击根因修复】统一的零延迟指针处理器：
                //   Initial 通道直发——手指按下立即发送 m 事件，无 detectTapGestures
                //   的双击等待（旧版每次点击延迟 ~300ms，且 SurfaceView 拉伸变形导致
                //   坐标错位；本处理器配合外层 aspectRatio 等比容器后坐标严格一致）。
                .pointerInput(inputMode, viewOnly, streamSize, viewSize) {
                    awaitPointerEventScope {
                        // ---- 触屏会话状态 ----
                        val touches = LinkedHashMap<PointerId, Offset>()
                        var pressStart = 0L
                        var pressPos = Offset.Zero
                        var touchMoved = false
                        var longFired = false
                        var leftDownSent = false        // TOUCH 模式：已发送左键按下
                        var twoFingerStart = 0L
                        var twoFingerDist = 0f
                        var twoFingerMoved = false
                        var lastTapUpAt = 0L            // 双击检测

                        // ---- v2.7.0：桌面鼠标拖拽 = 放大镜平移（纯本地）；全屏边缘唤出工具栏 ----
                        // 放大状态：左键按下不动→松开 = 远程点击（延迟决策）；拖动 = 本地平移；中键拖动 = 平移
                        var mousePanActive = false
                        var mousePanLast = Offset.Zero
                        var mousePressPos = Offset.Zero
                        var mousePressMoved = false
                        val edgeRevealPx = 36f
                        val mousePanSlop = 6f

                        fun endTouchSession(sendRelease: Boolean) {
                            if (leftDownSent && sendRelease && !viewOnly) {
                                if (inputMode == InputMode.TOUCH) {
                                    val (x, y) = mapToStream(pressPos)
                                    session.sendMouseMove(x, y, 0)
                                } else {
                                    session.sendMouseMoveRelative(0, 0, 0)
                                }
                            }
                            leftDownSent = false
                        }

                        while (true) {
                            // 长按计时：单指按下未移动未触发时，等到 LONG_PRESS_MS
                            val needTimer = touches.size == 1 && !touchMoved && !longFired && !pendingDrag && !viewOnly &&
                                    (inputMode == InputMode.TOUCH || inputMode == InputMode.TRACKPAD)
                            val waitMs = if (needTimer) {
                                (LONG_PRESS_MS - (System.currentTimeMillis() - pressStart)).coerceIn(16L, LONG_PRESS_MS)
                            } else 60_000L
                            val event = withTimeoutOrNull(waitMs) {
                                awaitPointerEvent(PointerEventPass.Initial)
                            }
                            if (event == null) {
                                // 超时：长按 → 右键
                                if (needTimer) {
                                    longFired = true
                                    if (inputMode == InputMode.TOUCH) {
                                        // 先释放已按下的左键，再发右键
                                        endTouchSession(true)
                                        rightClickAt(pressPos)
                                    } else {
                                        trackpadClick(Protocol.BUTTON_RIGHT)
                                    }
                                }
                                continue
                            }
                            val changes = event.changes
                            // ---------- 桌面鼠标（滚轮/按键/悬停）----------
                            if (changes.any { it.type == PointerType.Mouse }) {
                                when (event.type) {
                                    PointerEventType.Scroll -> {
                                        val sd = changes.fold(Offset.Zero) { a, c -> a + c.scrollDelta }
                                        if (sd != Offset.Zero) {
                                            val ctrl = event.keyboardModifiers.isCtrlPressed
                                            if (ctrl) {
                                                // v2.6.0：Ctrl+滚轮 = 本地缩放/放大镜（鼠标位置为焦点，只调本地）
                                                val f = changes.first().position
                                                zoomAt(f, localZoom * (1f - sd.y * 0.25f))
                                            } else {
                                                if (abs(sd.y) > 1e-4f) {
                                                    acc.wheelY += -sd.y * 3f
                                                    val n = acc.wheelY.toInt()
                                                    if (n != 0) { acc.wheelY -= n; scrollNotches(true, n) }
                                                }
                                                if (abs(sd.x) > 1e-4f) {
                                                    acc.wheelX += sd.x * 3f
                                                    val n = acc.wheelX.toInt()
                                                    if (n != 0) { acc.wheelX -= n; scrollNotches(false, n) }
                                                }
                                            }
                                            changes.forEach { it.consume() }
                                        }
                                    }
                                    PointerEventType.Press -> {
                                        val pos = changes.first().position
                                        mousePressPos = pos
                                        mousePressMoved = false
                                        when {
                                            event.tertiaryPressed() -> {
                                                // v2.7.0：放大状态中键拖拽 = 本地平移；未放大 = 中键点击
                                                if (localZoom > 1.01f) {
                                                    mousePanActive = true
                                                    mousePanLast = pos
                                                } else middleClickAt(pos)
                                            }
                                            event.secondaryPressed() -> rightClickAt(pos)
                                            event.primaryPressed() -> {
                                                // v2.7.0：放大状态左键拖拽 = 本地平移（点击在松手时判定）；未放大 = 左键点击
                                                if (localZoom > 1.01f) {
                                                    mousePanActive = true
                                                    mousePanLast = pos
                                                } else leftClickAt(pos)
                                            }
                                        }
                                        changes.forEach { it.consume() }
                                    }
                                    PointerEventType.Move -> {
                                        val pos = changes.first().position
                                        // v2.7.0：全屏时鼠标移到顶部/底部边缘 → 唤出工具栏
                                        if (fullscreen && !toolbarVisible &&
                                            (pos.y <= edgeRevealPx || pos.y >= viewSize.height - edgeRevealPx)
                                        ) {
                                            toolbarShowAt = System.currentTimeMillis()
                                            toolbarVisible = true
                                        }
                                        if (mousePanActive) {
                                            // 放大镜平移（本地）：拖拽画面，与远程无关
                                            val d = pos - mousePanLast
                                            if (abs(d.x) > 0.1f || abs(d.y) > 0.1f) mousePressMoved = true
                                            panLocalBy(d)
                                            mousePanLast = pos
                                            changes.forEach { it.consume() }
                                            continue
                                        }
                                        val mask = when {
                                            event.primaryPressed() -> Protocol.BUTTON_LEFT
                                            event.secondaryPressed() -> Protocol.BUTTON_RIGHT
                                            event.tertiaryPressed() -> Protocol.BUTTON_MIDDLE
                                            else -> 0
                                        }
                                        if (!viewOnly) {
                                            val (x, y) = mapToStream(pos)
                                            session.sendMouseMove(x, y, mask)
                                        }
                                    }
                                    PointerEventType.Release -> {
                                        val pos = changes.first().position
                                        if (mousePanActive) {
                                            mousePanActive = false
                                            // 放大状态未移动的松开 = 远程点击（延迟决策，保持可控）
                                            if (!mousePressMoved &&
                                                hypot(pos.x - mousePressPos.x, pos.y - mousePressPos.y) < mousePanSlop && !viewOnly
                                            ) {
                                                leftClickAt(pos)
                                            }
                                        } else if (!viewOnly) {
                                            val (x, y) = mapToStream(pos)
                                            session.sendMouseMove(x, y, 0)
                                        }
                                        changes.forEach { it.consume() }
                                    }
                                    else -> {}
                                }
                                continue
                            }

                            // ---------- 触屏 ----------
                            val touchChanges = changes.filter { it.type == PointerType.Touch }
                            if (touchChanges.isEmpty()) continue
                            when (event.type) {
                                PointerEventType.Press -> {
                                    touchChanges.forEach { touches[it.id] = it.position }
                                    if (touches.size == 1) {
                                        // 新的单指会话
                                        pressStart = System.currentTimeMillis()
                                        pressPos = touchChanges.first().position
                                        touchMoved = false
                                        longFired = false
                                        leftDownSent = false
                                        if (!viewOnly) {
                                            if (localZoom > 1.01f) {
                                                // v2.7.0：放大状态单指拖动 = 本地平移；点击延迟到松手判定
                                            } else if (inputMode == InputMode.TOUCH) {
                                                // 直接模式：按下即左键按下（零延迟）
                                                val (x, y) = mapToStream(pressPos)
                                                session.sendMouseMove(x, y, Protocol.BUTTON_LEFT)
                                                leftDownSent = true
                                            } else if (pendingDrag) {
                                                // 触摸板双击后按住 = 拖动
                                                session.sendMouseMoveRelative(0, 0, Protocol.BUTTON_LEFT)
                                                leftDownSent = true
                                            }
                                        }
                                    } else if (touches.size == 2) {
                                        // 双指手势开始：取消单指会话（释放左键）
                                        endTouchSession(true)
                                        twoFingerStart = System.currentTimeMillis()
                                        val ps = touches.values.toList()
                                        twoFingerDist = hypot(ps[0].x - ps[1].x, ps[0].y - ps[1].y)
                                        twoFingerMoved = false
                                        acc.scrollY = 0f
                                        acc.zoom = 1f
                                    }
                                    touchChanges.forEach { it.consume() }
                                }
                                PointerEventType.Move -> {
                                    touchChanges.forEach { c -> touches[c.id] = c.position }
                                    if (touches.size >= 2 && !viewOnly) {
                                        // 双指：捏合 = 本地缩放/放大镜（v2.6.0，以双指中点为焦点，只调本地显示）；
                                        // 放大后稳定距离拖动 = 本地平移；未放大时拖动 = 远程滚动（原行为）
                                        val ps = touches.values.toList()
                                        val d = hypot(ps[0].x - ps[1].x, ps[0].y - ps[1].y)
                                        val mid = Offset((ps[0].x + ps[1].x) / 2f, (ps[0].y + ps[1].y) / 2f)
                                        val pan = touchChanges.fold(Offset.Zero) { a, c -> a + c.positionChange() }
                                        if (twoFingerDist > 10f && d > 10f && abs(d - twoFingerDist) > 0.5f) {
                                            twoFingerMoved = true
                                            zoomAt(mid, localZoom * (d / twoFingerDist))
                                        } else if (localZoom > 1.01f && (abs(pan.x) > 0.5f || abs(pan.y) > 0.5f)) {
                                            // 放大状态：双指稳定距离拖动 = 平移画面（本地）
                                            twoFingerMoved = true
                                            zoomPan = clampZoomPan(zoomPan + pan, localZoom)
                                        } else if (abs(pan.y) > 0.5f) {
                                            twoFingerMoved = true
                                            acc.scrollY += -pan.y / 2f
                                            val notches = (acc.scrollY / TOUCH_SCROLL_NOTCH_PX).toInt()
                                            if (notches != 0) {
                                                acc.scrollY -= notches * TOUCH_SCROLL_NOTCH_PX
                                                scrollNotches(true, notches)
                                            }
                                        }
                                        twoFingerDist = d
                                    } else if (touches.size == 1) {
                                        val pos = touchChanges.first().position
                                        val delta = pos - pressPos
                                        if (!touchMoved && hypot(delta.x, delta.y) > TAP_SLOP_PX) {
                                            touchMoved = true
                                        }
                                        if (localZoom > 1.01f) {
                                            // v2.7.0：放大状态单指拖动 = 本地平移画面（不控远程；可拖到未显示区域后轻点）
                                            if (touchMoved) {
                                                panLocalBy(touchChanges.first().positionChange())
                                            }
                                            // v2.7.0：全屏时手指移到顶部/底部边缘 → 唤出工具栏
                                            if (fullscreen && !toolbarVisible &&
                                                (pos.y <= 36f || pos.y >= viewSize.height - 36f)
                                            ) {
                                                toolbarShowAt = System.currentTimeMillis()
                                                toolbarVisible = true
                                            }
                                        } else if (!viewOnly && touchMoved) {
                                            if (inputMode == InputMode.TOUCH) {
                                                val (x, y) = mapToStream(pos)
                                                session.sendMouseMove(x, y, if (leftDownSent) Protocol.BUTTON_LEFT else 0)
                                            } else {
                                                val d = deltaToStream(touchChanges.first().positionChange())
                                                session.sendMouseMoveRelative(
                                                    d.x.roundToInt(), d.y.roundToInt(),
                                                    if (leftDownSent || pendingDrag) Protocol.BUTTON_LEFT else 0
                                                )
                                                if (pendingDrag && !leftDownSent) leftDownSent = true
                                            }
                                        }
                                        pressPos = pos
                                    }
                                    touchChanges.forEach { it.consume() }
                                }
                                PointerEventType.Release -> {
                                    touchChanges.forEach { c -> touches.remove(c.id) }
                                    if (touches.isEmpty()) {
                                        val dur = System.currentTimeMillis() - twoFingerStart
                                        val wasTwoFinger = twoFingerStart > pressStart
                                        if (wasTwoFinger) {
                                            // 双指轻点 = 右键
                                            if (!twoFingerMoved && dur < TWO_FINGER_TAP_MS && !viewOnly) {
                                                if (inputMode == InputMode.TOUCH) rightClickAt(pressPos)
                                                else trackpadClick(Protocol.BUTTON_RIGHT)
                                            }
                                            twoFingerStart = 0L
                                        } else {
                                            val tapDur = System.currentTimeMillis() - pressStart
                                            when (inputMode) {
                                                InputMode.TOUCH -> {
                                                    if (localZoom > 1.01f) {
                                                        // v2.7.0：放大状态轻点 = 远程点击（延迟判定；坐标已含缩放映射）
                                                        if (!touchMoved && !longFired && tapDur < TAP_MAX_MS && !viewOnly) {
                                                            leftClickAt(pressPos)
                                                        }
                                                    } else {
                                                        // 松手：释放左键（按下时已发）
                                                        endTouchSession(true)
                                                    }
                                                }
                                                InputMode.TRACKPAD -> {
                                                    if (!viewOnly && !touchMoved && !longFired) {
                                                        if (tapDur < TAP_MAX_MS) {
                                                            // 轻触 = 左键（两次快速轻触自然构成双击，
                                                            // 远程桌面自行识别，无需额外注入）
                                                            trackpadClick(Protocol.BUTTON_LEFT)
                                                        }
                                                        // 超长按且未发左键 → 长按右键已在超时分支处理
                                                    } else {
                                                        endTouchSession(true)
                                                    }
                                                    pendingDrag = false
                                                }
                                            }
                                        }
                                        leftDownSent = false
                                    }
                                    touchChanges.forEach { it.consume() }
                                }
                                else -> {}
                            }
                        }
                    }
                }
        ) {
            // 等比自适应视频容器：画面按流宽高比居中缩放（不拉伸变形）
            // v2.6.0：本地缩放/放大镜变换（graphicsLayer 只影响显示；坐标映射已同步缩放感知）
            val ratio = streamSize.second.takeIf { it > 0 }?.let { streamSize.first.toFloat() / it } ?: (16f / 9f)
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = localZoom
                        scaleY = localZoom
                        translationX = zoomPan.x
                        translationY = zoomPan.y
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
                    },
                contentAlignment = Alignment.Center
            ) {
                VideoSurface(session, Modifier.aspectRatio(ratio).fillMaxSize())
            }

            // ============ 本地光标覆盖层 ============
            // 服务器推送的 cursor 形状（PNG+热点）+ 本地回显位置，与视频等比同坐标
            if (status == SelkiesSession.Status.CONNECTED && cursorX >= 0f && cursorY >= 0f && !viewOnly) {
                CursorOverlay(
                    cursorX = cursorX,
                    cursorY = cursorY,
                    shape = cursorShape,
                    mapFromStream = ::mapFromStream
                )
            }

            // ============ FPS/带宽/延迟悬浮条（官方 StreamStrip 等效，设置开关） ============
            if (status == SelkiesSession.Status.CONNECTED &&
                state.store.settings.value.showFpsOverlay
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(top = 52.dp, start = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.55f)
                ) {
                    Text(
                        buildString {
                            if (stats.fps > 0) append(stats.fps).append(" fps")
                            if (stats.bitrateMbps > 0) append("  ·  ").append(
                                String.format(java.util.Locale.US, "%.1f Mbps", stats.bitrateMbps)
                            )
                            if (stats.latencyMs > 0) append("  ·  ").append(stats.latencyMs).append(" ms")
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // ============ 状态覆盖层 ============
        if (status == SelkiesSession.Status.CONNECTING || status == SelkiesSession.Status.IDLE) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black.copy(alpha = 0.78f)) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(statusDetail.ifBlank { "正在连接服务器…" }, color = Color.White)
                }
            }
        } else if (status == SelkiesSession.Status.FAILED || status == SelkiesSession.Status.CLOSED) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black.copy(alpha = 0.82f)) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        if (status == SelkiesSession.Status.FAILED) "连接失败" else "已断开",
                        color = if (status == SelkiesSession.Status.FAILED) MaterialTheme.colorScheme.error else Color.White,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(lastError ?: statusDetail, color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (status == SelkiesSession.Status.FAILED || lastError != null) {
                            Button(onClick = { session.connect() }) { Text("重试") }
                        }
                        Button(onClick = { state.navigate(com.selkies.client.Screen.ServerList) }) { Text("返回") }
                    }
                }
            }
        } else if (status == SelkiesSession.Status.RECONNECTING) {
            Surface(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.94f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(statusDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        // ============ 顶部信息栏 ============
        AnimatedVisibility(
            visible = toolbarVisible && status == SelkiesSession.Status.CONNECTED,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.45f),
                shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
            ) {
                Row(
                    modifier = Modifier.statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { exitConfirm = true }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            server.name.ifBlank { server.url },
                            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium
                        )
                        Text(
                            buildString {
                                append(streamSize.first).append('×').append(streamSize.second)
                                if (server.resolutionW > 0) append(" · 固定")
                                if (stats.fps > 0) append(" · ").append(stats.fps).append("fps")
                                if (stats.latencyMs > 0) append(" · ").append(stats.latencyMs).append("ms")
                                // 连接时长
                                val dur = (now - connectAt) / 1000
                                val h = dur / 3600; val m = (dur % 3600) / 60; val s = dur % 60
                                if (h > 0) append(String.format(java.util.Locale.US, " · %d:%02d:%02d", h, m, s))
                                else append(String.format(java.util.Locale.US, " · %d:%02d", m, s))
                                if (viewOnly) append(" · 只读（viewer 通道）")
                                if (inputMode == InputMode.TRACKPAD) append(" · 触摸板")
                            },
                            color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp
                        )
                    }
                    IconButton(onClick = { toolbarVisible = false }) {
                        Icon(Icons.Filled.Fullscreen, "隐藏工具栏", tint = Color.White)
                    }
                }
            }
        }

        // 隐藏工具栏后的唤出按钮
        if (!toolbarVisible && status == SelkiesSession.Status.CONNECTED) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 4.dp)
                    .size(width = 120.dp, height = 26.dp)
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(13.dp))
                    .clickable {
                        toolbarShowAt = System.currentTimeMillis()
                        toolbarVisible = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (fullscreen) "显工具栏（或碰屏幕边缘）" else "显示工具栏",
                    color = Color.White, fontSize = 10.sp,
                    modifier = Modifier.padding(2.dp)
                )
            }
        }

        // ============ 底部工具栏（可横向滑动——修「功能栏无法滑动」）============
        AnimatedVisibility(
            visible = toolbarVisible && status == SelkiesSession.Status.CONNECTED,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                        .navigationBarsPadding()
                        // v2.7.0：全屏自动隐藏计时刷新（正在滑动/操作工具栏时不隐藏）
                        .pointerInput(fullscreen) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent()
                                    if (fullscreen) toolbarShowAt = System.currentTimeMillis()
                                }
                            }
                        },
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // v2.6.0：按用户自定义顺序渲染（设置→功能栏排序；默认 ToolbarDefaults.ALL）
                    toolbarOrder.forEach { key ->
                        when (key) {
                            "keyboard" -> ToolButton(
                                Icons.Filled.Keyboard, "键盘",
                                tint = if (floatingKb || panel == SessionPanel.KEYBOARD || quickBar) Color(0xFF60A5FA) else Color.White,
                                onLongClick = onKeyboardButtonLong,
                                onClick = onKeyboardButton
                            )
                            "hotkeys" -> ToolButton(Icons.Filled.Bolt, "快捷键") { panel = if (panel == SessionPanel.HOTKEYS) SessionPanel.NONE else SessionPanel.HOTKEYS }
                            "clipboard" -> ToolButton(Icons.Filled.ContentPaste, "剪贴板") { panel = if (panel == SessionPanel.CLIPBOARD) SessionPanel.NONE else SessionPanel.CLIPBOARD }
                            "files" -> ToolButton(Icons.Filled.Folder, "文件") { panel = if (panel == SessionPanel.FILES) SessionPanel.NONE else SessionPanel.FILES }
                            "print" -> ToolButton(
                                Icons.Filled.Print,
                                "打印" + if (printJobs.isNotEmpty()) "(${printJobs.size})" else "",
                                tint = if (printJobs.any { it.status == "error" }) MaterialTheme.colorScheme.error else Color.White
                            ) { panel = if (panel == SessionPanel.PRINT) SessionPanel.NONE else SessionPanel.PRINT }
                            "mic" -> ToolButton(
                                if (micSessionOn) Icons.Filled.Mic else Icons.Filled.MicOff, "麦克风",
                                tint = when {
                                    micSessionOn -> Color(0xFF4ADE80)
                                    !serverMicEnabled -> Color(0xFF6B7280)
                                    else -> Color.White
                                }
                            ) {
                                if (!serverMicEnabled) {
                                    toast = "服务器已禁用麦克风（microphone_enabled=false），无法上行"
                                } else if (micSessionOn) session.stopMicUplink() else session.startMicUplink()
                            }
                            "camera" -> ToolButton(
                                if (camSessionOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff, "摄像头",
                                tint = when {
                                    camSessionOn -> Color(0xFF4ADE80)
                                    !serverWebcamEnabled -> Color(0xFF6B7280)
                                    else -> Color.White
                                }
                            ) {
                                if (!serverWebcamEnabled) {
                                    toast = "服务器已禁用摄像头（webcam_enabled=false），无法上行"
                                } else if (camSessionOn) session.stopWebcamUplink() else session.startWebcamUplink()
                            }
                            "hardware" -> ToolButton(Icons.Filled.DevicesOther, "硬件") { panel = if (panel == SessionPanel.HARDWARE) SessionPanel.NONE else SessionPanel.HARDWARE }
                            "quality" -> ToolButton(Icons.Filled.Tune, "画质") { panel = if (panel == SessionPanel.SETTINGS) SessionPanel.NONE else SessionPanel.SETTINGS }
                            "touchmode" -> if (platform.isTouchDevice()) {
                                ToolButton(
                                    if (inputMode == InputMode.TRACKPAD) Icons.Filled.Mouse else Icons.Filled.TouchApp,
                                    if (inputMode == InputMode.TRACKPAD) "触摸板" else "触屏",
                                    tint = if (inputMode == InputMode.TRACKPAD) Color(0xFF60A5FA) else Color.White
                                ) { showModeSelector = true }
                            }
                            "apps" -> ToolButton(Icons.Filled.Apps, "应用") { panel = if (panel == SessionPanel.APPS) SessionPanel.NONE else SessionPanel.APPS }
                            "stats" -> ToolButton(Icons.Filled.QueryStats, "统计") { panel = if (panel == SessionPanel.STATS) SessionPanel.NONE else SessionPanel.STATS }
                            "screenshot" -> ToolButton(Icons.Filled.PhotoCamera, "截图") { session.captureScreenshot() }
                            "record" -> ToolButton(
                                if (recording) Icons.Filled.StopCircle else Icons.Filled.Videocam,
                                if (recording) "停止(${recordingSec}s)" else "录屏",
                                tint = if (recording) Color(0xFFEF4444) else Color.White
                            ) {
                                if (recording) {
                                    session.stopRecording()
                                } else {
                                    if (session.startRecording()) recording = true
                                    else toast = "无法开始录制（存储不可用）"
                                }
                            }
                            "volume" -> ToolButton(
                                if (appMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                                if (appMuted) "取消静音" else "音量",
                                tint = if (appMuted || appVolume <= 0f) Color(0xFFF59E0B) else Color.White
                            ) { showVolumePopup = !showVolumePopup }
                            "gamepad" -> ToolButton(Icons.Filled.SportsEsports, "手柄") { panel = if (panel == SessionPanel.GAMEPAD) SessionPanel.NONE else SessionPanel.GAMEPAD }
                            "virtualpad" -> ToolButton(
                                Icons.Filled.VideogameAsset, if (showVirtualPad) "关闭手柄" else "虚拟手柄",
                                tint = if (showVirtualPad) Color(0xFF4ADE80) else Color.White
                            ) { showVirtualPad = !showVirtualPad }
                            "zoom" -> ToolButton(
                                Icons.Filled.ZoomIn, if (localZoom > 1.01f) "${(localZoom * 100).toInt()}%" else "放大镜",
                                tint = if (localZoom > 1.01f) Color(0xFF60A5FA) else Color.White
                            ) {
                                showZoomControls = true
                            }
                            "media" -> ToolButton(Icons.Filled.PhotoLibrary, "媒体库") { panel = if (panel == SessionPanel.MEDIA) SessionPanel.NONE else SessionPanel.MEDIA }
                            "fullscreen" -> ToolButton(
                                if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, "全屏",
                                tint = if (fullscreen) Color(0xFF60A5FA) else Color.White
                            ) { fullscreen = !fullscreen }
                        }
                    }
                }
            }
        }

        // ============ 底部面板 ============
        if (panel != SessionPanel.NONE && status == SelkiesSession.Status.CONNECTED) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.62f),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                // v2.7.0: shadowElevation 移除——Skiko SOFTWARE 渲染（Win7/8 强制软渲染 + Xvfb）下 Surface(shape+shadow) 内容丢失 bug
            ) {
                Column {
                    // 面板标题栏
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            when (panel) {
                                SessionPanel.KEYBOARD -> "系统输入法（输入完成后点发送）"
                                SessionPanel.HOTKEYS -> "快捷键（可固定到快捷按钮栏）"
                                SessionPanel.CLIPBOARD -> "剪贴板"
                                SessionPanel.FILES -> "文件传输"
                                SessionPanel.STATS -> "会话统计"
                                SessionPanel.APPS -> "远程应用"
                                SessionPanel.HARDWARE -> "远程硬件（麦克风/摄像头/扬声器）"
                                SessionPanel.PRINT -> "打印任务"
                                SessionPanel.SETTINGS -> "画质与性能（实时生效）"
                                SessionPanel.GAMEPAD -> "手柄（实时状态/振动测试）"
                                SessionPanel.MEDIA -> "媒体库（截图/录屏/下载）"
                                SessionPanel.NONE -> ""
                            },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { panel = SessionPanel.NONE }) { Text("关闭") }
                    }
                    when (panel) {
                        SessionPanel.KEYBOARD -> ImeHostPanel(session)
                        SessionPanel.HOTKEYS -> HotkeysPanel(state, session)
                        SessionPanel.CLIPBOARD -> ClipboardPanel(state, session)
                        SessionPanel.FILES -> FilesPanel(state, session)
                        SessionPanel.STATS -> StatsPanel(session)
                        SessionPanel.APPS -> AppsPanel(session)
                        SessionPanel.HARDWARE -> HardwarePanel(session)
                        SessionPanel.PRINT -> PrintJobsPanel(session)
                        SessionPanel.SETTINGS -> SessionSettingsPanel(state, session)
                        SessionPanel.GAMEPAD -> GamepadPanel(session) { showVirtualPad = true }
                        SessionPanel.MEDIA -> SavedMediaPanel(state)
                        SessionPanel.NONE -> {}
                    }
                }
            }
        }

        // ============ 悬浮窗可拖动完整键盘（分类模式 / 完整模式 151 键）============
        if (!viewOnly && status == SelkiesSession.Status.CONNECTED) {
            // 完整键盘默认缩放：屏宽恰好容纳 23 单位（v2.6.0：不再依赖固定 1f）
            val kbDensity = androidx.compose.ui.platform.LocalDensity.current
            val fullKbFit = with(kbDensity) {
                (viewSize.width.takeIf { it > 0 } ?: 1280).toDp().value / (23f * 34f + 8f)
            }
            CompleteKeyboard(
                session = session,
                visible = floatingKb,
                autoScaleHint = fullKbFit,
                savedScale = state.store.settings.value.keyboardScale,
                savedFullScale = state.store.settings.value.keyboardFullScale,
                savedFullMode = state.store.settings.value.keyboardFullMode,
                onScaleChange = { s ->
                    state.store.updateSettings {
                        if (it.keyboardFullMode) it.keyboardFullScale = s else it.keyboardScale = s
                    }
                },
                onFullModeChange = { f -> state.store.updateSettings { it.keyboardFullMode = f } },
                onDismiss = { floatingKb = false }
            )
        }

        // ============ 快捷按钮栏（CTL/ALT/WIN 粘滞 + 常用单发 + 固定快捷键）============
        if (!viewOnly && status == SelkiesSession.Status.CONNECTED) {
            QuickShortcutBar(
                session = session,
                hotkeys = state.store.hotkeys.value,
                visible = quickBar,
                onDismiss = { quickBar = false },
                onOpenManager = { quickBar = false; panel = SessionPanel.HOTKEYS }
            )
        }

        // ============ 音量弹窗（应用内独立音量：滑条 + 静音；不依赖系统音量） ============
        if (showVolumePopup && status == SelkiesSession.Status.CONNECTED) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 96.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.75f),
                // v2.7.0: shadowElevation 移除——SOFTWARE 渲染内容丢失 bug
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "远程声音音量",
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        androidx.compose.material3.IconButton(onClick = { session.setAppMuted(!appMuted) }) {
                            Icon(
                                if (appMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                                if (appMuted) "取消静音" else "静音",
                                tint = if (appMuted) Color(0xFFEF4444) else Color.White
                            )
                        }
                    }
                    androidx.compose.material3.Slider(
                        value = if (appVolume < 0) 1f else appVolume,
                        onValueChange = { session.setAppVolume(it) },
                        valueRange = 0f..1f,
                        colors = androidx.compose.material3.SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color(0xFF60A5FA),
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        )
                    )
                    Text(
                        "${(if (appVolume < 0) 1f else appVolume * 100).toInt()}%  ·  仅调节本应用播放音量，不影响系统",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall
                    )
                    TextButton(onClick = { showVolumePopup = false }) { Text("关闭") }
                }
            }
        }

        // ============ v2.7.0 本地缩放控制（放大镜：滑条 + 加减/重置 + 方向移动按钮 + 百分比；只调本地显示） ============
        if (status == SelkiesSession.Status.CONNECTED) {
            // 放大中常驻指示（点按打开控制面板：方向按钮/滑条/重置）
            if (localZoom > 1.01f && !showZoomControls) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 52.dp, end = 6.dp)
                        .clickable { showZoomControls = true },
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.6f)
                ) {
                    Text(
                        "${(localZoom * 100).toInt()}% ⤢",
                        color = Color(0xFF7DD3FC),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            if (showZoomControls) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = 96.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "本地缩放（放大镜）",
                                color = Color.White,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${(localZoom * 100).toInt()}%",
                                color = Color(0xFF7DD3FC),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        androidx.compose.material3.Slider(
                            value = localZoom,
                            onValueChange = { zoomAt(Offset(viewSize.width / 2f, viewSize.height / 2f), it) },
                            valueRange = 1f..5f,
                            colors = androidx.compose.material3.SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color(0xFF60A5FA),
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            )
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            androidx.compose.material3.OutlinedButton(onClick = {
                                zoomAt(Offset(viewSize.width / 2f, viewSize.height / 2f), localZoom / 1.25f)
                            }) { Icon(Icons.Filled.ZoomOut, null, tint = Color.White); Text(" 缩小") }
                            androidx.compose.material3.OutlinedButton(onClick = {
                                zoomAt(Offset(viewSize.width / 2f, viewSize.height / 2f), localZoom * 1.25f)
                            }) { Icon(Icons.Filled.ZoomIn, null, tint = Color.White); Text(" 放大") }
                            androidx.compose.material3.OutlinedButton(onClick = {
                                localZoom = 1f; zoomPan = Offset.Zero
                            }) { Text("重置 100%") }
                            TextButton(onClick = { showZoomControls = false }) { Text("关闭") }
                        }
                        Spacer(Modifier.height(6.dp))
                        // v2.7.0：手动移动画面按钮（上下左右；纯本地平移，不控制远程）
                        Text(
                            "移动画面（本地，不影响远程）",
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelMedium
                        )
                        val panStepX = (viewSize.width * 0.18f).coerceAtLeast(40f)
                        val panStepY = (viewSize.height * 0.18f).coerceAtLeast(40f)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.OutlinedButton(onClick = {
                                panLocalBy(Offset(-panStepX, 0f))
                            }) { Icon(Icons.Filled.KeyboardArrowLeft, "左移", tint = Color.White); Text(" 左") }
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                androidx.compose.material3.OutlinedButton(onClick = {
                                    panLocalBy(Offset(0f, -panStepY))
                                }) { Icon(Icons.Filled.KeyboardArrowUp, "上移", tint = Color.White); Text(" 上") }
                                androidx.compose.material3.OutlinedButton(onClick = {
                                    panLocalBy(Offset(0f, panStepY))
                                }) { Icon(Icons.Filled.KeyboardArrowDown, "下移", tint = Color.White); Text(" 下") }
                            }
                            androidx.compose.material3.OutlinedButton(onClick = {
                                panLocalBy(Offset(panStepX, 0f))
                            }) { Icon(Icons.Filled.KeyboardArrowRight, "右移", tint = Color.White); Text(" 右") }
                        }
                        Text(
                            "只缩放/移动本机画面，不改变远程分辨率与远程操作 · 触屏：双指捏合缩放，放大后单指拖动平移 · 桌面：Ctrl+滚轮缩放，放大后鼠标拖拽/中键拖拽平移",
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        // ============ v2.6.0 虚拟手柄悬浮面板（屏上手柄：完整/精简/赛车模式 + 只读 + 键盘映射） ============
        if (!viewOnly && status == SelkiesSession.Status.CONNECTED) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 70.dp)) {
                VirtualGamepadOverlay(
                    engine = virtualEngine,
                    session = session,
                    visible = showVirtualPad,
                    initialMode = state.store.settings.value.virtualGamepadMode,
                    keyboardMapping = virtualPadKbMap,
                    onModeChange = { m -> state.store.updateSettings { it.virtualGamepadMode = m } },
                    onKeyboardMappingChange = { virtualPadKbMap = it },
                    onDismiss = { showVirtualPad = false }
                )
            }
        }

        // ============ 保存引导对话框（截图/录屏/下载完成 → 打开/文件夹/复制路径） ============
        savedEvent?.let { ev ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { session.savedMediaEvent.value = null },
                title = {
                    Text(
                        when {
                            ev.error != null -> "保存失败"
                            ev.kind == "screenshot" -> "截图已保存"
                            ev.kind == "recording" -> "录屏已保存"
                            else -> "已保存"
                        }
                    )
                },
                text = {
                    Column {
                        Text(
                            when {
                                ev.error != null -> ev.error
                                else -> "${ev.name}${if (ev.detail.isNotBlank()) "\n${ev.detail}" else ""}"
                            }
                        )
                        ev.path?.let { p ->
                            Text(
                                p,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    Row {
                        if (ev.path != null) {
                            androidx.compose.material3.TextButton(onClick = {
                                platform.openFile(ev.path)
                            }) { Text("打开") }
                            androidx.compose.material3.TextButton(onClick = {
                                platform.openFileLocation(ev.path)
                            }) { Text("所在文件夹") }
                            androidx.compose.material3.TextButton(onClick = {
                                platform.clipboard().setText(ev.path)
                                toast = "已复制完整路径"
                            }) { Text("复制路径") }
                        }
                        androidx.compose.material3.TextButton(onClick = {
                            session.savedMediaEvent.value = null
                        }) { Text("完成") }
                    }
                }
            )
        }

        // ============ v2.7.0：音频流状态提示（服务器音频管线无数据时自动恢复/透明化） ============
        if (audioStallNotice != null && status == SelkiesSession.Status.CONNECTED) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 52.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.72f)
            ) {
                Text(
                    audioStallNotice ?: "",
                    color = Color(0xFFF59E0B),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        // ============ 录屏指示器（红点闪烁 + 时长） ============
        if (recording) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 52.dp, end = 8.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(Color(0xFFEF4444), androidx.compose.foundation.shape.CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        formatRecSec(recordingSec),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        // ============ 轻提示（打印到达等） ============
        AnimatedVisibility(
            visible = toast != null,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.statusBarsPadding().padding(top = 8.dp)
            ) {
                Text(
                    toast ?: "",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // ============ 输入方式选择器（软键盘 / 系统输入法 / 快捷按钮）============
        if (showInputPicker) {
            AlertDialog(
                onDismissRequest = { showInputPicker = false },
                title = { Text("选择输入方式") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ModeOption(
                            title = "悬浮完整键盘（151 键）",
                            desc = "可拖动、双指缩放的 PC 键盘：默认按键自动分类，可切换完整模式看全部按键；修饰键粘滞组合",
                            selected = floatingKb
                        ) { openInputMethod("float") }
                        ModeOption(
                            title = "系统输入法（先输入后发送）",
                            desc = "直接唤起本机输入法（支持中文），文字先显示在输入区，点「发送」一次性发到远程",
                            selected = panel == SessionPanel.KEYBOARD
                        ) { openInputMethod("ime") }
                        ModeOption(
                            title = "快捷按钮栏",
                            desc = "CTL/ALT/WIN 粘滞 + TAB/ESC/ENTER 单发 + 固定的常用快捷键，一键直发",
                            selected = quickBar
                        ) { openInputMethod("quick") }
                        // v2.4.0：唤起操作系统自带屏幕键盘（Windows osk / Linux onboard / macOS 键盘查看器；Android=系统输入法）
                        ModeOption(
                            title = "系统屏幕键盘（操作系统自带）",
                            desc = "调起本机操作系统自带的屏幕键盘，点击物理屏幕键盘输入——适合触屏笔记本/平板",
                            selected = false
                        ) {
                            showInputPicker = false
                            if (!platform.showOnScreenKeyboard()) {
                                toast = "当前系统未找到可用的屏幕键盘"
                            }
                        }
                        Text(
                            "选择后下次点“键盘”直接打开该方式；长按“键盘”可重新选择。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { showInputPicker = false }) { Text("取消") } }
            )
        }

        // ============ 输入模式选择器 ============
        if (showModeSelector) {
            AlertDialog(
                onDismissRequest = { showModeSelector = false },
                title = { Text("远程控制模式") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ModeOption(
                            title = "触屏模式（直接）",
                            desc = "手指点哪里就在哪里点击，按下即响应；拖动即拖拽，双指滚动/缩放，长按或双指轻点=右键",
                            selected = inputMode == InputMode.TOUCH
                        ) {
                            inputMode = InputMode.TOUCH
                            pendingDrag = false
                            showModeSelector = false
                        }
                        ModeOption(
                            title = "鼠标模式（触摸板）",
                            desc = "手指滑动移动远程光标（相对），轻点=左键，长按/双指点=右键，双击后按住拖动，双指滚动",
                            selected = inputMode == InputMode.TRACKPAD
                        ) {
                            inputMode = InputMode.TRACKPAD
                            pendingDrag = false
                            showModeSelector = false
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { showModeSelector = false }) { Text("取消") } }
            )
        }

        // ============ 返回确认 ============
        if (exitConfirm) {
            AlertDialog(
                onDismissRequest = { exitConfirm = false },
                title = { Text("断开连接") },
                text = {
                    Text(
                        if (recording) "确定断开远程会话并返回服务器列表？\n\n⏺ 录屏正在进行中：断开后将自动保存录屏文件。"
                        else "确定断开远程会话并返回服务器列表？"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        exitConfirm = false
                        if (recording) session.stopRecording()
                        state.navigate(com.selkies.client.Screen.ServerList)
                    }) { Text("断开返回", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { exitConfirm = false }) { Text("取消") } }
            )
        }
    }
}

private fun formatRecSec(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(java.util.Locale.US, "%02d:%02d", m, s)
}

// =====================================================================
// 本地光标覆盖层
// =====================================================================

@Composable
private fun CursorOverlay(
    cursorX: Float,
    cursorY: Float,
    shape: com.selkies.client.model.CursorShape?,
    mapFromStream: (Float, Float) -> Offset
) {
    // 服务器 PNG 形状解码（按 handle 缓存；平台各自解码：Android=BitmapFactory / Desktop=Skia）
    val bitmap: ImageBitmap? = shape?.let { sh ->
        remember(sh.handle) {
            runCatching {
                val bytes = java.util.Base64.getDecoder().decode(sh.pngBase64)
                com.selkies.client.platform.decodePngToImageBitmap(bytes)
            }.getOrNull()
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val pos = mapFromStream(cursorX, cursorY)
        val sh = shape
        if (bitmap != null && sh != null) {
            val scale = 1f  // 服务器已按显示 DPI 提供合适尺寸
            drawImage(
                image = bitmap,
                dstOffset = androidx.compose.ui.unit.IntOffset(
                    (pos.x - sh.hotX * scale).roundToInt(),
                    (pos.y - sh.hotY * scale).roundToInt()
                ),
                dstSize = androidx.compose.ui.unit.IntSize(
                    (sh.width * scale).roundToInt().coerceAtLeast(1),
                    (sh.height * scale).roundToInt().coerceAtLeast(1)
                )
            )
        } else {
            // 回退：绘制经典箭头
            val arrow = Path().apply {
                moveTo(pos.x, pos.y)
                lineTo(pos.x + 12f, pos.y + 18f)
                lineTo(pos.x + 6.5f, pos.y + 17.5f)
                lineTo(pos.x + 4f, pos.y + 24f)
                lineTo(pos.x + 1.5f, pos.y + 23f)
                lineTo(pos.x + 4f, pos.y + 16f)
                close()
            }
            drawPath(arrow, Color.Black.copy(alpha = 0.8f))
            drawPath(arrow, Color.White, style = Stroke(width = 1.5f))
        }
    }
}

@Composable
private fun ModeOption(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 手势累积器 */
private class ScrollAccumulator {
    var scrollY = 0f
    var scrollX = 0f
    var wheelY = 0f
    var wheelX = 0f
    var zoom = 1f
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Color.White,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = { println("[Selkies][工具栏] 点击: $label"); onClick() },
                onLongClick = onLongClick
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, color = tint, fontSize = 9.sp, maxLines = 1)
    }
}

/** v2.6.0 工具栏功能键定义（设置→功能栏排序自定义顺序） */
object ToolbarDefaults {
    val ALL = listOf(
        "keyboard", "hotkeys", "clipboard", "files", "print", "mic", "camera",
        "hardware", "quality", "touchmode", "apps", "stats",
        "screenshot", "record", "volume", "gamepad", "virtualpad", "zoom",
        "media", "fullscreen"
    )

    /** 功能键 → 中文名（设置界面显示） */
    val LABELS = mapOf(
        "keyboard" to "键盘", "hotkeys" to "快捷键", "clipboard" to "剪贴板", "files" to "文件",
        "print" to "打印", "mic" to "麦克风", "camera" to "摄像头", "hardware" to "硬件",
        "quality" to "画质", "touchmode" to "触屏/触摸板", "apps" to "应用", "stats" to "统计",
        "screenshot" to "截图", "record" to "录屏", "volume" to "音量", "gamepad" to "手柄",
        "virtualpad" to "虚拟手柄", "zoom" to "放大镜", "media" to "媒体库", "fullscreen" to "全屏"
    )
}
