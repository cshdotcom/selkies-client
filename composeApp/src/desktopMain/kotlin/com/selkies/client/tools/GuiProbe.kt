package com.selkies.client.tools

import java.awt.Robot
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import javax.imageio.ImageIO

/**
 * GUI E2E 驱动（Xvfb 下运行）：
 * 通过 -Dscript="sleep:4000;shot:01;click:640,300;..." 注入步骤序列，
 * 截图保存到 /tmp/gui-shots/。配合 Xvfb + 应用 JAR 进程做端到端验证。
 */
object GuiProbe {

    @JvmStatic
    fun main(args: Array<String>) {
        val robot = Robot()
        robot.setAutoDelay(50)
        File("/tmp/gui-shots").mkdirs()
        val script = System.getProperty("script") ?: args.joinToString(";")
        var shotIdx = 0
        for (step in script.split(";").map { it.trim() }.filter { it.isNotEmpty() }) {
            val idx = step.indexOf(':')
            val cmd = if (idx > 0) step.substring(0, idx) else step
            val arg = if (idx > 0) step.substring(idx + 1) else ""
            try {
                when (cmd) {
                    "sleep" -> Thread.sleep(arg.toLong())
                    "shot" -> {
                        val sz = java.awt.Toolkit.getDefaultToolkit().screenSize
                        val img = robot.createScreenCapture(Rectangle(0, 0, sz.width, sz.height))
                        ImageIO.write(img, "png", File("/tmp/gui-shots/${arg.ifBlank { "shot-%02d".format(shotIdx) }}.png"))
                        println("[shot] $arg saved (${sz.width}x${sz.height})")
                        shotIdx++
                    }
                    "click" -> click(robot, arg, InputEvent.BUTTON1_DOWN_MASK)
                    "rclick" -> click(robot, arg, InputEvent.BUTTON3_DOWN_MASK)
                    "dclick" -> {
                        click(robot, arg, InputEvent.BUTTON1_DOWN_MASK)
                        Thread.sleep(50)
                        click(robot, arg, InputEvent.BUTTON1_DOWN_MASK)
                    }
                    "drag" -> {
                        val v = arg.split(",")
                        val (x1, y1, x2, y2) = listOf(v[0].toInt(), v[1].toInt(), v[2].toInt(), v[3].toInt())
                        val dur = v[4].toLong()
                        robot.mouseMove(x1, y1)
                        Thread.sleep(150)
                        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                        Thread.sleep(80)
                        val t0 = System.currentTimeMillis()
                        while (System.currentTimeMillis() - t0 < dur) {
                            val f = (System.currentTimeMillis() - t0).toDouble() / dur
                            robot.mouseMove((x1 + (x2 - x1) * f).toInt(), (y1 + (y2 - y1) * f).toInt())
                            Thread.sleep(14)
                        }
                        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                        println("[drag] $arg")
                    }
                    "type" -> {
                        for (c in arg) {
                            val code = KeyEvent.getExtendedKeyCodeForChar(c.code)
                            if (code == KeyEvent.VK_UNDEFINED) continue
                            val upper = Character.isUpperCase(c)
                            if (upper) robot.keyPress(KeyEvent.VK_SHIFT)
                            robot.keyPress(code)
                            robot.keyRelease(code)
                            if (upper) robot.keyRelease(KeyEvent.VK_SHIFT)
                            Thread.sleep(20)
                        }
                        println("[type] $arg")
                    }
                    "key" -> {
                        val code = KeyEvent::class.java.getField("VK_$arg").getInt(null)
                        robot.keyPress(code)
                        robot.keyRelease(code)
                        println("[key] $arg")
                    }
                    "wheel" -> {
                        robot.mouseWheel(arg.toInt())
                        println("[wheel] $arg")
                    }
                    "moveto" -> {
                        val xy = arg.split(",")
                        robot.mouseMove(xy[0].toInt(), xy[1].toInt())
                        Thread.sleep(150)
                        println("[moveto] $arg")
                    }
                    "combo" -> {
                        // 组合键物理按下（快捷键录制盒捕获验证）：combo:CONTROL,C
                        val keys = arg.split(",").map { it.trim() }
                        for (k in keys) robot.keyPress(KeyEvent::class.java.getField("VK_$k").getInt(null))
                        Thread.sleep(120)
                        for (k in keys.reversed()) robot.keyRelease(KeyEvent::class.java.getField("VK_$k").getInt(null))
                        println("[combo] $arg")
                    }
                    "ctrlwheel" -> {
                        // Ctrl+滚轮（本地缩放）：ctrlwheel:2
                        robot.keyPress(KeyEvent.VK_CONTROL)
                        Thread.sleep(60)
                        robot.mouseWheel(arg.toInt())
                        Thread.sleep(60)
                        robot.keyRelease(KeyEvent.VK_CONTROL)
                        println("[ctrlwheel] $arg")
                    }
                    else -> println("[??] $step")
                }
            } catch (e: Exception) {
                println("[err] $step -> ${e.message}")
            }
        }
        println("GuiProbe done")
    }

    private fun click(robot: Robot, arg: String, mask: Int) {
        val xy = arg.split(",")
        robot.mouseMove(xy[0].toInt(), xy[1].toInt())
        Thread.sleep(100)
        robot.mousePress(mask)
        robot.mouseRelease(mask)
        println("[click] $arg")
    }
}
