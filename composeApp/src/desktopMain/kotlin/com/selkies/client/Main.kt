package com.selkies.client

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.selkies.client.platform.DesktopPlatform
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

fun main() {
    // v2.7.0：stdout 行缓冲（重定向到文件时 JVM 默认块缓冲，诊断日志积压丢失）
    runCatching {
        System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true))
        System.setErr(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true))
    }
    System.setProperty("skiko.settings.fps", "60")
    applyLegacyWindowsRenderFix()
    installCrashLogger()
    // GL 自检模式（launch.sh 便携启动器调用）：真实渲染一帧验证 GPU 路径可用性
    if (System.getProperty("selkies.glselftest") != null) {
        glSelfTest()
        return
    }
    // v2.7.1：窗口尺寸可用 -Dselkies.winsize=1280x400 自定义（默认 1280x800 不变）
    //   用途：小屏/横屏手机布局实测（完整键盘 fit、工具栏布局）与自动化验证
    val winSize = runCatching {
        val p = (System.getProperty("selkies.winsize") ?: "").split("x")
        require(p.size == 2 && p[0].toInt() > 0 && p[1].toInt() > 0)
        p[0].toInt() to p[1].toInt()
    }.getOrNull()
    application {
        DesktopPlatform.init()
        Window(
            onCloseRequest = ::exitApplicationCompat,
            title = "Selkies 远程桌面",
            state = rememberWindowState(
                width = (winSize?.first ?: 1280).dp,
                height = (winSize?.second ?: 800).dp
            )
        ) {
            // AWT 帧引用（文件选择器 / 全屏控制 / 焦点追踪）
            DesktopPlatform.windowFrame = window
            App()
        }
    }
}

/**
 * 【v2.6.0+ · 无 GL 环境自检】
 * Linux 无 GPU/VM/Xvfb 场景：Skiko 默认 OPENGL → "Cannot create Linux GL context" →
 * 窗口空白（异常被 Skiko 内部吞掉，应用不退出，用户只见白屏/黑屏）。
 * launch.sh 先以本模式跑一次：真实渲染 + Robot 截屏做像素级验证——
 * 窗口中心出现绘制色 = 渲染管线端到端可用 → exit(0)；拿不到像素（超时被杀）→
 * 启动器改用 -Dskiko.renderApi=SOFTWARE 启动。
 * ⚠️ 不能只看 draw lambda 是否执行：绘制指令被录制后由 redrawer 提交，GL 失败
 *    发生在提交层——lambda 跑完不等于像素上屏（实测教训）。
 */
private fun glSelfTest() {
    application {
        Window(onCloseRequest = ::exitApplication, title = "GL self-test") {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color(0xFFDC143C)) // crimson 特征色，避免与常见桌面色混淆
            }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(1200) // 等窗口上屏
                val ok = runCatching {
                    val robot = java.awt.Robot()
                    val gc = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
                    val bounds = gc.defaultConfiguration.bounds
                    val shot = robot.createScreenCapture(bounds)
                    // 全屏扫描特征色（窗口位置由 WM/平台决定，不可假设居中）
                    var hit = false
                    outer@ for (y in 0 until bounds.height step 4) {
                        for (x in 0 until bounds.width step 4) {
                            val p = shot.getRGB(x, y)
                            val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
                            if (r in 190..255 && g in 10..70 && b in 40..100) {
                                hit = true; break@outer
                            }
                        }
                    }
                    hit
                }.getOrDefault(false)
                kotlin.system.exitProcess(if (ok) 0 else 1)
            }
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
