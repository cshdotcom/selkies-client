package com.selkies.client.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardCapslock
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selkies.client.core.Keysyms
import com.selkies.client.core.SelkiesSession
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 世界最完整键盘（151 键）——悬浮窗可拖动、可缩放、可折叠、双模式：
 *
 * - 【分类模式（默认）】按键自动分类，指尖尺寸大键：
 *   常用（打字主区）/ 功能（Esc+F1-F12）/ 导航（编辑区+方向键）/ 小键盘 / 媒体 / 扩展 / 文本合成条
 * - 【完整模式】完整 23 单位网格真实 PC 布局（151 键一屏），可横向滚动、双指缩放
 * - 修饰键（Ctrl/Shift/Alt/Super/AltGr）单次粘滞，双击锁定，与远程组合键兼容
 * - 文本合成条：与输入法等效——可调用系统输入法（中文等）输入任意文本，回车发送（co,end 协议）
 * - 双指捏合缩放 / +− 按钮缩放，长按退格/方向键等自动重复
 * - 选择器模式（CompleteKeyboardPicker 语义）：供快捷键创建时用完整键盘选键（onChord）
 */

private enum class KbTab { MAIN, FUNC, NAV, NUMPAD, MEDIA, EXTRA, TEXT, FULL }

/** 键描述 */
private class K(
    val label: String,
    val keysym: Long,
    val weight: Float = 1f,
    val shiftLabel: String? = null,   // Shift 粘滞时显示的标签
    val mod: Long = 0L,              // 非 0 = 该键是修饰键
    val repeat: Boolean = false,     // 长按重复
    val dark: Boolean = false,       // 深色底（功能区）
    val tiny: Boolean = false,       // 更小字号
    val ghost: Boolean = false,      // 占位（真实键盘上被跨行键占据的位置，渲染为延伸块）
    val ghostDark: Boolean = false,  // 延伸块颜色跟随上方键
    val gap: Boolean = false         // 不可见间隙
) {
    companion object
}

/** 不可见间隙键（保持网格对齐） */
private fun G(w: Float) = K("", 0L, w, gap = true)

private object KbLayout {

    /** 分类模式·常用页：13 单位打字主区（指尖尺寸，自适应宽度） */
    fun compactMain(): List<List<K>> = listOf(
        listOf(
            K("1", '1'.code.toLong(), 1f, shiftLabel = "!"),
            K("2", '2'.code.toLong(), 1f, shiftLabel = "@"),
            K("3", '3'.code.toLong(), 1f, shiftLabel = "#"),
            K("4", '4'.code.toLong(), 1f, shiftLabel = "$"),
            K("5", '5'.code.toLong(), 1f, shiftLabel = "%"),
            K("6", '6'.code.toLong(), 1f, shiftLabel = "^"),
            K("7", '7'.code.toLong(), 1f, shiftLabel = "&"),
            K("8", '8'.code.toLong(), 1f, shiftLabel = "*"),
            K("9", '9'.code.toLong(), 1f, shiftLabel = "("),
            K("0", '0'.code.toLong(), 1f, shiftLabel = ")"),
            K("-", '-'.code.toLong(), 1f, shiftLabel = "_"),
            K("=", '='.code.toLong(), 1f, shiftLabel = "+"),
            K("⌫", Keysyms.XK_BackSpace, 1.3f, dark = true, repeat = true)
        ),
        listOf(
            K("Tab", Keysyms.XK_Tab, 1.4f, dark = true, tiny = true),
            K("Q", 'q'.code.toLong()), K("W", 'w'.code.toLong()), K("E", 'e'.code.toLong()),
            K("R", 'r'.code.toLong()), K("T", 't'.code.toLong()), K("Y", 'y'.code.toLong()),
            K("U", 'u'.code.toLong()), K("I", 'i'.code.toLong()), K("O", 'o'.code.toLong()),
            K("P", 'p'.code.toLong()),
            K("[", '['.code.toLong(), 1f, shiftLabel = "{"),
            K("]", ']'.code.toLong(), 1f, shiftLabel = "}"),
            K("⏎", Keysyms.XK_Return, 1.6f, dark = true)
        ),
        listOf(
            K("Caps", Keysyms.XK_Caps_Lock, 1.6f, dark = true, tiny = true),
            K("A", 'a'.code.toLong()), K("S", 's'.code.toLong()), K("D", 'd'.code.toLong()),
            K("F", 'f'.code.toLong()), K("G", 'g'.code.toLong()), K("H", 'h'.code.toLong()),
            K("J", 'j'.code.toLong()), K("K", 'k'.code.toLong()), K("L", 'l'.code.toLong()),
            K(";", ';'.code.toLong(), 1f, shiftLabel = ":"),
            K("'", '\''.code.toLong(), 1f, shiftLabel = "\""),
            K("\\", '\\'.code.toLong(), 1f, shiftLabel = "|"),
            K("Esc", Keysyms.XK_Escape, 1.4f, dark = true, tiny = true)
        ),
        listOf(
            K("⇧", Keysyms.XK_Shift_L, 1.8f, mod = Keysyms.XK_Shift_L, dark = true, tiny = true),
            K("Z", 'z'.code.toLong()), K("X", 'x'.code.toLong()), K("C", 'c'.code.toLong()),
            K("V", 'v'.code.toLong()), K("B", 'b'.code.toLong()), K("N", 'n'.code.toLong()),
            K("M", 'm'.code.toLong()),
            K(",", ','.code.toLong(), 1f, shiftLabel = "<"),
            K(".", '.'.code.toLong(), 1f, shiftLabel = ">"),
            K("/", '/'.code.toLong(), 1f, shiftLabel = "?"),
            K("↑", Keysyms.XK_Up, 1f, dark = true, repeat = true)
        ),
        listOf(
            K("Ctrl", Keysyms.XK_Control_L, 1.4f, mod = Keysyms.XK_Control_L, dark = true, tiny = true),
            K("Win", Keysyms.XK_Super_L, 1.2f, mod = Keysyms.XK_Super_L, dark = true, tiny = true),
            K("Alt", Keysyms.XK_Alt_L, 1.2f, mod = Keysyms.XK_Alt_L, dark = true, tiny = true),
            K("空格", ' '.code.toLong(), 5.2f),
            K("AltGr", Keysyms.XK_ISO_Level3_Shift, 1.2f, mod = Keysyms.XK_ISO_Level3_Shift, dark = true, tiny = true),
            K("←", Keysyms.XK_Left, 1f, dark = true, repeat = true),
            K("↓", Keysyms.XK_Down, 1f, dark = true, repeat = true),
            K("→", Keysyms.XK_Right, 1f, dark = true, repeat = true)
        )
    )

