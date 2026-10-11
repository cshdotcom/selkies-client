package com.selkies.client.core

import com.selkies.client.model.RemoteFileEntry
import com.selkies.client.model.TransferTask
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
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
 * 文件传输客户端：上传（HTTP 分片）/ 下载（流式）/ 远程目录列表
 */
class FilesClient(private val session: SelkiesSession) {

    val transfers = MutableStateFlow<List<TransferTask>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 解析 /api/files HTML 目录列表 */
    fun parseListing(html: String): List<RemoteFileEntry> {
        val entries = ArrayList<RemoteFileEntry>()
        val rowRe = Regex(
            """<tr>\s*<td><a href="([^"]+)">([^<]*)</a></td>\s*<td>([^<]*)</td>\s*<td>([^<]*)</td>\s*</tr>""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (m in rowRe.findAll(html)) {
            val name = URLDecoder.decode(m.groupValues[1], "UTF-8")
            if (name.isEmpty() || name == "..") continue
            val displayName = m.groupValues[2]
            val mtime = m.groupValues[3]
            val size = m.groupValues[4]
            // 目录判定：服务器对目录 301 重定向到带斜杠 URL；HTML 中无法直接区分，
            // 用常识判断：无扩展名或 size 列为 "-"。更可靠的方式 HEAD 探测，此处用启发式 + 探测回退
            val isDir = size.trim() == "-" || (size.isBlank() && !displayName.contains('.'))
            entries.add(RemoteFileEntry(displayName.ifEmpty { name }, isDir, size, mtime))
        }
        return entries
    }

    /** 列出远程目录（path 为空 = 根 = 上传目录） */
    suspend fun list(path: String): Result<List<RemoteFileEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = path.trimStart('/')
            val (code, body) = session.connection.apiGetText("/api/files/" + clean.let { if (it.isEmpty()) "" else it })
            if (code != 200) {
                return@runCatching if (code == 403) {
                    throw IllegalStateException("服务器禁用了文件下载（403）")
                } else {
                    throw IllegalStateException("列目录失败：HTTP $code")
                }
            }
            parseListing(body)
        }
    }

    /** 判断远程条目是否为目录（通过 301 重定向） */
    suspend fun isDirectory(path: String): Boolean {
        val clean = path.trim('/')
        if (clean.isEmpty()) return true
        val (status, loc) = session.connection.probeApi("/api/files/$clean")
        return status == 301 && (loc?.endsWith("/") == true)
    }

    /** 上传本地文件（自动分片，进度回报） */
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

    /** 流式下载远程文件到本地 */
    fun download(remotePath: String, dest: File) {
        val task = TransferTask(
            id = UUID.randomUUID().toString(),
            fileName = remotePath.substringAfterLast('/'),
            filePath = remotePath,
            totalBytes = 0,
            upload = false
        )
        addTask(task)
        scope.launch {
            try {
                task.status = "running"
                updateTask(task)
                val clean = remotePath.trim('/')
                val url = session.connection.resolveApiUrl("/api/files/$clean")
                val (code, file) = withContext(Dispatchers.IO) {
                    var respCode = 0
                    val tmp = File(dest.absolutePath + ".part")
                    session.connection.http.prepareGet(url).execute { resp ->
                        respCode = resp.status.value
                        if (respCode != 200) {
                            return@execute respCode to null
                        }
                        val channel = resp.bodyAsChannel()
                        java.io.FileOutputStream(tmp).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var received = 0L
                            while (true) {
                                val n = channel.readAvailable(buf, 0, buf.size)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                received += n
                                task.sentBytes = received
                                if (task.totalBytes == 0L) {
                                    resp.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLongOrNull()?.let {
                                        task.totalBytes = it
                                    }
                                }
                                updateTask(task)
                            }
                        }
                        respCode to tmp
                    }
                    Pair(respCode, null as File?)
                }
                if (code != 200) throw IllegalStateException("下载失败：HTTP $code")
                val tmp = file ?: throw IllegalStateException("下载数据为空")
                if (dest.exists()) dest.delete()
                tmp.renameTo(dest)
                task.status = "done"
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

    private fun updateTask(task: TransferTask) {
        transfers.value = transfers.value.map { if (it.id == task.id) task.copy() else it }
    }

    fun removeTask(id: String) {
        transfers.value = transfers.value.filter { it.id != id }
    }
}
