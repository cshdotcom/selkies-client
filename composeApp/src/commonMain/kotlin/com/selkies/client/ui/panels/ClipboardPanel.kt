package com.selkies.client.ui.panels

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.selkies.client.AppState
import com.selkies.client.core.SelkiesSession
import com.selkies.client.platform.decodePngToImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 剪贴板面板：双向同步 + 手动发送 + 同步开关 + 历史
 * 【v2.6.0 修复】
 *  - 远程剪贴板直接观察 session.remoteClipboard（旧版本地 state 从未更新 = "收不了"的 UI 根因）
 *  - 本地剪贴板实时刷新（多监听器，桌面端已实现真正的 addListener）
 *  - 同步事件提示（clipboardNotice：发送/接收成功/失败一目了然）
 *
 * 【v2.7.3 修复】实测验证服务器剪贴板双向协议完全正常后，针对用户实际反镄的 UI 反馈问题：
 *  - 「获取远程剪贴板」fetching 状态永不复位（卡在"正在获取…"）→ 3s 超时自动复位并给出空结果反馈
 *  - 「复制到本机」点击后无任何反馈 → 成功/空内容均给出明确提示
 *  - 服务器剪贴板为空时 REQUEST_CLIPBOARD 无应答（官方设计）→ 明确告知而非永远等待
 */
