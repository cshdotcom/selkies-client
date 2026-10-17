package com.selkies.client.platform

import androidx.compose.runtime.Immutable
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerEvent
import com.selkies.client.model.HardwareDevice
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.StateFlow

/** 视频帧头（0x04 二进制帧解析结果） */
@Immutable
data class VideoFrameHeader(
    val codecId: Int,        // 1=h264 2=vp8 3=vp9 4=av1 5=h265
    val keyframe: Boolean,
    val frameId: Int,
    val stripeY: Int,
    val width: Int,
    val height: Int,
    val refFrameId: Int
) {
    val codecName: String get() = when (codecId) {
        1 -> "h264"; 2 -> "vp8"; 3 -> "vp9"; 4 -> "av1"; 5 -> "h265"; else -> "h264"
    }
}

/** 平台视频渲染器/解码器接口 */
interface VideoSink {
    /** 全帧/条带视频数据（Annex-B 等裸流） */
    fun onVideoFrame(header: VideoFrameHeader, data: ByteArray, offset: Int, length: Int)
    /** JPEG 条带 */
    fun onJpegStripe(frameId: Int, stripeY: Int, data: ByteArray, offset: Int, length: Int)
    /** 重置解码器（分辨率变化/断线重连） */
    fun reset()
    /** 释放资源 */
    fun close()
    /** 首帧已渲染（用于 UI 状态） */
    val firstFrameRendered: StateFlow<Boolean>
    /** 平均解码耗时 ms（EMA；无测量 0.0）——统计面板 decode tile */
    val avgDecodeMs: Double get() = 0.0
    /** 平均到达→上屏耗时 ms（EMA；无测量 0.0）——统计面板 present tile */
    val avgPresentMs: Double get() = 0.0
    /** 因队列溢出丢弃的帧数（累计）——frames_not_shown */
    val framesDropped: Long get() = 0L
    /** 【v2.7.7】解码问题上报（队列溢出/解码异常）→ 会话层强制 REQUEST_KEYFRAME 自愈黑屏。
     *  平台实现（DesktopVideoSink/AndroidVideoSink）覆盖为真实字段；桩实现保持默认空操作。 */
    var onDecodeIssue: ((String) -> Unit)? get() = null; set(@Suppress("UNUSED_PARAMETER") value) {}
}

/** 平台音频播放器（下行 Opus 48kHz） */
interface AudioPlayer {
    fun start(sampleRate: Int = 48000, channels: Int = 2)
    fun playOpus(opus: ByteArray)
    /** 静默标记（服务器告知音频暂停） */
    fun onQuiet()
    fun setVolume(volume: Float)
    /** v2.6.0 多输出设备 + 独立音量：deviceId → 增益(0..1)。
     *  空映射 = 系统默认设备。平台实现：桌面 = 多条 SourceDataLine（各自混音器）；
     *  Android = 多条 AudioTrack（各自 setPreferredDevice）。设备不可用时静默跳过。 */
    fun setOutputDevices(outputs: Map<String, Float>) {}
    /** v2.6.0 播放器错误回调（设备打开失败等；UI 呈现） */
    var onError: ((String) -> Unit)?
        get() = null
        set(_) { /* 默认无操作；平台实现覆写 */ }
    fun stop()
    val bufferLevelMs: Int
}

/** 平台麦克风采集（上行 Opus 24kHz mono；支持多设备混音） */
interface MicCapture {
    /** @param deviceIds 设备 id 列表（多个时客户端混音为单流）；空 = 默认设备 */
    fun start(
        deviceIds: List<String> = emptyList(),
        onOpusFrame: (ByteArray) -> Unit,
        onError: (String) -> Unit
    )
    fun stop()
    val active: Boolean
}

