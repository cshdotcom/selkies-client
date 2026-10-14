package com.selkies.client.audio

import com.selkies.client.platform.AudioPlayer
import com.selkies.client.platform.MicCapture
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_OPUS
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.javacpp.BytePointer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * 桌面音频播放：FFmpeg Opus 解码 → 软件 float→s16 → 多路 SourceDataLine
 * 【v2.6.0】
 *  - 多输出设备（多选）：每设备一条 SourceDataLine（各自 Mixer），独立音量（PCM 增益）
 *  - 设备打开失败：回退默认设备 + onError 反馈（旧版静默失败 = "远程音频不播放"的候选根因之一）
 *  - 写入异常：剔除该路并通知；全部失败时定时重试重建
 */
class DesktopAudioPlayer : AudioPlayer {

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private val pending = ConcurrentLinkedQueue<ByteArray>()

    override var onError: ((String) -> Unit)? = null

    /** 期望输出（deviceId → 0..1 增益）；空 = 默认设备 */
    @Volatile
    private var wantedOutputs: Map<String, Float> = emptyMap()

    // 解码线程独占
    private var codecCtx: AVCodecContext? = null
    private var packet: AVPacket? = null
    private var frame: AVFrame? = null

    private class Out(val deviceId: String, val line: SourceDataLine, @Volatile var gain: Float)

    /** 当前活动输出（解码线程独占；wantedOutputs 变化时重建） */
    private val outs = ArrayList<Out>()
    private var outsSig = ""

    override val bufferLevelMs: Int get() = pending.size * 20

    override fun start(sampleRate: Int, channels: Int) {
        if (running) return
        running = true
        thread = Thread({ loop(sampleRate) }, "selkies-audio-play").apply {
            isDaemon = true
            start()
        }
    }

    override fun playOpus(opus: ByteArray) {
        if (!running) return
        if (pending.size > 96) pending.poll()
        pending.offer(opus)
    }

    override fun onQuiet() {}

    @Volatile
    private var volume = 1.0f

    override fun setVolume(volume: Float) {
        this.volume = volume
    }

    override fun setOutputDevices(outputs: Map<String, Float>) {
        wantedOutputs = outputs
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        releaseAll()
        pending.clear()
    }

    private fun releaseAll() {
        runCatching { outs.forEach { runCatching { it.line.stop(); it.line.close() } } }
        outs.clear()
        // ⚠️【double-free 修复】JavaCPP 分配的对象（avcodec_alloc_context3 / AVPacket() / AVFrame()）
        //    都已自动挂接释放器：手动调 C 释放函数 + GC 释放器 = 双重释放 → "free(): invalid pointer" 崩溃。
        //    必须用 Pointer.deallocate()（幂等，只释放一次）。
        runCatching { codecCtx?.deallocate() }
        runCatching { packet?.deallocate() }
        runCatching { frame?.deallocate() }
        codecCtx = null
        packet = null
        frame = null
    }

    /** 打开一路输出：mixer:N → 第 N 个 Mixer；null/其他 → 系统默认 */
    private fun openLine(deviceId: String?, format: AudioFormat, info: DataLine.Info): SourceDataLine? {
        return runCatching {
            val l: SourceDataLine = if (deviceId == null || !deviceId.startsWith("mixer:")) {
                AudioSystem.getSourceDataLine(format)
            } else {
                val idx = deviceId.removePrefix("mixer:").toIntOrNull()
                val mixerInfo = idx?.let { AudioSystem.getMixerInfo().getOrNull(it) }
                val mixer = mixerInfo?.let { AudioSystem.getMixer(it) }
                if (mixer?.isLineSupported(info) == true) mixer.getLine(info) as SourceDataLine
                else AudioSystem.getSourceDataLine(format)
            }
            l.open(format, (format.sampleRate / 10).toInt() * 4)
            l.start()
            l
        }.getOrNull()
    }

    /** 按期望输出重建活动线路；返回是否有可用路 */
    private fun rebuildOuts(sampleRate: Int, channels: Int): Boolean {
        outs.forEach { runCatching { it.line.stop(); it.line.close() } }
        outs.clear()
        val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val wanted = wantedOutputs
        if (wanted.isEmpty()) {
            val l = openLine(null, format, info)
            if (l != null) outs.add(Out("", l, 1f))
            else onError?.invoke("无法打开默认音频输出设备（${format.sampleRate}Hz ${channels}ch）——将自动重试")
        } else {
            for ((id, gain) in wanted) {
                val l = openLine(id, format, info)
                if (l != null) {
                    outs.add(Out(id, l, gain))
                } else {
                    onError?.invoke("音频输出设备不可用：$id（已跳过该设备）")
                }
            }
            // 全部失败 → 回退默认
            if (outs.isEmpty()) {
                val l = openLine(null, format, info)
                if (l != null) outs.add(Out("", l, 1f))
            }
        }
        return outs.isNotEmpty()
    }