@Composable
fun ClipboardPanel(state: AppState, session: SelkiesSession) {
    var localText by remember { mutableStateOf(state.platform.clipboard().getText() ?: "") }
    val remote by session.remoteClipboard.collectAsState()
    val remoteImage by session.remoteClipboardImage.collectAsState()
    val notice by session.clipboardNotice.collectAsState()
    var autoSend by remember { mutableStateOf(state.store.settings.value.clipboardAutoSend) }
    var autoReceive by remember { mutableStateOf(state.store.settings.value.clipboardAutoReceive) }
    var edit by remember { mutableStateOf("") }
    var fetching by remember { mutableStateOf(false) }
    // v2.7.5：本机剪贴板图像状态（IO 读取，不卡 UI）
    var localImage by remember { mutableStateOf<ByteArray?>(null) }
    var localImageChecking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 打开面板自动拉取一次远程剪贴板
    LaunchedEffect(Unit) {
        session.requestClipboard()
        fetching = true
        delay(1500)
        fetching = false
    }

    // v2.7.3：手动获取——带超时自动复位（旧版 fetching=true 永不复位 = "点击后卡住"的根因）
    fun fetchRemote() {
        fetching = true
        session.requestClipboard()
        session.scope.launch {
            delay(3000)
            fetching = false
        }
    }

    // 远程内容变化 → 同步本地面板显示
    val remoteText = remote?.first
    val remoteMime = remote?.second ?: "text/plain"

    // 本地剪贴板监听（多监听器：面板 + 会话层共存）
    DisposableEffect(session) {
        val listener: () -> Unit = {
            localText = state.platform.clipboard().getText() ?: localText
            // v2.7.5：剪贴板变化时异步探测图像（本机截图/复制图片 → 发送远程入口）
            scope.launch {
                val has = withContext(Dispatchers.Default) { state.platform.clipboard().hasImage() }
                localImage = if (has) {
                    withContext(Dispatchers.IO) { state.platform.clipboard().getImagePng() }
                } else null
            }
        }
        state.platform.clipboard().addListener(listener)
        onDispose { state.platform.clipboard().removeListener(listener) }
    }

    // 面板打开时探测一次本机剪贴板图像
    LaunchedEffect(Unit) {
        localImageChecking = true
        val has = withContext(Dispatchers.Default) { runCatching { state.platform.clipboard().hasImage() }.getOrDefault(false) }
        localImage = if (has) {
            withContext(Dispatchers.IO) { runCatching { state.platform.clipboard().getImagePng() }.getOrNull() }
        } else null
        localImageChecking = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 同步事件提示
        notice?.let { n ->
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            ) {
                Text(
                    n,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        // 同步开关
        SettingSwitch("本地复制 → 自动发送到远程", autoSend) {
            autoSend = it
            state.store.settings.value.clipboardAutoSend = it
            state.store.saveSettings()
        }
        SettingSwitch("远程剪贴板 → 自动应用到本地", autoReceive) {
            autoReceive = it
            state.store.settings.value.clipboardAutoReceive = it
            state.store.saveSettings()
        }
        var pollSync by remember { mutableStateOf(state.store.settings.value.clipboardPollSync) }
        SettingSwitch("可靠同步（前台定时拉取）", pollSync) {
            pollSync = it
            state.store.settings.value.clipboardPollSync = it
            state.store.saveSettings()
        }

        // 本地剪贴板
        SectionCard("本机剪贴板") {
            Text(
                localText.ifBlank { "（空）" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            // v2.7.5：本机剪贴板图像（截图/复制图片）预览 + 手动发送远程
            localImage?.let { img ->
                Spacer(Modifier.height(6.dp))
                decodePngToImageBitmap(img)?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = "本机剪贴板图像",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Text(
                    "剪贴板图像（${img.size / 1024} KB）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (localImageChecking) {
                Text("正在检测本机剪贴板图像…", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val t = state.platform.clipboard().getText()
                    if (!t.isNullOrBlank()) {
                        session.sendClipboardTextNow(t)
                        session.clipboardNotice.value = "已手动发送本机剪贴板（${t.length} 字符）"
                    }
                }) { Text("发送文本") }
                // v2.7.5：本机图像 → 远程剪贴板（旧版 Android getImagePng 恒 null = 从未实现）
                OutlinedButton(onClick = {
                    scope.launch {
                        val png = withContext(Dispatchers.IO) {
                            runCatching { state.platform.clipboard().getImagePng() }.getOrNull()
                        }
                        if (png != null) {
                            session.sendClipboardImageNow(png)
                        } else {
                            session.clipboardNotice.value = "本机剪贴板没有可发送的图像（截图或复制一张图片后再试）"
                        }
                    }
                }) { Text("发送图像到远程") }
                // v2.7.5：从相册/文件选一张图发送到远程剪贴板
                OutlinedButton(onClick = {
                    state.platform.pickFiles(listOf("image/*")) { files ->
                        val f = files.firstOrNull() ?: return@pickFiles
                        scope.launch {
                            val bytes = withContext(Dispatchers.IO) {
                                runCatching { state.platform.readFileBytes(f.path) }.getOrNull()
                            }
                            if (bytes != null && bytes.size > 100) {
                                session.sendClipboardImageNow(bytes)
                            } else {
                                session.clipboardNotice.value = "读取所选图片失败"
                            }
                        }
                    }
                }) { Text("从相册选图") }
            }
            OutlinedButton(onClick = {
                localText = state.platform.clipboard().getText() ?: ""
                scope.launch {
                    localImage = withContext(Dispatchers.IO) {
                        runCatching { state.platform.clipboard().getImagePng() }.getOrNull()
                    }
                }
            }) { Text("刷新") }
        }

        // 远程剪贴板
        SectionCard("远程剪贴板（$remoteMime）") {
            // v2.7.5：远程图像预览（远程复制的图片/截图同步过来）
            remoteImage?.let { img ->
                decodePngToImageBitmap(img)?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = "远程剪贴板图像",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    "远程图像（${img.size / 1024} KB）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        // 复制到本机剪贴板（本机粘贴可用）
                        state.platform.clipboard().setImagePng(img)
                        session.clipboardNotice.value = "已复制远程图像到本机剪贴板（${img.size / 1024} KB）"
                    }) { Text("复制到本机") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val path = withContext(Dispatchers.IO) {
                                runCatching {
                                    state.platform.mediaStore().saveScreenshot(img, "clipboard_${System.currentTimeMillis()}.png")
                                }.getOrNull()
                            }
                            session.clipboardNotice.value = if (path != null) "已保存到相册：$path" else "保存失败"
                        }
                    }) { Text("保存到相册") }
                }
                Spacer(Modifier.height(6.dp))
            }
            Text(
                if (fetching && remote == null && remoteImage == null) "正在获取…"
                else if (remoteImage != null) "（图像内容，见上方预览）"
                else remoteText?.ifBlank { "（远程剪贴板为空）" } ?: "（尚未获取——点下方按钮拉取；远程无内容时不会有应答）",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 6,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { fetchRemote() }) { Text("获取远程剪贴板") }
                OutlinedButton(onClick = {
                    val t = remoteText
                    if (!t.isNullOrBlank()) {
                        state.platform.clipboard().setText(t)
                        localText = t
                        // v2.7.3：明确反馈（旧版点击后无任何提示 = "点了没反应"的体验）
                        session.clipboardNotice.value = "已复制 ${t.length} 字符到本机剪贴板（可在本机粘贴）"
                    } else {
                        session.clipboardNotice.value = "远程剪贴板为空或尚未获取——先点「获取远程剪贴板」"
                    }
                }) { Text("复制到本机") }
            }
        }

        // 手动输入发送
        SectionCard("输入文本发送到远程") {
            OutlinedTextField(
                value = edit,
                onValueChange = { edit = it },
                modifier = Modifier.fillMaxWidth().height(100.dp),
                placeholder = { Text("输入要放到远程剪贴板的文本") }
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (edit.isNotBlank()) {
                        session.sendClipboardTextNow(edit)
                        session.clipboardNotice.value = "已发送输入文本到远程剪贴板（${edit.length} 字符）"
                    }
                }) { Text("发送") }
                OutlinedButton(onClick = { session.typeText(edit) }) { Text("直接打字") }
            }
        }
    }
}

@Composable
fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SectionCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}
