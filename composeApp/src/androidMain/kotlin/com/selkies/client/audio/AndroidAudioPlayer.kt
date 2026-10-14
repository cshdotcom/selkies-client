package com.selkies.client.audio

import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import com.selkies.client.platform.AudioPlayer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Android 音频播放：MediaCodec Opus 解码 → 多路 AudioTrack
 *
 * v2.4.0 修复：
 * 1. 合成 OpusHead 作为 csd-0 —— 无该头时多数设备的 c2.android.opus.decoder 静默无输出（音频无声主因）
 * 2. 声道数来自服务器 audio_channels（默认 2 立体声），并在 INFO_OUTPUT_FORMAT_CHANGED 时重配 AudioTrack
 * 3. 输出缓冲抖动控制：先积 4 帧再开播，防 starvation 卡顿
 *
 * v2.6.0：
 * 4. 多输出设备（多选）：每设备一条 AudioTrack + setPreferredDevice 路由，独立音量（track.setVolume）
 * 5. 设备热插拔（AudioDeviceCallback）自动剔除失效设备
 * 6. onError 反馈（解码器/设备错误不再静默）
 */
class AndroidAudioPlayer : AudioPlayer {

    private var codec: MediaCodec? = null
    private var thread: Thread? = null
    @Volatile
    private var running = false
    @Volatile
    private var volume = 1.0f

    override var onError: ((String) -> Unit)? = null

    /** 期望输出 deviceId → 增益；空 = 系统默认 */
    @Volatile
    private var wantedOutputs: Map<String, Float> = emptyMap()

    private val pending = ConcurrentLinkedQueue<ByteArray>()
    private var sampleRate = 48000
    private var channels = 2

    /** 一路输出：AudioTrack + 设备 + 增益 */
    private class Out(val deviceId: String, val track: AudioTrack, @Volatile var gain: Float)

