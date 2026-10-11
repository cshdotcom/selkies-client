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
 * 桌面音频播放：FFmpeg Opus 解码 → 软件 float→s16 → SourceDataLine
 */
class DesktopAudioPlayer : AudioPlayer {

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private val pending = ConcurrentLinkedQueue<ByteArray>()

    // 解码线程独占
    private var codecCtx: AVCodecContext? = null
    private var packet: AVPacket? = null
    private var frame: AVFrame? = null
    private var line: SourceDataLine? = null

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

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        releaseAll()
        pending.clear()
    }

    private fun releaseAll() {
        runCatching { line?.stop(); line?.close() }
        runCatching { codecCtx?.let { avcodec.avcodec_free_context(it) } }
        runCatching { packet?.let { avcodec.av_packet_free(it) } }
        runCatching { frame?.let { avutil.av_frame_free(it) } }
        line = null
        codecCtx = null
        packet = null
        frame = null
    }

    private fun loop(sampleRate: Int) {
        runCatching {
            val codec = avcodec.avcodec_find_decoder(AV_CODEC_ID_OPUS)
                ?: throw IllegalStateException("未找到 Opus 解码器")
            val ctx = AVCodecContext(codec)
            ctx.sample_rate(sampleRate)
            if (avcodec.avcodec_open2(ctx, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) < 0) {
                throw IllegalStateException("Opus 解码器初始化失败")
            }
            codecCtx = ctx
            packet = AVPacket()
            frame = AVFrame()

            val pkt = packet!!
            val fr = frame!!

            while (running) {
                val opus = pending.poll()
                if (opus != null) {
                    avcodec.av_packet_unref(pkt)
                    avcodec.av_new_packet(pkt, opus.size)
                    pkt.data().position(0).asByteBuffer().put(opus)
                    if (avcodec.avcodec_send_packet(ctx, pkt) < 0) continue
                }
                while (true) {
                    val ret = avcodec.avcodec_receive_frame(ctx, fr)
                    if (ret < 0) break
                    val n = fr.nb_samples()
                    val ch = fr.ch_layout().nb_channels()
                    if (n > 0 && ch > 0) {
                        val l = ensureLine(fr.sample_rate(), ch)
                        val pcm = decodeToS16(fr, n, ch)
                        var written = 0
                        while (written < pcm.size && running) {
                            written += l.write(pcm, written, pcm.size - written)
                        }
                    }
                    avutil.av_frame_unref(fr)
                }
                if (opus == null) Thread.sleep(4)
            }
        }.onFailure {
            running = false
        }
        releaseAll()
    }

    private fun ensureLine(sampleRate: Int, channels: Int): SourceDataLine {
        val cur = line
        if (cur != null && cur.format.sampleRate == sampleRate.toFloat() && cur.format.channels == channels) {
            return cur
        }
        runCatching { cur?.stop(); cur?.close() }
        val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val l = AudioSystem.getLine(info) as SourceDataLine
        l.open(format, sampleRate / 10 * 4)
        l.start()
        line = l
        return l
    }

    /** 解码帧（float packed/planar 或 s16）→ s16le 字节 */
    private fun decodeToS16(fr: AVFrame, n: Int, ch: Int): ByteArray {
        val out = ByteArray(n * ch * 2)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val sb = bb.asShortBuffer()
        when (fr.format()) {
            avutil.AV_SAMPLE_FMT_FLT -> {
                val fb = fr.data(0)!!.position(0).asByteBuffer()
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                for (i in 0 until n * ch) sb.put(i, (fb.get(i) * volume * 32767f).toInt().toShort())
            }
            avutil.AV_SAMPLE_FMT_S16 -> {
                val src = fr.data(0)!!.position(0).asByteBuffer()
                    .order(ByteOrder.LITTLE_ENDIAN)
                val dst = ByteBuffer.wrap(out)
                // 音量处理
                val srcSb = src.asShortBuffer()
                for (i in 0 until n * ch) sb.put(i, (srcSb.get(i) * volume).toInt().toShort())
            }
            avutil.AV_SAMPLE_FMT_FLTP -> {
                for (c in 0 until ch) {
                    val fb = fr.data(c)!!.position(0).asByteBuffer()
                        .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    for (i in 0 until n) {
                        sb.put(i * ch + c, (fb.get(i) * volume * 32767f).toInt().toShort())
                    }
                }
            }
            avutil.AV_SAMPLE_FMT_S16P -> {
                for (c in 0 until ch) {
                    val srcSb = fr.data(c)!!.position(0).asByteBuffer()
                        .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
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
 * 自动适配编码器采样格式（s16 / flt / fltp）
 */
class DesktopMicCapture : MicCapture {

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var onOpus: ((ByteArray) -> Unit)? = null

    override val active: Boolean get() = running

    override fun start(onOpusFrame: (ByteArray) -> Unit, onError: (String) -> Unit) {
        if (running) return
        onOpus = onOpusFrame
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

    private fun loop(onError: (String) -> Unit) {
        var line: TargetDataLine? = null
        var ctx: AVCodecContext? = null
        var frame: AVFrame? = null
        var pkt: AVPacket? = null
        try {
            val sampleRate = 48000
            val channels = 1
            val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) throw IllegalStateException("未找到麦克风设备")
            line = AudioSystem.getTargetDataLine(format) as TargetDataLine
            line.open(format, sampleRate / 10 * 4)
            line.start()

            val codec = avcodec.avcodec_find_encoder(AV_CODEC_ID_OPUS)
                ?: throw IllegalStateException("未找到 Opus 编码器")
            ctx = AVCodecContext(codec)
            ctx.sample_rate(sampleRate)
            ctx.ch_layout().nb_channels(channels)
            avutil.av_channel_layout_default(ctx.ch_layout(), channels)
            ctx.bit_rate(32000)

            var opened = false
            for (fmt in intArrayOf(avutil.AV_SAMPLE_FMT_S16, avutil.AV_SAMPLE_FMT_FLT, avutil.AV_SAMPLE_FMT_FLTP)) {
                runCatching { ctx?.let { avcodec.avcodec_free_context(it) } }
                val tryCtx = AVCodecContext(codec)
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
            var pts = 0L

            while (running) {
                var read = 0
                while (read < pcm.size && running) {
                    val n = line.read(pcm, read, pcm.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < pcm.size) continue

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
                        frame.data(0)!!.position(0).asByteBuffer().put(pcm)
                    }
                    avutil.AV_SAMPLE_FMT_FLT -> {
                        val bb = frame.data(0)!!.position(0).asByteBuffer()
                        bb.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(floatBuf)
                    }
                    avutil.AV_SAMPLE_FMT_FLTP -> {
                        for (c in 0 until channels) {
                            val bb = frame.data(c)!!.position(0).asByteBuffer()
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
                        pkt.data().position(0).asBuffer().get(bytes)
                        onOpus?.invoke(bytes)
                    }
                    avcodec.av_packet_unref(pkt)
                }
            }
        } catch (e: Exception) {
            if (running) onError("麦克风错误：${e.message ?: e.toString()}")
        } finally {
            running = false
            runCatching { line?.stop(); line?.close() }
            runCatching { ctx?.let { avcodec.avcodec_free_context(it) } }
            runCatching { frame?.let { avutil.av_frame_free(it) } }
            runCatching { pkt?.let { avcodec.av_packet_free(it) } }
        }
    }
}
