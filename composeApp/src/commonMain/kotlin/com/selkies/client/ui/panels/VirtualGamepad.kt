package com.selkies.client.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selkies.client.core.SelkiesSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * 虚拟手柄引擎（v2.6.0）：无物理手柄也能玩远程游戏
 * - 维护 17 键 + 6 轴状态，发送官方 js 协议（js,c/b/a/d/h）
 * - 发送开关：只读模式（不发送，仅预览）/ 控制模式
 * - 桌面键盘映射：WASD=左摇杆、方向键=D-Pad、IJKL=右摇杆、QE=肩键、UO=扳机、1234=ABXY
 */
class VirtualGamepadEngine(private val session: SelkiesSession) {

    companion object {
        const val N_BUTTONS = 17
        const val N_AXES = 6
        /** 键盘映射表（keysym → 按钮索引或轴索引）；桌面无触屏的替代方案 */
        val KEY_MAP: Map<Long, Pair<String, Int>> = mapOf(
            0x77L to ("axis" to 1),      // W → 左摇杆 Y-（上）
            0x73L to ("axis" to 1),      // S → 左摇杆 Y+
            0x61L to ("axis" to 0),      // A → 左摇杆 X-
            0x64L to ("axis" to 0),      // D → 左摇杆 X+
            0xff52L to ("btn" to 12),    // ↑ → D-Pad Up
            0xff54L to ("btn" to 13),    // ↓ → D-Pad Down
            0xff51L to ("btn" to 14),    // ← → D-Pad Left
            0xff53L to ("btn" to 15),    // → → D-Pad Right
            0x6fL to ("axis2" to 3),     // O → 右摇杆 Y-
            0x6cL to ("axis2" to 3),     // L → 右摇杆 Y+
            0x6aL to ("axis2" to 2),     // J → 右摇杆 X-
            0x6bL to ("axis2" to 2),     // K → 右摇杆 X+
            0x71L to ("btn" to 4),       // Q → LB
            0x65L to ("btn" to 5),       // E → RB
            0x75L to ("btn" to 6),       // U → LT
            0x69L to ("btn" to 7),       // I → RT
            0x31L to ("btn" to 0),       // 1 → A
            0x32L to ("btn" to 1),       // 2 → B
            0x33L to ("btn" to 2),       // 3 → X
            0x34L to ("btn" to 3),       // 4 → Y
            0xff0dL to ("btn" to 9),     // Enter → Start
            0xff08L to ("btn" to 8),     // Backspace → Back
            0xff50L to ("btn" to 10),    // Home → LS
            0xff57L to ("btn" to 11)     // End → RS
        )
    }

    /** 槽位（0-3，服务器上限 4；默认 3 避开物理手柄常用 0-2） */
    var slot: Int = 3

    /** false = 只读模式（不发送任何 js 消息） */
    var sendEnabled: Boolean = true

    private var connected = false
    private val buttons = FloatArray(N_BUTTONS)
    private val axes = FloatArray(N_AXES)

    /** 键盘按住的轴方向（keysym → pressed） */
    private val heldKeys = HashMap<Long, Boolean>()

    val isConnected: Boolean get() = connected

    fun connect() {
        if (connected) return
        connected = true
        java.util.Arrays.fill(buttons, 0f)
        java.util.Arrays.fill(axes, 0f)
        if (sendEnabled) session.sendGamepadConnect(slot, "Selkies Virtual Gamepad", N_AXES, N_BUTTONS)
    }

    fun disconnect() {
        if (!connected) return
        connected = false
        heldKeys.clear()
        if (sendEnabled) session.sendGamepadDisconnect(slot)
    }

    fun heartbeat() {
        if (connected && sendEnabled) session.sendGamepadHeartbeat(slot)
    }

    fun setButton(index: Int, pressed: Boolean) {
        val v = if (pressed) 1f else 0f
        if (buttons[index] == v) return
        buttons[index] = v
        if (sendEnabled) session.sendGamepadButton(slot, index, v.toDouble())
    }

