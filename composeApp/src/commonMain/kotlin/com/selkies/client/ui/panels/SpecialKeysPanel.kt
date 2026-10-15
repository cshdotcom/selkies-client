package com.selkies.client.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selkies.client.core.Keysyms
import com.selkies.client.core.SelkiesSession

private data class KeyDef(val label: String, val keysym: Long, val wide: Boolean = false)

/**
 * 特殊按键面板：修饰键（粘滞）+ 功能键 + 编辑键 + 方向键
 * 移动端发不出的按键全部在这里
 */
@Composable
fun SpecialKeysPanel(session: SelkiesSession) {
    // 粘滞修饰键状态
    var sticky by remember { mutableStateOf(setOf<Long>()) }

    fun toggleModifier(keysym: Long) {
        val on = keysym !in sticky
        if (on) {
            sticky = sticky + keysym
            session.sendKey(keysym, true)
        } else {
            sticky = sticky - keysym
            session.sendKey(keysym, false)
        }
    }

    val modifiers = listOf(
        KeyDef("Ctrl", Keysyms.XK_Control_L),
        KeyDef("Alt", Keysyms.XK_Alt_L),
        KeyDef("Shift", Keysyms.XK_Shift_L),
        KeyDef("Super", Keysyms.XK_Super_L),
        KeyDef("Ctrl→", Keysyms.XK_Control_R),
        KeyDef("AltGr", Keysyms.XK_Alt_R)
    )

    val functionKeys = listOf(
        KeyDef("Esc", Keysyms.XK_Escape), KeyDef("F1", Keysyms.XK_F1), KeyDef("F2", Keysyms.XK_F2),
        KeyDef("F3", Keysyms.XK_F3), KeyDef("F4", Keysyms.XK_F4), KeyDef("F5", Keysyms.XK_F5),
        KeyDef("F6", Keysyms.XK_F6), KeyDef("F7", Keysyms.XK_F7), KeyDef("F8", Keysyms.XK_F8),
        KeyDef("F9", Keysyms.XK_F9), KeyDef("F10", Keysyms.XK_F10), KeyDef("F11", Keysyms.XK_F11),
        KeyDef("F12", Keysyms.XK_F12), KeyDef("PrtSc", Keysyms.XK_Print), KeyDef("ScrLk", Keysyms.XK_Scroll_Lock),
        KeyDef("Pause", Keysyms.XK_Pause), KeyDef("Menu", Keysyms.XK_Menu), KeyDef("SysRq", Keysyms.XK_Sys_Req)
    )

    val editKeys = listOf(
        KeyDef("Tab", Keysyms.XK_Tab), KeyDef("Backspace", Keysyms.XK_BackSpace, true),
        KeyDef("Delete", Keysyms.XK_Delete), KeyDef("Insert", Keysyms.XK_Insert),
        KeyDef("Home", Keysyms.XK_Home), KeyDef("End", Keysyms.XK_End),
        KeyDef("PgUp", Keysyms.XK_Page_Up), KeyDef("PgDn", Keysyms.XK_Page_Down),
        KeyDef("Enter ⏎", Keysyms.XK_Return, true), KeyDef("Space", 0x20L),
        KeyDef("NumLk", Keysyms.XK_Num_Lock), KeyDef("CapsLk", Keysyms.XK_Caps_Lock)
    )

    val arrows = listOf(
        KeyDef("↑", Keysyms.XK_Up), KeyDef("↓", Keysyms.XK_Down),
        KeyDef("←", Keysyms.XK_Left), KeyDef("→", Keysyms.XK_Right)
    )

    val numpad = listOf(
        KeyDef("7", Keysyms.XK_KP_7), KeyDef("8", Keysyms.XK_KP_8), KeyDef("9", Keysyms.XK_KP_9),
        KeyDef("4", Keysyms.XK_KP_4), KeyDef("5", Keysyms.XK_KP_5), KeyDef("6", Keysyms.XK_KP_6),
        KeyDef("1", Keysyms.XK_KP_1), KeyDef("2", Keysyms.XK_KP_2), KeyDef("3", Keysyms.XK_KP_3),
        KeyDef("0", Keysyms.XK_KP_0), KeyDef(".", Keysyms.XK_KP_Decimal), KeyDef("±", Keysyms.XK_KP_Subtract)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 粘滞修饰键
        Text("修饰键（点击保持按下，再点释放）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            modifiers.take(4).forEach { m ->
                KeyChip(m.label, m.keysym in sticky) { toggleModifier(m.keysym) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            modifiers.drop(4).forEach { m ->
                KeyChip(m.label, m.keysym in sticky) { toggleModifier(m.keysym) }
            }
            if (sticky.isNotEmpty()) {
                KeyChip("全部释放", false) {
                    sticky.forEach { session.sendKey(it, false) }
                    sticky = emptySet()
                }
            }
        }

        KeyGroup("功能键", functionKeys, 6) { k -> session.sendKey(k.keysym, true); session.sendKey(k.keysym, false) }
        KeyGroup("编辑键", editKeys, 4) { k -> session.sendKey(k.keysym, true); session.sendKey(k.keysym, false) }

        Text("方向键", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            KeyChip("↑", false) { session.sendKey(Keysyms.XK_Up, true); session.sendKey(Keysyms.XK_Up, false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyChip("←", false) { session.sendKey(Keysyms.XK_Left, true); session.sendKey(Keysyms.XK_Left, false) }
                KeyChip("↓", false) { session.sendKey(Keysyms.XK_Down, true); session.sendKey(Keysyms.XK_Down, false) }
                KeyChip("→", false) { session.sendKey(Keysyms.XK_Right, true); session.sendKey(Keysyms.XK_Right, false) }
            }
        }

        KeyGroup("小键盘", numpad, 6) { k -> session.sendKey(k.keysym, true); session.sendKey(k.keysym, false) }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun KeyGroup(title: String, keys: List<KeyDef>, columns: Int, onKey: (KeyDef) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.height(((keys.size + columns - 1) / columns * 44).dp)
    ) {
        items(keys) { k ->
            KeyChip(k.label, false) { onKey(k) }
        }
    }
}

@Composable
fun KeyChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
