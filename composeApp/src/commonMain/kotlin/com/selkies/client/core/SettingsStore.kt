package com.selkies.client.core

import com.selkies.client.model.AppSettings
import com.selkies.client.model.HotkeyDef
import com.selkies.client.model.ServerEntry
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
            }
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

/** 简单的可变状态包装（避免在 common 层直接依赖 androidx mutableStateOf 的生命周期语义） */
class MutableStateFlowCompat<T>(initial: T) {
    var value: T = initial
        set(newValue) {
            field = newValue
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
