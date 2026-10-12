package com.selkies.client.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.selkies.client.core.SelkiesSession
import java.util.Locale

/**
 * 打印任务面板：远程打印文档的接收/校验/打印状态与重试
 * 服务器端「打印」= 远程桌面应用打印 → 服务器转 PDF → 推送 print_document → 本端下载并交给系统打印服务
 */
@Composable
fun PrintJobsPanel(session: SelkiesSession) {
    val jobs by session.printJobs.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text(
            "远程打印：在远程桌面中选择「Selkies」打印机打印的文档会出现在这里，自动下载校验后交给本机打印服务。文档同时备份到下载目录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp)
        )
        if (jobs.isEmpty()) {
            Text(
                "暂无打印任务",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp)
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp)
            ) {
                items(jobs, key = { it.name + it.receivedAt }) { job ->
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Print, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    job.name,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    when (job.status) {
                                        "pending" -> "等待"
                                        "downloading" -> "下载中"
                                        "downloaded" -> "已下载"
                                        "printing" -> "打印中"
                                        "done" -> "已交付"
                                        else -> "失败"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (job.status) {
                                        "done" -> MaterialTheme.colorScheme.primary
                                        "error" -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    formatSize(job.sizeBytes) + (job.localPath?.let { " · 已备份" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                if (job.status == "downloading") {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                }
                                IconButton(onClick = { session.retryPrintJob(job.name) }, enabled = job.status == "error") {
                                    Icon(Icons.Filled.Refresh, "重试打印")
                                }
                                IconButton(onClick = {
                                    session.savePrintJob(job.name) { ok, path ->
                                        if (ok && path != null) {
                                            com.selkies.client.platform.getPlatformServices().openLocalFile(path)
                                        }
                                    }
                                }, enabled = job.status != "downloading") {
                                    Icon(Icons.Filled.Save, "保存/打开")
                                }
                            }
                            if (job.message.isNotBlank()) {
                                Text(
                                    job.message,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (job.status == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(b: Long): String = when {
    b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
    b >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
    b > 0 -> "$b B"
    else -> "—"
}
