package com.selkies.client.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.selkies.client.AppState
import com.selkies.client.core.BuiltinHotkeys
import com.selkies.client.core.HotkeyRecorder
import com.selkies.client.core.Keysyms
import com.selkies.client.core.SelkiesSession
import com.selkies.client.model.HotkeyDef
import com.selkies.client.platform.keysymOrNull
import java.util.UUID

/**
 * 快捷键面板：内置分类 + 自定义 + 录制
 */
@Composable
fun HotkeysPanel(state: AppState, session: SelkiesSession) {
    var search by remember { mutableStateOf("") }
    val hotkeys = state.store.hotkeys.value

    val filtered = if (search.isBlank()) hotkeys
    else hotkeys.filter { it.name.contains(search, true) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        androidx.compose.material3.OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("搜索快捷键") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp)
        ) {
            items(filtered, key = { it.id }) { hk ->
                HotkeyRow(
                    hotkey = hk,
                    onRun = { session.sendKeyChord(hk.keysyms) },
                    onDelete = if (!hk.builtin) {
                        { state.store.deleteHotkey(hk.id) }
                    } else null
                )
            }
        }
    }
}

@Composable
fun HotkeyRow(hotkey: HotkeyDef, onRun: () -> Unit, onDelete: (() -> Unit)?) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (hotkey.builtin) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(hotkey.name, fontWeight = FontWeight.Medium)
                Row {
                    Text(
                        hotkey.keysyms.joinToString(" + ") { Keysyms.name(it) },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onRun) {
                Icon(Icons.Filled.PlayArrow, "执行", tint = MaterialTheme.colorScheme.primary)
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * 快捷键管理页（独立屏幕）：全量管理 + 新建录制（物理键盘 / 软键盘修饰键）
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HotkeyManagerScreen(state: AppState) {
    val hotkeys = state.store.hotkeys.value
    var recording by remember { mutableStateOf(false) }
    var newHotkeyName by remember { mutableStateOf("") }
    val recorder = remember { HotkeyRecorder() }
    var recordedKeys by remember { mutableStateOf<List<Long>>(emptyList()) }

    com.selkies.client.ui.PlatformBackHandler {
        if (recording) recording = false
        else state.navigate(com.selkies.client.Screen.ServerList)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        androidx.compose.material3.TopAppBar(
            title = { Text("快捷键管理") },
            navigationIcon = {
                androidx.compose.material3.TextButton(onClick = { state.navigate(com.selkies.client.Screen.ServerList) }) {
                    Text("返回")
                }
            }
        )
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Text(
                "内置 ${hotkeys.count { it.builtin }} 个 · 自定义 ${hotkeys.count { !it.builtin }} 个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            // 录制新区
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    androidx.compose.material3.OutlinedTextField(
                        value = newHotkeyName,
                        onValueChange = { newHotkeyName = it },
                        label = { Text("新快捷键名称") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    if (!recording) {
                        androidx.compose.material3.Button(onClick = {
                            recording = true
                            recorder.reset()
                            recordedKeys = emptyList()
                        }) { Text("开始录制（按住修饰键后按主键）") }
                    } else {
                        Text(
                            if (recordedKeys.isEmpty()) "请先按下修饰键（Ctrl/Alt/Shift/Super）…"
                            else "已按：${recordedKeys.joinToString(" + ") { Keysyms.name(it) }} — 现在按主键完成录制",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        KeyCaptureBox(state) { keysym ->
                            if (Keysyms.isModifier(keysym)) {
                                recorder.offer(keysym)
                                recordedKeys = recorder.current()
                            } else {
                                val keys = recorder.finish(keysym)
                                recording = false
                                if (keys.size >= 2 && newHotkeyName.isNotBlank()) {
                                    state.store.upsertHotkey(
                                        HotkeyDef(
                                            id = UUID.randomUUID().toString(),
                                            name = newHotkeyName,
                                            keysyms = keys,
                                            builtin = false,
                                            category = "custom"
                                        )
                                    )
                                    newHotkeyName = ""
                                    recordedKeys = emptyList()
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        androidx.compose.material3.TextButton(onClick = {
                            recording = false
                            recorder.reset()
                            recordedKeys = emptyList()
                        }) { Text("取消录制") }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(hotkeys, key = { it.id }) { hk ->
                    HotkeyRow(
                        hotkey = hk,
                        onRun = { },
                        onDelete = if (!hk.builtin) {
                            { state.store.deleteHotkey(hk.id) }
                        } else null
                    )
                }
            }
        }
    }
}

/** 物理按键捕获盒 */
@Composable
private fun KeyCaptureBox(state: AppState, onKeysym: (Long) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .background(MaterialTheme.colorScheme.surface)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) {
                    val ks = e.keysymOrNull()
                    if (ks != null && ks > 0) {
                        onKeysym(ks.toLong())
                        true
                    } else false
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        Text("点击此处后按键（物理键盘或软键盘均可）", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
