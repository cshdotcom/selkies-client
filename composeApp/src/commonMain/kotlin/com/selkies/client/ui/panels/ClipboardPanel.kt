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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.selkies.client.AppState
import com.selkies.client.core.SelkiesSession

/**
 * 剪贴板面板：双向同步 + 手动发送 + 同步开关 + 历史
 * 【v2.5.0 修复】监听器改为 add/remove：不再顶掉会话层自动同步（旧版打开过此面板后自动同步失效的根因）
 */
@Composable
fun ClipboardPanel(state: AppState, session: SelkiesSession) {
    var localText by remember { mutableStateOf(state.platform.clipboard().getText() ?: "") }
    var remoteText by remember { mutableStateOf("") }
    var autoSend by remember { mutableStateOf(state.store.settings.value.clipboardAutoSend) }
    var autoReceive by remember { mutableStateOf(state.store.settings.value.clipboardAutoReceive) }
    var edit by remember { mutableStateOf("") }

    // 拉取远程剪贴板
    LaunchedEffect(Unit) { session.requestClipboard() }

    DisposableEffect(session) {
        val listener: () -> Unit = {
            localText = state.platform.clipboard().getText() ?: localText
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

        // 本地剪贴板
        SectionCard("本机剪贴板") {
            Text(
                localText.ifBlank { "（空）" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val t = state.platform.clipboard().getText()
                    if (!t.isNullOrBlank()) session.sendClipboardTextNow(t)
                }) { Text("发送到远程") }
                OutlinedButton(onClick = {
                    localText = state.platform.clipboard().getText() ?: ""
                }) { Text("刷新") }
            }
        }

        // 远程剪贴板
        SectionCard("远程剪贴板") {
            Text(
                remoteText.ifBlank { "（点击下方按钮获取）" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { session.requestClipboard() }) { Text("获取远程剪贴板") }
                OutlinedButton(onClick = {
                    if (remoteText.isNotBlank()) state.platform.clipboard().setText(remoteText)
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
                    if (edit.isNotBlank()) session.sendClipboardTextNow(edit)
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
