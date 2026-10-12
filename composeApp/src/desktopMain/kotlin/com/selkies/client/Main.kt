package com.selkies.client

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.selkies.client.platform.DesktopPlatform

fun main() {
    System.setProperty("skiko.settings.fps", "60")
    application {
        DesktopPlatform.init()
        Window(
            onCloseRequest = ::exitApplicationCompat,
            title = "Selkies 远程桌面",
            state = rememberWindowState(width = 1280.dp, height = 800.dp)
        ) {
            // AWT 帧引用（文件选择器 / 全屏控制 / 焦点追踪）
            DesktopPlatform.windowFrame = window
            App()
        }
    }
}

private fun exitApplicationCompat() {
    DesktopPlatform.shutdown()
    kotlin.system.exitProcess(0)
}
