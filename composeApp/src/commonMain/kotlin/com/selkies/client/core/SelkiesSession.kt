package com.selkies.client.core

import com.selkies.client.model.AppSettings
import com.selkies.client.model.CursorShape
import com.selkies.client.model.HardwareDevice
import com.selkies.client.model.PrintJobEntry
import com.selkies.client.model.SavedMediaEvent
import com.selkies.client.model.ServerEntry
import com.selkies.client.model.StatsInfo
import com.selkies.client.model.StreamHealth
import com.selkies.client.platform.PlatformServices
import com.selkies.client.platform.PrintBridge
import com.selkies.client.platform.VideoSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * Selkies 会话：协议状态机 + 重连 + 剪贴板 + 音视频路由
 */
class SelkiesSession(
    val server: ServerEntry,
    val platform: PlatformServices,
    private val settingsProvider: () -> AppSettings,
    private val onClipboardFromRemote: (text: String?, mime: String) -> Unit = { _, _ -> },
    private val onPrintDocument: (name: String, sizeBytes: Long) -> Unit = { _, _ -> }
) : SelkiesConnection.Listener {

    enum class Status { IDLE, CONNECTING, CONNECTED, RECONNECTING, FAILED, CLOSED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val status = MutableStateFlow(Status.IDLE)
    val statusDetail = MutableStateFlow("")
    val lastError = MutableStateFlow<String?>(null)
    val lastErrorTechnical = MutableStateFlow<String?>(null)
    val stats = MutableStateFlow(StatsInfo())
    /** 用户可见轻提示（打印到达/传输完成/硬件错误等，UI 自动消费） */
    val notices = MutableStateFlow<String?>(null)
    val realizedResolution = MutableStateFlow<Pair<Int, Int>?>(null)
    val apps = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val role = MutableStateFlow("controller")
    val realizedResolutionFlow: StateFlow<Pair<Int, Int>?> get() = realizedResolution

    /** 本地回显的远程光标位置（流坐标）——绝对移动置位、相对移动累加 */
    val cursorX = MutableStateFlow(-1f)
    val cursorY = MutableStateFlow(-1f)
    /** 服务器推送的光标形状（cursor,{curdata,...} 消息，按 handle 去重） */
    val cursorShape = MutableStateFlow<CursorShape?>(null)

    /** 服务器设置的镜像（encoder 等） */
    val serverSettings = MutableStateFlow<Map<String, kotlinx.serialization.json.JsonElement>>(emptyMap())

    // ---- v2.7.0：服务器能力开关（server_settings 解析；UI 据此禁用入口并给出明确提示） ----
    /** 服务器是否允许音频下行（audio_enabled） */
    val serverAudioEnabled = MutableStateFlow(true)
    /** 服务器是否允许麦克风上行（microphone_enabled；实测存在 false 的部署） */
    val serverMicEnabled = MutableStateFlow(true)
    /** 服务器是否允许摄像头上行（webcam_enabled） */
    val serverWebcamEnabled = MutableStateFlow(true)
    /** 音频流状态提示（AUDIO_STARTED 但无帧时自动恢复/告知用户；null = 正常） */
    val audioStallNotice = MutableStateFlow<String?>(null)

    /** 当前生效编码器（服务器通知） */
    val currentEncoder = MutableStateFlow("h264enc")

    /** 服务器上报的音频声道数（默认 2，与官方 audio_channels 一致） */
    val audioChannels = MutableStateFlow(2)

    /** 打印任务队列（下载校验→系统打印→状态跟踪） */
    val printJobs = MutableStateFlow<List<PrintJobEntry>>(emptyList())

    /** 硬件设备列表（UI 打开硬件面板时刷新） */
    val micDevices = MutableStateFlow<List<HardwareDevice>>(emptyList())
    val cameraDevices = MutableStateFlow<List<HardwareDevice>>(emptyList())
    val speakerDevices = MutableStateFlow<List<HardwareDevice>>(emptyList())

    /** 当前选中的麦克风设备 id 列表（多选混音） */
    val selectedMicIds = MutableStateFlow<List<String>>(emptyList())

    /** 当前选中的摄像头设备 id（多选：多路合成上行；空 = 默认） */
    val selectedCameraId = MutableStateFlow("")

    /** v2.6.0 当前选中的扬声器 id 列表（多选；空 = 系统默认输出） */
    val selectedSpeakerIds = MutableStateFlow<List<String>>(emptyList())
    /** v2.6.0 每扬声器独立音量（id → 0..1） */
    val speakerGains = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** 麦克风/摄像头上行开关（UI 观察） */
    val micActive = MutableStateFlow(false)
    val webcamActive = MutableStateFlow(false)

    /** 录屏引擎（WS 流直采，无系统录屏权限） */
    val recorder = StreamRecorder { path -> platform.makeStreamMuxer(path) }

    /** v2.7.0：会话级录制中标志（含自动分段切换瞬间；UI 观察它而非 recorder.state，避免段间闪烁） */
    val recordingActive = MutableStateFlow(false)

    /** 截图/录屏保存结果通知（路径 + 帧数 + 错误；UI 弹保存引导） */
    val savedMediaEvent = MutableStateFlow<SavedMediaEvent?>(null)

    /** 应用内音量（0..1，独立于系统音量）与静音 */
    val appVolume = MutableStateFlow(-1f)   // -1 = 未初始化（从 settings 读）
    val appMuted = MutableStateFlow(false)

    /** 服务器流健康信息（官方 stream_info → 四行状态显示） */
    val streamHealth = MutableStateFlow<StreamHealth?>(null)

    /** 手柄心跳定时器（js,h 保活） */
    private var gamepadHeartbeatJob: Job? = null

    var videoSink: VideoSink? = null
        set(value) {
            field?.let { old -> if (value !== old) old.close() }
            field = value
            watchSinkFirstFrame(value)
        }

    /** v2.6.0+ 视频流可靠性三重加固之一：
     *  监听解码器 firstFrameRendered —— 帧在到达但长时间解码不出（中途加入 GOP，
     *  P 帧引用未见过的关键帧，ffmpeg 日志狂刷 non-existing PPS）时主动 REQUEST_KEYFRAME。
     *  旧版仅看"帧停顿"（无帧到达），这类"帧到达但永不解码"的黑屏无任何自息。 */
    private var firstDecodedFrameAt = 0L
    private var firstFrameArrivedAt = 0L
    private var decodeStallKeyframeAt = 0L
    private fun watchSinkFirstFrame(sink: VideoSink?) {
        if (sink == null) { firstDecodedFrameAt = 0; return }
        scope.launch {
            runCatching {
                sink.firstFrameRendered.collect { rendered ->
                    if (rendered && firstDecodedFrameAt == 0L) {
                        firstDecodedFrameAt = System.currentTimeMillis()
                        println("[Selkies][看门狗] 首帧解码成功，解码停滞检测解除")
                    }
                }
            }
        }
    }

    val audioPlayer by lazy {
        platform.makeAudioPlayer().also { p ->
            p.onError = { msg -> notices.value = "音频：$msg" }
        }
    }
    val micCapture by lazy { platform.makeMicCapture() }
    val webcamCapture by lazy { platform.makeWebcamCapture() }
    val filesClient by lazy { FilesClient(this) }

    // ---- v2.7.0：音频停看门狗（AUDIO_STARTED 但服务器无帧 → 自动重启音频管线 + 状态透明化） ----
    @Volatile private var audioStartedAt = 0L
    @Volatile private var audioFramesSinceStart = 0L
    @Volatile private var audioReviveAttempts = 0
    private var audioStallJob: Job? = null

    private fun startAudioStallWatchdog() {
        if (audioStallJob?.isActive == true) return
        audioStallJob = scope.launch {
            while (isActive) {
                delay(2000)
                if (audioStartedAt == 0L || userClosed) continue
                val elapsed = System.currentTimeMillis() - audioStartedAt
                if (audioFramesSinceStart > 0) {
                    // 帧已流动：一切正常，清除提示并重置恢复计数（下次停滞可获得新一轮自动恢复）
                    if (audioStallNotice.value != null) audioStallNotice.value = null
                    audioReviveAttempts = 0
                    continue
                }
                if (elapsed < 8000) continue  // 等待首个音频帧的宽限期
                // AUDIO_STARTED 后 8 秒无任何音频帧 → 服务器音频管线疑似停滞
                if (audioReviveAttempts < 3) {
                    audioReviveAttempts++
                    audioStallNotice.value = "远程音频无数据，正在尝试恢复（第 $audioReviveAttempts 次）…"
                    post {
                        runCatching {
                            connection.sendText("STOP_AUDIO")
                            delay(600)
                            connection.sendText("START_AUDIO")
                        }
                        audioStartedAt = System.currentTimeMillis()
                        audioFramesSinceStart = 0
                    }
                } else {
                    audioStallNotice.value = "远程音频无数据（服务器端音频管线异常）——视频不受影响；可尝试断开后重连"
                    stopAudioStallWatchdog()
                }
            }
        }
    }

    private fun stopAudioStallWatchdog() {
        audioStallJob?.cancel()
        audioStallJob = null
    }

    lateinit var connection: SelkiesConnection
        private set

    // ---- 内部状态 ----
    private var modeReceived = false
    /** 本连接的标签页标识（模拟官方网页客户端 pageTabId） */
    private val tabId = UUID.randomUUID().toString().replace("-", "").take(12)
    private var videoRequested = false
    private var lastReceivedFrameId = -1
    private var lastReceivedFrameAt = 0L
    private val audioParseState = Protocol.AudioParseState()
    private var gzRxEnabled = false
    private var suppressClipboardEcho = false // v2.6.0: 跨线程读写（ioScope 写 / 监听器线程读），用锁保护
    private val clipboardEchoLock = Any()
    private var lastRemoteClipboardText: String? = null
    /** v2.6.0 远程剪贴板内容（text?, mime）——UI（剪贴板面板等）直接观察，修复旧版回调未接线导致面板永远不更新 */
    val remoteClipboard = MutableStateFlow<Pair<String?, String>?>(null)
    /** v2.6.0 剪贴板同步事件提示（用户反馈：发送/接收成功或失败） */
    val clipboardNotice = MutableStateFlow<String?>(null)
    private var clipboardMultipart: StringBuilder? = null
    private var clipboardMultipartMime = "text/plain"
    private var clipboardMultipartExpected = 0L
    private var clipboardMultipartIsReply = false
    private var awaitingReplyVerbs = mutableSetOf<String>()
    // ---- v2.7.0：剪贴板可靠同步（轮询 + 焦点拉取；覆盖服务器 announcement 失效的部署） ----
    private var clipboardPollJob: Job? = null
    /** 收到大二进制载荷后暂停轮询（避免大图反复全量拉取） */
    @Volatile private var clipboardPollBackoffUntil = 0L
    private var userClosed = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var ackTimer: Job? = null
    private var watchdogTimer: Job? = null
    private var startVideoAttempts = 0
    private var clipboardTid = 0
    private var statsSubscriptionActive = false
    /** 每秒统计采样（本地 fps + 服务器统计 → 历史序列，供图表） */
    private var statsSampler: Job? = null
    private var localFrameCount = 0L
    private var localFrameWindowStart = 0L
    /** 上行字节窗口（统计：mic/webcam Mbps） */
    private var uplinkBytesWindow = 0L
    private var uplinkWindowStart = 0L
    /** 剪贴板：待应用内容（Android 后台写剪贴板受限 → 回焦点时补写） */
    private var pendingClipboardWrite: String? = null
    private var pendingClipboardImage: ByteArray? = null
    /** 帧停顿看门狗：CONNECTED 且已请求视频但超时无新帧 → 自动 REQUEST_KEYFRAME */
    private var stallWatchdog: Job? = null
    private var lastKeyframeRequestAt = 0L
    /** 指针消息递增序列号（官方 _pointerSeq，多线程安全） */
    private val pointerSeq = java.util.concurrent.atomic.AtomicInteger(0)
    /** 当前按住的 keysym（用于 kh 心跳，官方 100ms 周期） */
    private val heldKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private var keyHeartbeatJob: Job? = null

    private val startedAt = System.currentTimeMillis()

    fun settings(): AppSettings = settingsProvider()

    // =====================================================================
    // 生命周期
    // =====================================================================

    fun connect() {
        if (status.value != Status.IDLE && status.value != Status.FAILED && status.value != Status.CLOSED) return
        userClosed = false
        reconnectAttempts = 0
        startVideoAttempts = 0
        status.value = Status.CONNECTING
        statusDetail.value = "正在连接服务器..."
        modeReceived = false
        videoRequested = false
        lastReceivedFrameId = -1
        openConnection()
    }

    private fun openConnection() {
        // ⚠️【泄漏修复】重连前必须释放上一条连接（OkHttp 连接池/半开 socket/线程）。
        //    旧代码直接覆盖引用，重连风暴下 socket 逐次泄漏，最终引发
        //    ConnectException（拒绝连接）/文件描述符耗尽——即用户报的
        //    "报错重连→401→拒绝连接"链条的客户端侧根源。
        if (::connection.isInitialized) {
            runCatching { connection.close() }
        }
        // v2.6.0+：新连接的帧/解码追踪归零（否则旧值会让看门狗误判而永不重臂）
        lastReceivedFrameId = -1
        lastReceivedFrameAt = 0L
        firstFrameArrivedAt = 0L
        decodeStallKeyframeAt = 0L
        connection = SelkiesConnection(server, this)
        connection.start()
        startStallWatchdog()
    }

    /** 计算本连接应请求的分辨率：每连接配置 > 自适应本机屏幕 > 默认 1280x720 */
    private fun desiredResolution(): Pair<Int, Int> {
        if (server.resolutionW > 0 && server.resolutionH > 0) {
            return alignRes(server.resolutionW) to alignRes(server.resolutionH)
        }
        // 自适应：跟随客户端屏幕物理像素
        // v2.6.0：竖屏时若开启「竖屏自动旋转分辨率」，直接请求竖屏尺寸填满屏幕（旧版恒横向桌面 = 小画面）
        val sw = screenWidthPx
        val sh = screenHeightPx
        if (sw > 0 && sh > 0) {
            val portraitView = sh > sw
            if (portraitView && settings().autoRotatePortraitRes) {
                return alignRes(sw) to alignRes(sh)
            }
            return if (sw >= sh) alignRes(sw) to alignRes(sh) else alignRes(sh) to alignRes(sw)
        }
        return 1280 to 720
    }

    fun disconnect() {
        userClosed = true
        reconnectJob?.cancel()
        ackTimer?.cancel()
        watchdogTimer?.cancel()
        stallWatchdog?.cancel()
        statsSampler?.cancel()
        keyHeartbeatJob?.cancel()
        stopGamepadHeartbeat()
        stopClipboardPoll()
        stopAudioStallWatchdog()
        recordingActive.value = false
        heldKeys.clear()
        // 录屏中退出会话 → 自动保存（用户要求：退出默认视为保存）
        if (recorder.state == StreamRecorder.State.RECORDING) {
            recorder.stopAndSave("session-end")
        }
        runCatching { micCapture.stop() }
        runCatching { webcamCapture.stop() }
        runCatching { audioPlayer.stop() }
        if (::connection.isInitialized) connection.close()
        videoSink?.let { runCatching { it.close() } }
        videoSink = null
        status.value = Status.CLOSED
        statusDetail.value = "已断开"
    }

    private fun nextPointerSeq(): Int = pointerSeq.incrementAndGet()

    // =====================================================================
    // 连接回调
    // =====================================================================

    override fun onOpened() {
        status.value = Status.CONNECTED
        statusDetail.value = "连接已建立，等待服务器模式..."
        lastError.value = null
        reconnectAttempts = 0
        ioScope.launch {
            // gzip 能力宣告
            connection.sendText("_gz,1")
            sendInitialSettings()
            // 服务器端光标渲染进视频流（官方 SET_NATIVE_CURSOR_RENDERING，与本地覆盖层双保险）
            connection.sendText("SET_NATIVE_CURSOR_RENDERING,1")
            // 请求服务器剪贴板（cache-only）
            connection.sendText("cr")
        }
        // v2.7.0：剪贴板可靠同步——定向轮询拉取（REQUEST_CLIPBOARD 响应未打标记，
        // 与 announcement 同路处理；内容未变时静默去重，不会反复覆盖本地剪贴板）。
        // 覆盖实测「服务器剪贴板变更推送（announcement）不工作」的部署。
        startClipboardPoll()
        startAckLoop()
    }

    /** v2.7.0：剪贴板轮询（每 5s，仅在前台且开启自动接收时；服务器去抖 0.25s 远低于此间隔） */
    private fun startClipboardPoll() {
        if (clipboardPollJob?.isActive == true) return
        clipboardPollJob = scope.launch {
            while (isActive && !userClosed) {
                delay(5000)
                if (userClosed || status.value != Status.CONNECTED) continue
                if (!settings().clipboardAutoReceive || !settings().clipboardPollSync) continue
                if (!hasWindowFocus()) continue
                if (System.currentTimeMillis() < clipboardPollBackoffUntil) continue
                post { runCatching { connection.sendText("REQUEST_CLIPBOARD") } }
            }
        }
    }

    private fun stopClipboardPoll() {
        clipboardPollJob?.cancel()
        clipboardPollJob = null
    }

    private suspend fun sendInitialSettings() {
        val s = settings()
        val (defW, defH) = desiredResolution()
        val payload = LinkedHashMap<String, Any>()
        payload["manual_resolution"] = false
        payload["initialClientWidth"] = defW
        payload["initialClientHeight"] = defH
        payload["scaling_dpi"] = 96
        payload["framerate"] = server.framerate
        payload["keyboardLayout"] = "us"
        payload["useCssScaling"] = false
        payload["displayId"] = server.displayId
        // tabId：官方网页客户端每标签页一个随机 ID，服务器据此区分 owner/旁观连接（缺失会导致 START_VIDEO 不启动捕获）
        payload["tabId"] = tabId
        payload["displayScale"] = 1
        payload["audioRedundancy"] = true
        payload["encoder"] = server.encoder
        if (server.videoCrf in 1..51) {
            payload["video_crf"] = server.videoCrf
            payload["rate_control_mode"] = "crf"
        } else {
            payload["video_bitrate"] = server.videoBitrate
            // ⚠️ 官方合法枚举仅 cbr/crf（旧版误发 "bitrate" 会被服务器拒绝）
            payload["rate_control_mode"] = "cbr"
        }
        payload["enable_binary_clipboard"] = s.clipboardBinaryEnabled
        // ---- 官方前端视频高级设置（仅非默认时发送，与官方 postSetting 键名一致）----
        if (server.jpegQuality in 1..100) payload["jpeg_quality"] = server.jpegQuality
        if (server.videoFullcolor) payload["video_fullcolor"] = true
        if (server.video10bit) payload["video_10bit"] = true
        if (server.useCpu) payload["use_cpu"] = true
        if (server.forceAlignedResolution) payload["force_aligned_resolution"] = true
        // video_streaming_mode：官方为布尔（true = Turbo 逐帧编码）
        payload["video_streaming_mode"] = server.turboMode
        if (server.keyframeInterval >= 0) payload["keyframe_interval"] = server.keyframeInterval
        if (server.videoMinQp in 1..51) payload["video_min_qp"] = server.videoMinQp
        if (server.videoMaxQp in 1..51) payload["video_max_qp"] = server.videoMaxQp
        if (server.paintOverCrf in 1..51) payload["video_paintover_crf"] = server.paintOverCrf
        if (server.paintOverBurstFrames > 0) payload["video_paintover_burst_frames"] = server.paintOverBurstFrames
        if (server.paintOverJpegQuality in 1..100) payload["paint_over_jpeg_quality"] = server.paintOverJpegQuality
        if (server.usePaintOverQuality) payload["use_paint_over_quality"] = true
        if (server.audioBitrate >= 6000) payload["audio_bitrate"] = server.audioBitrate
        if (server.scalingDpi in 96..288) payload["scaling_dpi"] = server.scalingDpi
        if (server.webcamEncoder != "auto") payload["webcam_encoder"] = server.webcamEncoder
        connection.sendText("SETTINGS," + jsonEncode(payload))
    }

    /** 会话内动态应用设置（部分 SETTINGS；服务器按字段热更新/重启捕获自行处理） */
    fun applySettings(patch: Map<String, Any>) = post {
        if (patch.isNotEmpty()) {
            connection.sendText("SETTINGS," + jsonEncode(patch))
        }
    }

    /** 单值热路径：视频码率（vb） */
    fun sendVideoBitrate(kbps: Int) = post { connection.sendText("vb,$kbps") }

    /** 单值热路径：音频码率（ab） */
    fun sendAudioBitrate(bps: Int) = post { connection.sendText("ab,$bps") }

    /** 单值热路径：帧率（_arg_fps） */
    fun sendFramerate(fps: Int) = post { connection.sendText("_arg_fps,$fps") }

    /** UI 层在连接前设置屏幕物理像素尺寸 */
    var screenWidthPx: Int = 0
    var screenHeightPx: Int = 0

    private fun jsonEncode(map: Map<String, Any>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(",")
            first = false
            sb.append(jsonEscape(k)).append(":")
            when (v) {
                is Boolean -> sb.append(v)
                is Int -> sb.append(v)
                is Long -> sb.append(v)
                is Double -> sb.append(v)
                else -> sb.append(jsonEscape(v.toString()))
            }
        }
        sb.append("}")
        return sb.toString()
    }

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    override fun onText(text: String) {
        // JSON 对象消息
        if (text.startsWith("{")) {
            handleJsonText(text)
            return
        }
        when {
            text == "MODE websockets" -> {
                modeReceived = true
                statusDetail.value = "已进入 WebSocket 流模式"
                if (!videoRequested) {
                    videoRequested = true
                    ioScope.launch {
                        if (server.viewOnly) {
                            // 官方 viewer/共享模式流程：先 STOP 再延迟 250ms START，服务器才会为旁观者启动视频
                            connection.sendText("STOP_VIDEO")
                            delay(250)
                        }
                        // 请求分辨率 + 启动视频（每连接配置 > 自适应屏幕 > 默认）
                        val (w, h) = desiredResolution()
                        connection.sendText(Protocol.resolution(w, h, server.displayId))
                        connection.sendText("START_VIDEO")
                        if (server.audioEnabled) connection.sendText("START_AUDIO")
                        subscribeStats()
                        armStartVideoWatchdog()
                    }
                }
            }
            text.startsWith("AUTH_SUCCESS,") -> parseAuthSuccess(text)
            text.startsWith("ROLE_UPDATE,") -> parseAuthSuccess(text.replaceFirst("ROLE_UPDATE", "AUTH_SUCCESS"))
            text.startsWith("MK_ACCESS,") -> { /* 键鼠权限提示，暂不处理 */ }
            text == "VIDEO_STARTED" -> {
                statusDetail.value = "视频流已启动"
                startVideoAttempts = 0
            }
            text == "VIDEO_STOPPED" -> { statusDetail.value = "视频流已停止" }
            text == "AUDIO_STARTED" -> {
                // 声道数来自 server_settings（官方默认 2 声道；旧版硬编码 1 导致无声/变音）
                runCatching { audioPlayer.start(48000, audioChannels.value) }
                // v2.7.0：音频停看门狗状态归零（实测存在 AUDIO_STARTED 但服务器管线无帧的部署）
                // ⚠️ 恢复尝试计数不在此归零（否则每次自动恢复的 AUDIO_STARTED 都重置计数 = 无限重试循环）；
                //    帧真正流动时才归零
                audioStartedAt = System.currentTimeMillis()
                audioFramesSinceStart = 0
                startAudioStallWatchdog()
                startStatsSamplerIfNeeded()
            }
            text == "AUDIO_STOPPED" -> {
                audioStartedAt = 0L
                stopAudioStallWatchdog()
                audioStallNotice.value = null
            }
            text == "AUDIO_DISABLED" -> {
                statusDetail.value = "音频被服务器禁用"
                serverAudioEnabled.value = false
                audioStartedAt = 0L
                stopAudioStallWatchdog()
                audioStallNotice.value = null
                runCatching { audioPlayer.stop() }
            }
            text == "MICROPHONE_DISABLED" -> { runCatching { micCapture.stop() } }
            text == "WEBCAM_DISABLED" -> { runCatching { webcamCapture.stop() } }
            text == "WEBCAM_KEYFRAME" -> { webcamCapture.requestKeyframe() }
            text.startsWith("CAPTURE_DEMAND ") -> {
                val parts = text.split(" ")
                if (parts.size >= 3) {
                    val subject = parts[1]
                    val wanted = parts[2] == "1"
                    handleCaptureDemand(subject, wanted)
                }
            }
            text.startsWith("PIPELINE_RESETTING") -> {
                videoSink?.reset()
            }
            text.startsWith("DISPLAY_CONFIG_UPDATE,") -> { /* 多屏配置，v1 仅单屏 */ }
            text.startsWith("cursor,") -> parseCursorShape(text.substring(7))
            text.startsWith("system,pointer,") -> handlePointerEcho(text.substring("system,pointer,".length))
            text.startsWith("system,") -> handleSystemAction(text.substring(7))
            text.startsWith("KILL ") -> {
                val reason = text.substring(5)
                userClosed = true
                status.value = Status.CLOSED
                // 被新客户端顶替是服务器的单主人策略：给出友好解释而非原始英文
                val friendly = if (reason.contains("new primary client") || reason.contains("primary")) {
                    "另一个客户端接管了此会话（服务器单主人策略）。若要夺回控制权，请点击重试重连。"
                } else "服务器断开：$reason"
                statusDetail.value = friendly
                lastError.value = friendly
            }
            text == "_gz,1" -> { gzRxEnabled = true }
            // ---- 剪贴板 ----
            text.startsWith("clipboard_reply,") -> {
                val verb = text.substring("clipboard_reply,".length)
                awaitingReplyVerbs.add(verb)
            }
            text == "clipboard_secret" -> { /* 机密剪贴板：不落地 */ }
            text.startsWith("clipboard_start,") -> {
                val parts = text.split(",", limit = 3)
                clipboardMultipartMime = parts.getOrNull(1) ?: "text/plain"
                clipboardMultipartExpected = parts.getOrNull(2)?.toLongOrNull() ?: 0
                clipboardMultipart = StringBuilder()
                clipboardMultipartIsReply = awaitingReplyVerbs.isNotEmpty()
            }
            text.startsWith("clipboard_data,") -> {
                clipboardMultipart?.append(text.substring("clipboard_data,".length))
            }
            text == "clipboard_finish" -> {
                val b64 = clipboardMultipart?.toString()
                clipboardMultipart = null
                if (b64 != null) {
                    finishClipboardPayload(b64, clipboardMultipartMime, takeReplyFlag())
                }
            }
            text.startsWith("clipboard_binary,") -> {
                val idx = text.indexOf(',', "clipboard_binary,".length)
                if (idx > 0) {
                    val mime = text.substring("clipboard_binary,".length, idx)
                    val b64 = text.substring(idx + 1)
                    finishClipboardPayload(b64, mime, takeReplyFlag())
                }
            }
            text.startsWith("clipboard,") -> {
                finishClipboardPayload(text.substring("clipboard,".length), "text/plain", takeReplyFlag())
            }
        }
    }

    private fun takeReplyFlag(): Boolean {
        if (awaitingReplyVerbs.isNotEmpty()) {
            awaitingReplyVerbs.clear()
            return true
        }
        return false
    }

    private fun parseResW(res: String): Int? = res.substringBefore('x').toIntOrNull()
    private fun parseResH(res: String): Int? = res.substringAfter('x', "").toIntOrNull()

    private fun parseAuthSuccess(text: String) {
        runCatching {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(text.substringAfter(','))
            if (json is kotlinx.serialization.json.JsonObject) {
                val r = (json["role"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                if (r != null) {
                    role.value = r
                    if (r == "viewer") {
                        // 查看者模式：无键鼠输入权限
                        statusDetail.value = "以查看者身份连接"
                    }
                }
            }
        }
    }

    private fun handleJsonText(text: String) {
        runCatching {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject ?: return
            when ((obj["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content) {
                "server_settings" -> {
                    val settingsObj = obj["settings"] as? kotlinx.serialization.json.JsonObject ?: return
                    val map = settingsObj.toMap()
                    serverSettings.value = map
                    (map["encoder"] as? kotlinx.serialization.json.JsonObject)?.let { enc ->
                        (enc["value"] as? kotlinx.serialization.json.JsonPrimitive)?.let { v ->
                            currentEncoder.value = v.content
                        }
                    }
                    // v2.7.0：服务器能力开关（麦克风/摄像头/音频；UI 据此禁用入口）
                    fun boolFlag(key: String, current: MutableStateFlow<Boolean>) {
                        (map[key] as? kotlinx.serialization.json.JsonObject)?.let { o ->
                            (o["value"] as? kotlinx.serialization.json.JsonPrimitive)?.let { v ->
                                current.value = v.content == "true" || v.content == "1"
                            }
                        }
                    }
                    boolFlag("audio_enabled", serverAudioEnabled)
                    boolFlag("microphone_enabled", serverMicEnabled)
                    boolFlag("webcam_enabled", serverWebcamEnabled)
                    if (!serverMicEnabled.value && micActive.value) {
                        // 服务器已禁用：立即停掉上行（避免持续发被丢弃的帧）
                        runCatching { micCapture.stop() }
                        micActive.value = false
                        notices.value = "服务器已禁用麦克风，上行已停止"
                    }
                    if (!serverWebcamEnabled.value && webcamActive.value) {
                        runCatching { webcamCapture.stop() }
                        webcamActive.value = false
                        notices.value = "服务器已禁用摄像头，上行已停止"
                    }
                    // 服务器音频声道数（官方默认 2，修复单声道硬编码导致的无声/变音）
                    (map["audio_channels"] as? kotlinx.serialization.json.JsonObject)?.let { ch ->
                        (ch["value"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()?.let {
                            if (it in 1..8) audioChannels.value = it
                        }
                    }
                }
                "server_apps" -> {
                    val arr = obj["apps"] as? kotlinx.serialization.json.JsonArray ?: return
                    apps.value = arr.mapNotNull { el ->
                        (el as? kotlinx.serialization.json.JsonObject)?.let { o ->
                            val name = (o["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@let null
                            val cmd = (o["command"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                            name to cmd
                        }
                    }
                }
                "pipeline_status" -> { }
                "stream_resolution" -> {
                    val w = (obj["width"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                    val h = (obj["height"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                    if (w != null && h != null && w > 0 && h > 0) {
                        realizedResolution.value = w to h
                        stats.value.resolution = "${w}x${h}"
                    }
                }
                "stream_info" -> {
                    val info = obj["info"] as? kotlinx.serialization.json.JsonObject ?: return
                    val backend = (info["backend"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                    val encoder = (info["encoder"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                    if (backend.isNotBlank() || encoder.isNotBlank()) {
                        stats.value.encoderDetail = "$backend/$encoder"
                        stats.value = stats.value
                    }
                    // 官方四行健康状态数据（stream-stats-view streamRows 同源字段）
                    streamHealth.value = StreamHealth(
                        backend = backend,
                        capture = (info["capture"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "",
                        zeroCopy = (info["zero_copy"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true",
                        encoder = encoder,
                        hardware = (info["hardware"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true",
                        gpuPresent = (info["gpu_present"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true",
                        hardwareExpected = (info["hardware_expected"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true",
                        codec = (info["codec"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "",
                        gpu = (info["gpu"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                    )
                }
                "stream_stats" -> {
                    // 官方格式：{"type":"stream_stats","displayId":"primary","stats":{...}}
                    val st = obj["stats"] as? kotlinx.serialization.json.JsonObject ?: return
                    val s = stats.value
                    fun dbl(k: String): Double =
                        (st[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
                    fun lng(k: String): Long =
                        (st[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                    dbl("encoded_fps").let { if (it > 0) s.encodedFps = it }
                    dbl("encode_ms").let { if (it > 0) s.encodeMs = it }
                    dbl("pipeline_ms").let { if (it > 0) s.pipelineMs = it }
                    dbl("pipeline_min_ms").let { if (it > 0) s.pipelineMinMs = it }
                    dbl("pipeline_max_ms").let { if (it > 0) s.pipelineMaxMs = it }
                    dbl("send_ms").let { if (it > 0) s.sendMs = it }
                    dbl("send_min_ms").let { if (it > 0) s.sendMinMs = it }
                    dbl("send_max_ms").let { if (it > 0) s.sendMaxMs = it }
                    dbl("frame_max_kb").let { if (it > 0) s.frameMaxKb = it }
                    dbl("video_mbps").let { if (it > 0) s.bitrateMbps = it }
                    dbl("peak_mbps").let { if (it > 0) s.peakMbps = it }
                    dbl("target_mbps").let { if (it > 0) s.targetMbps = it }
                    dbl("rtt_ms").let { if (it > 0) s.latencyMs = it.toInt() }
                    dbl("cpu_percent").let { if (it > 0) s.cpuPercent = it }
                    dbl("gpu_percent").let { if (it > 0) s.gpuPercent = it }
                    lng("mem_total").let { if (it > 0) s.memTotal = it }
                    lng("mem_used").let { if (it > 0) s.memUsed = it }
                    lng("gpu_mem_total").let { if (it > 0) s.gpuMemTotal = it }
                    lng("gpu_mem_used").let { if (it > 0) s.gpuMemUsed = it }
                    s.throttled = (st["throttled"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
                    stats.value = s
                }
                "print_document" -> {
                    val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
                    val size = (obj["size_bytes"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0
                    handlePrintDocument(name, size)
                }
            }
        }
    }

    private fun handleSystemAction(json: String) {
        runCatching {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject ?: return
            val action = (obj["action"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
            when {
                action.startsWith("rumble,") -> {
                    // 官方格式：rumble,slot,strong,weak,ms —— 手柄马达振动（无手柄时手机振动兜底）
                    val parts = action.split(",")
                    if (parts.size >= 5) {
                        val slot = parts[1].toIntOrNull() ?: 0
                        val strong = parts[2].toFloatOrNull() ?: 0f
                        val weak = parts[3].toFloatOrNull() ?: 0f
                        val ms = parts[4].toIntOrNull() ?: 200
                        if (strong > 0 || weak > 0) {
                            if (settings().gamepadRumble) {
                                platform.rumbleGamepad(slot, strong, weak, ms)
                                if (platform.isTouchDevice()) platform.vibrate(ms.toLong())
                            }
                        }
                    }
                }
                action.startsWith("command_error,") -> { /* 命令错误提示 */ }
                action.startsWith("command_done,") -> { }
            }
        }
    }

    /** 新版服务器的指针位置回显：system,pointer,<did>,<x>,<y>,<scale>,<seq>（本地回显的权威校正） */
    private fun handlePointerEcho(args: String) {
        val parts = args.split(",")
        if (parts.size < 3) return
        val x = parts[1].toFloatOrNull() ?: return
        val y = parts[2].toFloatOrNull() ?: return
        cursorX.value = x
        cursorY.value = y
    }

    /** 服务器光标形状：cursor,{"curdata":"<b64 png>","width":32,"height":32,"hotx":4,"hoty":4,"handle":N} */
    private fun parseCursorShape(json: String) {
        runCatching {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject ?: return
            val curdata = (obj["curdata"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
            val handle = (obj["handle"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: return
            val shape = CursorShape(
                pngBase64 = curdata,
                width = (obj["width"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 32,
                height = (obj["height"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 32,
                hotX = (obj["hotx"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                hotY = (obj["hoty"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                handle = handle
            )
            // 按 handle 去重：同形状重复推送不触发 UI 重组
            if (cursorShape.value?.handle != handle) {
                cursorShape.value = shape
            }
        }
    }

    private fun handleCaptureDemand(subject: String, wanted: Boolean) {
        val s = settings()
        if (subject == "webcam" || subject.contains("webcam")) {
            if (wanted) {
                if (webcamActive.value) return
                startWebcamUplink()
            } else {
                stopWebcamUplink()
            }
        } else if (subject == "microphone" || subject.contains("microphone")) {
            if (wanted && s.micEnabled && !micActive.value) {
                startMicUplink()
            } else if (!wanted) {
                stopMicUplink()
            }
        }
    }

    // =====================================================================
    // 打印队列（v2.4.0：下载校验 + 本地备份 + 系统打印 + 状态跟踪）
    // =====================================================================

    private fun updatePrintJob(name: String, transform: (PrintJobEntry) -> PrintJobEntry) {
        val list = printJobs.value.toMutableList()
        val idx = list.indexOfFirst { it.name == name }
        if (idx >= 0) {
            list[idx] = transform(list[idx])
        } else {
            list.add(0, transform(PrintJobEntry(name = name, sizeBytes = 0)))
        }
        printJobs.value = list
    }

    fun retryPrintJob(name: String) {
        val job = printJobs.value.firstOrNull { it.name == name } ?: return
        if (job.status == "printing" || job.status == "downloading") return
        downloadAndPrint(job.name, job.sizeBytes)
    }

    fun savePrintJob(name: String, onDone: (Boolean, String?) -> Unit) {
        val job = printJobs.value.firstOrNull { it.name == name } ?: return
        val local = job.localPath
        if (local != null && java.io.File(local).exists()) {
            onDone(true, local)
            return
        }
        ioScope.launch {
            val (code, bytes) = runCatching {
                connection.apiGetBytes("/api/print/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20"))
            }.getOrNull() ?: (-1 to ByteArray(0))
            if (code == 200 && bytes.size > 4 && bytes[0] == '%'.code.toByte()) {
                val saved = runCatching { platform.savePrintPdf(name, bytes) }.getOrNull()
                updatePrintJob(name) { it.copy(status = if (saved != null) "printing" else "error", localPath = saved, message = if (saved == null) "保存失败" else "") }
                onDone(saved != null, saved)
            } else {
                updatePrintJob(name) { it.copy(status = "error", message = "获取失败（HTTP $code），文档可能已被取走或名称非法") }
                onDone(false, null)
            }
        }
    }

    private fun downloadAndPrint(name: String, sizeBytes: Long) {
        updatePrintJob(name) { it.copy(status = "downloading", message = "") }
        ioScope.launch {
            val (code, bytes) = runCatching {
                connection.apiGetBytes("/api/print/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20"))
            }.getOrNull() ?: (-1 to ByteArray(0))
            when {
                code != 200 -> updatePrintJob(name) {
                    it.copy(status = "error", message = "下载失败（HTTP $code）：" + when (code) {
                        401 -> "认证失败，请检查用户名/密码或令牌"
                        403 -> "打印被服务器禁用或当前为只读身份"
                        404 -> "文档不存在（可能已被取走——服务器取走即删）"
                        else -> "网络或服务器错误"
                    })
                }
                bytes.size < 5 || bytes[0] != '%'.code.toByte() || bytes[1] != 'P'.code.toByte() ->
                    updatePrintJob(name) { it.copy(status = "error", message = "文件校验失败：非有效 PDF（传输损坏或服务器输出异常）") }
                sizeBytes > 0 && bytes.size.toLong() != sizeBytes ->
                    updatePrintJob(name) { it.copy(status = "error", message = "文件大小不符（通知 $sizeBytes 字节，实际 ${bytes.size}），疑似传输损坏") }
                else -> {
                    // 本地备份（保存失败不影响打印）
                    val saved = runCatching { platform.savePrintPdf(name, bytes) }.getOrNull()
                    updatePrintJob(name) { it.copy(status = "printing", localPath = saved, message = "") }
                    withContext(Dispatchers.Main) {
                        onPrintDocument(name, bytes.size.toLong())
                        // ⚠️ PrintManager 必须在主线程调起（旧版在 IO 线程调用导致远程打印无声失败）
                        platform.print().printPdf(bytes, name) { ok, err ->
                            updatePrintJob(name) {
                                if (ok) it.copy(status = "done", message = "已交付系统打印服务")
                                else it.copy(status = "error", message = "系统打印失败：${err ?: "未知原因"}")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handlePrintDocument(name: String, sizeBytes: Long) {
        // 入队（去重：同名且已完成/正在处理的不重复）
        val existing = printJobs.value.firstOrNull { it.name == name }
        if (existing != null && (existing.status == "done" || existing.status == "printing" || existing.status == "downloading")) return
        updatePrintJob(name) { it.copy(sizeBytes = sizeBytes, status = "pending", message = "") }
        downloadAndPrint(name, sizeBytes)
    }

    // =====================================================================
    // 二进制帧
    // =====================================================================

    override fun onBinary(data: ByteArray, length: Int) {
        if (length < 1) return
        stats.value.bytesReceived += length.toLong()
        // ⚠️【连接稳定性】单帧解析异常绝不允许打断 WS 接收循环：
        //    修复前一个 copyOfRange 越界就能让整条连接被判死亡并无限重连。
        //    帧级错误降级为丢帧 + 关键帧请求。
        runCatching {
            when (data[0]) {
                Protocol.FRAME_AUDIO -> {
                    if (role.value == "viewer") return
                    val frames = Protocol.parseAudioFrames(data, length, audioParseState)
                    for ((opus, quiet) in frames) {
                        if (quiet) audioPlayer.onQuiet()
                        if (opus.isNotEmpty()) {
                            audioFramesSinceStart++
                            audioPlayer.playOpus(opus)
                            // 录屏：录主 Opus 载荷
                            if (recorder.state == StreamRecorder.State.RECORDING) {
                                recorder.onAudioFrame(opus)
                            }
                        }
                    }
                }
                Protocol.FRAME_JPEG -> {
                    Protocol.parseJpegStripeHeader(data, length)?.let { (frameId, stripeY, off) ->
                        noteVideoChunk(frameId)
                        videoSink?.onJpegStripe(frameId, stripeY, data, off, length)
                        if (recorder.state == StreamRecorder.State.RECORDING) {
                            recorder.onJpegStripe(frameId, stripeY, data, off, length)
                        }
                    }
                }
                Protocol.FRAME_VIDEO -> {
                    Protocol.parseVideoFrameHeader(data, length)?.let { (header, off) ->
                        noteVideoChunk(header.frameId)
                        // ⚠️【坐标修复】帧头携带真实流尺寸——stream_resolution JSON 缺失/迟到时
                        //    仍能同步视图几何，否则触摸坐标会按错误分辨率映射（点击错位的根源）
                        if (header.width > 0 && header.height > 0) {
                            val cur = realizedResolution.value
                            if (cur == null || cur.first != header.width || cur.second != header.height) {
                                realizedResolution.value = header.width to header.height
                                stats.value.resolution = "${header.width}x${header.height}"
                                stats.value = stats.value
                            }
                        }
                        stats.value.videoFrames++
                        videoSink?.onVideoFrame(header, data, off, length)
                        if (recorder.state == StreamRecorder.State.RECORDING) {
                            recorder.onVideoFrame(header, data, off, length)
                        }
                    }
                }
                Protocol.FRAME_GZ -> {
                    val text = gunzip(data, 1, length)
                    onText(text)
                }
            }
        }.onFailure {
            // 丢帧恢复：请求服务器补发关键帧
            post { connection.sendText("REQUEST_KEYFRAME") }
        }
    }

    private fun noteVideoChunk(frameId: Int) {
        if (firstFrameArrivedAt == 0L) firstFrameArrivedAt = System.currentTimeMillis()
        if (role.value != "viewer") {
            // 官方 VideoWorker lostFrame 语义：检测到帧号跳跃（丢帧）即上报，
            // 服务器据此统计/补偿。限定每秒最多报一次避免风暴。
            if (lastReceivedFrameId >= 0 && frameId > lastReceivedFrameId + 1 &&
                System.currentTimeMillis() - lastLostFrameReportAt > 1000
            ) {
                lastLostFrameReportAt = System.currentTimeMillis()
                stats.value.lostFrames += (frameId - lastReceivedFrameId - 1).coerceAtLeast(1)
                post { connection.sendText("LOST_FRAME $frameId") }
            }
            lastReceivedFrameId = frameId
        }
        lastReceivedFrameAt = System.currentTimeMillis()
        disarmStartVideoWatchdog()
        // 本地帧率测量（呈现近似）
        val now = lastReceivedFrameAt
        if (localFrameWindowStart == 0L) localFrameWindowStart = now
        localFrameCount++
        if (now - localFrameWindowStart >= 1000) {
            val fps = localFrameCount * 1000.0 / (now - localFrameWindowStart)
            stats.value.fps = fps.toInt()
            localFrameCount = 0
            localFrameWindowStart = now
        }
    }

    private var lastLostFrameReportAt = 0L

    private fun gunzip(data: ByteArray, offset: Int, length: Int): String {
        GZIPInputStream(ByteArrayInputStream(data, offset, length - offset)).use { gz ->
            return gz.readBytes().toString(Charsets.UTF_8)
        }
    }

    // =====================================================================
    // 关闭/错误/重连
    // =====================================================================

    override fun onRedirected(from: String, to: String) {
        // 重定向透明处理，状态栏可显示
    }

    override fun onClosed(code: Int, reason: String) {
        if (userClosed) {
            status.value = Status.CLOSED
            return
        }
        if (code == 4001 || reason.contains("auth", ignoreCase = true)) {
            status.value = Status.FAILED
            lastError.value = "连接被服务器关闭（$code）：$reason"
            statusDetail.value = "认证或权限问题"
            return
        }
        val desc = if (code == -1) "网络中断或服务器关闭了连接"
                    else "连接被关闭（$code）${reason.ifBlank { "" }}"
        scheduleReconnect(desc)
    }

    override fun onError(userMessage: String, technical: String) {
        lastError.value = userMessage
        lastErrorTechnical.value = technical
        if (userClosed) return
        // 认证/路径/模式错误不自动重连
        val fatal = technical.contains("401") || technical.contains("403") ||
                technical.contains("409") || technical.contains("no ws endpoint") ||
                technical.contains("redirect loop")
        if (fatal) {
            status.value = Status.FAILED
            statusDetail.value = if (technical.contains("401")) "认证失败——请编辑服务器条目校验账号密码" else "连接失败"
            return
        }
        scheduleReconnect(userMessage)
    }

    private fun scheduleReconnect(reason: String) {
        if (userClosed || status.value == Status.RECONNECTING) return
        // ⚠️【风暴防护】连续失败达上限后停止自动重连，转为 FAILED + 手动重试。
        //    无限重连风暴会持续冲击反代/CDN（可能触发 401/403 拉黑），且叠加旧版
        //    socket 泄漏会耗尽本地资源——必须封顶。
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            status.value = Status.FAILED
            statusDetail.value = "自动重连已达上限（$MAX_RECONNECT_ATTEMPTS 次）"
            lastError.value = "$reason\u3002已停止自动重连，请检查网络/服务器状态后手动重试"
            return
        }
        reconnectJob?.cancel()
        val base = (500L shl reconnectAttempts.coerceAtMost(6)).coerceAtMost(30_000L)
        // 抖动：避免与其他客户端/重连周期同步形成请求波峰
        val delayMs = base + (0..(base / 4)).random()
        reconnectAttempts++
        status.value = Status.RECONNECTING
        statusDetail.value = "$reason · ${"%.1f".format(delayMs / 1000.0)}s 后重连（第 $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS 次）"
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!userClosed) {
                runCatching { micCapture.stop() }
                runCatching { webcamCapture.stop() }
                videoRequested = false
                modeReceived = false
                lastReceivedFrameId = -1
                openConnection()
            }
        }
    }

    // =====================================================================
    // 定时器：ACK / 统计 / START_VIDEO 看门狗
    // =====================================================================

    private fun startAckLoop() {
        ackTimer?.cancel()
        ackTimer = scope.launch {
            while (isActive && !userClosed) {
                delay(50)
                val id = lastReceivedFrameId
                if (id >= 0) {
                    val held = (System.currentTimeMillis() - lastReceivedFrameAt).coerceAtLeast(0)
                    runCatching { connection.sendText("CLIENT_FRAME_ACK $id $held") }
                }
            }
        }
    }

    private fun subscribeStats() {
        if (statsSubscriptionActive) return
        statsSubscriptionActive = true
        ioScope.launch {
            runCatching { connection.sendText("_stats,1") }
        }
        startStatsSamplerIfNeeded()
    }

    /** 每秒采样：本地 fps/decode/present/上行带宽 + 服务器统计 → 历史序列（最多 120 点，供图表）；每 5 秒上报 _f/_l */
    private fun startStatsSamplerIfNeeded() {
        if (statsSampler?.isActive == true) return
        uplinkWindowStart = System.currentTimeMillis()
        statsSampler = scope.launch {
            var tick = 0
            var lastTotalBytes = 0L
            var lastTotalAt = System.currentTimeMillis()
            while (isActive && !userClosed) {
                delay(1000)
                val s = stats.value
                // 客户端侧带宽（含音频/控制，全部 WS 字节）
                val now = System.currentTimeMillis()
                val dt = (now - lastTotalAt).coerceAtLeast(1)
                s.mbpsClient = (s.bytesReceived - lastTotalBytes) * 8.0 / 1e6 / (dt / 1000.0)
                lastTotalBytes = s.bytesReceived
                lastTotalAt = now
                s.receivedMb = s.bytesReceived / 1e6
                // 客户端解码/上屏/丢帧（来自 VideoSink EMA）
                videoSink?.let { sink ->
                    s.decodeMs = sink.avgDecodeMs
                    s.presentMs = sink.avgPresentMs
                    s.framesNotShown = sink.framesDropped
                }
                s.audioBufferMs = audioPlayer.bufferLevelMs
                // 上行带宽
                val upDt = (now - uplinkWindowStart).coerceAtLeast(1)
                val upMbps = uplinkBytesWindow * 8.0 / 1e6 / (upDt / 1000.0)
                uplinkBytesWindow = 0
                uplinkWindowStart = now
                s.micActive = micActive.value
                s.webcamActive = webcamActive.value
                s.micUplinkMbps = if (micActive.value) upMbps else 0.0
                s.webcamUplinkMbps = if (webcamActive.value) upMbps else 0.0
                s.pushSample(
                    s.fps.toDouble(), s.encodedFps,
                    if (s.bitrateMbps > 0) s.bitrateMbps else s.mbpsClient,
                    s.latencyMs.toDouble(),
                    s.decodeMs, s.presentMs,
                    if (upMbps > 0) upMbps else 0.0
                )
                stats.value = s
                if (++tick % 5 == 0) {
                    runCatching {
                        if (s.fps > 0) connection.sendText("_f,${s.fps}")
                        if (s.latencyMs > 0) connection.sendText("_l,${s.latencyMs}")
                    }
                }
            }
        }
    }

    private fun armStartVideoWatchdog() {
        watchdogTimer?.cancel()
        watchdogTimer = scope.launch {
            delay(3000)
            if (!userClosed && modeReceived && lastReceivedFrameAt == 0L) {
                if (startVideoAttempts < 3) {
                    startVideoAttempts++
                    runCatching {
                        connection.sendText("START_VIDEO")
                        if (server.audioEnabled) connection.sendText("START_AUDIO")
                    }
                    armStartVideoWatchdog()
                } else {
                    // 仍无视频：请求关键帧；再等 8s 仍无帧 → 重建连接
                    // （v2.6.0+：服务器端会话/管线可能卡在旧状态，唯一可靠的恢复方式是换新连接——
                    //   实测同一服务器上新 WS 连接立即可取流，旧连接永远拿不到帧）
                    runCatching { connection.sendText("REQUEST_KEYFRAME") }
                    delay(8000)
                    if (!userClosed && modeReceived && lastReceivedFrameAt == 0L) {
                        statusDetail.value = "视频流未启动，自动重建连接…"
                        runCatching { connection.close() }
                        openConnection()
                    }
                }
            }
        }
    }

    private fun disarmStartVideoWatchdog() {
        watchdogTimer?.cancel()
        watchdogTimer = null
    }

    // =====================================================================
    // 输入发送 API
    // =====================================================================

    fun post(block: suspend () -> Unit) {
        ioScope.launch { runCatching { block() } }
    }

    fun sendKey(keysym: Long, down: Boolean) = post {
        if (down) heldKeys.add(keysym) else heldKeys.remove(keysym)
        connection.sendText(Protocol.keyEvent(down, keysym))
        if (down) startKeyHeartbeat() else if (heldKeys.isEmpty()) stopKeyHeartbeat()
    }

    /**
     * kh 按键心跳（官方行为）：有按键按住期间每 100ms 上报一次按住的 keysym 列表，
     * 全部松开后停止。用于服务器校准长按状态、防按键卡死。
     */
    private fun startKeyHeartbeat() {
        if (keyHeartbeatJob?.isActive == true) return
        keyHeartbeatJob = scope.launch {
            while (isActive && heldKeys.isNotEmpty() && !userClosed) {
                runCatching { connection.sendText(Protocol.keyHeartbeat(heldKeys.toList())) }
                delay(100)
            }
        }
    }

    private fun stopKeyHeartbeat() {
        keyHeartbeatJob?.cancel()
        keyHeartbeatJob = null
    }

    fun sendKeyChord(keysyms: List<Long>) = post {
        val mods = keysyms.dropLast(1)
        val main = keysyms.last()
        for (m in mods) connection.sendText(Protocol.keyEvent(true, m))
        connection.sendText(Protocol.keyEvent(true, main))
        connection.sendText(Protocol.keyEvent(false, main))
        for (m in mods.reversed()) connection.sendText(Protocol.keyEvent(false, m))
    }

    fun typeText(text: String) = post {
        connection.sendText(Protocol.commitText(text))
    }

    fun sendMouseMove(x: Int, y: Int, mask: Int) = post {
        connection.sendText(Protocol.mouseMove(x, y, mask, nextPointerSeq()))
        // 本地回显光标位置（服务器无位置回显时由本地维护）
        cursorX.value = x.toFloat()
        cursorY.value = y.toFloat()
    }

    fun sendMouseMoveRelative(dx: Int, dy: Int, mask: Int) = post {
        connection.sendText(Protocol.mouseMoveRelative(dx, dy, mask, nextPointerSeq()))
        // 虚拟光标累加并限制在流范围内
        if (dx != 0 || dy != 0) {
            val w = (realizedResolution.value?.first ?: 1920).toFloat()
            val h = (realizedResolution.value?.second ?: 1080).toFloat()
            var cx = cursorX.value
            var cy = cursorY.value
            if (cx < 0) cx = w / 2f
            if (cy < 0) cy = h / 2f
            cursorX.value = (cx + dx).coerceIn(0f, w - 1f)
            cursorY.value = (cy + dy).coerceIn(0f, h - 1f)
        }
    }

    fun sendMouseButton(x: Int, y: Int, mask: Int) = post {
        connection.sendText(Protocol.mouseMove(x, y, mask, nextPointerSeq()))
    }

    fun sendScroll(currentMask: Int, vertical: Boolean, notches: Int) = post {
        for (msg in Protocol.scrollPulse(currentMask, vertical, notches)) {
            connection.sendText(msg)
        }
    }

    fun requestKeyframe() = post {
        // 限流：看门狗/手动触发共享 3s 冷却，避免风暴
        val now = System.currentTimeMillis()
        if (now - lastKeyframeRequestAt > 3000) {
            lastKeyframeRequestAt = now
            stats.value.keyframeRequests++
            connection.sendText("REQUEST_KEYFRAME")
        } else if (now - lastKeyframeRequestAt < 0) {
            lastKeyframeRequestAt = now
        }
    }

    /** Surface 重建/解码器重建后的强制关键帧（绕过限流） */
    fun forceKeyframe() = post {
        lastKeyframeRequestAt = System.currentTimeMillis()
        stats.value.keyframeRequests++
        connection.sendText("REQUEST_KEYFRAME")
    }

    /**
     * 帧停顿看门狗：CONNECTED 且已请求视频但 3s 无新帧 → 自动 REQUEST_KEYFRAME（限流 5s/次）。
     * 覆盖三类用户报障：①退到后台再回前台 SurfaceView 重建后解码器丢弃增量帧→永久黑屏；
     * ②链路丢帧导致参考帧断裂→画面冻结；③【v2.6.0+】帧到达但中途加入 GOP 解码不出
     * （non-existing PPS）→ 帧到达后 2.5s 仍未出首帧解码则强制关键帧。
     */
    private fun startStallWatchdog() {
        stallWatchdog?.cancel()
        stallWatchdog = scope.launch {
            while (isActive && !userClosed) {
                delay(1000)
                if (status.value != Status.CONNECTED || !videoRequested) continue
                if (!settings().requestKeyframeOnStall) continue
                val last = lastReceivedFrameAt
                val now = System.currentTimeMillis()
                if (last > 0 && now - last > 3000) {
                    if (now - lastKeyframeRequestAt > 5000) {
                        lastKeyframeRequestAt = now
                        runCatching { connection.sendText("REQUEST_KEYFRAME") }
                    }
                }
                // ③帧在到达但解码器从未出首帧（中途加入 GOP）→ 立刻要关键帧（限流 2.5s）
                if (firstFrameArrivedAt > 0 && firstDecodedFrameAt == 0L &&
                    now - firstFrameArrivedAt > 2500 &&
                    now - decodeStallKeyframeAt > 2500
                ) {
                    decodeStallKeyframeAt = now
                    lastKeyframeRequestAt = now
                    println("[Selkies][看门狗] 帧到达但未解码 → 请求关键帧（首帧到达于 ${now - firstFrameArrivedAt}ms 前）")
                    runCatching { connection.sendText("REQUEST_KEYFRAME") }
                }
            }
        }
    }

    fun sendResolution(w: Int, h: Int) = post {
        connection.sendText(Protocol.resolution(alignRes(w), alignRes(h), server.displayId))
        requestKeyframe()
    }

    fun sendViewport(ox: Double, oy: Double, sx: Double, sy: Double) = post {
        connection.sendText(Protocol.viewport(ox, oy, sx, sy))
    }

    fun sendGamepadConnect(index: Int, id: String, nAxes: Int, nButtons: Int) = post {
        val b64 = Base64.getEncoder().encodeToString(id.take(64).toByteArray(Charsets.ISO_8859_1))
        connection.sendText(Protocol.gamepadConnect(index, b64, nAxes, nButtons))
    }

    fun sendGamepadButton(index: Int, button: Int, value: Double) = post {
        connection.sendText(Protocol.gamepadButton(index, button, value))
    }

    fun sendGamepadAxis(index: Int, axis: Int, value: Double) = post {
        connection.sendText(Protocol.gamepadAxis(index, axis, value))
    }

    fun sendGamepadDisconnect(index: Int) = post {
        connection.sendText(Protocol.gamepadDisconnect(index))
    }

    fun sendGamepadHeartbeat(index: Int) = post {
        connection.sendText(Protocol.gamepadHeartbeat(index))
    }

    fun startVideo() = post {
        connection.sendText("START_VIDEO")
    }

    fun stopVideo() = post {
        connection.sendText("STOP_VIDEO")
    }

    // =====================================================================
    // 剪贴板
    // =====================================================================

    /** 本地剪贴板变化 → 发送到远程（由 UI 层的监听调用） */
    fun pushLocalClipboard(text: String?) {
        val suppressed = synchronized(clipboardEchoLock) { suppressClipboardEcho }
        if (suppressed) return
        val s = settings()
        if (!s.clipboardAutoSend) return
        if (text == null) return
        if (text == lastRemoteClipboardText) return
        lastRemoteClipboardText = text
        ioScope.launch {
            runCatching {
                val bytes = text.toByteArray(Charsets.UTF_8)
                sendClipboardChunked(bytes, "text/plain")
                clipboardNotice.value = "本地复制已发送到远程（${text.length} 字符）"
            }.onFailure {
                clipboardNotice.value = "发送到远程剪贴板失败：${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    fun pushLocalImage(png: ByteArray) {
        val suppressed = synchronized(clipboardEchoLock) { suppressClipboardEcho }
        if (suppressed) return
        val s = settings()
        if (!s.clipboardAutoSend || !s.clipboardBinaryEnabled) return
        ioScope.launch {
            runCatching { sendClipboardChunked(png, "image/png") }
        }
    }

    private suspend fun sendClipboardChunked(bytes: ByteArray, mime: String) {
        val isText = mime == "text/plain"
        val total = bytes.size
        if (total < Protocol.CLIPBOARD_CHUNK_RAW) {
            val b64 = Base64.getEncoder().encodeToString(bytes)
            connection.sendText(if (isText) "cw,$b64" else "cb,$mime,$b64")
            return
        }
        clipboardTid++
        val tid = clipboardTid
        if (isText) connection.sendText("cws,$tid,$total")
        else connection.sendText("cbs,$tid,$mime,$total")
        var off = 0
        while (off < total) {
            val end = minOf(off + Protocol.CLIPBOARD_CHUNK_RAW, total)
            val chunk = Base64.getEncoder().encodeToString(bytes.copyOfRange(off, end))
            connection.sendText((if (isText) "cwd,$tid," else "cbd,$tid,") + chunk)
            off = end
        }
        connection.sendText(if (isText) "cwe,$tid" else "cbe,$tid")
    }

    /** 主动请求远程剪贴板 */
    fun requestClipboard() = post {
        connection.sendText("REQUEST_CLIPBOARD")
    }

    private fun finishClipboardPayload(b64: String, mime: String, isReply: Boolean) {
        runCatching {
            val bytes = Base64.getDecoder().decode(b64)
            if (mime == "text/plain" || mime.startsWith("text/")) {
                val text = bytes.toString(Charsets.UTF_8)
                // v2.7.0：去重防护——内容与上次完全相同（轮询拉取的未变更响应）时静默跳过，
                // 不重复写本地剪贴板/不刷提示（否则 5s 轮询会反复覆盖与弹提示）
                val unchanged = text == lastRemoteClipboardText
                lastRemoteClipboardText = text
                // v2.6.0：迟到的 cr 空快照（reply）不覆盖已收到的真实内容
                //   （服务器连接期读剪贴板可能数秒后才回空，此时更早的真实同步不应被清空显示）
                val skipOverwrite = isReply && text.isEmpty() && !remoteClipboard.value?.first.isNullOrEmpty()
                if (!skipOverwrite) {
                    remoteClipboard.value = text to mime
                }
                onClipboardFromRemote(text, mime)
                if (!unchanged) {
                    clipboardNotice.value = if (isReply) "已获取远程剪贴板（${text.length} 字符）" else "远程剪贴板已同步到本地（${text.length} 字符）"
                }
                if (!isReply && !unchanged && settings().clipboardAutoReceive && text.isNotEmpty()) {
                    applyRemoteClipboardLocally {
                        // 后台（无焦点）时 Android 禁止写剪贴板 → 挂起，回焦点补写
                        if (!hasWindowFocus()) {
                            pendingClipboardWrite = text
                            pendingClipboardImage = null
                        } else {
                            platform.clipboard().setText(text)
                        }
                    }
                }
            } else {
                // 二进制载荷（图片等）：拉取成本高，暂停轮询 60s
                clipboardPollBackoffUntil = System.currentTimeMillis() + 60_000
                remoteClipboard.value = null to mime
                onClipboardFromRemote(null, mime)
                clipboardNotice.value = "远程剪贴板收到 $mime 内容（${bytes.size / 1024} KB）"
                if (!isReply && settings().clipboardAutoReceive && settings().clipboardBinaryEnabled) {
                    if (mime == "image/png") {
                        applyRemoteClipboardLocally {
                            if (!hasWindowFocus()) {
                                pendingClipboardImage = bytes
                                pendingClipboardWrite = null
                            } else {
                                platform.clipboard().setImagePng(bytes)
                            }
                        }
                    }
                }
            }
        }
    }

    /** 窗口焦点（Android 后台剪贴板限制判定；由 UI 层经 onAppFocusChanged 驱动维护） */
    @Volatile
    internal var hasWindowFocus: () -> Boolean = { true }

    /** 应用远程剪贴板到本地（抑制回声；无 Main dispatcher 的无头环境回退当前线程） */
    private fun applyRemoteClipboardLocally(apply: () -> Unit) {
        ioScope.launch {
            synchronized(clipboardEchoLock) { suppressClipboardEcho = true }
            try {
                try {
                    withContext(Dispatchers.Main) { apply() }
                } catch (_: IllegalStateException) {
                    // 无 Main dispatcher（无头探针）：直接执行（桌面 AWT 剪贴板可后台写）
                    apply()
                }
                delay(1500)
            } finally {
                synchronized(clipboardEchoLock) { suppressClipboardEcho = false }
            }
        }
    }

    /** 手动发送文本到远程剪贴板 */
    fun sendClipboardTextNow(text: String) {
        ioScope.launch {
            runCatching { sendClipboardChunked(text.toByteArray(Charsets.UTF_8), "text/plain") }
        }
    }

    // =====================================================================
    // 麦克风 / 摄像头上行（v2.4.0：设备选择）
    // =====================================================================

    /** 枚举硬件设备（麦克风/摄像头/扬声器）——UI 打开硬件面板时调用 */
    fun refreshHardwareDevices() {
        ioScope.launch {
            runCatching {
                micDevices.value = platform.enumerateMics()
                cameraDevices.value = platform.enumerateCameras()
                speakerDevices.value = platform.enumerateSpeakers()
                // 初始化选中状态：优先服务器条目保存的偏好
                if (selectedMicIds.value.isEmpty()) {
                    val pref = server.preferredMicIds.split(',').filter { it.isNotBlank() }
                        .filter { id -> micDevices.value.any { it.id == id } }
                    selectedMicIds.value = pref.ifEmpty {
                        micDevices.value.firstOrNull()?.let { listOf(it.id) } ?: emptyList()
                    }
                }
                if (selectedCameraId.value.isBlank()) {
                    val pref = server.preferredCameraId
                    selectedCameraId.value = if (cameraDevices.value.any { it.id == pref }) pref else ""
                }
                // v2.6.0 扬声器偏好（多选 + 独立音量）
                if (selectedSpeakerIds.value.isEmpty()) {
                    val pref = server.preferredSpeakerIds.split(',').filter { it.isNotBlank() }
                        .filter { id -> speakerDevices.value.any { it.id == id } }
                    selectedSpeakerIds.value = pref
                    applySpeakerOutputs(pref)
                }
            }
        }
    }

    /** 选择麦克风（多选混音；持久化到服务器条目；采集中则热切换） */
    fun selectMics(ids: List<String>) {
        selectedMicIds.value = ids
        server.preferredMicIds = ids.joinToString(",")
        if (micActive.value) {
            runCatching { micCapture.stop() }
            if (ids.isNotEmpty()) startMicUplink()
        }
    }

    /** 选择摄像头（多选：多路合成上行；采集中则热切换） */
    fun selectCamera(id: String) {
        val cur = selectedCameraId.value.split(',').filter { it.isNotBlank() }
        val next = if (id in cur) cur - id else cur + id
        selectedCameraId.value = next.joinToString(",")
        server.preferredCameraId = selectedCameraId.value
        if (webcamActive.value) {
            runCatching { webcamCapture.stop() }
            if (next.isNotEmpty()) startWebcamUplink()
        }
    }

    // ---- v2.6.0 扬声器多选 + 独立音量 ----

    /** 选择输出扬声器（多选；空 = 系统默认）；立即生效 */
    fun selectSpeakers(ids: List<String>) {
        selectedSpeakerIds.value = ids
        server.preferredSpeakerIds = ids.joinToString(",")
        applySpeakerOutputs(ids)
    }

    /** 设置单个扬声器音量（0..1；独立于应用主音量） */
    fun setSpeakerGain(deviceId: String, gain: Float) {
        val g = gain.coerceIn(0f, 1f)
        speakerGains.value = speakerGains.value + (deviceId to g)
        server.speakerGains = speakerGains.value.entries.joinToString(";") { (k, v) -> "$k:${"%.2f".format(v)}" }
        applySpeakerOutputs(selectedSpeakerIds.value)
    }

    private fun applySpeakerOutputs(ids: List<String>) {
        // 解析持久化的增益（id:gain;id:gain）
        val persisted = HashMap<String, Float>()
        for (part in server.speakerGains.split(';')) {
            val kv = part.split(':')
            val k = kv.getOrNull(0)?.takeIf { it.isNotBlank() } ?: continue
            kv.getOrNull(1)?.toFloatOrNull()?.let { persisted[k] = it.coerceIn(0f, 1f) }
        }
        // 合并当前内存值
        for ((k, v) in speakerGains.value) persisted[k] = v
        speakerGains.value = persisted
        val outputs = ids.mapNotNull { id ->
            val dev = speakerDevices.value.firstOrNull { it.id == id } ?: return@mapNotNull null
            id to (persisted[id] ?: 1f)
        }.toMap()
        runCatching { audioPlayer.setOutputDevices(outputs) }
        // Android SCO 特殊路由（蓝牙耳机语音通道）
        if (outputs.isNotEmpty()) {
            platform.setSpeakerDevice(ids.first())
        }
    }

    fun startMicUplink(): Boolean {
        // v2.7.0：服务器禁用麦克风时明确拒绝（旧版静默发送被丢弃的帧 = "麦克风有问题"的迷惑根因）
        if (!serverMicEnabled.value) {
            notices.value = "服务器已禁用麦克风（microphone_enabled=false），无法上行"
            micActive.value = false
            return false
        }
        if (micActive.value || micCapture.active) { micActive.value = true; return true }
        micActive.value = true
        micCapture.start(
            deviceIds = selectedMicIds.value,
            onOpusFrame = { opus ->
                noteUplinkBytes(opus.size + 1)
                ioScope.launch {
                    val frame = ByteArray(opus.size + 1)
                    frame[0] = Protocol.FRAME_MIC
                    opus.copyInto(frame, 1)
                    runCatching { connection.sendBinary(frame) }
                }
            },
            onError = { err ->
                notices.value = "麦克风：$err"
                micActive.value = false
                runCatching { micCapture.stop() }
            }
        )
        return true
    }

    fun stopMicUplink() {
        micActive.value = false
        runCatching { micCapture.stop() }
    }

    fun startWebcamUplink(): Boolean {
        // v2.7.0：服务器禁用摄像头时明确拒绝
        if (!serverWebcamEnabled.value) {
            notices.value = "服务器已禁用摄像头（webcam_enabled=false），无法上行"
            webcamActive.value = false
            return false
        }
        if (webcamActive.value || webcamCapture.active) { webcamActive.value = true; return true }
        webcamActive.value = true
        webcamCapture.start(
            encoder = server.webcamEncoder,
            deviceId = selectedCameraId.value,
            targetW = server.webcamWidth,
            targetH = server.webcamHeight,
            onFrame = { codecId, keyframe, data ->
                noteUplinkBytes(data.size + 3)
                ioScope.launch {
                    val frame = ByteArray(data.size + 3)
                    frame[0] = Protocol.FRAME_WEBCAM
                    frame[1] = codecId.toByte()
                    frame[2] = (if (keyframe) 0x01 else 0x00).toByte()
                    data.copyInto(frame, 3)
                    runCatching { connection.sendBinary(frame) }
                }
            },
            onError = { err ->
                notices.value = "摄像头：$err"
                webcamActive.value = false
                runCatching { webcamCapture.stop() }
            }
        )
        return true
    }

    fun stopWebcamUplink() {
        webcamActive.value = false
        runCatching { webcamCapture.stop() }
    }

    /** 请求执行远程命令 */
    fun sendCommand(command: String) = post {
        connection.sendText("cmd,$command")
    }

    // =====================================================================
    // v2.5.0：应用内音量 / 截图 / 录屏 / 剪贴板焦点 / 手柄保活
    // =====================================================================

    /** 初始化音量状态（SessionScreen 连接后调用一次） */
    fun initAudioVolume() {
        val s = settings()
        if (appVolume.value < 0) appVolume.value = s.speakerVolume
        appMuted.value = s.audioMuted
        applyVolumeNow()
    }

    /** 应用内音量（0..1，感知曲线；独立于系统音量——AudioTrack.setVolume/PCM 增益） */
    fun setAppVolume(v: Float) {
        appVolume.value = v.coerceIn(0f, 1f)
        settingsProvider().speakerVolume = v.coerceIn(0f, 1f)
        applyVolumeNow()
    }

    fun setAppMuted(muted: Boolean) {
        appMuted.value = muted
        settingsProvider().audioMuted = muted
        applyVolumeNow()
    }

    private fun applyVolumeNow() {
        // 感知（平方）曲线：小音量段调节更细腻
        val v = if (appMuted.value) 0f else {
            val base = appVolume.value.coerceIn(0f, 1f)
            base * base
        }
        runCatching { audioPlayer.setVolume(v) }
    }

    /** 截图当前视频帧（平台直取解码 Surface/位图；无系统截图权限） */
    fun captureScreenshot() {
        println("[Selkies][截图] 开始捕获")
        platform.captureVideoFrame { png ->
            println("[Selkies][截图] 捕获回调: ${if (png == null) "null" else "${png.size} 字节"}")
            if (png == null || png.isEmpty()) {
                savedMediaEvent.value = SavedMediaEvent("screenshot", null, "", error = "暂无可截取的视频帧")
                return@captureVideoFrame
            }
            val name = StreamRecorder.autoScreenshotName()
            val path = platform.mediaStore().saveScreenshot(png, name)
            println("[Selkies][截图] 保存: name=$name path=$path")
            savedMediaEvent.value = if (path != null) {
                SavedMediaEvent("screenshot", path, name, detail = "${png.size / 1024} KB")
            } else {
                SavedMediaEvent("screenshot", null, name, error = "保存失败（存储不可用）")
            }
        }
    }

    /** 录屏关键帧重试循环：muxer 写入首帧前持续请求关键帧
     *  （服务器限流 4 次/s 每显示，1.5s 间隔远低于限制；实测关键帧 1~5s 内到达） */
    private var recKeyframeJob: Job? = null

    /** v2.7.0：自动续录——分辨率/编码器切换分段保存后自动开新段（旧版只停不续 = 用户丢录后半段） */
    @Volatile private var recordingAutoContinue = false

    /** 开始录屏（WS 流直采；Android 前台服务保活→后台持续录） */
    fun startRecording(): Boolean {
        if (recorder.state == StreamRecorder.State.RECORDING) return false
        recorder.includeAudio = settings().recordingIncludeAudio && server.audioEnabled
        val name = StreamRecorder.autoName("")
        val target = platform.mediaStore().createRecordingFile(name) ?: return false
        val ok = recorder.start(target)
        if (ok) {
            recordingAutoContinue = true
            recordingActive.value = true
            // ⚠️【v2.6.0】录制启动即强制关键帧：H.264/H.265 复用器需要参数集（SPS/PPS）写头；
            //    服务器无限 GOP（按需关键帧）下不主动请求则可能长时间无关键帧 → 录屏无法开始
            forceKeyframe()
            // v2.6.0+：首帧未写入前每 1.5s 重试（单次请求可能被限流/竞态吞掉；实测
            //   RecProbe 首帧写入前有 ~1s 空窗，偶发 3.5s+ 仍无关键帧 → 0 帧录制）
            recKeyframeJob?.cancel()
            recKeyframeJob = scope.launch {
                var waited = 0
                while (isActive && !userClosed &&
                    recorder.state == StreamRecorder.State.RECORDING &&
                    recorder.videoFramesWritten == 0L
                ) {
                    delay(1500)
                    waited += 1500
                    if (recorder.state != StreamRecorder.State.RECORDING || recorder.videoFramesWritten > 0) break
                    if (waited > 30_000) break // 半分钟仍无帧：放弃重试（服务器可能已断流，由看门狗处理）
                    runCatching { connection.sendText("REQUEST_KEYFRAME") }
                }
            }
            RecordingShutdownHook.register(recorder)
            platform.setRecordingActive(true)
            recorder.onSaved = { path, frames, error, kind ->
                platform.setRecordingActive(false)
                recKeyframeJob?.cancel()
                RecordingShutdownHook.register(null)
                if (kind == "segment" && recordingAutoContinue && !userClosed &&
                    status.value == Status.CONNECTED
                ) {
                    // 分辨率切换分段：静默保存当前段，自动开新段继续录（recordingActive 保持 true，UI 不闪烁）
                    val next = platform.mediaStore().createRecordingFile(StreamRecorder.autoName(""))
                    if (next != null && recorder.start(next)) {
                        forceKeyframe()
                        RecordingShutdownHook.register(recorder)
                        platform.setRecordingActive(true)
                    } else {
                        recordingAutoContinue = false
                        recordingActive.value = false
                        notices.value = "分辨率切换：上一段录屏已保存，新段无法创建"
                    }
                } else {
                    recordingAutoContinue = false
                    recordingActive.value = false
                    savedMediaEvent.value = SavedMediaEvent(
                        "recording", path,
                        path?.substringAfterLast('/') ?: "",
                        detail = "$frames 帧 · ${recorder.durationSec().coerceAtLeast(0)}s",
                        error = error
                    )
                }
            }
        }
        return ok
    }

    /** 停止录屏并保存（幂等；v2.7.0：保存移出主线程——muxer 收尾可能数百 ms，旧版在 UI 线程同步执行 = ANR/闪退风险） */
    fun stopRecording() {
        recordingAutoContinue = false
        recKeyframeJob?.cancel()
        if (recorder.state == StreamRecorder.State.RECORDING) {
            ioScope.launch {
                runCatching { recorder.stopAndSave("manual") }
            }
        }
    }

    /** 应用焦点变化：Android 后台剪贴板读写受限 → 回前台时追赶 */
    fun onAppFocusChanged(gained: Boolean) {
        platform.notifyAppFocus(gained)
        if (!gained) return
        // 1) 远程→本地：补写挂起的剪贴板
        val pendingText = pendingClipboardWrite
        val pendingImg = pendingClipboardImage
        if (pendingText != null || pendingImg != null) {
            pendingClipboardWrite = null
            pendingClipboardImage = null
            ioScope.launch {
                applyRemoteClipboardLocally {
                    if (pendingText != null) platform.clipboard().setText(pendingText)
                    else if (pendingImg != null) platform.clipboard().setImagePng(pendingImg)
                }
            }
        }
        // 2) 本地→远程：回前台重读剪贴板（后台期间复制的内容此时才可读）
        ioScope.launch {
            delay(300) // 等 ClipboardManager 就绪
            runCatching {
                val t = platform.clipboard().getText()
                if (t != null && t != lastRemoteClipboardText) {
                    // 仅当与上次发送不同才推（lastText 追踪在 SessionScreen 的监听闭包里）
                    pushLocalClipboard(t)
                }
            }
        }
        // 3) v2.7.0：回前台立即拉取一次远程剪贴板（覆盖服务器 announcement 失效的部署；
        //    内容未变时由去重防护静默跳过，不会覆盖本地新复制的内容）
        if (status.value == Status.CONNECTED && settings().clipboardAutoReceive && settings().clipboardPollSync) {
            post { runCatching { connection.sendText("REQUEST_CLIPBOARD") } }
        }
    }

    /** 麦克风/摄像头上行字节计数（onFrame 回调里累加） */
    internal fun noteUplinkBytes(bytes: Int) {
        stats.value.uplinkTotalBytes += bytes.toLong()
        uplinkBytesWindow += bytes.toLong()
    }

    /** 启动手柄保活心跳（每 2s 对全部已连手柄发 js,h） */
    internal fun startGamepadHeartbeat() {
        if (gamepadHeartbeatJob?.isActive == true) return
        gamepadHeartbeatJob = scope.launch {
            while (isActive && !userClosed) {
                delay(2000)
                sendGamepadHeartbeats(this@SelkiesSession)
            }
        }
    }

    internal fun stopGamepadHeartbeat() {
        gamepadHeartbeatJob?.cancel()
        gamepadHeartbeatJob = null
    }

    /** 手柄面板「测试振动」：本地直测 rumble 通路（不经服务器） */
    fun platformTestRumble(slot: Int) {
        platform.rumbleGamepad(slot, 0.8f, 0.5f, 300)
    }

    companion object {
        fun alignRes(v: Int): Int {
            val even = if (v % 2 == 0) v else v - 1
            return even.coerceIn(16, 4080)
        }

        /** 自动重连次数上限：约 8 分钟退避总量，之后交还用户手动控制 */
        private const val MAX_RECONNECT_ATTEMPTS = 12
    }
}
