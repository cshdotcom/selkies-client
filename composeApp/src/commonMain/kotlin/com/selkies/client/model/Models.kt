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
    /** 每连接分辨率（0=自适应本机屏幕）——用户要求：每个列表条目可独立设置 */
    var resolutionW: Int = 0,
    var resolutionH: Int = 0,
    var videoBitrate: Int = 8000,       // kbps
    var videoCrf: Int = 24,             // 0 = 使用码率模式
    var encoder: String = "h264enc",    // h264enc / h264enc-striped / h265enc / vp8enc / vp9enc / av1enc / jpeg
    var audioEnabled: Boolean = true,
    // ---- 官方前端视频高级设置（0/false = 不发送走服务器默认）----
    var jpegQuality: Int = 0,               // jpeg_quality 1-100（服务器默认 40）
    var videoFullcolor: Boolean = false,    // video_fullcolor：4:4:4 全色度
    var video10bit: Boolean = false,        // video_10bit：10bit 色深
    var useCpu: Boolean = false,            // use_cpu（软编）
    var forceAlignedResolution: Boolean = false,
    /** video_streaming_mode：官方为布尔（true=Turbo 逐帧编码；false=按损坏门控） */
    var turboMode: Boolean = true,
    var keyframeInterval: Double = -1.0,    // keyframe_interval 秒；-1=不发（服务器默认 0=无限 GOP）
    var videoMinQp: Int = 0,                // video_min_qp 0-51（0=编码器默认）
    var videoMaxQp: Int = 0,                // video_max_qp 0-51（0=默认；35 可大幅改善滚动文字）
    var paintOverCrf: Int = 0,              // video_paintover_crf（服务器默认 18）
    var paintOverBurstFrames: Int = 0,      // video_paintover_burst_frames（服务器默认 5）
    var paintOverJpegQuality: Int = 0,      // paint_over_jpeg_quality（服务器默认 90）
    var usePaintOverQuality: Boolean = false,
    // ---- 音频 / 缩放 ----
    var audioBitrate: Int = 0,              // audio_bitrate bps（0=服务器默认 128000）
    var scalingDpi: Int = 0,                // scaling_dpi 96-288（0=不发）
    // ---- 摄像头上行 ----
    var webcamEncoder: String = "auto",     // webcam_encoder: auto/h264/h265/vp8/vp9/av1/mjpeg
    var webcamWidth: Int = 1280,
    var webcamHeight: Int = 720,
    // ---- 硬件偏好（设备 id，空 = 自动）----
    var preferredMicIds: String = "",       // 逗号分隔，支持多选混音
    var preferredCameraId: String = "",     // 单选
    /** v2.6.0 多扬声器多选（逗号分隔 id）；空 = 系统默认输出 */
    var preferredSpeakerIds: String = "",
    /** v2.6.0 每扬声器独立音量（格式 id:gain;id:gain，gain 0..1） */
    var speakerGains: String = "",
    // ---- 预设 ----
    var preset: String = "balanced",
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

/** 视频质量预设（映射到 ServerEntry 原始字段；与官方设置键一致） */
object VideoPresets {
    data class Preset(val key: String, val label: String, val desc: String)

    val ALL = listOf(
        Preset("smooth", "流畅优先", "H.264 · 30fps · CRF 30：弱网/移动数据省流量"),
        Preset("balanced", "均衡（默认）", "H.264 · 60fps · CRF 24：日常通用"),
        Preset("quality", "画质优先", "H.265 · 60fps · CRF 20：高速网络下最佳观感"),
        Preset("text", "文字清晰", "H.264 · 4:4:4 · 60fps · CRF 18：代码/文档文字最锐利"),
        Preset("cinema", "影像高保真", "H.265 · 10bit · 60fps · CRF 16：照片视频色彩最准"),
        Preset("compat", "最大兼容", "JPEG · 30fps：解码能力最差的环境也能用"),
        Preset("custom", "自定义", "手动调整全部参数")
    )

