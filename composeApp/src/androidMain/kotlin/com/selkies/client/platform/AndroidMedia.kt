package com.selkies.client.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.view.Surface
import com.selkies.client.MainActivity
import com.selkies.client.model.SavedMediaEntry
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 媒体存储（v2.5.0）：截图 → Pictures/Selkies；录屏 → Movies/Selkies；下载 → Download/Selkies。
 * API 29+ 经 MediaStore 免存储权限；API 26-28 回退公共目录（需 WRITE_EXTERNAL_STORAGE，未授权时回退应用外部私有目录）。
 *
 * v2.7.5【默认存储位置选择器】：用户经系统 SAF 选择器选定目录后（treeUriProvider 非空且有效），
 * 全部保存改走 SAF：{所选根}/Selkies/{Screenshots|Recordings|Downloads}，跨重启持久授权、支持 U 盘/SD 卡/任意目录。
 * treeUri 失效（系统回收授权/目录被删）时自动回退默认 MediaStore 行为。
 */
class AndroidMediaStoreBridge(
    private val ctx: Context,
    private val treeUriProvider: () -> String = { "" }
) : MediaStoreBridge {

    private fun q29(): Boolean = Build.VERSION.SDK_INT >= 29

    // ---------------- v2.7.5 SAF 目录支持 ----------------

    /** 当前生效的 SAF 目录树（无授权/无效 → null = 走默认 MediaStore） */
    private fun safTree(): Uri? {
        val s = treeUriProvider().trim()
        if (s.isEmpty() || !s.startsWith("content://")) return null
        val uri = runCatching { Uri.parse(s) }.getOrNull() ?: return null
        // 持久写权限仍在？（系统最多保留 512 个；被回收/目录删除 → 回退默认）
        val granted = runCatching {
            ctx.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isWritePermission
            }
        }.getOrDefault(false)
        if (!granted) return null
        // 目录树仍可访问（docId 可解析）
        return runCatching { SafPaths.treeRootDocUri(uri) }.getOrNull()
    }

    /** SAF 根 → {根}/Selkies（不存在则创建） */
    private fun safTopDir(tree: Uri): Uri? =
        SafPaths.findOrCreateDir(ctx, tree, SafPaths.TOP_DIR)

    /** SAF 子目录：{根}/Selkies/{sub} */
    private fun safSubDir(tree: Uri, sub: String): Uri? {
        val top = safTopDir(tree) ?: return null
        return SafPaths.findOrCreateDir(ctx, top, sub)
    }

    /** SAF 展示根路径（真实绝对路径，解析不出时为可读描述） */
    private fun safDisplayRoot(tree: Uri): String =
        SafPaths.resolveDisplayPath(ctx, tree)

    /** SAF 创建文件文档 → (docUri, 展示真实绝对路径)；失败 null */
    private fun safCreate(fileName: String, mime: String, sub: String): Pair<Uri, String>? {
        val tree = safTree() ?: return null
        val dir = safSubDir(tree, sub) ?: return null
        val docUri = runCatching {
            DocumentsContract.createDocument(ctx.contentResolver, dir, mime, fileName)
        }.getOrNull() ?: return null
        val display = SafPaths.fileDocIdToAbsolutePath(ctx, DocumentsContract.getDocumentId(docUri))
            ?: docUri.toString()
        return docUri to display
    }

    override fun screenshotsDir(): String =
        safTree()?.let { "${safDisplayRoot(it)}/${SafPaths.TOP_DIR}/${SafPaths.SUB_SCREENSHOTS}" }
            ?: if (q29()) "Pictures/Selkies"
            else legacyDir(Environment.DIRECTORY_PICTURES).absolutePath

    override fun recordingsDir(): String =
        safTree()?.let { "${safDisplayRoot(it)}/${SafPaths.TOP_DIR}/${SafPaths.SUB_RECORDINGS}" }
            ?: if (q29()) "Movies/Selkies"
            else legacyDir(Environment.DIRECTORY_MOVIES).absolutePath

    override fun downloadsDir(): String =
        safTree()?.let { "${safDisplayRoot(it)}/${SafPaths.TOP_DIR}/${SafPaths.SUB_DOWNLOADS}" }
            ?: if (q29()) "Download/Selkies"
            else legacyDir(Environment.DIRECTORY_DOWNLOADS).absolutePath

    override fun saveScreenshot(png: ByteArray, fileName: String): String? = runCatching {
        // v2.7.5：SAF 自定义目录优先
        safCreate(fileName, "image/png", SafPaths.SUB_SCREENSHOTS)?.let { (docUri, display) ->
            ctx.contentResolver.openOutputStream(docUri)?.use { it.write(png) } ?: return null
            return display
        }
        if (q29()) {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Selkies")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(png) }
            // 【v2.7.6】返回真实绝对路径而非 content:// URI（用户反馈「文件位置显示有问题」：
            //   保存引导弹窗/媒体库直接展示 content://media/external/images/media/71191 对用户
            //   不可认知也无法打开。与下载路径修复对齐：查 DATA 列，失败回退构造可读路径）
            mediaStoreRealPath(uri, "/storage/emulated/0/Pictures/Selkies/$fileName")
        } else {
            val dir = legacyDir(Environment.DIRECTORY_PICTURES)
            val f = uniqueFile(dir, fileName)
            f.writeBytes(png)
            mediaScan(f, "image/png")
            f.absolutePath
        }
    }.getOrNull()

    override fun saveDownload(fileName: String, bytes: ByteArray): String? = runCatching {
        // v2.7.5：SAF 自定义目录优先
        safCreate(fileName, mimeOf(fileName), SafPaths.SUB_DOWNLOADS)?.let { (docUri, display) ->
            ctx.contentResolver.openOutputStream(docUri)?.use { it.write(bytes) } ?: return null
            return display
        }
        if (q29()) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeOf(fileName))
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Selkies")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            // 【v2.7.6】真实绝对路径（同 saveScreenshot 修复）
            mediaStoreRealPath(uri, "/storage/emulated/0/Download/Selkies/$fileName")
        } else {
            val dir = legacyDir(Environment.DIRECTORY_DOWNLOADS)
            val f = uniqueFile(dir, fileName)
            f.writeBytes(bytes)
            mediaScan(f, mimeOf(fileName))
            f.absolutePath
        }
    }.getOrNull()

    override fun openDownloadStream(fileName: String): OutputStreamHandle? = runCatching {
        // v2.7.5：SAF 自定义目录优先（流式写入用户选定目录；返回真实绝对路径）
        safCreate(fileName, mimeOf(fileName), SafPaths.SUB_DOWNLOADS)?.let { (docUri, display) ->
            val os = ctx.contentResolver.openOutputStream(docUri) ?: return null
            return object : OutputStreamHandle {
                override val path: String = display
                override fun write(bytes: ByteArray, off: Int, len: Int) = os.write(bytes, off, len)
                override fun close() = os.close()
            }
        }
        if (q29()) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeOf(fileName))
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Selkies")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: return null
            val os = ctx.contentResolver.openOutputStream(uri) ?: return null
            // v2.7.4【真实路径修复】：content:// URI 对用户不可认知（「保存位置不是真实路径」根因）。
            // 查询 DATA 列拿到 /storage/emulated/0/Download/Selkies/xxx 真实绝对路径；
            // 查不到时回退构造 RELATIVE_PATH + 文件名的可读路径。
            val realPath = runCatching {
                ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: "/storage/emulated/0/Download/Selkies/$fileName"
            object : OutputStreamHandle {
                override val path: String = realPath
                override fun write(bytes: ByteArray, off: Int, len: Int) = os.write(bytes, off, len)
                override fun close() = os.close()
            }
        } else {
            val dir = legacyDir(Environment.DIRECTORY_DOWNLOADS)
            val f = uniqueFile(dir, fileName)
            val os = f.outputStream()
            object : OutputStreamHandle {
                override val path: String = f.absolutePath
                override fun write(bytes: ByteArray, off: Int, len: Int) = os.write(bytes, off, len)
                override fun close() {
                    os.close(); mediaScan(f, mimeOf(fileName))
                }
            }
        }
    }.getOrNull()

    override fun createRecordingFile(fileName: String): String? {
        // v2.7.5：SAF 自定义目录 → 复用器惰性创建文档（首帧才建文件）
        safTree()?.let { tree ->
            return "${SafMarker.PREFIX}${tree}|$fileName"
        }
        return "selkies-pending://$fileName"
    }

    override fun listMedia(): List<SavedMediaEntry> {
        val out = ArrayList<SavedMediaEntry>()
        runCatching {
            // v2.7.5：SAF 自定义目录优先（仅列出该目录下的媒体；不再扫 MediaStore）
            val tree = safTree()
            if (tree != null) {
                val top = safTopDir(tree)
                if (top != null) {
                    listOf(
                        SafPaths.SUB_SCREENSHOTS to "screenshot",
                        SafPaths.SUB_RECORDINGS to "recording",
                        SafPaths.SUB_DOWNLOADS to "download"
                    ).forEach { (sub, kind) ->
                        val dir = SafPaths.findChildDir(ctx, top, sub) ?: return@forEach
                        SafPaths.listFiles(ctx, dir).forEach { row ->
                            out.add(
                                SavedMediaEntry(
                                    path = DocumentsContract.buildDocumentUriUsingTree(tree, row.documentId).toString(),
                                    name = row.displayName,
                                    kind = kind,
                                    sizeBytes = row.sizeBytes,
                                    modifiedAt = row.modifiedAt
                                )
                            )
                        }
                    }
                }
                return out.sortedByDescending { it.modifiedAt }
            }
            if (q29()) {
                listOf(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "screenshot",
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "recording",
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI to "download"
                ).forEach { (collection, kind) ->
                    ctx.contentResolver.query(
                        collection,
                        arrayOf(
                            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
                            MediaStore.MediaColumns.RELATIVE_PATH
                        ),
                        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                        arrayOf("%Selkies%"),
                        "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                    )?.use { c ->
                        while (c.moveToNext()) {
                            out.add(
                                SavedMediaEntry(
                                    path = collection.buildUpon()
                                        .appendPath(c.getLong(0).toString()).build().toString(),
                                    name = c.getString(1) ?: "",
                                    kind = kind,
                                    sizeBytes = c.getLong(2),
                                    modifiedAt = c.getLong(3) * 1000L
                                )
                            )
                        }
                    }
                }
            } else {
                listOf(
                    Environment.DIRECTORY_PICTURES to "screenshot",
                    Environment.DIRECTORY_MOVIES to "recording",
                    Environment.DIRECTORY_DOWNLOADS to "download"
                ).forEach { (env, kind) ->
                    legacyDir(env).listFiles()?.forEach { f ->
                        out.add(
                            SavedMediaEntry(
                                path = f.absolutePath, name = f.name, kind = kind,
                                sizeBytes = f.length(), modifiedAt = f.lastModified()
                            )
                        )
                    }
                }
            }
        }
        return out.sortedByDescending { it.modifiedAt }
    }

    override fun deleteMedia(entry: SavedMediaEntry): Boolean = runCatching {
        if (entry.path.startsWith("content://")) {
            val uri = Uri.parse(entry.path)
            if (uri.authority == "media") {
                // MediaStore 集合 URI
                ctx.contentResolver.delete(uri, null, null) > 0
            } else {
                // v2.7.5：SAF 文档 URI（tree 文档）
                DocumentsContract.deleteDocument(ctx.contentResolver, uri)
            }
        } else {
            File(entry.path).delete()
        }
    }.getOrDefault(false)

    private fun legacyDir(envDir: String): File {
        val base = Environment.getExternalStoragePublicDirectory(envDir)
        val dir = File(base, "Selkies")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun uniqueFile(dir: File, name: String): File {
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        val ts = SimpleDateFormat("HHmmss", Locale.US).format(Date())
        return File(dir, "${stem}_$ts$ext")
    }

    private fun mediaScan(f: File, mime: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            intent.data = Uri.fromFile(f)
            ctx.sendBroadcast(intent)
        }
    }

    /**
     * 【v2.7.6】MediaStore URI → 真实绝对路径（用户可认知的 /storage/emulated/0/... 形态）。
     * 查询 DATA 列；查不到（部分 ROM 返回空）回退 [fallback]（按 RELATIVE_PATH 规则构造的可读路径）。
     * 修复：截图/录屏保存后弹窗与媒体库显示 content://media/external/images/media/71191。
     */
    private fun mediaStoreRealPath(uri: Uri, fallback: String): String {
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull() ?: fallback
    }

    private fun mimeOf(name: String): String = when {
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".mp4", true) -> "video/mp4"
        name.endsWith(".webm", true) -> "video/webm"
        name.endsWith(".mkv", true) -> "video/x-matroska"
        name.endsWith(".pdf", true) -> "application/pdf"
        name.endsWith(".zip", true) -> "application/zip"
        name.endsWith(".txt", true) -> "text/plain"
        else -> "application/octet-stream"
    }
}

