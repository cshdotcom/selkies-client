package com.selkies.client.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selkies.client.core.SelkiesSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 虚拟手柄引擎 v2.7.7 全面重做：
 * - 双模拟摇杆（浮动锚点 + 死区 + 全向模拟量）——「不要只是按键要有摇杆」
 * - viewer（只读）模式手柄控制端：官方 VIEWER_ALLOWED_PREFIXES 允许 viewer 发 js,*
 *   持有玩家槽位（?role=viewer&slot=N）即可操作游戏 = 官方「4 个只读手柄控制端」
 * - controller 模式：js 槽位读用户设置（virtualGamepadSlot）
 * - 按键触感反馈（Android 振动）
 */
class VirtualGamepadEngine(private val session: SelkiesSession) {

    companion object {
        const val N_BUTTONS = 17
        const val N_AXES = 6
        /** 摇杆死区（0..1）：官方 Gamepad API 默认一致性体验 */
        const val STICK_DEADZONE = 0.12f
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

    /** 0..3。由 refreshRoleSlot() 解析（viewer=slot-1；controller=用户设置） */
    var slot: Int = 0
        private set

    /** false = 不发送（viewer 未持槽位 / 用户关闭） */
    var sendEnabled: Boolean = true
        private set

    /** viewer 未持槽位时的原因（UI 展示 + 引导「以玩家槽位重连」） */
    val viewerBlockReason = MutableStateFlow<String?>(null)

    private var connected = false
    private val buttons = FloatArray(N_BUTTONS)
    private val axes = FloatArray(N_AXES)

    /** 键盘按住的轴方向（keysym → pressed） */
    private val heldKeys = HashMap<Long, Boolean>()

    val isConnected: Boolean get() = connected

    /**
     * 【v2.7.7】按当前会话角色解析 js 槽位与发送权限：
     * - viewer + gamepadSlot(2..4) → index = slot-1，允许发送（官方共享手柄控制端）
     * - viewer 无槽位 → 禁止发送（服务器 gamepad_slot_denied 拒收 viewer 无槽位的 js）
     * - controller → 用户设置槽位（settings.virtualGamepadSlot 0..3）
     */
    fun refreshRoleSlot(userSlot: Int) {
        val role = session.role.value
        val newSlot: Int
        if (role == "viewer") {
            val vs = session.gamepadSlot.value
            if (vs != null && vs in 2..4) {
                newSlot = vs - 1
                sendEnabled = true
                viewerBlockReason.value = null
            } else {
                newSlot = userSlot.coerceIn(0, 3)
                sendEnabled = false
                viewerBlockReason.value = "只读模式未持手柄槽位——服务器拒绝 viewer 的手柄输入。" +
                        "点「以玩家槽位重连」选择 2/3/4 号位即可在只读下玩"
            }
        } else {
            newSlot = userSlot.coerceIn(0, 3)
            sendEnabled = true
            viewerBlockReason.value = null
        }
        if (newSlot != slot) {
            // 槽位变更：换连接（旧槽 js,d → 新槽 js,c）
            if (connected) {
                disconnect()
                slot = newSlot
                connect()
            } else slot = newSlot
        } else slot = newSlot
    }

    fun connect() {
        if (connected) return
        connected = true
        java.util.Arrays.fill(buttons, 0f)
        java.util.Arrays.fill(axes, 0f)
        if (sendEnabled) {
            trace("js,c,$slot (id=Selkies Virtual Gamepad axes=6 buttons=17)")
            session.sendGamepadConnect(slot, "Selkies Virtual Gamepad", N_AXES, N_BUTTONS)
        }
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

    // v2.7.4：最近发送事件（UI 实时反馈——用户能立刻看到「手柄真的在发」）
    val lastEvent = MutableStateFlow<String?>(null)
    private var lastEventAt = 0L
    private fun noteEvent(desc: String) {
        val now = System.currentTimeMillis()
        if (now - lastEventAt > 60) {  // 节流
            lastEvent.value = desc
            lastEventAt = now
        }
    }

    /** 触感反馈（按键/摇杆激活；UI 注入，Android 振动） */
    var onHaptic: (() -> Unit)? = null
    private fun haptic() { runCatching { onHaptic?.invoke() } }

    /** v2.7.7 测试可观测：-Dselkies.padtrace 开启后打印全部 js 发送（GUI 实测链路铁证） */
    private val padTrace = System.getProperty("selkies.padtrace") != null
    private fun trace(msg: String) { if (padTrace) println("[Selkies][PadTrace] $msg") }

    fun setButton(index: Int, pressed: Boolean) {
        val v = if (pressed) 1f else 0f
        if (buttons[index] == v) return
        buttons[index] = v
        if (pressed) haptic()
        if (sendEnabled) {
            trace("js,b,$slot,$index,$v")
            session.sendGamepadButton(slot, index, v.toDouble())
            noteEvent("按钮${buttonName(index)}${if (pressed) "按下" else "松开"} → js,b,$slot,$index,$v")
        }
    }

    fun setAxis(index: Int, value: Float) {
        val v = value.coerceIn(-1f, 1f)
        if (axes[index] == v) return
        axes[index] = v
        if (sendEnabled) {
            trace("js,a,$slot,$index,$v")
            session.sendGamepadAxis(slot, index, v.toDouble())
            noteEvent("轴${axisName(index)}=${"%.2f".format(v)} → js,a,$slot,$index")
        }
    }

    /** 摇杆直接置值（触摸拖动；带死区处理） */
    fun setStick(axisX: Int, axisY: Int, x: Float, y: Float) {
        setAxis(axisX, applyDeadzone(x))
        setAxis(axisY, applyDeadzone(y))
    }

    /** 死区 + 线性重标定（中心附近输出 0；越过死区线性映射到全量程） */
    private fun applyDeadzone(v: Float, dz: Float = STICK_DEADZONE): Float {
        val a = abs(v)
        if (a < dz) return 0f
        val scaled = (a - dz) / (1f - dz)
        return (if (v < 0) -scaled else scaled).coerceIn(-1f, 1f)
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

    // v2.7.4：按钮/轴名称（事件反馈用；与官方 XPAD_CONFIG 客户端编号一致）
    private fun buttonName(i: Int) = when (i) {
        0 -> "A"; 1 -> "B"; 2 -> "X"; 3 -> "Y"; 4 -> "LB"; 5 -> "RB"
        6 -> "LT"; 7 -> "RT"; 8 -> "Back"; 9 -> "Start"; 10 -> "LS"; 11 -> "RS"
        12 -> "上"; 13 -> "下"; 14 -> "左"; 15 -> "右"; 16 -> "Guide"; else -> "#$i"
    }
    private fun axisName(i: Int) = when (i) {
        0 -> "左X"; 1 -> "左Y"; 2 -> "右X"; 3 -> "右Y"; 4 -> "LT"; 5 -> "RT"; else -> "#$i"
    }
}

/**
 * 虚拟手柄悬浮面板 v2.7.7 重做：
 * - Xbox 风格布局：双浮动模拟摇杆（锚点跟随手指）+ ABXY 菱形 + 一体化十字键
 *   + 模拟量扳机（LT/RT 填充条）+ 肩键 + Back/Guide/Start + L3/R3
 * - 只读（viewer）模式可用：持槽位照常发送；无槽位给出引导
 * - 模式：full 完整 / simple 精简（小屏）/ wheel 赛车
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
    var slotPref by remember { mutableStateOf(0) }
    var collapsed by remember { mutableStateOf(false) }
    var kbMap by remember { mutableStateOf(keyboardMapping) }
    val role by session.role.collectAsState()
    val gamepadSlot by session.gamepadSlot.collectAsState()

    // 【v2.7.7】角色/槽位变化 → 引擎重新解析发送权限（viewer 被顶替/重连/切换后自动跟随）
    LaunchedEffect(role, gamepadSlot, slotPref) {
        engine.refreshRoleSlot(slotPref)
    }

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

    androidx.compose.material3.Surface(
        modifier = Modifier
            // ⚠️【v2.7.7 顺序修复】widthIn 必须在 fillMaxWidth 之前（外层）：
            //   旧顺序 fillMaxWidth().widthIn(max=820) 实测渲染为全宽 1280（fillMaxWidth 强制占满
            //   父约束）——桌面大屏上摇杆贴左右屏幕边缘、布局散架。交换后先钳到 ≤820 再填充。
            .widthIn(max = 820.dp)
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xF00F1318),
        // v2.7.0: shadowElevation 移除——SOFTWARE 渲染内容丢失 bug
    ) {
        Column(Modifier.padding(10.dp)) {
            // ---------- 顶栏：模式 / 槽位 / 键盘映射 / 折叠 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        role == "viewer" && gamepadSlot != null -> "虚拟手柄 · 只读·玩家${gamepadSlot}位"
                        role == "viewer" -> "虚拟手柄 · 只读"
                        else -> "虚拟手柄"
                    },
                    color = Color(0xFF7DD3FC), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                VgChip("完整", mode == "full") { mode = "full"; onModeChange("full") }
                VgChip("精简", mode == "simple") { mode = "simple"; onModeChange("simple") }
                VgChip("赛车", mode == "wheel") { mode = "wheel"; onModeChange("wheel") }
                Spacer(Modifier.width(4.dp))
                VgChip("键位$slotPref", role != "viewer") {
                    slotPref = (slotPref + 1) % 4
                }
                if (!collapsed) {
                    Spacer(Modifier.width(4.dp))
                    VgChip(if (kbMap) "键盘映射开" else "键盘映射", kbMap) {
                        kbMap = !kbMap
                        onKeyboardMappingChange(kbMap)
                    }
                    Spacer(Modifier.width(4.dp))
                    androidx.compose.material3.TextButton(
                        onClick = { collapsed = true },
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("收起", fontSize = 10.sp) }
                    androidx.compose.material3.TextButton(
                        onClick = { onDismiss() },
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("关闭", fontSize = 10.sp) }
                } else {
                    Spacer(Modifier.width(4.dp))
                    androidx.compose.material3.TextButton(
                        onClick = { collapsed = false },
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("展开", fontSize = 10.sp) }
                    androidx.compose.material3.TextButton(
                        onClick = { onDismiss() },
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("关闭", fontSize = 10.sp) }
                }
            }

            // v2.7.4：连接状态 + 最近事件实时反馈
            val lastEvent by engine.lastEvent.collectAsState()
            val blockReason by engine.viewerBlockReason.collectAsState()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (engine.isConnected) "已连接 js${engine.slot}" else "未连接",
                    color = if (engine.isConnected) Color(0xFF4ADE80) else Color(0xFFF87171),
                    fontSize = 9.sp
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    lastEvent ?: "等待输入…（按按钮/拖摇杆，此处显示发送事件）",
                    color = Color(0x99FFFFFF), fontSize = 9.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            // 【v2.7.7】viewer 无槽位：明确引导（官方只读手柄控制端需要 ?slot=2..4）
            blockReason?.let { reason ->
                Text(
                    reason,
                    color = Color(0xFFFBBF24), fontSize = 9.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                )
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
// 布局（v2.7.7 重做：Xbox 风格）
// =====================================================================

/** 手柄配色 */
private object PadColors {
    val A = Color(0xFF4ADE80)   // 绿
    val B = Color(0xFFF87171)   // 红
    val X = Color(0xFF60A5FA)   // 蓝
    val Y = Color(0xFFFBBF24)   // 黄
    val ACCENT = Color(0xFF60A5FA)
    val KEYFACE = Color(0xFF2A3441)
    val KEYFACE_PRESS = Color(0xFF3B4A5C)
    val GUIDE = Color(0xFF94A3B8)
}

/** 完整布局：Xbox 式双摇杆 + 十字键 + ABXY 菱形 + 扳机肩键 + 系统键 */
@Composable
private fun FullPad(engine: VirtualGamepadEngine) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 肩键 + 扳机行（模拟量）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TriggerBar("LT", engine, axis = 4, button = 6)
            ShoulderBtn("LB", engine, 4)
            ShoulderBtn("RB", engine, 5)
            TriggerBar("RT", engine, axis = 5, button = 7)
        }
        // 主区：左摇杆 | 十字 + 系统 | ABXY + 右摇杆
        Row(
            modifier = Modifier.fillMaxWidth().height(210.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左摇杆 + L3 + Back
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FloatingJoystick(engine, 0, 1, label = "L")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PadBtn("Back", engine, 8, small = true)
                    PadBtn("L3", engine, 10, small = true)
                }
            }
            // 中央：十字键 + Guide
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CrossDpad(engine)
                Spacer(Modifier.height(6.dp))
                PadBtn("☰", engine, 16, small = true, accent = PadColors.GUIDE)
            }
            // ABXY 菱形 + Start + 右摇杆
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(52.dp))
                    PadBtn("Y", engine, 3, round = true, accent = PadColors.Y)
                    Box(Modifier.size(52.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PadBtn("X", engine, 2, round = true, accent = PadColors.X)
                    Text("R", color = Color(0x55FFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    PadBtn("B", engine, 1, round = true, accent = PadColors.B)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(52.dp))
                    PadBtn("A", engine, 0, round = true, accent = PadColors.A)
                    Box(Modifier.size(52.dp))
                }
            }
            // 右摇杆 + R3 + Start
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FloatingJoystick(engine, 2, 3, label = "R")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PadBtn("R3", engine, 11, small = true)
                    PadBtn("Start", engine, 9, small = true)
                }
            }
        }
    }
}

