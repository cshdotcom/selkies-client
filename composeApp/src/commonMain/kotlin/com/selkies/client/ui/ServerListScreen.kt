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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
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
 * 服务器列表：卡片点击连接、铅笔编辑、长按多选批量（删除/复制/导出）、文件/剪贴板导入导出、
 * 返回键双击退出
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
    var snackbarMsg by remember { mutableStateOf<String?>(null) }
    var lastBackAt by remember { mutableStateOf(0L) }

    // 返回键双击退出（首次提示，2 秒内再按才退出）
    PlatformBackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackAt < 2000) {
            state.platform.exitApp()
        } else {
            lastBackAt = now
            snackbarMsg = "再按一次返回键退出应用"
        }
    }

    fun exitSelection() {
        selectionMode = false
        selected = emptySet()
    }

    /** 导出：保存为 JSON 文件（SAF/文件对话框） */
    fun exportToFile(ids: Set<String>) {
        val json = state.store.exportServers(ids)
        val name = "selkies-servers-${System.currentTimeMillis() / 1000}.json"
        state.platform.saveFile(name, json.toByteArray(Charsets.UTF_8)) { ok ->
            snackbarMsg = if (ok) "已导出到文件：$name（${if (ids.isEmpty()) servers.size else ids.size} 项）"
            else "导出失败：未选择保存位置"
        }
    }

    /** 导出：复制到剪贴板 */
    fun exportToClipboard(ids: Set<String>) {
        val json = state.store.exportServers(ids)
        state.platform.clipboard().setText(json)
        snackbarMsg = "已复制 ${if (ids.isEmpty()) servers.size else ids.size} 项配置 JSON 到剪贴板"
    }

    /** 从文件导入 */
    fun importFromFile() {
        state.platform.pickFiles(listOf("application/json", "text/*", "*/*")) { files ->
            val f = files.firstOrNull() ?: return@pickFiles
            runCatching {
                val bytes = state.platform.readFileBytes(f.path)
                val text = bytes?.toString(Charsets.UTF_8) ?: ""
                val n = state.store.importServers(text)
                snackbarMsg = if (n > 0) "从 ${f.name} 导入 $n 项成功" else "导入失败：文件格式不正确"
            }.onFailure {
                snackbarMsg = "导入失败：${it.message}"
            }
        }
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
                            val id = selected.firstOrNull()
                            if (id != null) {
                                exitSelection()
                                state.navigate(Screen.AddEdit(id))
                            }
                        }) { Icon(Icons.Filled.Edit, "编辑") }
                        IconButton(onClick = {
                            state.store.duplicateServer(selected.firstOrNull() ?: return@IconButton)
                            exitSelection()
                        }) { Icon(Icons.Filled.ContentCopy, "复制") }
                        IconButton(onClick = { showDeleteConfirm = true }) { Icon(Icons.Filled.Delete, "删除") }
                        TextButton(onClick = {
                            exportToFile(selected)
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
                        // 全部导出（无选中时）
                        if (servers.isNotEmpty()) {
                            TextButton(onClick = { exportToFile(emptySet()) }) { Text("导出") }
                        }
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
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp),
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
                            // 编辑入口（v2.1.0：列表可直接编辑）
                            IconButton(onClick = { state.navigate(Screen.AddEdit(server.id)) }) {
                                Icon(
                                    Icons.Filled.Edit, "编辑",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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

    // 轻量提示（含双击退出提示）
    snackbarMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { snackbarMsg = null },
            confirmButton = { TextButton(onClick = { snackbarMsg = null }) { Text("知道了") } },
            title = { Text(msg) }
        )
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showImport = false; importFromFile() }) { Text("从文件导入") }
                        OutlinedButton(onClick = { /* 留在对话框手动粘贴 */ }) { Text("手动粘贴") }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("或粘贴此前导出的 JSON 配置：", style = MaterialTheme.typography.bodySmall)
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
                    snackbarMsg = if (n > 0) "导入 $n 项成功" else "导入失败：JSON 格式不正确"
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