/** v2.7.5：SAF 录屏标记（携带 treeUri，复用器惰性建文件时用） */
object SafMarker {
    const val PREFIX = "selkies-saf://"
    fun parse(outputPath: String): Pair<Uri, String>? {
        if (!outputPath.startsWith(PREFIX)) return null
        val rest = outputPath.removePrefix(PREFIX)
        val sep = rest.indexOf('|')
        if (sep <= 0) return null
        val tree = runCatching { Uri.parse(rest.substring(0, sep)) }.getOrNull() ?: return null
        val name = rest.substring(sep + 1)
        if (name.isBlank()) return null
        return tree to name
    }
}

/**
 * Android 流复用器（MediaMuxer）：
 * - H.264/H.265 → MP4（Annex-B→AVCC + SPS/PPS/VPS→csd，首关键帧就绪才建轨启动）
 * - VP8/VP9 → WebM（帧直通，无需 csd）
 * - Opus 音频轨（启动前有音频数据才建——避免空轨导致 stop 失败）
 * - JPEG 条带 → MediaCodec H.264 实时转码入轨（Surface 输入）
 */
class AndroidStreamMuxer(override val outputPath: String) : StreamMuxer {

    private var realMuxer: MediaMuxer? = null
    // v2.7.5：接口化真实保存路径（SAF 录屏 = 解析后的真实绝对路径；建文件前 = outputPath 占位）
    override var realPath: String = outputPath
        private set
    /** v2.7.5：创建的媒体文档（MediaStore 或 SAF）——空内容时清理用 */
    private var docUri: Uri? = null
    private var pfd: ParcelFileDescriptor? = null
    private var videoTrackIdx = -1
    private var audioTrackIdx = -1
    private var muxerStarted = false
    // 【v2.7.6】close 结果缓存（close 幂等语义：重复调用返回上次结果而非 muxerStarted 已被置 false）
    private var lastCloseOk = false
    private var containerMp4 = true

