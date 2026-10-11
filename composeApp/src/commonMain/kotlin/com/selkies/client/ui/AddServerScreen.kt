package com.selkies.client.ui

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

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
                            videoBitrate = videoBitrate.toInt(),
                            videoCrf = if (useCrf) videoCrf.toInt() else 0,
                            encoder = encoder,
                            audioEnabled = audioEnabled,
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
                        videoBitrate = videoBitrate.toInt(),
                        videoCrf = if (useCrf) videoCrf.toInt() else 0,
                        encoder = encoder,
                        audioEnabled = audioEnabled,
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
