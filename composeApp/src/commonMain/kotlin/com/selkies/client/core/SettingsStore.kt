package com.selkies.client.core

import com.selkies.client.model.AppSettings
import com.selkies.client.model.HotkeyDef
import com.selkies.client.model.ServerEntry
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 设置与数据持久化（JSON 文件，Android/Desktop 通用）
 */
class SettingsStore(private val configDir: String) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val dir: File get() = File(configDir).apply { mkdirs() }

    val servers = MutableStateFlowCompat<List<ServerEntry>>(emptyList())
    val settings = MutableStateFlowCompat(AppSettings())
    val hotkeys = MutableStateFlowCompat<List<HotkeyDef>>(emptyList())

    private fun serversFile() = File(dir, "servers.json")
    private fun settingsFile() = File(dir, "settings.json")
    private fun hotkeysFile() = File(dir, "hotkeys.json")

    fun loadAll() {
        runCatching {
            if (serversFile().exists()) {
                servers.value = json.decodeFromString(ListSerializer(ServerEntry.serializer()), serversFile().readText())
            }
        }
        runCatching {
            if (settingsFile().exists()) {
                settings.value = json.decodeFromString(AppSettings.serializer(), settingsFile().readText())
                // v2.7.7 测试可观测：加载结果（自动化验证配置生效）
                println("[Selkies][设置] 已加载 settings.json：toolbarOrder=${settings.value.toolbarOrder.take(40)} virtualGamepad=${settings.value.virtualGamepadEnabled}")
            }
        }.onFailure {
            println("[Selkies][设置] settings.json 解析失败：${it.message}")
        }
        runCatching {
            if (hotkeysFile().exists()) {
                hotkeys.value = json.decodeFromString(ListSerializer(HotkeyDef.serializer()), hotkeysFile().readText())
            }
        }
        if (hotkeys.value.isEmpty()) {
            hotkeys.value = BuiltinHotkeys.list()
            saveHotkeys()
        }
    }

    fun saveServers() {
        scope.launch {
            mutex.withLock {
                runCatching {
                    serversFile().writeText(json.encodeToString(ListSerializer(ServerEntry.serializer()), servers.value))
                }
            }
        }
    }

    fun saveSettings() {
        scope.launch {
            mutex.withLock {
                runCatching {
                    settingsFile().writeText(json.encodeToString(AppSettings.serializer(), settings.value))
                }
            }
        }
    }

    fun saveHotkeys() {
        scope.launch {
            mutex.withLock {
                runCatching {
                    hotkeysFile().writeText(json.encodeToString(ListSerializer(HotkeyDef.serializer()), hotkeys.value))
                }
            }
        }
    }

    // ---- 服务器管理 ----
    fun upsertServer(entry: ServerEntry) {
        val list = servers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == entry.id }
        if (idx >= 0) list[idx] = entry else list.add(entry)
        servers.value = list
        saveServers()
    }

    fun deleteServers(ids: Set<String>) {
        servers.value = servers.value.filter { it.id !in ids }
        saveServers()
    }

    fun duplicateServer(id: String): ServerEntry? {
        val src = servers.value.firstOrNull { it.id == id } ?: return null
        val copy = src.copy(id = java.util.UUID.randomUUID().toString(), name = src.name + " (副本)")
        servers.value = servers.value + copy
        saveServers()
        return copy
    }

    /** 导出为 JSON 文本 */
    fun exportServers(ids: Set<String>): String {
        val list = servers.value.filter { ids.isEmpty() || it.id in ids }
        return json.encodeToString(ListSerializer(ServerEntry.serializer()), list)
    }

    /** 从 JSON 导入 */
    fun importServers(text: String): Int {
        val imported = runCatching {
            json.decodeFromString(ListSerializer(ServerEntry.serializer()), text)
        }.getOrNull() ?: return 0
        val existing = servers.value.toMutableList()
        var added = 0
        for (s in imported) {
            val fresh = s.copy(id = java.util.UUID.randomUUID().toString())
            existing.add(fresh)
            added++
        }
        servers.value = existing
        saveServers()
        return added
    }

    /**
     * 响应式更新设置：复制一份新实例再修改并重新赋值，
     * 触发 Compose 重组与 onChange 监听器。
     * （直接改 var 字段不会触发任何通知——这是 v2.0.1 设置开关"点不动"的根因）
     */
    fun updateSettings(mutate: (AppSettings) -> Unit) {
        val s = settings.value.copy()
        mutate(s)
        settings.value = s
        saveSettings()
    }

    // ---- 快捷键 ----
    fun upsertHotkey(def: HotkeyDef) {
        val list = hotkeys.value.toMutableList()
        val idx = list.indexOfFirst { it.id == def.id }
        if (idx >= 0) list[idx] = def else list.add(def)
        hotkeys.value = list
        saveHotkeys()
    }

    fun deleteHotkey(id: String) {
        hotkeys.value = hotkeys.value.filter { it.id != id }
        saveHotkeys()
    }
}

/**
 * 可变状态包装：以 Compose mutableStateOf 为背书，
 * 在 Composable 中读取 .value 即可自动跟踪变化（Android/Desktop 通用）。
 * 赋值时同时通知 onChange 监听器（AppState 等）。
 */
class MutableStateFlowCompat<T>(initial: T) {
    private val state = mutableStateOf(initial)
    var value: T
        get() = state.value
        set(newValue) {
            state.value = newValue
            listeners.forEach { it(newValue) }
        }
    private val listeners = ArrayList<(T) -> Unit>()

    fun onChange(listener: (T) -> Unit) {
        listeners.add(listener)
    }

    fun <R> map(transform: (T) -> R): MutableStateFlowCompat<R> {
        val out = MutableStateFlowCompat(transform(value))
        onChange { out.value = transform(it) }
        return out
    }
}