    // 视频 codec（1=h264 2=vp8 3=vp9 5=h265）
    private var videoCodec = 1
    private val isAnnexB: Boolean get() = videoCodec == 1 || videoCodec == 5

    // 缓冲（等首关键帧 csd）
    private val pendingVideo = ArrayList<Pair<ByteArray, Long>>()
    private val pendingAudio = ArrayList<Pair<ByteArray, Long>>()
    // v2.7.0：缓冲字节累计（防 OOM）
    private var pendingBytes = 0L
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var vps: ByteArray? = null
    private var gotKeyframe = false
    private var lastVideoPts = -1L
    private var lastAudioPts = -1L
    private var audioSamplesWritten = 0L
    private var closed = false
    private val lock = Object()

    // JPEG 转码器
    private var jpegEncoder: JpegH264Encoder? = null

    override fun addVideoTrack(codecId: Int, width: Int, height: Int): Boolean {
        synchronized(lock) {
            if (realMuxer != null || closed) return false
            videoCodec = if (codecId == 0) 1 else codecId
            containerMp4 = videoCodec == 1 || videoCodec == 5
            return true // 真正建轨在首关键帧（csd 就绪）
        }
    }

    override fun addAudioTrack(sampleRate: Int, channels: Int): Boolean {
        // 音频轨在建 muxer 启动时按 pendingAudio 是否非空决定（避免空轨）
        synchronized(lock) { return !closed && !muxerStarted }
    }

