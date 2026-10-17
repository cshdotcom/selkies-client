package com.selkies.client.ui

import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.selkies.client.core.SelkiesSession
import com.selkies.client.video.AndroidVideoSink

/**
 * Android 视频表面：TextureView + MediaCodec 直渲
 * 【v2.6.0】SurfaceView → TextureView：
 *  - SurfaceView 是独立原生 surface，不参与 Compose graphicsLayer 变换 → 本地缩放/放大镜无法生效；
 *    TextureView 是普通合成层，缩放/平移变换直接可用。
 *  - 截图可用 textureView.getBitmap()（比 PixelCopy 兼容性更好）。
 *  - 生命周期语义与 SurfaceView 相同（surface 销毁→释放解码器，重建→强制关键帧）。
 * ⚠️ 黑屏修复语义保留：
 *    应用退到后台时 surface 会被销毁（解码器随 detachSurface 释放），
 *    回前台 surfaceCreated 重建后必须立刻向服务器请求关键帧——否则解码器只能拿到
 *    增量帧无法重建参考链，画面永久黑屏。
 */
@Composable
actual fun VideoSurface(session: SelkiesSession, modifier: Modifier) {
    val sink = remember(session) { (session.videoSink as? AndroidVideoSink) ?: AndroidVideoSink().also { session.videoSink = it } }

    // 【v2.7.6】解码器错误/不可用 → 会话通知（无解码器时告知用户切换编码，不再静默黑屏）
    remember(session) {
        sink.onDecodeError = { msg -> session.notices.value = msg }
        true
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, width: Int, height: Int) {
                        sink.attachSurface(Surface(st))
                        // 重建后强制关键帧（绕过会话层限流），恢复解码参考链
                        session.forceKeyframe()
                    }

                    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, width: Int, height: Int) {}

                    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                        sink.detachSurface()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                }
                // 记录到平台层（截图 getBitmap 直取）
                com.selkies.client.platform.AndroidPlatform.setVideoTextureView(this)
            }
        },
        modifier = modifier
    )

    DisposableEffect(session) {
        onDispose {
            com.selkies.client.platform.AndroidPlatform.setVideoTextureView(null)
            session.videoSink?.let { runCatching { it.close() } }
            session.videoSink = null
        }
    }
}
