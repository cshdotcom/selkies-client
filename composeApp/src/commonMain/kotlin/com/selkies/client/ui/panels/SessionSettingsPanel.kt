package com.selkies.client.ui.panels

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.selkies.client.AppState
import com.selkies.client.core.SelkiesSession
import com.selkies.client.model.ServerEntry
import com.selkies.client.model.VideoPresets
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 会话内画质与性能面板：预设 + 全部官方视频/音频参数（SETTINGS 热更新）
 * 服务器锁定（locked）的参数自动禁用。
 */
@Composable
fun SessionSettingsPanel(state: AppState, session: SelkiesSession) {
    val server = session.server
    val serverSettings by session.serverSettings.collectAsState()

    // 是否被服务器锁定
    fun locked(key: String): Boolean {
        val el = serverSettings[key] as? kotlinx.serialization.json.JsonObject ?: return false
        return (el["locked"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
    }

    var preset by remember { mutableStateOf(server.preset) }
    var encoder by remember { mutableStateOf(server.encoder) }
    var useCrf by remember { mutableStateOf(server.videoCrf in 1..51) }
    var bitrate by remember { mutableFloatStateOf(server.videoBitrate.toFloat()) }
    var crf by remember { mutableFloatStateOf(server.videoCrf.toFloat()) }
    var fps by remember { mutableFloatStateOf(server.framerate.toFloat()) }
    var keyframeInt by remember { mutableFloatStateOf(server.keyframeInterval.toFloat()) }
    var fullcolor by remember { mutableStateOf(server.videoFullcolor) }
    var tenbit by remember { mutableStateOf(server.video10bit) }
    var turbo by remember { mutableStateOf(server.turboMode) }
    var useCpu by remember { mutableStateOf(server.useCpu) }
    var paintOver by remember { mutableStateOf(server.usePaintOverQuality) }
    var paintOverCrf by remember { mutableFloatStateOf(server.paintOverCrf.toFloat()) }
    var jpegQ by remember { mutableFloatStateOf(server.jpegQuality.toFloat()) }
    var audioBr by remember { mutableFloatStateOf(server.audioBitrate.toFloat() / 1000f) }
    var dpi by remember { mutableFloatStateOf(server.scalingDpi.toFloat()) }

    /** 应用并持久化 */
    fun apply(patch: Map<String, Any>, mutate: ServerEntry.() -> Unit) {
        server.apply(mutate)
        session.applySettings(patch)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ---- 预设 ----
        SectionCard("质量预设") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VideoPresets.ALL.forEach { p ->
                    FilterChip(
                        selected = preset == p.key,
                        onClick = {
                            preset = p.key
                            if (p.key != "custom") {
                                VideoPresets.apply(server, p.key)
                                encoder = server.encoder
                                bitrate = server.videoBitrate.toFloat()
                                crf = server.videoCrf.toFloat()
                                fps = server.framerate.toFloat()
                                useCrf = server.videoCrf in 1..51
                                fullcolor = server.videoFullcolor
                                tenbit = server.video10bit
                                turbo = server.turboMode
                                // 整包重发（encoder 等需重启管线，服务器自行处理）
                                session.applySettings(
                                    mapOf(
                                        "encoder" to server.encoder,
                                        "framerate" to server.framerate,
                                        "video_bitrate" to server.videoBitrate,
                                        "video_crf" to server.videoCrf,
                                        "rate_control_mode" to (if (server.videoCrf in 1..51) "crf" else "cbr"),
                                        "video_fullcolor" to server.videoFullcolor,
                                        "video_10bit" to server.video10bit,
                                        "video_streaming_mode" to server.turboMode,
                                        "jpeg_quality" to server.jpegQuality
                                    )
                                )
                            }
                        },
                        label = { Text(p.label) }
                    )
                }
            }
            Text(
                VideoPresets.ALL.firstOrNull { it.key == preset }?.desc ?: "手动微调下方所有参数",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ---- 编码器 ----
        SectionCard("视频编码") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "h264enc" to "H.264",
                    "h265enc" to "H.265",
                    "vp8enc" to "VP8",
                    "vp9enc" to "VP9",
                    "av1enc" to "AV1",
                    "jpeg" to "JPEG"
                ).forEach { (enc, label) ->
                    FilterChip(
                        selected = encoder == enc,
                        onClick = {
                            encoder = enc
                            apply(mapOf("encoder" to enc)) { this.encoder = enc }
                        },
                        label = { Text(label) },
                        enabled = !locked("encoder")
                    )
                }
            }
            Text(
                "切换编码器会重启服务器捕获管线（约 1 秒黑屏后自动恢复）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ---- 码率 / 质量 ----
        SectionCard(if (useCrf) "质量（CRF 模式）" else "码率（CBR 模式）") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CBR 码率", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Switch(checked = !useCrf, onCheckedChange = { v ->
                    useCrf = !v
                    apply(mapOf("rate_control_mode" to if (v) "cbr" else "crf")) { }
                }, enabled = !locked("rate_control_mode"))
                Spacer(Modifier.width(8.dp))
                Text("CRF 质量", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Switch(checked = useCrf, onCheckedChange = { v ->
                    useCrf = v
                    apply(mapOf("rate_control_mode" to if (v) "crf" else "cbr")) { }
                }, enabled = !locked("rate_control_mode"))
            }
            if (useCrf) {
                Slider(
                    value = crf, onValueChange = { crf = it },
                    onValueChangeFinished = {
                        server.videoCrf = crf.roundToInt()
                        session.applySettings(mapOf("video_crf" to server.videoCrf))
                    },
                    valueRange = 5f..50f,
                    enabled = !locked("video_crf")
                )
                Text("CRF ${crf.roundToInt()}（越小质量越高；18≈近无损，30≈省流量）", style = MaterialTheme.typography.labelSmall)
            } else {
                Slider(
                    value = bitrate, onValueChange = { bitrate = it },
                    onValueChangeFinished = {
                        server.videoBitrate = bitrate.roundToInt()
                        session.sendVideoBitrate(server.videoBitrate)
                    },
                    valueRange = 500f..50000f,
                    enabled = !locked("video_bitrate")
                )
                Text(String.format(Locale.US, "%d kbps（%.1f Mbps）", bitrate.roundToInt(), bitrate / 1000.0), style = MaterialTheme.typography.labelSmall)
            }
        }

        // ---- 帧率 / GOP ----
        SectionCard("帧率与关键帧") {
            Text("帧率 ${fps.roundToInt()} fps", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = fps, onValueChange = { fps = it },
                onValueChangeFinished = {
                    server.framerate = fps.roundToInt().coerceIn(8, 240)
                    session.sendFramerate(server.framerate)
                },
                valueRange = 8f..240f,
                enabled = !locked("framerate")
            )
            Text(
                String.format(Locale.US, "关键帧间隔 %.0f 秒（0 = 无限 GOP，仅按需关键帧，最省带宽）", keyframeInt),
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = keyframeInt, onValueChange = { keyframeInt = it },
                onValueChangeFinished = {
                    server.keyframeInterval = keyframeInt.toDouble()
                    session.applySettings(mapOf("keyframe_interval" to keyframeInt))
                },
                valueRange = 0f..300f
            )
        }

        // ---- 高级开关 ----
        SectionCard("高级编码选项") {
            SwitchRow(
                "Turbo 模式（逐帧编码，画面变化最流畅）",
                "关闭后仅编码变化区域（damage-gated），带宽更低",
                turbo, !locked("video_streaming_mode")
            ) { v ->
                turbo = v
                apply(mapOf("video_streaming_mode" to v)) { turboMode = v }
            }
            SwitchRow(
                "4:4:4 全色度（文字最锐利）",
                "代码/文档场景推荐；需编码器支持，切换会重启管线",
                fullcolor, !locked("video_fullcolor")
            ) { v ->
                fullcolor = v
                apply(mapOf("video_fullcolor" to v)) { videoFullcolor = v }
            }
            SwitchRow(
                "10bit 色深（平滑渐变）",
                "照片/视频色彩更准；带宽略增，切换会重启管线",
                tenbit, !locked("video_10bit")
            ) { v ->
                tenbit = v
                apply(mapOf("video_10bit" to v)) { video10bit = v }
            }
            SwitchRow(
                "强制软编码（CPU）",
                "无 GPU/驱动异常时兜底；占用远程 CPU",
                useCpu, !locked("use_cpu")
            ) { v ->
                useCpu = v
                apply(mapOf("use_cpu" to v)) { this.useCpu = v }
            }
            SwitchRow(
                "静止画面质量清理（paint-over）",
                "画面静止时用更高质量刷新文字区域",
                paintOver, !locked("use_paint_over_quality")
            ) { v ->
                paintOver = v
                apply(mapOf("use_paint_over_quality" to v)) { usePaintOverQuality = v }
            }
            if (paintOver) {
                Text("paint-over CRF ${paintOverCrf.roundToInt()}（静止画面刷新质量）", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = paintOverCrf, onValueChange = { paintOverCrf = it },
                    onValueChangeFinished = {
                        server.paintOverCrf = paintOverCrf.roundToInt()
                        session.applySettings(mapOf("video_paintover_crf" to server.paintOverCrf))
                    },
                    valueRange = 5f..50f
                )
            }
            if (encoder == "jpeg") {
                Text("JPEG 质量 ${jpegQ.roundToInt()}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = jpegQ, onValueChange = { jpegQ = it },
                    onValueChangeFinished = {
                        server.jpegQuality = jpegQ.roundToInt()
                        session.applySettings(mapOf("jpeg_quality" to server.jpegQuality))
                    },
                    valueRange = 10f..100f
                )
            }
        }

        // ---- 音频 ----
        SectionCard("音频") {
            Text(
                String.format(Locale.US, "音频码率 %.0f kbps", audioBr),
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = audioBr, onValueChange = { audioBr = it },
                onValueChangeFinished = {
                    server.audioBitrate = audioBr.roundToInt() * 1000
                    session.sendAudioBitrate(server.audioBitrate)
                },
                valueRange = 32f..512f,
                enabled = !locked("audio_bitrate")
            )
        }

        // ---- DPI ----
        SectionCard("远程界面缩放（DPI）") {
            Text("scaling_dpi ${dpi.roundToInt()}", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = dpi, onValueChange = { dpi = it },
                onValueChangeFinished = {
                    server.scalingDpi = (dpi.roundToInt() / 24) * 24
                    session.applySettings(mapOf("scaling_dpi" to server.scalingDpi))
                },
                valueRange = 96f..288f,
                enabled = !locked("scaling_dpi")
            )
            Text(
                "值越大远程界面元素越大（适合手机竖屏）；配合「统计」面板的分辨率适配使用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