    private val outs = ArrayList<Out>()
    private var outsSig = ""
    private var audioManager: AudioManager? = null
    private var deviceCallback: AudioDeviceCallback? = null

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
        synchronized(outs) { outs.forEach { runCatching { it.track.setVolume(volume * it.gain) } } }
    }

    override fun setOutputDevices(outputs: Map<String, Float>) {
        wantedOutputs = outputs
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        runCatching { codec?.stop(); codec?.release() }
        codec = null
        synchronized(outs) {
            outs.forEach { runCatching { it.track.stop(); it.track.release() } }
            outs.clear()
        }
        runCatching {
            deviceCallback?.let { audioManager?.unregisterAudioDeviceCallback(it) }
        }
        deviceCallback = null
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

    private fun makeTrack(sr: Int, ch: Int, preferredDev: AudioDeviceInfo?): AudioTrack? {
        return runCatching {
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
            if (preferredDev != null) {
                if (!t.preferredDevice!!.equals(preferredDev)) {
                    t.preferredDevice = preferredDev
                }
            }
            t.play()
            t
        }.getOrNull()
    }

    /** 通过 AudioManager 解析 deviceId（address 或 speaker-N）→ AudioDeviceInfo */
    private fun resolveDevice(deviceId: String): AudioDeviceInfo? {
        val am = audioManager ?: return null
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return null
        // 1) address 精确匹配
        devices.firstOrNull { it.address?.equals(deviceId, true) == true }?.let { return it }
        // 2) speaker-N 索引匹配（枚举顺序）
        if (deviceId.startsWith("speaker-")) {
            val idx = deviceId.removePrefix("speaker-").toIntOrNull() ?: return null
            val outputs = devices.filter { d ->
                when (d.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE -> true
                    else -> false
                }
            }
            outputs.getOrNull(idx)?.let { return it }
        }
        return null
    }

    /** 按期望输出重建 AudioTrack 集合 */
    private fun rebuildOuts(sr: Int, ch: Int) {
        synchronized(outs) {
            outs.forEach { runCatching { it.track.stop(); it.track.release() } }
            outs.clear()
            val wanted = wantedOutputs
            if (wanted.isEmpty()) {
                val t = makeTrack(sr, ch, null)
                if (t != null) {
                    t.setVolume(volume)
                    outs.add(Out("", t, 1f))
                } else {
                    onError?.invoke("无法打开默认音频输出（${sr}Hz ${ch}ch）")
                }
            } else {
                for ((id, gain) in wanted) {
                    val dev = resolveDevice(id)
                    if (id.contains("sco", true)) {
                        // 蓝牙 SCO 输出需要系统级开启
                        runCatching {
                            audioManager?.startBluetoothSco()
                            audioManager?.isBluetoothScoOn = true
                        }
                    }
                    val t = makeTrack(sr, ch, dev)
                    if (t != null) {
                        t.setVolume(volume * gain)
                        outs.add(Out(id, t, gain))
                    } else {
                        onError?.invoke("音频输出设备不可用：$id（已跳过）")
                    }
                }
                if (outs.isEmpty()) {
                    val t = makeTrack(sr, ch, null)
                    if (t != null) {
                        t.setVolume(volume)
                        outs.add(Out("", t, 1f))
                    }
                } else if (wanted.keys.none { it.contains("sco", true) }) {
                    // 非 SCO 选择时关闭 SCO
                    runCatching {
                        if (audioManager?.isBluetoothScoOn == true) {
                            audioManager?.stopBluetoothSco()
                            audioManager?.isBluetoothScoOn = false
                        }
                    }
                }
            }
        }
    }

    private fun loop() {
        runCatching {
            // AudioManager + 设备热插拔监听
            val ctx = com.selkies.client.platform.AndroidPlatform.appCtx()
            audioManager = ctx?.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager
            deviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    // 输出设备拔出 → 重建（剔除失效路）
                    outsSig = ""
                }
            }
            audioManager?.registerAudioDeviceCallback(deviceCallback, android.os.Handler(android.os.Looper.getMainLooper()))

            var t: AudioTrack? = null

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
                            channels = realCh.coerceIn(1, 2)
                            sampleRate = realSr
                            outsSig = ""
                        }
                    } else if (outIdx >= 0 && info.size > 0) {
                        val outBuf = c.getOutputBuffer(outIdx)
                        if (outBuf != null) {
                            val pcm = ByteArray(info.size)
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            outBuf.get(pcm)
                            // 输出集合维护（解码线程独占；wantedOutputs 变化时重建）
                            val sig = "$sampleRate x $channels | " +
                                    wantedOutputs.entries.sortedBy { it.key }.joinToString("|") { (k, v) -> "$k=${"%.2f".format(v)}" }
                            if (sig != outsSig) {
                                rebuildOuts(sampleRate, channels)
                                outsSig = if (outs.isNotEmpty()) sig else ""
                            }
                            if (outs.isEmpty()) {
                                c.releaseOutputBuffer(outIdx, false)
                                continue
                            }
                            var wrote = 0
                            val step = channels * 2
                            var failed = false
                            for (o in outs.toList()) {
                                try {
                                    o.track.write(pcm, 0, pcm.size)
                                } catch (e: Exception) {
                                    failed = true
                                    runCatching { o.track.stop(); o.track.release() }
                                    synchronized(outs) { outs.remove(o) }
                                    onError?.invoke("音频输出 ${o.deviceId.ifBlank { "默认设备" }} 异常（${e.javaClass.simpleName}），已移除")
                                }
                            }
                            if (outs.isEmpty()) outsSig = ""
                        }
                        c.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx = c.dequeueOutputBuffer(info, 0)
                }
                if (opus == null) Thread.sleep(4)
            }
        }.onFailure {
            running = false
            onError?.invoke("音频播放器错误：${it.message ?: it.javaClass.simpleName}")
        }
    }
}
