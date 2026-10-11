package com.selkies.client.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Abc
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
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
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
import com.selkies.client.model.ServerEntry
import com.selkies.client.platform.keysymOrNull
import com.selkies.client.platform.primaryPressed
import com.selkies.client.platform.secondaryPressed
import com.selkies.client.platform.tertiaryPressed
import com.selkies.client.ui.panels.ClipboardPanel
import com.selkies.client.ui.panels.FilesPanel
import com.selkies.client.ui.panels.HotkeysPanel
import com.selkies.client.ui.panels.ImeHostPanel
import com.selkies.client.ui.panels.SpecialKeysPanel
import com.selkies.client.ui.panels.StatsPanel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

/** 底部面板类型 */
enum class SessionPanel { NONE, KEYBOARD, SPECIAL_KEYS, HOTKEYS, CLIPBOARD, FILES, STATS }

private const val TOUCH_SCROLL_NOTCH_PX = 50f
private const val PINCH_NOTCH_RATIO = 1.189207115002721 // 2^(1/4)

/** 视频渲染表面（平台实现：Android=SurfaceView+MediaCodec，Desktop=Skia 位图） */
@Composable
expect fun VideoSurface(session: SelkiesSession, modifier: Modifier)

/** IME 宿主（Android=隐藏 EditText；Desktop=空实现） */
@Composable
expect fun ImeHost(
    visible: Boolean,
    onCommitText: (String) -> Unit,
    onEnter: () -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier
)

/**
 * 远程桌面会话界面：视频 + 全套输入 + 工具栏 + 面板
 */
