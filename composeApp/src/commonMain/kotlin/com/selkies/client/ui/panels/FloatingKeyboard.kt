package com.selkies.client.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardCapslock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selkies.client.core.Keysyms
import com.selkies.client.core.SelkiesSession
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 悬浮窗可拖动软键盘（非系统输入法）：
 * - 拖动头可整体拖动到屏幕任意位置
 * - 完整布局：QWERTY + 数字行 + 符号层 + Fn 层（F1-F12/编辑键）
 * - 修饰键（Shift/Ctrl/Alt/Meta/Super）粘性组合，与远程组合键兼容
 * - 可折叠为可拖动悬浮球，随时唤起完整键盘
 * - 长按退格/方向键自动重复
 */
private enum class KbLayer { LETTERS, SYMBOLS, FN }

@Composable
fun FloatingKeyboard(
    session: SelkiesSession,
    visible: Boolean,
    onDismiss: () -> Unit
) {
    if (!visible) return

    var layer by remember { mutableStateOf(KbLayer.LETTERS) }
    var shift by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var meta by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf(false) }
    // 相对 BottomCenter 锚点的偏移
    var offset by remember { mutableStateOf(IntOffset.Zero) }
    var parentSize by remember { mutableStateOf(IntSize.Zero) }
    var kbSize by remember { mutableStateOf(IntSize.Zero) }

    fun resetMods() {
        shift = false; ctrl = false; alt = false; meta = false
    }

    /** 发送一个字符键，处理修饰键组合。X11 keysym：Latin-1 区间=码点，超出用 0x01000000|code */
    fun pressChar(c: Char) {
        val sym = if (c.code > 0xFF) (0x01000000L or c.code.toLong()) else c.code.toLong()
        val mods = ArrayList<Long>()
        if (ctrl) mods.add(Keysyms.XK_Control_L)
        if (alt) mods.add(Keysyms.XK_Alt_L)
        if (meta) mods.add(Keysyms.XK_Super_L)
        if (mods.isNotEmpty()) {
            mods.add(sym)
            session.sendKeyChord(mods)
        } else {
            session.sendKey(sym, true)
            session.sendKey(sym, false)
        }
        resetMods()
    }

    /** 发送功能键（Esc/Tab/F1...） */
    fun pressSpecial(keysym: Long) {
        val mods = ArrayList<Long>()
        if (ctrl) mods.add(Keysyms.XK_Control_L)
        if (alt) mods.add(Keysyms.XK_Alt_L)
        if (meta) mods.add(Keysyms.XK_Super_L)
        if (mods.isNotEmpty()) {
            mods.add(keysym)
            session.sendKeyChord(mods)
        } else {
            session.sendKey(keysym, true)
            session.sendKey(keysym, false)
        }
        resetMods()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { parentSize = it },
        contentAlignment = Alignment.BottomCenter
    ) {
        if (collapsed) {
            // 折叠态：可拖动悬浮球（拖动=移动，点击=展开）
            Box(
                modifier = Modifier
                    .padding(bottom = 18.dp)
                    .offset { offset }
                    .background(Color(0xEE1D2530), CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { _, drag ->
                            offset = clampOffset(
                                offset + IntOffset(drag.x.roundToInt(), drag.y.roundToInt()),
                                parentSize, IntSize(160, 56)
                            )
                        }
                    }
                    .clickable { collapsed = false }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Filled.KeyboardCapslock, "展开键盘",
                        tint = Color(0xFF7DD3FC), modifier = Modifier.size(20.dp)
                    )
                    Text("键盘", color = Color(0xFFE2E8F0), fontSize = 13.sp)
                }
            }
        } else {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 6.dp)
                    .offset { offset }
                    .onSizeChanged { kbSize = it },
                shape = RoundedCornerShape(14.dp),
                color = Color(0xF0161B22),
                shadowElevation = 10.dp
            ) {
                Column(modifier = Modifier.padding(4.dp)) {
                    // ---- 拖动头 ----
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 拖动手柄
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(26.dp)
                                .pointerInput(Unit) {
                                    detectDragGestures { _, drag ->
                                        offset = clampOffset(
                                            offset + IntOffset(drag.x.roundToInt(), drag.y.roundToInt()),
                                            parentSize, kbSize
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier
                                    .width(44.dp)
                                    .height(4.dp)
                                    .background(Color(0x66FFFFFF), RoundedCornerShape(2.dp))
                            )
                        }
                        // 层切换
                        MiniChip(if (layer == KbLayer.LETTERS) "?123" else "ABC") {
                            layer = if (layer == KbLayer.LETTERS) KbLayer.SYMBOLS else KbLayer.LETTERS
                            resetMods()
                        }
                        Spacer(Modifier.width(4.dp))
                        MiniChip("Fn", active = layer == KbLayer.FN) {
                            layer = if (layer == KbLayer.FN) KbLayer.LETTERS else KbLayer.FN
                            resetMods()
                        }
                        Spacer(Modifier.width(4.dp))
                        MiniIcon(Icons.Filled.KeyboardArrowDown, "折叠") { collapsed = true }
                        MiniIcon(Icons.Filled.Close, "关闭") { onDismiss() }
                    }

                    val keyBg = Color(0xFF2A323D)
                    val keyBgActive = Color(0xFF3B82F6)

                    when (layer) {
                        KbLayer.LETTERS -> {
                            // 数字行（shift 时变符号）
                            val numRow = if (shift) "!@#$%^&*()" else "1234567890"
                            RowKeyRow {
                                numRow.forEach { c ->
                                    KeyButton(c.toString(), weight = 1f, bg = keyBg, repeatable = false) { pressChar(c) }
                                }
                                KeyButton("⌫", weight = 1.8f, bg = Color(0xFF39424E), repeatable = true) {
                                    pressSpecial(Keysyms.XK_BackSpace)
                                }
                            }
                            RowKeyRow {
                                "qwertyuiop".forEach { c ->
                                    val ch = if (shift) c.uppercaseChar() else c
                                    KeyButton(ch.toString(), weight = 1f, bg = keyBg) { pressChar(ch) }
                                }
                            }
                            RowKeyRow {
                                KeyButton(
                                    "⇧", weight = 1.6f,
                                    bg = if (shift) keyBgActive else Color(0xFF39424E)
                                ) { shift = !shift }
                                "asdfghjkl".forEach { c ->
                                    val ch = if (shift) c.uppercaseChar() else c
                                    KeyButton(ch.toString(), weight = 1f, bg = keyBg) { pressChar(ch) }
                                }
                                KeyButton("⏎", weight = 1.6f, bg = Color(0xFF39424E)) {
                                    pressSpecial(Keysyms.XK_Return)
                                }
                            }
                            RowKeyRow {
                                KeyButton("⇧", weight = 2.1f, bg = if (shift) keyBgActive else Color(0xFF39424E)) { shift = !shift }
                                "zxcvbnm".forEach { c ->
                                    val ch = if (shift) c.uppercaseChar() else c
                                    KeyButton(ch.toString(), weight = 1f, bg = keyBg) { pressChar(ch) }
                                }
                                KeyButton(".", weight = 1f, bg = keyBg) { pressChar('.') }
                                KeyButton("?", weight = 1.5f, bg = Color(0xFF39424E)) {
                                    layer = KbLayer.SYMBOLS
                                }
                            }
                            RowKeyRow {
                                KeyButton("Ctrl", weight = 1.7f, small = true, bg = if (ctrl) keyBgActive else Color(0xFF39424E)) { ctrl = !ctrl }
                                KeyButton("Alt", weight = 1.4f, small = true, bg = if (alt) keyBgActive else Color(0xFF39424E)) { alt = !alt }
                                KeyButton("Super", weight = 1.7f, small = true, bg = if (meta) keyBgActive else Color(0xFF39424E)) { meta = !meta }
                                KeyButton("空格", weight = 4.5f, bg = keyBg) { pressChar(' ') }
                                KeyButton("↑", weight = 1f, bg = keyBg, repeatable = true) { pressSpecial(Keysyms.XK_Up) }
                                KeyButton("↓", weight = 1f, bg = keyBg, repeatable = true) { pressSpecial(Keysyms.XK_Down) }
                                KeyButton("←", weight = 1f, bg = keyBg, repeatable = true) { pressSpecial(Keysyms.XK_Left) }
                                KeyButton("→", weight = 1f, bg = keyBg, repeatable = true) { pressSpecial(Keysyms.XK_Right) }
                            }
                        }

                        KbLayer.SYMBOLS -> {
                            RowKeyRow {
                                "`~-_=+[]{}|;:'\",.<>/?".forEach { c ->
                                    KeyButton(c.toString(), weight = 1f, bg = keyBg) { pressChar(c) }
                                }
                            }
                            RowKeyRow {
                                "\\¡¢£¥§¶«»".forEach { c ->
                                    KeyButton(c.toString(), weight = 1f, bg = keyBg) { pressChar(c) }
                                }
                                KeyButton("Tab", weight = 2f, small = true, bg = Color(0xFF39424E)) { pressSpecial(Keysyms.XK_Tab) }
                            }
                            RowKeyRow {
                                "°±¬µ¿ªº¹²³".forEach { c ->
                                    KeyButton(c.toString(), weight = 1f, bg = keyBg) { pressChar(c) }
                                }
                            }
                            RowKeyRow {
                                KeyButton("ABC", weight = 2f, small = true, bg = Color(0xFF39424E)) { layer = KbLayer.LETTERS }
                                "¼½¾×÷()[]{}".forEach { c ->
                                    KeyButton(c.toString(), weight = 1f, bg = keyBg) { pressChar(c) }
                                }
                                KeyButton("⌫", weight = 2f, bg = Color(0xFF39424E), repeatable = true) {
                                    pressSpecial(Keysyms.XK_BackSpace)
                                }
                            }
                        }

                        KbLayer.FN -> {
                            RowKeyRow {
                                KeyButton("Esc", weight = 1.8f, small = true, bg = Color(0xFF39424E)) { pressSpecial(Keysyms.XK_Escape) }
                                (1..6).forEach { i ->
                                    KeyButton("F$i", weight = 1f, bg = keyBg) { pressSpecial(fkey(i)) }
                                }
                            }
                            RowKeyRow {
                                KeyButton("Del", weight = 1.8f, small = true, bg = Color(0xFF39424E)) { pressSpecial(Keysyms.XK_Delete) }
                                (7..12).forEach { i ->
                                    KeyButton("F$i", weight = 1f, bg = keyBg) { pressSpecial(fkey(i)) }
                                }
                            }
                            RowKeyRow {
                                KeyButton("Home", weight = 1.8f, small = true, bg = keyBg) { pressSpecial(Keysyms.XK_Home) }
                                KeyButton("PgUp", weight = 1.8f, small = true, bg = keyBg) { pressSpecial(Keysyms.XK_Page_Up) }
                                KeyButton("Caps", weight = 1.8f, small = true, bg = keyBg) { pressSpecial(Keysyms.XK_Caps_Lock) }
                                KeyButton("PrtSc", weight = 1.8f, small = true, bg = keyBg) { pressSpecial(Keysyms.XK_Print) }
                                KeyButton("Menu", weight = 1.8f, small = true, bg = keyBg) { pressSpecial(Keysyms.XK_Menu) }
                            }
                            RowKeyRow {
                                KeyButton("End", weight = 1.8f, small = true, bg = keyBg) { pressSpecial(Keysyms.XK_End) }
                                KeyButton("PgDn", weight = 1.8f, small = true, bg = keyBg) { pressSpecial(Keysyms.XK_Page_Down) }
                                KeyButton("Ins", weight = 1.8f, small = true, bg = keyBg) { pressSpecial(Keysyms.XK_Insert) }
                                KeyButton("NumLk", weight = 1.8f, small = true, bg = keyBg) { pressSpecial(Keysyms.XK_Num_Lock) }
                                KeyButton("ScrlLk", weight = 1.8f, small = true, bg = keyBg) { pressSpecial(Keysyms.XK_Scroll_Lock) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fkey(n: Int): Long = when (n) {
    1 -> Keysyms.XK_F1; 2 -> Keysyms.XK_F2; 3 -> Keysyms.XK_F3; 4 -> Keysyms.XK_F4; 5 -> Keysyms.XK_F5; 6 -> Keysyms.XK_F6
    7 -> Keysyms.XK_F7; 8 -> Keysyms.XK_F8; 9 -> Keysyms.XK_F9; 10 -> Keysyms.XK_F10; 11 -> Keysyms.XK_F11; else -> Keysyms.XK_F12
}

private fun clampOffset(off: IntOffset, parent: IntSize, kb: IntSize): IntOffset {
    if (parent == IntSize.Zero) return off
    // 锚点为 BottomCenter：offset.x ∈ [-(parent.w-40), parent.w-40]，y ∈ [-(parent.h-40), 0]
    val maxX = ((parent.width - 40) / 2).coerceAtLeast(0)
    val minX = -maxX
    val maxY = 0
    val minY = -(parent.height - 60).coerceAtLeast(0)
    return IntOffset(
        off.x.coerceIn(minX, maxX),
        off.y.coerceIn(minY, maxY)
    )
}

@Composable
private fun RowKeyRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.KeyButton(
    label: String,
    weight: Float,
    bg: Color,
    small: Boolean = false,
    repeatable: Boolean = false,
    onPress: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // 按下即触发；长按自动重复（退格/方向键）
    LaunchedEffect(pressed) {
        if (pressed) {
            onPress()
            if (repeatable) {
                var n = 0
                while (true) {
                    delay(if (n == 0) 380 else 55)
                    onPress()
                    n++
                }
            }
        }
    }

    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .weight(weight)
            .height(if (small) 38.dp else 44.dp)
            .background(
                if (pressed) Color(0xFF60A5FA) else bg,
                RoundedCornerShape(7.dp)
            )
            .clickable(interactionSource = interaction, indication = null) {},
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = Color(0xFFE8EDF4),
            fontSize = if (small) 11.sp else 15.sp
        )
    }
}

@Composable
private fun MiniChip(label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (active) Color(0xFF3B82F6) else Color(0x33FFFFFF),
                RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(label, color = Color(0xFFE2E8F0), fontSize = 11.sp)
    }
}

@Composable
private fun MiniIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .background(Color(0x33FFFFFF), RoundedCornerShape(6.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, tint = Color(0xFFE2E8F0), modifier = Modifier.size(16.dp))
    }
}