    override fun writeVideoFrame(data: ByteArray, keyframe: Boolean, ptsUs: Long): Boolean {
        synchronized(lock) {
            if (closed) return false
            if (realMuxer == null && !ensureMuxer()) return false

            if (isAnnexB) {
                val converted = annexToAvcc(data, videoCodec == 5) ?: return false
                if (converted.sps != null) sps = converted.sps
                if (converted.pps != null) pps = converted.pps
                if (converted.vps != null) vps = converted.vps
                if (!gotKeyframe) {
                    if (!keyframe) return true // 丢弃前导增量帧
                    if (sps == null || (videoCodec == 5 && vps == null) || (videoCodec == 1 && pps == null)) {
                        // 关键帧未带参数集：缓冲等下一个
                        // v2.7.0：帧数与字节双上限（防 OOM 闪退——旧版仅限 240 帧，高码率关键帧可累积 >100MB）
                        val frameBytes = converted.sample.size
                        if (pendingVideo.size < 48 && pendingBytes + frameBytes < 48L * 1024 * 1024) {
                            pendingVideo.add(converted.sample to ptsUs)
                            pendingBytes += frameBytes
                        }
                        return true
                    }
                    gotKeyframe = true
                    startMuxer()
                    // 冲刷缓冲
                    for ((s, p) in pendingVideo) writeSample(s, false, p)
                    pendingVideo.clear()
                    pendingBytes = 0L
                }
                return writeSample(converted.sample, keyframe, ptsUs)
            } else {
                // VP8/VP9：直通
                if (!gotKeyframe) {
                    gotKeyframe = true
                    startMuxer()
                }
                return writeSample(data, keyframe, ptsUs)
            }
        }
    }

