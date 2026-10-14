package com.selkies.client.platform

import com.selkies.client.model.SavedMediaEntry
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.javacpp.BytePointer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 桌面媒体存储（v2.5.0）：
 * 截图 → ~/Pictures/Selkies；录屏 → ~/Videos/Selkies；下载 → ~/Downloads/Selkies
 * （settings.storageRoot 非空时以它为根：<root>/Screenshots|Recordings|Downloads）
 */
class DesktopMediaStoreBridge(private val rootProvider: () -> String) : MediaStoreBridge {

    private fun root(): File {
        val custom = rootProvider().trim()
        return if (custom.isNotEmpty()) File(custom) else File(System.getProperty("user.home"), "Selkies")
    }

    private fun dir(sub: String): File = File(root(), sub).apply { if (!exists()) mkdirs() }

    override fun screenshotsDir(): String = dir("Screenshots").absolutePath
    override fun recordingsDir(): String = dir("Recordings").absolutePath
    override fun downloadsDir(): String = dir("Downloads").absolutePath

    override fun saveScreenshot(png: ByteArray, fileName: String): String? = runCatching {
        val f = uniqueFile(dir("Screenshots"), fileName)
        f.writeBytes(png)
        f.absolutePath
    }.getOrNull()

    override fun saveDownload(fileName: String, bytes: ByteArray): String? = runCatching {
        val f = uniqueFile(dir("Downloads"), fileName)
        f.writeBytes(bytes)
        f.absolutePath
    }.getOrNull()

    override fun openDownloadStream(fileName: String): OutputStreamHandle? = runCatching {
        val f = uniqueFile(dir("Downloads"), fileName)
        val os = f.outputStream().buffered()
        object : OutputStreamHandle {
            override val path: String = f.absolutePath
            override fun write(bytes: ByteArray, off: Int, len: Int) = os.write(bytes, off, len)
            override fun close() = os.close()
        }
    }.getOrNull()

    override fun createRecordingFile(fileName: String): String? {
        val base = fileName.substringBeforeLast('.', fileName)
        return File(dir("Recordings"), "$base.mkv").absolutePath
    }

