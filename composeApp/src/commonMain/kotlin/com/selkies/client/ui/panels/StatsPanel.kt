package com.selkies.client.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selkies.client.core.Keysyms
import com.selkies.client.core.SelkiesSession
import kotlin.math.max

/**
 * 统计面板 v2.4.0：连接详情 + 实时图表（FPS 双序列/带宽/延迟）+ 服务器管线 + 主机资源 + 分辨率
 */
@Composable
fun StatsPanel(session: SelkiesSession) {
    val stats by session.stats.collectAsState()
    val status by session.status.collectAsState()
    val encoder by session.currentEncoder.collectAsState()
    val realizedRes by session.realizedResolution.collectAsState()
    var resW by remember { mutableStateOf("") }
    var resH by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val connectAt = remember { System.currentTimeMillis() }

    // 连接时长每秒刷新
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val durationSec = max(0, (now - connectAt) / 1000)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionCard("连接详情") {
            StatRow("服务器", session.server.baseUrl())
            StatRow("已连接时长", formatDuration(durationSec))
            StatRow("传输模式", "WebSocket（本客户端原生模式）")
            StatRow("角色", if (session.role.value == "viewer") "查看者（只读）" else "控制者")
            StatRow("分辨率", stats.resolution.ifBlank { realizedRes?.let { "${it.first}x${it.second}" } ?: "—" })
            StatRow("编码器", encoder + if (stats.encoderDetail.isNotBlank()) "（${stats.encoderDetail}）" else "")
            StatRow("状态", when (status) {
                SelkiesSession.Status.CONNECTED -> "已连接"
                SelkiesSession.Status.RECONNECTING -> "重连中"
                SelkiesSession.Status.FAILED -> "失败"
                SelkiesSession.Status.CLOSED -> "已关闭"
                else -> "连接中"
            })
        }

        SectionCard("帧率（本端呈现 / 服务器编码）") {
            LineChart(
                series = listOf(
                    ChartSeries("本端", stats.fpsHistory, Color(0xFF4ADE80)),
                    ChartSeries("服务器", stats.encodedFpsHistory, Color(0xFF60A5FA))
                ),
                maxValue = max(
                    max(session.server.framerate.toDouble(), stats.fps.toDouble()),
                    stats.encodedFps
                ).coerceAtLeast(10.0)
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                ChartLegend("本端 ${stats.fps}", Color(0xFF4ADE80))
                Spacer(Modifier.width(16.dp))
                ChartLegend("服务器 ${stats.encodedFps.toInt()}", Color(0xFF60A5FA))
            }
        }

        SectionCard("视频带宽") {
            LineChart(
                series = listOf(ChartSeries("Mbps", stats.mbpsHistory, Color(0xFFF59E0B))),
                maxValue = max(stats.bitrateMbps, stats.peakMbps).coerceAtLeast(1.0)
            )
            Text(
                String.format(java.util.Locale.US, "当前 %.1f Mbps · 峰值 %.1f Mbps", stats.bitrateMbps, stats.peakMbps) +
                        (if (stats.targetMbps > 0) String.format(java.util.Locale.US, " · 目标 %.1f", stats.targetMbps) else "") +
                        (if (stats.throttled) " · 服务器限流中" else ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        SectionCard("往返延迟（RTT）") {
            LineChart(
                series = listOf(ChartSeries("ms", stats.rttHistory, Color(0xFFEF4444))),
                maxValue = stats.latencyMs.toDouble().coerceAtLeast(20.0)
            )
            Text(
                if (stats.latencyMs > 0) "当前 ${stats.latencyMs} ms" else "等待服务器统计…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        SectionCard("服务器管线") {
            val tiles = listOf(
                "编码 ms" to stats.encodeMs,
                "管线 ms" to stats.pipelineMs,
                "发送 ms" to stats.sendMs,
                "最大帧 KB" to stats.frameMaxKb
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth().height(64.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(tiles) { (label, v) ->
                    StatTile(label, if (v > 0) String.format(java.util.Locale.US, "%.1f", v) else "—")
                }
            }
            if (stats.pipelineMaxMs > 0) {
                Text(
                    String.format(java.util.Locale.US, "管线峰值 %.1f ms · 发送峰值 %.1f ms", stats.pipelineMaxMs, stats.sendMaxMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionCard("远程主机资源") {
            BarRow("CPU", stats.cpuPercent)
            BarRow("GPU", stats.gpuPercent)
            StatRow("内存", if (stats.memTotal > 0) String.format(java.util.Locale.US, "%.1f / %.1f GB", stats.memUsed / 1e9, stats.memTotal / 1e9) else "—")
            StatRow("显存", if (stats.gpuMemTotal > 0) String.format(java.util.Locale.US, "%.1f / %.1f GB", stats.gpuMemUsed / 1e9, stats.gpuMemTotal / 1e9) else "—")
        }

        SectionCard("传输统计") {
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

data class ChartSeries(val label: String, val values: List<Double>, val color: Color)

/** 折线图（官方 stream-stats 同款视觉：填充区 + 网格线） */
@Composable
private fun LineChart(series: List<ChartSeries>, maxValue: Double) {
    Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
        val w = size.width
        val h = size.height
        val pad = 4.dp.toPx()
        // 网格
        val gridColor = Color.Gray.copy(alpha = 0.2f)
        for (i in 1..3) {
            val y = pad + (h - 2 * pad) * i / 4
            drawLine(gridColor, Offset(pad, y), Offset(w - pad, y), strokeWidth = 1f)
        }
        val maxV = maxValue.toFloat().coerceAtLeast(0.1f)
        for (s in series) {
            if (s.values.size < 2) continue
            val step = (w - 2 * pad) / (s.values.size - 1).coerceAtLeast(1).toFloat()
            val path = Path()
            val fill = Path()
            s.values.forEachIndexed { i, v ->
                val x = pad + step * i
                val y = (h - pad) - ((v / maxV).toFloat().coerceIn(0f, 1f) * (h - 2 * pad))
                if (i == 0) {
                    path.moveTo(x, y); fill.moveTo(x, h - pad); fill.lineTo(x, y)
                } else {
                    path.lineTo(x, y); fill.lineTo(x, y)
                }
            }
            fill.lineTo(pad + step * (s.values.size - 1), h - pad)
            fill.close()
            drawPath(fill, s.color.copy(alpha = 0.12f))
            drawPath(path, s.color, style = Stroke(width = 2.5f))
        }
    }
}

@Composable
private fun ChartLegend(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.width(10.dp).height(10.dp)) {
            drawCircle(color)
        }
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun StatTile(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BarRow(label: String, percent: Double) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(
                if (percent > 0) String.format(java.util.Locale.US, "%.0f%%", percent) else "—",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium
            )
        }
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().height(6.dp),
                shape = RoundedCornerShape(3.dp)
            ) {}
            if (percent > 0) {
                Surface(
                    color = when {
                        percent > 85 -> MaterialTheme.colorScheme.error
                        percent > 60 -> Color(0xFFF59E0B)
                        else -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.fillMaxWidth(percent.coerceIn(0.0, 100.0).toFloat() / 100f).height(6.dp),
                    shape = RoundedCornerShape(3.dp)
                ) {}
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        m > 0 -> String.format(java.util.Locale.US, "%d:%02d", m, s)
        else -> "${s}s"
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
