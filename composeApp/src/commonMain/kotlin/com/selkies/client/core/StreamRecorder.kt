package com.selkies.client.core

import com.selkies.client.platform.VideoFrameHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WS 流直采录屏引擎（v2.5.0）：
 *
 * 不依赖系统录屏权限——直接从 WebSocket 接收路径捕获已编码的媒体帧：
 *  - 0x04 H.264/H.265/VP8/VP9 帧 → 原样复用进 MP4/MKV（零转码、零画质损失、极低 CPU）
 *  - 0x03 JPEG 条带 → 完整帧合成后交平台转码 H.264 入轨
 *  - 0x01 Opus 音频帧（可选）→ Opus 音轨
 *
 * 时间戳采用帧到达墙钟（录屏期间画面静止 = 文件中真实停顿，回放节奏与所见一致）。
 * 挂进 SelkiesSession.onBinary，会话断开/退出时自动 stopAndSave()（用户要求：
 * 「退出应用默认视为保存」「杀进程前先保存」由 Android 前台服务 onDestroy 兜底）。
 */
class StreamRecorder(
    private val muxerFactory: (String) -> com.selkies.client.platform.StreamMuxer
) {
    enum class State { IDLE, RECORDING, FINALIZING, DONE }

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var startedAt: Long = 0
        private set

    /** 已录制的视频帧数（诊断/统计面板显示） */
    @Volatile
    var videoFramesWritten: Long = 0
        private set

    /** 已录制的音频帧数 */
    @Volatile
    var audioFramesWritten: Long = 0
        private set

    /** v2.7.9【修「录完显示 0 秒」】：停止时定格的真实时长（秒）。
     *  旧版保存回调里调 durationSec()——那时 state 已是 DONE 恒返回 0 →
     *  保存提示永远「X 帧 · 0s」。 */
    @Volatile
    var lastDurationSec: Long = 0
        private set

    @Volatile
    var bytesCaptured: Long = 0
        private set

    /** 录屏完成（无论成功与否）→ 回调（路径 null = 失败/无内容）
     *  v2.7.0：kind = "final"（用户停止/退出）| "segment"（分辨率切换自动分段，UI 静默处理）| "empty"（空录制） */
    var onSaved: ((path: String?, frames: Long, error: String?, kind: String) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var muxer: com.selkies.client.platform.StreamMuxer? = null
    private var outputPath: String? = null
    private var startNanos = 0L
    private var videoTrackReady = false
    private var audioTrackReady = false
    private var videoCodecId = -1
    private var width = 0
    private var height = 0

    // JPEG 条带合成（frameId 变更时上一帧即完整）
    private var jpegFrameId = -1
    private var jpegCanvasW = 0
    private var jpegCanvasH = 0
    private var jpegParts = ArrayList<Pair<Int, ByteArray>>() // (stripeY, jpeg)
    private var lastJpegPts = 0L

    // v2.7.0：缓冲硬上限（防 OOM 闪退——旧版 240 帧高码率关键帧可累积 >100MB）
    private var pendingBytes = 0L

    /** 是否包含远程音频 */
    var includeAudio = true

    /** 开始录制：outputPath 由调用方（platform mediaStore.createRecordingFile）生成 */
    @Synchronized
    fun start(path: String): Boolean {
        if (state == State.RECORDING || state == State.FINALIZING) return false
        outputPath = path
        videoTrackReady = false
        audioTrackReady = false
        videoCodecId = -1
        width = 0
        height = 0
        videoFramesWritten = 0
        audioFramesWritten = 0
        bytesCaptured = 0
        jpegFrameId = -1
        jpegParts.clear()
        startedAt = System.currentTimeMillis()
        startNanos = System.nanoTime()
        state = State.RECORDING
        return true
    }

    private fun ptsNow(): Long = (System.nanoTime() - startNanos) / 1000

    /** 0x04 视频帧（由 SelkiesSession.onBinary 转发；仅全帧 stripeY==0） */
    @Synchronized
    fun onVideoFrame(header: VideoFrameHeader, data: ByteArray, offset: Int, length: Int) {
        if (state != State.RECORDING) return
        if (header.stripeY != 0) return
        try {
            val m = muxer
            if (m == null) {
                // 惰性创建：首帧到达才知道路径是否可用（path 在 start() 已定）
                val p = outputPath ?: return
                muxer = muxerFactory(p)
                videoCodecId = header.codecId
                width = header.width
                height = header.height
                videoTrackReady = muxer?.addVideoTrack(header.codecId, header.width, header.height) == true
                if (!videoTrackReady) {
                    muxer?.close()
                    muxer = null
                    state = State.DONE
                    onSaved?.invoke(null, 0, "此设备不支持录制 ${header.codecName} 流", "final")
                    return
                }
            } else if (header.codecId != videoCodecId || header.width != width || header.height != height) {
                // 分辨率/编码器切换：结束当前段（自动分段保存，会话层续开新段）
                stopAndSaveInternal(trigger = "分辨率切换")
                return
            }
            if (m !== muxer) return // stopAndSaveInternal 已换状态
            val payload = data.copyOfRange(offset, length)
            if (muxer?.writeVideoFrame(payload, header.keyframe, ptsNow()) == true) {
                videoFramesWritten++
                bytesCaptured += payload.size
            }
        } catch (_: Throwable) {
        }
    }

    /** 0x03 JPEG 条带（frameId 变更时冲刷上一完整帧） */
    @Synchronized
    fun onJpegStripe(frameId: Int, stripeY: Int, data: ByteArray, offset: Int, length: Int) {
        if (state != State.RECORDING) return
        try {
            if (frameId != jpegFrameId) {
                flushJpegFrame()
                jpegFrameId = frameId
                jpegParts.clear()
            }
            val copy = data.copyOfRange(offset, length)
            jpegParts.add(stripeY to copy)
            if (jpegParts.size > 64) { // 异常保护
                jpegParts.removeAt(0)
            }
        } catch (_: Throwable) {
        }
    }

    private fun flushJpegFrame() {
        if (jpegParts.isEmpty()) return
        try {
            if (muxer == null) {
                val p = outputPath ?: return
                muxer = muxerFactory(p)
                // v2.7.9：codecId 1→0——桌面 muxer 的 JPEG 路径是 MJPEG 直通（要求 addVideoTrack(0)）；
                //   旧版传 1 = H264 ctx → jpeg 帧全被拒（桌面 jpeg 录屏 0 帧）+ 编码器切换场景
                //   触发 writeHeader 半初始化崩溃。Android 端 writeJpegFrame 内部自定向（无影响）。
                videoCodecId = 0
                videoTrackReady = muxer?.addVideoTrack(0, 0, 0) == true
            }
            val pts = if (lastJpegPts == 0L) ptsNow() else lastJpegPts
            val snapshot = ArrayList(jpegParts)
            if (muxer?.writeJpegFrame(snapshot, pts) == true) {
                videoFramesWritten++
                bytesCaptured += snapshot.sumOf { it.second.size.toLong() }
            }
        } catch (_: Throwable) {
        } finally {
            jpegParts = ArrayList()
        }
    }

    /** 0x01 音频帧（主 Opus 载荷，由会话解析后转发） */
    @Synchronized
    fun onAudioFrame(opus: ByteArray) {
        if (state != State.RECORDING || !includeAudio || opus.isEmpty()) return
        try {
            if (!audioTrackReady) {
                if (muxer == null) return // 视频未启动则不建音频轨（容器以视频为主导）
                audioTrackReady = muxer?.addAudioTrack(48000, 2) == true
                if (!audioTrackReady) return // 该平台容器不支持 Opus → 静默跳过音频
            }
            if (muxer?.writeAudioFrame(opus, ptsNow()) == true) {
                audioFramesWritten++
                bytesCaptured += opus.size
            }
        } catch (_: Throwable) {
        }
    }

    /** 停止并保存（幂等；可在任意线程调用） */
    fun stopAndSave(trigger: String = "manual") {
        var path: String? = null
        var frames = 0L
        var error: String? = null
        var kind = if (trigger == "分辨率切换") "segment" else "final"
        synchronized(this) {
            if (state != State.RECORDING) return
            state = State.FINALIZING
            // v2.7.9：先定格真实时长（state 变 DONE 后 durationSec() 会归零）
            lastDurationSec = (System.currentTimeMillis() - startedAt) / 1000
            flushJpegFrame()
            try {
                muxer?.let { m ->
                    // v2.7.5：优先用 realPath（Android SAF 录屏 = 用户可读的真实绝对路径）
                    path = if (m.realPath.isNotBlank()) m.realPath else m.outputPath
                    val ok = m.close()
                    if (!ok) error = "封装失败"
                }
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            }
            muxer = null
            frames = videoFramesWritten
            state = State.DONE
        }
        if (videoFramesWritten == 0L && audioFramesWritten == 0L) {
            // 空录制：删除文件避免垃圾
            path?.let { runCatching { java.io.File(it).delete() } }
            onSaved?.invoke(null, 0, "录制内容为空（未收到可写入的视频帧——已自动请求关键帧重试）", "empty")
            return
        }
        onSaved?.invoke(path, frames, error, kind)
    }

    private fun stopAndSaveInternal(trigger: String) {
        // 分辨率切换分段：直接结束（onSaved 通知 UI 重建新段）
        stopAndSave(trigger)
    }

    /** 当前录制时长（秒） */
    fun durationSec(): Long = if (state == State.RECORDING) (System.currentTimeMillis() - startedAt) / 1000 else 0

    companion object {
        /** 自动命名：selkies_recording_20261012_143025 */
        fun autoName(extension: String): String {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            return "selkies_recording_$ts$extension"
        }

        fun autoScreenshotName(): String {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            return "selkies_screenshot_$ts.png"
        }
    }
}