@Composable
fun SessionScreen(state: AppState, server: ServerEntry) {
    val platform = state.platform
    val session = remember(server.id) {
        SelkiesSession(
            server = server,
            platform = platform,
            settingsProvider = { state.store.settings.value }
        )
    }

    val status by session.status.collectAsState()
    val statusDetail by session.statusDetail.collectAsState()
    val stats by session.stats.collectAsState()
    val realizedRes by session.realizedResolution.collectAsState()
    val lastError by session.lastError.collectAsState()
    val role by session.role.collectAsState()

    var panel by remember { mutableStateOf(SessionPanel.NONE) }
    var fullscreen by remember { mutableStateOf(false) }
    var toolbarVisible by remember { mutableStateOf(true) }
    var touchMouse by remember { mutableStateOf(state.store.settings.value.touchMouseMode) }
    var micOn by remember { mutableStateOf(false) }
    var camOn by remember { mutableStateOf(false) }

    // 视口几何
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var streamSize by remember { mutableStateOf(realizedRes ?: Pair(1280, 720)) }
    LaunchedEffect(realizedRes) { realizedRes?.let { streamSize = it } }

    // 虚拟光标（触屏鼠标模式）
    var cursorPos by remember { mutableStateOf(Offset.Zero) }
    var cursorVisible by remember { mutableStateOf(false) }

    // 手势累积器
    val acc = remember { ScrollAccumulator() }
    val scope = rememberCoroutineScope()

    val viewOnly = server.viewOnly || role == "viewer"

    // 连接生命周期 + 剪贴板监听 + 桌面物理键直通
    DisposableEffect(session) {
        session.screenWidthPx = streamSize.first
        session.screenHeightPx = streamSize.second
        session.connect()

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
            if (!viewOnly && panel == SessionPanel.NONE &&
                state.store.settings.value.physicalKeyboardPassthrough
            ) {
                val ks = platform.nativeKeyCodeToKeysym(code, 0)
                if (ks != null && ks > 0) {
                    session.sendKey(ks.toLong(), down)
                }
            }
        }

        onDispose {
            platform.setPhysicalKeyHandler(null)
            clip.setListener(null)
            session.disconnect()
            platform.setFullscreen(false)
        }
    }

    LaunchedEffect(fullscreen) { platform.setFullscreen(fullscreen) }

    LaunchedEffect(Unit) {
        if (state.store.settings.value.autoShowIme && !platform.hasHardwareKeyboard()) {
            kotlinx.coroutines.delay(600)
            platform.showSoftInput(true)
        }
    }

    // ---------- 坐标映射 ----------
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

    fun cursorStreamPos(): Pair<Int, Int> =
        if (cursorVisible) mapToStream(cursorPos)
        else streamSize.first / 2 to streamSize.second / 2

    fun leftClickAt(pos: Offset) {
        if (viewOnly) return
        val (x, y) = if (touchMouse) cursorStreamPos() else mapToStream(pos)
        session.sendMouseMove(x, y, Protocol.BUTTON_LEFT)
        session.sendMouseMove(x, y, 0)
    }

    fun rightClickAt(pos: Offset) {
        if (viewOnly) return
        val (x, y) = if (touchMouse) cursorStreamPos() else mapToStream(pos)
        session.sendMouseMove(x, y, Protocol.BUTTON_RIGHT)
        session.sendMouseMove(x, y, 0)
    }

    fun middleClickAt(pos: Offset) {
        if (viewOnly) return
        val (x, y) = if (touchMouse) cursorStreamPos() else mapToStream(pos)
        session.sendMouseMove(x, y, Protocol.BUTTON_MIDDLE)
        session.sendMouseMove(x, y, 0)
    }

    fun doubleClickAt(pos: Offset) {
        leftClickAt(pos)
        scope.launch {
            kotlinx.coroutines.delay(80)
            leftClickAt(pos)
        }
    }

    fun movePointerTo(pos: Offset) {
        if (viewOnly) return
        val (x, y) = mapToStream(pos)
        session.sendMouseMove(x, y, 0)
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
                // 单指点击/长按/双击
                .pointerInput(touchMouse, viewOnly, streamSize, viewSize) {
                    detectTapGestures(
                        onDoubleTap = { pos -> doubleClickAt(pos) },
                        onLongPress = { pos -> rightClickAt(pos) },
                        onTap = { pos -> leftClickAt(pos) },
                        onPress = { pos ->
                            if (touchMouse && !viewOnly) {
                                // 触屏鼠标模式：轻触仅移动光标
                                if (!cursorVisible) {
                                    cursorPos = pos
                                    cursorVisible = true
                                }
                            } else {
                                movePointerTo(pos)
                            }
                        }
                    )
                }
                // 拖动
                .pointerInput(touchMouse, viewOnly, streamSize, viewSize) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            if (!viewOnly) {
                                if (touchMouse) {
                                    if (!cursorVisible) {
                                        cursorPos = pos
                                        cursorVisible = true
                                    }
                                } else {
                                    val (x, y) = mapToStream(pos)
                                    session.sendMouseMove(x, y, Protocol.BUTTON_LEFT)
                                }
                            }
                        },
                        onDrag = { change, _ ->
                            if (!viewOnly) {
                                if (touchMouse) {
                                    val vw = viewSize.width.toFloat()
                                    val vh = viewSize.height.toFloat()
                                    cursorPos = Offset(
                                        (cursorPos.x + change.positionChange().x).coerceIn(0f, vw),
                                        (cursorPos.y + change.positionChange().y).coerceIn(0f, vh)
                                    )
                                    cursorVisible = true
                                } else {
                                    val (x, y) = mapToStream(change.position)
                                    session.sendMouseMove(x, y, Protocol.BUTTON_LEFT)
                                }
                            }
                        },
                        onDragEnd = {
                            if (!viewOnly && !touchMouse) {
                                val (x, y) = cursorStreamPos()
                                session.sendMouseMove(x, y, 0)
                            }
                        },
                        onDragCancel = {
                            if (!viewOnly && !touchMouse) session.sendMouseMove(0, 0, 0)
                        }
                    )
                }
                // 双指：滚动 + 捏合缩放（触屏）
                .pointerInput(viewOnly) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (!viewOnly) {
                            if (abs(pan.y) > 0.5f) {
                                acc.scrollY += -pan.y
                                val notches = (acc.scrollY / TOUCH_SCROLL_NOTCH_PX).toInt()
                                if (notches != 0) {
                                    acc.scrollY -= notches * TOUCH_SCROLL_NOTCH_PX
                                    scrollNotches(true, notches)
                                }
                            }
                            if (zoom != 1f) {
                                acc.zoom *= zoom
                                val n = (log10(acc.zoom.toDouble()) / log10(PINCH_NOTCH_RATIO)).toInt()
                                if (n != 0) {
                                    acc.zoom /= PINCH_NOTCH_RATIO.pow(n.toDouble()).toFloat()
                                    pinchNotches(n)
                                }
                            }
                        }
                    }
                }
                // 桌面鼠标：滚轮 / 右中键 / 悬停（Initial 通道拦截）
                .pointerInput(viewOnly, touchMouse) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (viewOnly) continue
                            if (event.type == PointerEventType.Scroll) {
                                val sd = event.changes.fold(Offset.Zero) { a, c -> a + c.scrollDelta }
                                if (sd != Offset.Zero) {
                                    if (abs(sd.y) > 1e-4f) {
                                        acc.wheelY += -sd.y * 3f
                                        val n = acc.wheelY.toInt()
                                        if (n != 0) {
                                            acc.wheelY -= n
                                            scrollNotches(true, n)
                                        }
                                    }
                                    if (abs(sd.x) > 1e-4f) {
                                        acc.wheelX += sd.x * 3f
                                        val n = acc.wheelX.toInt()
                                        if (n != 0) {
                                            acc.wheelX -= n
                                            scrollNotches(false, n)
                                        }
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            } else if (event.type == PointerEventType.Press) {
                                val isMouse = event.changes.any { it.type == PointerType.Mouse }
                                if (isMouse) {
                                    val pos = event.changes.first().position
                                    when {
                                        event.tertiaryPressed() -> middleClickAt(pos)
                                        event.secondaryPressed() -> rightClickAt(pos)
                                        event.primaryPressed() -> {
                                            if (!touchMouse) {
                                                val (x, y) = mapToStream(pos)
                                                session.sendMouseMove(x, y, Protocol.BUTTON_LEFT)
                                            }
                                        }
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            } else if (event.type == PointerEventType.Move && !touchMouse) {
                                val isMouse = event.changes.any { it.type == PointerType.Mouse }
                                if (isMouse) {
                                    val pos = event.changes.first().position
                                    val mask = when {
                                        event.primaryPressed() -> Protocol.BUTTON_LEFT
                                        event.secondaryPressed() -> Protocol.BUTTON_RIGHT
                                        event.tertiaryPressed() -> Protocol.BUTTON_MIDDLE
                                        else -> 0
                                    }
                                    val (x, y) = mapToStream(pos)
                                    session.sendMouseMove(x, y, mask)
                                }
                            } else if (event.type == PointerEventType.Release) {
                                val isMouse = event.changes.any { it.type == PointerType.Mouse }
                                if (isMouse && !touchMouse) {
                                    val pos = event.changes.first().position
                                    val (x, y) = mapToStream(pos)
                                    session.sendMouseMove(x, y, 0)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                }
        ) {
            VideoSurface(session, Modifier.fillMaxSize())

            // 触屏鼠标模式的虚拟光标
            if (touchMouse && cursorVisible && !viewOnly) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = cursorPos
                    val path = Path().apply {
                        moveTo(c.x, c.y)
                        lineTo(c.x + 16f, c.y + 5.5f)
                        lineTo(c.x + 7f, c.y + 7f)
                        lineTo(c.x + 5.5f, c.y + 16f)
                        close()
                    }
                    drawPath(path, Color.Black, style = Stroke(width = 2f))
                    drawPath(path, Color.White)
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
                        if (status == SelkiesSession.Status.FAILED) {
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
                    IconButton(onClick = { state.navigate(com.selkies.client.Screen.ServerList) }) {
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
                                if (stats.fps > 0) append(" · ").append(stats.fps).append("fps")
                                if (stats.latencyMs > 0) append(" · ").append(stats.latencyMs).append("ms")
                                if (viewOnly) append(" · 查看模式")
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
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "显示工具栏",
                    color = Color.White, fontSize = 11.sp,
                    modifier = Modifier.padding(2.dp)
                )
            }
        }

        // ============ 底部工具栏 ============
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
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToolButton(Icons.Filled.Keyboard, "键盘") { panel = if (panel == SessionPanel.KEYBOARD) SessionPanel.NONE else SessionPanel.KEYBOARD }
                    ToolButton(Icons.Filled.Abc, "特殊键") { panel = if (panel == SessionPanel.SPECIAL_KEYS) SessionPanel.NONE else SessionPanel.SPECIAL_KEYS }
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
                            Icons.Filled.Mouse, "触屏鼠标",
                            tint = if (touchMouse) Color(0xFF60A5FA) else Color.White
                        ) {
                            touchMouse = !touchMouse
                            cursorVisible = false
                        }
                    }
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
                                SessionPanel.KEYBOARD -> "输入法（可直接打字）"
                                SessionPanel.SPECIAL_KEYS -> "特殊按键与修饰键"
                                SessionPanel.HOTKEYS -> "快捷键"
                                SessionPanel.CLIPBOARD -> "剪贴板"
                                SessionPanel.FILES -> "文件传输"
                                SessionPanel.STATS -> "会话统计"
                                SessionPanel.NONE -> ""
                            },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { panel = SessionPanel.NONE }) { Text("关闭") }
                    }
                    when (panel) {
                        SessionPanel.KEYBOARD -> ImeHostPanel(session)
                        SessionPanel.SPECIAL_KEYS -> SpecialKeysPanel(session)
                        SessionPanel.HOTKEYS -> HotkeysPanel(state, session)
                        SessionPanel.CLIPBOARD -> ClipboardPanel(state, session)
                        SessionPanel.FILES -> FilesPanel(state, session)
                        SessionPanel.STATS -> StatsPanel(session)
                        SessionPanel.NONE -> {}
                    }
                }
            }
        }

        // IME 宿主（Android 隐藏输入框，常驻）
        ImeHost(
            visible = panel == SessionPanel.KEYBOARD,
            onCommitText = { session.typeText(it) },
            onEnter = { session.sendKey(Keysyms.XK_Return, true); session.sendKey(Keysyms.XK_Return, false) },
            onBackspace = {
                session.sendKey(Keysyms.XK_BackSpace, true)
                session.sendKey(Keysyms.XK_BackSpace, false)
            },
            modifier = Modifier.size(1.dp).align(Alignment.Center)
        )
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

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = interaction,
                indication = null
            ) { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, color = Color.White, fontSize = 9.sp, maxLines = 1)
    }
}