    private fun loop(sampleRate: Int) {
        runCatching {
            val codec = avcodec.avcodec_find_decoder(AV_CODEC_ID_OPUS)
                ?: throw IllegalStateException("未找到 Opus 解码器")
            // ⚠️【SIGSEGV 修复】必须用 avcodec_alloc_context3 分配上下文；
            //    AVCodecContext(codec) 是 Pointer 包装构造器，会越界写直接段错误
            val ctx = AVCodecContext(avcodec.avcodec_alloc_context3(codec))
            ctx.sample_rate(sampleRate)
            if (avcodec.avcodec_open2(ctx, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) < 0) {
                throw IllegalStateException("Opus 解码器初始化失败")
            }
            codecCtx = ctx
            packet = AVPacket()
            frame = AVFrame()

            val pkt = packet!!
            val fr = frame!!
            var reopenAt = 0L

            while (running) {
                val opus = pending.poll()
                if (opus != null) {
                    avcodec.av_packet_unref(pkt)
                    avcodec.av_new_packet(pkt, opus.size)
                    // ⚠️ BytePointer 无容量信息：asByteBuffer() 容量为 0，必须用 put(byte[])
                    pkt.data().position(0).put(opus, 0, opus.size)
                    if (avcodec.avcodec_send_packet(ctx, pkt) < 0) continue
                }
                while (true) {
                    val ret = avcodec.avcodec_receive_frame(ctx, fr)
                    if (ret < 0) break
                    val n = fr.nb_samples()
                    val ch = fr.ch_layout().nb_channels()
                    if (n > 0 && ch > 0) {
                        val sr = fr.sample_rate()
                        // 输出配置签名变化（设备/音量）→ 重建线路
                        val sig = sr.toString() + "x" + ch + "|" +
                                wantedOutputs.entries.sortedBy { it.key }.joinToString("|") { (k, v) -> "$k=${"%.2f".format(v)}" }
                        if (sig != outsSig || outs.isEmpty()) {
                            if (outs.isEmpty() && reopenAt > System.currentTimeMillis()) {
                                // 全失败后的节流重试（1s 一次）
                            } else {
                                rebuildOuts(sr, ch)
                                outsSig = if (outs.isNotEmpty()) sig else ""
                                reopenAt = System.currentTimeMillis() + 1000
                            }
                        }
                        if (outs.isEmpty()) {
                            avutil.av_frame_unref(fr)
                            continue
                        }
                        val pcm = decodeToS16(fr, n, ch)
                        val it = outs.iterator()
                        while (it.hasNext()) {
                            val o = it.next()
                            try {
                                val bytes = if (o.gain >= 0.999f) pcm else scalePcm(pcm, o.gain)
                                var written = 0
                                while (written < bytes.size && running) {
                                    written += o.line.write(bytes, written, bytes.size - written)
                                }
                            } catch (e: Exception) {
                                runCatching { o.line.stop(); o.line.close() }
                                it.remove()
                                onError?.invoke("音频输出 ${o.deviceId.ifBlank { "默认设备" }} 写入失败（${e.javaClass.simpleName}），已移除")
                            }
                        }
                        if (outs.isEmpty()) {
                            outsSig = ""
                            reopenAt = System.currentTimeMillis() + 1000
                        }
                    }
                    avutil.av_frame_unref(fr)
                }
                if (opus == null) Thread.sleep(4)
            }
        }.onFailure {
            running = false
            onError?.invoke("音频播放器错误：${it.message ?: it.javaClass.simpleName}")
        }
        releaseAll()
    }

    /** s16 PCM 按增益缩放（每路输出独立音量） */
    private fun scalePcm(pcm: ByteArray, gain: Float): ByteArray {
        val out = pcm.copyOf()
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val sb = bb.asShortBuffer()
        for (i in 0 until sb.limit()) {
            sb.put(i, (sb.get(i) * gain).toInt().toShort())
        }
        return out
    }

