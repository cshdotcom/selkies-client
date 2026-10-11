package com.selkies.client

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.selkies.client.core.GamepadBridge

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.selkies.client.platform.AndroidPlatform.init(this)
        setContent {
            App()
        }
    }

    /** 手柄按钮/方向键 → 远程（js,b） */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (GamepadBridge.handleKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** 手柄摇杆/扳机轴 → 远程（js,a） */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (GamepadBridge.handleMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            com.selkies.client.platform.AndroidPlatform.release()
        }
    }
}