    fun setAxis(index: Int, value: Float) {
        val v = value.coerceIn(-1f, 1f)
        if (axes[index] == v) return
        axes[index] = v
        if (sendEnabled) session.sendGamepadAxis(slot, index, v.toDouble())
    }

    /** 摇杆直接置值（触摸拖动） */
    fun setStick(axisX: Int, axisY: Int, x: Float, y: Float) {
        setAxis(axisX, x)
        setAxis(axisY, y)
    }

    /**
     * 键盘映射（桌面替代方案）：处理物理键 → 返回是否已消费（true = 不再发到远程）
     */
    fun handleKeysym(keysym: Long, down: Boolean): Boolean {
        val target = KEY_MAP[keysym] ?: return false
        val (kind, idx) = target
        when (kind) {
            "btn" -> setButton(idx, down)
            "axis" -> {
                heldKeys[keysym] = down
                recomputeKeyAxis(idx)
            }
            "axis2" -> {
                heldKeys[keysym] = down
                recomputeKeyAxis(idx)
            }
        }
        return true
    }

    /** 由按住的键重算轴值（数字键盘摇杆：±1 或 0） */
    private fun recomputeKeyAxis(axis: Int) {
        val neg: Long
        val pos: Long
        when (axis) {
            0 -> { neg = 0x61L; pos = 0x64L }   // A / D
            1 -> { neg = 0x77L; pos = 0x73L }   // W / S（屏幕 Y 向下为正）
            2 -> { neg = 0x6aL; pos = 0x6bL }   // J / K
            3 -> { neg = 0x6fL; pos = 0x6cL }   // O / L
            else -> return
        }
        val v = when {
            heldKeys[neg] == true && heldKeys[pos] == true -> 0f
            heldKeys[neg] == true -> -1f
            heldKeys[pos] == true -> 1f
            else -> 0f
        }
        setAxis(axis, v)
    }

    /** 释放全部（窗口失焦/关闭面板时） */
    fun releaseAll() {
        for (i in 0 until N_BUTTONS) setButton(i, false)
        for (i in 0 until N_AXES) setAxis(i, 0f)
        heldKeys.clear()
    }
}

/**
 * 虚拟手柄悬浮面板（v2.6.0）：
 * - 模式可选：完整布局 / 精简布局 / 赛车布局
 * - 只读模式：发送开关关闭时仅显示状态不发送
 * - 触屏：摇杆拖动 + 按键长按；桌面：键盘映射（引擎在外部接入物理键拦截）
 */