    override fun writeAudioFrame(data: ByteArray, ptsUs: Long): Boolean {
        synchronized(lock) {
            if (closed) return false
            if (!muxerStarted) {
                // v2.7.0：帧数与字节双上限（防 OOM）
                if (pendingAudio.size < 600 && pendingBytes + data.size < 48L * 1024 * 1024) {
                    pendingAudio.add(data to ptsUs)
                    pendingBytes += data.size
                }
                return true
            }
            // 【v2.7.6】本地引用快照（close() 并发置空防护）
            val m = realMuxer ?: return false
            if (audioTrackIdx < 0) return false // 启动时无音频数据 → 不再补轨
            if (ptsUs <= lastAudioPts) return true
            val buf = ByteBuffer.allocate(data.size)
            buf.put(data); buf.flip()
            val info = MediaCodec.BufferInfo().apply { set(0, data.size, ptsUs, 0) }
            val ok = runCatching { m.writeSampleData(audioTrackIdx, buf, info) }.isSuccess
            if (ok) { lastAudioPts = ptsUs; audioSamplesWritten++ }
            return ok
        }
    }

    override fun writeJpegFrame(parts: List<Pair<Int, ByteArray>>, ptsUs: Long): Boolean {
        synchronized(lock) {
            if (closed) return false
            var enc = jpegEncoder
            if (enc == null) {
                videoCodec = 1
                containerMp4 = true
                enc = JpegH264Encoder(
                    onReady = { csd0, csd1 ->
                        // 编码器输出格式就绪：建 muxer + 视频轨
                        if (realMuxer == null && !ensureMuxer()) return@JpegH264Encoder false
                        if (videoTrackIdx < 0) {
                            sps = csd0; pps = csd1
                            gotKeyframe = true
                            startMuxer()
                        }
                        videoTrackIdx >= 0
                    },
                    onFrame = { sample, keyframe, pts ->
                        if (muxerStarted && videoTrackIdx >= 0) writeSample(sample, keyframe, pts) else true
                    }
                )
                jpegEncoder = enc
            }
            if (!enc.ready) {
                if (enc.prepare(parts) == null) return true // 无法初始化（拿不到尺寸）→ 丢弃但保持录制
            }
            return enc.submitFrame(parts, ptsUs)
        }
    }

    override fun close(): Boolean {
        synchronized(lock) {
            if (closed) return lastCloseOk
            closed = true
            // 【v2.7.6】录屏结束闪退根因修复（use-after-free 竞态）：
            //   旧版顺序 = jpegEncoder.release()（join 仅 800ms，超时即放弃）→ MediaMuxer.stop()/
            //   release() → 但编码器 drain 线程可能仍活着并调 writeSampleData() → 对已释放的
            //   native muxer 写入 = SIGSEGV 原生崩溃（Java runCatching 拦不住）。
            //   新顺序：①先置 muxerStarted=false（writeSample 立即拒新）②彻底等编码器线程退出
            //   ③才 stop/release muxer ④置空引用（漏网写入碰 null 检查而非悬垂 native 指针）。
            muxerStarted = false
            var ok = false
            if (realMuxer != null) {
                runCatching { jpegEncoder?.release() }
                runCatching {
                    realMuxer!!.stop()
                    ok = true
                }
                runCatching { realMuxer!!.release() }
                pfd?.close()
            } else {
                runCatching { jpegEncoder?.release() }
                runCatching { realMuxer?.release() }
                pfd?.close()
                // 未启动（无有效内容）：删除空条目
                // v2.7.5：统一按 docUri 清理（MediaStore=resolver.delete；SAF=deleteDocument；旧版文件路径=File.delete）
                val ctx0 = AndroidPlatform.appCtx()
                val u = docUri
                if (u != null && ctx0 != null) {
                    runCatching {
                        if (u.authority == "media") ctx0.contentResolver.delete(u, null, null)
                        else DocumentsContract.deleteDocument(ctx0.contentResolver, u)
                    }
                } else if (realPath != outputPath) {
                    runCatching { File(realPath).delete() }
                }
            }
            // 【v2.7.6】置空引用：任何残留写入（编码器线程漏网帧）碰 null 检查而非悬垂 native 对象
            realMuxer = null
            videoTrackIdx = -1
            audioTrackIdx = -1
            lastCloseOk = ok
            return ok
        }
    }

