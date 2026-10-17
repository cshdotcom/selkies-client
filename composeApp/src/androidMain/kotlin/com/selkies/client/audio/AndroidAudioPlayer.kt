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
 *
 * 【v2.7.6】「安卓端打死无法播放」根因修复（三重）：
 *  A. 解码器选择：createDecoderByType 可能选中设备厂商的 Opus「硬件」解码器（部分机型
 *     注册了但实际坏/无输出）。现在按可靠度顺序创建：c2.android.opus.decoder（软件，标准）
 *     → OMX.google.opus.decoder（旧软件）→ createDecoderByType 兜底。
 *  B. 容错重建：旧版 loop() 里任何一个异常（解码器抛 IllegalStateException / 输出设备写异常）
 *     都会跳出 runCatching → running=false → 整条音频线程永久死亡 = 「打死无法播放」。
 *     现在单帧异常只丢帧，解码器异常自动重建（最多 5 次），输出设备异常剔除该路。
 *  C. 重建风暴修复：旧版 outs 为空时每个输出缓冲都触发 rebuildOuts（每帧 new AudioTrack +
 *     play() → 系统抖动/卡死）。现在 2 秒退避重试。
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

    /** v2.7.6：解码器重建计数/上限（0 = 尚未重建过） */
    private var decoderRebuilds = 0
    private val maxDecoderRebuilds = 5

    /** 一路输出：AudioTrack + 设备 + 增益 */
    private class Out(val deviceId: String, val track: AudioTrack, @Volatile var gain: Float)

    private val outs = ArrayList<Out>()
    private var outsSig = ""
    /** v2.7.6：上次尝试重建输出时间（退避，防每帧重建风暴） */
    @Volatile private var lastOutsRebuildAt = 0L
    /** v2.7.6：诊断计数（onError 反馈「解码正常但无输出」类问题） */
    @Volatile private var decodedFrames = 0L
    @Volatile private var writtenFrames = 0L

    private var audioManager: AudioManager? = null
    private var deviceCallback: AudioDeviceCallback? = null

    override val bufferLevelMs: Int
        get() = pending.size * 20   // v2.7.6：服务器 audio_frame_duration_ms 默认 20ms（旧版误写 10）

    override fun start(sampleRate: Int, channels: Int) {
        if (running) return
        this.sampleRate = sampleRate
        this.channels = channels.coerceIn(1, 2)
        decoderRebuilds = 0
        decodedFrames = 0
        writtenFrames = 0
        running = true
        thread = Thread(this::loop, "selkies-audio-play").apply {
            isDaemon = true
            start()
        }
    }

    override fun playOpus(opus: ByteArray) {
        if (!running) return
        if (pending.size > 96) pending.poll() // 丢弃最旧（约 2s @20ms 帧）
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

    /** v2.7.6：按可靠度创建 Opus 解码器（软件优先——createDecoderByType 在部分机型选中坏的厂商实现） */
    private fun createOpusDecoder(): MediaCodec? {
        // ① API 29+ 软件 Codec2（最可靠）
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching { MediaCodec.createByCodecName("c2.android.opus.decoder") }.getOrNull()?.let { return it }
        }
        // ② API 21+ 旧 OMX 软件
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            runCatching { MediaCodec.createByCodecName("OMX.google.opus.decoder") }.getOrNull()?.let { return it }
        }
        // ③ 按类型兜底（可能拿到厂商实现）
        return runCatching { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS) }.getOrNull()
    }

    private fun makeTrack(sr: Int, ch: Int, preferredDev: AudioDeviceInfo?): AudioTrack? {
        return runCatching {
            val minBuf = AudioTrack.getMinBufferSize(
                sr,
                if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val b = android.media.AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sr)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf, 4096) * 4)
                .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
            // v2.7.6：低延迟模式（API 26+，Builder 方法）——远程桌面音频对延迟敏感
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                runCatching { b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) }
            }
            val t = b.build()
            if (preferredDev != null) {
                runCatching { t.setPreferredDevice(preferredDev) }
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
            lastOutsRebuildAt = System.currentTimeMillis()
            val wanted = wantedOutputs
            if (wanted.isEmpty()) {
                val t = makeTrack(sr, ch, null)
                if (t != null) {
                    t.setVolume(volume)
                    outs.add(Out("", t, 1f))
                } else {
                    onError?.invoke("无法打开默认音频输出（${sr}Hz ${ch}ch）——请检查媒体音量/输出设备")
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
        // AudioManager + 设备热插拔监听
        runCatching {
            val ctx = com.selkies.client.platform.AndroidPlatform.appCtx()
            audioManager = ctx?.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager
            deviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    // 输出设备拔出 → 重建（剔除失效路）
                    outsSig = ""
                }
            }
            audioManager?.registerAudioDeviceCallback(deviceCallback, android.os.Handler(android.os.Looper.getMainLooper()))
        }

        val c0: MediaCodec?
        try {
            c0 = openDecoder()
        } catch (t: Throwable) {
            running = false
            onError?.invoke("无法创建 Opus 解码器：${t.message ?: t.javaClass.simpleName}（Android ${android.os.Build.VERSION.SDK_INT}）")
            return
        }
        if (c0 == null) {
            running = false
            onError?.invoke("此设备没有 Opus 解码器（Android ${android.os.Build.VERSION.SDK_INT}）——无法播放远程音频")
            return
        }
        var c: MediaCodec = c0
        codec = c

        // 抖动缓冲：先积 4 帧再送解码，避免频繁 underrun
        var primed = 0
        val info = MediaCodec.BufferInfo()
        while (running) {
            // v2.7.6：单帧级容错——任何异常只丢帧/重建解码器，绝不跳出循环（旧版一异常线程就死）
            try {
                val opus = if (primed < 4) {
                    if (pending.size >= 4 || primed > 0) {
                        primed++
                        pending.poll()
                    } else null
                } else pending.poll()

                if (opus != null) {
                    val idx = c.dequeueInputBuffer(50_000) // 50ms
                    if (idx >= 0) {
                        val buf = c.getInputBuffer(idx)
                        if (buf != null) {
                            buf.clear()
                            if (buf.capacity() >= opus.size) {
                                buf.put(opus)
                                c.queueInputBuffer(idx, 0, opus.size, System.nanoTime() / 1000, 0)
                            }
                            // 容量不足（极罕见）：放弃本帧而非异常
                        }
                    }
                }
                var outIdx = c.dequeueOutputBuffer(info, if (opus != null) 0 else 10_000)
                while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // 解码器实际输出格式（声道/采样率）可能与配置不同：重配 AudioTrack
                        val out = c.outputFormat
                        val realCh = runCatching { out.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(channels)
                        val realSr = runCatching { out.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(sampleRate)
                        if (realCh != channels || realSr != sampleRate) {
                            channels = realCh.coerceIn(1, 2)
                            sampleRate = realSr
                            outsSig = ""
                        }
                    } else if (outIdx >= 0 && info.size > 0) {
                        val outBuf = c.getOutputBuffer(outIdx)
                        if (outBuf != null) {
                            decodedFrames++
                            val pcm = ByteArray(info.size)
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            outBuf.get(pcm)
                            // 输出集合维护（解码线程独占；wantedOutputs 变化时重建）
                            val sig = "$sampleRate x $channels | " +
                                    wantedOutputs.entries.sortedBy { it.key }.joinToString("|") { (k, v) -> "$k=${"%.2f".format(v)}" }
                            val outsEmptyNow = synchronized(outs) { outs.isEmpty() }
                            if (sig != outsSig) {
                                rebuildOuts(sampleRate, channels)
                                outsSig = if (outs.isNotEmpty()) sig else ""
                            } else if (outsEmptyNow &&
                                System.currentTimeMillis() - lastOutsRebuildAt > 2000
                            ) {
                                // v2.7.6：退避重试打开输出（旧版每帧重建 = 风暴）；2 秒一次
                                rebuildOuts(sampleRate, channels)
                                outsSig = if (outs.isNotEmpty()) sig else ""
                                if (outs.isEmpty() && decodedFrames > 50) {
                                    onError?.invoke("音频已解码 ${decodedFrames} 帧但打不开输出设备——请检查媒体音量是否为 0 / 是否被其他应用占用")
                                }
                            }
                            if (outs.isEmpty()) {
                                c.releaseOutputBuffer(outIdx, false)
                                outIdx = c.dequeueOutputBuffer(info, 0)
                                continue
                            }
                            for (o in outs.toList()) {
                                try {
                                    o.track.write(pcm, 0, pcm.size)
                                    writtenFrames++
                                } catch (e: Exception) {
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
            } catch (t: Throwable) {
                // v2.7.6：解码器异常 → 重建（最多 5 次）；重建失败才放弃
                if (!running) break
                runCatching { c.stop(); c.release() }
                codec = null
                if (decoderRebuilds < maxDecoderRebuilds) {
                    decoderRebuilds++
                    val nc = runCatching { openDecoder() }.getOrNull()
                    if (nc != null) {
                        c = nc
                        codec = c
                        pending.clear()   // 丢弃乱序残帧（RED 上层会补）
                        primed = 0
                    } else {
                        Thread.sleep(500)
                    }
                } else {
                    running = false
                    onError?.invoke("音频解码器反复异常（${t.message ?: t.javaClass.simpleName}），已停止重试")
                    break
                }
            }
        }
        runCatching { c.stop(); c.release() }
        codec = null
    }

    /** 创建并启动解码器（configure + start） */
    private fun openDecoder(): MediaCodec? {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels)
        // csd-0：OpusHead —— 没有它多数设备的 Opus 解码器完全无输出
        format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(opusHead(channels)))
        val c = createOpusDecoder() ?: return null
        c.configure(format, null, null, 0)
        c.start()
        return c
    }
}
