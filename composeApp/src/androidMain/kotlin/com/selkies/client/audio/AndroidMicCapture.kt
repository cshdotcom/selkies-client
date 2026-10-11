package com.selkies.client.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.selkies.client.platform.AndroidPlatform
import com.selkies.client.platform.MicCapture

/**
 * Android 麦克风采集：AudioRecord 24kHz 单声道 → MediaCodec Opus 编码（0x02 上行）
 * 需要 Android 10+ 的 Opus 软件编码器（c2.android.opus.encoder）
 */
class AndroidMicCapture : MicCapture {

    private var thread: Thread? = null
    @Volatile
    private var running = false
    private var onOpus: ((ByteArray) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    override val active: Boolean get() = running

    @SuppressLint("MissingPermission")
    override fun start(onOpusFrame: (ByteArray) -> Unit, onError: (String) -> Unit) {
        if (running) return
        this.onOpus = onOpusFrame
        this.onError = onError

        val ctx = runCatching { AndroidPlatform.appCtx() }.getOrNull()
        if (ctx != null &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            AndroidPlatform.ensurePermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted ->
                if (granted) start(onOpusFrame, onError) else onError("未授予麦克风权限")
            }
            return
        }

        running = true
        thread = Thread(this::loop, "selkies-mic").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        val sampleRate = 24000
        val frameSize = 480 // 20ms
        var record: AudioRecord? = null
        var encoder: MediaCodec? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, frameSize * 2 * 4) * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                onError?.invoke("麦克风初始化失败")
                return
            }
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 32000)
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            record.startRecording()

            val pcm = ByteArray(frameSize * 2)
            val info = MediaCodec.BufferInfo()
            var ptsUs = 0L
            while (running) {
                val n = record.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                var idx = encoder.dequeueInputBuffer(10_000_000)
                if (idx >= 0) {
                    val buf = encoder.getInputBuffer(idx)
                    if (buf != null) {
                        buf.clear()
                        buf.put(pcm, 0, n)
                        encoder.queueInputBuffer(idx, 0, n, ptsUs, 0)
                        ptsUs += n * 1_000_000L / (sampleRate * 2)
                    }
                }
                var outIdx = encoder.dequeueOutputBuffer(info, 0)
                while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIdx >= 0 && info.size > 0) {
                        val outBuf = encoder.getOutputBuffer(outIdx)
                        if (outBuf != null) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val opus = ByteArray(info.size)
                            outBuf.get(opus)
                            onOpus?.invoke(opus)
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx = encoder.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (e: Exception) {
            if (running) {
                onError?.invoke(
                    if (e.message?.contains("opus", true) == true || e is java.lang.IllegalArgumentException)
                        "此设备不支持 Opus 编码（需 Android 10+）" else (e.message ?: e.toString())
                )
            }
        } finally {
            running = false
            runCatching { record?.stop(); record?.release() }
            runCatching { encoder?.stop(); encoder?.release() }
        }
    }
}