    private fun ensureMuxer(): Boolean {
        if (realMuxer != null) return true
        val ctx = AndroidPlatform.appCtx() ?: return false
        // v2.7.5：SAF 自定义存储目录——{所选根}/Selkies/Recordings 下建文档，FD 写入
        SafMarker.parse(outputPath)?.let { (tree, name) ->
            val baseName = name.substringBeforeLast('.', name)
            val fileName = baseName + if (containerMp4) ".mp4" else ".webm"
            return try {
                val root = SafPaths.treeRootDocUri(tree)
                val top = SafPaths.findOrCreateDir(ctx, root, SafPaths.TOP_DIR)
                    ?: return false
                val dir = SafPaths.findOrCreateDir(ctx, top, SafPaths.SUB_RECORDINGS)
                    ?: return false
                val uri = DocumentsContract.createDocument(
                    ctx.contentResolver, dir,
                    if (containerMp4) "video/mp4" else "video/webm", fileName
                ) ?: return false
                docUri = uri
                val fd = ctx.contentResolver.openFileDescriptor(uri, "rw") ?: return false
                pfd = fd
                realPath = SafPaths.fileDocIdToAbsolutePath(ctx, DocumentsContract.getDocumentId(uri))
                    ?: uri.toString()
                realMuxer = MediaMuxer(
                    fd.fileDescriptor,
                    if (containerMp4) MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                    else MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                )
                true
            } catch (_: Throwable) {
                runCatching { pfd?.close() }
                pfd = null
                runCatching { docUri?.let { DocumentsContract.deleteDocument(ctx.contentResolver, it) } }
                docUri = null
                false
            }
        }
        val name = outputPath.removePrefix("selkies-pending://")
        val baseName = name.substringBeforeLast('.', name)
        val fileName = baseName + if (containerMp4) ".mp4" else ".webm"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val cv = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, if (containerMp4) "video/mp4" else "video/webm")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Selkies")
                }
                val uri = ctx.contentResolver.insert(collection, cv) ?: return false
                docUri = uri
                // v2.7.5：MediaStore 录屏也解析真实绝对路径（对齐下载修复）
                // 【v2.7.6】回退构造可读路径而非 uri.toString()（content:// 对用户不可认知）
                realPath = runCatching {
                    ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                }.getOrNull() ?: "/storage/emulated/0/Movies/Selkies/$fileName"
                pfd = ctx.contentResolver.openFileDescriptor(uri, "rw")
                realMuxer = MediaMuxer(
                    pfd!!.fileDescriptor,
                    if (containerMp4) MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                    else MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                )
            } else {
                val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val dir = File(movies, "Selkies")
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, fileName)
                realPath = f.absolutePath
                realMuxer = MediaMuxer(
                    f.absolutePath,
                    if (containerMp4) MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                    else MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                )
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun startMuxer() {
        if (muxerStarted || realMuxer == null || videoTrackIdx >= 0) return
        val fmt = MediaFormat.createVideoFormat(
            if (videoCodec == 5) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC,
            16, 16
        )
        if (videoCodec == 5) {
            val merged = mergeHevcCsd(vps, sps, pps)
            if (merged != null) {
                val b = ByteBuffer.allocate(merged.size); b.put(merged); b.flip()
                fmt.setByteBuffer("csd-0", b)
            }
        } else {
            sps?.let { s -> val b = ByteBuffer.allocate(s.size); b.put(s); b.flip(); fmt.setByteBuffer("csd-0", b) }
            pps?.let { p -> val b = ByteBuffer.allocate(p.size); b.put(p); b.flip(); fmt.setByteBuffer("csd-1", b) }
        }
        videoTrackIdx = runCatching { realMuxer!!.addTrack(fmt) }.getOrDefault(-1)
        if (videoTrackIdx < 0) return
        // 有真实音频数据才建音频轨（避免空轨）
        if (pendingAudio.isNotEmpty()) {
            runCatching {
                val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 2)
                af.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                audioTrackIdx = realMuxer!!.addTrack(af)
            }
        }
        realMuxer!!.start()
        muxerStarted = true
        // 冲刷音频缓冲
        if (audioTrackIdx >= 0) {
            for ((d, p) in pendingAudio) {
                val buf = ByteBuffer.allocate(d.size); buf.put(d); buf.flip()
                val info = MediaCodec.BufferInfo().apply { set(0, d.size, p, 0) }
                runCatching { realMuxer!!.writeSampleData(audioTrackIdx, buf, info) }
                lastAudioPts = p
            }
            audioSamplesWritten += pendingAudio.size
        }
        pendingAudio.clear()
        pendingBytes = 0L
    }

    private fun writeSample(sample: ByteArray, keyframe: Boolean, ptsUs: Long): Boolean {
        // 【v2.7.6】本地引用快照（close() 可能并发置空 realMuxer）+ muxerStarted 拒新
        val m = realMuxer ?: return false
        if (!muxerStarted || videoTrackIdx < 0) return false
        if (ptsUs <= lastVideoPts) return true
        val buf = ByteBuffer.allocate(sample.size)
        buf.put(sample); buf.flip()
        val info = MediaCodec.BufferInfo().apply {
            set(0, sample.size, ptsUs, if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        val ok = runCatching { m.writeSampleData(videoTrackIdx, buf, info) }.isSuccess
        if (ok) lastVideoPts = ptsUs
        return ok
    }

    private fun mergeHevcCsd(vps: ByteArray?, sps: ByteArray?, pps: ByteArray?): ByteArray? {
        if (sps == null) return null
        val parts = listOfNotNull(vps, sps, pps)
        var size = 0
        parts.forEach { size += it.size }
        val out = ByteArray(size)
        var off = 0
        parts.forEach { p ->
            System.arraycopy(p, 0, out, off, p.size)
            off += p.size
        }
        return out
    }

    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        System.arraycopy("OpusHead".toByteArray(Charsets.US_ASCII), 0, head, 0, 8)
        head[8] = 1; head[9] = 2
        head[10] = 0x38; head[11] = 0x01
        head[12] = 0x80.toByte(); head[13] = 0xBB.toByte(); head[14] = 0; head[15] = 0
        head[16] = 0; head[17] = 0; head[18] = 0
        return head
    }
}

