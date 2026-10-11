package com.selkies.client.model

import kotlinx.serialization.Serializable

/**
 * 服务器条目
 */
@Serializable
data class ServerEntry(
    val id: String,
    var name: String,
    var url: String,
    var username: String = "",
    var password: String = "",
    var viewOnly: Boolean = false,
    var displayId: String = "primary",
    var framerate: Int = 60,
    var videoBitrate: Int = 8000,       // kbps
    var videoCrf: Int = 24,             // 0 = 使用码率模式
    var encoder: String = "h264enc",    // h264enc / h264enc-striped / h264enc-fullcolor? / jpeg / ...
    var audioEnabled: Boolean = true,
    var useTokenAuth: Boolean = false,
    var token: String = "",
    var allowUntrustedCert: Boolean = false
) {
    /** 归一化的基础 URL（scheme://host[:port]） */
    fun baseUrl(): String {
        var u = url.trim()
        if (u.isEmpty()) return u
        if (!u.startsWith("http://") && !u.startsWith("https://") && !u.startsWith("ws://") && !u.startsWith("wss://")) {
            u = "https://$u"
        }
        u = u.removeSuffix("/")
        return u
    }
}

/**
 * 应用全局设置（深浅色主题等）
 */
@Serializable
data class AppSettings(
    var themeMode: String = "system",        // system / light / dark
    var dynamicColors: Boolean = true,
    // 剪贴板
    var clipboardAutoSend: Boolean = true,   // 本地复制自动发送到远程
    var clipboardAutoReceive: Boolean = true,// 远程剪贴板自动应用到本地
    var clipboardBinaryEnabled: Boolean = true,
    var clipboardHistorySize: Int = 50,
    // 输入
    var autoShowIme: Boolean = true,         // 进入会话自动唤起输入法（Android）
    var touchMouseMode: Boolean = false,     // 触屏鼠标模式（显示指针）
    var trackpadMode: Boolean = false,       // 触控板模式
    var physicalKeyboardPassthrough: Boolean = true,
    var gamepadEnabled: Boolean = true,
    // 视频
    var requestKeyframeOnStall: Boolean = true,
    var showFpsOverlay: Boolean = false,
    // 音频
    var micEnabled: Boolean = false,
    var webcamEnabled: Boolean = false,
    var speakerVolume: Float = 1.0f
)

/**
 * 快捷键定义
 */
@Serializable
data class HotkeyDef(
    val id: String,
    var name: String,
    var keysyms: List<Long>,       // 修饰键在前，主键在最后
    var builtin: Boolean = false,
    var category: String = "custom"
)

/**
 * 剪贴板历史条目
 */
@Serializable
data class ClipboardHistoryEntry(
    val id: String,
    val timestamp: Long,
    val mime: String,
    val preview: String,
    val sizeBytes: Long,
    val fromRemote: Boolean
)

/**
 * 文件传输任务
 */
@Serializable
data class TransferTask(
    val id: String,
    val fileName: String,
    val filePath: String,
    var totalBytes: Long,
    var sentBytes: Long = 0,
    var status: String = "pending",   // pending / running / done / error
    var message: String = "",
    val upload: Boolean = true,
    var speed: Double = 0.0
)

/**
 * 远程文件条目（文件列表）
 */
data class RemoteFileEntry(
    val name: String,
    val isDir: Boolean,
    val size: String,
    val mtime: String
)

/**
 * 应用统计信息
 */
data class StatsInfo(
    var fps: Int = 0,
    var latencyMs: Int = 0,
    var resolution: String = "",
    var codec: String = "",
    var bitrateMbps: Double = 0.0,
    var connectedAt: Long = 0,
    var bytesReceived: Long = 0,
    var videoFrames: Long = 0,
    var audioLevel: Float = 0f
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }
