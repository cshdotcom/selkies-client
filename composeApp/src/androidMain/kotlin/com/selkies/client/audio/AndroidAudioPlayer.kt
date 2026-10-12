package com.selkies.client.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import com.selkies.client.platform.AudioPlayer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Android 音频播放：MediaCodec Opus 解码 → AudioTrack
 *
 * v2.4.0 修复：
 * 1. 合成 OpusHead 作为 csd-0 —— 无该头时多数设备的 c2.android.opus.decoder 静默无输出（音频无声主因）
 * 2. 声道数来自服务器 audio_channels（默认 2 立体声），并在 INFO_OUTPUT_FORMAT_CHANGED 时重配 AudioTrack
 * 3. 输出缓冲抖动控制：先积 4 帧再开播，防 starvation 卡顿
 */
class AndroidAudioPlayer : AudioPlayer {

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile
    private var running = false
    @Volatile
    private var volume = 1.0f

    private val pending = ConcurrentLinkedQueue<ByteArray>()
    private var sampleRate = 48000
    private var channels = 2

    override val bufferLevelMs: Int
        get() = pending.size * 10

    override fun start(sampleRate: Int, channels: Int) {
        if (running) return
        this.sampleRate = sampleRate
        this.channels = channels.coerceIn(1, 2)
        running = true
        thread = Thread(this::loop, "selkies-audio-play").apply {
            isDaemon = true
            start()
        }
    }

    override fun playOpus(opus: ByteArray) {
        if (!running) return
        if (pending.size > 96) pending.poll() // 丢弃最旧（约 1s @10ms 帧）
        pending.offer(opus)
    }

    override fun onQuiet() {
        // 服务器静默门关闭标记：清空缓冲即可（AudioTrack 自然排空）
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        runCatching { track?.setVolume(volume) }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        runCatching { codec?.stop(); codec?.release() }
        codec = null
        runCatching { track?.stop(); track?.release() }
        track = null
        pending.clear()
    }

    /** 合成 OpusHead（RFC 7845）——MediaCodec Opus 解码器必须的 csd-0 */
    private fun opusHead(ch: Int): ByteArray {
        val head = ByteArray(19)
        val magic = "OpusHead".toByteArray(Charsets.US_ASCII)
        System.arraycopy(magic, 0, head, 0, 8)
        head[8] = 1                    // version
        head[9] = ch.toByte()          // channels
        head[10] = 0x38                // preskip = 312（c2 解码器标准值 0x0138 → LE 312）
        head[11] = 0x01
        // input sample rate 48000 LE（解码器忽略但要求字段存在）
        head[12] = 0x80.toByte(); head[13] = 0xBB.toByte(); head[14] = 0; head[15] = 0
        head[16] = 0; head[17] = 0     // output gain
        head[18] = 0                   // mapping family 0
        return head
    }

    private fun makeTrack(sr: Int, ch: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sr,
            if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sr)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO)
                .build(),
            maxOf(minBuf, 4096) * 4,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        t.play()
        t.setVolume(volume)
        return t
    }

    private fun loop() {
        runCatching {
            var t = makeTrack(sampleRate, channels)
            track = t

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels)
            // csd-0：OpusHead —— 没有它多数设备的 Opus 解码器完全无输出
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(opusHead(channels)))
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            c.configure(format, null, null, 0)
            c.start()
            codec = c

            // 抖动缓冲：先积 4 帧再送解码，避免频繁 underrun
            var primed = 0
            val info = MediaCodec.BufferInfo()
            while (running) {
                val opus = if (primed < 4) {
                    if (pending.size >= 4 || primed > 0) {
                        primed++
                        pending.poll()
                    } else null
                } else pending.poll()

                if (opus != null) {
                    var idx = c.dequeueInputBuffer(50_000) // 50ms
                    if (idx >= 0) {
                        val buf = c.getInputBuffer(idx)
                        if (buf != null) {
                            buf.clear()
                            buf.put(opus)
                            c.queueInputBuffer(idx, 0, opus.size, System.nanoTime() / 1000, 0)
                        }
                    }
                }
                var outIdx = c.dequeueOutputBuffer(info, if (opus != null) 0 else 10_000)
                while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // 解码器实际输出格式（声道/采样率）可能与配置不同：重配 AudioTrack
                        val out = c.outputFormat
                        val realCh = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val realSr = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (realCh != channels || realSr != sampleRate) {
                            runCatching { t.stop(); t.release() }
                            channels = realCh.coerceIn(1, 2)
                            sampleRate = realSr
                            t = makeTrack(realSr, channels)
                            track = t
                        }
                    } else if (outIdx >= 0 && info.size > 0) {
                        val outBuf = c.getOutputBuffer(outIdx)
                        if (outBuf != null) {
                            val pcm = ByteArray(info.size)
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            outBuf.get(pcm)
                            t.write(pcm, 0, pcm.size)
                        }
                        c.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx = c.dequeueOutputBuffer(info, 0)
                }
                if (opus == null) Thread.sleep(4)
            }
        }.onFailure {
            running = false
        }
    }
}