/** Annex-B → AVCC 结果 */
private class AvccResult(
    val sample: ByteArray,
    val sps: ByteArray?,
    val pps: ByteArray?,
    val vps: ByteArray?
)

/** H.264/H.265 Annex-B → AVCC（4 字节长度前缀）；SPS/PPS/VPS 提取为带前缀 csd */
private fun annexToAvcc(data: ByteArray, hevc: Boolean): AvccResult? {
    if (data.size < 6) return null
    val starts = ArrayList<Int>()
    var i = 0
    while (i < data.size - 3) {
        if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
            if (data[i + 2] == 1.toByte()) {
                starts.add(i); i += 3; continue
            } else if (i < data.size - 4 && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                starts.add(i); i += 4; continue
            }
        }
        i++
    }
    if (starts.isEmpty()) return null
    var sps: ByteArray? = null
    var pps: ByteArray? = null
    var vps: ByteArray? = null
    val nals = ArrayList<ByteArray>()
    var total = 0
    for ((idx, s) in starts.withIndex()) {
        val bodyStart = when {
            s + 3 < data.size && data[s + 2] == 1.toByte() -> s + 3
            else -> s + 4
        }
        val bodyEnd = if (idx + 1 < starts.size) starts[idx + 1] else data.size
        if (bodyEnd <= bodyStart || bodyEnd - bodyStart < 2) continue
        val len = bodyEnd - bodyStart
        val prefixed = ByteArray(4 + len)
        prefixed[0] = ((len ushr 24) and 0xff).toByte()
        prefixed[1] = ((len ushr 16) and 0xff).toByte()
        prefixed[2] = ((len ushr 8) and 0xff).toByte()
        prefixed[3] = (len and 0xff).toByte()
        System.arraycopy(data, bodyStart, prefixed, 4, len)
        val h264Type = data[bodyStart].toInt() and 0x1f
        val h265Type = (data[bodyStart].toInt() and 0x7e) shr 1
        if (hevc) {
            when (h265Type) {
                32 -> vps = prefixed
                33 -> sps = prefixed
                34 -> pps = prefixed
            }
        } else {
            when (h264Type) {
                7 -> sps = prefixed
                8 -> pps = prefixed
            }
        }
        nals.add(prefixed)
        total += prefixed.size
    }
    if (nals.isEmpty()) return null
    val sample = ByteArray(total)
    var off = 0
    for (nal in nals) {
        System.arraycopy(nal, 0, sample, off, nal.size)
        off += nal.size
    }
    return AvccResult(sample, sps, pps, vps)
}

/**
 * JPEG 条带 → MediaCodec H.264 实时转码（Surface 输入；帧节奏 = WS 到达节奏）。
 */
