package com.selkies.client.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.selkies.client.AppState
import com.selkies.client.Screen
import com.selkies.client.core.SelkiesConnection
import com.selkies.client.model.ServerEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 添加/编辑服务器 + 连接测试
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerScreen(state: AppState, serverId: String?) {
    val existing = state.store.servers.value.firstOrNull { it.id == serverId }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var viewOnly by remember { mutableStateOf(existing?.viewOnly ?: false) }
    var allowUntrusted by remember { mutableStateOf(existing?.allowUntrustedCert ?: false) }
    var useToken by remember { mutableStateOf(existing?.useTokenAuth ?: false) }
    var token by remember { mutableStateOf(existing?.token ?: "") }
    var framerate by remember { mutableFloatStateOf((existing?.framerate ?: 60).toFloat()) }
    var videoBitrate by remember { mutableFloatStateOf((existing?.videoBitrate ?: 8000).toFloat()) }
    var useCrf by remember { mutableStateOf((existing?.videoCrf ?: 0) > 0) }
    var videoCrf by remember { mutableFloatStateOf((existing?.videoCrf ?: 24).toFloat()) }
    var encoder by remember { mutableStateOf(existing?.encoder ?: "h264enc") }
    var audioEnabled by remember { mutableStateOf(existing?.audioEnabled ?: true) }
    var displayId by remember { mutableStateOf(existing?.displayId ?: "primary") }
    // ---- 每连接分辨率（0=自适应本机屏幕）----
    var resW by remember { mutableStateOf((existing?.resolutionW ?: 0).coerceAtLeast(0)) }
    var resH by remember { mutableStateOf((existing?.resolutionH ?: 0).coerceAtLeast(0)) }
    var customRes by remember { mutableStateOf(false) }

    // ---- 官方前端对齐的视频高级设置 ----
    var showAdvanced by remember { mutableStateOf(false) }
    var jpegQuality by remember { mutableFloatStateOf((existing?.jpegQuality ?: 0).toFloat()) }
    var videoFullcolor by remember { mutableStateOf(existing?.videoFullcolor ?: false) }
    var useCpu by remember { mutableStateOf(existing?.useCpu ?: false) }
    var forceAligned by remember { mutableStateOf(existing?.forceAlignedResolution ?: false) }
    var streamingMode by remember { mutableStateOf(existing?.videoStreamingMode ?: "") }
    var paintOverCrf by remember { mutableFloatStateOf((existing?.paintOverCrf ?: 0).toFloat()) }
    var paintOverBurst by remember { mutableFloatStateOf((existing?.paintOverBurstFrames ?: 0).toFloat()) }
    var paintOverJpeg by remember { mutableFloatStateOf((existing?.paintOverJpegQuality ?: 0).toFloat()) }
    var usePaintOverQuality by remember { mutableStateOf(existing?.usePaintOverQuality ?: false) }

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    com.selkies.client.ui.PlatformBackHandler { state.navigate(Screen.ServerList) }

    val encoders = listOf(
        "h264enc" to "H.264 硬编（默认）",
        "h264enc-striped" to "H.264 条带",
        "h265enc" to "H.265/HEVC",
        "vp8enc" to "VP8",
        "vp9enc" to "VP9",
        "av1enc" to "AV1",
        "jpeg" to "JPEG（兼容模式）"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "添加服务器" else "编辑服务器") },
                navigationIcon = {
                    IconButton(onClick = { state.navigate(Screen.ServerList) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionTitle("基本信息")
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") },
                modifier = Modifier.fillMaxWidth(), placeholder = { Text("我的 Webtop") })
            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("服务器地址") },
                modifier = Modifier.fillMaxWidth(), placeholder = { Text("https://example.com 或 1.2.3.4:6900") })

            SectionTitle("认证")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = useToken, onCheckedChange = { useToken = it })
                Text("使用令牌（Token）认证")
            }
            if (useToken) {
                OutlinedTextField(value = token, onValueChange = { token = it },
                    label = { Text("会话令牌") }, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation())
                Text("提示：令牌模式下按角色连接（controller/viewer）。", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                OutlinedTextField(value = username, onValueChange = { username = it },
                    label = { Text("用户名（Basic Auth）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = password, onValueChange = { password = it },
                    label = { Text("密码") }, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = allowUntrusted, onCheckedChange = { allowUntrusted = it })
                Text("允许不受信任的 TLS 证书")
            }
            Text("自签名证书或内网证书需开启此项；公网正规证书请保持关闭。",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionTitle("会话选项")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = viewOnly, onCheckedChange = { viewOnly = it })
                Text("仅查看（View-only）")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = audioEnabled, onCheckedChange = { audioEnabled = it })
                Text("启用音频")
            }
            Text("显示器：", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = displayId == "primary", onClick = { displayId = "primary" }, label = { Text("主屏") })
            }

            SectionTitle("视频质量")
            Text("分辨率（每个服务器可独立设置）：", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilterChip(
                    selected = !customRes && resW == 0,
                    onClick = { customRes = false; resW = 0; resH = 0 },
                    label = { Text("自适应屏幕") }
                )
                FilterChip(
                    selected = !customRes && resW == 1920,
                    onClick = { customRes = false; resW = 1920; resH = 1080 },
                    label = { Text("1920×1080") }
                )
                FilterChip(
                    selected = !customRes && resW == 1280,
                    onClick = { customRes = false; resW = 1280; resH = 720 },
                    label = { Text("1280×720") }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilterChip(
                    selected = !customRes && resW == 2560,
                    onClick = { customRes = false; resW = 2560; resH = 1440 },
                    label = { Text("2560×1440") }
                )
                FilterChip(
                    selected = customRes || (resW !in listOf(0, 1280, 1920, 2560)),
                    onClick = { customRes = true; if (resW == 0) { resW = 1600; resH = 900 } },
                    label = { Text("自定义") }
                )
            }
            if (customRes) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = if (resW > 0) resW.toString() else "",
                        onValueChange = { resW = it.filter { c -> c.isDigit() }.take(4).toIntOrNull() ?: 0 },
                        label = { Text("宽") }, modifier = Modifier.weight(1f), singleLine = true
                    )
                    OutlinedTextField(
                        value = if (resH > 0) resH.toString() else "",
                        onValueChange = { resH = it.filter { c -> c.isDigit() }.take(4).toIntOrNull() ?: 0 },
                        label = { Text("高") }, modifier = Modifier.weight(1f), singleLine = true
                    )
                }
            }
            Text(
                if (resW > 0 && resH > 0) "连接时固定请求 ${resW}×${resH}" else "连接时按本机屏幕自动适配（竖屏自动转横向）",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("帧率：${framerate.toInt()} fps", style = MaterialTheme.typography.bodyMedium)
            Slider(value = framerate, onValueChange = { framerate = it }, valueRange = 15f..144f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = useCrf, onCheckedChange = { useCrf = it })
                Text("使用 CRF 恒定质量模式")
            }
            if (useCrf) {
                Text("CRF：${videoCrf.toInt()}（越小画质越高，建议 18-28）", style = MaterialTheme.typography.bodyMedium)
                Slider(value = videoCrf, onValueChange = { videoCrf = it }, valueRange = 14f..40f)
            } else {
                Text("码率：${videoBitrate.toInt().coerceAtLeast(500)} kbps", style = MaterialTheme.typography.bodyMedium)
                Slider(value = videoBitrate, onValueChange = { videoBitrate = it }, valueRange = 1000f..50000f)
            }
            Text("编码器：", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilterChip(selected = encoder == "h264enc", onClick = { encoder = "h264enc" }, label = { Text("H.264") })
                FilterChip(selected = encoder == "h264enc-striped", onClick = { encoder = "h264enc-striped" }, label = { Text("H.264条带") })
                FilterChip(selected = encoder == "h265enc", onClick = { encoder = "h265enc" }, label = { Text("H.265") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilterChip(selected = encoder == "vp8enc", onClick = { encoder = "vp8enc" }, label = { Text("VP8") })
                FilterChip(selected = encoder == "vp9enc", onClick = { encoder = "vp9enc" }, label = { Text("VP9") })
                FilterChip(selected = encoder == "av1enc", onClick = { encoder = "av1enc" }, label = { Text("AV1") })
                FilterChip(selected = encoder == "jpeg", onClick = { encoder = "jpeg" }, label = { Text("JPEG") })
            }

            // ---- 视频高级设置（对齐官方设置面板）----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced }
            ) {
                Text(
                    if (showAdvanced) "▾ 视频高级设置（对齐官方）" else "▸ 视频高级设置（对齐官方）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (showAdvanced) {
                Text("JPEG 质量：${if (jpegQuality < 1) "默认" else jpegQuality.toInt().toString()}（仅 JPEG 编码器生效）",
                    style = MaterialTheme.typography.bodySmall)
                Slider(value = jpegQuality, onValueChange = { jpegQuality = it }, valueRange = 0f..100f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = videoFullcolor, onCheckedChange = { videoFullcolor = it })
                    Text("全色彩（video_fullcolor，带宽↑）")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useCpu, onCheckedChange = { useCpu = it })
                    Text("软编码（use_cpu，无 GPU 时）")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = forceAligned, onCheckedChange = { forceAligned = it })
                    Text("强制对齐分辨率")
                }
                Text("流式模式：", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = streamingMode.isEmpty(), onClick = { streamingMode = "" }, label = { Text("默认") })
                    FilterChip(selected = streamingMode == "standard", onClick = { streamingMode = "standard" }, label = { Text("标准") })
                    FilterChip(selected = streamingMode == "paint-over", onClick = { streamingMode = "paint-over" }, label = { Text("Paint-over") })
                }
                if (streamingMode == "paint-over") {
                    Text("Paint-over CRF：${if (paintOverCrf < 1) "默认" else paintOverCrf.toInt().toString()}",
                        style = MaterialTheme.typography.bodySmall)
                    Slider(value = paintOverCrf, onValueChange = { paintOverCrf = it }, valueRange = 0f..51f)
                    Text("突发帧数：${if (paintOverBurst < 1) "默认" else paintOverBurst.toInt().toString()}",
                        style = MaterialTheme.typography.bodySmall)
                    Slider(value = paintOverBurst, onValueChange = { paintOverBurst = it }, valueRange = 0f..30f)
                    Text("覆盖层 JPEG 质量：${if (paintOverJpeg < 1) "默认" else paintOverJpeg.toInt().toString()}",
                        style = MaterialTheme.typography.bodySmall)
                    Slider(value = paintOverJpeg, onValueChange = { paintOverJpeg = it }, valueRange = 0f..100f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = usePaintOverQuality, onCheckedChange = { usePaintOverQuality = it })
                        Text("使用 Paint-over 质量参数（CRF 模式）")
                    }
                }
                Text("以上选项留默认 = 不发送，由服务器端配置决定。",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    if (url.isBlank()) {
                        scope.launch { snackbar.showSnackbar("请填写服务器地址") }
                        return@Button
                    }
                    testing = true
                    testResult = null
                    scope.launch {
                        val entry = ServerEntry(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            name = name.ifBlank { url },
                            url = url.trim(),
                            username = username, password = password,
                            viewOnly = viewOnly, displayId = displayId,
                            framerate = framerate.toInt(),
                            resolutionW = if (resW > 0 && resH > 0) resW else 0,
                            resolutionH = if (resW > 0 && resH > 0) resH else 0,
                            videoBitrate = videoBitrate.toInt(),
                            videoCrf = if (useCrf) videoCrf.toInt() else 0,
                            encoder = encoder,
                            audioEnabled = audioEnabled,
                            jpegQuality = jpegQuality.toInt(),
                            videoFullcolor = videoFullcolor,
                            useCpu = useCpu,
                            forceAlignedResolution = forceAligned,
                            videoStreamingMode = streamingMode,
                            paintOverCrf = paintOverCrf.toInt(),
                            paintOverBurstFrames = paintOverBurst.toInt(),
                            paintOverJpegQuality = paintOverJpeg.toInt(),
                            usePaintOverQuality = usePaintOverQuality,
                            useTokenAuth = useToken, token = token,
                            allowUntrustedCert = allowUntrusted
                        )
                        val result = withContext(Dispatchers.IO) {
                            testConnection(entry)
                        }
                        testing = false
                        testResult = result
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !testing
            ) { Text(if (testing) "正在测试…" else "测试连接") }

            testResult?.let {
                Text(it, color = if (it.startsWith("√")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = {
                    if (url.isBlank()) {
                        scope.launch { snackbar.showSnackbar("请填写服务器地址") }
                        return@Button
                    }
                    val entry = ServerEntry(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        name = name.ifBlank { url },
                        url = url.trim(),
                        username = username, password = password,
                        viewOnly = viewOnly, displayId = displayId,
                        framerate = framerate.toInt(),
                        resolutionW = if (resW > 0 && resH > 0) resW else 0,
                        resolutionH = if (resW > 0 && resH > 0) resH else 0,
                        videoBitrate = videoBitrate.toInt(),
                        videoCrf = if (useCrf) videoCrf.toInt() else 0,
                        encoder = encoder,
                        audioEnabled = audioEnabled,
                        jpegQuality = jpegQuality.toInt(),
                        videoFullcolor = videoFullcolor,
                        useCpu = useCpu,
                        forceAlignedResolution = forceAligned,
                        videoStreamingMode = streamingMode,
                        paintOverCrf = paintOverCrf.toInt(),
                        paintOverBurstFrames = paintOverBurst.toInt(),
                        paintOverJpegQuality = paintOverJpeg.toInt(),
                        usePaintOverQuality = usePaintOverQuality,
                        useTokenAuth = useToken, token = token,
                        allowUntrustedCert = allowUntrusted
                    )
                    state.store.upsertServer(entry)
                    state.navigate(Screen.ServerList)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (existing == null) "保存" else "保存修改") }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary)
}

/** 测试连接：探测 WS 端点与认证 */
private fun testConnection(server: ServerEntry): String {
    val errors = ArrayList<String>()
    val conn = SelkiesConnection(server, object : SelkiesConnection.Listener {
        override fun onOpened() {}
        override fun onText(text: String) {}
        override fun onBinary(data: ByteArray, length: Int) {}
        override fun onClosed(code: Int, reason: String) {}
        override fun onError(userMessage: String, technical: String) { errors.add(userMessage) }
        override fun onRedirected(from: String, to: String) {}
    })
    return try {
        // 只做 HTTP 探测：resolveWsUrl 是私有的，这里用一个简化版本——直接 GET /api/health
        val (code, body) = kotlinx.coroutines.runBlocking {
            conn.apiGetText("/api/health")
        }
        when {
            code == 200 -> "√ 服务器可达（HTTP $code）：${body.take(80)}"
            code == 401 -> "√ 服务器可达，但认证失败（401）：请检查用户名/密码"
            code == 403 -> "× 访问被拒绝（403）：来源被拦截或需要白名单"
            code in 500..599 -> "× 服务器错误（HTTP $code）：后端未就绪"
            else -> "× 返回 HTTP $code：${body.take(80)}"
        }
    } catch (e: Exception) {
        "× 连接失败：${e.message ?: e.javaClass.simpleName}"
    } finally {
        conn.close()
    }
}
