package com.selkies.client.core

import com.selkies.client.model.RemoteFileEntry
import com.selkies.client.model.TransferTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder
import java.util.UUID

/**
 * 文件传输客户端：上传（HTTP 分片）/ 下载（认证流式）/ 远程目录列表
 *
 * ⚠️ 路径处理约定（修复下载 400/401 与目录判定）：
 * - 远程路径一律保持服务器 href 的百分号编码形式（空格/中文不解码直接拼接）
 * - 目录 = href 以 / 结尾（服务器静态目录列表的真实信号，无需逐条探测 301）
 * - 下载 URL = /api/files/<编码路径>，携带 Authorization/UA 头（否则 nginx 回 401）
 */
class FilesClient(private val session: SelkiesSession) {

    val transfers = MutableStateFlow<List<TransferTask>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 解析 /api/files HTML 目录列表（实测服务器格式：
     *  <tr><td><a href="encoded%20name">显示名</a></td><td>mtime</td><td>size|-</td></tr>） */
    fun parseListing(html: String): List<RemoteFileEntry> {
        val entries = ArrayList<RemoteFileEntry>()
        // 行格式（宽松匹配：属性顺序/空白无关）
        val rowRe = Regex(
            """<tr[^>]*>\s*<td[^>]*><a[^>]+href="([^"]+)"[^>]*>([^<]*)</a></td>\s*<td[^>]*>([^<]*)</td>\s*<td[^>]*>([^<]*)</td>\s*</tr>""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (m in rowRe.findAll(html)) {
            val hrefRaw = m.groupValues[1].trim()
            if (hrefRaw.isEmpty()) continue
            // 跳过上级链接（../ 或 ./）
            if (hrefRaw == "../" || hrefRaw == ".." || hrefRaw == "./" || hrefRaw == ".") continue
            if (hrefRaw.startsWith("/")) continue  // 绝对路径链接（站点导航）不上传目录内容
            val isDir = hrefRaw.endsWith("/")
            val displayRaw = m.groupValues[2].ifBlank { hrefRaw }
            val displayName = URLDecoder.decode(displayRaw.removeSuffix("/"), "UTF-8")
            val mtime = m.groupValues[3].trim()
            val size = m.groupValues[4].trim()
            entries.add(
                RemoteFileEntry(
                    name = displayName,
                    path = hrefRaw,           // 保持编码形式
                    isDir = isDir,
                    size = if (isDir) "" else size,
                    mtime = mtime
                )
            )
        }
        return entries
    }

    /** 列出远程目录（dirPath 为空 = 根 = 上传目录；目录路径必须以 / 结尾） */
    suspend fun list(dirPath: String): Result<List<RemoteFileEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val p = dirPath.trimStart('/')
            val url = "/api/files/" + p   // p 已带尾斜杠（目录）或为空（根）
            val (code, body) = session.connection.apiGetText(url)
            if (code != 200) {
                return@runCatching if (code == 403) {
                    throw IllegalStateException("服务器禁用了文件下载（403）")
                } else if (code == 401) {
                    throw IllegalStateException("认证失败（401）：请检查服务器账号密码")
                } else {
                    throw IllegalStateException("列目录失败：HTTP $code")
                }
            }
            if (!body.contains("<")) {
                throw IllegalStateException("目录不存在或无内容")
            }
            parseListing(body)
        }
    }

    /** 上传本地文件（自动分片，进度回报）；remotePath 为已解码的相对路径 */
    fun upload(file: File, remotePath: String) {
        val task = TransferTask(
            id = UUID.randomUUID().toString(),
            fileName = file.name,
            filePath = file.absolutePath,
            totalBytes = file.length(),
            upload = true
        )
        addTask(task)
        scope.launch {
            try {
                task.status = "running"
                updateTask(task)
                val total = file.length()
                val chunkSize = 8L * 1024 * 1024
                val transferId = UUID.randomUUID().toString()
                if (total <= chunkSize) {
                    val bytes = file.readBytes()
                    val (code, msg) = session.connection.apiUploadChunk(remotePath, bytes, 0, total, null, false)
                    if (code !in 200..299) throw IllegalStateException("上传失败($code): ${msg.take(120)}")
                    task.sentBytes = total
                } else {
                    java.io.RandomAccessFile(file, "r").use { raf ->
                        val buf = ByteArray(chunkSize.toInt())
                        var offset = 0L
                        while (offset < total) {
                            val n = raf.read(buf)
                            if (n <= 0) break
                            val slice = if (n == buf.size) buf else buf.copyOf(n)
                            val isFinal = offset + n >= total
                            val (code, msg) = session.connection.apiUploadChunk(
                                remotePath, slice, offset, total, transferId, isFinal
                            )
                            if (code !in 200..299) throw IllegalStateException("上传失败($code): ${msg.take(120)}")
                            offset += n
                            task.sentBytes = offset
                            updateTask(task)
                        }
                    }
                }
                task.status = "done"
                task.sentBytes = task.totalBytes
                updateTask(task)
            } catch (e: Exception) {
                task.status = "error"
                task.message = e.message ?: e.toString()
                updateTask(task)
            }
        }
    }

    /**
     * 流式下载远程文件到本地（带认证，进度回报）。
     * remotePath 为编码形式的相对路径（RemoteFileEntry.path）。
     */
    fun download(remotePath: String, displayName: String, destDir: File) {
        val task = TransferTask(
            id = UUID.randomUUID().toString(),
            fileName = displayName,
            filePath = remotePath,
            totalBytes = 0,
            upload = false
        )
        addTask(task)
        destDir.mkdirs()
        val dest = File(destDir, displayName)
        scope.launch {
            try {
                task.status = "running"
                updateTask(task)
                val (code, written) = session.connection.apiDownloadTo(
                    "/api/files/" + remotePath.trimStart('/'),
                    dest
                ) { received, total ->
                    task.sentBytes = received
                    if (task.totalBytes == 0L && total > 0) task.totalBytes = total
                    updateTask(task)
                }
                if (code != 200) {
                    task.status = "error"
                    task.message = when (code) {
                        401 -> "下载失败（401）：认证失败，请检查账号密码"
                        403 -> "下载失败（403）：服务器禁用了文件下载"
                        404 -> "下载失败（404）：文件不存在或已被移动"
                        else -> "下载失败：HTTP $code"
                    }
                } else {
                    task.status = "done"
                    task.sentBytes = written
                    task.totalBytes = written
                    task.message = dest.absolutePath
                }
                updateTask(task)
            } catch (e: Exception) {
                task.status = "error"
                task.message = e.message ?: e.toString()
                updateTask(task)
            }
        }
    }

    private fun addTask(task: TransferTask) {
        transfers.value = transfers.value + task
    }

    /**
     * 下载到媒体库分类目录（v2.5.0）：
     * - Android: MediaStore Download/Selkies（无需存储权限）
     * - 桌面: ~/Selkies/Downloads
     * - 文件名自动带时间戳（不覆盖）+ 完成后触发保存引导弹窗（打开/所在文件夹/复制路径）
     */
    fun downloadToMedia(remotePath: String, displayName: String) {
        val task = TransferTask(
            id = UUID.randomUUID().toString(),
            fileName = displayName,
            filePath = remotePath,
            totalBytes = 0,
            upload = false
        )
        addTask(task)
        scope.launch {
            try {
                task.status = "running"
                updateTask(task)
                val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val dot = displayName.lastIndexOf('.')
                val autoName = if (dot > 0) {
                    "${displayName.substring(0, dot)}_$ts${displayName.substring(dot)}"
                } else "${displayName}_$ts"
                val handle = session.platform.mediaStore().openDownloadStream(autoName)
                if (handle == null) {
                    task.status = "error"
                    task.message = "存储不可用（无法创建下载文件）"
                    updateTask(task)
                    return@launch
                }
                val (code, written) = session.connection.apiDownloadStream(
                    "/api/files/" + remotePath.trimStart('/')
                ) { buf, n, total ->
                    handle.write(buf, 0, n)
                    task.sentBytes += n
                    if (task.totalBytes == 0L && total > 0) task.totalBytes = total
                    updateTask(task)
                }
                handle.close()
                if (code != 200) {
                    task.status = "error"
                    task.message = "下载失败：HTTP $code"
                } else {
                    task.status = "done"
                    task.sentBytes = written
                    task.totalBytes = written
                    task.message = handle.path
                    // 保存引导（打开/所在文件夹/复制路径）
                    if (session.settings().recordingNotifyOnSave) {
                        session.savedMediaEvent.value = com.selkies.client.model.SavedMediaEvent(
                            "download", handle.path, autoName,
                            detail = String.format(java.util.Locale.US, "%.1f MB", written / 1048576.0)
                        )
                    }
                }
                updateTask(task)
            } catch (e: Exception) {
                task.status = "error"
                task.message = e.message ?: e.toString()
                updateTask(task)
            }
        }
    }

    private fun updateTask(task: TransferTask) {
        transfers.value = transfers.value.map { if (it.id == task.id) task.copy() else it }
    }

    fun removeTask(id: String) {
        transfers.value = transfers.value.filter { it.id != id }
    }
}
