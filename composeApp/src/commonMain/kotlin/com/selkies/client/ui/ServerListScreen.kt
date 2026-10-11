package com.selkies.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.selkies.client.AppState
import com.selkies.client.Screen
import com.selkies.client.model.ServerEntry
import java.util.UUID

/**
 * 服务器列表：多选批量操作（删除/复制/连接）、导入导出
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ServerListScreen(state: AppState) {
    val servers = state.store.servers.value
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var showImport by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }

    fun exitSelection() {
        selectionMode = false
        selected = emptySet()
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("已选择 ${selected.size} 项") },
                    navigationIcon = {
                        TextButton(onClick = { exitSelection() }) { Text("取消") }
                    },
                    actions = {
                        IconButton(onClick = {
                            state.store.duplicateServer(selected.firstOrNull() ?: return@IconButton)
                            exitSelection()
                        }) { Icon(Icons.Filled.ContentCopy, "复制") }
                        IconButton(onClick = { showDeleteConfirm = true }) { Icon(Icons.Filled.Delete, "删除") }
                        TextButton(onClick = {
                            val json = state.store.exportServers(selected)
                            // 写入剪贴板
                            state.platform.clipboard().setText(json)
                            exitSelection()
                        }) { Text("导出") }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text("Selkies 远程桌面", fontWeight = FontWeight.Bold)
                            Text(
                                "原生客户端 v${state.platform.appVersion()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { state.navigate(Screen.HotkeyManager) }) {
                            Icon(Icons.Filled.Keyboard, "快捷键管理")
                        }
                        IconButton(onClick = { state.navigate(Screen.Settings) }) {
                            Icon(Icons.Filled.Settings, "设置")
                        }
                        TextButton(onClick = { showImport = true }) { Text("导入") }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { state.navigate(Screen.AddEdit(null)) },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("添加服务器") }
                )
            }
        }
    ) { padding ->
        if (servers.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Computer, null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("还没有服务器", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "点击右下角按钮添加第一个 Selkies 服务器",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(servers, key = { it.id }) { server ->
                    val isSel = server.id in selected
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (selectionMode) {
                                        selected = if (isSel) selected - server.id else selected + server.id
                                        if (selected.isEmpty()) selectionMode = false
                                    } else {
                                        state.navigate(Screen.Session(server.id))
                                    }
                                },
                                onLongClick = {
                                    selectionMode = true
                                    selected = setOf(server.id)
                                    state.platform.vibrate(30)
                                }
                            ),
                        colors = if (isSel) {
                            androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        } else androidx.compose.material3.CardDefaults.cardColors()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selectionMode) {
                                Checkbox(checked = isSel, onCheckedChange = {
                                    selected = if (it) selected + server.id else selected - server.id
                                    if (selected.isEmpty()) selectionMode = false
                                })
                                Spacer(Modifier.width(8.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(server.name.ifBlank { server.url }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    server.url + if (server.username.isNotBlank()) " · ${server.username}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (server.viewOnly) {
                                        Tag("查看模式", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                                    }
                                    Tag(server.displayId, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                                    Tag("${server.framerate}fps", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (server.viewOnly) {
                                Icon(Icons.Filled.Visibility, "查看", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除服务器") },
            text = { Text("确定删除选中的 ${selected.size} 个服务器配置？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    state.store.deleteServers(selected)
                    exitSelection()
                    showDeleteConfirm = false
                    state.dataVersion++
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
        )
    }

    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("导入服务器") },
            text = {
                Column {
                    Text("粘贴此前导出的 JSON 配置：", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        placeholder = { Text("[]") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val n = state.store.importServers(importText)
                    showImport = false
                    importText = ""
                    state.dataVersion++
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text("取消") } }
        )
    }
}

@Composable
fun Tag(text: String, bg: Color, fg: Color) {
    Box(
        modifier = Modifier
            .background(bg, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}
