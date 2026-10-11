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
            App()
        }
    }
}

private fun exitApplicationCompat() {
    DesktopPlatform.shutdown()
    kotlin.system.exitProcess(0)
}
