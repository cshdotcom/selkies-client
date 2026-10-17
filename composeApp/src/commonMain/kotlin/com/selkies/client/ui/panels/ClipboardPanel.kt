package com.selkies.client.ui.panels

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
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
 *
 * 【v2.7.7 图片支持】用户要求「剪贴板增加图片粘贴和上传功能」：
 *  - 本机剪贴板图片预览 + 手动发送到远程（cb,image/png 协议，官方 clipboard-sync 同款）
 *  - 相册/文件管理器选图发送（pickFiles 的 image MIME）
 *  - 远程剪贴板图片预览 + 复制到本机 + 保存到本地 + 上传为远程文件
 */
@Composable
fun ClipboardPanel(state: AppState, session: SelkiesSession) {
    var localText by remember { mutableStateOf(state.platform.clipboard().getText() ?: "") }
    val remote by session.remoteClipboard.collectAsState()
    val notice by session.clipboardNotice.collectAsState()
    var autoSend by remember { mutableStateOf(state.store.settings.value.clipboardAutoSend) }
    var autoReceive by remember { mutableStateOf(state.store.settings.value.clipboardAutoReceive) }
    var edit by remember { mutableStateOf("") }
    var fetching by remember { mutableStateOf(false) }

    // v2.7.7：本机剪贴板图片（有图时预览 + 发送按钮）
    var localImage by remember { mutableStateOf<ByteArray?>(null) }
    fun refreshLocalImage() {
        localImage = runCatching { state.platform.clipboard().getImagePng() }.getOrNull()
    }
    // v2.7.7：远程剪贴板图片（服务器 cb,image/png 推送）
    val remoteImageBytes by session.remoteClipboardImage.collectAsState()

    // 图片字节 → 位图（后台解码，避免大图卡 UI）
    var localImageBmp by remember { mutableStateOf<ImageBitmap?>(null) }
    var remoteImageBmp by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(localImage) {
        localImageBmp = localImage?.let { bytes ->
            withContext(Dispatchers.Default) {
                runCatching { decodePngToImageBitmap(bytes) }.getOrNull()
            }
        }
    }
    LaunchedEffect(remoteImageBytes) {
        remoteImageBmp = remoteImageBytes?.let { bytes ->
            withContext(Dispatchers.Default) {
                runCatching { decodePngToImageBitmap(bytes) }.getOrNull()
            }
        }
    }

    // 打开面板自动拉取一次远程剪贴板 + 本机图片
    LaunchedEffect(Unit) {
        session.requestClipboard()
        fetching = true
        refreshLocalImage()
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
            refreshLocalImage()
        }
        state.platform.clipboard().addListener(listener)
        onDispose { state.platform.clipboard().removeListener(listener) }
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
                localText.ifBlank { if (localImageBmp != null) "（当前是图片）" else "（空）" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            // v2.7.7：本机图片预览
            localImageBmp?.let { bmp ->
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Image(
                        bmp,
                        contentDescription = "本机剪贴板图片",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .padding(4.dp)
                    )
                }
                Text(
                    "本机剪贴板含图片（${(localImage?.size ?: 0) / 1024} KB）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                // v2.7.7：发送本机图片到远程剪贴板
                OutlinedButton(onClick = {
                    val png = localImage
                    if (png != null) {
                        session.sendClipboardImageNow(png)
                    } else {
                        session.clipboardNotice.value = "本机剪贴板当前没有图片——先在本机复制一张图（或用下方「选图发送」）"
                    }
                }) { Text("发送图片") }
                OutlinedButton(onClick = {
                    localText = state.platform.clipboard().getText() ?: ""
                    refreshLocalImage()
                }) { Text("刷新") }
            }
            // v2.7.7：从相册/文件选图发送（转 PNG → 远程剪贴板）
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    state.platform.pickFiles(listOf("image/*")) { files ->
                        val f = files.firstOrNull() ?: return@pickFiles
                        session.scope.launch {
                            val bytes = withContext(Dispatchers.IO) {
                                runCatching {
                                    when {
                                        f.path.startsWith("content://") -> null  // Android URI 由平台层读取（见下）
                                        else -> java.io.File(f.path).takeIf { it.exists() }?.readBytes()
                                    }
                                }.getOrNull()
                            }
                            if (bytes == null) {
                                // Android content URI 路径：走平台 saveFile 兜底不可行——用剪贴板图片通道提示
                                session.clipboardNotice.value = "请在相册中长按图片→复制，再回到此处点「发送图片」"
                            } else {
                                session.sendClipboardImageNow(bytes)
                            }
                        }
                    }
                }) { Text("选图发送") }
            }
        }

        // 远程剪贴板
        SectionCard("远程剪贴板（$remoteMime）") {
            Text(
                if (fetching && remote == null && remoteImageBytes == null) "正在获取…"
                else remoteText?.ifBlank { "（远程剪贴板为空）" }
                    ?: if (remoteImageBytes != null) "（远程图片，见下方预览）"
                    else "（尚未获取——点下方按钮拉取；远程无内容时不会有应答）",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 6,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            // v2.7.7：远程图片预览 + 操作（复制到本机/保存本地/上传为远程文件）
            remoteImageBmp?.let { bmp ->
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Image(
                        bmp,
                        contentDescription = "远程剪贴板图片",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .padding(4.dp)
                    )
                }
                Text(
                    "远程图片 ${(remoteImageBytes?.size ?: 0) / 1024} KB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { session.copyRemoteImageToLocal() }) { Text("复制到本机") }
                    OutlinedButton(onClick = { session.saveRemoteImageLocally() }) { Text("保存到本地") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val bytes = remoteImageBytes ?: return@OutlinedButton
                        val name = "clipboard_${System.currentTimeMillis()}.png"
                        session.filesClient.uploadBytes(bytes, name, name)
                        session.clipboardNotice.value = "已开始上传图片到远程上传目录（$name）——进度见文件面板"
                    }) { Text("上传为远程文件") }
                }
            }
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