@Composable
fun VirtualGamepadOverlay(
    engine: VirtualGamepadEngine,
    session: SelkiesSession,
    visible: Boolean,
    initialMode: String,
    keyboardMapping: Boolean,
    onModeChange: (String) -> Unit,
    onKeyboardMappingChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    var mode by remember { mutableStateOf(initialMode) }
    var sendEnabled by remember { mutableStateOf(true) }
    var slot by remember { mutableStateOf(3) }
    var collapsed by remember { mutableStateOf(false) }
    var kbMap by remember { mutableStateOf(keyboardMapping) }

    // 连接生命周期 + 心跳
    DisposableEffect(engine) {
        engine.connect()
        val hb = CoroutineScope(Dispatchers.Default)
        val job = hb.launch {
            while (true) {
                delay(2000)
                engine.heartbeat()
            }
        }
        onDispose {
            job.cancel()
            engine.disconnect()
        }
    }

    engine.sendEnabled = sendEnabled
    engine.slot = slot

    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xE61A1F26),
        // v2.7.0: shadowElevation 移除——SOFTWARE 渲染内容丢失 bug
    ) {
        Column(Modifier.padding(10.dp)) {
            // ---------- 顶栏：模式 / 槽位 / 只读 / 键盘映射 / 折叠 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "虚拟手柄" + if (!sendEnabled) "（只读）" else "",
                    color = Color(0xFF7DD3FC), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                VgChip("完整", mode == "full") { mode = "full"; onModeChange("full") }
                VgChip("精简", mode == "simple") { mode = "simple"; onModeChange("simple") }
                VgChip("赛车", mode == "wheel") { mode = "wheel"; onModeChange("wheel") }
                Spacer(Modifier.width(4.dp))
                VgChip(if (sendEnabled) "发送中" else "只读", sendEnabled) {
                    sendEnabled = !sendEnabled
                    if (!sendEnabled) engine.releaseAll()
                }
                Spacer(Modifier.width(4.dp))
                VgChip("槽位$slot", true) { slot = (slot + 1) % 4 }
                if (!collapsed) {
                    Spacer(Modifier.width(4.dp))
                    VgChip(if (kbMap) "键盘映射开" else "键盘映射", kbMap) {
                        kbMap = !kbMap
                        onKeyboardMappingChange(kbMap)
                    }
                    Spacer(Modifier.width(4.dp))
                    androidx.compose.material3.TextButton(
                        onClick = { collapsed = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) { Text("收起", fontSize = 10.sp) }
                    androidx.compose.material3.TextButton(
                        onClick = { onDismiss() },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) { Text("关闭", fontSize = 10.sp) }
                } else {
                    Spacer(Modifier.width(4.dp))
                    androidx.compose.material3.TextButton(
                        onClick = { collapsed = false },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) { Text("展开", fontSize = 10.sp) }
                    androidx.compose.material3.TextButton(
                        onClick = { onDismiss() },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) { Text("关闭", fontSize = 10.sp) }
                }
            }

            if (!collapsed) {
                if (kbMap) {
                    Text(
                        "键盘映射：WASD=左摇杆 · 方向键=D-Pad · IJKL=右摇杆 · Q/E=肩键 · U/I=扳机 · 1/2/3/4=ABXY · Enter=Start · Backspace=Back",
                        color = Color(0x88FFFFFF), fontSize = 9.sp, modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                when (mode) {
                    "simple" -> SimplePad(engine)
                    "wheel" -> WheelPad(engine)
                    else -> FullPad(engine)
                }
            }
        }
    }
}

// =====================================================================
// 布局
// =====================================================================

/** 完整布局：双摇杆 + D-Pad + ABXY + 肩键扳机 + 系统键 */
@Composable
private fun FullPad(engine: VirtualGamepadEngine) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 肩键 + 扳机行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TriggerSlider("LT", engine, axis = 4, button = 6)
            PadBtn("LB", engine, 4)
            PadBtn("RB", engine, 5)
            TriggerSlider("RT", engine, axis = 5, button = 7)
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(190.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左摇杆 + Back/LS
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Joystick(engine, 0, 1)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PadBtn("Back", engine, 8, small = true)
                    PadBtn("LS", engine, 10, small = true)
                }
            }
            // D-Pad 居中
            DPad(engine)
            // ABXY + Start/RS
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PadBtn("X", engine, 2, round = true, accent = Color(0xFF93C5FD))
                    PadBtn("Y", engine, 3, round = true, accent = Color(0xFFFDE047))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PadBtn("A", engine, 0, round = true, accent = Color(0xFF4ADE80))
                    PadBtn("B", engine, 1, round = true, accent = Color(0xFFF87171))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PadBtn("Start", engine, 9, small = true)
                    PadBtn("RS", engine, 11, small = true)
                }
            }
        }
        PadBtn("☰ 菜单", engine, 16, small = true)
    }
}

