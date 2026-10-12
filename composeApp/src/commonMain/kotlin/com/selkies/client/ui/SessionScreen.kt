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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
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
import com.selkies.client.ui.panels.HotkeysPanel
import com.selkies.client.ui.panels.ImeHostPanel
import com.selkies.client.ui.panels.QuickShortcutBar
import com.selkies.client.ui.panels.StatsPanel
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
enum class SessionPanel { NONE, KEYBOARD, HOTKEYS, CLIPBOARD, FILES, STATS, APPS }

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

    var panel by remember { mutableStateOf(SessionPanel.NONE) }
    var fullscreen by remember { mutableStateOf(false) }
    var toolbarVisible by remember { mutableStateOf(true) }
    var floatingKb by remember { mutableStateOf(false) }
    var quickBar by remember { mutableStateOf(false) }
    var showInputPicker by remember { mutableStateOf(false) }
    var inputMode by remember { mutableStateOf(if (state.store.settings.value.touchMouseMode) InputMode.TRACKPAD else InputMode.TOUCH) }
    var showModeSelector by remember { mutableStateOf(false) }
    var exitConfirm by remember { mutableStateOf(false) }
    var micOn by remember { mutableStateOf(false) }
    var camOn by remember { mutableStateOf(false) }
    var pendingDrag by remember { mutableStateOf(false) } // 触摸板双击后按住拖动

    // 视口几何
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var streamSize by remember { mutableStateOf(realizedRes ?: Pair(1280, 720)) }
    LaunchedEffect(realizedRes) {
        realizedRes?.let {
            streamSize = it
            println("[Selkies] 流分辨率: ${it.first}x${it.second}")
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

    // 返回键：逐层退出（面板→悬浮键盘/快捷栏→选择器→全屏→确认断开）
    PlatformBackHandler {
        when {
            panel != SessionPanel.NONE -> panel = SessionPanel.NONE
            floatingKb -> floatingKb = false
            quickBar -> quickBar = false
            showModeSelector -> showModeSelector = false
            showInputPicker -> showInputPicker = false
            fullscreen -> fullscreen = false
            else -> exitConfirm = true
        }
    }

    // 连接生命周期 + 剪贴板监听 + 桌面物理键直通 + 手柄挂载
    DisposableEffect(session) {
        // ⚠️ 等待首次布局拿到真实视口尺寸再连接：SETTINGS 的 initialClientWidth/Height
        //    与自适应分辨率依赖它（旧版在布局前连接，恒发 1280x720）。
        val job = scope.launch {
            var waited = 0
            while (viewSize == IntSize.Zero && waited < 1500) { delay(50); waited += 50 }
            session.screenWidthPx = viewSize.width.takeIf { it > 0 } ?: 1280
            session.screenHeightPx = viewSize.height.takeIf { it > 0 } ?: 720
            session.connect()
        }

        // 服务器端光标渲染进视频流（官方 SET_NATIVE_CURSOR_RENDERING）+ 本地覆盖层双保险
        session.post { session.connection.sendText("SET_NATIVE_CURSOR_RENDERING,1") }

        // Android 手柄 → js 指令
        if (state.store.settings.value.gamepadEnabled) {
            attachGamepadSession(session)
        }

        val clip = platform.clipboard()
        var lastText: String? = clip.getText()
        clip.setListener {
            val t = clip.getText()
            if (t != null && t != lastText) {
                lastText = t
                session.pushLocalClipboard(t)
            }
        }

        // 桌面（AWT 分发器）物理键直通；无本地面板打开时才转发，避免在文本框输入时打到远程
        platform.setPhysicalKeyHandler { code, down ->
            if (!viewOnly && panel == SessionPanel.NONE && !floatingKb &&
                state.store.settings.value.physicalKeyboardPassthrough
            ) {
                val ks = platform.nativeKeyCodeToKeysym(code, 0)
                if (ks != null && ks > 0) {
                    session.sendKey(ks.toLong(), down)
                }
            }
        }

        onDispose {
            job.cancel()
            platform.setPhysicalKeyHandler(null)
            attachGamepadSession(null)
            clip.setListener(null)
            session.disconnect()
            platform.setFullscreen(false)
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

    // ---------- 坐标映射（视频等比容器内，画面与坐标严格一致）----------
    fun mapToStream(pos: Offset): Pair<Int, Int> {
        val vw = viewSize.width.toFloat()
        val vh = viewSize.height.toFloat()
        if (vw <= 0 || vh <= 0) return 0 to 0
        val sw = streamSize.first.toFloat()
        val sh = streamSize.second.toFloat()
        val scale = min(vw / sw, vh / sh)
        val dx = (vw - sw * scale) / 2f
        val dy = (vh - sh * scale) / 2f
        val x = ((pos.x - dx) / scale).toInt().coerceIn(0, streamSize.first - 1)
        val y = ((pos.y - dy) / scale).toInt().coerceIn(0, streamSize.second - 1)
        return x to y
    }

    /** 流坐标 → 视图坐标（光标覆盖层用） */
    fun mapFromStream(sx: Float, sy: Float): Offset {
        val vw = viewSize.width.toFloat()
        val vh = viewSize.height.toFloat()
        if (vw <= 0 || vh <= 0) return Offset.Zero
        val sw = streamSize.first.toFloat()
        val sh = streamSize.second.toFloat()
        val scale = min(vw / sw, vh / sh)
        val dx = (vw - sw * scale) / 2f
        val dy = (vh - sh * scale) / 2f
        return Offset(sx * scale + dx, sy * scale + dy)
    }

    /** 视图像素位移 → 流坐标位移（触摸板相对移动用） */
    fun deltaToStream(d: Offset): Offset {
        val vw = viewSize.width.toFloat()
        val vh = viewSize.height.toFloat()
        if (vw <= 0 || vh <= 0) return d
        val scale = min(vw / streamSize.first.toFloat(), vh / streamSize.second.toFloat())
        if (scale <= 0f) return d
        return Offset(d.x / scale, d.y / scale)
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

    fun handleKeyEvent(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (viewOnly) return false
        if (!state.store.settings.value.physicalKeyboardPassthrough) return false
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
                                            changes.forEach { it.consume() }
                                        }
                                    }
                                    PointerEventType.Press -> {
                                        val pos = changes.first().position
                                        when {
                                            event.tertiaryPressed() -> middleClickAt(pos)
                                            event.secondaryPressed() -> rightClickAt(pos)
                                            event.primaryPressed() -> leftClickAt(pos)
                                        }
                                        changes.forEach { it.consume() }
                                    }
                                    PointerEventType.Move -> {
                                        val pos = changes.first().position
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
                                        if (!viewOnly) {
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
                                            if (inputMode == InputMode.TOUCH) {
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
                                        // 双指：滚动 + 捏合缩放
                                        val ps = touches.values.toList()
                                        val d = hypot(ps[0].x - ps[1].x, ps[0].y - ps[1].y)
                                        val pan = touchChanges.fold(Offset.Zero) { a, c -> a + c.positionChange() }
                                        if (abs(pan.y) > 0.5f) {
                                            twoFingerMoved = true
                                            acc.scrollY += -pan.y / 2f
                                            val notches = (acc.scrollY / TOUCH_SCROLL_NOTCH_PX).toInt()
                                            if (notches != 0) {
                                                acc.scrollY -= notches * TOUCH_SCROLL_NOTCH_PX
                                                scrollNotches(true, notches)
                                            }
                                        }
                                        if (twoFingerDist > 10f && d > 10f && abs(d - twoFingerDist) > 0.5f) {
                                            twoFingerMoved = true
                                            acc.zoom *= d / twoFingerDist
                                            val n = (log10(acc.zoom.toDouble()) / log10(PINCH_NOTCH_RATIO)).toInt()
                                            if (n != 0) {
                                                acc.zoom /= PINCH_NOTCH_RATIO.pow(n.toDouble()).toFloat()
                                                pinchNotches(n)
                                            }
                                        }
                                        twoFingerDist = d
                                    } else if (touches.size == 1) {
                                        val pos = touchChanges.first().position
                                        val delta = pos - pressPos
                                        if (!touchMoved && hypot(delta.x, delta.y) > TAP_SLOP_PX) {
                                            touchMoved = true
                                        }
                                        if (!viewOnly && touchMoved) {
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
                                                    // 松手：释放左键（按下时已发）
                                                    endTouchSession(true)
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
            val ratio = streamSize.second.takeIf { it > 0 }?.let { streamSize.first.toFloat() / it } ?: (16f / 9f)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                    .clickable { toolbarVisible = true },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "显示工具栏",
                    color = Color.White, fontSize = 11.sp,
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
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToolButton(
                        Icons.Filled.Keyboard, "键盘",
                        tint = if (floatingKb || panel == SessionPanel.KEYBOARD || quickBar) Color(0xFF60A5FA) else Color.White,
                        onLongClick = onKeyboardButtonLong,
                        onClick = onKeyboardButton
                    )
                    ToolButton(Icons.Filled.Bolt, "快捷键") { panel = if (panel == SessionPanel.HOTKEYS) SessionPanel.NONE else SessionPanel.HOTKEYS }
                    ToolButton(Icons.Filled.ContentPaste, "剪贴板") { panel = if (panel == SessionPanel.CLIPBOARD) SessionPanel.NONE else SessionPanel.CLIPBOARD }
                    ToolButton(Icons.Filled.Folder, "文件") { panel = if (panel == SessionPanel.FILES) SessionPanel.NONE else SessionPanel.FILES }
                    ToolButton(
                        if (micOn) Icons.Filled.Mic else Icons.Filled.MicOff, "麦克风",
                        tint = if (micOn) Color(0xFF4ADE80) else Color.White
                    ) {
                        micOn = !micOn
                        if (micOn) session.startMicUplink() else session.stopMicUplink()
                    }
                    ToolButton(
                        if (camOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff, "摄像头",
                        tint = if (camOn) Color(0xFF4ADE80) else Color.White
                    ) {
                        camOn = !camOn
                        if (camOn) session.startWebcamUplink() else session.stopWebcamUplink()
                    }
                    if (platform.isTouchDevice()) {
                        ToolButton(
                            if (inputMode == InputMode.TRACKPAD) Icons.Filled.Mouse else Icons.Filled.TouchApp,
                            if (inputMode == InputMode.TRACKPAD) "触摸板" else "触屏",
                            tint = if (inputMode == InputMode.TRACKPAD) Color(0xFF60A5FA) else Color.White
                        ) { showModeSelector = true }
                    }
                    ToolButton(Icons.Filled.Apps, "应用") { panel = if (panel == SessionPanel.APPS) SessionPanel.NONE else SessionPanel.APPS }
                    ToolButton(Icons.Filled.QueryStats, "统计") { panel = if (panel == SessionPanel.STATS) SessionPanel.NONE else SessionPanel.STATS }
                    ToolButton(
                        if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, "全屏",
                        tint = if (fullscreen) Color(0xFF60A5FA) else Color.White
                    ) { fullscreen = !fullscreen }
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
                shadowElevation = 12.dp
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
                        SessionPanel.NONE -> {}
                    }
                }
            }
        }

        // ============ 悬浮窗可拖动完整键盘（分类模式 / 完整模式 151 键）============
        if (!viewOnly && status == SelkiesSession.Status.CONNECTED) {
            CompleteKeyboard(
                session = session,
                visible = floatingKb,
                savedScale = state.store.settings.value.keyboardScale,
                savedFullMode = state.store.settings.value.keyboardFullMode,
                onScaleChange = { s -> state.store.updateSettings { it.keyboardScale = s } },
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
                text = { Text("确定断开远程会话并返回服务器列表？") },
                confirmButton = {
                    TextButton(onClick = {
                        exitConfirm = false
                        state.navigate(com.selkies.client.Screen.ServerList)
                    }) { Text("断开返回", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { exitConfirm = false }) { Text("取消") } }
            )
        }
    }
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
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, color = tint, fontSize = 9.sp, maxLines = 1)
    }
}
