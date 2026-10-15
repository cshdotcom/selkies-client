package com.selkies.client.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selkies.client.core.Keysyms
import com.selkies.client.core.SelkiesSession
import com.selkies.client.model.StreamHealth
import kotlin.math.max

/**
 * 统计面板 v2.5.0：对齐官方 Sidebar StreamStats 全集——
 * 连接详情 + 四行状态 + 趋势图（FPS 双序列/带宽/RTT/解码/上屏，触摸十字线取值）+
 * 12 项官方 tile + 主机资源 + **上行/下行连接统计** + **客户端统计** + 分辨率
 */
@Composable
fun StatsPanel(session: SelkiesSession) {
    val stats by session.stats.collectAsState()
    val status by session.status.collectAsState()
    val encoder by session.currentEncoder.collectAsState()
    val realizedRes by session.realizedResolution.collectAsState()
    val streamHealth by session.streamHealth.collectAsState()
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

        // 官方四行健康状态（streamRows：encoder/capture/decoder/connection，1:1）
        streamHealth?.let { sh ->
            SectionCard("流健康状态（官方同款）") {
                HealthRow(
                    "编码器",
                    listOfNotNull(
                        sh.encoder.ifBlank { null },
                        sh.codec.uppercase().ifBlank { null }
                    ).joinToString(" · "),
                    when {
                        sh.hardware -> "good"
                        !sh.hardware && sh.hardwareExpected && sh.gpuPresent -> "warn"
                        else -> "neutral"
                    },
                    if (sh.hardware) sh.gpu else if (!sh.gpuPresent) "服务器无 GPU（普通部署）" else ""
                )
                HealthRow(
                    "采集",
                    listOfNotNull(
                        sh.backend.uppercase().ifBlank { null },
                        (if (sh.zeroCopy) "Zero-copy" else "Readback") +
                                (if (sh.capture.isNotBlank()) " (${sh.capture})" else "")
                    ).joinToString(" · "),
                    if (sh.zeroCopy) "good" else "neutral",
                    ""
                )
                HealthRow(
                    "解码器",
                    if (stats.decodeMs > 0) "硬件解码（本机）" else "检测中",
                    if (stats.decodeMs > 0) "good" else "neutral",
                    if (stats.decodeMs > 0) String.format(java.util.Locale.US, "%.1f ms/帧", stats.decodeMs) else ""
                )
                HealthRow(
                    "连接",
                    "WebSocket 流",
                    if (stats.throttled) "warn" else "good",
                    if (stats.throttled) "服务器正在限流（画面为省带宽被延后）" else if (stats.latencyMs > 0) "RTT ${stats.latencyMs} ms" else ""
                )
            }
        }

        SectionCard("帧率（本端呈现 / 服务器编码）") {
            InteractiveLineChart(
                series = listOf(
                    ChartSeries("本端", stats.fpsHistory, Color(0xFF4ADE80)),
                    ChartSeries("服务器", stats.encodedFpsHistory, Color(0xFF60A5FA))
                ),
                maxValue = max(
                    max(session.server.framerate.toDouble(), stats.fps.toDouble()),
                    stats.encodedFps
                ).coerceAtLeast(10.0),
                unit = "fps"
            )
        }

        SectionCard("视频带宽") {
            InteractiveLineChart(
                series = listOf(ChartSeries("Mbps", stats.mbpsHistory, Color(0xFFF59E0B))),
                maxValue = max(stats.bitrateMbps, stats.peakMbps).coerceAtLeast(1.0),
                unit = "Mbps"
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
            InteractiveLineChart(
                series = listOf(ChartSeries("ms", stats.rttHistory, Color(0xFFEF4444))),
                maxValue = stats.latencyMs.toDouble().coerceAtLeast(20.0),
                unit = "ms"
            )
            Text(
                if (stats.latencyMs > 0) "当前 ${stats.latencyMs} ms" else "等待服务器统计…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        SectionCard("客户端解码 / 上屏耗时") {
            InteractiveLineChart(
                series = listOf(
                    ChartSeries("解码 ms", stats.decodeHistory, Color(0xFF22D3EE)),
                    ChartSeries("上屏 ms", stats.presentHistory, Color(0xFFA78BFA))
                ),
                maxValue = max(stats.decodeMs, stats.presentMs).coerceAtLeast(10.0),
                unit = "ms"
            )
        }

        // 官方 12 项 tile（TRANSPORT_TILES.websockets 顺序）
        SectionCard("管线指标（官方同款 tile）") {
            val tiles = listOf(
                "编码" to stats.encodeMs,
                "管线" to stats.pipelineMs,
                "发送" to stats.sendMs,
                "解码" to stats.decodeMs,
                "上屏" to stats.presentMs,
                "音频缓冲" to stats.audioBufferMs.toDouble(),
                "码率 Mbps" to stats.bitrateMbps,
                "峰值 Mbps" to stats.peakMbps,
                "已收 MB" to stats.receivedMb,
                "丢帧" to stats.lostFrames.toDouble(),
                "未上屏帧" to stats.framesNotShown.toDouble(),
                "关键帧请求" to stats.keyframeRequests.toDouble()
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth().height(140.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
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

        SectionCard("下行统计（服务器 → 本机）") {
            StatRow("下行带宽", if (stats.mbpsClient > 0) String.format(java.util.Locale.US, "%.2f Mbps（含音频/控制）", stats.mbpsClient) else "—")
            StatRow("累计接收", formatCount(stats.bytesReceived))
            StatRow("视频帧", formatCount(stats.videoFrames))
            StatRow("丢帧（LOST_FRAME）", if (stats.lostFrames > 0) "${stats.lostFrames}" else "0")
            StatRow("未上屏帧", if (stats.framesNotShown > 0) "${stats.framesNotShown}" else "0")
            StatRow("传输模式", "WebSocket 流（本客户端原生模式）")
        }

        SectionCard("上行统计（本机 → 服务器）") {
            if (!stats.micActive && !stats.webcamActive) {
                Text(
                    "麦克风/摄像头未开启。开启后此处显示上行带宽与累计字节。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatRow(
                "麦克风",
                if (stats.micActive) "开启中 · ${String.format(java.util.Locale.US, "%.2f Mbps", stats.micUplinkMbps)}" else "关闭"
            )
            StatRow(
                "摄像头",
                if (stats.webcamActive) "开启中 · ${String.format(java.util.Locale.US, "%.2f Mbps", stats.webcamUplinkMbps)}" else "关闭"
            )
            StatRow("上行累计", if (stats.uplinkTotalBytes > 0) formatCount(stats.uplinkTotalBytes) else "—")
            if (stats.uplinkHistory.isNotEmpty()) {
                InteractiveLineChart(
                    series = listOf(ChartSeries("上行 Mbps", stats.uplinkHistory, Color(0xFF34D399))),
                    maxValue = stats.uplinkHistory.max().coerceAtLeast(0.5),
                    unit = "Mbps"
                )
            }
        }

        SectionCard("客户端统计（本机解码器）") {
            StatRow("解码器", "MediaCodec 硬解（H.264）/ FFmpeg")
            StatRow("音频缓冲", if (stats.audioBufferMs > 0) "${stats.audioBufferMs} ms" else "—")
            StatRow("解码耗时（EMA）", if (stats.decodeMs > 0) String.format(java.util.Locale.US, "%.1f ms", stats.decodeMs) else "—")
            StatRow("上屏耗时（EMA）", if (stats.presentMs > 0) String.format(java.util.Locale.US, "%.1f ms", stats.presentMs) else "—")
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

/** 官方四行健康状态行（good=绿✓ / warn=橙! / neutral=灰·） */
@Composable
private fun HealthRow(label: String, value: String, state: String, detail: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        val (color, icon) = when (state) {
            "good" -> Color(0xFF22C55E) to "✓"
            "warn" -> Color(0xFFF59E0B) to "!"
            else -> Color(0xFF94A3B8) to "·"
        }
        Text(
            icon,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.width(18.dp)
        )
        Column(Modifier.weight(1f)) {
            Row {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            }
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

data class ChartSeries(val label: String, val values: List<Double>, val color: Color)

/**
 * 交互式折线图（官方 stream-graph 体验）：
 * - 填充区 + 中线网格 + 当前值头部（多序列图例）
 * - 手指/鼠标按住 → 十字线 + 该时刻各序列取值（官方 onPointerMove 同款）
 * - 右下角最大刻度（graph-max 同款）
 */
@Composable
private fun InteractiveLineChart(series: List<ChartSeries>, maxValue: Double, unit: String) {
    var hoverIndex by remember { mutableStateOf<Int?>(null) }
    val count = series.firstOrNull()?.values?.size ?: 0

    Column(Modifier.fillMaxWidth()) {
        // 头部：图例 + 悬停时刻取值
        Row(
            Modifier.fillMaxWidth().padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            series.forEachIndexed { i, s ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 12.dp)
                ) {
                    Canvas(modifier = Modifier.size(8.dp)) { drawCircle(s.color) }
                    Spacer(Modifier.width(3.dp))
                    val v = if (hoverIndex != null && hoverIndex!! < s.values.size) s.values[hoverIndex!!]
                    else s.values.lastOrNull() ?: 0.0
                    Text(
                        "${s.label} ${String.format(java.util.Locale.US, "%.1f", v)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hoverIndex != null && series.size > 1 && i == 0) s.color.copy(alpha = 0.5f) else s.color,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(count) {
                        awaitEachGesture {
                            val down = awaitFirstDown(pass = PointerEventPass.Initial)
                            hoverIndex = idxForPos(down.position.x, size.width.toFloat(), count)
                            while (true) {
                                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                hoverIndex = idxForPos(change.position.x, size.width.toFloat(), count)
                            }
                            hoverIndex = null
                        }
                    }
            ) {
                val w = size.width
                val h = size.height
                val pad = 4.dp.toPx()
                val gridColor = Color.Gray.copy(alpha = 0.2f)
                // 中线（官方 stream-graph-grid）
                drawLine(gridColor, Offset(pad, h / 2), Offset(w - pad, h / 2), strokeWidth = 1f)
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
                // 十字线（官方 stream-graph-cross）
                val hi = hoverIndex
                if (hi != null && hi < count && count > 1) {
                    val step = (w - 2 * pad) / (count - 1).toFloat()
                    val x = pad + step * hi
                    drawLine(
                        Color.White.copy(alpha = 0.6f),
                        Offset(x, 0f), Offset(x, h),
                        strokeWidth = 1.5f
                    )
                }
            }
        }
        Text(
            "max ${String.format(java.util.Locale.US, "%.0f", maxValue)} $unit（按住图表查看历史值）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.End)
        )
    }
}

private fun idxForPos(x: Float, width: Float, count: Int): Int {
    if (count <= 1 || width <= 0f) return 0
    val step = width / (count - 1)
    return ((x / step).toInt()).coerceIn(0, count - 1)
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