    /** 解码帧（float packed/planar 或 s16）→ s16le 字节（主音量内联）
     *  ⚠️ 所有 BytePointer 读取前必须显式 limit(n)：getter 返回的指针容量未知，
     *    asByteBuffer() 视图容量为 0，不设 limit 会 BufferUnderflow */
    private fun decodeToS16(fr: AVFrame, n: Int, ch: Int): ByteArray {
        val out = ByteArray(n * ch * 2)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val sb = bb.asShortBuffer()
        when (fr.format()) {
            avutil.AV_SAMPLE_FMT_FLT -> {
                val fb = fr.data(0)!!.position(0).limit((n * ch * 4).toLong())
                    .asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                for (i in 0 until n * ch) sb.put(i, (fb.get(i) * volume * 32767f).toInt().toShort())
            }
            avutil.AV_SAMPLE_FMT_S16 -> {
                val srcSb = fr.data(0)!!.position(0).limit((n * ch * 2).toLong())
                    .asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                for (i in 0 until n * ch) sb.put(i, (srcSb.get(i) * volume).toInt().toShort())
            }
            avutil.AV_SAMPLE_FMT_FLTP -> {
                for (c in 0 until ch) {
                    val fb = fr.data(c)!!.position(0).limit((n * 4).toLong())
                        .asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    for (i in 0 until n) {
                        sb.put(i * ch + c, (fb.get(i) * volume * 32767f).toInt().toShort())
                    }
                }
            }
            avutil.AV_SAMPLE_FMT_S16P -> {
                for (c in 0 until ch) {
                    val srcSb = fr.data(c)!!.position(0).limit((n * 2).toLong())
                        .asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    for (i in 0 until n) {
                        sb.put(i * ch + c, (srcSb.get(i) * volume).toInt().toShort())
                    }
                }
            }
        }
        return out
    }
}

/**
 * 桌面麦克风：TargetDataLine 48kHz 单声道 s16 → FFmpeg Opus 编码（0x02 上行）
 * 自动适配编码器采样格式（s16 / flt / fltp）；v2.4.0 支持 mixer 设备选择与多设备混音
 */
class DesktopMicCapture : MicCapture {

    @Volatile
    private var running = false
    private var thread: Thread? = null
    @Volatile
    private var wantedDevices: List<String> = emptyList()
    private var onOpus: ((ByteArray) -> Unit)? = null

    override val active: Boolean get() = running

