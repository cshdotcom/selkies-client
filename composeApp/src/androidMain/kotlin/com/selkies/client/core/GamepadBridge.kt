package com.selkies.client.core

import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Android 手柄桥：把系统 KeyEvent/MotionEvent（SOURCE_GAMEPAD/JOYSTICK/DPAD）
 * 转成 Selkies 手柄指令（js,c/b/a/d/h），经会话上行。
 *
 * 按钮编号对齐 W3C Gamepad API（官方网页端同序）：
 * 0=A 1=B 2=X 3=Y 4=LB 5=RB 6=LT 7=RT 8=Back 9=Start 10=LS 11=RS
 * 12=DPAD↑ 13=DPAD↓ 14=DPAD← 15=DPAD→ 16=Guide
 * 轴：0=LX 1=LY 2=RX 3=RY 4=LT 5=RT
 */
object GamepadBridge {

    /** 服务器手柄槽位上限（官方 num_gamepads = 4） */
    const val MAX_PADS = 4

    private class PadState(val index: Int) {
        val buttons = FloatArray(17)
        val axes = FloatArray(6)
        var connected = false
        var deviceId = -1
    }

    @Volatile
    private var session: SelkiesSession? = null

    /** deviceId → PadState */
    private val pads = ConcurrentHashMap<Int, PadState>()
    private val nextIndex = AtomicInteger(0)
    private var deviceListener: InputManager.InputDeviceListener? = null

    fun attach(s: SelkiesSession?) {
        session = s
        if (s == null) {
            // 清空本地状态（会话已断，无需再发 js,d）
            unregisterDeviceListener()
            pads.clear()
        } else {
            registerDeviceListener()
        }
    }

    /** 已连接手柄信息（测试面板显示） */
    fun connectedPads(): List<Triple<Int, String, Int>> {
        return pads.values.filter { it.connected }.map { pad ->
            val name = InputDevice.getDevice(pad.deviceId)?.name ?: "pad-${pad.index}"
            Triple(pad.index, name ?: "pad-${pad.index}", pad.deviceId)
        }
    }

    /** 实时快照（测试面板：按钮值 + 轴值） */
    fun snapshot(padIndex: Int): Pair<FloatArray, FloatArray>? {
        val pad = pads.values.firstOrNull { it.connected && it.index == padIndex } ?: return null
        return pad.buttons.copyOf() to pad.axes.copyOf()
    }

