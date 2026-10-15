package com.selkies.client.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selkies.client.AppState
import com.selkies.client.model.SavedMediaEntry
import com.selkies.client.platform.PlatformServices
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 媒体库面板（v2.5.0）：截图 / 录屏 / 下载 全部已保存文件的浏览与管理。
 * 支持分类筛选、打开文件、打开所在文件夹、复制路径/文件名、删除。
 */
@Composable
fun SavedMediaPanel(state: AppState) {
    val platform = state.platform
    var entries by remember { mutableStateOf<List<SavedMediaEntry>>(emptyList()) }
    var filter by remember { mutableStateOf("all") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        entries = platform.mediaStore().listMedia()
        loaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        // 分类筛选
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip("全部", filter == "all") { filter = "all" }
            FilterChip("截图", filter == "screenshot") { filter = "screenshot" }
            FilterChip("录屏", filter == "recording") { filter = "recording" }
            FilterChip("下载", filter == "download") { filter = "download" }
        }
        Spacer(Modifier.height(6.dp))
        // 存储位置
        Text(
            buildString {
                append("截图: ${platform.mediaStore().screenshotsDir()}")
                append("  ·  录屏: ${platform.mediaStore().recordingsDir()}")
                append("  ·  下载: ${platform.mediaStore().downloadsDir()}")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))

        val shown = if (filter == "all") entries else entries.filter { it.kind == filter }
        if (!loaded) {
            Text("加载中…", style = MaterialTheme.typography.bodySmall)
        } else if (shown.isEmpty()) {
            Text(
                "暂无保存的文件。\n截图（📷 工具栏）→ 自动存入截图目录；\n录屏（⏺ 工具栏）→ 退出自动保存；\n下载（文件面板）→ 自动存入下载目录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(shown, key = { it.path }) { e ->
                    MediaRow(e, platform, onDelete = {
                        platform.mediaStore().deleteMedia(e)
                        entries = platform.mediaStore().listMedia()
                    })
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        onClick = onClick
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            fontSize = 12.sp,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun MediaRow(
    e: SavedMediaEntry,
    platform: PlatformServices,
    onDelete: () -> Unit
) {
    val icon: ImageVector = when (e.kind) {
        "screenshot" -> Icons.Filled.Image
        "recording" -> Icons.Filled.Videocam
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
    val kindLabel = when (e.kind) {
        "screenshot" -> "截图"
        "recording" -> "录屏"
        else -> "下载"
    }
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, kindLabel, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        e.name,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "$kindLabel · ${e.sizeText} · " +
                                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(e.modifiedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(4.dp))
            // 操作行：打开 / 所在文件夹 / 复制路径 / 复制文件名 / 分享
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MiniAction("打开") { platform.openFile(e.path) }
                MiniAction("所在文件夹") { platform.openFileLocation(e.path) }
                MiniAction("复制路径") { platform.clipboard().setText(e.path) }
                MiniAction("复制文件名") { platform.clipboard().setText(e.name) }
                MiniAction("分享") { platform.shareFile(e.path, e.name) }
            }
        }
    }
}

@Composable
private fun MiniAction(label: String, onClick: () -> Unit) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        onClick = onClick
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