/** 平台摄像头采集（上行编码帧） */
interface WebcamCapture {
    /** codecId: 0=MJPEG 1=H264 2=VP8 3=VP9 4=AV1 5=H265
     *  @param deviceId 指定设备（空 = 默认）；@param targetW/H 期望分辨率 */
    fun start(
        encoder: String,
        deviceId: String = "",
        targetW: Int = 1280,
        targetH: Int = 720,
        onFrame: (codecId: Int, keyframe: Boolean, data: ByteArray) -> Unit,
        onError: (String) -> Unit
    )
    fun requestKeyframe()
    fun stop()
    val active: Boolean
    val width: Int
    val height: Int
}

/** 系统剪贴板桥接 */
interface ClipboardBridge {
    /** 读取本地剪贴板文本；返回 null 表示无文本 */
    fun getText(): String?
    /** 写入本地剪贴板 */
    fun setText(text: String)
    /** 本地剪贴板是否包含图像 */
    fun hasImage(): Boolean
    /** 读取本地剪贴板图像 PNG 字节 */
    fun getImagePng(): ByteArray?
    /** 写入图像到本地剪贴板 */
    fun setImagePng(png: ByteArray)
    /** 旧单监听器（等价于 addListener；保留兼容） */
    fun setListener(listener: (() -> Unit)?) {
        // 默认实现由平台覆写；这里保持接口二进制兼容
    }
    /** 多监听器：会话自动同步与面板 UI 可同时存在（v2.5.0 修复：面板打开/关闭不再打断自动同步） */
    fun addListener(listener: () -> Unit) {}
    fun removeListener(listener: () -> Unit) {}
}

/** 平台打印接口 */
interface PrintBridge {
    /** 打印 PDF 文档字节 */
    fun printPdf(pdfBytes: ByteArray, fileName: String, onResult: (Boolean, String?) -> Unit = { _, _ -> })
}

/**
 * 流复用器：把 WS 直采的已编码帧（H264/H265/VP8/VP9 + Opus 音频）复用为容器文件。
 * Android：MediaMuxer（MP4/WebM，AnnexB→AVCC 自动转换；JPEG 实时转码 H264）
 * 桌面：FFmpeg avformat（MKV 直通零转码；JPEG 转码 H264）
 */
interface StreamMuxer {
    val outputPath: String
    /** v2.7.5：实际保存的可读路径（Android SAF 录屏 = 解析后的真实绝对路径；默认 = outputPath） */
    val realPath: String get() = outputPath
    /** 添加视频轨（codecId 与 0x04 帧头一致：1=h264 2=vp8 3=vp9 4=av1 5=h265） */
    fun addVideoTrack(codecId: Int, width: Int, height: Int): Boolean
    /** 添加 Opus 音频轨 */
    fun addAudioTrack(sampleRate: Int, channels: Int): Boolean
    /** 写一帧编码视频（Annex-B 裸流），ptsUs 呈现时间 */
    fun writeVideoFrame(data: ByteArray, keyframe: Boolean, ptsUs: Long): Boolean
    /** 写一帧 Opus 音频 */
    fun writeAudioFrame(data: ByteArray, ptsUs: Long): Boolean
    /** JPEG 模式：写一帧完整画面（多条带 stripeY+JPEG；平台解码合成后转码入轨） */
    fun writeJpegFrame(parts: List<Pair<Int, ByteArray>>, ptsUs: Long): Boolean
    /** 完成并封装索引（MP4 moov 等）；返回是否成功 */
    fun close(): Boolean
}

/** 流式写入句柄（下载大文件用） */
interface OutputStreamHandle {
    val path: String
    fun write(bytes: ByteArray, off: Int = 0, len: Int = bytes.size)
    fun close()
}

