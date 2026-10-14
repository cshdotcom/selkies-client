package com.selkies.client

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.selkies.client.platform.DesktopPlatform
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

fun main() {
    System.setProperty("skiko.settings.fps", "60")
    applyLegacyWindowsRenderFix()
    installCrashLogger()
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

/**
 * 【v2.6.0 · Windows 7/8 崩溃修复】
 * 用户实测崩溃日志（hs_err_pid6048.log）：
 *   EXCEPTION_ACCESS_VIOLATION in skiko-windows-x64.dll（Image._nMakeFromBitmap）
 *   —— Win7/8 的老 OpenGL 驱动/ANGLE 与 Skiko GPU 渲染路径不兼容，视频帧上屏时原生层崩溃。
 * 官方 Compose Desktop 不再支持 Win7/8；强制 SOFTWARE 渲染可完全绕开 GPU 路径，
 * 代价是 CPU 占用略高，但在旧机器上"能用且不崩"远重要于渲染速度。
 * skiko.renderApi 必须在 Compose/Skiko 初始化前设置。
 */
private fun applyLegacyWindowsRenderFix() {
    runCatching {
        val osName = System.getProperty("os.name") ?: ""
        val osVersion = System.getProperty("os.version") ?: ""
        val isWindows = osName.contains("windows", ignoreCase = true)
        // Windows 10 = 10.x；Windows 8/8.1 = 6.2/6.3；Windows 7 = 6.1；XP = 5.x
        val legacy = isWindows && (
                osVersion.startsWith("5.") ||
                        osVersion.startsWith("6.0") ||
                        osVersion.startsWith("6.1") ||
                        osVersion.startsWith("6.2") ||
                        osVersion.startsWith("6.3")
                )
        // os.version 在部分 Win11 上报 10.0，Win7 恒 6.1 —— 名称兜底
        val legacyByName = isWindows && (osName.contains("windows 7", true) || osName.contains("windows 8", true))
        if (legacy || legacyByName) {
            System.setProperty("skiko.renderApi", "SOFTWARE")
            System.setProperty("skiko.renderApi.fallback", "SOFTWARE")
        }
    }
}

/**
 * 【v2.6.0】全局未捕获异常落盘（<user.home>/.selkies-desktop/crash/），
 * 便于用户反馈时直接附上日志定位（旧版原生崩溃外的问题完全无痕）。
 */
private fun installCrashLogger() {
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        runCatching {
            val dir = File(File(System.getProperty("user.home"), ".selkies-desktop"), "crash")
            dir.mkdirs()
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val f = File(dir, "crash_${System.currentTimeMillis()}.log")
            f.writeText("Thread: ${t.name}\n${sw}")
        }
        e.printStackTrace()
    }
}

private fun exitApplicationCompat() {
    DesktopPlatform.shutdown()
    kotlin.system.exitProcess(0)
}
