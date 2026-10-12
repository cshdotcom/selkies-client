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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            StatRow("编码器", encoder + if (stats.encoderDetail.isNotBlank()) "（${stats.encoderDetail}）" else "")
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
            // 自适应本机屏幕：用会话视口尺寸（连接前已由布局写入；竖屏自动换向保证横向桌面）
            val screenW = session.screenWidthPx
            val screenH = session.screenHeightPx
            OutlinedButton(onClick = {
                val w = maxOf(screenW, screenH)
                val h = minOf(screenW, screenH)
                session.sendResolution(w, h)
            }) { Text("适应本机屏幕（${screenW}×$screenH）") }
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
 * 系统输入法面板（重设计）：
 * - 打开面板直接唤起本机输入法（自动聚焦大文本框）
 * - 文字先缓冲在输入区，点「发送」一次性 co,end 提交到远程（用户要求：输入完成点发送才可以）
 * - 常用控制键（回车/Tab/Esc/退格/方向/复制粘贴）一键直发
 */
@Composable
fun ImeHostPanel(session: SelkiesSession) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    // 打开面板即唤起输入法（直接弹键盘）
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .focusRequester(focusRequester),
            placeholder = { Text("在此输入（支持中文等任意输入法），完成后点「发送」") },
            textStyle = TextStyle(fontSize = 16.sp)
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (text.isNotEmpty()) {
                        session.typeText(text)
                        text = ""
                    }
                },
                modifier = Modifier.weight(1.4f)
            ) { Text("发送到远程 ⏎") }
            OutlinedButton(
                onClick = { text = "" },
                modifier = Modifier.weight(0.6f)
            ) { Text("清空") }
        }
        Spacer(Modifier.height(10.dp))
        Text("控制键（直发）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            ImeQuickKey("⏎ 回车") { session.sendKey(Keysyms.XK_Return, true); session.sendKey(Keysyms.XK_Return, false) }
            ImeQuickKey("Tab") { session.sendKey(Keysyms.XK_Tab, true); session.sendKey(Keysyms.XK_Tab, false) }
            ImeQuickKey("Esc") { session.sendKey(Keysyms.XK_Escape, true); session.sendKey(Keysyms.XK_Escape, false) }
            ImeQuickKey("⌫") {
                if (text.isNotEmpty()) text = text.dropLast(1)
                else { session.sendKey(Keysyms.XK_BackSpace, true); session.sendKey(Keysyms.XK_BackSpace, false) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            ImeQuickKey("←") { session.sendKey(Keysyms.XK_Left, true); session.sendKey(Keysyms.XK_Left, false) }
            ImeQuickKey("↑") { session.sendKey(Keysyms.XK_Up, true); session.sendKey(Keysyms.XK_Up, false) }
            ImeQuickKey("↓") { session.sendKey(Keysyms.XK_Down, true); session.sendKey(Keysyms.XK_Down, false) }
            ImeQuickKey("→") { session.sendKey(Keysyms.XK_Right, true); session.sendKey(Keysyms.XK_Right, false) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            ImeQuickKey("Ctrl+C") { session.sendKeyChord(listOf(Keysyms.XK_Control_L, 'c'.code.toLong())) }
            ImeQuickKey("Ctrl+V") { session.sendKeyChord(listOf(Keysyms.XK_Control_L, 'v'.code.toLong())) }
            ImeQuickKey("Ctrl+A") { session.sendKeyChord(listOf(Keysyms.XK_Control_L, 'a'.code.toLong())) }
            ImeQuickKey("Ctrl+Z") { session.sendKeyChord(listOf(Keysyms.XK_Control_L, 'z'.code.toLong())) }
        }
    }
}

@Composable
private fun ImeQuickKey(label: String, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            fontSize = 13.sp
        )
    }
}
