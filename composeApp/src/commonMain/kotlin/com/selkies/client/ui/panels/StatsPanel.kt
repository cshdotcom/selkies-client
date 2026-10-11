package com.selkies.client.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.selkies.client.core.Keysyms
import com.selkies.client.core.SelkiesSession

/**
 * 统计面板：连接/视频/音频信息 + 分辨率调整 + 关键帧请求
 */
@Composable
fun StatsPanel(session: SelkiesSession) {
    val stats by session.stats.collectAsState()
    val status by session.status.collectAsState()
    val encoder by session.currentEncoder.collectAsState()
    var resW by remember { mutableStateOf("") }
    var resH by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionCard("会话统计") {
            StatRow("状态", when (status) {
                SelkiesSession.Status.CONNECTED -> "已连接"
                SelkiesSession.Status.RECONNECTING -> "重连中"
                SelkiesSession.Status.FAILED -> "失败"
                SelkiesSession.Status.CLOSED -> "已关闭"
                else -> "连接中"
            })
            StatRow("分辨率", stats.resolution.ifBlank { "—" })
            StatRow("编码器", encoder)
            StatRow("帧率", if (stats.fps > 0) "${stats.fps} fps" else "—")
            StatRow("延迟", if (stats.latencyMs > 0) "${stats.latencyMs} ms" else "—")
            StatRow("码率", if (stats.bitrateMbps > 0) String.format(java.util.Locale.US, "%.1f Mbps", stats.bitrateMbps) else "—")
            StatRow("已接收", formatCount(stats.bytesReceived))
            StatRow("视频帧", formatCount(stats.videoFrames))
        }

        SectionCard("分辨率设置") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = resW, onValueChange = { resW = it.filter { c -> c.isDigit() } },
                    label = { Text("宽") }, modifier = Modifier.weight(1f), singleLine = true
                )
                OutlinedTextField(
                    value = resH, onValueChange = { resH = it.filter { c -> c.isDigit() } },
                    label = { Text("高") }, modifier = Modifier.weight(1f), singleLine = true
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val w = resW.toIntOrNull() ?: return@OutlinedButton
                    val h = resH.toIntOrNull() ?: return@OutlinedButton
                    session.sendResolution(w, h)
                }) { Text("应用分辨率") }
                OutlinedButton(onClick = { session.requestKeyframe() }) { Text("请求关键帧") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("1280x720" to (1280 to 720), "1920x1080" to (1920 to 1080), "2560x1440" to (2560 to 1440)).forEach { (label, wh) ->
                    OutlinedButton(onClick = { session.sendResolution(wh.first, wh.second) }) { Text(label) }
                }
            }
        }

        SectionCard("应用快捷入口") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    session.sendKeyChord(listOf(Keysyms.XK_Control_L, Keysyms.XK_Alt_L, 't'.code.toLong()))
                }) { Text("终端") }
                OutlinedButton(onClick = {
                    session.sendKeyChord(listOf(Keysyms.XK_Alt_L, Keysyms.XK_F4))
                }) { Text("关闭窗口") }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun formatCount(n: Long): String = when {
    n >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f GB", n / 1073741824.0)
    n >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0)
    n >= 1L shl 10 -> String.format(java.util.Locale.US, "%.1f KB", n / 1024.0)
    else -> "$n B"
}

/**
 * 键盘面板（会话内）：文本框逐键实时发送（追加=打字，删除=退格），支持输入法
 */
@Composable
fun ImeHostPanel(session: SelkiesSession) {
    var text by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { newText ->
                val old = text
                when {
                    newText.length > old.length && newText.startsWith(old) -> {
                        // 追加：逐字实时发送（支持输入法组合后的提交）
                        session.typeText(newText.substring(old.length))
                    }
                    newText.length < old.length && old.startsWith(newText) -> {
                        repeat(old.length - newText.length) {
                            session.sendKey(Keysyms.XK_BackSpace, true)
                            session.sendKey(Keysyms.XK_BackSpace, false)
                        }
                    }
                    else -> {
                        // 整体替换
                        repeat(old.length) {
                            session.sendKey(Keysyms.XK_BackSpace, true)
                            session.sendKey(Keysyms.XK_BackSpace, false)
                        }
                        if (newText.isNotEmpty()) session.typeText(newText)
                    }
                }
                text = newText
            },
            modifier = Modifier.fillMaxWidth().height(110.dp),
            placeholder = { Text("在此输入，逐键实时发送到远程（支持中文输入法）") }
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                session.sendKey(Keysyms.XK_Return, true); session.sendKey(Keysyms.XK_Return, false)
            }) { Text("回车") }
            OutlinedButton(onClick = {
                session.sendKey(Keysyms.XK_BackSpace, true); session.sendKey(Keysyms.XK_BackSpace, false)
            }) { Text("退格") }
            OutlinedButton(onClick = {
                session.sendKey(Keysyms.XK_Tab, true); session.sendKey(Keysyms.XK_Tab, false)
            }) { Text("Tab") }
            OutlinedButton(onClick = {
                session.sendKey(Keysyms.XK_Escape, true); session.sendKey(Keysyms.XK_Escape, false)
            }) { Text("Esc") }
        }
    }
}
