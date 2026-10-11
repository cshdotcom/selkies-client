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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import java.util.Locale

/**
 * 文件面板：远程目录浏览 + 上传 + 下载 + 传输列表
 */
@Composable
fun FilesPanel(state: AppState, session: SelkiesSession) {
    var path by remember { mutableStateOf("") } // 空字符串 = 根目录
    var entries by remember { mutableStateOf<List<RemoteFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showTransfers by remember { mutableStateOf(false) }
    var backStack by remember { mutableStateOf(listOf<String>()) }
    val scope = rememberCoroutineScope()

    fun load(p: String) {
        loading = true
        error = null
        scope.launch(Dispatchers.IO) {
            val result = session.filesClient.list(p)
            launch {
                result.onSuccess { entries = it; path = p }
                    .onFailure { error = it.message }
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load("") }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // 顶部操作
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (backStack.isNotEmpty()) {
                IconButton(onClick = {
                    val prev = backStack.last()
                    backStack = backStack.dropLast(1)
                    load(prev)
                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回上级") }
            }
            Text(
                "/" + path,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            OutlinedButton(onClick = { showTransfers = !showTransfers }) {
                Text(if (showTransfers) "浏览文件" else "传输(${session.filesClient.transfers.value.size})")
            }
        }

        if (!showTransfers) {
            Button(
                onClick = {
                    state.platform.pickFiles(listOf("*/*")) { files ->
                        for (f in files) {
                            val remote = (if (path.isBlank()) "" else path.trimEnd('/') + "/") + f.name
                            session.filesClient.upload(File(f.path), remote)
                        }
                        showTransfers = true
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.UploadFile, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("上传文件到 /" + path)
            }
            Spacer(Modifier.height(4.dp))

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
                items(entries, key = { it.name }) { entry ->
                    FileRow(entry) {
                        val child = (if (path.isBlank()) "" else path.trimEnd('/') + "/") + entry.name
                        scope.launch(Dispatchers.IO) {
                            val isDir = session.filesClient.isDirectory(child)
                            if (isDir) {
                                launch {
                                    backStack = backStack + path
                                    load(child)
                                }
                            } else {
                                val dir = File(state.platform.configDir(), "downloads")
                                dir.mkdirs()
                                session.filesClient.download(child, File(dir, entry.name))
                                launch { showTransfers = true }
                            }
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
                    items(transfers, key = { it.id }) { t ->
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
                                }
                                if (t.totalBytes > 0) {
                                    LinearProgressIndicator(
                                        progress = { (t.sentBytes.toFloat() / t.totalBytes).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                    )
                                    Text(
                                        "${formatBytes(t.sentBytes)} / ${formatBytes(t.totalBytes)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                t.message.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
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
private fun FileRow(entry: RemoteFileEntry, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = androidx.compose.ui.graphics.Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (entry.isDir) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                null,
                tint = if (entry.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                if (entry.isDir) "" else entry.size,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