    override fun start(
        deviceIds: List<String>,
        onOpusFrame: (ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        if (running) return
        onOpus = onOpusFrame
        wantedDevices = deviceIds
        running = true
        thread = Thread({ loop(onError) }, "selkies-mic").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    /** mixer:N → 打开第 N 个 mixer 的 TargetDataLine；null → 系统默认 */
    private fun openLine(deviceId: String?, format: AudioFormat, info: DataLine.Info): TargetDataLine? {
        if (deviceId == null) {
            if (!AudioSystem.isLineSupported(info)) return null
            return AudioSystem.getTargetDataLine(format) as TargetDataLine
        }
        if (!deviceId.startsWith("mixer:")) return null
        val idx = deviceId.removePrefix("mixer:").toIntOrNull() ?: return null
        val mixerInfo = AudioSystem.getMixerInfo().getOrNull(idx) ?: return null
        val mixer = AudioSystem.getMixer(mixerInfo)
        if (!mixer.isLineSupported(info)) return null
        return mixer.getLine(info) as TargetDataLine
    }

    private fun loop(onError: (String) -> Unit) {
        val lines = ArrayList<TargetDataLine>()
        var ctx: AVCodecContext? = null
        var frame: AVFrame? = null
        var pkt: AVPacket? = null
        try {
            val sampleRate = 48000
            val channels = 1
            val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            // 打开选中的设备（单台失败跳过；全部失败才算失败）
            val wanted = if (wantedDevices.isEmpty()) listOf<String?>(null) else wantedDevices
            for (id in wanted) {
                runCatching {
                    val l = openLine(id, format, info)
                    if (l != null) {
                        l.open(format, sampleRate / 10 * 4)
                        l.start()
                        lines.add(l)
                    }
                }
            }
            if (lines.isEmpty()) {
                throw IllegalStateException("未找到可用麦克风设备" + if (wantedDevices.isNotEmpty()) "（${wantedDevices.joinToString(",")}）" else "")
            }

            val codec = avcodec.avcodec_find_encoder(AV_CODEC_ID_OPUS)
                ?: throw IllegalStateException("未找到 Opus 编码器")
            // ⚠️【SIGSEGV 修复】avcodec_alloc_context3 分配；包装构造器会越界段错误
            ctx = AVCodecContext(avcodec.avcodec_alloc_context3(codec))
            ctx.sample_rate(sampleRate)
            ctx.ch_layout().nb_channels(channels)
            avutil.av_channel_layout_default(ctx.ch_layout(), channels)
            ctx.bit_rate(32000)

            var opened = false
            for (fmt in intArrayOf(avutil.AV_SAMPLE_FMT_S16, avutil.AV_SAMPLE_FMT_FLT, avutil.AV_SAMPLE_FMT_FLTP)) {
                runCatching { ctx?.deallocate() }
                // ⚠️ 同上：avcodec_alloc_context3 分配，包装构造器会 SIGSEGV
                val tryCtx = AVCodecContext(avcodec.avcodec_alloc_context3(codec))
                tryCtx.sample_rate(sampleRate)
                tryCtx.ch_layout().nb_channels(channels)
                avutil.av_channel_layout_default(tryCtx.ch_layout(), channels)
                tryCtx.bit_rate(32000)
                tryCtx.sample_fmt(fmt)
                if (avcodec.avcodec_open2(tryCtx, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) >= 0) {
                    ctx = tryCtx
                    opened = true
                    break
                }
            }
            if (!opened) throw IllegalStateException("Opus 编码器不支持可用的采样格式")

            val useFmt = ctx?.sample_fmt() ?: avutil.AV_SAMPLE_FMT_S16
            val planar = useFmt == avutil.AV_SAMPLE_FMT_FLTP
            val bytesPerSample = 2

            val frameSamples = 960 // 20ms @ 48kHz
            frame = AVFrame()
            frame.nb_samples(frameSamples)
            frame.format(useFmt)
            frame.ch_layout().nb_channels(channels)
            avutil.av_channel_layout_default(frame.ch_layout(), channels)
            avutil.av_frame_get_buffer(frame, 0)

            pkt = AVPacket()
            val pcm = ByteArray(frameSamples * channels * bytesPerSample)
            val floatBuf = FloatArray(frameSamples * channels)
            val mixShort = ShortArray(frameSamples)
            var pts = 0L

            while (running) {
                // 多设备混音：逐设备读同长帧后平均；单设备直读
                if (lines.size == 1) {
                    var read = 0
                    while (read < pcm.size && running) {
                        val n = lines[0].read(pcm, read, pcm.size - read)
                        if (n <= 0) break
                        read += n
                    }
                    if (read < pcm.size) continue
                } else {
                    java.util.Arrays.fill(mixShort, 0)
                    var got = 0
                    for (l in lines) {
                        val tmp = ByteArray(pcm.size)
                        var read = 0
                        while (read < tmp.size && running) {
                            val n = l.read(tmp, read, tmp.size - read)
                            if (n <= 0) break
                            read += n
                        }
                        if (read == tmp.size) {
                            val sb = ByteBuffer.wrap(tmp).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            for (i in 0 until frameSamples) {
                                mixShort[i] = (mixShort[i] + sb.get(i) / lines.size).toShort()
                            }
                            got++
                        }
                    }
                    if (got == 0) continue
                    ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(mixShort)
                }

                // s16le → 目标格式
                when (useFmt) {
                    avutil.AV_SAMPLE_FMT_FLT, avutil.AV_SAMPLE_FMT_FLTP -> {
                        val src = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val total = frameSamples * channels
                        for (i in 0 until total) {
                            floatBuf[i] = src.get(i) / 32768f
                        }
                    }
                }

                avutil.av_frame_make_writable(frame)
                when (useFmt) {
                    avutil.AV_SAMPLE_FMT_S16 -> {
                        frame.data(0)!!.position(0).put(pcm, 0, pcm.size)
                    }
                    avutil.AV_SAMPLE_FMT_FLT -> {
                        val bb = frame.data(0)!!.position(0).limit((frameSamples * channels * 4).toLong()).asByteBuffer()
                        bb.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(floatBuf)
                    }
                    avutil.AV_SAMPLE_FMT_FLTP -> {
                        for (c in 0 until channels) {
                            val bb = frame.data(c)!!.position(0).limit((frameSamples * 4).toLong()).asByteBuffer()
                            bb.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(floatBuf, c * frameSamples, frameSamples)
                        }
                    }
                }
                frame.pts(pts)
                pts += frameSamples
                if (avcodec.avcodec_send_frame(ctx, frame) < 0) continue
                while (true) {
                    val ret = avcodec.avcodec_receive_packet(ctx, pkt)
                    if (ret < 0) break
                    val n = pkt.size()
                    if (n > 0) {
                        val bytes = ByteArray(n)
                        pkt.data().position(0).get(bytes, 0, n)
                        onOpus?.invoke(bytes)
                    }
                    avcodec.av_packet_unref(pkt)
                }
            }
        } catch (e: Exception) {
            if (running) onError("麦克风错误：${e.message ?: e.toString()}")
        } finally {
            running = false
            for (l in lines) runCatching { l.stop(); l.close() }
            // ⚠️ 同 DesktopAudioPlayer：JavaCPP 对象用 deallocate()，避免双重释放
            runCatching { ctx?.deallocate() }
            runCatching { frame?.deallocate() }
            runCatching { pkt?.deallocate() }
        }
    }
}