/** 精简布局：左摇杆 + D-Pad + A/B + Start（适合小屏手机） */
@Composable
private fun SimplePad(engine: VirtualGamepadEngine) {
    Row(
        modifier = Modifier.fillMaxWidth().height(170.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Joystick(engine, 0, 1)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PadBtn("A", engine, 0, round = true, accent = Color(0xFF4ADE80))
            Spacer(Modifier.height(8.dp))
            PadBtn("B", engine, 1, round = true, accent = Color(0xFFF87171))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DPad(engine)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PadBtn("LB", engine, 4, small = true)
                PadBtn("RB", engine, 5, small = true)
            }
        }
        PadBtn("Start", engine, 9, small = true)
    }
}

/** 赛车布局：转向摇杆（横向）+ 油门/刹车踏板 + 挡位 */
@Composable
private fun WheelPad(engine: VirtualGamepadEngine) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 转向：左摇杆横向（拖动区）
        SteeringSlider(engine)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TriggerSlider("刹车LT", engine, axis = 4, button = 6)
            TriggerSlider("油门RT", engine, axis = 5, button = 7)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            PadBtn("升挡(A)", engine, 0, small = true)
            PadBtn("降挡(B)", engine, 1, small = true)
            PadBtn("手刹(X)", engine, 2, small = true)
            PadBtn("喇叭(Y)", engine, 3, small = true)
            PadBtn("回放(Back)", engine, 8, small = true)
        }
    }
}

// =====================================================================
// 控件
// =====================================================================

/** 摇杆：拖动圆盘，±1 输出 */
@Composable
private fun Joystick(engine: VirtualGamepadEngine, axisX: Int, axisY: Int) {
    var stickX by remember { mutableStateOf(0f) }
    var stickY by remember { mutableStateOf(0f) }
    var active by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(Color(0x33FFFFFF), CircleShape)
                .border(1.dp, if (active) Color(0xFF60A5FA) else Color(0x55FFFFFF), CircleShape)
                .pointerInput(engine) {
                    detectDragGestures(
                        onDragStart = { active = true },
                        onDragEnd = {
                            active = false
                            stickX = 0f; stickY = 0f
                            engine.setStick(axisX, axisY, 0f, 0f)
                        },
                        onDragCancel = {
                            active = false
                            stickX = 0f; stickY = 0f
                            engine.setStick(axisX, axisY, 0f, 0f)
                        }
                    ) { _, drag ->
                        val radius = 44.dp.toPx()
                        var nx = stickX + drag.x / radius
                        var ny = stickY + drag.y / radius
                        val len = hypot(nx, ny)
                        if (len > 1f) { nx /= len; ny /= len }
                        stickX = nx
                        stickY = ny
                        engine.setStick(axisX, axisY, nx, ny)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // 中心十字
            Box(Modifier.size(3.dp).background(Color(0x66FFFFFF), CircleShape))
            // 摇杆头
            Box(
                Modifier
                    .offset(x = (stickX * 44).dp, y = (stickY * 44).dp)
                    .size(30.dp)
                    .background(if (active) Color(0xFF60A5FA) else Color(0xFF64748B), CircleShape)
                    .border(2.dp, Color(0xCCFFFFFF), CircleShape)
            )
        }
        Text(
            "(${String.format(java.util.Locale.US, "%.2f, %.2f", stickX, stickY)})",
            color = Color(0x88FFFFFF), fontSize = 9.sp, textAlign = TextAlign.Center
        )
    }
}

/** D-Pad：四方向 */
@Composable
private fun DPad(engine: VirtualGamepadEngine) {
    val size = 44.dp
    val shape = RoundedCornerShape(8.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        DpadKey(engine, 12, "↑", size, shape)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            DpadKey(engine, 14, "←", size, shape)
            Box(Modifier.size(size).background(Color(0xFF475569), shape))
            DpadKey(engine, 15, "→", size, shape)
        }
        DpadKey(engine, 13, "↓", size, shape)
    }
}

