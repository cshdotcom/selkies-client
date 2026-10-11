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
    private var channels = 1

    override val bufferLevelMs: Int
        get() = (pending.size * 20)

    override fun start(sampleRate: Int, channels: Int) {
        if (running) return
        this.sampleRate = sampleRate
        this.channels = channels
        running = true
        thread = Thread(this::loop, "selkies-audio-play").apply {
            isDaemon = true
            start()
        }
    }

    override fun playOpus(opus: ByteArray) {
        if (!running) return
        if (pending.size > 64) pending.poll() // 丢弃最旧
        pending.offer(opus)
    }

    override fun onQuiet() {
        // 播完缓冲即可（AudioTrack 自然排空）
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

    private fun loop() {
        runCatching {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val t = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
                maxOf(minBuf, 4096) * 2,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            t.play()
            t.setVolume(volume)
            track = t

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels)
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            c.configure(format, null, null, 0)
            c.start()
            codec = c

            val info = MediaCodec.BufferInfo()
            while (running) {
                val opus = pending.poll()
                if (opus != null) {
                    var idx = c.dequeueInputBuffer(10_000_000) // 10ms
                    if (idx >= 0) {
                        val buf = c.getInputBuffer(idx)
                        if (buf != null) {
                            buf.clear()
                            buf.put(opus)
                            c.queueInputBuffer(idx, 0, opus.size, System.nanoTime() / 1000, 0)
                        }
                    }
                }
                var outIdx = c.dequeueOutputBuffer(info, if (opus != null) 0 else 5_000_000)
                while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIdx >= 0 && info.size > 0) {
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
