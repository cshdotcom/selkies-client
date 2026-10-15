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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
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
 * 快捷键面板：内置分类 + 自定义 + 录制 + 固定到快捷按钮栏
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
                    } else null,
                    onPin = {
                        state.store.upsertHotkey(hk.copy(pinned = !hk.pinned))
                    }
                )
            }
        }
    }
}

@Composable
fun HotkeyRow(hotkey: HotkeyDef, onRun: () -> Unit, onDelete: (() -> Unit)?, onPin: (() -> Unit)? = null) {
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
            if (onPin != null) {
                IconButton(onClick = onPin) {
                    Icon(
                        if (hotkey.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        if (hotkey.pinned) "取消固定" else "固定到快捷按钮栏",
                        tint = if (hotkey.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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
 * 【v2.7.0 重构】修复「按下多个热键或单个键时键盘突然退出、内容无法录入」：
 *  - 软键盘选择：不再成组即自动关闭——每次主键按下更新捕获区，键盘保持打开，
 *    由「保存」按钮显式落库（名字可随时录入，内容不再丢）
 *  - 物理键盘录制：桌面端 keysymOrNull 修复后可真实捕获（旧版恒 null = 桌面物理键完全无效）；
 *    捕获完成后录制盒保持打开，可反复重按覆盖，ESC 也可作为热键成分录制
 *  - 返回键（Android）/ESC（桌面）：录制中不退出页面（ESC 进热键），选择中关键盘，其余才返回
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HotkeyManagerScreen(state: AppState) {
    val hotkeys = state.store.hotkeys.value
    var recording by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var newHotkeyName by remember { mutableStateOf("") }
    val recorder = remember { HotkeyRecorder() }
    var recordedKeys by remember { mutableStateOf<List<Long>>(emptyList()) }
    // v2.7.0：已捕获完成的组合（录制盒/选择器共用显示与保存）
    var capturedChord by remember { mutableStateOf<List<Long>>(emptyList()) }

    // v2.7.0：录制中禁用返回键消费（ESC 作为热键成分被捕获盒吃掉；用「取消录制」退出）
    com.selkies.client.ui.PlatformBackHandler(enabled = !recording) {
        when {
            picking -> picking = false
            recording -> recording = false
            else -> state.navigate(com.selkies.client.Screen.ServerList)
        }
    }

    /** 保存当前捕获的组合 */
    fun saveCaptured() {
        if (capturedChord.isEmpty()) return
        if (newHotkeyName.isBlank()) {
            newHotkeyName = capturedChord.joinToString("+") { Keysyms.name(it) }
        }
        state.store.upsertHotkey(
            HotkeyDef(
                id = UUID.randomUUID().toString(),
                name = newHotkeyName,
                keysyms = capturedChord,
                builtin = false,
                category = "custom"
            )
        )
        newHotkeyName = ""
        recordedKeys = emptyList()
        capturedChord = emptyList()
        recorder.reset()
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
                        label = { Text("新快捷键名称（留空自动命名）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!recording && !picking) {
                            androidx.compose.material3.Button(onClick = {
                                recording = true
                                recorder.reset()
                                recordedKeys = emptyList()
                                capturedChord = emptyList()
                            }) { Text("录制（物理键盘）") }
                            androidx.compose.material3.OutlinedButton(onClick = {
                                picking = true
                                recorder.reset()
                                recordedKeys = emptyList()
                                capturedChord = emptyList()
                            }) {
                                Icon(Icons.Filled.Keyboard, null, modifier = Modifier.padding(end = 4.dp).size(16.dp))
                                Text("用完整键盘选择")
                            }
                        }
                    }
                    // v2.7.0：捕获区（键盘不再自动退出；显式保存/清空/收起）
                    if (capturedChord.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "已捕获：${capturedChord.joinToString(" + ") { Keysyms.name(it) }}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                androidx.compose.material3.TextButton(onClick = {
                                    capturedChord = emptyList()
                                    recordedKeys = emptyList()
                                    recorder.reset()
                                }) { Text("清空") }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.Button(onClick = { saveCaptured() }) { Text("保存快捷键") }
                            androidx.compose.material3.OutlinedButton(onClick = {
                                picking = false
                                recording = false
                                capturedChord = emptyList()
                                recordedKeys = emptyList()
                            }) { Text("收起键盘/录制盒") }
                        }
                    }
                    if (picking) {
                        Text(
                            "先点修饰键（Ctrl/Alt/Shift/AltGr…）再点主键——每按一次主键更新下方捕获；点「保存快捷键」完成，键盘不会自动退出",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(6.dp))
                        // 世界最完整键盘选择器：修饰键粘滞 + 主键点选 → 捕获区更新（不自动关闭）
                        Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                            CompleteKeyboard(
                                session = null,
                                visible = true,
                                autoScaleHint = 0.42f,
                                onDismiss = { picking = false },
                                onChord = { chord ->
                                    // v2.7.0：主键按下 → 更新捕获组合（替换）；键盘保持打开
                                    capturedChord = chord
                                }
                            )
                        }
                    } else if (recording) {
                        Text(
                            "请按修饰键（Ctrl/Alt/Shift/Super），再按主键完成捕获；可反复重按覆盖；ESC 也可作为热键成分（录制中返回键不退出）",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        KeyCaptureBox(state) { keysym ->
                            if (Keysyms.isModifier(keysym)) {
                                recorder.offer(keysym)
                                recordedKeys = recorder.current()
                            } else {
                                // v2.7.0：主键 → 捕获组合，但录制盒保持打开（不自动退出）
                                capturedChord = recorder.finish(keysym)
                                recorder.reset()
                                recordedKeys = emptyList()
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        androidx.compose.material3.TextButton(onClick = {
                            recording = false
                            recorder.reset()
                            recordedKeys = emptyList()
                            capturedChord = emptyList()
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
                        } else null,
                        onPin = { state.store.upsertHotkey(hk.copy(pinned = !hk.pinned)) }
                    )
                }
            }
        }
    }
}

/** 物理按键捕获盒（v2.7.0：桌面端 keysymOrNull 已修复，物理键可真实捕获；ESC 也可录制） */
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
        Text("点击此处后按键（物理键盘或软键盘均可；按错可重按覆盖）", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
