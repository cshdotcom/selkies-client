package com.selkies.client.core

/**
 * X11 keysym 常量与查找（与 Selkies 官方前端一致）
 */
object Keysyms {
    // ---- 修饰键 ----
    const val XK_Shift_L = 0xffe1L
    const val XK_Shift_R = 0xffe2L
    const val XK_Control_L = 0xffe3L
    const val XK_Control_R = 0xffe4L
    const val XK_Caps_Lock = 0xffe5L
    const val XK_Shift_Lock = 0xffe6L
    const val XK_Meta_L = 0xffe7L
    const val XK_Meta_R = 0xffe8L
    const val XK_Alt_L = 0xffe9L
    const val XK_Alt_R = 0xffeaL
    const val XK_Super_L = 0xffebL
    const val XK_Super_R = 0xffecL
    const val XK_Hyper_L = 0xffedL
    const val XK_Hyper_R = 0xffeeL

    // ---- 功能/编辑键 ----
    const val XK_BackSpace = 0xff08L
    const val XK_Tab = 0xff09L
    const val XK_Linefeed = 0xff0aL
    const val XK_Clear = 0xff0bL
    const val XK_Return = 0xff0dL
    const val XK_Pause = 0xff13L
    const val XK_Scroll_Lock = 0xff14L
    const val XK_Num_Lock = 0xff7fL
    const val XK_Sys_Req = 0xff15L
    const val XK_Escape = 0xff1bL
    const val XK_Delete = 0xffffL
    const val XK_Home = 0xff50L
    const val XK_Left = 0xff51L
    const val XK_Up = 0xff52L
    const val XK_Right = 0xff53L
    const val XK_Down = 0xff54L
    const val XK_Page_Up = 0xff55L
    const val XK_Page_Down = 0xff56L
    const val XK_End = 0xff57L
    const val XK_Begin = 0xff58L
    const val XK_Select = 0xff60L
    const val XK_Print = 0xff61L
    const val XK_Execute = 0xff62L
    const val XK_Insert = 0xff63L
    const val XK_Undo = 0xff65L
    const val XK_Redo = 0xff66L
    const val XK_Menu = 0xff67L
    const val XK_Find = 0xff68L
    const val XK_Cancel = 0xff69L
    const val XK_Help = 0xff6aL
    const val XK_Break = 0xff6bL

    // ---- keypad ----
    const val XK_KP_Space = 0xff80L
    const val XK_KP_Tab = 0xff89L
    const val XK_KP_Enter = 0xff8dL
    const val XK_KP_F1 = 0xff91L
    const val XK_KP_F2 = 0xff92L
    const val XK_KP_F3 = 0xff93L
    const val XK_KP_F4 = 0xff94L
    const val XK_KP_Home = 0xff95L
    const val XK_KP_Left = 0xff96L
    const val XK_KP_Up = 0xff97L
    const val XK_KP_Right = 0xff98L
    const val XK_KP_Down = 0xff99L
    const val XK_KP_Page_Up = 0xff9aL
    const val XK_KP_Page_Down = 0xff9bL
    const val XK_KP_End = 0xff9cL
    const val XK_KP_Begin = 0xff9dL
    const val XK_KP_Insert = 0xff9eL
    const val XK_KP_Delete = 0xff9fL
    const val XK_KP_Equal = 0xffbdL
    const val XK_KP_Multiply = 0xffaaL
    const val XK_KP_Add = 0xffabL
    const val XK_KP_Separator = 0xffacL
    const val XK_KP_Subtract = 0xffadL
    const val XK_KP_Decimal = 0xffaeL
    const val XK_KP_Divide = 0xffafL
    const val XK_KP_0 = 0xffb0L
    const val XK_KP_1 = 0xffb1L
    const val XK_KP_2 = 0xffb2L
    const val XK_KP_3 = 0xffb3L
    const val XK_KP_4 = 0xffb4L
    const val XK_KP_5 = 0xffb5L
    const val XK_KP_6 = 0xffb6L
    const val XK_KP_7 = 0xffb7L
    const val XK_KP_8 = 0xffb8L
    const val XK_KP_9 = 0xffb9L

