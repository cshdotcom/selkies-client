package com.selkies.client.ui.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot

/**
 * v2.7.13 虚拟鼠标面板：点击工具栏「虚拟鼠标」弹出的一只"真正可操作"的虚拟鼠标。
 *
 * - 触控板：单指滑动 = 移动远程指针（相对位移×灵敏度）；轻点 = 左键单击；
 *   按住 ~0.5s 不动 = 抓取左键进入拖拽（拖拽中继续滑动 = 按住左键拖动，松手释放）；
 *   双指轻点 = 右键；双指竖滑 = 滚轮。
 * - 实体按键：左键/中键/右键 真实按下-抬起（可按住不放 + 触控板滑动 = 标准鼠标拖拽/文字选取）。
 * - 滚动滑条：竖直滑条上下拖 = 滚轮滚动（拖多少滚多少），另有 ▲▼ 单格滚动。
 * - 快捷键：复制/粘贴/剪切/全选（Ctrl+C/V/X/A 直发远程，选完即复制）。
 * - 灵敏度：0.5x–3.0x，经 AppSettings.virtualMouseSensitivity 持久化。
 *
 * 所有输入经回调送 SessionScreen（含 viewOnly 门控与 vcursor 视觉反馈），
 * 面板本身不直接触碰 SelkiesSession —— 与远程画面手势处理器完全解耦，零冲突。
 */

/** 触控板长按抓取左键时长 */
private const val VM_LONG_PRESS_MS = 500L
/** 长按判定/拖拽臂位移容差（px） */
private const val VM_HOLD_SLOP_PX = 14f
/** 轻点最大时长 */
private const val VM_TAP_MS = 240L
/** 双指轻点右键窗口 */
private const val VM_TWO_TAP_MS = 320L
/** 滑条每多少 px 产生 1 格滚轮 */
private const val VM_SLIDER_NOTCH_PX = 18f
/** 触控板双指滚动每多少 px 产生 1 格滚轮 */
private const val VM_PAD_NOTCH_PX = 42f

/** 协议按钮位（与 Protocol.BUTTON_* 一致，面板不反向依赖 core 层） */
private object VmBtn {
    const val LEFT = 0x01
    const val MIDDLE = 0x02
    const val RIGHT = 0x04
}

@Composable
fun VirtualMousePanel(
    visible: Boolean,
    modifier: Modifier = Modifier,
    sensitivity: Float,
    onSensitivityChange: (Float) -> Unit,
    onMoveRelative: (dx: Float, dy: Float, mask: Int) -> Unit,
    onPressButton: (button: Int) -> Unit,
    onReleaseButton: (button: Int) -> Unit,
    onTapButton: (button: Int) -> Unit,
    onScroll: (vertical: Boolean, notches: Int) -> Unit,
    onKeyChord: (List<Long>) -> Unit,
    onDismiss: () -> Unit
) {
    // 实体左键按住状态（触控板移动据此带左键掩码 = 标准拖拽）
    var leftHeld by remember { mutableStateOf(false) }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier.padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xE60B1220),
            tonalElevation = 4.dp,
            border = BorderStroke(1.dp, Color(0x33FFFFFF))
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // ---- 标题行：图标 + 标题 + 灵敏度 + 关闭 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Mouse, null, tint = Color(0xFF4ADE80), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("虚拟鼠标", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("灵敏度", color = Color(0xFF9CA3AF), fontSize = 11.sp)
                    var sens by remember(sensitivity) { mutableStateOf(sensitivity) }
                    Slider(
                        value = sens,
                        onValueChange = { sens = it },
                        // 拖动结束才持久化（updateSettings 每次调用都写盘，连拖会写爆）
                        onValueChangeFinished = {
                            onSensitivityChange((sens * 100).toInt() / 100f)
                        },
                        valueRange = 0.5f..3f,
                        modifier = Modifier.width(110.dp).height(26.dp)
                    )
                    Text(
                        String.format("%.2f", sens),
                        color = Color(0xFF60A5FA), fontSize = 11.sp,
                        modifier = Modifier.width(32.dp)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, "关闭", tint = Color(0xFF9CA3AF), modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    TouchPad(
                        modifier = Modifier.weight(1f),
                        sensitivity = sensitivity,
                        leftHeld = leftHeld,
                        onMoveRelative = onMoveRelative,
                        onPressButton = onPressButton,
                        onReleaseButton = onReleaseButton,
                        onTapButton = onTapButton,
                        onScroll = onScroll,
                        onLeftHoldChange = { leftHeld = it }
                    )
                    Spacer(Modifier.width(10.dp))
                    ScrollSlider(onScroll = onScroll)
                }
                Spacer(Modifier.height(8.dp))
                // ---- 鼠标实体按键行 ----
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MouseKeyButton("左键", VmBtn.LEFT, Modifier.weight(1f), {
                        leftHeld = true
                        onPressButton(it)
                    }, {
                        leftHeld = false
                        onReleaseButton(it)
                    })
                    MouseKeyButton("中键", VmBtn.MIDDLE, Modifier.weight(1f), onPressButton, onReleaseButton)
                    MouseKeyButton("右键", VmBtn.RIGHT, Modifier.weight(1f), onPressButton, onReleaseButton)
                }
                Spacer(Modifier.height(6.dp))
                // ---- Ctrl 快捷键行 ----
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyChordButton("复制", listOf(0xffe3L, 0x63L), Modifier.weight(1f), onKeyChord)   // Ctrl+C
                    KeyChordButton("粘贴", listOf(0xffe3L, 0x76L), Modifier.weight(1f), onKeyChord)   // Ctrl+V
                    KeyChordButton("剪切", listOf(0xffe3L, 0x78L), Modifier.weight(1f), onKeyChord)   // Ctrl+X
                    KeyChordButton("全选", listOf(0xffe3L, 0x61L), Modifier.weight(1f), onKeyChord)   // Ctrl+A
                }
            }
        }
    }
}