/** 媒体存储：截图/录屏/下载 的分类目录、自动命名、列表、删除 */
interface MediaStoreBridge {
    fun screenshotsDir(): String
    fun recordingsDir(): String
    fun downloadsDir(): String
    /** 保存 PNG 截图，返回保存路径（失败 null） */
    fun saveScreenshot(png: ByteArray, fileName: String): String?
    /** 保存下载文件，返回路径 */
    fun saveDownload(fileName: String, bytes: ByteArray): String?
    /** 流式保存下载（大文件） */
    fun openDownloadStream(fileName: String): OutputStreamHandle?
    /** 创建录屏目标（Android=延迟 MediaStore token；桌面=mkv 文件路径），失败 null */
    fun createRecordingFile(fileName: String): String?
    /** 列出全部已保存媒体（截图/录屏/下载） */
    fun listMedia(): List<com.selkies.client.model.SavedMediaEntry>
    /** 删除一条媒体 */
    fun deleteMedia(entry: com.selkies.client.model.SavedMediaEntry): Boolean
    /** 【v2.7.7】自定义存储位置是否真正生效（设置页「验证写入」显示；默认未知=false） */
    val customRootActive: Boolean get() = false
    /** 【v2.7.7】存储诊断说明（未生效原因；null=正常/无诊断） */
    fun storageDiagnostic(): String? = null
}

/** 文件选择/保存结果 */
data class PickedFile(val name: String, val path: String, val size: Long)

/** v2.7.5：「默认存储位置」选择器结果——用户在系统选择器选定的目录 */
data class PickedDirectory(
    /** 展示用可读路径（桌面=绝对路径；Android 主存储=真实绝对路径，其他卷尽量解析、拿不到时为可读描述） */
    val displayPath: String,
    /** 桌面 = displayPath 同值；Android = SAF tree URI（content://…，持久授权后可跨重启读写） */
    val treeUri: String
)

/** v2.7.4：「另存为」目标——用户在系统选择器选定的可写位置 */
data class PickedSaveTarget(
    /** 展示名（含扩展） */
    val displayName: String,
    /** 打开可写输出流（调用方负责 close） */
    val open: () -> java.io.OutputStream?,
    /** 展示用真实路径（Android SAF 拿不到绝对路径时为 uri.toString） */
    val displayPath: String
)

/** 平台服务集合——由 Android/Desktop 各自提供 */
interface PlatformServices {
    val platformName: String
    fun configDir(): String
    fun clipboard(): ClipboardBridge
    fun print(): PrintBridge
    fun vibrate(ms: Long)
    fun openUrl(url: String)
    /** 选择多个文件（上传用） */
    fun pickFiles(mimeTypes: List<String>, onResult: (List<PickedFile>) -> Unit)
    /** 保存字节到用户选择位置 */
    fun saveFile(suggestedName: String, bytes: ByteArray, onDone: (Boolean) -> Unit)
    /** v2.7.4：弹出系统「另存为」选择器，返回用户选择的可写输出流目标（绝对路径/URI）。
     *  Android=SAF CreateDocument；Desktop=JFileChooser 保存对话框。
     *  用户取消时 onResult(null)。 */
    fun saveFileWithPicker(
        suggestedName: String,
        mime: String,
        onResult: (PickedSaveTarget?) -> Unit
    )
    /** v2.7.5：弹出系统「选择目录」选择器，选默认存储位置（截图/录屏/下载的根目录）。
     *  Android=SAF OpenDocumentTree（持久授权 + 尽量解析真实绝对路径）；Desktop=JFileChooser 目录模式。
     *  用户取消时 onResult(null)。默认实现立即回 null（测试桩/不支持的平台）。 */
    fun pickDirectory(onResult: (PickedDirectory?) -> Unit) { onResult(null) }
    fun makeVideoSink(): VideoSink
    fun makeAudioPlayer(): AudioPlayer
    fun makeMicCapture(): MicCapture
    fun makeWebcamCapture(): WebcamCapture
    /** 是否有物理键盘连接（Android: Configuration.keyboard，Desktop: 恒 true） */
    fun hasHardwareKeyboard(): Boolean
    /** 进入/退出全屏 */
    fun setFullscreen(enabled: Boolean)
    /** 屏幕信息（DPI/尺寸） */
    fun screenDpi(): Float
    fun appVersion(): String
    /** Android: 唤起/隐藏输入法；Desktop: no-op */
    fun showSoftInput(show: Boolean)
    /** Android: 当前是否为触屏设备 */
    fun isTouchDevice(): Boolean
    /** 平台键码 → X11 keysym（物理键盘直通） */
    fun nativeKeyCodeToKeysym(nativeKeyCode: Int, unicodeChar: Int): Int?
    /** 物理/平台键盘直通钩子（Desktop=AWT 分发器；Android=no-op，走 Compose 键事件）
     *  v2.7.0：处理器返回 true = 已消费（AWT 分发器吞掉该键，Compose 不再收到，防双发） */
    fun setPhysicalKeyHandler(handler: ((keyCode: Int, down: Boolean) -> Boolean)?)
    /** 读取本地文件/内容 URI 字节（导入、上传预读等） */
    fun readFileBytes(path: String): ByteArray?
    /** 用系统查看器打开本地文件（下载完成后「打开」） */
    fun openFile(path: String)
    /** 分享本地文件（系统分享面板） */
    fun shareFile(path: String, displayName: String)
    /** 退出应用（Android=finish；Desktop=System.exit） */
    fun exitApp()