/** 精简布局：左摇杆 + 十字 + A/B + LB/RB + Start（小屏手机） */
@Composable
private fun SimplePad(engine: VirtualGamepadEngine) {
    Row(
        modifier = Modifier.fillMaxWidth().height(180.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        FloatingJoystick(engine, 0, 1, label = "L", compact = true)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PadBtn("A", engine, 0, round = true, accent = PadColors.A)
            Spacer(Modifier.height(8.dp))
            PadBtn("B", engine, 1, round = true, accent = PadColors.B)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CrossDpad(engine, compact = true)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PadBtn("LB", engine, 4, small = true)
                PadBtn("RB", engine, 5, small = true)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TriggerBar("RT", engine, axis = 5, button = 7, compact = true)
            Spacer(Modifier.height(6.dp))
            PadBtn("Start", engine, 9, small = true)
        }
    }
}

/** 赛车布局：转向摇杆 + 油门/刹车踏板 + 挡位 */
@Composable
private fun WheelPad(engine: VirtualGamepadEngine) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SteeringSlider(engine)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TriggerBar("刹车LT", engine, axis = 4, button = 6)
            TriggerBar("油门RT", engine, axis = 5, button = 7)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            PadBtn("升挡(A)", engine, 0, small = true, accent = PadColors.A)
            PadBtn("降挡(B)", engine, 1, small = true, accent = PadColors.B)
            PadBtn("手刹(X)", engine, 2, small = true, accent = PadColors.X)
            PadBtn("喇叭(Y)", engine, 3, small = true, accent = PadColors.Y)
            PadBtn("回放(Back)", engine, 8, small = true)
        }
    }
}

