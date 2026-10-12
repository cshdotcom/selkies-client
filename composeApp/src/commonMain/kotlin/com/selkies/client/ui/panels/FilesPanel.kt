package com.selkies.client.ui.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.selkies.client.AppState
import com.selkies.client.core.SelkiesSession
import com.selkies.client.model.RemoteFileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLDecoder
import java.util.Locale

/**
 * 文件面板 v2.4.0：远程目录浏览（面包屑 + 目录秒开）+ 搜索过滤 + 多选批量下载 + 上传 + 传输管理（打开/分享/另存为）
 * 说明：官方服务器 API 仅提供 列表/下载/上传，无删除/重命名端点。
 */
@Composable
fun FilesPanel(state: AppState, session: SelkiesSession) {
    var path by remember { mutableStateOf("") }   // 编码形式的目录路径（以 / 结尾，空 = 根）
    var entries by remember { mutableStateOf<List<RemoteFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showTransfers by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }   // 编码 path 集合
    val scope = rememberCoroutineScope()

    fun load(p: String) {
        loading = true
        error = null
        scope.launch(Dispatchers.IO) {
            val result = session.filesClient.list(p)
            launch {
                result.onSuccess { entries = it; path = p; selected = setOf() }
                    .onFailure { error = it.message }
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load("") }

    val filtered = entries.filter {
        search.isBlank() || it.name.contains(search, true)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // 顶部：面包屑路径（解码显示）+ 刷新 + 传输切换
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = {
                // 返回上级：去掉最后一段（编码路径）
                val p = path.trimEnd('/')
                val parent = p.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                load(parent)
            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回上级") }
            Text(
                "远程文件 /" + decodePath(path),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = { load(path) }) { Icon(Icons.Filled.Refresh, "刷新") }
            OutlinedButton(onClick = { showTransfers = !showTransfers }) {
                Text(if (showTransfers) "浏览文件" else "传输(${session.filesClient.transfers.value.size})")
            }
        }

        if (!showTransfers) {
            // 搜索 + 多选切换
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("搜索当前目录") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(Modifier.size(6.dp))
                FilterChip(
                    selected = selectMode,
                    onClick = { selectMode = !selectMode; selected = setOf() },
                    label = { Text(if (selectMode) "退出多选" else "多选") }
                )
            }
            Spacer(Modifier.height(4.dp))

            Button(
                onClick = {
                    state.platform.pickFiles(listOf("*/*")) { files ->
                        for (f in files) {
                            // 上传到当前目录（remotePath 用解码名拼接，上传时由连接层统一编码）
                            val dirDecoded = decodePath(path)
                            val remote = if (dirDecoded.isBlank()) f.name else "$dirDecoded/${f.name}"
                            session.filesClient.upload(File(f.path), remote)
                        }
                        showTransfers = true
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.UploadFile, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("上传文件到 远程文件/" + decodePath(path))
            }
            Spacer(Modifier.height(4.dp))

            // 多选操作条
            if (selectMode) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "已选 ${selected.size} 项",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            selected = filtered.filter { !it.isDir }.map { it.path }.toSet()
                        }) { Icon(Icons.Filled.SelectAll, "全选文件") }
                        IconButton(
                            onClick = {
                                val dir = File(state.platform.configDir(), "downloads")
                                dir.mkdirs()
                                for (e in entries.filter { it.path in selected }) {
                                    session.filesClient.download(e.path, e.name, dir)
                                }
                                showTransfers = true
                            },
                            enabled = selected.isNotEmpty()
                        ) { Icon(Icons.Filled.Download, "批量下载") }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "官方服务器 API 仅支持 列表/下载/上传，暂不支持远程删除/重命名",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (loading) {
                CircularProgressIndicator(modifier = Modifier.padding(24.dp))
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp)
            ) {
                items(filtered, key = { it.path }) { entry ->
                    FileRow(
                        entry = entry,
                        selectMode = selectMode,
                        selected = entry.path in selected
                    ) {
                        if (selectMode) {
                            if (!entry.isDir) {
                                selected = if (entry.path in selected) selected - entry.path else selected + entry.path
                            }
                        } else if (entry.isDir) {
                            // 目录：href 已带尾斜杠，直接列出（无需 301 探测——秒开）
                            load(entry.path)
                        } else {
                            val dir = File(state.platform.configDir(), "downloads")
                            dir.mkdirs()
                            session.filesClient.download(entry.path, entry.name, dir)
                            showTransfers = true
                        }
                    }
                }
            }
        } else {
            val transfers = session.filesClient.transfers.value
            if (transfers.isEmpty()) {
                Text("暂无传输任务", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp)
                ) {
                    items(transfers.asReversed(), key = { it.id }) { t ->
                        TransferRow(state, session, t)
                    }
                }
            }
        }
    }
}

private fun decodePath(p: String): String =
    p.split("/").filter { it.isNotBlank() }.joinToString("/") { URLDecoder.decode(it, "UTF-8") }

@Composable
private fun TransferRow(state: AppState, session: SelkiesSession, t: com.selkies.client.model.TransferTask) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (t.upload) "↑ " else "↓ ") + t.fileName,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    when (t.status) {
                        "running" -> "传输中"
                        "done" -> "完成"
                        "error" -> "失败"
                        else -> "等待"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (t.status) {
                        "done" -> MaterialTheme.colorScheme.primary
                        "error" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (t.status == "done" || t.status == "error") {
                    IconButton(onClick = { session.filesClient.removeTask(t.id) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, "移除", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (t.status == "running" && t.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { (t.sentBytes.toFloat() / t.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            } else if (t.status == "running") {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        t.status == "running" && t.totalBytes > 0 -> "${formatBytes(t.sentBytes)} / ${formatBytes(t.totalBytes)}"
                        t.status == "done" && !t.upload -> "已保存：${t.message.ifBlank { "下载目录" }}"
                        else -> t.message
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                // 下载完成：打开 / 分享 / 另存为
                if (t.status == "done" && !t.upload && t.message.isNotBlank()) {
                    IconButton(onClick = { state.platform.openFile(t.message) }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Filled.OpenInNew, "打开", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { state.platform.shareFile(t.message, t.fileName) }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Filled.Share, "分享", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = {
                        val bytes = state.platform.readFileBytes(t.message)
                        if (bytes != null) {
                            state.platform.saveFile(t.fileName, bytes) { }
                        }
                    }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Filled.UploadFile, "另存为", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

private fun formatBytes(b: Long): String = when {
    b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
    b >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
    else -> "$b B"
}

@Composable
private fun FileRow(entry: RemoteFileEntry, selectMode: Boolean = false, selected: Boolean = false, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else androidx.compose.ui.graphics.Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectMode) {
                Icon(
                    Icons.Filled.Download,
                    if (selected) "已选择" else null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.size(6.dp))
            }
            Icon(
                if (entry.isDir) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                null,
                tint = if (entry.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.mtime.isNotBlank()) {
                    Text(entry.mtime, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            Text(
                entry.size,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