private class JpegH264Encoder(
    private val onReady: (csd0: ByteArray, csd1: ByteArray?) -> Boolean,
    private val onFrame: (sample: ByteArray, keyframe: Boolean, ptsUs: Long) -> Boolean
) {
    @Volatile
    var ready = false
        private set

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private var canvasBmp: Bitmap? = null

    /** 用首帧条带探测尺寸并初始化；返回非 null = 初始化成功 */
    fun prepare(parts: List<Pair<Int, ByteArray>>): Int? {
        for ((_, jpeg) in parts) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                val w = (opts.outWidth + 1) / 2 * 2
                val h = (opts.outHeight + 1) / 2 * 2
                if (initCodec(w, h)) return w
            }
        }
        return null
    }

    private fun initCodec(w: Int, h: Int): Boolean {
        return runCatching {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = c.createInputSurface()
            c.start()
            codec = c
            inputSurface = surface
            running.set(true)
            thread = Thread(this::drainLoop, "selkies-jpeg-enc").apply {
                isDaemon = true; start()
            }
            true
        }.getOrDefault(false)
    }

    fun submitFrame(parts: List<Pair<Int, ByteArray>>, ptsUs: Long): Boolean {
        val surface = inputSurface ?: return false
        var w = 0
        var h = 0
        val stripes = ArrayList<Pair<Int, Bitmap>>()
        for ((y, jpeg) in parts) {
            val bmp = runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }.getOrNull() ?: continue
            stripes.add(y to bmp)
            w = maxOf(w, bmp.width)
            h = maxOf(h, y + bmp.height)
        }
        if (stripes.isEmpty()) return false
        var bmp = canvasBmp
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            canvasBmp = bmp
        }
        val canvas = Canvas(bmp)
        for ((y, s) in stripes) canvas.drawBitmap(s, 0f, y.toFloat(), null)
        stripes.forEach { it.second.recycle() }
        // v2.7.4【录屏闪退根因修复】：lockHardwareCanvas 在非渲染线程对 MediaCodec inputSurface
        // 调用在大量设备（Android 11+，尤其部分 OEM）上 native crash（EXCEPTION_ACCESS_VIOLATION）。
        // 用户实测「点击录屏后过 2 秒闪退」= 首关键帧到达 → JPEG 转码启动 → hardware canvas 崩溃。
        // 改用 lockCanvas（CPU 软件画布）：MediaCodec inputSurface 的传统稳定路径，兼容性好。
        runCatching {
            val c = surface.lockCanvas(null)
            c.drawBitmap(bmp, 0f, 0f, null)
            surface.unlockCanvasAndPost(c)
        }.onFailure { return false }
        return true
    }

    private fun drainLoop() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            try {
                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val fmt = c.outputFormat
                    val csd0 = fmt.getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
                    val csd1 = fmt.getByteBuffer("csd-1")?.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
                    if (csd0 != null) ready = onReady(csd0, csd1)
                } else if (outIdx >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        c.releaseOutputBuffer(outIdx, false)
                        continue
                    }
                    val buf = c.getOutputBuffer(outIdx)
                    if (buf != null && ready && info.size > 0) {
                        val sample = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.get(sample)
                        onFrame(
                            sample,
                            info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0,
                            info.presentationTimeUs
                        )
                    }
                    c.releaseOutputBuffer(outIdx, false)
                }
            } catch (_: Throwable) {
                break
            }
        }
    }

    fun release() {
        // 【v2.7.6】彻底等 drain 线程退出（旧版 join 仅 800ms——超时后 muxer 被释放而线程
        //   还在跑 = 录屏结束闪退根因；native writeSampleData 悬垂指针无法被 Java 捕获）
        running.set(false)
        runCatching { codec?.signalEndOfInputStream() }
        val t = thread
        if (t != null && t.isAlive) {
            runCatching { t.join(2500) }
            if (t.isAlive) {
                // 二次等待：编码器清尾缓冲可能慢；期间 writeSample 已被 muxerStarted=false 拦新
                runCatching { t.join(1500) }
            }
        }
        runCatching { inputSurface?.release() }
        runCatching { codec?.stop(); codec?.release() }
        codec = null
        inputSurface = null
        thread = null
    }
}

/**
 * 录屏前台服务：保活进程 → WS 不断 → 后台持续录；
 * 用户划掉任务/系统回收 → onDestroy/onTaskRemoved → 自动保存（「杀进程前先保存」）。
 */
class RecordingService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "selkies:recording").apply {
                setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L)
            }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val channelId = "selkies_recording"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "录屏进行中", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Selkies 录屏进行中")
            .setContentText("正在录制远程桌面（离开应用也会继续）")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }.onFailure {
                runCatching { startForeground(NOTIF_ID, notification) }
            }
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        wakeLock?.release()
        com.selkies.client.core.RecordingShutdownHook.autosave()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        com.selkies.client.core.RecordingShutdownHook.autosave()
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        const val NOTIF_ID = 0x5e1c
        const val ACTION_STOP = "stop"

        fun start(ctx: Context) {
            val i = Intent(ctx, RecordingService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RecordingService::class.java))
        }
    }
}
