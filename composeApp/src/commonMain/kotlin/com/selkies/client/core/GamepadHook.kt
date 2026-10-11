package com.selkies.client.core

/**
 * 平台手柄会话挂钩：
 * - Android：MainActivity 把 KeyEvent/MotionEvent 分发给 GamepadBridge
 * - Desktop：暂无系统手柄枚举，空实现
 */
expect fun attachGamepadSession(session: SelkiesSession?)
