package com.selkies.client.core

/** Android：把手柄会话挂到 GamepadBridge（MainActivity 分发输入事件） */
actual fun attachGamepadSession(session: SelkiesSession?) {
    GamepadBridge.attach(session)
}

actual fun sendGamepadHeartbeats(session: SelkiesSession?) {
    GamepadBridge.sendHeartbeats(session)
}

actual fun gamepadSnapshot(): List<GamepadInfo> {
    return GamepadBridge.connectedPads().mapNotNull { (index, name, _) ->
        val snap = GamepadBridge.snapshot(index) ?: return@mapNotNull null
        GamepadInfo(index, name, snap.first, snap.second)
    }
}