    /** 分类模式·功能页 */
    fun compactFunc(): List<List<K>> = listOf(
        listOf(
            K("Esc", Keysyms.XK_Escape, 1.4f, dark = true, tiny = true),
            K("F1", Keysyms.XK_F1), K("F2", Keysyms.XK_F2), K("F3", Keysyms.XK_F3),
            K("F4", Keysyms.XK_F4), K("F5", Keysyms.XK_F5), K("F6", Keysyms.XK_F6)
        ),
        listOf(
            K("F7", Keysyms.XK_F7), K("F8", Keysyms.XK_F8), K("F9", Keysyms.XK_F9),
            K("F10", Keysyms.XK_F10), K("F11", Keysyms.XK_F11), K("F12", Keysyms.XK_F12)
        ),
        listOf(
            K("PrtSc", Keysyms.XK_Print, 1.2f, dark = true, tiny = true),
            K("ScrLk", Keysyms.XK_Scroll_Lock, 1.2f, dark = true, tiny = true),
            K("Pause", Keysyms.XK_Pause, 1.2f, dark = true, tiny = true),
            K("Menu", Keysyms.XK_Menu, 1.2f, dark = true, tiny = true),
            K("NumLk", Keysyms.XK_Num_Lock, 1.2f, dark = true, tiny = true),
            K("Caps", Keysyms.XK_Caps_Lock, 1.2f, dark = true, tiny = true)
        ),
        listOf(
            K("`", '`'.code.toLong(), 1f, shiftLabel = "~"),
            K("F13", Keysyms.XK_F13), K("F14", Keysyms.XK_F14), K("F15", Keysyms.XK_F15),
            K("F16", Keysyms.XK_F16), K("F17", Keysyms.XK_F17), K("F18", Keysyms.XK_F18)
        )
    )

    /** 分类模式·导航页 */
    fun compactNav(): List<List<K>> = listOf(
        listOf(
            G(1f),
            K("Ins", Keysyms.XK_Insert, 1f, dark = true, tiny = true),
            K("Home", Keysyms.XK_Home, 1f, dark = true, tiny = true),
            K("PgUp", Keysyms.XK_Page_Up, 1f, dark = true, tiny = true),
            G(1f),
            K("Tab", Keysyms.XK_Tab, 1f, dark = true, tiny = true),
            K("⏎", Keysyms.XK_Return, 1f, dark = true)
        ),
        listOf(
            G(1.5f),
            K("↑", Keysyms.XK_Up, 1f, dark = true, repeat = true),
            G(1.5f),
            K("Del", Keysyms.XK_Delete, 1f, dark = true, tiny = true, repeat = true),
            K("End", Keysyms.XK_End, 1f, dark = true, tiny = true),
            K("PgDn", Keysyms.XK_Page_Down, 1f, dark = true, tiny = true),
            K("Space", ' '.code.toLong(), 1f, dark = true),
            K("⌫", Keysyms.XK_BackSpace, 1f, dark = true, repeat = true)
        ),
        listOf(
            K("←", Keysyms.XK_Left, 1f, dark = true, repeat = true),
            K("↓", Keysyms.XK_Down, 1f, dark = true, repeat = true),
            K("→", Keysyms.XK_Right, 1f, dark = true, repeat = true)
        )
    )

    /** 分类模式·小键盘页（4 列真实布局） */
    fun compactNumpad(): List<List<K>> = listOf(
        listOf(
            K("Num", Keysyms.XK_Num_Lock, 1f, dark = true, tiny = true),
            K("/", Keysyms.XK_KP_Divide, 1f),
            K("*", Keysyms.XK_KP_Multiply, 1f),
            K("−", Keysyms.XK_KP_Subtract, 1f)
        ),
        listOf(
            K("7", Keysyms.XK_KP_7), K("8", Keysyms.XK_KP_8), K("9", Keysyms.XK_KP_9),
            K("+", Keysyms.XK_KP_Add, 1f)
        ),
        listOf(
            K("4", Keysyms.XK_KP_4), K("5", Keysyms.XK_KP_5), K("6", Keysyms.XK_KP_6),
            K("", 0L, 1f, ghost = true)
        ),
        listOf(
            K("1", Keysyms.XK_KP_1), K("2", Keysyms.XK_KP_2), K("3", Keysyms.XK_KP_3),
            K("Ent", Keysyms.XK_KP_Enter, 1f, dark = true, tiny = true)
        ),
        listOf(
            K("0", Keysyms.XK_KP_0, 2f),
            K(".", Keysyms.XK_KP_Decimal, 1f),
            K("", 0L, 1f, ghost = true, ghostDark = true)
        )
    )

