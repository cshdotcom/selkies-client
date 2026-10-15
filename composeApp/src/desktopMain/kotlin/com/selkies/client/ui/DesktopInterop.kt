package com.selkies.client.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.selkies.client.core.SelkiesSession
import com.selkies.client.video.DesktopVideoSink

/**
 * 桌面视频表面：观察解码位图并等比绘制
 */
@Composable
actual fun VideoSurface(session: SelkiesSession, modifier: Modifier) {
    val sink = remember(session) {
        (session.videoSink as? DesktopVideoSink) ?: DesktopVideoSink().also { session.videoSink = it }
    }
    val bitmap by sink.bitmapFlow.collectAsState()

    // v2.6.0 修复：注册 sink 供平台截图使用（旧版从未注册 = 截图恒失败的根因）
    androidx.compose.runtime.SideEffect {
        com.selkies.client.platform.DesktopPlatform.registerVideoSink(sink)
    }

    Canvas(modifier = modifier) {
        drawRect(Color.Black)
        val bmp = bitmap ?: return@Canvas
        val scale = minOf(size.width / bmp.width, size.height / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val ox = (size.width - dw) / 2f
        val oy = (size.height - dh) / 2f
        // ⚠️【防崩防线】绘制异常（位图损坏/驱动异常）绝不沿 EDT 上抛杀死整个应用——
        //    旧版未捕获的 RuntimeException 直接终止进程（用户见到的“点截图/看视频就闪退”）。
        //    丢一帧远好于丢整个会话；下一帧到来自动恢复。
        runCatching {
            drawImage(
                image = bmp,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(bmp.width, bmp.height),
                dstOffset = IntOffset(ox.toInt(), oy.toInt()),
                dstSize = IntSize(dw.toInt(), dh.toInt())
            )
        }
    }

    DisposableEffect(session) {
        onDispose {
            com.selkies.client.platform.DesktopPlatform.registerVideoSink(null)
            session.videoSink?.let { runCatching { it.close() } }
            session.videoSink = null
        }
    }
}

