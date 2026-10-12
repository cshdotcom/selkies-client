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
}

/** 平台音频播放器（下行 Opus 48kHz） */
interface AudioPlayer {
    fun start(sampleRate: Int = 48000, channels: Int = 2)
    fun playOpus(opus: ByteArray)
    /** 静音标记（服务器告知音频暂停） */
    fun onQuiet()
    fun setVolume(volume: Float)
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
    /** 监听本地剪贴板变化（轮询实现也可以），回调在变更时触发 */
    fun setListener(listener: (() -> Unit)?)
}

/** 平台打印接口 */
interface PrintBridge {
    /** 打印 PDF 文档字节 */
    fun printPdf(pdfBytes: ByteArray, fileName: String, onResult: (Boolean, String?) -> Unit = { _, _ -> })
}

/** 文件选择/保存结果 */
data class PickedFile(val name: String, val path: String, val size: Long)

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
    /** 物理/平台键盘直通钩子（Desktop=AWT 分发器；Android=no-op，走 Compose 键事件） */
    fun setPhysicalKeyHandler(handler: ((keyCode: Int, down: Boolean) -> Unit)?)
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