    /** 完整模式：23 单位统一网格（所有行权重和恒为 23，列严格对齐） */
    fun main(): List<List<K>> = listOf(
        // 主区 15u | 0.5u 间隙 | 导航/方向 3u | 0.5u 间隙 | 小键盘 4u
        // ---- 行 0：功能行（Esc + F1-F12 分三组 + PrtSc/ScrLk/Pause 对齐导航区）----
        listOf(
            K("Esc", Keysyms.XK_Escape, 1f, dark = true, tiny = true),
            G(0.5f),
            K("F1", Keysyms.XK_F1), K("F2", Keysyms.XK_F2), K("F3", Keysyms.XK_F3), K("F4", Keysyms.XK_F4),
            G(0.5f),
            K("F5", Keysyms.XK_F5), K("F6", Keysyms.XK_F6), K("F7", Keysyms.XK_F7), K("F8", Keysyms.XK_F8),
            G(0.5f),
            K("F9", Keysyms.XK_F9), K("F10", Keysyms.XK_F10), K("F11", Keysyms.XK_F11), K("F12", Keysyms.XK_F12),
            G(1f),
            K("PrtSc", Keysyms.XK_Print, 1f, dark = true, tiny = true),
            K("ScrLk", Keysyms.XK_Scroll_Lock, 1f, dark = true, tiny = true),
            K("Pause", Keysyms.XK_Pause, 1f, dark = true, tiny = true),
            G(4.5f)
        ),
        // ---- 行 1：数字行 + Ins/Home/PgUp + NumLk / * − ----
        listOf(
            K("`", '`'.code.toLong(), 1f, shiftLabel = "~"),
            K("1", '1'.code.toLong(), 1f, shiftLabel = "!"),
            K("2", '2'.code.toLong(), 1f, shiftLabel = "@"),
            K("3", '3'.code.toLong(), 1f, shiftLabel = "#"),
            K("4", '4'.code.toLong(), 1f, shiftLabel = "$"),
            K("5", '5'.code.toLong(), 1f, shiftLabel = "%"),
            K("6", '6'.code.toLong(), 1f, shiftLabel = "^"),
            K("7", '7'.code.toLong(), 1f, shiftLabel = "&"),
            K("8", '8'.code.toLong(), 1f, shiftLabel = "*"),
            K("9", '9'.code.toLong(), 1f, shiftLabel = "("),
            K("0", '0'.code.toLong(), 1f, shiftLabel = ")"),
            K("-", '-'.code.toLong(), 1f, shiftLabel = "_"),
            K("=", '='.code.toLong(), 1f, shiftLabel = "+"),
            K("⌫", Keysyms.XK_BackSpace, 2f, dark = true, repeat = true),
            G(0.5f),
            K("Ins", Keysyms.XK_Insert, 1f, dark = true, tiny = true),
            K("Home", Keysyms.XK_Home, 1f, dark = true, tiny = true),
            K("PgUp", Keysyms.XK_Page_Up, 1f, dark = true, tiny = true),
            G(0.5f),
            K("Num", Keysyms.XK_Num_Lock, 1f, dark = true, tiny = true),
            K("/", Keysyms.XK_KP_Divide, 1f),
            K("*", Keysyms.XK_KP_Multiply, 1f),
            K("−", Keysyms.XK_KP_Subtract, 1f)
        ),
        // ---- 行 2：Tab 行 + Del/End/PgDn + 7 8 9 + ----
        listOf(
            K("Tab", Keysyms.XK_Tab, 1.5f, dark = true, tiny = true),
            K("Q", 'q'.code.toLong()), K("W", 'w'.code.toLong()), K("E", 'e'.code.toLong()), K("R", 'r'.code.toLong()),
            K("T", 't'.code.toLong()), K("Y", 'y'.code.toLong()), K("U", 'u'.code.toLong()), K("I", 'i'.code.toLong()),
            K("O", 'o'.code.toLong()), K("P", 'p'.code.toLong()),
            K("[", '['.code.toLong(), 1f, shiftLabel = "{"),
            K("]", ']'.code.toLong(), 1f, shiftLabel = "}"),
            K("\\", '\\'.code.toLong(), 1.5f, shiftLabel = "|"),
            G(0.5f),
            K("Del", Keysyms.XK_Delete, 1f, dark = true, tiny = true, repeat = true),
            K("End", Keysyms.XK_End, 1f, dark = true, tiny = true),
            K("PgDn", Keysyms.XK_Page_Down, 1f, dark = true, tiny = true),
            G(0.5f),
            K("7", Keysyms.XK_KP_7, 1f), K("8", Keysyms.XK_KP_8, 1f), K("9", Keysyms.XK_KP_9, 1f),
            K("+", Keysyms.XK_KP_Add, 1f)
        ),
        // ---- 行 3：Caps 行 + 导航区空 + 4 5 6 [+向下延伸] ----
        listOf(
            K("Caps", Keysyms.XK_Caps_Lock, 1.75f, dark = true, tiny = true),
            K("A", 'a'.code.toLong()), K("S", 's'.code.toLong()), K("D", 'd'.code.toLong()), K("F", 'f'.code.toLong()),
            K("G", 'g'.code.toLong()), K("H", 'h'.code.toLong()), K("J", 'j'.code.toLong()), K("K", 'k'.code.toLong()),
            K("L", 'l'.code.toLong()),
            K(";", ';'.code.toLong(), 1f, shiftLabel = ":"),
            K("'", '\''.code.toLong(), 1f, shiftLabel = "\""),
            K("⏎", Keysyms.XK_Return, 2.25f, dark = true),
            G(0.5f),
            G(3f),
            G(0.5f),
            K("4", Keysyms.XK_KP_4, 1f), K("5", Keysyms.XK_KP_5, 1f), K("6", Keysyms.XK_KP_6, 1f),
            K("", 0L, 1f, ghost = true)
        ),
        // ---- 行 4：Shift 行 + ↑ + 1 2 3 Ent ----
        listOf(
            K("⇧", Keysyms.XK_Shift_L, 2.25f, mod = Keysyms.XK_Shift_L, dark = true, tiny = true),
            K("Z", 'z'.code.toLong()), K("X", 'x'.code.toLong()), K("C", 'c'.code.toLong()), K("V", 'v'.code.toLong()),
            K("B", 'b'.code.toLong()), K("N", 'n'.code.toLong()), K("M", 'm'.code.toLong()),
            K(",", ','.code.toLong(), 1f, shiftLabel = "<"),
            K(".", '.'.code.toLong(), 1f, shiftLabel = ">"),
            K("/", '/'.code.toLong(), 1f, shiftLabel = "?"),
            K("⇧", Keysyms.XK_Shift_R, 2.75f, mod = Keysyms.XK_Shift_R, dark = true, tiny = true),
            G(0.5f),
            G(1f),
            K("↑", Keysyms.XK_Up, 1f, dark = true, repeat = true),
            G(1f),
            G(0.5f),
            K("1", Keysyms.XK_KP_1, 1f), K("2", Keysyms.XK_KP_2, 1f), K("3", Keysyms.XK_KP_3, 1f),
            K("Ent", Keysyms.XK_KP_Enter, 1f, dark = true, tiny = true)
        ),
        // ---- 行 5：底行 + ← ↓ → + 0(2u) . [Ent 延伸] ----
        listOf(
            K("Ctrl", Keysyms.XK_Control_L, 1.25f, mod = Keysyms.XK_Control_L, dark = true, tiny = true),
            K("Win", Keysyms.XK_Super_L, 1.25f, mod = Keysyms.XK_Super_L, dark = true, tiny = true),
            K("Alt", Keysyms.XK_Alt_L, 1.25f, mod = Keysyms.XK_Alt_L, dark = true, tiny = true),
            K("空格", ' '.code.toLong(), 6.25f),
            K("AltGr", Keysyms.XK_ISO_Level3_Shift, 1.25f, mod = Keysyms.XK_ISO_Level3_Shift, dark = true, tiny = true),
            K("Win", Keysyms.XK_Super_R, 1.25f, mod = Keysyms.XK_Super_R, dark = true, tiny = true),
            K("Menu", Keysyms.XK_Menu, 1.25f, dark = true, tiny = true),
            K("Ctrl", Keysyms.XK_Control_R, 1.25f, mod = Keysyms.XK_Control_R, dark = true, tiny = true),
            G(0.5f),
            K("←", Keysyms.XK_Left, 1f, dark = true, repeat = true),
            K("↓", Keysyms.XK_Down, 1f, dark = true, repeat = true),
            K("→", Keysyms.XK_Right, 1f, dark = true, repeat = true),
            G(0.5f),
            K("0", Keysyms.XK_KP_0, 2f),
            K(".", Keysyms.XK_KP_Decimal, 1f),
            K("", 0L, 1f, ghost = true, ghostDark = true)
        )
    )