// =====================================================================
// 控件（v2.7.7 全面升级）
// =====================================================================

/**
 * 浮动模拟摇杆（体验核心）：
 * - 按下处即摇杆中心（锚点跟随手指——手指按哪都自然，不用瞄准固定圆心）
 * - 全向模拟量 ±1、死区 0.12、圆形限幅
 * - 视觉：刻度环 + 径向渐变摇杆头 + 激活光环 + 实时数值
 */
@Composable
private fun FloatingJoystick(
    engine: VirtualGamepadEngine,
    axisX: Int,
    axisY: Int,
    label: String,
    compact: Boolean = false
) {
    val zone = if (compact) 132.dp else 158.dp
    var active by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(Offset.Zero) }   // 本次触摸的摇杆中心（区域局部坐标）
    var knob by remember { mutableStateOf(Offset.Zero) }     // 当前偏移（已限幅）
    var stickX by remember { mutableStateOf(0f) }
    var stickY by remember { mutableStateOf(0f) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(zone)
                .pointerInput(engine, axisX, axisY) {
                    val radius = (zone.toPx() / 2f) * 0.72f   // 可用半径（留边）
                    fun apply(dx: Float, dy: Float) {
                        var v = Offset(dx / radius, dy / radius)
                        val len = hypot(v.x, v.y)
                        if (len > 1f) v = Offset(v.x / len, v.y / len)
                        knob = v * radius
                        stickX = v.x
                        stickY = v.y
                        engine.setStick(axisX, axisY, v.x, v.y)
                    }
                    fun reset() {
                        active = false
                        anchor = Offset.Zero
                        knob = Offset.Zero
                        stickX = 0f; stickY = 0f
                        engine.setStick(axisX, axisY, 0f, 0f)
                    }
                    detectDragGestures(
                        onDragStart = { pos ->
                            active = true
                            anchor = pos
                            knob = Offset.Zero
                        },
                        onDragEnd = { reset() },
                        onDragCancel = { reset() }
                    ) { change, _ ->
                        change.consume()
                        apply(change.position.x - anchor.x, change.position.y - anchor.y)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val c = center
                val r = size.minDimension / 2f
                // 底盘（固定视觉参考环）
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color(0x33000000), Color(0x55000000)),
                        center = c, radius = r * 0.9f
                    ),
                    radius = r * 0.9f
                )
                drawCircle(Color(0x33FFFFFF), radius = r * 0.9f, style = Stroke(1.5.dp.toPx()))
                // 8 方向刻度
                val tickR1 = r * 0.82f
                val tickR2 = r * 0.92f
                for (i in 0 until 8) {
                    val ang = Math.PI * i / 4.0
                    drawLine(
                        if (active) Color(0xAA60A5FA) else Color(0x55FFFFFF),
                        Offset(c.x + (tickR1 * cos(ang)).toFloat(), c.y + (tickR1 * sin(ang)).toFloat()),
                        Offset(c.x + (tickR2 * cos(ang)).toFloat(), c.y + (tickR2 * sin(ang)).toFloat()),
                        strokeWidth = 2.dp.toPx()
                    )
                }
                // 中心十字
                drawLine(Color(0x44FFFFFF), Offset(c.x - 6.dp.toPx(), c.y), Offset(c.x + 6.dp.toPx(), c.y), 1.5f)
                drawLine(Color(0x44FFFFFF), Offset(c.x, c.y - 6.dp.toPx()), Offset(c.x, c.y + 6.dp.toPx()), 1.5f)

                // 摇杆活动中心（锚点）+ 摇杆头
                val a = if (active) anchor else c
                val knobR = r * 0.34f
                // 锚点提示环（按下处）
                if (active) {
                    drawCircle(Color(0x3360A5FA), radius = r * 0.62f, center = a, style = Stroke(1.dp.toPx()))
                }
                val k = a + knob
                // 激活光环
                if (active) {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Color(0x5560A5FA), Color(0x0060A5FA)),
                            center = k, radius = knobR * 1.7f
                        ),
                        radius = knobR * 1.7f, center = k
                    )
                }
                // 摇杆头：径向渐变球 + 白描边
                drawCircle(
                    Brush.radialGradient(
                        listOf(
                            if (active) Color(0xFF7EB8FF) else Color(0xFF64748B),
                            if (active) Color(0xFF2F5FA8) else Color(0xFF334155)
                        ),
                        center = k, radius = knobR
                    ),
                    radius = knobR, center = k
                )
                drawCircle(Color(0xCCFFFFFF), radius = knobR, center = k, style = Stroke(2.dp.toPx()))
                // 摇杆头中心点
                drawCircle(Color(0x88FFFFFF), radius = 3.dp.toPx(), center = k)
                // 方向指示线（锚点 → 摇杆头）
                if (active && (abs(knob.x) > 1f || abs(knob.y) > 1f)) {
                    drawLine(Color(0x9960A5FA), a, k, 2.dp.toPx())
                }
            }
        }
        Text(
            buildString {
                append(label)
                append(" (")
                append(String.format(java.util.Locale.US, "%.2f, %.2f", stickX, stickY))
                append(")")
            },
            color = Color(0x88FFFFFF), fontSize = 9.sp, textAlign = TextAlign.Center
        )
    }
}

