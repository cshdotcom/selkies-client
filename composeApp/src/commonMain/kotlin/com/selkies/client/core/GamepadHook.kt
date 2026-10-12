package com.selkies.client.core

/**
 * 平台手柄会话挂钩：
 * - Android：MainActivity 把 KeyEvent/MotionEvent 分发给 GamepadBridge
 * - Desktop：LWJGL GLFW gamepad 轮询（v2.5.0）
 */
expect fun attachGamepadSession(session: SelkiesSession?)

/** 对全部已连手柄发送 js,h 保活（会话层定时器调用） */
expect fun sendGamepadHeartbeats(session: SelkiesSession?)

/** 手柄实时快照（测试面板显示） */
data class GamepadInfo(
    val index: Int,
    val name: String,
    val buttons: FloatArray,   // W3C 17 键
    val axes: FloatArray       // 6 轴
) {
    override fun equals(other: Any?): Boolean = other is GamepadInfo && other.index == index
    override fun hashCode(): Int = index
}

expect fun gamepadSnapshot(): List<GamepadInfo>
