package com.selkies.client.core

import com.selkies.client.model.AppSettings
import com.selkies.client.model.ServerEntry
import com.selkies.client.model.StatsInfo
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
    val realizedResolution = MutableStateFlow<Pair<Int, Int>?>(null)
    val apps = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val role = MutableStateFlow("controller")
    val realizedResolutionFlow: StateFlow<Pair<Int, Int>?> get() = realizedResolution

    /** 服务器设置的镜像（encoder 等） */
    val serverSettings = MutableStateFlow<Map<String, kotlinx.serialization.json.JsonElement>>(emptyMap())

    /** 当前生效编码器（服务器通知） */
    val currentEncoder = MutableStateFlow("h264enc")

    var videoSink: VideoSink? = null
        set(value) {
            field?.let { old -> if (value !== old) old.close() }
            field = value
        }

    val audioPlayer by lazy { platform.makeAudioPlayer() }
    val micCapture by lazy { platform.makeMicCapture() }
    val webcamCapture by lazy { platform.makeWebcamCapture() }
    val filesClient by lazy { FilesClient(this) }

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
    private var suppressClipboardEcho = false
    private var lastRemoteClipboardText: String? = null
    private var clipboardMultipart: StringBuilder? = null
    private var clipboardMultipartMime = "text/plain"
    private var clipboardMultipartExpected = 0L
    private var clipboardMultipartIsReply = false
    private var awaitingReplyVerbs = mutableSetOf<String>()
    private var userClosed = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var ackTimer: Job? = null
    private var watchdogTimer: Job? = null
    private var startVideoAttempts = 0
    private var clipboardTid = 0
    private var statsSubscriptionActive = false
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
        connection = SelkiesConnection(server, this)
        connection.start()
    }

    fun disconnect() {
        userClosed = true
        reconnectJob?.cancel()
        ackTimer?.cancel()
        watchdogTimer?.cancel()
        keyHeartbeatJob?.cancel()
        heldKeys.clear()
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
            // 请求服务器剪贴板（cache-only）
            connection.sendText("cr")
        }
        startAckLoop()
    }

    private suspend fun sendInitialSettings() {
        val s = settings()
        val defW = 1920
        val defH = 1080
        val payload = LinkedHashMap<String, Any>()
        payload["manual_resolution"] = false
        payload["initialClientWidth"] = alignRes(screenWidthPx.takeIf { it > 0 } ?: defW)
        payload["initialClientHeight"] = alignRes(screenHeightPx.takeIf { it > 0 } ?: defH)
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
            payload["rate_control_mode"] = "bitrate"
        }
        payload["audio_bitrate"] = 128000
        payload["enable_binary_clipboard"] = s.clipboardBinaryEnabled
        // ---- 官方前端视频高级设置（仅非默认时发送，与官方 postSetting 键名一致）----
        if (server.jpegQuality in 1..100) payload["jpeg_quality"] = server.jpegQuality
        if (server.videoFullcolor) payload["video_fullcolor"] = true
        if (server.useCpu) payload["use_cpu"] = true
        if (server.forceAlignedResolution) payload["force_aligned_resolution"] = true
        if (server.videoStreamingMode in listOf("standard", "paint-over")) {
            payload["video_streaming_mode"] = server.videoStreamingMode
        }
        if (server.paintOverCrf in 1..51) payload["video_paintover_crf"] = server.paintOverCrf
        if (server.paintOverBurstFrames > 0) payload["video_paintover_burst_frames"] = server.paintOverBurstFrames
        if (server.paintOverJpegQuality in 1..100) payload["paint_over_jpeg_quality"] = server.paintOverJpegQuality
        if (server.usePaintOverQuality) payload["use_paint_over_quality"] = true
        connection.sendText("SETTINGS," + jsonEncode(payload))
    }

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
                        // 请求分辨率 + 启动视频
                        val w = realizedResolution.value?.first ?: stats.value.let {
                            parseResW(it.resolution) ?: 1280
                        }
                        val h = realizedResolution.value?.second ?: stats.value.let {
                            parseResH(it.resolution) ?: 720
                        }
                        connection.sendText(Protocol.resolution(alignRes(w), alignRes(h), server.displayId))
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
            text == "AUDIO_STARTED" -> { runCatching { audioPlayer.start(48000, 1) } }
            text == "AUDIO_STOPPED" -> { }
            text == "AUDIO_DISABLED" -> { statusDetail.value = "音频被服务器禁用" }
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
            text.startsWith("cursor,") -> { /* 服务器光标样式，触屏模式渲染本地指针即可 */ }
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
                "stream_info" -> { }
                "stream_stats" -> {
                    val st = stats.value
                    (obj["fps"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.let { st.fps = it.toInt() }
                    (obj["latency_ms"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()?.let { st.latencyMs = it }
                    (obj["bitrate_mbps"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.let { st.bitrateMbps = it }
                    (obj["video_codec"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { st.codec = it }
                    stats.value = st
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
                    val parts = action.split(",")
                    if (parts.size >= 5) {
                        val strong = parts[2].toDoubleOrNull() ?: 0.0
                        val ms = parts[4].toLongOrNull() ?: 200
                        if (strong > 0) platform.vibrate(ms)
                    }
                }
                action.startsWith("command_error,") -> { /* 命令错误提示 */ }
                action.startsWith("command_done,") -> { }
            }
        }
    }

    private fun handleCaptureDemand(subject: String, wanted: Boolean) {
        val s = settings()
        if (subject == "webcam" || subject.contains("webcam")) {
            if (wanted) {
                if (webcamCapture.active) return
                startWebcamUplink()
            } else {
                runCatching { webcamCapture.stop() }
            }
        } else if (subject == "microphone" || subject.contains("microphone")) {
            if (wanted && s.micEnabled && !micCapture.active) {
                startMicUplink()
            } else if (!wanted) {
                runCatching { micCapture.stop() }
            }
        }
    }

    private fun handlePrintDocument(name: String, sizeBytes: Long) {
        ioScope.launch {
            runCatching {
                val (code, bytes) = connection.apiGetBytes("/api/print/" + java.net.URLEncoder.encode(name, "UTF-8"))
                if (code == 200 && bytes.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        onPrintDocument(name, sizeBytes)
                    }
                    platform.print().printPdf(bytes, name)
                } else {
                    lastError.value = "远程打印失败：获取打印文档 $name 失败（HTTP $code）"
                }
            }.onFailure {
                lastError.value = "远程打印失败：${it.message ?: it.javaClass.simpleName}"
            }
        }
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
                        if (opus.isNotEmpty()) audioPlayer.playOpus(opus)
                    }
                }
                Protocol.FRAME_JPEG -> {
                    Protocol.parseJpegStripeHeader(data, length)?.let { (frameId, stripeY, off) ->
                        noteVideoChunk(frameId)
                        videoSink?.onJpegStripe(frameId, stripeY, data, off, length)
                    }
                }
                Protocol.FRAME_VIDEO -> {
                    Protocol.parseVideoFrameHeader(data, length)?.let { (header, off) ->
                        noteVideoChunk(header.frameId)
                        stats.value.videoFrames++
                        videoSink?.onVideoFrame(header, data, off, length)
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
        if (role.value != "viewer") {
            // 官方 VideoWorker lostFrame 语义：检测到帧号跳跃（丢帧）即上报，
            // 服务器据此统计/补偿。限定每秒最多报一次避免风暴。
            if (lastReceivedFrameId >= 0 && frameId > lastReceivedFrameId + 1 &&
                System.currentTimeMillis() - lastLostFrameReportAt > 1000
            ) {
                lastLostFrameReportAt = System.currentTimeMillis()
                post { connection.sendText("LOST_FRAME $frameId") }
            }
            lastReceivedFrameId = frameId
        }
        lastReceivedFrameAt = System.currentTimeMillis()
        disarmStartVideoWatchdog()
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
                    // 仍无视频：请求关键帧并重连
                    runCatching { connection.sendText("REQUEST_KEYFRAME") }
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
    }

    fun sendMouseMoveRelative(dx: Int, dy: Int, mask: Int) = post {
        connection.sendText(Protocol.mouseMoveRelative(dx, dy, mask, nextPointerSeq()))
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
        connection.sendText("REQUEST_KEYFRAME")
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
        if (suppressClipboardEcho) return
        val s = settings()
        if (!s.clipboardAutoSend) return
        if (text == null) return
        if (text == lastRemoteClipboardText) return
        lastRemoteClipboardText = text
        ioScope.launch {
            runCatching {
                val bytes = text.toByteArray(Charsets.UTF_8)
                sendClipboardChunked(bytes, "text/plain")
            }
        }
    }

    fun pushLocalImage(png: ByteArray) {
        if (suppressClipboardEcho) return
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
                lastRemoteClipboardText = text
                onClipboardFromRemote(text, mime)
                if (!isReply && settings().clipboardAutoReceive && text.isNotEmpty()) {
                    applyRemoteClipboardLocally {
                        platform.clipboard().setText(text)
                    }
                }
            } else {
                onClipboardFromRemote(null, mime)
                if (!isReply && settings().clipboardAutoReceive && settings().clipboardBinaryEnabled) {
                    if (mime == "image/png") {
                        applyRemoteClipboardLocally {
                            platform.clipboard().setImagePng(bytes)
                        }
                    }
                }
            }
        }
    }

    /** 应用远程剪贴板到本地（抑制回声） */
    private fun applyRemoteClipboardLocally(apply: () -> Unit) {
        ioScope.launch {
            suppressClipboardEcho = true
            try {
                withContext(Dispatchers.Main) { apply() }
                delay(1500)
            } finally {
                suppressClipboardEcho = false
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
    // 麦克风 / 摄像头上行
    // =====================================================================

    fun startMicUplink() {
        if (micCapture.active) return
        micCapture.start(
            onOpusFrame = { opus ->
                ioScope.launch {
                    val frame = ByteArray(opus.size + 1)
                    frame[0] = Protocol.FRAME_MIC
                    opus.copyInto(frame, 1)
                    runCatching { connection.sendBinary(frame) }
                }
            },
            onError = { err ->
                lastError.value = "麦克风错误：$err"
                runCatching { micCapture.stop() }
            }
        )
    }

    fun stopMicUplink() {
        runCatching { micCapture.stop() }
    }

    fun startWebcamUplink() {
        if (webcamCapture.active) return
        webcamCapture.start(
            encoder = "h264",
            onFrame = { codecId, keyframe, data ->
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
                lastError.value = "摄像头错误：$err"
                runCatching { webcamCapture.stop() }
            }
        )
    }

    fun stopWebcamUplink() {
        runCatching { webcamCapture.stop() }
    }

    /** 请求执行远程命令 */
    fun sendCommand(command: String) = post {
        connection.sendText("cmd,$command")
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