@Composable
private fun DpadKey(engine: VirtualGamepadEngine, btn: Int, label: String, size: androidx.compose.ui.unit.Dp, shape: androidx.compose.foundation.shape.RoundedCornerShape) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(size)
            .background(if (pressed) Color(0xFF60A5FA) else Color(0xFF334155), shape)
            .border(1.dp, if (pressed) Color.White else Color(0x44FFFFFF), shape)
            .pointerInput(engine, btn) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        engine.setButton(btn, true)
                        try { awaitRelease() } catch (_: Exception) { }
                        engine.setButton(btn, false)
                        pressed = false
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** 普通按键 */
@Composable
private fun PadBtn(
    label: String,
    engine: VirtualGamepadEngine,
    btn: Int,
    small: Boolean = false,
    round: Boolean = false,
    accent: Color = Color(0xFF64748B)
) {
    var pressed by remember { mutableStateOf(false) }
    val w = if (small) 52.dp else 58.dp
    val h = if (small) 32.dp else 46.dp
    Box(
        modifier = Modifier
            .size(width = w, height = h)
            .background(
                if (pressed) accent else Color(0xFF334155),
                if (round) CircleShape else RoundedCornerShape(8.dp)
            )
            .border(
                1.dp,
                if (pressed) Color.White else Color(0x44FFFFFF),
                if (round) CircleShape else RoundedCornerShape(8.dp)
            )
            .pointerInput(engine, btn) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        engine.setButton(btn, true)
                        try { awaitRelease() } catch (_: Exception) { }
                        engine.setButton(btn, false)
                        pressed = false
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = if (small) 10.sp else 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** 扳机：垂直滑条（模拟量轴 + 按键） */
@Composable
private fun TriggerSlider(label: String, engine: VirtualGamepadEngine, axis: Int, button: Int) {
    var value by remember { mutableStateOf(0f) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0x99FFFFFF), fontSize = 10.sp)
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(110.dp)
                .background(Color(0x22FFFFFF), RoundedCornerShape(10.dp))
                .pointerInput(engine, axis, button) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        // 自底向上：值 = 1 - (y / height)
                        val h = size.height.toFloat()
                        val y = change.position.y.coerceIn(0f, h)
                        value = (1f - y / h).coerceIn(0f, 1f)
                        engine.setAxis(axis, value)
                        engine.setButton(button, value > 0.5f)
                    }
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height((value * 110).dp)
                    .background(Color(0xFF4ADE80).copy(alpha = 0.75f), RoundedCornerShape(10.dp))
            )
        }
        Text("${(value * 100).toInt()}%", color = Color(0x88FFFFFF), fontSize = 9.sp)
    }
}

/** 赛车转向：横向拖动条（左摇杆 X 轴） */
@Composable
private fun SteeringSlider(engine: VirtualGamepadEngine) {
    var value by remember { mutableStateOf(0f) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("转向（拖动）", color = Color(0x99FFFFFF), fontSize = 10.sp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .background(Color(0x22FFFFFF), RoundedCornerShape(12.dp))
                .pointerInput(engine) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val w = size.width.toFloat()
                        val x = change.position.x.coerceIn(0f, w)
                        value = (x / w * 2f - 1f).coerceIn(-1f, 1f)
                        engine.setAxis(0, value)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // 中心刻度线
            Box(Modifier.width(2.dp).height(44.dp).background(Color(0x55FFFFFF)))
            Box(
                Modifier
                    .offset(x = (value / 2f * 300).dp)
                    .size(width = 18.dp, height = 44.dp)
                    .background(Color(0xFF60A5FA), RoundedCornerShape(6.dp))
            )
            Text(
                "${(value * 100).toInt()}",
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)
            )
        }
    }
}

@Composable
private fun VgChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (active) Color(0xFF2563EB) else Color(0x33FFFFFF),
                RoundedCornerShape(8.dp)
            )
            .clickableNoRipple(onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, color = Color.White, fontSize = 10.sp)
    }
}

/** 无涟漪 clickable（内联工具） */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        detectTapGestures { onClick() }
    }
)
