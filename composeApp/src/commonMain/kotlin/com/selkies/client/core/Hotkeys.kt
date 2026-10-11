package com.selkies.client.core

import com.selkies.client.model.HotkeyDef

/**
 * 内置快捷键库：常用操作 + 移动端发不出的按键组合
 */
object BuiltinHotkeys {
    private fun id(s: String) = "builtin-$s"

    fun list(): List<HotkeyDef> {
        val system = listOf(
            HotkeyDef(id("lock"), "锁定屏幕", listOf(Keysyms.XK_Super_L, 'l'.code.toLong()), true, "system"),
            HotkeyDef(id("run"), "运行对话框", listOf(Keysyms.XK_Alt_L, Keysyms.XK_F2), true, "system"),
            HotkeyDef(id("terminal"), "打开终端", listOf(Keysyms.XK_Control_L, Keysyms.XK_Alt_L, 't'.code.toLong()), true, "system"),
            HotkeyDef(id("close-window"), "关闭窗口", listOf(Keysyms.XK_Alt_L, Keysyms.XK_F4), true, "system"),
            HotkeyDef(id("switch-app"), "切换应用", listOf(Keysyms.XK_Alt_L, Keysyms.XK_Tab), true, "system"),
            HotkeyDef(id("show-desktop"), "显示桌面", listOf(Keysyms.XK_Super_L, 'd'.code.toLong()), true, "system"),
            HotkeyDef(id("activities"), "活动概览", listOf(Keysyms.XK_Super_L), true, "system"),
            HotkeyDef(id("screenshot"), "截图", listOf(Keysyms.XK_Print), true, "system"),
            HotkeyDef(id("area-shot"), "区域截图", listOf(Keysyms.XK_Shift_L, Keysyms.XK_Print), true, "system"),
            HotkeyDef(id("file-manager"), "文件管理器", listOf(Keysyms.XK_Super_L, 'e'.code.toLong()), true, "system"),
            HotkeyDef(id("settings"), "系统设置", listOf(Keysyms.XK_Super_L, 'i'.code.toLong()), true, "system"),
            HotkeyDef(id("search"), "搜索", listOf(Keysyms.XK_Super_L), true, "system"),
            HotkeyDef(id("workspace-up"), "切换工作区", listOf(Keysyms.XK_Super_L, Keysyms.XK_Page_Up), true, "system"),
            HotkeyDef(id("workspace-down"), "下个工作区", listOf(Keysyms.XK_Super_L, Keysyms.XK_Page_Down), true, "system"),
            HotkeyDef(id("log-out"), "注销/退出会话", listOf(Keysyms.XK_Control_L, Keysyms.XK_Alt_L, Keysyms.XK_Delete), true, "system"),
            HotkeyDef(id("display"), "显示设置", listOf(Keysyms.XK_Super_L, 'p'.code.toLong()), true, "system"),
            HotkeyDef(id("notifications"), "通知面板", listOf(Keysyms.XK_Super_L, 'n'.code.toLong()), true, "system"),
            HotkeyDef(id("window-left"), "窗口靠左", listOf(Keysyms.XK_Super_L, Keysyms.XK_Left), true, "system"),
            HotkeyDef(id("window-right"), "窗口靠右", listOf(Keysyms.XK_Super_L, Keysyms.XK_Right), true, "system"),
            HotkeyDef(id("window-max"), "窗口最大化", listOf(Keysyms.XK_Super_L, Keysyms.XK_Up), true, "system")
        )
        val browser = listOf(
            HotkeyDef(id("address"), "地址栏", listOf(Keysyms.XK_Control_L, 'l'.code.toLong()), true, "browser"),
            HotkeyDef(id("new-tab"), "新标签页", listOf(Keysyms.XK_Control_L, 't'.code.toLong()), true, "browser"),
            HotkeyDef(id("close-tab"), "关闭标签页", listOf(Keysyms.XK_Control_L, 'w'.code.toLong()), true, "browser"),
            HotkeyDef(id("reopen-tab"), "恢复标签页", listOf(Keysyms.XK_Control_L, Keysyms.XK_Shift_L, 't'.code.toLong()), true, "browser"),
            HotkeyDef(id("find"), "页面查找", listOf(Keysyms.XK_Control_L, 'f'.code.toLong()), true, "browser"),
            HotkeyDef(id("devtools"), "开发者工具", listOf(Keysyms.XK_F12), true, "browser"),
            HotkeyDef(id("reload"), "刷新页面", listOf(Keysyms.XK_Control_L, 'r'.code.toLong()), true, "browser"),
            HotkeyDef(id("zoom-in"), "放大", listOf(Keysyms.XK_Control_L, '+'.code.toLong()), true, "browser"),
            HotkeyDef(id("zoom-out"), "缩小", listOf(Keysyms.XK_Control_L, '-'.code.toLong()), true, "browser"),
            HotkeyDef(id("zoom-reset"), "缩放复位", listOf(Keysyms.XK_Control_L, '0'.code.toLong()), true, "browser"),
            HotkeyDef(id("switch-tab"), "切换标签", listOf(Keysyms.XK_Control_L, Keysyms.XK_Tab), true, "browser"),
            HotkeyDef(id("history"), "历史记录", listOf(Keysyms.XK_Control_L, 'h'.code.toLong()), true, "browser"),
            HotkeyDef(id("downloads"), "下载列表", listOf(Keysyms.XK_Control_L, 'j'.code.toLong()), true, "browser")
        )
        val text = listOf(
            HotkeyDef(id("select-all"), "全选", listOf(Keysyms.XK_Control_L, 'a'.code.toLong()), true, "text"),
            HotkeyDef(id("copy"), "复制", listOf(Keysyms.XK_Control_L, 'c'.code.toLong()), true, "text"),
            HotkeyDef(id("paste"), "粘贴", listOf(Keysyms.XK_Control_L, 'v'.code.toLong()), true, "text"),
            HotkeyDef(id("cut"), "剪切", listOf(Keysyms.XK_Control_L, 'x'.code.toLong()), true, "text"),
            HotkeyDef(id("undo"), "撤销", listOf(Keysyms.XK_Control_L, 'z'.code.toLong()), true, "text"),
            HotkeyDef(id("redo"), "重做", listOf(Keysyms.XK_Control_L, Keysyms.XK_Shift_L, 'z'.code.toLong()), true, "text"),
            HotkeyDef(id("find-text"), "查找", listOf(Keysyms.XK_Control_L, 'f'.code.toLong()), true, "text"),
            HotkeyDef(id("save"), "保存", listOf(Keysyms.XK_Control_L, 's'.code.toLong()), true, "text"),
            HotkeyDef(id("print-doc"), "打印", listOf(Keysyms.XK_Control_L, 'p'.code.toLong()), true, "text"),
            HotkeyDef(id("home-line"), "行首", listOf(Keysyms.XK_Home), true, "text"),
            HotkeyDef(id("end-line"), "行尾", listOf(Keysyms.XK_End), true, "text"),
            HotkeyDef(id("doc-home"), "文档开头", listOf(Keysyms.XK_Control_L, Keysyms.XK_Home), true, "text"),
            HotkeyDef(id("doc-end"), "文档结尾", listOf(Keysyms.XK_Control_L, Keysyms.XK_End), true, "text"),
            HotkeyDef(id("word-del"), "删除单词", listOf(Keysyms.XK_Control_L, Keysyms.XK_BackSpace), true, "text")
        )
        val shell = listOf(
            HotkeyDef(id("sigint"), "中断 (Ctrl+C)", listOf(Keysyms.XK_Control_L, 'c'.code.toLong()), true, "shell"),
            HotkeyDef(id("sigquit"), "退出 (Ctrl+\\)", listOf(Keysyms.XK_Control_L, '\\'.code.toLong()), true, "shell"),
            HotkeyDef(id("eof"), "输入结束 (Ctrl+D)", listOf(Keysyms.XK_Control_L, 'd'.code.toLong()), true, "shell"),
            HotkeyDef(id("suspend"), "挂起 (Ctrl+Z)", listOf(Keysyms.XK_Control_L, 'z'.code.toLong()), true, "shell"),
            HotkeyDef(id("clear"), "清屏", listOf(Keysyms.XK_Control_L, 'l'.code.toLong()), true, "shell"),
            HotkeyDef(id("reverse-search"), "历史搜索 (Ctrl+R)", listOf(Keysyms.XK_Control_L, 'r'.code.toLong()), true, "shell"),
            HotkeyDef(id("eof-word"), "删除到行首", listOf(Keysyms.XK_Control_L, 'u'.code.toLong()), true, "shell"),
            HotkeyDef(id("kill-word"), "删除到行尾", listOf(Keysyms.XK_Control_L, 'k'.code.toLong()), true, "shell")
        )
        return system + browser + text + shell
    }

    val categories = listOf(
        "system" to "系统",
        "browser" to "浏览器",
        "text" to "文本编辑",
        "shell" to "终端",
        "custom" to "自定义"
    )

    fun categoryName(key: String): String = categories.firstOrNull { it.first == key }?.second ?: key
}

/**
 * 快捷键录制器：累积按键序列 → 组合
 */
class HotkeyRecorder {
    private val pressed = LinkedHashSet<Long>()

    /** 记录一次按键；返回 true 表示录制完成 */
    fun offer(keysym: Long): Boolean {
        if (!Keysyms.isModifier(keysym)) return true
        pressed.add(keysym)
        return pressed.size >= 2
    }

    /** 完成录制（主键为最后一个非修饰键） */
    fun finish(mainKeysym: Long): List<Long> {
        val result = pressed.toList() + mainKeysym
        pressed.clear()
        return result
    }

    fun reset() = pressed.clear()

    fun current(): List<Long> = pressed.toList()
}