    // ---- v2.4.0 硬件选择 ----
    /** 枚举麦克风设备（含内置/外接/蓝牙） */
    fun enumerateMics(): List<HardwareDevice> = emptyList()
    /** 枚举摄像头设备（含前置/后置/外接） */
    fun enumerateCameras(): List<HardwareDevice> = emptyList()
    /** 枚举音频输出设备（扬声器/耳机/蓝牙） */
    fun enumerateSpeakers(): List<HardwareDevice> = emptyList()
    /** 唤起操作系统自带屏幕键盘（Windows osk / Linux onboard / macOS 键盘查看器 / Android 软键盘） */
    fun showOnScreenKeyboard(): Boolean = false
    /** 选择音频输出设备（空 = 默认） */
    fun setSpeakerDevice(deviceId: String) {}
    /** 本地保存打印 PDF 备份；返回保存路径 */
    fun savePrintPdf(fileName: String, pdfBytes: ByteArray): String? = null
    /** 打开本地文件（Android=FileProvider+ACTION_VIEW；Desktop=Desktop.browse） */
    fun openLocalFile(path: String): Boolean = false

    // ---- v2.5.0 媒体与手柄 ----
    /** 打开文件所在目录（文件管理器定位到该文件） */
    fun openFileLocation(path: String) {}
    /** 手柄振动（服务器 rumble 指令 → 本机手柄马达；无手柄/不支持时 no-op） */
    fun rumbleGamepad(slot: Int, strong: Float, weak: Float, ms: Int) {}
    /** 截图当前视频帧 → PNG 字节（Android PixelCopy 直取解码 Surface；桌面取最新位图） */
    fun captureVideoFrame(onDone: (ByteArray?) -> Unit) { onDone(null) }
    /** 平台媒体存储 */
    fun mediaStore(): MediaStoreBridge
    /** 创建流复用器（录屏；已编码帧写入指定路径的容器） */
    fun makeStreamMuxer(outputPath: String): StreamMuxer
    /** 应用获得/失去焦点（Android 后台剪贴板限制的追赶时机） */
    fun notifyAppFocus(gained: Boolean) {}
    /** 当前应用是否持有窗口焦点（Android 后台剪贴板读写限制判定） */
    fun hasAppFocus(): Boolean = true
    /** Android：启动/停止录屏前台服务（后台持续录）；Desktop：注册关闭钩子 */
    fun setRecordingActive(active: Boolean) {}
}

expect fun getPlatformServices(): PlatformServices

/** PNG 字节 → Compose ImageBitmap（Android=BitmapFactory，Desktop=Skia） */
expect fun decodePngToImageBitmap(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap?

/** 平台 HttpClient 工厂（Android/Desktop 均使用 OkHttp 引擎） */
expect fun createHttpClient(allowUntrustedCert: Boolean): HttpClient

/** 平台键事件 → X11 keysym（物理键盘直通，各平台键码不同） */
expect fun KeyEvent.keysymOrNull(): Int?

/** 鼠标按键状态（各平台 API 差异封装） */
expect fun PointerEvent.primaryPressed(): Boolean
expect fun PointerEvent.secondaryPressed(): Boolean
expect fun PointerEvent.tertiaryPressed(): Boolean