/** 一体化十字键（Xbox 式整体十字；四臂独立按压） */
@Composable
private fun CrossDpad(engine: VirtualGamepadEngine, compact: Boolean = false) {
    val arm = if (compact) 38.dp else 46.dp
    val shape = RoundedCornerShape(7.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        DpadKey(engine, 12, "▲", arm, shape)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            DpadKey(engine, 14, "◀", arm, shape)
            Box(Modifier.size(arm).background(Color(0xFF232C38), shape))
            DpadKey(engine, 15, "▶", arm, shape)
        }
        DpadKey(engine, 13, "▼", arm, shape)
    }
}

@Composable
private fun DpadKey(engine: VirtualGamepadEngine, btn: Int, label: String, size: androidx.compose.ui.unit.Dp, shape: androidx.compose.foundation.shape.RoundedCornerShape) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(size)
            .background(if (pressed) PadColors.ACCENT else Color(0xFF2A3441), shape)
            .border(1.dp, if (pressed) Color.White else Color(0x33FFFFFF), shape)
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
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** 普通按键（v2.7.7：按下渐变高光 + 圆形 ABXY） */
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
    val w = if (small) 52.dp else 56.dp
    val h = if (small) 30.dp else 56.dp
    val shape = if (round) CircleShape else RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(width = w, height = h)
            .background(if (pressed) accent else PadColors.KEYFACE, shape)
            .border(
                if (pressed) 2.dp else 1.dp,
                if (pressed) Color.White else Color(0x44FFFFFF),
                shape
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
        Text(
            label,
            color = Color.White,
            fontSize = if (small) 10.sp else 14.sp,
            fontWeight = if (round) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/** 肩键（LB/RB）：宽扁按钮 */
@Composable
private fun ShoulderBtn(label: String, engine: VirtualGamepadEngine, btn: Int) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(width = 74.dp, height = 40.dp)
            .background(if (pressed) PadColors.ACCENT else PadColors.KEYFACE, RoundedCornerShape(12.dp))
            .border(1.dp, if (pressed) Color.White else Color(0x44FFFFFF), RoundedCornerShape(12.dp))
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
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 扳机（LT/RT）：模拟量垂直填充条 + 数字阈值按钮 */
@Composable
private fun TriggerBar(label: String, engine: VirtualGamepadEngine, axis: Int, button: Int, compact: Boolean = false) {
    var value by remember { mutableStateOf(0f) }
    val h = if (compact) 86.dp else 118.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0x99FFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Medium)
        Box(
            modifier = Modifier
                .width(46.dp)
                .height(h)
                .background(Color(0x1FFFFFFF), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                .pointerInput(engine, axis, button) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val hh = size.height.toFloat()
                        val y = change.position.y.coerceIn(0f, hh)
                        value = (1f - y / hh).coerceIn(0f, 1f)
                        engine.setAxis(axis, value)
                        engine.setButton(button, value > 0.5f)
                    }
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            // 填充（渐变：底部亮）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(h * value)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF4ADE80).copy(alpha = 0.5f), Color(0xFF4ADE80).copy(alpha = 0.85f))
                        ),
                        RoundedCornerShape(12.dp)
                    )
            )
            // 中心刻度
            Box(Modifier.width(2.dp).height(6.dp).background(Color(0x55FFFFFF)))
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
            Box(Modifier.width(2.dp).height(44.dp).background(Color(0x55FFFFFF)))
            Box(
                Modifier
                    .offset(x = (value / 2f * 300).dp)
                    .size(width = 18.dp, height = 44.dp)
                    .background(PadColors.ACCENT, RoundedCornerShape(6.dp))
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