    fun media(): List<List<K>> = listOf(
        listOf(
            K("🔇", Keysyms.XK_XF86AudioMute, 1.6f, dark = true),
            K("Vol−", Keysyms.XK_XF86AudioLowerVolume, 1.6f, dark = true, tiny = true),
            K("Vol+", Keysyms.XK_XF86AudioRaiseVolume, 1.6f, dark = true, tiny = true),
            K("⏮", Keysyms.XK_XF86AudioPrev, 1.6f, dark = true),
            K("⏯", Keysyms.XK_XF86AudioPlay, 1.6f, dark = true),
            K("⏭", Keysyms.XK_XF86AudioNext, 1.6f, dark = true),
            K("⏹", Keysyms.XK_XF86AudioStop, 1.6f, dark = true)
        ),
        listOf(
            K("主页", Keysyms.XK_XF86HomePage, 1.6f, dark = true, tiny = true),
            K("邮件", Keysyms.XK_XF86Mail, 1.6f, dark = true, tiny = true),
            K("搜索", Keysyms.XK_XF86Search, 1.6f, dark = true, tiny = true),
            K("计算器", Keysyms.XK_XF86Calculator, 1.9f, dark = true, tiny = true),
            K("⏻", Keysyms.XK_XF86PowerOff, 1.6f, dark = true),
            K("⏾", Keysyms.XK_XF86WakeUp, 1.6f, dark = true),
            K("⏏", Keysyms.XK_XF86Eject, 1.6f, dark = true)
        ),
        listOf(
            K("F13", Keysyms.XK_F13), K("F14", Keysyms.XK_F14), K("F15", Keysyms.XK_F15), K("F16", Keysyms.XK_F16),
            K("F17", Keysyms.XK_F17), K("F18", Keysyms.XK_F18)
        ),
        listOf(
            K("F19", Keysyms.XK_F19), K("F20", Keysyms.XK_F20), K("F21", Keysyms.XK_F21), K("F22", Keysyms.XK_F22),
            K("F23", Keysyms.XK_F23), K("F24", Keysyms.XK_F24)
        )
    )

    fun extra(): List<List<K>> = listOf(
        listOf(
            K("SysRq", Keysyms.XK_Sys_Req, 1.5f, dark = true, tiny = true),
            K("Break", Keysyms.XK_Break, 1.5f, dark = true, tiny = true),
            K("Compose", Keysyms.XK_Multi_key, 1.7f, dark = true, tiny = true),
            K("Linefeed", Keysyms.XK_Linefeed, 1.7f, dark = true, tiny = true),
            K("Clear", Keysyms.XK_Clear, 1.5f, dark = true, tiny = true),
            K("Select", Keysyms.XK_Select, 1.5f, dark = true, tiny = true)
        ),
        listOf(
            K("Execute", Keysyms.XK_Execute, 1.5f, dark = true, tiny = true),
            K("Undo", Keysyms.XK_Undo, 1.5f, dark = true, tiny = true),
            K("Redo", Keysyms.XK_Redo, 1.5f, dark = true, tiny = true),
            K("Find", Keysyms.XK_Find, 1.5f, dark = true, tiny = true),
            K("Help", Keysyms.XK_Help, 1.5f, dark = true, tiny = true),
            K("Cancel", Keysyms.XK_Cancel, 1.5f, dark = true, tiny = true)
        ),
        listOf(
            K("Begin", Keysyms.XK_Begin, 1.5f, dark = true, tiny = true),
            K("ShiftLk", Keysyms.XK_Shift_Lock, 1.6f, dark = true, tiny = true),
            K("全角", Keysyms.XK_Zenkaku, 1.5f, dark = true, tiny = true),
            K("半角", Keysyms.XK_Hankaku, 1.5f, dark = true, tiny = true),
            K("変換", Keysyms.XK_Henkan_Mode, 1.5f, dark = true, tiny = true),
            K("無変換", Keysyms.XK_Muhenkan, 1.6f, dark = true, tiny = true)
        ),
        listOf(
            K("한글", Keysyms.XK_Hangul, 1.5f, dark = true, tiny = true),
            K("한자", Keysyms.XK_Hangul_Hanja, 1.5f, dark = true, tiny = true),
            K("KP=", Keysyms.XK_KP_Equal, 1.5f, dark = true, tiny = true),
            K("KP空格", Keysyms.XK_KP_Space, 1.6f, dark = true, tiny = true),
            K("KP,", Keysyms.XK_KP_Separator, 1.5f, dark = true, tiny = true),
            K("KP Tab", Keysyms.XK_KP_Tab, 1.5f, dark = true, tiny = true)
        )
    )

    /** NumLock 关闭时数字小键盘 → 导航语义 */
    fun numLockOffKey(k: K): K {
        val remap = mapOf(
            Keysyms.XK_KP_0 to Keysyms.XK_KP_Insert,
            Keysyms.XK_KP_1 to Keysyms.XK_KP_End,
            Keysyms.XK_KP_2 to Keysyms.XK_KP_Down,
            Keysyms.XK_KP_3 to Keysyms.XK_KP_Page_Down,
            Keysyms.XK_KP_4 to Keysyms.XK_KP_Left,
            Keysyms.XK_KP_5 to Keysyms.XK_KP_Begin,
            Keysyms.XK_KP_6 to Keysyms.XK_KP_Right,
            Keysyms.XK_KP_7 to Keysyms.XK_KP_Home,
            Keysyms.XK_KP_8 to Keysyms.XK_KP_Up,
            Keysyms.XK_KP_9 to Keysyms.XK_KP_Page_Up,
            Keysyms.XK_KP_Decimal to Keysyms.XK_KP_Delete
        )
        val sym = remap[k.keysym] ?: return k
        val label = mapOf(
            Keysyms.XK_KP_Insert to "Ins", Keysyms.XK_KP_End to "End", Keysyms.XK_KP_Down to "↓",
            Keysyms.XK_KP_Page_Down to "PgDn", Keysyms.XK_KP_Left to "←", Keysyms.XK_KP_Begin to "5",
            Keysyms.XK_KP_Right to "→", Keysyms.XK_KP_Home to "Home", Keysyms.XK_KP_Up to "↑",
            Keysyms.XK_KP_Page_Up to "PgUp", Keysyms.XK_KP_Delete to "Del"
        )[sym] ?: k.label
        return K(label, sym, k.weight, dark = true, tiny = true, repeat = sym == Keysyms.XK_KP_Down ||
                sym == Keysyms.XK_KP_Left || sym == Keysyms.XK_KP_Right || sym == Keysyms.XK_KP_Up)
    }
}

// =====================================================================
// 悬浮完整键盘（会话内使用）
// =====================================================================

/** 修饰键粘滞状态机：单次粘滞 / 双击锁定
 *  【v2.7.2 修复】旧逻辑双击 = 粘滞→取消（第二次 tap 直接 remove），「双击锁定」永远
 *  触发不了（用户需三击才锁定）。新三态循环：未粘滞→粘滞(蓝)→锁定(青)→解除。 */
private class ModLatches {
    val latched = LinkedHashMap<Long, Boolean>()   // keysym -> locked
    private val lastTap = HashMap<Long, Long>()

    fun tap(mod: Long): Boolean {
        val now = System.currentTimeMillis()
        val dbl = lastTap[mod]?.let { now - it < 380 } == true
        lastTap[mod] = now
        return when {
            !latched.containsKey(mod) -> { latched[mod] = dbl; true }   // 粘滞（快速二连击直接锁定）
            latched[mod] == false && dbl -> { latched[mod] = true; true } // 快速二连击 → 锁定
            else -> { latched.remove(mod); false }                       // 慢速再点取消 / 已锁定解除
        }
    }

