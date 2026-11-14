package com.selkies.client.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
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
import com.selkies.client.ui.panels.VirtualMousePanel
import com.selkies.client.ui.panels.VirtualGamepadOverlay
import kotlinx.coroutines.Dispatchers
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

// ---- v2.7.13 放大镜手势状态机阈值：双指外扩/内收 + 停顿保持（不再即时缩放）----
// 用户实测「双手一放就进放大」根因：旧版双指距离变化 >0.5px 即触发 zoomAt。
// 新语义：外扩达标 + 停顿 3 秒才进入/加档；内收达标 + 停顿才减档/退出（退出同样 3 秒）。
private const val ZOOM_EXPAND_PX = 44f       // 外扩判定：距基准距离增加量
private const val ZOOM_CONTRACT_PX = 36f     // 内收判定：距基准距离减少量
private const val ZOOM_HOLD_MS = 3000L       // 保持静止时长（进入/加档/减档/退出统一 3 秒）
private const val ZOOM_HOLD_JITTER_PX = 2.5f // 事件间距离抖动容差（≤视为静止，不打断倒计时）
private const val ZOOM_HOLD_DRIFT_PX = 7f    // 保持期累计漂移容差（超过=仍在移动，倒计时重置）
private const val ZOOM_HOLD_PAN_PX = 6f      // 保持期平移容差（超过=取消倒计时）