    override fun listMedia(): List<SavedMediaEntry> {
        val out = ArrayList<SavedMediaEntry>()
        val root = root()
        listOf("Screenshots" to "screenshot", "Recordings" to "recording", "Downloads" to "download")
            .forEach { (sub, kind) ->
                val d = File(root, sub)
                if (d.isDirectory) {
                    d.listFiles()?.forEach { f ->
                        if (f.isFile) {
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

    override fun deleteMedia(entry: SavedMediaEntry): Boolean =
        runCatching { File(entry.path).delete() }.getOrDefault(false)

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
}

/**
 * 桌面流复用器（FFmpeg avformat → MKV）：
 * - H.264/H.265/VP8/VP9：Matroska 原生接受 Annex-B/裸帧 → **零转码直通**（画质无损、CPU≈0）
 * - Opus 音频：extradata=OpusHead
 * - JPEG 条带：MJPEG 直通（无需转码，画质无损）
 */
class DesktopStreamMuxer(override val outputPath: String) : StreamMuxer {

    private var fmtCtx: AVFormatContext? = null
    private var videoStream: AVStream? = null
    private var audioStream: AVStream? = null
    private var headerWritten = false
    /** ⚠️ write_header 失败后置位：半初始化的 muxer 重入 write_header 会段错误，绝不再试 */
    private var headerFailed = false
    private var videoCodec = 1 // 1=h264 2=vp8 3=vp9 5=h265；0→MJPEG
    private var lastVideoPts = -1L
    private var lastAudioPts = -1L
    private var closed = false
    private val lock = Object()

    /** H.264/H.265 需要 extradata（参数集）才能写头；等待携带参数集的关键帧 */
    private fun videoHeaderReady(): Boolean {
        if (videoCodec != 1 && videoCodec != 5) return true
        return (videoStream?.codecpar()?.extradata_size() ?: 0) > 0
    }

    override fun addVideoTrack(codecId: Int, width: Int, height: Int): Boolean {
        synchronized(lock) {
            if (fmtCtx != null || closed) return false
            videoCodec = codecId
            return ensureContext()
        }
    }

    override fun addAudioTrack(sampleRate: Int, channels: Int): Boolean {
        synchronized(lock) {
            if (closed || fmtCtx == null || audioStream != null || headerWritten) return false
            return try {
                val st = avformat.avformat_new_stream(fmtCtx, null) ?: return false
                st.codecpar().codec_type(avutil.AVMEDIA_TYPE_AUDIO)
                st.codecpar().codec_id(avcodec.AV_CODEC_ID_OPUS)
                st.codecpar().sample_rate(sampleRate)
                // FFmpeg 5.1+：channels → ch_layout
                st.codecpar().ch_layout().nb_channels(channels)
                st.codecpar().format(avutil.AV_SAMPLE_FMT_S16)
                // OpusHead extradata
                val head = opusHead(channels)
                val ed = BytePointer(avutil.av_malloc(head.size.toLong())).capacity(head.size.toLong())
                ed.position(0).put(head, 0, head.size)
                st.codecpar().extradata(ed)
                st.codecpar().extradata_size(head.size)
                st.time_base().num(1); st.time_base().den(1_000_000)
                audioStream = st
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    override fun writeVideoFrame(data: ByteArray, keyframe: Boolean, ptsUs: Long): Boolean {
        synchronized(lock) {
            if (closed || fmtCtx == null || headerFailed) return false
            if (!headerWritten) {
                // ⚠️【录屏 SIGSEGV 根因链】
                //  ① Matroska 的 H.264/H.265 无参数集 extradata 时 avformat_write_header 返回 INVALIDDATA；
                //  ② 非关键帧先到（无 SPS/PPS）→ 失败；等关键帧到来后二次 write_header →
                //     半初始化 muxer 重入 → __libc_free 段错误（hs_err 实录）。
                //  对策：等待携带参数集的帧才写头；失败后绝不再试。
                if (videoCodec == 1 || videoCodec == 5) {
                    ensureExtradataFromKeyframe(data)
                    if (!videoHeaderReady()) {
                        return false // GOP 内帧丢弃（等待关键帧参数集），不计入已写帧数
                    }
                }
                if (!writeHeader()) {
                    headerFailed = true
                    return false
                }
            }
            if (ptsUs <= lastVideoPts) return true
            val ok = writePacket(videoStream!!, data, keyframe, ptsUs)
            if (ok) lastVideoPts = ptsUs
            return ok
        }
    }

    override fun writeAudioFrame(data: ByteArray, ptsUs: Long): Boolean {
        synchronized(lock) {
            if (closed || fmtCtx == null || audioStream == null || headerFailed) return false
            if (!headerWritten) {
                // 视频头未就绪（等待参数集）期间丢弃音频，绝不提前写头（防二次 write_header 重入）
                if (!videoHeaderReady()) return false
                if (!writeHeader()) {
                    headerFailed = true
                    return false
                }
            }
            if (ptsUs <= lastAudioPts) return true
            val ok = writePacket(audioStream!!, data, false, ptsUs)
            if (ok) lastAudioPts = ptsUs
            return ok
        }
    }

    override fun writeJpegFrame(parts: List<Pair<Int, ByteArray>>, ptsUs: Long): Boolean {
        synchronized(lock) {
            if (closed) return false
            if (videoCodec != 0) {
                // JPEG 模式：MJPEG 直通（每包=完整帧）
                videoCodec = 0
                if (fmtCtx != null) return false // 已按其他编码器初始化
                if (!ensureContext()) return false
            }
            if (fmtCtx == null) return false
            if (!headerWritten && !writeHeader()) return false
            if (ptsUs <= lastVideoPts) return true
            // 条带合成完整帧 → 单包 MJPEG（画质无损）
            val jpeg = compositeJpeg(parts) ?: return true
            val ok = writePacket(videoStream!!, jpeg, true, ptsUs)
            if (ok) lastVideoPts = ptsUs
            return ok
        }
    }

    /** 多条带 JPEG → 单张完整 JPEG（ImageIO 解码+拼接+重编码） */
    private fun compositeJpeg(parts: List<Pair<Int, ByteArray>>): ByteArray? {
        return runCatching {
            val stripes = ArrayList<Pair<Int, java.awt.image.BufferedImage>>()
            var w = 0
            var h = 0
            for ((y, bytes) in parts) {
                val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)) ?: continue
                stripes.add(y to img)
                w = maxOf(w, img.width)
                h = maxOf(h, y + img.height)
            }
            if (stripes.isEmpty() || w <= 0 || h <= 0) return null
            val canvas = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_3BYTE_BGR)
            val g = canvas.graphics
            for ((y, img) in stripes) g.drawImage(img, 0, y, null)
            g.dispose()
            val out = java.io.ByteArrayOutputStream()
            // 质量 0.92 的 JPEG 重编码
            val writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpg").next()
            val params = writer.defaultWriteParam
            params.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
            params.compressionQuality = 0.92f
            writer.output = javax.imageio.stream.MemoryCacheImageOutputStream(out)
            writer.write(null, javax.imageio.IIOImage(canvas, null, null), params)
            writer.dispose()
            out.toByteArray()
        }.getOrNull()
    }

    override fun close(): Boolean {
        synchronized(lock) {
            if (closed) return headerWritten
            closed = true
            val ctx = fmtCtx ?: return false
            var ok = false
            runCatching {
                if (headerWritten) {
                    avformat.av_write_trailer(ctx)
                    ok = true
                }
                // ⚠️ 同 avio_open：先取值关闭，再显式置空字段（防 avformat_free_context 二次关闭）
                val pb = ctx.pb()
                if (pb != null && !pb.isNull) {
                    avformat.avio_close(pb)
                    ctx.pb(AVIOContext(null))
                }
                avformat.avformat_free_context(ctx)
            }.onFailure { it.printStackTrace() }
            if (!headerWritten) {
                // 空内容：删除文件
                runCatching { File(outputPath).delete() }
            }
            fmtCtx = null
            return ok
        }
    }

    private fun ensureContext(): Boolean {
        if (fmtCtx != null) return true
        return try {
            avformat.avformat_network_init()
            val ctx = AVFormatContext(null)
            if (avformat.avformat_alloc_output_context2(ctx, null, "matroska", outputPath) < 0 || ctx.isNull) {
                return false
            }
            val st = avformat.avformat_new_stream(ctx, null) ?: return false
            val codecId = when (videoCodec) {
                2 -> avcodec.AV_CODEC_ID_VP8
                3 -> avcodec.AV_CODEC_ID_VP9
                5 -> avcodec.AV_CODEC_ID_HEVC
                0 -> avcodec.AV_CODEC_ID_MJPEG
                else -> avcodec.AV_CODEC_ID_H264
            }
            st.codecpar().codec_type(avutil.AVMEDIA_TYPE_VIDEO)
            st.codecpar().codec_id(codecId)
            st.codecpar().width(1280); st.codecpar().height(720)
            st.time_base().num(1); st.time_base().den(1_000_000)
            videoStream = st
            fmtCtx = ctx
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun writeHeader(): Boolean {
        val ctx = fmtCtx ?: return false
        return try {
            // ⚠️【SIGSEGV 修复】avio_open 是 @ByPtrPtr：必须用局部包装接收回写、再显式赋回字段。
            //    旧版直接传 ctx.pb()（getter 返回临时包装）——回写落在临时对象上，ctx->pb 恒 NULL，
            //    后续写入段错误（hs_err 实录：SIGSEGV in libavformat = 桌面录屏崩溃根因之一）。
            val ofmt = ctx.oformat()
            if (ofmt != null && !ofmt.isNull && (ofmt.flags() and avformat.AVFMT_NOFILE) == 0) {
                val pb = AVIOContext(null)
                if (avformat.avio_open(pb, outputPath, avformat.AVIO_FLAG_WRITE) < 0) return false
                ctx.pb(pb)
            }
            if (avformat.avformat_write_header(ctx, null as AVDictionary?) < 0) return false
            headerWritten = true
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 从首帧 Annex-B 流提取参数集作 extradata（H.264: SPS+PPS；H.265: VPS+SPS+PPS）
     *  无参数集时退化为直接使用整帧（容错——至少不崩溃） */
    private fun ensureExtradataFromKeyframe(data: ByteArray) {
        val st = videoStream ?: return
        if (st.codecpar().extradata_size() > 0) return
        val wanted: Set<Int> = if (videoCodec == 5) setOf(32, 33, 34) else setOf(7, 8)
        val annexb = extractParameterSets(data, wanted) ?: return
        val ed = BytePointer(avutil.av_malloc(annexb.size.toLong())).capacity(annexb.size.toLong())
        ed.position(0).put(annexb, 0, annexb.size)
        st.codecpar().extradata(ed)
        st.codecpar().extradata_size(annexb.size)
    }

    /** Annex-B 扫描指定 NAL 类型的参数集序列（含起始码） */
    private fun extractParameterSets(data: ByteArray, wanted: Set<Int>): ByteArray? {
        val nals = ArrayList<Pair<Int, Int>>() // (start, end)
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                val start = i + 3
                var j = start
                while (j + 3 < data.size) {
                    if (data[j] == 0.toByte() && data[j + 1] == 0.toByte() && data[j + 2] == 1.toByte()) break
                    j++
                }
                nals.add(start to j)
                i = j
            } else i++
        }
        val picked = nals.filter { (s, _) ->
            val t = if (wanted.first() >= 32) {
                (data[s].toInt() and 0xff) shr 1 and 0x3f   // HEVC：2 字节头
            } else {
                data[s].toInt() and 0x1f                      // H.264：1 字节头
            }
            t in wanted
        }
        if (picked.isEmpty()) return null
        val from = picked.first().first - 4
        val to = picked.last().second
        return data.copyOfRange(from.coerceAtLeast(0), to.coerceAtMost(data.size))
    }
    private fun writePacket(st: AVStream, sample: ByteArray, keyframe: Boolean, ptsUs: Long): Boolean {
        return try {
            val pkt = AVPacket()
            avcodec.av_new_packet(pkt, sample.size)
            pkt.data().position(0).put(sample, 0, sample.size)
            pkt.stream_index(st.index())
            // ⚠️【时长修复】avformat_write_header 后 muxer 会改写 stream time_base（mkv=1/1000 毫秒精度）——
            //    必须把微秒 pts 换算到实际 time_base，否则回放时长膨胀 1000 倍
            val num = st.time_base().num().toLong()
            val den = st.time_base().den().toLong()
            val pts = if (den > 0 && num > 0) ptsUs * den / (1_000_000L * num) else ptsUs
            pkt.pts(pts)
            pkt.dts(pts)
            pkt.duration(0)
            if (keyframe) pkt.flags(pkt.flags() or avcodec.AV_PKT_FLAG_KEY)
            avformat.av_interleaved_write_frame(fmtCtx, pkt) >= 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun opusHead(ch: Int): ByteArray {
        val head = ByteArray(19)
        System.arraycopy("OpusHead".toByteArray(Charsets.US_ASCII), 0, head, 0, 8)
        head[8] = 1; head[9] = ch.coerceIn(1, 2).toByte()
        head[10] = 0x38; head[11] = 0x01
        head[12] = 0x80.toByte(); head[13] = 0xBB.toByte(); head[14] = 0; head[15] = 0
        head[16] = 0; head[17] = 0; head[18] = 0
        return head
    }
}