    private fun registerDeviceListener() {
        val ctx = com.selkies.client.platform.AndroidPlatform.appCtx() ?: return
        val im = ctx.getSystemService(android.content.Context.INPUT_SERVICE) as? InputManager ?: return
        if (deviceListener != null) return
        deviceListener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {}
            override fun onInputDeviceChanged(deviceId: Int) {}
            override fun onInputDeviceRemoved(deviceId: Int) {
                pads.remove(deviceId)?.let { pad ->
                    if (pad.connected) {
                        runCatching { session?.sendGamepadDisconnect(pad.index) }
                    }
                }
            }
        }
        runCatching { im.registerInputDeviceListener(deviceListener, null) }
    }

    private fun unregisterDeviceListener() {
        val ctx = com.selkies.client.platform.AndroidPlatform.appCtx() ?: return
        val im = ctx.getSystemService(android.content.Context.INPUT_SERVICE) as? InputManager ?: return
        deviceListener?.let { runCatching { im.unregisterInputDeviceListener(it) } }
        deviceListener = null
    }

    private fun isGamepad(deviceId: Int): Boolean {
        val dev = InputDevice.getDevice(deviceId) ?: return false
        val src = dev.sources
        return (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    private fun ensurePad(deviceId: Int): PadState? {
        pads[deviceId]?.let { return it }
        val dev = InputDevice.getDevice(deviceId) ?: return null
        if (!isGamepad(deviceId)) return null
        // 【v2.7.7】viewer 连接：js 只能驱动自持槽位（官方 gamepad_slot_denied）——
        // viewer 只允许一副物理手柄，index = slot-1；controller 不受限（服务器默认放行全部 index）
        val viewerSlot = session?.gamepadSlot?.value
        if (session?.role?.value == "viewer") {
            if (viewerSlot == null || viewerSlot !in 2..4) return null  // viewer 无槽位：服务器拒收任何 js
            if (pads.isNotEmpty()) return null  // viewer 只驱动这一副
        }
        // 服务器只有 4 个槽位：超出不再连接
        if (pads.values.count { it.connected } >= MAX_PADS) return null
        val pad = PadState(
            if (session?.role?.value == "viewer") (viewerSlot!! - 1)
            else nextIndex.getAndIncrement()
        )
        pads[deviceId] = pad
        pad.deviceId = deviceId
        // 首次事件即连接：id 用设备名 base64（官方格式）
        val idB64 = java.util.Base64.getEncoder()
            .encodeToString((dev.name ?: "android-pad").take(64).toByteArray(Charsets.ISO_8859_1))
        runCatching {
            session?.sendGamepadConnect(pad.index, idB64, 6, 17)
            session?.startGamepadHeartbeat()
        }
        pad.connected = true
        return pad
    }

    /** 对全部已连手柄发 js,h 保活（会话层定时器调用） */
    fun sendHeartbeats(s: SelkiesSession?) {
        pads.values.filter { it.connected }.forEach { pad ->
            runCatching { s?.sendGamepadHeartbeat(pad.index) }
        }
    }

    /** rumble：振动指定槽位的手柄（Android InputDevice 振动器） */
    fun rumble(slot: Int, strong: Float, weak: Float, ms: Int) {
        val pad = pads.values.firstOrNull { it.connected && it.index == slot } ?: return
        if (pad.deviceId < 0) return
        runCatching {
            val dev = InputDevice.getDevice(pad.deviceId) ?: return
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
                val vm = dev.vibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                dev.vibrator
            } ?: return
            if (vibrator.hasVibrator()) {
                val amplitude = ((strong * 0.6f + weak * 0.4f).coerceIn(0f, 1f) * 255).toInt().coerceAtLeast(1)
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(
                        android.os.VibrationEffect.createOneShot(
                            ms.toLong(),
                            android.os.VibrationEffect.DEFAULT_AMPLITUDE.takeIf { amplitude >= 255 } ?: amplitude
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(ms.toLong())
                }
            }
        }
    }

    /** 键事件（按钮）。返回 true 表示已消费 */
    fun handleKey(event: KeyEvent): Boolean {
        if (event.keyCode in SYSTEM_KEYS) return false
        if (!isGamepad(event.deviceId)) return false
        val s = session ?: return false
        val button = mapButton(event.keyCode) ?: return false
        val pad = ensurePad(event.deviceId) ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        val value = if (down) 1.0 else 0.0
        pad.buttons[button] = value.toFloat()
        runCatching { s.sendGamepadButton(pad.index, button, value) }
        return true
    }

    /** 轴/扳机事件。返回 true 表示已消费 */
    fun handleMotion(event: MotionEvent): Boolean {
        if (!isGamepad(event.deviceId)) return false
        val s = session ?: return false
        val pad = ensurePad(event.deviceId) ?: return false

        fun axisValue(axis: Int): Float {
            val r = event.getRangeOrNull(axis) ?: return 0f
            val v = event.getAxisValue(axis)
            return if (r == 0f) 0f else (v / r).coerceIn(-1f, 1f)
        }

        // 摇杆
        val pairs = arrayOf(
            MotionEvent.AXIS_X to 0, MotionEvent.AXIS_Y to 1,
            MotionEvent.AXIS_Z to 2, MotionEvent.AXIS_RZ to 3
        )
        for ((axis, slot) in pairs) {
            if (event.hasAxis(axis)) {
                val v = axisValue(axis)
                if (abs(v - pad.axes[slot]) > 0.01f) {
                    pad.axes[slot] = v
                    runCatching { s.sendGamepadAxis(pad.index, slot, v.toDouble()) }
                }
            }
        }
        // 模拟扳机（轴）；同步作为模拟按钮 6/7 上报（官方同款）
        val trigs = arrayOf(MotionEvent.AXIS_LTRIGGER to 4, MotionEvent.AXIS_RTRIGGER to 5)
        for ((axis, slot) in trigs) {
            if (event.hasAxis(axis)) {
                val v = event.getAxisValue(axis).coerceIn(0f, 1f)
                if (abs(v - pad.axes[slot]) > 0.02f) {
                    pad.axes[slot] = v
                    runCatching { s.sendGamepadAxis(pad.index, slot, v.toDouble()) }
                    runCatching { s.sendGamepadButton(pad.index, if (slot == 4) 6 else 7, v.toDouble()) }
                }
            }
        }
        // 帽子开关（DPAD 轴）
        if (event.hasAxis(MotionEvent.AXIS_HAT_X) || event.hasAxis(MotionEvent.AXIS_HAT_Y)) {
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val dpad = floatArrayOf(
                if (hy < -0.5f) 1f else 0f,  // up → 12
                if (hy > 0.5f) 1f else 0f,   // down → 13
                if (hx < -0.5f) 1f else 0f,  // left → 14
                if (hx > 0.5f) 1f else 0f    // right → 15
            )
            for (i in 0..3) {
                val btn = 12 + i
                if (abs(dpad[i] - pad.buttons[btn]) > 0.5f) {
                    pad.buttons[btn] = dpad[i]
                    runCatching { s.sendGamepadButton(pad.index, btn, dpad[i].toDouble()) }
                }
            }
        }
        return true
    }

    private fun MotionEvent.hasAxis(axis: Int): Boolean =
        runCatching { device?.motionRanges?.any { it.axis == axis } == true }.getOrDefault(false)

    private fun MotionEvent.getRangeOrNull(axis: Int): Float? =
        runCatching { device?.motionRanges?.firstOrNull { it.axis == axis }?.range }.getOrNull()

    private fun mapButton(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> 0
        KeyEvent.KEYCODE_BUTTON_B -> 1
        KeyEvent.KEYCODE_BUTTON_X -> 2
        KeyEvent.KEYCODE_BUTTON_Y -> 3
        KeyEvent.KEYCODE_BUTTON_L1 -> 4
        KeyEvent.KEYCODE_BUTTON_R1 -> 5
        KeyEvent.KEYCODE_BUTTON_L2 -> 6
        KeyEvent.KEYCODE_BUTTON_R2 -> 7
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> 8
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> 9
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 10
        KeyEvent.KEYCODE_BUTTON_THUMBR -> 11
        KeyEvent.KEYCODE_DPAD_UP -> 12
        KeyEvent.KEYCODE_DPAD_DOWN -> 13
        KeyEvent.KEYCODE_DPAD_LEFT -> 14
        KeyEvent.KEYCODE_DPAD_RIGHT -> 15
        KeyEvent.KEYCODE_BUTTON_MODE -> 16
        else -> null
    }

    private val SYSTEM_KEYS = setOf(
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE
    )
}
