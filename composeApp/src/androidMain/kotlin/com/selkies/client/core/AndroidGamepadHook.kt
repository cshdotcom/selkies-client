package com.selkies.client.core

/** Android：把手柄会话挂到 GamepadBridge（MainActivity 分发输入事件） */
actual fun attachGamepadSession(session: SelkiesSession?) {
    GamepadBridge.attach(session)
}
