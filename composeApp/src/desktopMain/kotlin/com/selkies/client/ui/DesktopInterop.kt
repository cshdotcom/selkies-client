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

    Canvas(modifier = modifier) {
        drawRect(Color.Black)
        val bmp = bitmap ?: return@Canvas
        val scale = minOf(size.width / bmp.width, size.height / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val ox = (size.width - dw) / 2f
        val oy = (size.height - dh) / 2f
        drawImage(
            image = bmp,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bmp.width, bmp.height),
            dstOffset = IntOffset(ox.toInt(), oy.toInt()),
            dstSize = IntSize(dw.toInt(), dh.toInt())
        )
    }

    DisposableEffect(session) {
        onDispose {
            session.videoSink?.let { runCatching { it.close() } }
            session.videoSink = null
        }
    }
}

/** 桌面无软键盘：空实现 */
@Composable
actual fun ImeHost(
    visible: Boolean,
    onCommitText: (String) -> Unit,
    onEnter: () -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier
) {
    // 桌面平台由物理键盘直通完成输入
}