    // ---- F 键 ----
    const val XK_F1 = 0xffbeL
    const val XK_F2 = 0xffbfL
    const val XK_F3 = 0xffc0L
    const val XK_F4 = 0xffc1L
    const val XK_F5 = 0xffc2L
    const val XK_F6 = 0xffc3L
    const val XK_F7 = 0xffc4L
    const val XK_F8 = 0xffc5L
    const val XK_F9 = 0xffc6L
    const val XK_F10 = 0xffc7L
    const val XK_F11 = 0xffc8L
    const val XK_F12 = 0xffc9L
    const val XK_F13 = 0xffcaL
    const val XK_F14 = 0xffcbL
    const val XK_F15 = 0xffccL
    const val XK_F16 = 0xffcdL
    const val XK_F17 = 0xffceL
    const val XK_F18 = 0xffcfL
    const val XK_F19 = 0xffd0L
    const val XK_F20 = 0xffd1L
    const val XK_F21 = 0xffd2L
    const val XK_F22 = 0xffd3L
    const val XK_F23 = 0xffd4L
    const val XK_F24 = 0xffd5L

    // ---- AltGr / Compose / 国际键 ----
    const val XK_ISO_Level3_Shift = 0xfe03L   // AltGr
    const val XK_Multi_key = 0xff20L          // Compose
    const val XK_Zenkaku = 0xff28L
    const val XK_Hankaku = 0xff29L
    const val XK_Henkan_Mode = 0xff23L
    const val XK_Muhenkan = 0xff22L
    const val XK_Hangul = 0xff31L
    const val XK_Hangul_Hanja = 0xff34L

    // ---- XF86 多媒体键（X11 XF86keysym.h 标准值）----
    const val XK_XF86AudioLowerVolume = 0x1008ff11L
    const val XK_XF86AudioMute = 0x1008ff12L
    const val XK_XF86AudioRaiseVolume = 0x1008ff13L
    const val XK_XF86AudioPlay = 0x1008ff14L
    const val XK_XF86AudioStop = 0x1008ff15L
    const val XK_XF86AudioPrev = 0x1008ff16L
    const val XK_XF86AudioNext = 0x1008ff17L
    const val XK_XF86HomePage = 0x1008ff18L
    const val XK_XF86Mail = 0x1008ff19L
    const val XK_XF86Search = 0x1008ff1bL
    const val XK_XF86Calculator = 0x1008ff1dL
    const val XK_XF86PowerOff = 0x1008ff2aL
    const val XK_XF86WakeUp = 0x1008ff2bL
    const val XK_XF86Eject = 0x1008ff2cL

    // ---- 修饰键集合判断 ----
    val MODIFIER_KEYSYMS = setOf(
        XK_Shift_L, XK_Shift_R, XK_Control_L, XK_Control_R,
        XK_Meta_L, XK_Meta_R, XK_Alt_L, XK_Alt_R,
        XK_Super_L, XK_Super_R, XK_Hyper_L, XK_Hyper_R, XK_Caps_Lock,
        XK_ISO_Level3_Shift
    )

    fun isModifier(keysym: Long): Boolean = MODIFIER_KEYSYMS.contains(keysym)

    /**
     * Unicode 码点 → keysym：
     * Latin-1 直接映射；常用文字查传统表；其余走 Unicode 区（0x01000000|cp）
     */
    fun lookup(codepoint: Int): Long {
        if (codepoint in 0x20..0xff) return codepoint.toLong()
        KEYSYMS_BY_CODEPOINT[codepoint]?.let { return it }
        return (0x01000000L or codepoint.toLong())
    }

    /** keysym → 大致可读名称（用于 UI 显示） */
    fun name(keysym: Long): String {
        for (f in Keysyms::class.java.declaredFields) {
            if (f.name.startsWith("XK_")) {
                val v = f.getLong(null)
                if (v == keysym) return f.name.removePrefix("XK_").replace('_', ' ')
            }
        }
        if (keysym >= 0x01000000L) {
            val cp = (keysym and 0xffffffL).toInt()
            return String(Character.toChars(cp))
        }
        if (keysym in 0x20..0xff) return String(Character.toChars(keysym.toInt()))
        return "0x${keysym.toString(16)}"
    }
}
