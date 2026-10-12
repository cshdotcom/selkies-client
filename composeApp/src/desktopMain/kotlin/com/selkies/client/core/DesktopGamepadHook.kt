package com.selkies.client.core

import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWGamepadState
import java.util.concurrent.ConcurrentHashMap

/**
 * 桌面手柄钩子（v2.5.0）：LWJGL GLFW gamepad API——
 * Windows XInput / macOS / Linux 全平台标准映射手柄。
 *
 * GLFW 按钮布局 → Selkies W3C 布局映射（官方 lib/gamepad.js STANDARD_LAYOUT 同序）：
 * 0=A 1=B 2=X 3=Y 4=LB 5=RB 6=LT 7=RT 8=Back 9=Start 10=LS 11=RS
 * 12=DPAD↑ 13=DPAD↓ 14=DPAD← 15=DPAD→ 16=Guide；轴：0=LX 1=LY 2=RX 3=RY 4=LT 5=RT
 */
actual fun attachGamepadSession(session: SelkiesSession?) {
    DesktopGamepad.attach(session)
}

actual fun sendGamepadHeartbeats(session: SelkiesSession?) {
    DesktopGamepad.sendHeartbeats(session)
}

actual fun gamepadSnapshot(): List<GamepadInfo> {
    return DesktopGamepad.snapshot().map { (index, name, state) ->
        GamepadInfo(index, name, state.first, state.second)
    }
}

object DesktopGamepad {
    // GLFW 按钮 → W3C 按钮（Selkies 官方 lib/gamepad.js STANDARD_LAYOUT）
    // GLFW：0=A 1=B 2=X 3=Y 4=LB 5=RB 6=BACK 7=START 8=GUIDE 9=LTHUMB 10=RTHUMB 11=↑ 12=→ 13=↓ 14=←
    // W3C：0=A 1=B 2=X 3=Y 4=LB 5=RB 6=LT 7=RT 8=Back 9=Start 10=LS 11=RS 12=↑ 13=↓ 14=← 15=→ 16=Guide
    private val BUTTON_MAP = intArrayOf(
        0,   // A
        1,   // B
        2,   // X
        3,   // Y
        4,   // LB
        5,   // RB
        8,   // BACK → W3C 8
        9,   // START → W3C 9
        16,  // GUIDE → W3C 16
        10,  // LEFT_THUMB → W3C 10
        11,  // RIGHT_THUMB → W3C 11
        12,  // DPAD_UP → W3C 12
        15,  // DPAD_RIGHT → W3C 15
        13,  // DPAD_DOWN → W3C 13
        14   // DPAD_LEFT → W3C 14
    )

    private class Pad(val jid: Int, val index: Int) {
        val buttons = FloatArray(17)
        val axes = FloatArray(6)
        var connected = true
    }

    @Volatile
    private var session: SelkiesSession? = null

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private val pads = ConcurrentHashMap<Int, Pad>()   // jid → pad
    private var nextIndex = 0
    private val deadzone = 0.05f

    fun attach(s: SelkiesSession?) {
        session = s
        if (s != null) start() else stop()
    }

    /** 对全部已连手柄发 js,h 保活（会话层定时器调用） */
    fun sendHeartbeats(s: SelkiesSession?) {
        pads.values.filter { it.connected }.forEach { pad ->
            runCatching { s?.sendGamepadHeartbeat(pad.index) }
        }
    }

    /** 测试面板：已连接手柄快照 */
    fun snapshot(): List<Triple<Int, String, Pair<FloatArray, FloatArray>>> {
        return pads.values.filter { it.connected }.map { p ->
            Triple(p.index, GLFW.glfwGetJoystickName(p.jid) ?: "pad-${p.index}", p.buttons.copyOf() to p.axes.copyOf())
        }
    }

    @Synchronized
    private fun start() {
        if (running) return
        running = true
        thread = Thread(this::loop, "selkies-gamepad").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    @Synchronized
    private fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        pads.values.forEach { p ->
            runCatching { session?.sendGamepadDisconnect(p.index) }
        }
        pads.clear()
        nextIndex = 0
        runCatching { GLFW.glfwTerminate() }
    }

    private fun loop() {
        // GLFW 初始化（无显示环境可能失败——优雅降级）
        if (!runCatching { GLFW.glfwInit() }.getOrDefault(false)) {
            session?.let { s ->
                s.notices.value = "手柄：本环境无法初始化输入子系统（无显示/输入权限）"
            }
            running = false
            return
        }
        val state = GLFWGamepadState.calloc()
        val names = HashMap<Int, String>()
        while (running) {
            try {
                // 扫描连接/断开
                for (jid in GLFW.GLFW_JOYSTICK_1..GLFW.GLFW_JOYSTICK_16) {
                    val present = GLFW.glfwJoystickPresent(jid)
                    val known = pads[jid]
                    if (present && known == null) {
                        if (!GLFW.glfwJoystickIsGamepad(jid)) continue
                        val index = nextIndex++
                        if (index >= 4) continue // 服务器槽位上限
                        val pad = Pad(jid, index)
                        pads[jid] = pad
                        val name = GLFW.glfwGetGamepadName(jid) ?: GLFW.glfwGetJoystickName(jid) ?: "gamepad"
                        names[jid] = name
                        val idB64 = java.util.Base64.getEncoder()
                            .encodeToString(name.take(64).toByteArray(Charsets.ISO_8859_1))
                        runCatching {
                            session?.sendGamepadConnect(index, idB64, 6, 17)
                            session?.startGamepadHeartbeat()
                        }
                    } else if (!present && known != null) {
                        pads.remove(jid)
                        runCatching { session?.sendGamepadDisconnect(known.index) }
                    }
                }
                // 轮询状态
                for (pad in pads.values) {
                    if (!GLFW.glfwGetGamepadState(pad.jid, state)) continue
                    // 按钮（GLFW 15 键 → W3C 17 键；扳机按钮 6/7 来自轴值）
                    for (i in 0..14) {
                        val w3c = BUTTON_MAP[i]
                        if (w3c < 0) continue
                        val v = if (state.buttons(i).toInt() == GLFW.GLFW_PRESS) 1f else 0f
                        if (v != pad.buttons[w3c]) {
                            pad.buttons[w3c] = v
                            runCatching { session?.sendGamepadButton(pad.index, w3c, v.toDouble()) }
                        }
                    }
                    // 轴（含死区；GLFW 与 W3C 同为 Y 负向上，无需反转）
                    for (a in 0..5) {
                        var v = state.axes(a)
                        if (v > -deadzone && v < deadzone) v = 0f
                        // 触发器轴 0..1 → 保持（官方 axes 4/5 = 0..1）
                        if (v != pad.axes[a]) {
                            pad.axes[a] = v
                            runCatching { session?.sendGamepadAxis(pad.index, a, v.toDouble()) }
                            // 扳机轴 → 模拟按钮 6/7（官方同款双报）
                            val btn = if (a == 4) 6 else if (a == 5) 7 else -1
                            if (btn >= 0) {
                                runCatching { session?.sendGamepadButton(pad.index, btn, v.toDouble()) }
                            }
                        }
                    }
                }
                Thread.sleep(4) // 官方 GP_TIMEOUT=4ms
            } catch (_: InterruptedException) {
                break
            } catch (_: Throwable) {
                Thread.sleep(200)
            }
        }
        runCatching { state.free() }
    }
}
