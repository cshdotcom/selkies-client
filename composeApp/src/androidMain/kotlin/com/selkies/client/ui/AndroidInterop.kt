package com.selkies.client.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.selkies.client.core.SelkiesSession
import com.selkies.client.video.AndroidVideoSink

/**
 * Android 视频表面：SurfaceView + MediaCodec 直渲
 * ⚠️ Surface 生命周期与黑屏修复：
 *    应用退到后台时 SurfaceView 的 surface 会被销毁（解码器随 detachSurface 释放），
 *    回前台 surfaceCreated 重建后必须立刻向服务器请求关键帧——否则解码器只能拿到
 *    增量帧无法重建参考链，画面永久黑屏（用户报的“退出再打开就黑屏卡住”根因）。
 */
@Composable
actual fun VideoSurface(session: SelkiesSession, modifier: Modifier) {
    val sink = remember(session) { (session.videoSink as? AndroidVideoSink) ?: AndroidVideoSink().also { session.videoSink = it } }

    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.setKeepScreenOn(true)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        sink.attachSurface(holder.surface)
                        // 重建后强制关键帧（绕过会话层限流），恢复解码参考链
                        session.forceKeyframe()
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        sink.detachSurface()
                    }
                })
            }
        },
        modifier = modifier
    )

    DisposableEffect(session) {
        onDispose {
            session.videoSink?.let { runCatching { it.close() } }
            session.videoSink = null
        }
    }
}
