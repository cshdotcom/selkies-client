package com.selkies.client.core

import com.selkies.client.model.ServerEntry
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readAvailable
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import com.selkies.client.platform.createHttpClient
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.ConnectException
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Selkies WebSocket 连接层
 * - 双路径探测（/api/websockets → /api/ws）
 * - HTTP 301/302 重定向手动跟随（保留认证头）
 * - Basic / Bearer 认证，401/403 明确报错
 * - 详尽的用户可读错误（取代笼统的"未预料的错误"）
 */
class SelkiesConnection(
    private val server: ServerEntry,
    private val listener: Listener
) {
    interface Listener {
        fun onOpened()
        fun onText(text: String)
        fun onBinary(data: ByteArray, length: Int)
        fun onClosed(code: Int, reason: String)
        fun onError(userMessage: String, technical: String)
        fun onRedirected(from: String, to: String)
    }

    companion object {
        /** 模拟网页客户端的 UA，最大化反代/CDN 兼容性 */
        const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 SelkiesNativeClient/2.0"
        private val WS_PATHS = listOf("/api/websockets", "/api/ws")
        private const val PROBE_TIMEOUT_MS = 12_000L
        private const val MAX_REDIRECTS = 5
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val http: HttpClient = com.selkies.client.platform.createHttpClient(server.allowUntrustedCert)
    private var wsJob: kotlinx.coroutines.Job? = null
    private var wsSession: DefaultClientWebSocketSession? = null

    @Volatile
    private var closing = false

    private val sendMutex = Mutex()

    val authHeader: String?
        get() {
            if (server.useTokenAuth && server.token.isNotBlank()) {
                return "Bearer ${server.token}"
            }
            if (server.username.isNotBlank() || server.password.isNotBlank()) {
                val raw = "${server.username}:${server.password}"
                val b64 = java.util.Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
                return "Basic $b64"
            }
            return null
        }

    /** ws(s) → http(s) */
    private fun toHttpUrl(u: String): String = when {
        u.startsWith("wss://") -> "https://" + u.drop(6)
        u.startsWith("ws://") -> "http://" + u.drop(5)
        else -> u
    }

    /** http(s) → ws(s) */
    private fun toWsUrl(u: String): String = when {
        u.startsWith("https://") -> "wss://" + u.drop(8)
        u.startsWith("http://") -> "ws://" + u.drop(7)
        u.startsWith("wss://") || u.startsWith("ws://") -> u
        else -> "wss://$u"
    }

    private fun originOf(u: Url): String =
        "${u.protocol.name}://${u.host}" + (if (u.specifiedPort == 80 || u.specifiedPort == 443 || u.specifiedPort == 0) "" else ":${u.specifiedPort}")

    /** RFC 3986 简化相对 URL 解析（处理 Location 头） */
    private fun resolveUrl(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://") ||
            ref.startsWith("ws://") || ref.startsWith("wss://")
        ) return ref
        val b = Url(base)
        val port = if (b.specifiedPort == 0 || b.specifiedPort == 80 || b.specifiedPort == 443) "" else ":${b.specifiedPort}"
        val origin = "${b.protocol.name}://${b.host}$port"
        if (ref.startsWith("//")) return "${b.protocol.name}:$ref"
        if (ref.startsWith("/")) return origin + ref
        val baseDir = b.encodedPath.substringBeforeLast('/', "")
        return "$origin$baseDir/$ref"
    }

    /** 丢弃响应体以释放连接 */
    private suspend fun discardBody(resp: HttpResponse) {
        runCatching {
            val ch = resp.bodyAsChannel()
            val buf = ByteArray(16384)
            while (true) {
                val n = ch.readAvailable(buf, 0, buf.size)
                if (n <= 0) break
            }
        }
    }

    /**
     * 解析最终 WS URL：跟随重定向、探测路径与认证。
     * 返回 null 表示无可用端点（已通过 onError 报告原因）。
     */
    private suspend fun resolveWsUrl(): String? {
        val base = Url(server.baseUrl())
        val basePort = if (base.specifiedPort == 0) "" else ":${base.specifiedPort}"
        val prefix = base.encodedPath.trimEnd('/')

        var lastStatus = 0
        var lastError: String? = null

        for (path in WS_PATHS) {
            // ⚠️ 必须显式拼入 base.host——漏掉主机名会导致 OkHttp 解析空主机抛 UnknownHostException（v2.0.0 连不上的根因之一）
            var current: Url = Url("${base.protocol.name}://${base.host}$basePort$prefix$path")
            var redirects = 0
            var endpointFound = false
            var abort: Boolean
            try {
                while (true) {
                    abort = false
                    val probeUrl = Url(toHttpUrl(current.toString()))
                    val probed: HttpResponse = try {
                        withTimeout(PROBE_TIMEOUT_MS) {
                            http.get(probeUrl.toString()) {
                                headers {
                                    authHeader?.let { append(HttpHeaders.Authorization, it) }
                                    append(HttpHeaders.UserAgent, USER_AGENT)
                                    append(HttpHeaders.Origin, originOf(current))
                                    append(HttpHeaders.Accept, "*/*")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        lastError = friendlyNetworkError(e)
                        break
                    }
                    val status = probed.status.value
                    val location = probed.headers[HttpHeaders.Location]
                    val serverHdr = probed.headers[HttpHeaders.Server]
                    discardBody(probed)
                    when {
                        status in 301..308 && location != null -> {
                            val next = Url(resolveUrl(current.toString(), location))
                            listener.onRedirected(current.toString(), next.toString())
                            current = Url(toWsUrl(next.toString()))
                            redirects++
                            if (redirects > MAX_REDIRECTS) {
                                listener.onError(
                                    "重定向次数过多（超过 $MAX_REDIRECTS 次），请检查反向代理配置",
                                    "redirect loop ending at $location"
                                )
                                return null
                            }
                            continue
                        }
                        status == 401 -> {
                            if (authHeader == null) {
                                listener.onError(
                                    "服务器要求认证（HTTP 401）：请在服务器配置中填写用户名和密码",
                                    "401 Unauthorized; no credentials configured"
                                )
                            } else {
                                listener.onError(
                                    "认证失败（HTTP 401）：${if (server.useTokenAuth) "请检查令牌是否正确" else "用户名或密码错误"}",
                                    "401 Unauthorized with credentials user='${server.username.ifBlank { "(empty)" }}'"
                                )
                            }
                            return null
                        }
                        status == 403 -> {
                            val isCf = serverHdr?.contains("cloudflare", ignoreCase = true) == true
                            listener.onError(
                                if (isCf) "被 Cloudflare 拦截（403）：请将此客户端的网络加入白名单，或将该域名的 Cloudflare 代理关闭（DNS 仅记录模式）"
                                else "访问被拒绝（HTTP 403）：来源或权限被服务器拒绝",
                                "403 Forbidden; server=$serverHdr"
                            )
                            return null
                        }
                        status == 409 -> {
                            listener.onError(
                                "服务器当前以 WebRTC 模式运行（HTTP 409）。本客户端走 WebSocket 模式，请在服务器端将流模式切换为 websockets。",
                                "409 Conflict: webrtc mode active"
                            )
                            return null
                        }
                        status == 404 || status == 405 -> {
                            lastStatus = status
                            break
                        }
                        status == 502 || status == 503 || status == 504 -> {
                            lastError = "服务器网关错误（HTTP $status）：后端未就绪或反向代理配置有误"
                            lastStatus = status
                            break
                        }
                        else -> {
                            // 200 / 400 等——端点存在（WS 端点对普通 GET 常回 400）
                            endpointFound = true
                            break
                        }
                    }
                }
                if (endpointFound) return current.toString()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastError = friendlyNetworkError(e)
            }
        }
        listener.onError(
            "未找到 WebSocket 端点：/api/websockets 与 /api/ws 均不可用。" +
                    "请确认这是 Selkies 服务器且地址/端口正确（最后状态码 $lastStatus）",
            "no ws endpoint; lastStatus=$lastStatus lastError=$lastError"
        )
        return null
    }

    private fun friendlyNetworkError(e: Throwable): String = when (e) {
        is UnknownHostException -> "无法解析主机：请检查服务器地址是否正确（DNS 解析失败）"
        is ConnectException -> "连接被拒绝：请检查端口是否开放、服务是否在运行"
        is SSLException -> "TLS/证书错误：若服务器使用自签名证书，请在服务器设置中开启「允许不受信任证书」"
        is kotlinx.coroutines.TimeoutCancellationException -> "连接超时：服务器无响应，请检查网络与防火墙设置"
        else -> "网络错误：${e.javaClass.simpleName}：${e.message ?: "未知原因"}"
    }

    /**
     * 建立 WebSocket 连接（挂起直到连接结束）。返回 false 时已通过 onError 报告原因。
     * 只读模式追加 ?role=viewer：官方共享模式下的旁观通道，服务器不授予键鼠权限（非控制通道）。
     */
    suspend fun connectOnce(): Boolean {
        closing = false
        val wsUrl = resolveWsUrl() ?: return false
        val viewerSuffix = if (server.viewOnly) {
            if (wsUrl.contains('?')) "&role=viewer" else "?role=viewer"
        } else ""
        val url = Url(toWsUrl(wsUrl) + viewerSuffix)
        try {
            val session = http.webSocketSession {
                // ⚠️ webSocketSession 必须用 ws(s):// URL；http(s):// 会导致引擎按普通 GET 处理（无 101 升级）
                url(toWsUrl(wsUrl) + viewerSuffix)
                headers {
                    authHeader?.let { append(HttpHeaders.Authorization, it) }
                    append(HttpHeaders.UserAgent, USER_AGENT)
                    append(HttpHeaders.Origin, originOf(url))
                }
            }
            wsSession = session
            listener.onOpened()
            var sawCloseFrame = false
            try {
                for (frame in session.incoming) {
                    // ⚠️【重连竞态修复】close() 置位 closing 后（重连释放旧连接/用户断开），
                    //    旧连接仍可能投递残留帧（尤其 KILL/close）——必须立即停止向监听者投递，
                    //    否则服务器发给旧连接的 KILL 会污染共享会话状态（表现为“已断开”假死）。
                    if (closing) break
                    when (frame) {
                        is io.ktor.websocket.Frame.Text -> listener.onText(frame.readText())
                        is io.ktor.websocket.Frame.Binary -> {
                            val buf = frame.data.copyOf()
                            listener.onBinary(buf, buf.size)
                        }
                        is io.ktor.websocket.Frame.Close -> {
                            sawCloseFrame = true
                            // 主动关闭路径（重连释放旧连接/用户断开）不再回调，避免与新连接竞争
                            if (!closing) {
                                val reason = frame.readReason()
                                listener.onClosed(reason?.code?.toInt() ?: 1000, reason?.message ?: "")
                            }
                            break
                        }
                        else -> {}
                    }
                }
            } finally {
                wsSession = null
                runCatching { session.close() }
            }
            // ⚠️【静默死亡修复】TCP 被链路中途掐断（RST/FIN，无 Close 帧）时 incoming 通道
            //    直接关闭、for 循环正常退出——若不在此上报，会话将永远停留在 CONNECTED
            //    状态且视频冻结，永远不会触发重连（用户"一直断开/卡死"的隐藏根因之一）。
            if (!sawCloseFrame && !closing) {
                listener.onClosed(-1, "连接异常中断（未收到关闭帧）")
            }
            return true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (closing) return true
            listener.onError(friendlyWsError(e), "${e.javaClass.simpleName}: ${e.message}")
            return false
        }
    }

    private fun friendlyWsError(e: Throwable): String {
        val msg = e.message ?: ""
        return when {
            e is SSLException -> friendlyNetworkError(e)
            e is UnknownHostException -> friendlyNetworkError(e)
            e is ConnectException -> friendlyNetworkError(e)
            msg.contains("401") -> "WebSocket 握手认证失败（401）：用户名或密码错误"
            msg.contains("403") -> "WebSocket 握手被拒绝（403）"
            msg.contains("404") -> "WebSocket 路径不存在（404）"
            msg.contains("409") -> "服务器运行在 WebRTC 模式（409），需要 WebSocket 模式"
            msg.contains("Handshake", true) || msg.contains("Unexpected response", true) ->
                "WebSocket 握手失败：${msg.take(180)}"
            e is kotlinx.coroutines.TimeoutCancellationException -> friendlyNetworkError(e)
            else -> "WebSocket 连接中断：${msg.take(200).ifBlank { e.javaClass.simpleName }}"
        }
    }

    /** 异步启动连接 */
    fun start() {
        wsJob = scope.launch { connectOnce() }
    }

    suspend fun sendText(text: String) {
        sendMutex.withLock {
            wsSession?.send(io.ktor.websocket.Frame.Text(text))
        }
    }

    suspend fun sendBinary(data: ByteArray) {
        sendMutex.withLock {
            wsSession?.send(io.ktor.websocket.Frame.Binary(true, data))
        }
    }

    fun close() {
        closing = true
        val s = wsSession
        wsSession = null
        if (s != null) {
            scope.launch {
                runCatching { s.close(io.ktor.websocket.CloseReason(1000, "client closing")) }
            }
        }
        wsJob?.cancel()
        runCatching { http.close() }
    }

    // ---- HTTP API（文件/打印） ----

    private fun apiHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = HashMap<String, String>()
        authHeader?.let { h[HttpHeaders.Authorization] = it }
        h[HttpHeaders.UserAgent] = USER_AGENT
        for ((k, v) in extra) h[k] = v
        return h
    }

    /** 与官方前端 encodeURIComponent 一致的路径段编码（空格→%20，而非 +） */
    fun encodePathSegment(path: String): String =
        URLEncoder.encode(path, "UTF-8").replace("+", "%20")

    suspend fun resolveApiUrl(path: String): String {
        var url = server.baseUrl() + path
        var redirects = 0
        while (redirects < MAX_REDIRECTS) {
            // ⚠️ 用 HEAD 解析重定向：GET 会把整个文件体拉下来再丢弃（大文件双倍下载）
            val resp: HttpResponse? = try {
                withTimeout(PROBE_TIMEOUT_MS) {
                    http.head(url) { headers { apiHeaders().forEach { (k, v) -> append(k, v) } } }
                }
            } catch (_: Exception) {
                null
            }
            if (resp == null) return url
            val loc = resp.headers[HttpHeaders.Location]
            val st = resp.status.value
            discardBody(resp)
            if (loc != null && st in 301..308) {
                url = resolveUrl(url, loc)
                redirects++
            } else break
        }
        return url
    }

    suspend fun apiGetBytes(path: String): Pair<Int, ByteArray> {
        val url = resolveApiUrl(path)
        val resp = withTimeout(300_000L) {
            http.get(url) { headers { apiHeaders().forEach { (k, v) -> append(k, v) } } }
        }
        val code = resp.status.value
        val bytes = resp.bodyAsChannel().toByteArrayCompat()
        return code to bytes
    }

    /**
     * ⚠️【下载 401 根因修复】带认证头的流式下载。
     *    旧版 prepareGet(url) 未携带 Authorization/UA/Origin，
     *    服务器（nginx Basic Auth）一律回 401。
     * 返回 (httpCode, 已写字节数)；非 2xx 时写 0。
     */
    suspend fun apiDownloadTo(
        path: String,
        dest: File,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> }
    ): Pair<Int, Long> {
        val url = resolveApiUrl(path)
        val tmp = File(dest.absolutePath + ".part")
        return try {
            val resp = withTimeout(600_000L) {
                http.prepareGet(url) {
                    headers { apiHeaders().forEach { (k, v) -> append(k, v) } }
                }.execute { resp ->
                    val code = resp.status.value
                    if (code != 200) {
                        discardBody(resp)
                        return@execute code to 0L
                    }
                    val total = resp.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L
                    val channel = resp.bodyAsChannel()
                    java.io.FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var received = 0L
                        while (true) {
                            val n = channel.readAvailable(buf, 0, buf.size)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            received += n
                            onProgress(received, total)
                        }
                    }
                    code to tmp.length()
                }
            }
            if (resp.first == 200) {
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
            } else {
                tmp.delete()
            }
            resp
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    suspend fun apiGetText(path: String): Pair<Int, String> {
        val url = resolveApiUrl(path)
        val resp = withTimeout(60_000L) {
            http.get(url) { headers { apiHeaders().forEach { (k, v) -> append(k, v) } } }
        }
        val code = resp.status.value
        val text = resp.bodyAsText()
        discardBody(resp)
        return code to text
    }

    suspend fun apiUploadChunk(
        path: String,
        data: ByteArray,
        offset: Long,
        total: Long,
        transferId: String?,
        final: Boolean
    ): Pair<Int, String> {
        val url = resolveApiUrl("/api/upload")
        val extraHeaders = mutableMapOf(
            HttpHeaders.ContentType to "application/octet-stream",
            // ⚠️ 与官方 file-upload.js 一致：encodeURIComponent 编码（空格=%20）
            "X-Upload-Path" to encodePathSegment(path)
        )
        if (transferId != null) {
            extraHeaders["X-Upload-Id"] = transferId
            extraHeaders["X-Upload-Offset"] = offset.toString()
            extraHeaders["X-Upload-Total"] = total.toString()
            if (final) extraHeaders["X-Upload-Final"] = "1"
        }
        val resp = withTimeout(600_000L) {
            http.post(url) {
                headers { apiHeaders(extraHeaders).forEach { (k, v) -> append(k, v) } }
                setBody(data)
            }
        }
        val code = resp.status.value
        val text = runCatching { resp.bodyAsText() }.getOrDefault("")
        discardBody(resp)
        return code to text
    }

    /** 探测 API：返回状态码与 Location 头（用于目录判定等） */
    suspend fun probeApi(path: String): Pair<Int, String?> {
        val url = resolveApiUrl(path)
        return try {
            withTimeout(PROBE_TIMEOUT_MS) {
                val resp = http.get(url) { headers { apiHeaders().forEach { (k, v) -> append(k, v) } } }
                val code = resp.status.value
                val loc = resp.headers[HttpHeaders.Location]
                discardBody(resp)
                code to loc
            }
        } catch (e: Exception) {
            -1 to null
        }
    }
}

/** ByteReadChannel -> ByteArray（通用） */
private suspend fun io.ktor.utils.io.ByteReadChannel.toByteArrayCompat(): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(65536)
    while (true) {
        val n = readAvailable(buf, 0, buf.size)
        if (n <= 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