/** -Dselkies.zoomdbg：放大镜缩放链路追踪（滚轮 ctrl 判定/焦点） */
private val ZOOMDBG = System.getProperty("selkies.zoomdbg") != null

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
    // 【v2.7.6】工具栏唤出把手不再常驻：隐藏工具栏后只显示 4 秒即自动淡出（零遮挡）。
    //   用户截图实证：「显示工具栏」胶囊常驻顶部挡住远程 Edge 标签栏/关闭按钮。
    //   恢复工具栏三条路径：①顶/底边缘向内滑 >28px（v2.7.6 起不限全屏）②鼠标移近顶/底边缘
    //   （桌面）③顶部边缘轻触 → 把手闪现 4s 可点击。每次隐藏/闪现 epoch+1 重启计时。
    var handleVisible by remember { mutableStateOf(false) }
    var handleEpoch by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(handleEpoch, toolbarVisible) {
        if (toolbarVisible) {
            handleVisible = false
        } else {
            handleVisible = true
            kotlinx.coroutines.delay(4000)
            handleVisible = false
        }
    }
    var floatingKb by remember { mutableStateOf(false) }
    // v2.7.2：悬浮键盘文本合成条焦点（false = 键盘打开但无文本框聚焦 → 桌面物理键直通远程）
    var kbTextFocused by remember { mutableStateOf(false) }
    var quickBar by remember { mutableStateOf(false) }
    var showInputPicker by remember { mutableStateOf(false) }
    var inputMode by remember { mutableStateOf(if (state.store.settings.value.touchMouseMode) InputMode.TRACKPAD else InputMode.TOUCH) }
    var showModeSelector by remember { mutableStateOf(false) }
    var exitConfirm by remember { mutableStateOf(false) }
    // v2.7.17：防系统手势误触（真机边缘拖动被返回/主页劫持的修复开关）
    var edgeGestureGuard by remember {
        mutableStateOf(state.store.settings.value.edgeGestureGuard)
    }

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
    // v2.7.13 虚拟鼠标面板（工具栏「虚拟鼠标」按钮弹出：触控板+实体键+滚动滑条）
    var showVirtualMouse by remember { mutableStateOf(false) }
    val virtualEngine = remember(session) { com.selkies.client.ui.panels.VirtualGamepadEngine(session) }
    // 【v2.7.7】手柄触感反馈（按键振动；Android 真机生效，桌面空操作）
    androidx.compose.runtime.DisposableEffect(virtualEngine) {
        virtualEngine.onHaptic = {
            if (state.store.settings.value.virtualGamepadHaptics) platform.vibrate(12)
        }
        onDispose { virtualEngine.onHaptic = null }
    }

    // ============ v2.7.7：角色切换 / 顶替应对 / 认证弹窗状态 ============
    var showRoleSwitcher by remember { mutableStateOf(false) }
    var roleSwitchSlot by remember { mutableStateOf(2) }   // 只读时的手柄槽位选择（2..4）
    val takeoverEvent by session.takeoverEvent.collectAsState()
    var authDialogShown by remember { mutableStateOf(false) }  // 防止 AUTH_REQUIRED 重复弹
    var authUser by remember { mutableStateOf(server.username) }
    var authPass by remember { mutableStateOf(server.password) }
    var authToken by remember { mutableStateOf(server.token) }
    var authShowPass by remember { mutableStateOf(false) }
    var authRemember by remember { mutableStateOf(true) }

    // 【测试钩子】-Dselkies.testtakeover：连接后 8s 自动注入顶替事件（GUI 自动化验证弹窗渲染/交互）
    LaunchedEffect(Unit) {
        if (System.getProperty("selkies.testtakeover") != null) {
            delay(8000)
            if (session.status.value == SelkiesSession.Status.CONNECTED) {
                session.takeoverEvent.value = com.selkies.client.model.TakeoverEvent("test-injected")
                println("[Selkies][测试] 已注入顶替事件（testtakeover）")
            }
        }
    }

    // v2.6.0：本地缩放/放大镜（只改本地显示，不动远程分辨率）
    var localZoom by remember { mutableStateOf(1f) }
    var zoomPan by remember { mutableStateOf(Offset.Zero) }  // 缩放后平移（视图 px）
    var showZoomControls by remember { mutableStateOf(false) }

    // v2.7.0：服务器能力（麦克风/摄像头/音频是否被服务器禁用；UI 据此禁用入口）
    // v2.7.5【麦克风「点不了」根因修复】：补收 locked 状态——官方语义（websockets_mode.py 实证）
    //   仅 (value=false 且 locked=true) 才是真禁用；未锁定的 false 只是默认关，客户端上行数据即开启。
    //   旧版工具栏按钮只看 value 一律拦截 = 「网页能用麦克风、App 点不了」的真正根因（v2.7.3 漏修此处）。
    val serverMicEnabled by session.serverMicEnabled.collectAsState()
    val serverWebcamEnabled by session.serverWebcamEnabled.collectAsState()
    val serverAudioEnabled by session.serverAudioEnabled.collectAsState()
    val serverMicLocked by session.serverMicLocked.collectAsState()
    val serverWebcamLocked by session.serverWebcamLocked.collectAsState()
    val audioStallNotice by session.audioStallNotice.collectAsState()
    // v2.7.4：音频警告用户已关闭标记（新警告出现时重置；同一警告不再打扰）
    var audioNoticeDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(audioStallNotice) {
        // 新的警告文案出现 → 重新允许显示
        if (audioStallNotice != null) audioNoticeDismissed = false
    }

    // v2.6.0：工具栏自定义顺序
    // 【v2.7.7 修复】旧版 ToolbarDefaults.ALL.filter { it in order } 只做“包含过滤”——顺序仍是默认序，
    //   用户的自定义排序从未生效（设置页拖什么顺序工具栏都不变）。改为：保存的顺序优先 +
    //   未列出的键按默认顺序补齐。
    val toolbarOrder = remember(state.store.settings.value.toolbarOrder) {
        val saved = state.store.settings.value.toolbarOrder
            .split(',').map { it.trim() }.filter { it.isNotBlank() && it in ToolbarDefaults.ALL }
        saved.distinct() + ToolbarDefaults.ALL.filter { it !in saved }
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

    // 【v2.7.12】美观虚拟鼠标状态：手势处理器写入（press/release/pulse）→ 覆盖层动画读取。
    //   纯快照状态写入，不消费事件、不改变任何手势判定控制流 → 与拖动/放大镜零冲突。
    val vcursor = remember { VirtualCursorState() }
    // 【v2.7.13】放大镜手势倒计时指示状态（手势处理器写入 → 指示环覆盖层读取，纯渲染层）
    val zoomHold = remember { ZoomHoldState() }
    val haptic = LocalHapticFeedback.current
    // 【v2.7.12】虚拟鼠标开关（设置持久化；-Dselkies.vcursor=false 可测试强制关）
    val virtualCursorOn = System.getProperty("selkies.vcursor")?.toBoolean()
        ?: state.store.settings.value.virtualCursorEnabled

    // 【v2.7.12 防御】滚轮缩放的 ctrl 判定兜底：PointerEvent.keyboardModifiers 依赖
    //   AWT 修饰键状态传递（实测 Robot/真实键盘可用）；当 Compose 无聚焦节点或
    //   修饰键状态未随事件携带时可能漏报。根 Box onPreviewKeyEvent 在聚焦链存在时
    //   能看到全部物理键按下/抬起——维护 Ctrl 物理状态作 OR 兜底（零成本防御，
    //   不改变任何既有判定语义）。Android 真实键盘走 keyboardModifiers 正常。
    var ctrlPhysicallyDown by remember { mutableStateOf(false) }

    // 【v2.7.7】含会话中切换（switchRole 运行时覆盖）的只读状态；role==viewer 是服务器/自请求确认
    val viewOnly = session.effectiveViewOnly || role == "viewer"

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
            showVirtualPad || floatingKb || quickBar || showModeSelector || showInputPicker || fullscreen ||
            showRoleSwitcher || showVirtualMouse
    PlatformBackHandler(enabled = hasDismissibleLayer || platform.isTouchDevice()) {
        when {
            panel != SessionPanel.NONE -> panel = SessionPanel.NONE
            showVolumePopup -> showVolumePopup = false
            showZoomControls -> showZoomControls = false
            showVirtualMouse -> showVirtualMouse = false
            showVirtualPad -> showVirtualPad = false
            floatingKb -> floatingKb = false
            quickBar -> quickBar = false
            showModeSelector -> showModeSelector = false
            showInputPicker -> showInputPicker = false
            showRoleSwitcher -> showRoleSwitcher = false
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
                        // v2.7.5：图像读取（content resolver IO）移出主线程（旧版主线程读=卡顿/ANR风险）
                        scope.launch(Dispatchers.IO) {
                            val png = runCatching { clip.getImagePng() }.getOrNull()
                            if (png != null) session.pushLocalImage(png)
                        }
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
        // v2.7.2：悬浮键盘打开但文本合成条未聚焦时不再屏蔽物理键（桌面用户开软键盘后
        //        仍可用物理键盘打字到远程；仅文本框聚焦时才留给本地输入）
        platform.setPhysicalKeyHandler { code, down ->
            if (!viewOnly && panel == SessionPanel.NONE && (!floatingKb || !kbTextFocused) && !quickBar &&
                !showZoomControls && !showVirtualPad && !showVirtualMouse && !showVolumePopup &&
                !showInputPicker && !showModeSelector &&
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
            // v2.7.17：离开会话恢复系统边缘手势
            platform.setGestureExclusion(false)
        }
    }

    // v2.7.17 防系统手势误触：会话期间排除屏幕左/右/底边缘的系统手势（Android 10+
    // 手势导航下贴边拖动文件/框选文字会被返回/主页手势劫持 = 真机专属 bug，桌面测不到）。
    // 设置可关（edgeGestureGuard）；旋转屏幕后本 Composable 重建，LaunchedEffect 重跑自动重铺。
    LaunchedEffect(edgeGestureGuard) {
        platform.setGestureExclusion(edgeGestureGuard)
    }

    // 全屏模式：工具栏闲置自动隐藏（顶部/底部边缘内滑或鼠标移近唤出）
    // v2.7.1：4s → 2.5s（更快让位给远程画面）；且全屏中触摸远程画面会立即隐藏（见触屏 Press 分支）
    LaunchedEffect(fullscreen, toolbarVisible) {
        if (fullscreen && toolbarVisible) {
            while (fullscreen && toolbarVisible) {
                delay(500)
                if (System.currentTimeMillis() - toolbarShowAt >= 2500) {
                    toolbarVisible = false
                }
            }
        }
    }

    LaunchedEffect(fullscreen) { platform.setFullscreen(fullscreen) }

    // v2.7.4：进入全屏时自动收起快捷按钮栏——修「全屏底部工具栏按钮挡住视线」；
    // 退出全屏不自动恢复（用户按需再开）。悬浮键盘保留（打字场景常见），可手动关。
    LaunchedEffect(fullscreen) {
        if (fullscreen && quickBar) quickBar = false
    }

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
    // 【v2.7.12】所有点击路径都打一次涟漪（vcursor.pulse）——视觉反馈与远程事件同步。
    fun leftClickAt(pos: Offset) {
        if (viewOnly) return
        val (x, y) = mapToStream(pos)
        vcursor.pulse()
        session.sendMouseMove(x, y, Protocol.BUTTON_LEFT)
        session.sendMouseMove(x, y, 0)
    }

    fun rightClickAt(pos: Offset) {
        if (viewOnly) return
        val (x, y) = mapToStream(pos)
        vcursor.pulse()
        session.sendMouseMove(x, y, Protocol.BUTTON_RIGHT)
        session.sendMouseMove(x, y, 0)
    }

    fun middleClickAt(pos: Offset) {
        if (viewOnly) return
        val (x, y) = mapToStream(pos)
        vcursor.pulse()
        session.sendMouseMove(x, y, Protocol.BUTTON_MIDDLE)
        session.sendMouseMove(x, y, 0)
    }

    fun trackpadClick(mask: Int) {
        if (viewOnly) return
        vcursor.pulse()
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
                showVirtualMouse || showVolumePopup || showInputPicker || showModeSelector ||
                exitConfirm || savedEvent != null

    fun handleKeyEvent(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        // 【v2.7.12】修饰键物理状态跟踪（先于一切门控；桌面 keyboardModifiers 恒空的兜底）
        if (event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp) {
            val k = event.key
            if (k == Key.CtrlLeft || k == Key.CtrlRight) {
                ctrlPhysicallyDown = event.type == KeyEventType.KeyDown
            }
        }
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
                        var lastTapUpAt = 0L            // v2.7.11：TRACKPAD 上次轻点完成时刻（双击窗口判定）

                        // ---- v2.7.13 放大镜手势状态机：外扩/内收 + 停顿保持 → 缩放/退出（不再即时缩放）----
                        var tfBaseDist = 0f       // 双指基准距离（会话开始/触发后重置）
                        var tfHoldRefDist = 0f    // 倒计时参考距离（检测保持期漂移）
                        var tfHoldTarget = 0      // 0=无 1=进入放大 2=再加一档 3=减一档/退出
                        var tfHoldSince = 0L      // 倒计时起始时刻
                        var tfHoldActive = false  // 倒计时进行中

                        // ---- v2.7.0：桌面鼠标拖拽 = 放大镜平移（纯本地）；全屏边缘唤出工具栏 ----
                        // 放大状态：左键按下不动→松开 = 远程点击（延迟决策）；拖动 = 本地平移；中键拖动 = 平移
                        var mousePanActive = false
                        var mousePanLast = Offset.Zero
                        var mousePressPos = Offset.Zero
                        var mousePressMoved = false
                        val edgeRevealPx = 36f
                        val mousePanSlop = 6f

                        // ---- v2.7.1：触屏边缘滑动唤出工具栏（防误触）----
                        // 起点：pressOrigin（不动）；pressFromEdge：-1=从顶边缘按下 / 1=从底边缘按下 / 0=非边缘。
                        // 唤出条件 = 起点在边缘带(12px)内且向内滑动 >28px——单纯点击/操作远程画面
                        // 顶部（如浏览器标签栏、任务栏）不会误触唤出工具栏。
                        var pressOrigin = Offset.Zero
                        var pressFromEdge = 0

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
                            // 【v2.7.12】远程左键释放 → 虚拟鼠标视觉同步释放（幂等；双指手势接管时也走这里）
                            vcursor.release()
                        }

                        /** 【v2.7.13】取消放大镜手势倒计时（指示环一并隐藏） */
                        fun cancelZoomHold() {
                            tfHoldActive = false
                            tfHoldTarget = 0
                            zoomHold.cancel()
                        }

                        /** 【v2.7.13】倒计时完成 → 执行缩放动作（触觉反馈；重置基准防连续误触发） */
                        fun fireZoomHold() {
                            val ps = touches.values.toList()
                            val mid = if (ps.size >= 2) Offset((ps[0].x + ps[1].x) / 2f, (ps[0].y + ps[1].y) / 2f) else pressPos
                            if (ZOOMDBG) println("[Selkies][ZoomDbg] hold-fire target=$tfHoldTarget zoom=$localZoom")
                            when (tfHoldTarget) {
                                1 -> zoomAt(mid, 2f)                                     // 进入放大
                                2 -> zoomAt(mid, (localZoom + 1f).coerceAtMost(5f))      // 再加一档
                                3 -> zoomAt(mid, (localZoom - 1f).coerceIn(1f, 5f))      // 减一档（到 1x = 退出）
                            }
                            tfBaseDist = twoFingerDist
                            cancelZoomHold()
                            twoFingerMoved = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }

                        while (true) {
                            // 长按计时：单指按下未移动未触发时，等到 LONG_PRESS_MS
                            // 【v2.7.11 拖动保护】已发送左键按下（leftDownSent）时绝不触发长按右键——
                            //   按住文字/文件稍一停顿（>480ms）就被掐断左键+弹右键菜单 =
                            //   「拖动经常拖不了/选择文字弄不了」主根因。右键有双指轻点兜底（TOUCH）
                            //   或长按仅限未发键时（TRACKPAD）。
                            val needTimer = touches.size == 1 && !touchMoved && !longFired && !viewOnly && !leftDownSent &&
                                    (inputMode == InputMode.TOUCH || inputMode == InputMode.TRACKPAD)
                            var waitMs = if (needTimer) {
                                (LONG_PRESS_MS - (System.currentTimeMillis() - pressStart)).coerceIn(16L, LONG_PRESS_MS)
                            } else 60_000L
                            // 【v2.7.13】放大镜手势倒计时：60ms tick 驱动进度环推进与触发判定
                            if (tfHoldActive && touches.size >= 2) waitMs = minOf(waitMs, 60L)
                            val event = withTimeoutOrNull(waitMs) {
                                awaitPointerEvent(PointerEventPass.Initial)
                            }
                            if (event == null) {
                                // 超时：单指长按 → 右键；双指保持倒计时 → 推进/触发
                                if (needTimer) {
                                    longFired = true
                                    if (inputMode == InputMode.TOUCH) {
                                        // 先释放已按下的左键，再发右键
                                        endTouchSession(true)
                                        rightClickAt(pressPos)
                                    } else {
                                        trackpadClick(Protocol.BUTTON_RIGHT)
                                    }
                                } else if (tfHoldActive && touches.size >= 2) {
                                    val ps = touches.values.toList()
                                    if (ps.size >= 2) {
                                        val d = hypot(ps[0].x - ps[1].x, ps[0].y - ps[1].y)
                                        val now = System.currentTimeMillis()
                                        if (abs(d - tfHoldRefDist) > ZOOM_HOLD_DRIFT_PX) {
                                            // 仍在缓慢移动 → 倒计时重置（等待真正静止）
                                            tfHoldRefDist = d
                                            tfHoldSince = now
                                        }
                                        val held = now - tfHoldSince
                                        if (held >= ZOOM_HOLD_MS) {
                                            fireZoomHold()
                                        } else {
                                            val label = when (tfHoldTarget) {
                                                1 -> "进入放大"
                                                2 -> "再放大一档"
                                                else -> "缩小 / 退出"
                                            }
                                            zoomHold.update(
                                                Offset((ps[0].x + ps[1].x) / 2f, (ps[0].y + ps[1].y) / 2f),
                                                held.toFloat() / ZOOM_HOLD_MS, label
                                            )
                                        }
                                    } else {
                                        cancelZoomHold()
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
                                            // 【v2.7.12 防御】ctrl 判定 = 事件修饰键 OR 物理键跟踪兜底
                                            val ctrl = event.keyboardModifiers.isCtrlPressed || ctrlPhysicallyDown
                                            if (ZOOMDBG) println("[Selkies][ZoomDbg] scroll sd=$sd ctrl=$ctrl (kb=${event.keyboardModifiers.isCtrlPressed} phys=$ctrlPhysicallyDown)")
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
                                                // v2.7.0：放大状态左键拖拽 = 本地平移（点击在松手时判定）
                                                // 【v2.7.11】未放大：标准鼠标语义——按下只发左键按下（松手时抬起）。
                                                //   旧版此处发 down+up 完整点击对 → 远程收到 down,up,down+move…
                                                //   拖动前多出一次点击（选择起点被重置）= 桌面拖文字/拖文件不灵敏根因之一。
                                                if (localZoom > 1.01f) {
                                                    mousePanActive = true
                                                    mousePanLast = pos
                                                } else if (!viewOnly) {
                                                    // 【v2.7.12】虚拟鼠标按下反馈（涟漪+缩放）
                                                    vcursor.press()
                                                    val (x, y) = mapToStream(pos)
                                                    session.sendMouseMove(x, y, Protocol.BUTTON_LEFT)
                                                }
                                            }
                                        }
                                        changes.forEach { it.consume() }
                                    }
                                    PointerEventType.Move -> {
                                        val pos = changes.first().position
                                        // 【v2.7.6】鼠标移到顶部/底部边缘 → 唤出工具栏（不再限全屏：
                                        //   非全屏隐藏工具栏后同样可唤回，把手已非常驻必须留唤出途径）
                                        if (!toolbarVisible &&
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
                                        // 【v2.7.12】虚拟鼠标视觉释放（未按下时为幂等空操作）
                                        vcursor.release()
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
                                        pressOrigin = pressPos
                                        // v2.7.1：起点是否在屏幕顶/底边缘带内（用于「向内滑动唤出工具栏」判定）
                                        pressFromEdge = when {
                                            pressPos.y <= 12f -> -1
                                            pressPos.y >= viewSize.height - 12f -> 1
                                            else -> 0
                                        }
                                        // 【v2.7.6】顶部/底部边缘轻触（未滑动）→ 闪现把手 4s（可发现性；
                                        //   把手已不常驻，用户需要入口提示；触摸照常发送远程不受影响）
                                        if (pressFromEdge != 0 && !toolbarVisible) {
                                            handleEpoch++
                                        }
                                        touchMoved = false
                                        longFired = false
                                        leftDownSent = false
                                        // v2.7.1【全屏工具栏不挡触屏】：全屏 + 工具栏可见时，触摸远程画面
                                        // → 工具栏立即隐藏让位；本次触摸照常发送远程（等效点击穿透）。
                                        // 远程画面顶部/底部元素从此可以直接点到，无需等 4 秒超时。
                                        if (fullscreen && toolbarVisible) {
                                            toolbarVisible = false
                                        }
                                        if (!viewOnly) {
                                            // 【v2.7.12】触摸按下 → 虚拟鼠标按下反馈（涟漪+触点光环+微缩放）。
                                            //   仅写状态，不改变任何手势控制流。
                                            vcursor.press()
                                            if (inputMode == InputMode.TOUCH) {
                                                // 直接模式：按下即左键按下（零延迟）。
                                                // 【v2.7.11】放大状态同样直发——拖动/选择文字是远程操作；
                                                //   旧版放大状态（zoom>1.01，双指缩放后未恢复 1.0）单指被抢为
                                                //   本地平移不发远程 =「拖不了」根因之一。平移由双指承担。
                                                val (x, y) = mapToStream(pressPos)
                                                session.sendMouseMove(x, y, Protocol.BUTTON_LEFT)
                                                leftDownSent = true
                                            } else {
                                                // 【v2.7.11】触摸板双击拖动实装：双击窗口内再按 = 按住左键。
                                                //   第二击不动松手 = 自然构成双击（down,up,down,up）；
                                                //   第二击按住拖动 = 按住左键拖拽。旧版 pendingDrag 恒 false
                                                //   （从未有人置 true）= 触摸板模式拖动完全不可用的根因。
                                                if (System.currentTimeMillis() - lastTapUpAt < TAP_MAX_MS) {
                                                    session.sendMouseMoveRelative(0, 0, Protocol.BUTTON_LEFT)
                                                    leftDownSent = true
                                                }
                                            }
                                        }
                                    } else if (touches.size == 2) {
                                        // 双指手势开始：取消单指会话（释放左键）
                                        endTouchSession(true)
                                        twoFingerStart = System.currentTimeMillis()
                                        val ps = touches.values.toList()
                                        twoFingerDist = hypot(ps[0].x - ps[1].x, ps[0].y - ps[1].y)
                                        twoFingerMoved = false
                                        // 【v2.7.13】基准距离 = 会话起始距离；倒计时清零
                                        tfBaseDist = twoFingerDist
                                        cancelZoomHold()
                                        acc.scrollY = 0f
                                        acc.zoom = 1f
                                    }
                                    touchChanges.forEach { it.consume() }
                                }
                                PointerEventType.Move -> {
                                    touchChanges.forEach { c -> touches[c.id] = c.position }
                                    if (touches.size >= 2 && !viewOnly) {
                                        // 【v2.7.13 放大镜手势状态机】双指不再即时缩放（旧版距离变化
                                        //   >0.5px 即 zoomAt = 「双手一放就进放大」根因）。新语义：
                                        //   外扩 ≥44px + 保持静止 3 秒 → 进入放大/再加一档；
                                        //   内收 ≥36px + 保持静止 3 秒 → 减一档/退出（缩回 1x）。
                                        //   快速捏合不触发任何缩放；双指轻点右键/竖滑滚轮/放大后稳定拖平移保留。
                                        val ps = touches.values.toList()
                                        val d = hypot(ps[0].x - ps[1].x, ps[0].y - ps[1].y)
                                        val mid = Offset((ps[0].x + ps[1].x) / 2f, (ps[0].y + ps[1].y) / 2f)
                                        val pan = touchChanges.fold(Offset.Zero) { a, c -> a + c.positionChange() }
                                        val distDelta = d - twoFingerDist
                                        if (twoFingerDist > 10f && d > 10f && abs(distDelta) > ZOOM_HOLD_JITTER_PX) {
                                            // 距离显著变化 = 捏合进行中：评估外扩/内收意图（不缩放；
                                            //   倒计时随移动重置，静止后由主循环 tick 推进）
                                            twoFingerMoved = true
                                            cancelZoomHold()
                                            when {
                                                d > tfBaseDist + ZOOM_EXPAND_PX -> {
                                                    tfHoldTarget = if (localZoom <= 1.01f) 1 else 2
                                                    tfHoldRefDist = d
                                                    tfHoldSince = System.currentTimeMillis()
                                                    tfHoldActive = true
                                                }
                                                d < tfBaseDist - ZOOM_CONTRACT_PX && localZoom > 1.01f -> {
                                                    tfHoldTarget = 3
                                                    tfHoldRefDist = d
                                                    tfHoldSince = System.currentTimeMillis()
                                                    tfHoldActive = true
                                                }
                                            }
                                        } else if (tfHoldActive) {
                                            // 倒计时进行中：明显平移/距离突变 → 取消（微小抖动容忍）
                                            if (hypot(pan.x, pan.y) > ZOOM_HOLD_PAN_PX ||
                                                abs(distDelta) > ZOOM_HOLD_JITTER_PX
                                            ) cancelZoomHold()
                                        } else if (localZoom > 1.01f && (abs(pan.x) > 0.5f || abs(pan.y) > 0.5f)) {
                                            // 放大状态：双指稳定距离拖动 = 平移画面（本地）——原行为保留
                                            twoFingerMoved = true
                                            zoomPan = clampZoomPan(zoomPan + pan, localZoom)
                                        } else if (abs(pan.y) > 0.5f) {
                                            // 未放大：双指竖滑 = 远程滚动——原行为保留
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
                                        // v2.7.1【边缘滑动唤出·防误触】：起点在屏幕顶/底边缘带(12px)内且向内滑动
                                        // >28px 才唤出工具栏；任何缩放状态均可用。
                                        // 【v2.7.6】不再限全屏：非全屏隐藏工具栏后同样可边缘唤回（把手不常驻了）。
                                        if (!toolbarVisible && pressFromEdge != 0) {
                                            val inward = if (pressFromEdge < 0) pos.y - pressOrigin.y else pressOrigin.y - pos.y
                                            if (inward > 28f) {
                                                toolbarShowAt = System.currentTimeMillis()
                                                toolbarVisible = true
                                                pressFromEdge = 0
                                            }
                                        }
                                        // 【v2.7.11 零死区】旧版 touchMoved（>18px）达标前丢弃全部 move——
                                        //   左键已按住（TOUCH 按下即发/TRACKPAD 双击拖动）时前段拖动位移被吞
                                        //   = 文字选择起点丢失、小距离拖动完全无效的根因。现在逐事件直发；
                                        //   touchMoved 只保留 UI 语义（长按取消/点击判定）。
                                        // 【v2.7.11】删除旧版「放大状态单指 = 本地平移」分支——放大后单指
                                        //   拖动重新发给远程；平移由双指稳定拖动承担（下方 touches>=2 分支）。
                                        if (!viewOnly) {
                                            if (inputMode == InputMode.TOUCH) {
                                                val (x, y) = mapToStream(pos)
                                                session.sendMouseMove(x, y, if (leftDownSent) Protocol.BUTTON_LEFT else 0)
                                            } else {
                                                val d = deltaToStream(touchChanges.first().positionChange())
                                                session.sendMouseMoveRelative(
                                                    d.x.roundToInt(), d.y.roundToInt(),
                                                    if (leftDownSent) Protocol.BUTTON_LEFT else 0
                                                )
                                            }
                                        }
                                        pressPos = pos
                                    }
                                    touchChanges.forEach { it.consume() }
                                }
                                PointerEventType.Release -> {
                                    touchChanges.forEach { c -> touches.remove(c.id) }
                                    // 【v2.7.13】手指不足两根 → 取消放大镜手势倒计时
                                    if (touches.size < 2) cancelZoomHold()
                                    if (touches.isEmpty()) {
                                        // 【v2.7.12】全部手指离开 → 虚拟鼠标视觉释放
                                        vcursor.release()
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
                                                    // 【v2.7.11】统一收尾：松手 = 释放左键（按下时已发）。
                                                    //   不动=完整点击；移动=拖动/选择完成。删除旧版放大状态
                                                    //   「延迟点击」分支——按下已发左键，重复补点击会双击误触。
                                                    endTouchSession(true)
                                                }
                                                InputMode.TRACKPAD -> {
                                                    if (leftDownSent) {
                                                        // 【v2.7.11】双击窗口内第二击按住中：松手 = 左键抬起。
                                                        //   不动 = 双击完成（down,up,down,up）；移动 = 拖动完成。
                                                        endTouchSession(true)
                                                    } else if (!viewOnly && !touchMoved && !longFired) {
                                                        if (tapDur < TAP_MAX_MS) {
                                                            // 轻触 = 左键（两次快速轻触自然构成双击）
                                                            trackpadClick(Protocol.BUTTON_LEFT)
                                                            // v2.7.11：记录轻点完成时刻——下次按下在窗口内
                                                            // 即为「双击后按住拖动」的起点
                                                            lastTapUpAt = System.currentTimeMillis()
                                                        }
                                                        // 超长按且未发左键 → 长按右键已在超时分支处理
                                                    } else {
                                                        endTouchSession(true)
                                                    }
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

            // ============ v2.7.12 美观虚拟鼠标覆盖层 ============
            // 服务器 cursor 形状（PNG+热点）+ 本地回显位置（缩放感知 mapFromStream）；
            // 按下涟漪/触点光环/按下微缩放/美型回退箭头。
            // 【零冲突保证】纯渲染层：无 pointerInput、不消费任何指针事件 →
            //   放大镜平移（双指/鼠标拖拽）、单指拖动/文字选择、双指缩放全部不受影响。
            if (status == SelkiesSession.Status.CONNECTED && cursorX >= 0f && cursorY >= 0f && !viewOnly && virtualCursorOn) {
                VirtualCursorOverlay(
                    cursorX = cursorX,
                    cursorY = cursorY,
                    shape = cursorShape,
                    pressed = vcursor.pressed,
                    rippleAt = vcursor.rippleAt,
                    touchDevice = platform.isTouchDevice(),
                    mapFromStream = ::mapFromStream
                )
            }

            // ============ v2.7.13 放大镜手势倒计时指示环（纯渲染，双指中点，视图坐标）============
            if (zoomHold.active) {
                ZoomHoldIndicator(zoomHold)
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
        } else if (status == SelkiesSession.Status.AUTH_REQUIRED) {
            // 【v2.7.7】认证弹窗背景层（弹窗本身在下方渲染）
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black.copy(alpha = 0.6f)) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Filled.Lock,
                        null, tint = Color.White, modifier = Modifier.size(34.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("需要认证", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(statusDetail, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
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
                        // v2.7.5：断开/被踢时一键切换只读模式重连（官方共享模式旁观通道，不掉线）
                        if (!viewOnly) {
                            androidx.compose.material3.OutlinedButton(onClick = {
                                session.switchViewOnly(true)
                            }) { Text("切到只读重连") }
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
                    // 【v2.7.7】控制/只读切换入口（顶栏角色徽章：点击弹模式选择器）
                    IconButton(onClick = { showRoleSwitcher = true }) {
                        Icon(
                            if (viewOnly) Icons.Filled.LockOpen else Icons.Filled.Lock,
                            if (viewOnly) "只读模式（点击切换）" else "控制模式（点击切换）",
                            tint = if (viewOnly) Color(0xFFFBBF24) else Color(0xFF4ADE80),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = { toolbarVisible = false }) {
                        Icon(Icons.Filled.Fullscreen, "隐藏工具栏", tint = Color.White)
                    }
                }
            }
        }

        // 隐藏工具栏后的唤出把手
        // 【v2.7.6】不再常驻遮挡：仅出现 4 秒自动淡出（修「显示工具栏还是有遮挡」）；
        // 顶/底边缘内滑（全模式）、鼠标移近边缘（桌面）、顶/底边缘轻触闪现——三条唤出途径。
        androidx.compose.animation.AnimatedVisibility(
            visible = handleVisible && !toolbarVisible && status == SelkiesSession.Status.CONNECTED,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(600)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = 2.dp)
                    .size(width = 78.dp, height = 18.dp)
                    .background(
                        Color.Black.copy(alpha = if (fullscreen) 0.22f else 0.32f),
                        RoundedCornerShape(9.dp)
                    )
                    .clickable {
                        toolbarShowAt = System.currentTimeMillis()
                        toolbarVisible = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "▼ 工具栏",
                    color = Color.White.copy(alpha = if (fullscreen) 0.75f else 0.9f),
                    fontSize = 9.sp,
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
                                    // v2.7.5：仅锁定禁用才置灰；默认关（未锁定）保持白色可点
                                    !serverMicEnabled && serverMicLocked -> Color(0xFF6B7280)
                                    else -> Color.White
                                }
                            ) {
                                // v2.7.5：与官方 web 客户端对齐——未锁定的默认关直接开启上行
                                //   （服务器 0x02 门控只拒 locked 的 false；数据到达即视为开启）
                                if (!serverMicEnabled && serverMicLocked) {
                                    toast = "服务器已锁定禁用麦克风（microphone_enabled 锁定为关），无法上行"
                                } else if (micSessionOn) session.stopMicUplink() else session.startMicUplink()
                            }
                            "camera" -> ToolButton(
                                if (camSessionOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff, "摄像头",
                                tint = when {
                                    camSessionOn -> Color(0xFF4ADE80)
                                    !serverWebcamEnabled && serverWebcamLocked -> Color(0xFF6B7280)
                                    else -> Color.White
                                }
                            ) {
                                // v2.7.5：同麦克风——仅锁定禁用才拒绝（未锁定的 false = 默认关可开启）
                                if (!serverWebcamEnabled && serverWebcamLocked) {
                                    toast = "服务器已锁定禁用摄像头（webcam_enabled 锁定为关），无法上行"
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
                            "vmouse" -> if (platform.isTouchDevice()) {
                                // v2.7.13 虚拟鼠标面板：弹出真正可操作的虚拟鼠标
                                // （触控板+左中右实体键+滚动滑条+Ctrl+C/V/X/A+灵敏度）
                                ToolButton(
                                    Icons.Filled.Adjust, if (showVirtualMouse) "关闭虚拟鼠标" else "虚拟鼠标",
                                    tint = if (showVirtualMouse) Color(0xFF4ADE80) else Color.White
                                ) { showVirtualMouse = !showVirtualMouse }
                            }
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
                onTextFocusChanged = { kbTextFocused = it },
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

        // ============ v2.7.13 虚拟鼠标面板（真正的可操作虚拟鼠标：触控板+实体键+滚动滑条）============
        if (!viewOnly && status == SelkiesSession.Status.CONNECTED && platform.isTouchDevice()) {
            VirtualMousePanel(
                visible = showVirtualMouse,
                modifier = Modifier.align(Alignment.BottomCenter),
                sensitivity = state.store.settings.value.virtualMouseSensitivity,
                onSensitivityChange = { s -> state.store.updateSettings { it.virtualMouseSensitivity = s } },
                onMoveRelative = { dx, dy, mask ->
                    if (!viewOnly) session.sendMouseMoveRelative(dx.roundToInt(), dy.roundToInt(), mask)
                },
                onPressButton = { b ->
                    if (!viewOnly) {
                        if (b == Protocol.BUTTON_LEFT) vcursor.press()
                        session.sendMouseMoveRelative(0, 0, b)
                    }
                },
                onReleaseButton = { _ ->
                    if (!viewOnly) {
                        vcursor.release()
                        session.sendMouseMoveRelative(0, 0, 0)
                    }
                },
                onTapButton = { b -> trackpadClick(b) },
                onScroll = { v, n -> scrollNotches(v, n) },
                onKeyChord = { keys -> if (!viewOnly) session.sendKeyChord(keys) },
                onDismiss = { showVirtualMouse = false }
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
        // 【v2.7.1】放大镜可明确退出：常驻指示旁 ✕ 一键退出 + 面板内「退出放大镜」按钮（恢复 100% 并收起）
        if (status == SelkiesSession.Status.CONNECTED) {
            // 放大中常驻指示（点按打开控制面板：方向按钮/滑条/重置；✕ = 直接退出放大镜）
            if (localZoom > 1.01f && !showZoomControls) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 52.dp, end = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.6f)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${(localZoom * 100).toInt()}% ⤢",
                            color = Color(0xFF7DD3FC),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .clickable { showZoomControls = true }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        // v2.7.1：退出放大镜（恢复 1x，关闭面板）；v2.7.4 命中区域增大（用户反馈找不到退出）
                        Box(
                            modifier = Modifier
                                .clickable {
                                    localZoom = 1f
                                    zoomPan = Offset.Zero
                                    showZoomControls = false
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("退出", color = Color(0xFFF87171), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                // v2.7.4：右下角常驻大号「退出放大」按钮（一击退出；比顶部小指示更直观）
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 18.dp)
                        .clickable {
                            localZoom = 1f
                            zoomPan = Offset.Zero
                            showZoomControls = false
                        },
                    shape = RoundedCornerShape(18.dp),
                    color = Color.Black.copy(alpha = 0.62f)
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Close, null, tint = Color(0xFFF87171), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("退出放大", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
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
                            // v2.7.1：明确退出放大镜（恢复 1x + 收起面板）——修「放大镜点击之后无法退出」
                            androidx.compose.material3.Button(onClick = {
                                localZoom = 1f
                                zoomPan = Offset.Zero
                                showZoomControls = false
                            }) {
                                Icon(Icons.Filled.Close, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Text(" 退出放大镜")
                            }
                            TextButton(onClick = { showZoomControls = false }) { Text("收起") }
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

        // ============ 虚拟手柄（v2.7.9 悬浮式：全屏透明覆盖，控件半透明漂浮在视频上，
        //             空白区域触摸穿透到远程画面；只读模式也可用——官方允许 viewer 持槽位） ============
        if (status == SelkiesSession.Status.CONNECTED) {
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
                                // v2.7.5：反馈打开结果（旧版静默失败 = 「点了没反应」）
                                val ok = platform.openFile(ev.path)
                                if (!ok) toast = "没有可打开此文件的应用（或文件不存在）"
                            }) { Text("打开") }
                            androidx.compose.material3.TextButton(onClick = {
                                val ok = platform.openFileLocation(ev.path)
                                if (!ok) toast = "无法打开所在文件夹（已尝试文件管理器/下载/文件本身）"
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
        // 【v2.7.4】可关闭（✕）+ 20s 无新内容自动收起——修「警告无法关闭遮挡视线」；
        // 用户主动关闭后本次连接内不再弹（直到新的恢复尝试发生）
        if (audioStallNotice != null && status == SelkiesSession.Status.CONNECTED && !audioNoticeDismissed) {
            LaunchedEffect(audioStallNotice) {
                // 同一提示文案 20s 后自动收起（避免长期遮挡远程画面）
                kotlinx.coroutines.delay(20_000)
                audioNoticeDismissed = true
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 52.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.72f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp)
                ) {
                    Text(
                        audioStallNotice ?: "",
                        color = Color(0xFFF59E0B),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f, fill = false).padding(vertical = 3.dp)
                    )
                    androidx.compose.material3.IconButton(
                        onClick = { audioNoticeDismissed = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close, "关闭警告",
                            tint = Color(0xFFF59E0B), modifier = Modifier.size(14.dp)
                        )
                    }
                }
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
                            desc = "手指点哪里就在哪里点击，按下即响应；按住拖动=拖拽（选文字/拖文件不中断），双指轻点=右键，双指滚动/缩放",
                            selected = inputMode == InputMode.TOUCH
                        ) {
                            inputMode = InputMode.TOUCH
                            showModeSelector = false
                        }
                        ModeOption(
                            title = "鼠标模式（触摸板）",
                            desc = "手指滑动移动远程光标（相对），轻点=左键，快速双击=双击，双击后按住拖动=拖拽，长按=右键，双指滚动",
                            selected = inputMode == InputMode.TRACKPAD
                        ) {
                            inputMode = InputMode.TRACKPAD
                            showModeSelector = false
                        }
                        // 【v2.7.12】美观虚拟鼠标开关（会话内即时生效，持久化到全局设置）
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    state.store.updateSettings { it.virtualCursorEnabled = !it.virtualCursorEnabled }
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = state.store.settings.value.virtualCursorEnabled,
                                onCheckedChange = {
                                    state.store.updateSettings { s -> s.virtualCursorEnabled = it }
                                }
                            )
                            Column(Modifier.padding(start = 4.dp)) {
                                Text("美观虚拟鼠标", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "远程画面显示动画光标：触摸按下涟漪与触点光环反馈、平滑跟随、美型回退箭头（不干预任何手势）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { showModeSelector = false }) { Text("取消") } }
            )
        }

        // ============ v2.7.7：认证弹窗（连接发现需要认证时自动弹出） ============
        // 用户要求：配置没填认证信息时，连接遇到 401 自动弹窗输入；
        // 支持保存（写回服务器配置，下次自动填入）——密码带眼睛图标可显示/隐藏。
        if (status == SelkiesSession.Status.AUTH_REQUIRED && !authDialogShown) {
            authDialogShown = true
            authUser = server.username
            authPass = server.password
            authToken = server.token
        }
        if (status == SelkiesSession.Status.AUTH_REQUIRED) {
            AlertDialog(
                onDismissRequest = {
                    // 允许取消 → 回失败页（可重试/返回）
                    session.cancelAuth()
                },
                title = { Text(if (server.useTokenAuth) "需要访问令牌" else "服务器要求认证") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "连接 ${server.url} 需要认证" +
                                    (lastError?.let { "\n$it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (server.useTokenAuth) {
                            OutlinedTextField(
                                value = authToken,
                                onValueChange = { authToken = it },
                                label = { Text("访问令牌（Bearer）") },
                                singleLine = true,
                                visualTransformation = if (authShowPass)
                                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                                else androidx.compose.ui.text.input.VisualTransformation.None,
                                trailingIcon = {
                                    IconButton(onClick = { authShowPass = !authShowPass }) {
                                        Icon(
                                            if (authShowPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            if (authShowPass) "隐藏令牌" else "显示令牌"
                                        )
                                    }
                                }
                            )
                        } else {
                            OutlinedTextField(
                                value = authUser,
                                onValueChange = { authUser = it },
                                label = { Text("用户名") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = authPass,
                                onValueChange = { authPass = it },
                                label = { Text("密码") },
                                singleLine = true,
                                visualTransformation = if (authShowPass)
                                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                                else androidx.compose.ui.text.input.VisualTransformation.None,
                                trailingIcon = {
                                    IconButton(onClick = { authShowPass = !authShowPass }) {
                                        Icon(
                                            if (authShowPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            if (authShowPass) "隐藏密码" else "显示密码"
                                        )
                                    }
                                }
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(
                                checked = authRemember,
                                onCheckedChange = { authRemember = it }
                            )
                            Text("记住密码（保存到服务器配置，下次自动填入）", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        // 写回服务器条目（记住密码）→ 立即重连
                        server.username = authUser.trim()
                        server.password = if (server.useTokenAuth) server.password else authPass
                        server.token = if (server.useTokenAuth) authToken.trim() else server.token
                        if (authRemember) {
                            state.store.upsertServer(server)
                            toast = if (authRemember) "认证信息已保存到服务器配置" else ""
                        } else {
                            // 不记住：仅本次连接使用（运行时生效，不落盘）
                            toast = "已使用输入的认证信息重连（未保存）"
                        }
                        authDialogShown = false
                        session.retryWithCredentials()
                    }) { Text("连接") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        authDialogShown = false
                        session.cancelAuth()
                    }) { Text("取消") }
                }
            )
        }

        // ============ v2.7.7：被顶替 → 「切换到只读继续观看」选择器（含手柄槽位） ============
        takeoverEvent?.let { tk ->
            AlertDialog(
                onDismissRequest = { session.takeoverEvent.value = null },
                title = { Text("会话被其他客户端接管") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "服务器单主人策略：新的主客户端接管了此会话。\n\n" +
                                    "可切换到只读模式继续观看画面（多人同看互不干扰）；" +
                                    "选择手柄玩家槽位后还能在只读下继续用手柄玩游戏（官方共享手柄控制端，最多 4 位玩家）。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text("手柄槽位（可稍后在手柄面板更改）", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SlotChip("纯观看", roleSwitchSlot == 0) { roleSwitchSlot = 0 }
                            SlotChip("玩家2", roleSwitchSlot == 2) { roleSwitchSlot = 2 }
                            SlotChip("玩家3", roleSwitchSlot == 3) { roleSwitchSlot = 3 }
                            SlotChip("玩家4", roleSwitchSlot == 4) { roleSwitchSlot = 4 }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        session.takeoverEvent.value = null
                        session.switchRole(viewOnly = true, slot = roleSwitchSlot)
                    }) { Text("切只读观看") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            session.takeoverEvent.value = null
                            session.connect()   // 重连夺回控制权
                        }) { Text("重连夺回") }
                        TextButton(onClick = {
                            session.takeoverEvent.value = null
                            state.navigate(com.selkies.client.Screen.ServerList)
                        }) { Text("返回列表") }
                    }
                }
            )
        }

        // ============ v2.7.7：会话中切换 控制模式 ↔ 只读模式 ============
        if (showRoleSwitcher) {
            AlertDialog(
                onDismissRequest = { showRoleSwitcher = false },
                title = { Text("切换连接模式") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ModeOption(
                            "控制模式（当前${if (viewOnly) "：否" else "：是"}）",
                            "完整键鼠/触控权限 + 全部手柄槽位。多人时会顶替其他控制端（服务器单主人）。",
                            !viewOnly
                        ) {
                            showRoleSwitcher = false
                            if (viewOnly) {
                                session.switchRole(viewOnly = false)
                                toast = "正在切换到控制模式…"
                            }
                        }
                        ModeOption(
                            "只读模式（观看）",
                            "无键鼠权限，只收画面/声音；多人同看互不干扰。官方支持 4 个只读手柄控制端——选玩家槽位即可在只读下玩游戏。",
                            viewOnly
                        ) {
                            // 直接选只读（默认带当前槽位选择）
                        }
                        Text("只读模式手柄槽位", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SlotChip("纯观看", roleSwitchSlot == 0) { roleSwitchSlot = 0 }
                            SlotChip("玩家2", roleSwitchSlot == 2) { roleSwitchSlot = 2 }
                            SlotChip("玩家3", roleSwitchSlot == 3) { roleSwitchSlot = 3 }
                            SlotChip("玩家4", roleSwitchSlot == 4) { roleSwitchSlot = 4 }
                        }
                        Button(onClick = {
                            showRoleSwitcher = false
                            session.switchRole(viewOnly = true, slot = roleSwitchSlot)
                            toast = if (roleSwitchSlot in 2..4)
                                "正在切换到只读模式（手柄玩家 $roleSwitchSlot 位）…"
                            else "正在切换到只读模式…"
                        }) { Text("切换到只读模式") }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { showRoleSwitcher = false }) { Text("取消") } }
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
// v2.7.12 美观虚拟鼠标（覆盖层渲染 + 手势状态桥）
// =====================================================================

/** 涟漪扩散时长（按下/点击后从热点扩开的圆环动画） */
private const val VCURSOR_RIPPLE_MS = 460
/** 按下时光标的微缩放（弹性回弹；视觉上「按进去」的手感） */
private const val VCURSOR_PRESS_SCALE = 0.84f

/**
 * 虚拟鼠标状态桥：手势处理器写、覆盖层读。
 * press/release/pulse 只做快照状态写入——绝不消费指针事件、绝不改动手势判定控制流，
 * 因此对拖动/文字选择/双指缩放/放大镜平移零影响（v2.7.11 五项拖动根因修复保持原样）。
 * - [press]：触摸/鼠标按下 → pressed=true + 涟漪（触点光环 + 微缩放）
 * - [release]：松开/手势被双指接管 → pressed=false（幂等）
 * - [pulse]：完整点击对（左/右/中键单击、双指轻点右键、放大镜延迟点击）→ 单发涟漪
 */
/**
 * v2.7.13 放大镜手势倒计时指示状态：手势处理器写入（60ms tick）→ 指示环覆盖层读取。
 * 纯渲染层（无 pointerInput），与远程画面手势零冲突。
 */
private class ZoomHoldState {
    var active by mutableStateOf(false)
    var mid by mutableStateOf(Offset.Zero)   // 双指中点（视图坐标）
    var progress by mutableStateOf(0f)        // 0..1
    var label by mutableStateOf("")
    fun update(mid: Offset, progress: Float, label: String) {
        this.mid = mid
        this.progress = progress
        this.label = label
        active = true
    }
    fun cancel() {
        active = false
    }
}

/**
 * v2.7.13 放大镜手势倒计时指示环：双指外扩/内收达标后跟随双指中点显示，
 * 进度环满 3 秒触发缩放（进入/加档/减档/退出）。半透明圆底 + 进度弧 + 目标文案。
 */
@Composable
private fun ZoomHoldIndicator(state: ZoomHoldState) {
    Box(Modifier.fillMaxSize()) {
        val ringPx = with(LocalDensity.current) { 92.dp.toPx() }
        Surface(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (state.mid.x - ringPx / 2f).roundToInt(),
                        (state.mid.y - ringPx / 2f).roundToInt()
                    )
                }
                .size(92.dp),
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.55f)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Canvas(Modifier.size(74.dp)) {
                    val stroke = 5.dp.toPx()
                    drawArc(
                        Color.White.copy(alpha = 0.22f), 0f, 360f, false,
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                    drawArc(
                        Color(0xFF60A5FA), -90f, 360f * state.progress.coerceIn(0f, 1f), false,
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
                Text(
                    state.label,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
        }
    }
}

private class VirtualCursorState {
    var pressed by mutableStateOf(false)
    var rippleAt by mutableStateOf(0L)
    fun press() {
        pressed = true
        rippleAt = System.currentTimeMillis()
        if (TRACE) println("[Selkies][VCursor] press (ripple@$rippleAt)")
    }
    fun release() {
        if (TRACE && pressed) println("[Selkies][VCursor] release")
        pressed = false
    }
    fun pulse() {
        rippleAt = System.currentTimeMillis()
        if (TRACE) println("[Selkies][VCursor] pulse (ripple@$rippleAt)")
    }
    companion object {
        /** -Dselkies.vctrace：虚拟鼠标状态链路追踪（E2E 断言用） */
        private val TRACE = System.getProperty("selkies.vctrace") != null
    }
}

/**
 * 美观虚拟鼠标覆盖层（纯渲染，无 pointerInput）：
 * - 服务器 PNG 形状按热点绘制 + 软阴影（黑色滤色偏移）；
 * - 无形状时回退美型箭头（黑体白描边 + 柔和投影）；
 * - 按下：触点光环（触摸设备；手指遮挡时仍可见精确热点）+ 光标弹性微缩放；
 * - 按下/单击：涟漪圆环从热点扩散淡出；
 * - 位置 = mapFromStream(cursorX, cursorY)（本地缩放感知：放大镜下随内容同变换）。
 */
@Composable
private fun VirtualCursorOverlay(
    cursorX: Float,
    cursorY: Float,
    shape: com.selkies.client.model.CursorShape?,
    pressed: Boolean,
    rippleAt: Long,
    touchDevice: Boolean,
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
    // 按下微缩放（弹性回弹：松手时轻微过冲，手感「Q 弹」）
    val scale = animateFloatAsState(
        targetValue = if (pressed) VCURSOR_PRESS_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = 900f
        ),
        label = "vcursorScale"
    ).value
    // 触点光环淡入淡出（按下期间常亮呼吸由 alpha 脉动实现）
    val halo = animateFloatAsState(
        targetValue = if (pressed && touchDevice) 1f else 0f,
        animationSpec = tween(180),
        label = "vcursorHalo"
    ).value
    // 涟漪进度：rippleAt 变化 → 0→1 单程扩散动画
    val ripple = remember { Animatable(1f) }
    LaunchedEffect(rippleAt) {
        if (rippleAt > 0L) {
            ripple.snapTo(0f)
            ripple.animateTo(
                1f,
                animationSpec = tween(durationMillis = VCURSOR_RIPPLE_MS, easing = FastOutSlowInEasing)
            )
        }
    }
    // 光环呼吸（按下期间 1.1s 周期往复脉动；帧驱动连续动画，静止按住也持续呼吸）
    val breath = remember { Animatable(0f) }
    LaunchedEffect(pressed, touchDevice) {
        if (pressed && touchDevice) {
            while (true) {
                breath.animateTo(1f, tween(550, easing = FastOutSlowInEasing))
                breath.animateTo(0f, tween(550, easing = FastOutSlowInEasing))
            }
        } else {
            breath.snapTo(0f)
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        val pos = mapFromStream(cursorX, cursorY)
        val accent = Color(0xFF60A5FA)
        // 1) 触点光环（触摸设备按下中）：双层柔光 + 热点白点 —— 手指遮挡时的精确位置反馈
        if (halo > 0.01f) {
            val a = halo * (0.85f + 0.15f * breath.value)
            drawCircle(accent.copy(alpha = 0.10f * a), radius = 34f + 4f * breath.value, center = pos)
            drawCircle(accent.copy(alpha = 0.20f * a), radius = 21f, center = pos)
            drawCircle(Color.White.copy(alpha = 0.45f * a), radius = 3.5f, center = pos)
        }
        // 2) 涟漪：按下/单击从热点扩散的描边圆环（宽度和透明度随进度收敛）
        val rp = ripple.value
        if (rp < 1f) {
            drawCircle(
                color = accent.copy(alpha = 0.6f * (1f - rp)),
                radius = 10f + 46f * rp,
                center = pos,
                style = Stroke(width = (1f + 2.6f * (1f - rp)).coerceAtLeast(0.8f))
            )
        }
        // 3) 光标本体（缩放围绕热点；软阴影 = 黑色滤色低透明度偏移一层）
        val sh = shape
        if (bitmap != null && sh != null) {
            val w = (sh.width * scale).roundToInt().coerceAtLeast(1)
            val h = (sh.height * scale).roundToInt().coerceAtLeast(1)
            val ox = (pos.x - sh.hotX * scale).roundToInt()
            val oy = (pos.y - sh.hotY * scale).roundToInt()
            drawImage(
                image = bitmap,
                dstOffset = androidx.compose.ui.unit.IntOffset(ox + 2, oy + 2),
                dstSize = androidx.compose.ui.unit.IntSize(w, h),
                alpha = 0.32f,
                colorFilter = ColorFilter.tint(Color.Black)
            )
            drawImage(
                image = bitmap,
                dstOffset = androidx.compose.ui.unit.IntOffset(ox, oy),
                dstSize = androidx.compose.ui.unit.IntSize(w, h)
            )
        } else {
            // 回退：美型箭头（黑体白描边 + 柔和投影；围绕热点缩放）
            val s = scale
            val arrow = Path().apply {
                moveTo(pos.x, pos.y)
                lineTo(pos.x + 12.5f * s, pos.y + 19f * s)
                lineTo(pos.x + 7.2f * s, pos.y + 17.6f * s)
                lineTo(pos.x + 4.3f * s, pos.y + 24.5f * s)
                lineTo(pos.x + 1.6f * s, pos.y + 23.6f * s)
                lineTo(pos.x + 4.9f * s, pos.y + 16.4f * s)
                close()
            }
            translate(left = 1.2f, top = 1.8f) {
                drawPath(arrow, Color.Black.copy(alpha = 0.35f))
            }
            drawPath(arrow, Color.Black.copy(alpha = 0.92f))
            drawPath(arrow, Color.White, style = Stroke(width = 1.4f))
        }
    }
}

/** v2.7.7 手柄玩家槽位选择芯片（纯观看 / 玩家2/3/4） */
@Composable
private fun SlotChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .width(76.dp)
            .clickable { onClick() }
    ) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
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
        "screenshot", "record", "volume", "gamepad", "virtualpad", "vmouse", "zoom",
        "media", "fullscreen"
    )

    /** 功能键 → 中文名（设置界面显示） */
    val LABELS = mapOf(
        "keyboard" to "键盘", "hotkeys" to "快捷键", "clipboard" to "剪贴板", "files" to "文件",
        "print" to "打印", "mic" to "麦克风", "camera" to "摄像头", "hardware" to "硬件",
        "quality" to "画质", "touchmode" to "触屏/触摸板", "apps" to "应用", "stats" to "统计",
        "screenshot" to "截图", "record" to "录屏", "volume" to "音量", "gamepad" to "手柄",
        "virtualpad" to "虚拟手柄", "vmouse" to "虚拟鼠标", "zoom" to "放大镜", "media" to "媒体库", "fullscreen" to "全屏"
    )
}