/**
 * 触控板。手势状态机：
 * - 单指按下 → 500ms 内未超容差移动 = 抓取左键（dragArmed，绿框指示 + 触感由回调层处理）；
 *   抓取后滑动 = 按住左键拖动（文字选取/文件拖拽），松手释放左键。
 * - 快速轻点（<240ms 未移动）= 左键单击。
 * - 第二指按下：未抓取时取消长按；双指轻点（<320ms 未移动）= 右键；
 *   双指竖滑 = 滚轮（取两指平均 Y 位移）。
 * - 外部实体左键按住（leftHeld=true）时滑动自动带左键掩码。
 */
@Composable
private fun TouchPad(
    modifier: Modifier,
    sensitivity: Float,
    leftHeld: Boolean,
    onMoveRelative: (Float, Float, Int) -> Unit,
    onPressButton: (Int) -> Unit,
    onReleaseButton: (Int) -> Unit,
    onTapButton: (Int) -> Unit,
    onScroll: (Boolean, Int) -> Unit,
    onLeftHoldChange: (Boolean) -> Unit
) {
    var dragArmed by remember { mutableStateOf(false) }
    Box(
        modifier
            .height(190.dp)
            .background(Color(0x1400FF88), RoundedCornerShape(12.dp))
            .border(
                if (dragArmed || leftHeld) 2.dp else 1.dp,
                if (dragArmed || leftHeld) Color(0xFF4ADE80) else Color(0x33FFFFFF),
                RoundedCornerShape(12.dp)
            )
            .pointerInput(sensitivity) {
                awaitPointerEventScope {
                    var pid: PointerId? = null
                    var last = Offset.Zero
                    var downAt = 0L
                    var downPos = Offset.Zero
                    var moved = false
                    var accScroll = 0f
                    var secondId: PointerId? = null
                    var secondDownAt = 0L
                    var twoMoved = false
                    var lastAvgY = 0f

                    fun singleReset() {
                        pid = null; moved = false; dragArmed = false
                    }
                    fun fullReset() {
                        singleReset()
                        secondId = null; twoMoved = false; accScroll = 0f
                    }

                    while (true) {
                        val holdWait =
                            if (pid != null && secondId == null && !moved && !dragArmed) {
                                (VM_LONG_PRESS_MS - (System.currentTimeMillis() - downAt)).coerceIn(16L, VM_LONG_PRESS_MS)
                            } else 60_000L
                        val event: PointerEvent? = withTimeoutOrNull(holdWait) {
                            awaitPointerEvent(PointerEventPass.Main)
                        }
                        if (event == null) {
                            // 长按超时 → 抓取左键（拖拽/选取模式）
                            if (pid != null && secondId == null && !moved && !dragArmed) {
                                dragArmed = true
                                onLeftHoldChange(true)
                                onPressButton(VmBtn.LEFT)
                            }
                            continue
                        }
                        val changes = event.changes.filter { it.type == PointerType.Touch }
                        if (changes.isEmpty()) continue
                        when (event.type) {
                            PointerEventType.Press -> {
                                if (pid == null) {
                                    pid = changes.first().id
                                    last = changes.first().position
                                    downPos = last
                                    downAt = System.currentTimeMillis()
                                    moved = false
                                } else if (secondId == null) {
                                    secondId = (changes.firstOrNull { it.id != pid } ?: changes.first()).id
                                    secondDownAt = System.currentTimeMillis()
                                    twoMoved = false
                                    lastAvgY = avgTouchY(changes)
                                    accScroll = 0f
                                }
                            }
                            PointerEventType.Move -> {
                                val c1 = pid?.let { id -> changes.firstOrNull { it.id == id } }
                                val c2 = secondId?.let { id -> changes.firstOrNull { it.id == id } }
                                if (c1 != null && c2 != null) {
                                    // 双指：竖滑 = 滚轮
                                    val avgY = (c1.position.y + c2.position.y) / 2f
                                    val dy = avgY - lastAvgY
                                    lastAvgY = avgY
                                    if (abs(dy) > 0.3f) {
                                        twoMoved = true
                                        accScroll += -dy
                                        val notches = (accScroll / VM_PAD_NOTCH_PX).toInt()
                                        if (notches != 0) {
                                            accScroll -= notches * VM_PAD_NOTCH_PX
                                            onScroll(true, notches)
                                        }
                                    }
                                } else if (c1 != null) {
                                    val pos = c1.position
                                    val delta = pos - last
                                    if (hypot(delta.x, delta.y) > 0.5f) {
                                        if (hypot(pos.x - downPos.x, pos.y - downPos.y) > VM_HOLD_SLOP_PX) moved = true
                                        val mask = if (dragArmed || leftHeld) VmBtn.LEFT else 0
                                        onMoveRelative(delta.x * sensitivity, delta.y * sensitivity, mask)
                                        last = pos
                                    }
                                }
                            }
                            PointerEventType.Release -> {
                                val liftedSecond = changes.any { it.id == secondId }
                                val liftedFirst = changes.any { it.id == pid }
                                if (liftedSecond) secondId = null
                                if (liftedFirst) {
                                    if (dragArmed || leftHeld) {
                                        // 抓取拖拽 / 实体左键按住 → 松手释放左键
                                        if (dragArmed) onReleaseButton(VmBtn.LEFT)
                                        singleReset()
                                    } else if (secondId == null && !moved &&
                                        System.currentTimeMillis() - downAt < VM_TAP_MS
                                    ) {
                                        onTapButton(VmBtn.LEFT)
                                        singleReset()
                                    } else {
                                        singleReset()
                                    }
                                }
                                // 双指全部离开且手势很快 → 右键轻点
                                if (secondId == null && liftedSecond && !twoMoved &&
                                    System.currentTimeMillis() - secondDownAt < VM_TWO_TAP_MS
                                ) {
                                    onTapButton(VmBtn.RIGHT)
                                }
                                if (liftedSecond && !liftedFirst) {
                                    twoMoved = false
                                }
                                if (changes.size >= 2 && pid != null) fullReset()
                            }
                            else -> {}
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.Icon(
                Icons.Filled.Mouse, null,
                tint = if (dragArmed || leftHeld) Color(0xFF4ADE80) else Color(0x66FFFFFF),
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    dragArmed || leftHeld -> "拖拽中（按住左键）"
                    else -> "单指移动 · 轻点=左键 · 长按=拖拽\n双指轻点=右键 · 双指竖滑=滚动"
                },
                color = if (dragArmed || leftHeld) Color(0xFF4ADE80) else Color(0x88FFFFFF),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                lineHeight = 14.sp
            )
        }
    }
}

private fun avgTouchY(changes: List<PointerInputChange>): Float =
    if (changes.isEmpty()) 0f else changes.fold(0f) { a, c -> a + c.position.y } / changes.size

/** 滚动滑条：▲▼ 单格按钮 + 竖直滑条（上下拖 = 滚轮，拖多少滚多少） */
@Composable
private fun ScrollSlider(onScroll: (Boolean, Int) -> Unit) {
    Column(
        Modifier.width(46.dp).height(190.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ▲ 上滚一格
        Box(
            Modifier
                .size(38.dp)
                .background(Color(0x22FFFFFF), RoundedCornerShape(10.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        onScroll(true, -1) // ▲ 上滚一格（协议约定：负数 = SCROLL_UP）
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.KeyboardArrowUp, "上滚", tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        // 竖直滑条轨道：拖动 = 滚轮。拇指下拖 = 内容下滚（滚轮下，正格）。
        Box(
            Modifier
                .weight(1f)
                .width(34.dp)
                .background(Color(0x1400FF88), RoundedCornerShape(17.dp))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(17.dp))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        var lastY = 0f
                        var acc = 0f
                        var active = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val c = event.changes.firstOrNull() ?: continue
                            when (event.type) {
                                PointerEventType.Press -> {
                                    active = true; lastY = c.position.y; acc = 0f; c.consume()
                                }
                                PointerEventType.Move -> {
                                    if (active) {
                                        val dy = c.position.y - lastY
                                        lastY = c.position.y
                                        acc += dy
                                        val notches = (acc / VM_SLIDER_NOTCH_PX).toInt()
                                        if (notches != 0) {
                                            acc -= notches * VM_SLIDER_NOTCH_PX
                                            onScroll(true, notches)
                                        }
                                        c.consume()
                                    }
                                }
                                PointerEventType.Release -> {
                                    active = false; c.consume()
                                }
                                else -> {}
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                repeat(3) {
                    Box(
                        Modifier
                            .padding(vertical = 7.dp)
                            .size(width = 16.dp, height = 3.dp)
                            .background(Color(0x88FFFFFF), RoundedCornerShape(2.dp))
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // ▼ 下滚一格
        Box(
            Modifier
                .size(38.dp)
                .background(Color(0x22FFFFFF), RoundedCornerShape(10.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        onScroll(true, 1) // ▼ 下滚一格（正数 = SCROLL_DOWN）
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, "下滚", tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

/** 实体鼠标按键：按住 = 按下（可保持），松开 = 抬起 —— 标准拖拽语义 */
@Composable
private fun MouseKeyButton(
    label: String,
    button: Int,
    modifier: Modifier,
    onPressButton: (Int) -> Unit,
    onReleaseButton: (Int) -> Unit
) {
    var held by remember { mutableStateOf(false) }
    val bg = if (held) Color(0xFF4ADE80) else Color(0x22FFFFFF)
    val fg = if (held) Color(0xFF052E12) else Color.White
    Box(
        modifier
            .height(40.dp)
            .background(bg, RoundedCornerShape(10.dp))
            .pointerInput(button) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val c = event.changes.firstOrNull() ?: continue
                        when (event.type) {
                            PointerEventType.Press -> {
                                held = true
                                onPressButton(button); c.consume()
                            }
                            PointerEventType.Release -> {
                                if (held) {
                                    held = false
                                    onReleaseButton(button); c.consume()
                                }
                            }
                            else -> {}
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** Ctrl+字母 快捷键（单发和弦） */
@Composable
private fun KeyChordButton(label: String, keysyms: List<Long>, modifier: Modifier, onKeyChord: (List<Long>) -> Unit) {
    var flash by remember { mutableStateOf(false) }
    Box(
        modifier
            .height(34.dp)
            .background(if (flash) Color(0xFF60A5FA) else Color(0x22FFFFFF), RoundedCornerShape(9.dp))
            .pointerInput(keysyms) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (event.type == PointerEventType.Press) {
                            flash = true
                            onKeyChord(keysyms)
                            event.changes.forEach { it.consume() }
                        } else if (event.type == PointerEventType.Release) {
                            flash = false
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (flash) Color.White else Color(0xFFBFDBFE), fontSize = 12.sp)
    }
}