    fun isLatched(mod: Long) = latched.containsKey(mod)
    fun isLocked(mod: Long) = latched[mod] == true

    /** 取消全部未锁定的粘滞 */
    fun clearUnlocked() {
        latched.entries.removeAll { !it.value }
    }

    fun reset() = latched.clear()

    /** 当前粘滞的修饰键（规范顺序） */
    fun chordPrefix(): List<Long> {
        val order = listOf(Keysyms.XK_Control_L, Keysyms.XK_Control_R, Keysyms.XK_Alt_L, Keysyms.XK_Alt_R,
            Keysyms.XK_Super_L, Keysyms.XK_Super_R, Keysyms.XK_Shift_L, Keysyms.XK_Shift_R,
            Keysyms.XK_ISO_Level3_Shift)
        return order.filter { latched.containsKey(it) } + latched.keys.filter { it !in order }
    }
}

@Composable
fun CompleteKeyboard(
    session: SelkiesSession?,
    visible: Boolean,
    autoScaleHint: Float = 1f,
    savedScale: Float = 0f,
    savedFullScale: Float = 0f,
    savedFullMode: Boolean = false,
    onScaleChange: (Float) -> Unit = {},
    onFullModeChange: (Boolean) -> Unit = {},
    onDismiss: () -> Unit,
    onChord: ((List<Long>) -> Unit)? = null,
    // v2.7.2：文本合成条焦点回调（会话层据此放行物理键直通远程——键盘打开但无文本框聚焦时
    // 桌面物理键盘不再被完全屏蔽，修复「键盘开着物理键打不出字」）
    onTextFocusChanged: (Boolean) -> Unit = {}
) {
    if (!visible) return

    var fullMode by remember { mutableStateOf(savedFullMode) }
    var tab by remember(fullMode) { mutableStateOf(if (fullMode) KbTab.FULL else KbTab.MAIN) }
    // v2.6.0+ 诊断：键盘打开瞬间的真实状态（仅首次组合打印一次，避免重组刷屏）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        println("[Selkies][键盘] 打开: fullMode=$fullMode tab=$tab savedFullScale=$savedFullScale autoHint=${"%.2f".format(autoScaleHint)} savedScale=$savedScale")
    }
    val latches = remember { ModLatches() }
    var numLock by remember { mutableStateOf(true) }
    var collapsed by remember { mutableStateOf(false) }
    var textBarVisible by remember { mutableStateOf(false) }
    var composeText by remember { mutableStateOf("") }
    // 缩放：分类模式 0.55–1.6（作用于自动键高），完整模式 0.3–2.0（作用于单位宽度）
    // 【v2.6.0 修复】两种模式各自持久化（keyboardScale / keyboardFullScale）：
    //   旧版共用一个值——在完整模式缩小后切回分类模式键盘变微型，反之亦然。
    // 【v2.7.6 修复】完整模式默认 = 宽度恰好适配容器（fitFullWidth）。
    //   v2.7.4 的 max(autoScaleHint, 0.7) 在窄容器（竖屏手机 ~360dp / 快捷键管理页 ~340dp）上
    //   把 23 单位键盘撑到 ~550dp > 容器宽 → 右侧 ~40%（F8-F12 / I O P / 回车 / 退格）被裁掉
    //   = 用户「键盘布局有问题」（截图实证：键盘只显示到 U/J/N）。键偏小可拉滑杆放大
    //   （超宽部分横向滚动可达），但默认必须「全部键可见」。
    var scale by remember(savedScale, savedFullScale, fullMode) {
        mutableStateOf(
            if (fullMode) {
                if (savedFullScale > 0f) savedFullScale else autoScaleHint.coerceIn(0.3f, 1.2f)
            } else {
                if (savedScale > 0f) savedScale else 1f
            }
        )
    }
    // 【v2.7.6】用户手动调整过（滑杆/-/+/捏合）→ 尺寸就绪后不再自动纠正，尊重用户选择
    var userAdjustedScale by remember(fullMode) { mutableStateOf(false) }
    var repaint by remember { mutableStateOf(0) }   // 触发修饰键高亮重绘

    var offset by remember { mutableStateOf(IntOffset.Zero) }
    var parentSize by remember { mutableStateOf(IntSize.Zero) }
    var kbSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    var kbWidthDp by remember { mutableStateOf(0f) }

    // 【v2.7.1 修复·完整键盘显示不完整】根因：完整模式 6 行网格在横屏矮屏/用户放大过的 scale 下
    // 总高度超过屏幕高度，BottomCenter 对齐使顶部行被推出屏幕外裁掉（只能看到下面几行）。
    // 修复三件套：
    //   ① fitFullScale()：高度方向能容纳全部 6 行的最大 scale（chrome=头部行+内边距保守 66dp）
    //   ② 打开/切到完整模式时自动把 scale clamp 到 fit（打开即完整可见；用户仍可手动放大，配合③滚动查看）
    //   ③ 键区加 verticalScroll + Surface 高度上限——即使内容超高也不再裁切，可上下滚动
    //【v2.7.5 修复·竖屏键盘右半被裁】用户截图实证：手机竖屏（宽 ~360dp）下 23 单位宽网格
    //   在 scale=0.7 时宽 547dp > 屏宽 → 右侧（回车/Shift/小键盘）被裁掉；旧版仅按高度适配的
    //   fitFullScale 在竖屏返回 2.0 → clamp 完全失效。现在同时适配宽度：
    //   fit = min(高度适配, 宽度适配)，宽度适配 = (屏宽-8)/782（23 单位 × 34dp + 8dp）。
    //   竖屏自动缩到全键盘可见（~0.45），放大后配合横向滚动仍可达全部按键。
    fun fitFullScale(): Float {
        if (parentSize == IntSize.Zero) return 2f
        val hDp = with(density) { parentSize.height.toDp().value }
        val wDp = with(density) { parentSize.width.toDp().value }
        // 完整模式 6 行键 + 5 间隙 ≈ (6×34 + 5×2.8)×scale ≈ 218×scale；头部行/内边距保守 66dp
        val availH = hDp * 0.92f - 66f
        val heightFit = if (availH <= 60f) 0.3f else (availH / 218f)
        // v2.7.5：宽度方向——23 单位 × 34dp + 8dp = 782dp 满宽
        val widthFit = ((wDp - 8f) / 782f).coerceAtLeast(0.3f)
        return minOf(heightFit, widthFit).coerceIn(0.3f, 2f)
    }

    /** 【v2.7.6】宽度适配：让 23 单位（23×34=782dp@scale1）键盘恰好放进容器宽度——全部键可见。
     *  边距精确核算（GUI 实测校准）：Surface 内层 Column padding 3dp×2 + 键区 Column padding 2dp×2 + 余量 → -20dp */
    fun fitFullWidthScale(): Float {
        if (kbWidthDp <= 0f) return 0f
        return ((kbWidthDp - 20f) / 782f).coerceIn(0.3f, 2f)
    }

    fun doScale(f: Float) {
        userAdjustedScale = true
        scale = (scale * f).coerceIn(if (fullMode) 0.3f else 0.55f, if (fullMode) 2.0f else 1.6f)
        onScaleChange(scale)
    }

    // 【v2.7.1】打开键盘即处于完整模式（savedFullMode）或父容器尺寸就绪时：
    // 同样把 scale clamp 到高度适配值——保证「打开即 6 行全部可见」，不再出现顶部行被裁。
    LaunchedEffect(fullMode, parentSize) {
        if (fullMode && parentSize != IntSize.Zero) {
            // 【v2.7.6】无保存偏好且未手动调整：宽度适配优先（右半键盘可见性 > 键大小）。
            //   拿到真实容器宽后一次性纠正；不调 onScaleChange（默认值推导，非用户意图，不写盘）。
            if (!userAdjustedScale && savedFullScale <= 0f) {
                val fitW = fitFullWidthScale()
                if (fitW > 0f) {
                    val target = minOf(fitW, fitFullScale())
                    if (kotlin.math.abs(scale - target) > 0.01f) scale = target
                }
            } else {
                val fit = fitFullScale()
                if (scale > fit) {
                    scale = fit
                    onScaleChange(fit)
                }
            }
        }
    }

    /** 发送普通键（含粘滞修饰键组合） */
    fun pressNormal(k: K) {
        if (k.ghost || k.keysym == 0L) return
        val key = if (!numLock && (tab == KbTab.MAIN || tab == KbTab.FULL || tab == KbTab.NUMPAD)) KbLayout.numLockOffKey(k) else k
        val mods = latches.chordPrefix()
        if (onChord != null) {
            onChord(mods + key.keysym)
        } else if (mods.isNotEmpty()) {
            session?.sendKeyChord(mods + key.keysym)
        } else if (session != null) {
            session.sendKey(key.keysym, true)
            session.sendKey(key.keysym, false)
        }
        latches.clearUnlocked()
        repaint++
    }

    /** 文本合成条：输入法等效（co,end 协议提交文本） */
    fun commitText() {
        if (composeText.isNotEmpty() && session != null) {
            session.typeText(composeText)
        }
        composeText = ""
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged {
                parentSize = it
                kbWidthDp = with(density) { it.width.toDp().value }
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        if (collapsed) {
            // 折叠悬浮球
            Box(
                modifier = Modifier
                    .padding(bottom = 18.dp)
                    .offset { offset }
                    .background(Color(0xEE1D2530), CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { _, drag ->
                            offset = clampKbOffset(
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
                        Icons.Filled.KeyboardCapslock, "展开完整键盘",
                        tint = Color(0xFF7DD3FC), modifier = Modifier.size(20.dp)
                    )
                    Text("键盘", color = Color(0xFFE2E8F0), fontSize = 13.sp)
                }
            }
        } else {
            // ---- 键高：分类模式按宽度自适应（指尖尺寸），完整模式按缩放单位 ----
            val compactKeyH = if (kbWidthDp > 0) (kbWidthDp / 13.6f * 1.04f * scale) else 34f
            val keyH = if (fullMode) (34f * scale).coerceAtLeast(14f)
            else compactKeyH.coerceIn(24f, 56f)
            val gap = (keyH * 0.08f).coerceAtLeast(1.4f).dp

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    // 【v2.7.1】高度上限 = 父容器 92%：完整模式内容再高也不会把顶部行推出屏幕
                    .heightIn(max = if (parentSize != IntSize.Zero)
                        with(density) { (parentSize.height * 0.92f).toInt().toDp() } else Dp.Unspecified
                    )
                    .padding(horizontal = if (fullMode) 0.dp else 3.dp, vertical = 4.dp)
                    .offset { offset }
                    .onSizeChanged { kbSize = it }
                    // 双指捏合缩放
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            if (zoom != 1f) {
                                val ns = (scale * zoom).coerceIn(if (fullMode) 0.3f else 0.55f, if (fullMode) 2.0f else 1.6f)
                                if (kotlin.math.abs(ns - scale) > 0.004f) {
                                    userAdjustedScale = true   // v2.7.6：捏合=手动调整
                                    scale = ns
                                    onScaleChange(scale)
                                }
                            }
                        }
                    },
                shape = RoundedCornerShape(12.dp),
                color = Color(0xF0161B22),
                // v2.7.0: shadowElevation 移除——SOFTWARE 渲染内容丢失 bug
            ) {
                Column(modifier = Modifier.padding(3.dp)) {
                    // ---------- 拖动头 + 分类页签（可横向滑动） + 模式/缩放控制 ----------
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(26.dp)
                                .pointerInput(Unit) {
                                    detectDragGestures { _, drag ->
                                        offset = clampKbOffset(
                                            offset + IntOffset(drag.x.roundToInt(), drag.y.roundToInt()),
                                            parentSize, kbSize
                                        )
                                    }
                                },
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .width(36.dp)
                                        .height(4.dp)
                                        .background(Color(0x66FFFFFF), RoundedCornerShape(2.dp))
                                )
                                Text(
                                    (if (fullMode) "完整 151 键" else "分类") + " · ${(scale * 100).toInt()}%",
                                    color = Color(0x99FFFFFF), fontSize = 9.sp
                                )
                            }
                        }
                        // 页签（横向滑动，修「键盘太多」：默认分类只显示当前类的大按键）
                        Row(
                            modifier = Modifier
                                .weight(2.2f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (fullMode) {
                                KbChip("完整", active = tab == KbTab.FULL) { tab = KbTab.FULL }
                                KbChip("媒体", active = tab == KbTab.MEDIA) { tab = KbTab.MEDIA }
                                KbChip("扩展", active = tab == KbTab.EXTRA) { tab = KbTab.EXTRA }
                                KbChip("文本", active = textBarVisible) { textBarVisible = !textBarVisible }
                            } else {
                                KbChip("常用", active = tab == KbTab.MAIN) { tab = KbTab.MAIN }
                                KbChip("功能", active = tab == KbTab.FUNC) { tab = KbTab.FUNC }
                                KbChip("导航", active = tab == KbTab.NAV) { tab = KbTab.NAV }
                                KbChip("小键盘", active = tab == KbTab.NUMPAD) { tab = KbTab.NUMPAD }
                                KbChip("媒体", active = tab == KbTab.MEDIA) { tab = KbTab.MEDIA }
                                KbChip("扩展", active = tab == KbTab.EXTRA) { tab = KbTab.EXTRA }
                                KbChip("文本", active = textBarVisible) { textBarVisible = !textBarVisible }
                            }
                        }
                        Spacer(Modifier.width(3.dp))
                        // 完整/分类 模式切换（用户要求：自动分类 + 可选完整模式）
                        KbChip(
                            if (fullMode) "分类" else "完整",
                            active = fullMode,
                            accent = true
                        ) {
                            fullMode = !fullMode
                            tab = if (fullMode) KbTab.FULL else KbTab.MAIN
                            // 【v2.7.1】切到完整模式：scale 超出高度适配值时自动降到 fit（打开即 6 行全可见）
                            if (fullMode) {
                                val fitW = fitFullWidthScale()
                                if (!userAdjustedScale && savedFullScale <= 0f && fitW > 0f) {
                                    scale = minOf(fitW, fitFullScale())   // v2.7.6：宽度适配默认
                                } else {
                                    val fit = fitFullScale()
                                    if (scale > fit) { scale = fit; onScaleChange(fit) }
                                }
                            }
                            onFullModeChange(fullMode)
                        }
                        KbIcon(Icons.Filled.Remove, "缩小") { doScale(1f / 1.15f) }
                        KbIcon(Icons.Filled.Add, "放大") { doScale(1.15f) }
                        KbIcon(Icons.Filled.KeyboardArrowDown, "折叠") { collapsed = true }
                        KbIcon(Icons.Filled.Close, "关闭") { onDismiss() }
                    }

                    // v2.7.4：缩放滑杆（直观调整键盘大小——用户要求「支持调整大小」）。
                    // 拖动中只改本地 scale（不写盘——updateSettings 每次都会落盘，
                    // 高频拖动会狂写磁盘），松手时才持久化。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Remove, null, tint = Color(0x88FFFFFF), modifier = Modifier.size(12.dp))
                        androidx.compose.material3.Slider(
                            value = scale,
                            onValueChange = {
                                userAdjustedScale = true   // v2.7.6：手动调整后不再自动纠正
                                scale = it.coerceIn(if (fullMode) 0.3f else 0.55f, if (fullMode) 2.0f else 1.6f)
                            },
                            onValueChangeFinished = { onScaleChange(scale) },
                            valueRange = (if (fullMode) 0.3f else 0.55f)..(if (fullMode) 2.0f else 1.6f),
                            modifier = Modifier.weight(1f).height(18.dp),
                            colors = androidx.compose.material3.SliderDefaults.colors(
                                thumbColor = Color(0xFF60A5FA),
                                activeTrackColor = Color(0xFF60A5FA),
                                inactiveTrackColor = Color(0x33FFFFFF)
                            )
                        )
                        Icon(Icons.Filled.Add, null, tint = Color(0x88FFFFFF), modifier = Modifier.size(12.dp))
                    }

                    // ---------- 文本合成条（输入法等效；文本页签=大号版）----------
                    if (textBarVisible || tab == KbTab.TEXT) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 3.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .background(Color(0xFF2A323D), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (composeText.isEmpty()) {
                                    Text("输入文本（可调用系统输入法/中文）", color = Color(0x66FFFFFF), fontSize = 10.sp)
                                }
                                BasicTextField(
                                    value = composeText,
                                    onValueChange = { composeText = it },
                                    textStyle = TextStyle(color = Color(0xFFE8EDF4), fontSize = 14.sp),
                                    cursorBrush = SolidColor(Color(0xFF60A5FA)),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { onTextFocusChanged(it.isFocused) }
                                )
                            }
                            KbChip("发送 ⏎", active = true) { commitText() }
                            KbChip("⌫") {
                                if (composeText.isNotEmpty()) composeText = composeText.dropLast(1)
                                else if (session != null) {
                                    session.sendKey(Keysyms.XK_BackSpace, true)
                                    session.sendKey(Keysyms.XK_BackSpace, false)
                                }
                            }
                            KbChip("清空") { composeText = "" }
                        }
                        if (tab == KbTab.TEXT) {
                            // 文本页：大号快捷键 + 说明
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                KbChip("回车") {
                                    session?.sendKey(Keysyms.XK_Return, true)
                                    session?.sendKey(Keysyms.XK_Return, false)
                                }
                                KbChip("Tab") {
                                    session?.sendKey(Keysyms.XK_Tab, true)
                                    session?.sendKey(Keysyms.XK_Tab, false)
                                }
                                KbChip("Esc") {
                                    session?.sendKey(Keysyms.XK_Escape, true)
                                    session?.sendKey(Keysyms.XK_Escape, false)
                                }
                                KbChip("Ctrl+C 复制") { session?.sendKeyChord(listOf(Keysyms.XK_Control_L, 'c'.code.toLong())) }
                                KbChip("Ctrl+V 粘贴") { session?.sendKeyChord(listOf(Keysyms.XK_Control_L, 'v'.code.toLong())) }
                            }
                        }
                    }

                    // ---------- 键区 ----------
                    val rows: List<List<K>> = when (tab) {
                        KbTab.MAIN -> KbLayout.compactMain()
                        KbTab.FUNC -> KbLayout.compactFunc()
                        KbTab.NAV -> KbLayout.compactNav()
                        KbTab.NUMPAD -> KbLayout.compactNumpad()
                        KbTab.MEDIA -> KbLayout.media()
                        KbTab.EXTRA -> KbLayout.extra()
                        KbTab.TEXT -> KbLayout.compactMain()   // 文本页下方保留打字区
                        KbTab.FULL -> KbLayout.main()
                    }
                    // 读取 repaint：修饰键粘滞状态变化时触发键区重绘
                    val rp = repaint

                    val keyArea: @Composable () -> Unit = {
                        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                            rows.forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Start,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    row.forEach { k ->
                                        KbKey(
                                            k = k,
                                            keyH = keyH,
                                            gap = gap,
                                            latched = k.mod != 0L && latches.isLatched(k.mod),
                                            locked = k.mod != 0L && latches.isLocked(k.mod),
                                            showShift = latches.isLatched(Keysyms.XK_Shift_L) || latches.isLatched(Keysyms.XK_Shift_R),
                                            onClick = {
                                                if (k.ghost) return@KbKey
                                                if (k.mod != 0L) {
                                                    latches.tap(k.mod)
                                                    repaint++
                                                } else if (k.keysym == Keysyms.XK_Num_Lock) {
                                                    numLock = !numLock
                                                } else {
                                                    pressNormal(k)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (fullMode && tab == KbTab.FULL) {
                        // 完整模式：真实单位宽度（可超出屏幕，横向滚动查看全键盘）
                        // 【v2.7.1】+ 纵向滚动：scale 放大后 6 行超出可见高度时不再裁切，上下滚动可达全部按键
                        val unitDp = (34f * scale).coerceAtLeast(12f)
                        val gridWidthDp = (23f * unitDp + 8f)
                        val screenDp = kbWidthDp
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .verticalScroll(rememberScrollState())
                        ) {
                            Column(
                                modifier = Modifier
                                    .width(with(density) { gridWidthDp.coerceAtLeast(screenDp).toDp() })
                                    .padding(horizontal = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(gap)
                            ) {
                                rows.forEachIndexed { ri, row ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Start,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        row.forEach { k ->
                                            FixedKbKey(
                                                k = k,
                                                unit = unitDp,
                                                keyH = keyH,
                                                gap = gap,
                                                latched = k.mod != 0L && latches.isLatched(k.mod),
                                                locked = k.mod != 0L && latches.isLocked(k.mod),
                                                showShift = latches.isLatched(Keysyms.XK_Shift_L) || latches.isLatched(Keysyms.XK_Shift_R),
                                                numLock = numLock,
                                                onClick = {
                                                    if (k.ghost) return@FixedKbKey
                                                    if (k.mod != 0L) {
                                                        latches.tap(k.mod)
                                                        repaint++
                                                    } else if (k.keysym == Keysyms.XK_Num_Lock) {
                                                        numLock = !numLock
                                                    } else {
                                                        pressNormal(k)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        keyArea()
                    }
                }
            }
        }
    }
}

private fun clampKbOffset(off: IntOffset, parent: IntSize, kb: IntSize): IntOffset {
    if (parent == IntSize.Zero) return off
    val maxX = ((parent.width - 40) / 2).coerceAtLeast(0)
    val minX = -maxX
    val maxY = 0
    val minY = -(parent.height - 60).coerceAtLeast(0)
    return IntOffset(off.x.coerceIn(minX, maxX), off.y.coerceIn(minY, maxY))
}

// =====================================================================
// 单键渲染
// =====================================================================

/** 权重键（自适应宽度——分类模式） */
@Composable
private fun androidx.compose.foundation.layout.RowScope.KbKey(
    k: K,
    keyH: Float,
    gap: androidx.compose.ui.unit.Dp,
    latched: Boolean,
    locked: Boolean,
    showShift: Boolean,
    onClick: () -> Unit
) {
    val gapHalf = (keyH * 0.05f).coerceAtLeast(0.5f).dp
    if (k.gap) {
        Box(Modifier.weight(k.weight).height(keyH.dp))
        return
    }
    if (k.ghost) {
        Box(
            Modifier
                .weight(k.weight)
                .height(keyH.dp + gap)
                .offset(y = -gap)
                .padding(horizontal = gapHalf)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(keyH.dp + gap)
                    .background(
                        if (k.ghostDark) Color(0xFF39424E) else Color(0xFF2A323D),
                        RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp)
                    )
            )
        }
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // 按下即触发；长按重复
    LaunchedEffect(pressed) {
        if (pressed) {
            onClick()
            if (k.repeat) {
                var n = 0
                while (true) {
                    delay(if (n == 0) 400 else 60)
                    onClick()
                    n++
                }
            }
        }
    }

    val base = when {
        latched -> if (locked) Color(0xFF22D3A5) else Color(0xFF3B82F6)
        else -> if (k.dark) Color(0xFF39424E) else Color(0xFF2A323D)
    }
    val label = when {
        k.shiftLabel != null && showShift -> k.shiftLabel
        else -> k.label
    }
    Box(
        modifier = Modifier
            .weight(k.weight)
            .height(keyH.dp)
            .padding(horizontal = gapHalf)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(keyH.dp)
                .background(if (pressed) Color(0xFF60A5FA) else base, RoundedCornerShape(6.dp))
                .clickable(interactionSource = interaction, indication = null) {},
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = if (latched) Color(0xFF06131F) else Color(0xFFE8EDF4),
                fontSize = (if (k.tiny) 10.5f else 15.5f).coerceAtMost(keyH * 0.42f).coerceAtLeast(7f).sp,
                maxLines = 1
            )
        }
    }
}

/** 固定单位宽度键（完整模式：真实键盘比例，横向滚动） */
@Composable
private fun androidx.compose.foundation.layout.RowScope.FixedKbKey(
    k: K,
    unit: Float,
    keyH: Float,
    gap: androidx.compose.ui.unit.Dp,
    latched: Boolean,
    locked: Boolean,
    showShift: Boolean,
    numLock: Boolean,
    onClick: () -> Unit
) {
    val key = if (!numLock) KbLayout.numLockOffKey(k) else k
    val gapHalf = (unit * 0.06f).coerceAtLeast(0.5f).dp
    if (key.gap) {
        Spacer(Modifier.width((unit * key.weight).dp).height(keyH.dp))
        return
    }
    if (key.ghost) {
        Box(
            Modifier
                .width((unit * key.weight).dp)
                .height(keyH.dp + gap)
                .offset(y = -gap)
                .padding(horizontal = gapHalf)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(keyH.dp + gap)
                    .background(
                        if (key.ghostDark) Color(0xFF39424E) else Color(0xFF2A323D),
                        RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp)
                    )
            )
        }
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    LaunchedEffect(pressed) {
        if (pressed) {
            onClick()
            if (key.repeat) {
                var n = 0
                while (true) {
                    delay(if (n == 0) 400 else 60)
                    onClick()
                    n++
                }
            }
        }
    }

    val base = when {
        latched -> if (locked) Color(0xFF22D3A5) else Color(0xFF3B82F6)
        else -> if (key.dark) Color(0xFF39424E) else Color(0xFF2A323D)
    }
    val label = when {
        key.shiftLabel != null && showShift -> key.shiftLabel
        else -> key.label
    }
    Box(
        modifier = Modifier
            .width((unit * key.weight).dp)
            .height(keyH.dp)
            .padding(horizontal = gapHalf)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(keyH.dp)
                .background(if (pressed) Color(0xFF60A5FA) else base, RoundedCornerShape(6.dp))
                .clickable(interactionSource = interaction, indication = null) {},
            contentAlignment = Alignment.Center
        ) {
            // 【v2.7.2 根因修复】旧公式 14f * unit（unit=34×scale≈14~34dp）→ 字号 200~476sp
            // 塞进 14~34dp 高的键 → 字形只剩顶部空隙被裁掉 = 完整模式字母键全部空白
            // （用户截图片段：仅 tiny 键 Esc/Tab/Caps/Ctrl 有字，字母/数字/功能键全空 =
            //  「软键盘无法使用」根因；该 bug 自 v2.3.0 引入）。新公式随键高自适应，
            // 与分类模式 KbKey 同思路：字号 = min(基准, 键高×系数)，下限 6.5sp。
            Text(
                label,
                color = if (latched) Color(0xFF06131F) else Color(0xFFE8EDF4),
                fontSize = (if (key.tiny) 10f else 15.5f)
                    .coerceAtMost(keyH * (if (key.tiny) 0.65f else 0.5f))
                    .coerceAtLeast(6.5f).sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun KbChip(label: String, active: Boolean = false, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                when {
                    accent && active -> Color(0xFF22D3A5)
                    active -> Color(0xFF3B82F6)
                    else -> Color(0x33FFFFFF)
                },
                RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(label, color = if (accent && active) Color(0xFF06131F) else Color(0xFFE2E8F0), fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun KbIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(Color(0x33FFFFFF), RoundedCornerShape(6.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, tint = Color(0xFFE2E8F0), modifier = Modifier.size(14.dp))
    }
}