    /** 应用预设到服务器条目（preset 字段自身由调用方维护） */
    fun apply(e: ServerEntry, key: String) {
        when (key) {
            "smooth" -> {
                e.encoder = "h264enc"; e.framerate = 30; e.videoCrf = 30; e.turboMode = true
                e.videoFullcolor = false; e.video10bit = false
            }
            "balanced" -> {
                e.encoder = "h264enc"; e.framerate = 60; e.videoCrf = 24; e.turboMode = true
                e.videoFullcolor = false; e.video10bit = false
            }
            "quality" -> {
                e.encoder = "h265enc"; e.framerate = 60; e.videoCrf = 20; e.turboMode = true
                e.videoFullcolor = false; e.video10bit = false
            }
            "text" -> {
                e.encoder = "h264enc"; e.framerate = 60; e.videoCrf = 18; e.turboMode = true
                e.videoFullcolor = true; e.video10bit = false
            }
            "cinema" -> {
                e.encoder = "h265enc"; e.framerate = 60; e.videoCrf = 16; e.turboMode = true
                e.videoFullcolor = false; e.video10bit = true
            }
            "compat" -> {
                e.encoder = "jpeg"; e.framerate = 30; e.videoCrf = 0; e.jpegQuality = 60; e.turboMode = true
                e.videoFullcolor = false; e.video10bit = false
            }
        }
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
    // 键盘
    var keyboardScale: Float = 0f,           // 分类键盘缩放（0 = 未设置，自动适配）
    var keyboardFullScale: Float = 0f,       // v2.6.0 完整键盘独立缩放（0 = 未设置，自动适配屏宽）
    var keyboardFullMode: Boolean = false,   // 键盘完整模式（否则分类模式）
    var lastInputMethod: String = "",        // 上次选择的输入方式：float / ime / quick
    // 视频
    var requestKeyframeOnStall: Boolean = true,
    var showFpsOverlay: Boolean = false,
    // 音频（应用内独立音量，不依赖系统）
    var micEnabled: Boolean = false,
    var webcamEnabled: Boolean = false,
    var speakerVolume: Float = 1.0f,
    var audioMuted: Boolean = false,
    // 手柄
    var gamepadRumble: Boolean = true,
    // v2.6.0 虚拟手柄（屏上手柄；无物理手柄也能玩远程游戏）
    var virtualGamepadEnabled: Boolean = false,
    /** 虚拟手柄模式：full = 全键布局 / simple = 精简布局 / wheel = 赛车风格 */
    var virtualGamepadMode: String = "full",
    /** v2.6.0 竖屏时自动旋转远端分辨率填充屏幕（可开关） */
    var autoRotatePortraitRes: Boolean = true,
    /** v2.6.0 工具栏按钮自定义顺序（逗号分隔功能键；空 = 默认顺序） */
    var toolbarOrder: String = "",
    // 截图/录屏/下载 存储位置（空 = 平台默认：Android 公共 Pictures/Videos/Download 下的 Selkies 子目录；桌面 ~/Pictures/Selkies 等）
    var storageRoot: String = "",
    // 录屏
    var recordingIncludeAudio: Boolean = true,
    var recordingNotifyOnSave: Boolean = true,
    // 保持屏幕常亮（会话中）
    var keepScreenOn: Boolean = true
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
    var category: String = "custom",
    var pinned: Boolean = false    // 固定到快捷按钮栏
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
 * path 保持服务器 href 的百分号编码形式（含目录尾斜杠），display 才是解码展示名
 */
data class RemoteFileEntry(
    val name: String,          // 展示名（已解码、无尾斜杠）
    val path: String,          // 相对上传根的编码路径（目录以 / 结尾）
    val isDir: Boolean,
    val size: String,
    val mtime: String
)

/** 保存的媒体文件（截图/录屏/下载）——SavedMediaPanel 浏览 */
data class SavedMediaEntry(
    val path: String,
    val name: String,
    val kind: String,        // screenshot / recording / download
    val sizeBytes: Long,
    val modifiedAt: Long
) {
    val sizeText: String
        get() = when {
            sizeBytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f GB", sizeBytes / 1073741824.0)
            sizeBytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", sizeBytes / 1048576.0)
            sizeBytes >= 1L shl 10 -> String.format(java.util.Locale.US, "%.1f KB", sizeBytes / 1024.0)
            else -> "$sizeBytes B"
        }
}

/** 截图/录屏/下载保存完成事件（UI 弹「打开/打开文件夹/复制路径」引导） */
data class SavedMediaEvent(
    val kind: String,          // screenshot / recording / download
    val path: String?,
    val name: String,
    val detail: String = "",   // 帧数/大小等信息
    val error: String? = null
)

/** 服务器流健康信息（官方 stream_info；统计面板四行状态用） */
data class StreamHealth(
    val backend: String = "",       // x11 / wayland
    val capture: String = "",       // NvFBC / DRI3 / XShm / dmabuf / readback
    val zeroCopy: Boolean = false,
    val encoder: String = "",       // NVENC / VAAPI / x264...
    val hardware: Boolean = false,  // 硬件编码
    val gpuPresent: Boolean = false,
    val hardwareExpected: Boolean = false,
    val codec: String = "",
    val gpu: String = ""
)

/** 打印任务条目（服务器 print_document 通知 → 下载校验 → 系统打印 → 状态跟踪） */
data class PrintJobEntry(
    val name: String,
    val sizeBytes: Long,
    val receivedAt: Long = System.currentTimeMillis(),
    var status: String = "pending",   // pending / downloading / printing / done / error
    var message: String = "",
    var localPath: String? = null
)

/** 硬件设备描述（麦克风/摄像头/扬声器） */
data class HardwareDevice(
    val id: String,
    val name: String,
    val type: String,          // mic / camera / speaker
    val detail: String = ""    // 前置/后置/外接、厂商等
)

/**
 * 应用统计信息（含 stream_stats 完整字段 + 历史采样）
 */
data class StatsInfo(
    var fps: Int = 0,                 // 客户端呈现帧率（本地测量）
    var encodedFps: Double = 0.0,     // 服务器编码帧率
    var latencyMs: Int = 0,           // rtt（服务器 stream_stats）
    var resolution: String = "",
    var codec: String = "",
    var encoderDetail: String = "",
    var bitrateMbps: Double = 0.0,    // video_mbps
    var peakMbps: Double = 0.0,
    var targetMbps: Double = 0.0,
    var connectedAt: Long = 0,
    var bytesReceived: Long = 0,
    var videoFrames: Long = 0,
    var audioLevel: Float = 0f,
    // 服务器管线统计
    var encodeMs: Double = 0.0,
    var pipelineMs: Double = 0.0,
    var pipelineMinMs: Double = 0.0,
    var pipelineMaxMs: Double = 0.0,
    var sendMs: Double = 0.0,
    var sendMinMs: Double = 0.0,
    var sendMaxMs: Double = 0.0,
    var frameMaxKb: Double = 0.0,
    var throttled: Boolean = false,
    // 主机资源
    var cpuPercent: Double = 0.0,
    var memUsed: Long = 0,
    var memTotal: Long = 0,
    var gpuPercent: Double = 0.0,
    var gpuMemUsed: Long = 0,
    var gpuMemTotal: Long = 0,
    // 客户端统计（本地测量，对齐官方 websockets tile 集）
    var decodeMs: Double = 0.0,           // 解码耗时
    var presentMs: Double = 0.0,          // 到达→上屏
    var framesNotShown: Long = 0,         // 解码但未上屏（队列溢出丢弃）
    var lostFrames: Long = 0,             // LOST_FRAME 上报计数
    var keyframeRequests: Long = 0,       // REQUEST_KEYFRAME 发送计数
    var audioBufferMs: Int = 0,           // 音频缓冲（播放器报告）
    var receivedMb: Double = 0.0,         // 累计接收 MB
    var mbpsClient: Double = 0.0,         // 客户端侧带宽（含音频/控制，比 video_mbps 更全）
    // 上行（麦克风/摄像头）统计
    var micActive: Boolean = false,
    var micBytesSent: Long = 0,
    var micUplinkMbps: Double = 0.0,
    var webcamActive: Boolean = false,
    var webcamBytesSent: Long = 0,
    var webcamUplinkMbps: Double = 0.0,
    var uplinkTotalBytes: Long = 0,
    // 历史采样（每秒，最多 120 点，供图表）
    val fpsHistory: ArrayDeque<Double> = ArrayDeque(),
    val encodedFpsHistory: ArrayDeque<Double> = ArrayDeque(),
    val mbpsHistory: ArrayDeque<Double> = ArrayDeque(),
    val rttHistory: ArrayDeque<Double> = ArrayDeque(),
    val decodeHistory: ArrayDeque<Double> = ArrayDeque(),
    val presentHistory: ArrayDeque<Double> = ArrayDeque(),
    val uplinkHistory: ArrayDeque<Double> = ArrayDeque()
) {
    fun pushSample(
        clientFps: Double, encFps: Double, mbps: Double, rtt: Double,
        decodeMs: Double = 0.0, presentMs: Double = 0.0, uplinkMbps: Double = 0.0
    ) {
        fun push(q: ArrayDeque<Double>, v: Double) {
            if (v <= 0.0) return
            q.addLast(v)
            while (q.size > 120) q.removeFirst()
        }
        push(fpsHistory, clientFps)
        push(encodedFpsHistory, encFps)
        push(mbpsHistory, mbps)
        push(rttHistory, rtt)
        push(decodeHistory, decodeMs)
        push(presentHistory, presentMs)
        push(uplinkHistory, uplinkMbps)
    }
}

/**
 * 服务器光标形状（cursor,{curdata,width,height,hotx,hoty,handle} 消息）
 */
data class CursorShape(
    val pngBase64: String,
    val width: Int,
    val height: Int,
    val hotX: Int,
    val hotY: Int,
    val handle: Long
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }
