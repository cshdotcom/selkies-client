package com.selkies.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

/**
 * 平台返回键处理（expect/actual）：
 * - Android：接系统返回手势/返回键
 * - Desktop：【v2.6.0+ 修复】ESC 键 = 返回（关闭最上层弹层/面板/键盘）
 *
 * 桌面实现要点：
 * - AWT KeyEventDispatcher 全局捕获（不依赖 Compose 焦点树——视频区无焦点组件时
 *   Compose onKeyEvent 收不到，旧版空实现 = ESC 完全无效，面板只能点关闭按钮）
 * - 多层注册时 AWT 按 LIFO 调用：内层（面板等）先消费返回 true，外层不再触发
 * - 文本输入框中的 ESC：同样触发返回（桌面惯例：ESC 取消当前层）
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val enabledState = rememberUpdatedState(enabled)
    val onBackState = rememberUpdatedState(onBack)
    DisposableEffect(Unit) {
        val dispatcher = KeyEventDispatcher { e ->
            if (e.id == KeyEvent.KEY_PRESSED && e.keyCode == KeyEvent.VK_ESCAPE) {
                if (enabledState.value) {
                    onBackState.value()
                    true
                } else false
            } else false
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
        onDispose {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
        }
    }
}
