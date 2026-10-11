package com.selkies.client.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selkies.client.core.Keysyms
import com.selkies.client.core.SelkiesSession
import com.selkies.client.model.HotkeyDef
import kotlin.math.roundToInt

/**
 * 快捷按钮栏（官方前端 softButtons 的完整版）：
 * - 一行可横向滚动的快捷按钮，点按即发送（Ctrl+C、Alt+Tab……），支持用户固定的自定义快捷键
 * - 官方风格 CTL/ALT/WIN 粘滞修饰 + TAB/ESC 单发
 * - 整条可拖动（拖动把手），可折叠为悬浮球
 * - 与悬浮键盘修饰键共享同一套粘滞逻辑：先点 CTL 再点 C = Ctrl+C
 */
@Composable
fun QuickShortcutBar(
    session: SelkiesSession,
    hotkeys: List<HotkeyDef>,
    visible: Boolean,
    onDismiss: () -> Unit,
    onOpenManager: () -> Unit
) {
    if (!visible) return

    var offset by remember { mutableStateOf(IntOffset.Zero) }
    var parentSize by remember { mutableStateOf(IntSize.Zero) }
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    var collapsed by remember { mutableStateOf(false) }
    val latches = remember { QuickLatches() }
    var repaint by remember { mutableStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { parentSize = it },
        contentAlignment = Alignment.BottomCenter
    ) {
        if (collapsed) {
            Box(
                modifier = Modifier
                    .padding(bottom = 18.dp)
                    .offset { offset }
                    .background(Color(0xEE1D2530), CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { _, drag ->
                            offset = clampBarOffset(
                                offset + IntOffset(drag.x.roundToInt(), drag.y.roundToInt()),
                                parentSize, IntSize(120, 48)
                            )
                        }
                    }
                    .clickable { collapsed = false }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Bolt, "展开快捷按钮", tint = Color(0xFFFBBF24), modifier = Modifier.size(18.dp))
                    Text("快捷", color = Color(0xFFE2E8F0), fontSize = 12.sp)
                }
            }
        } else {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .offset { offset }
                    .onSizeChanged { barSize = it },
                shape = RoundedCornerShape(12.dp),
                color = Color(0xF0161B22),
                shadowElevation = 8.dp
            ) {
                Column(modifier = Modifier.padding(3.dp)) {
                    // 拖动头
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(20.dp)
                                .pointerInput(Unit) {
                                    detectDragGestures { _, drag ->
                                        offset = clampBarOffset(
                                            offset + IntOffset(drag.x.roundToInt(), drag.y.roundToInt()),
                                            parentSize, barSize
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier
                                    .width(36.dp)
                                    .height(4.dp)
                                    .background(Color(0x55FFFFFF), RoundedCornerShape(2.dp))
                            )
                        }
                        Text("快捷按钮", color = Color(0x88FFFFFF), fontSize = 9.sp)
                        QsChip("管理") { onOpenManager() }
                        QsIcon(Icons.Filled.KeyboardArrowDown, "折叠") { collapsed = true }
                        QsIcon(Icons.Filled.Close, "关闭") { onDismiss() }
                    }

                    val rp = repaint
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // 官方 soft buttons：粘滞修饰
                        QsMod("CTL", Keysyms.XK_Control_L, latches, onRepaint = { repaint++ })
                        QsMod("ALT", Keysyms.XK_Alt_L, latches, onRepaint = { repaint++ })
                        QsMod("WIN", Keysyms.XK_Super_L, latches, onRepaint = { repaint++ })
                        QsDivider()
                        // 单发常用键
                        QsOne("TAB") { session.sendKeyChord(latches.prefix() + Keysyms.XK_Tab); latches.clear(); repaint++ }
                        QsOne("ESC") { session.sendKeyChord(latches.prefix() + Keysyms.XK_Escape); latches.clear(); repaint++ }
                        QsOne("ENTER") { session.sendKeyChord(latches.prefix() + Keysyms.XK_Return); latches.clear(); repaint++ }
                        QsDivider()
                        // 固定的快捷键
                        hotkeys.filter { it.pinned }.forEach { hk ->
                            QsOne(hk.name.take(7)) { session.sendKeyChord(hk.keysyms) }
                        }
                    }
                }
            }
        }
    }
}

/** 与官方一致的粘滞修饰（点按锁定/取消，组合发送后自动清除） */
private class QuickLatches {
    private val set = LinkedHashSet<Long>()
    fun toggle(mod: Long) { if (!set.remove(mod)) set.add(mod) }
    fun has(mod: Long) = set.contains(mod)
    fun clear() = set.clear()
    fun prefix(): List<Long> = set.toList()
}

@Composable
private fun QsMod(label: String, mod: Long, latches: QuickLatches, onRepaint: () -> Unit) {
    val active = latches.has(mod)
    Box(
        modifier = Modifier
            .background(
                if (active) Color(0xFF3B82F6) else Color(0xFF39424E),
                RoundedCornerShape(7.dp)
            )
            .clickable {
                latches.toggle(mod)
                onRepaint()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, color = if (active) Color(0xFF06131F) else Color(0xFFE8EDF4), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun QsOne(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color(0xFF2A323D), RoundedCornerShape(7.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color(0xFFE8EDF4), fontSize = 11.sp)
    }
}

@Composable
private fun QsDivider() {
    Box(Modifier.width(1.dp).height(20.dp).background(Color(0x33FFFFFF)))
}

@Composable
private fun QsChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color(0x33FFFFFF), RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(label, color = Color(0xFFE2E8F0), fontSize = 9.sp)
    }
}

@Composable
private fun QsIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(Color(0x33FFFFFF), RoundedCornerShape(6.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, tint = Color(0xFFE2E8F0), modifier = Modifier.size(13.dp))
    }
}

private fun clampBarOffset(off: IntOffset, parent: IntSize, bar: IntSize): IntOffset {
    if (parent == IntSize.Zero) return off
    val maxX = ((parent.width - 40) / 2).coerceAtLeast(0)
    val minY = -(parent.height - 56).coerceAtLeast(0)
    return IntOffset(off.x.coerceIn(-maxX, maxX), off.y.coerceIn(minY, 0))
}
