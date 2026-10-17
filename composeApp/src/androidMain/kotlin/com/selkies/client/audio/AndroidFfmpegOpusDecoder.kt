package com.selkies.client.audio

import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.javacpp.BytePointer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * v2.7.5：Android FFmpeg 软件 Opus 解码器（MediaCodec 无输出设备的备用引擎）
 *
 * 背景：用户三版实测「安卓端音频彻底无法播放」（脚本解码正常 = 服务器链路正常，
 * 桌面 FFmpeg 同协议解码 1519 帧 PCM 峰值 32767 = 协议解析正确）——指向部分设备的
 * c2.android.opus.decoder 只吃帧不出 PCM。本类把桌面端验证过的 FFmpeg 解码路径
 * （org.bytedeco:ffmpeg android-arm64 原生库）带到 Android：同一 API、同一调用序列。
 *
 * 失败语义：原生库缺失（非 arm64 设备）/初始化失败 → open() 返回 false，
 * 调用方置 ffmpegFailed 不再重试（MediaCodec 主引擎继续承担 + 定期重建自愈）。
 */
class AndroidFfmpegOpusDecoder {

    private var ctx: AVCodecContext? = null
    private var pkt: AVPacket? = null
    private var frame: AVFrame? = null

    @Volatile var sampleRate: Int = 48000
        private set
    @Volatile var channels: Int = 2
        private set

    /** 初始化解码器（幂等；失败返回 false） */
    fun open(): Boolean {
        if (ctx != null) return true
        return runCatching {
            avutil.av_log_set_level(avutil.AV_LOG_ERROR)
            val codec = avcodec.avcodec_find_decoder(avcodec.AV_CODEC_ID_OPUS)
                ?: return false
            // ⚠️ 必须用 avcodec_alloc_context3 分配；AVCodecContext(codec) 包装构造器会越界写 SIGSEGV
            val c = AVCodecContext(avcodec.avcodec_alloc_context3(codec))
            c.sample_rate(48000)
            if (avcodec.avcodec_open2(c, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) < 0) {
                runCatching { avcodec.avcodec_free_context(c) }
                return false
            }
            ctx = c
            pkt = AVPacket()
            frame = AVFrame()
            true
        }.getOrDefault(false)
    }

    /**
     * 解码一帧 Opus → PCM s16 小端交错字节；无输出（首帧前/内部缓冲）返回 null。
     * 单帧可能产生 0 或 1 个输出帧（Opus 解码器内部有预跳/缓冲）。
     */
    fun decode(opus: ByteArray): ByteArray? {
        val c = ctx ?: return null
        val p = pkt ?: return null
        val fr = frame ?: return null
        return runCatching {
            avcodec.av_packet_unref(p)
            avcodec.av_new_packet(p, opus.size)
            // BytePointer 无容量信息：asByteBuffer() 容量为 0，必须 put(byte[])
            p.data().position(0).put(opus, 0, opus.size)
            if (avcodec.avcodec_send_packet(c, p) < 0) return null
            val ret = avcodec.avcodec_receive_frame(c, fr)
            if (ret < 0) return null
            val n = fr.nb_samples()
            val ch = fr.ch_layout().nb_channels()
            if (n <= 0 || ch <= 0) {
                avutil.av_frame_unref(fr)
                return null
            }
            val sr = fr.sample_rate()
            if (sr > 0) sampleRate = sr
            channels = ch.coerceIn(1, 2)
            val pcm = decodeToS16(fr, n, ch)
            avutil.av_frame_unref(fr)
            pcm
        }.getOrNull()
    }

    /** 释放（幂等） */
    fun close() {
        runCatching {
            frame?.close()
            pkt?.close()
            ctx?.let { avcodec.avcodec_free_context(it) }
        }
        frame = null
        pkt = null
        ctx = null
    }

    /** AVFrame → s16 小端交错（适配 s16/s16p/flt/fltp 平面/打包格式；与桌面端一致） */
    private fun decodeToS16(fr: AVFrame, n: Int, ch: Int): ByteArray {
        val out = ByteArray(n * ch * 2)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val sb = bb.asShortBuffer()
        when (fr.format()) {
            avutil.AV_SAMPLE_FMT_FLT -> {
                val fb = fr.data(0)!!.position(0).limit((n * ch * 4).toLong())
                    .asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                for (i in 0 until n * ch) sb.put(i, (fb.get(i) * 32767f).toInt().toShort())
            }
            avutil.AV_SAMPLE_FMT_S16 -> {
                val srcSb = fr.data(0)!!.position(0).limit((n * ch * 2).toLong())
                    .asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                for (i in 0 until n * ch) sb.put(i, srcSb.get(i))
            }
            avutil.AV_SAMPLE_FMT_FLTP -> {
                for (c in 0 until ch) {
                    val fb = fr.data(c)!!.position(0).limit((n * 4).toLong())
                        .asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    for (i in 0 until n) {
                        sb.put(i * ch + c, (fb.get(i) * 32767f).toInt().toShort())
                    }
                }
            }
            avutil.AV_SAMPLE_FMT_S16P -> {
                for (c in 0 until ch) {
                    val srcSb = fr.data(c)!!.position(0).limit((n * 2).toLong())
                        .asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    for (i in 0 until n) {
                        sb.put(i * ch + c, srcSb.get(i))
                    }
                }
            }
        }
        return out
    }
}
