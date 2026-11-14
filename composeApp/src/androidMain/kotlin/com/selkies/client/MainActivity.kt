package com.selkies.client

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.selkies.client.core.GamepadBridge
import com.selkies.client.core.RecordingShutdownHook

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.selkies.client.platform.AndroidPlatform.init(this)
        // 修复：Android 13+ 通知运行时权限——录屏前台服务通知需要 POST_NOTIFICATIONS，
        // 首次安装未授权时部分 ROM 直接拒绝前台服务启动→5秒超时杀进程（录屏闪退链源头）。
        // 启动即请求，配合 RecordingService.startAsForeground 失败 stopSelf 兑底。
        if (Build.VERSION.SDK_INT >= 33) {
            val perm = android.Manifest.permission.POST_NOTIFICATIONS
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                runCatching { requestPermissions(arrayOf(perm), 0x5e1c) }
            }
        }
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        com.selkies.client.platform.AndroidPlatform.setFocus(hasFocus)
    }

    override fun onResume() {
        super.onResume()
        com.selkies.client.platform.AndroidPlatform.setFocus(true)
        // v2.7.17：前台心跳（维持 CrashCapture alive.marker 新鲜度，硬死检测窗口依赖它）
        com.selkies.client.core.CrashCapture.heartbeat()
    }

    override fun onPause() {
        super.onPause()
        com.selkies.client.platform.AndroidPlatform.setFocus(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            // 用户主动结束应用：录屏先保存（「退出默认视为保存」）
            RecordingShutdownHook.autosave()
            // v2.7.17：正常退出标记（区分「用户退出」与「进程被杀」→ 硬死检测不误报）
            com.selkies.client.core.CrashCapture.markCleanExit()
            com.selkies.client.platform.AndroidPlatform.release()
        }
    }
}
