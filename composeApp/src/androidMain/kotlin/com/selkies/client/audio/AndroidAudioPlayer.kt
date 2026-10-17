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
 * Android 音频播放：Opus 解码 → 多路 AudioTrack
 *
 * 【v2.7.5·声音无法播放根治】双解码引擎 + 自动切换 + 渲染计量：
 *  1. 主引擎 MediaCodec（c2.android.opus.decoder）——修复三类丢帧缺陷：
 *     ①输入缓冲繁忙时旧版直接丢帧（无声断续/无声）：现在挂起 carry 下轮重试，绝不丢；
 *     ②输出排空超时过短（有输入时 0ms）：解码器还没出帧就被跳过，输出堆积滞塞；
 *     ③primed 计数在空轮询时也会自增，缓冲未真正建立就开播（起播爆音/underrun）。
 *  2. 备用引擎 FFmpeg 软件解码（javacpp android-arm64 原生库，与桌面端同一验证过的代码路径）——
 *     实测存在 MediaCodec Opus 解码器只吃帧不出 PCM 的设备（三版实体机全无声）。
 *     自诊断：送入 ≥25 帧且 2s 内 PCM 输出为 0 → 自动切换 FFmpeg 软解并提示用户；
 *     两者都不可用则每 5s 重建 MediaCodec 重试（设备热插拔/临时故障自愈）。
 *  3. renderedPcmBytes/opusFramesFed 计量暴露给会话层（音频自诊断与统计面板显示）。
 *
 * 【v2.7.5·默认设备】空选择 = 系统默认输出（扬声器 ↔ 耳机自动跟随系统路由，无需手动选择）；
 *  明确多选时才按设备路由（setPreferredDevice）。设备热插拔自动重建输出。
 *
 * 【v2.7.6 并入】三重加固：
 *  A. 解码器按可靠度创建：c2.android.opus.decoder（软件标准）→ OMX.google.opus.decoder
 *     （旧软件）→ createDecoderByType 兜底（createDecoderByType 可能选中厂商坏「硬件」Opus 解码器）。
 *  B. 单帧/解码器异常只丢帧+自动重建（最多 5 次），不再让整条音频线程死亡。
 *  C. 输出重建 2s 退避（防每帧 new AudioTrack 风暴）。
 *  D. bufferLevelMs = 20ms/帧（服务器 audio_frame_duration_ms 默认 20，旧版误写 10）。
 */
class AndroidAudioPlayer : AudioPlayer {

    private var codec: MediaCodec? = null
    private var thread: Thread? = null
    @Volatile
    private var running = false
    @Volatile
    private var volume = 1.0f

    override var onError: ((String) -> Unit)? = null

    /** 期望输出 deviceId → 增益；空 = 系统默认（扬声器/耳机自动路由） */
    @Volatile
    private var wantedOutputs: Map<String, Float> = emptyMap()

    private val pending = ConcurrentLinkedQueue<ByteArray>()
    private var sampleRate = 48000
    private var channels = 2

    // ---- v2.7.5：解码引擎状态与计量 ----
    @Volatile private var useFfmpeg = false
    @Volatile private var ffmpegDecoder: Any? = null   // AndroidFfmpegOpusDecoder（反射隔离：无原生库时类加载不炸）
    @Volatile private var ffmpegFailed = false          // 软解初始化/解码失败（不再重试）
    @Volatile private var mcRecreateAt = 0L             // MediaCodec 重建节流
    // v2.7.6 并入：解码器容错重建计数（单帧异常不再杀死音频线程）
    private var decoderRebuilds = 0
    private val maxDecoderRebuilds = 5
    @Volatile private var startedAt = 0L
    @Volatile private var renderedPcm = 0L
    @Volatile private var framesFed = 0L

    override val renderedPcmBytes: Long get() = renderedPcm
    override val opusFramesFed: Long get() = framesFed
    override val decoderDescription: String
        get() = runCatching {
            when {
                useFfmpeg -> "FFmpeg 软件解码"
                codec != null -> "MediaCodec 硬件解码"
                else -> "未启动"
            }
        }.getOrDefault("未知")

    /** 一路输出：AudioTrack + 设备 + 增益 */
    private class Out(val deviceId: String, val track: AudioTrack, @Volatile var gain: Float)

    private val outs = ArrayList<Out>()
    private var outsSig = ""
    private var audioManager: AudioManager? = null
    private var deviceCallback: AudioDeviceCallback? = null

    override val bufferLevelMs: Int
        get() = pending.size * 20   // v2.7.6：服务器 audio_frame_duration_ms 默认 20ms

    override fun start(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate
        this.channels = channels.coerceIn(1, 2)
        if (running) return
        running = true
        renderedPcm = 0L
        framesFed = 0L
        startedAt = System.currentTimeMillis()
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
        runCatching { (ffmpegDecoder as? AndroidFfmpegOpusDecoder)?.close() }
        ffmpegDecoder = null
        useFfmpeg = false
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
                t.preferredDevice = preferredDev
            }
            t.setVolume(volume)
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

    /** 按期望输出重建 AudioTrack 集合（空 = 系统默认——扬声器/耳机自动跟随系统） */
    private fun rebuildOuts(sr: Int, ch: Int) {
        synchronized(outs) {
            outs.forEach { runCatching { it.track.stop(); it.track.release() } }
            outs.clear()
            val wanted = wantedOutputs
            if (wanted.isEmpty()) {
                val t = makeTrack(sr, ch, null)
                if (t != null) {
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

    /** v2.7.6 并入：输出重建 2s 退避（防每帧 new AudioTrack 风暴） */
    @Volatile private var lastOutsRebuildAt = 0L

    /** 输出集合维护（解码线程独占；wantedOutputs/采样率/声道变化时重建） */
    private fun ensureOuts(sr: Int, ch: Int): Boolean {
        val sig = "$sr x $ch | " +
                wantedOutputs.entries.sortedBy { it.key }.joinToString("|") { (k, v) -> "$k=${"%.2f".format(v)}" }
        if (sig != outsSig || outs.isEmpty()) {
            if (outs.isEmpty() && System.currentTimeMillis() - lastOutsRebuildAt < 2000) {
                return false  // 全失败后的退避（1 次/2s）
            }
            rebuildOuts(sr, ch)
            lastOutsRebuildAt = System.currentTimeMillis()
            outsSig = if (outs.isNotEmpty()) sig else ""
        }
        return outs.isNotEmpty()
    }

    /** PCM 写入全部输出（异常路剔除并反馈；计量已在解码产出处累计） */
    private fun writeOut(pcm: ByteArray) {
        for (o in outs.toList()) {
            try {
                o.track.write(pcm, 0, pcm.size)
            } catch (e: Exception) {
                runCatching { o.track.stop(); o.track.release() }
                synchronized(outs) { outs.remove(o) }
                onError?.invoke("音频输出 ${o.deviceId.ifBlank { "默认设备" }} 异常（${e.javaClass.simpleName}），已移除")
            }
        }
        if (outs.isEmpty()) outsSig = ""
    }

    /** v2.7.5：初始化 FFmpeg 软件解码器（反射隔离：原生库缺失时只置 ffmpegFailed，不影响主流程） */
    private fun tryInitFfmpeg(): Boolean {
        if (ffmpegFailed || ffmpegDecoder != null) return ffmpegDecoder != null
        return runCatching {
            val d = AndroidFfmpegOpusDecoder()
            if (!d.open()) {
                ffmpegFailed = true
                d.close()
                false
            } else {
                ffmpegDecoder = d
                true
            }
        }.getOrDefault(false)
    }

    private fun loop() {
        runCatching {
            // AudioManager + 设备热插拔监听（v2.7.5：新增设备到达也触发输出重建——
            // 插耳机/连蓝牙后自动切换到新默认路由；空选择 = 跟随系统无需干预）
            val ctx = com.selkies.client.platform.AndroidPlatform.appCtx()
            audioManager = ctx?.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager
            deviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    outsSig = ""
                }
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    outsSig = ""
                }
            }
            audioManager?.registerAudioDeviceCallback(deviceCallback, android.os.Handler(android.os.Looper.getMainLooper()))

            // ---- 主引擎：MediaCodec Opus ----
            var mcReady = false
            // v2.7.6 并入：按可靠度顺序创建（c2 软件 → OMX 旧软件 → 按类型兜底）——
            // createDecoderByType 可能选中厂商注册但实际无输出的 Opus「硬件」解码器
            fun createOpusDecoder(): MediaCodec? {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    runCatching { MediaCodec.createByCodecName("c2.android.opus.decoder") }.getOrNull()?.let { return it }
                }
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    runCatching { MediaCodec.createByCodecName("OMX.google.opus.decoder") }.getOrNull()?.let { return it }
                }
                return runCatching { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS) }.getOrNull()
            }
            fun ensureMediaCodec(): Boolean {
                if (mcReady && codec != null) return true
                runCatching { codec?.stop(); codec?.release() }
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels)
                // csd-0：OpusHead —— 没有它多数设备的 Opus 解码器完全无输出
                format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(opusHead(channels)))
                val c = createOpusDecoder() ?: return false
                c.configure(format, null, null, 0)
                c.start()
                codec = c
                mcReady = true
                return true
            }
            ensureMediaCodec()
            val info = MediaCodec.BufferInfo()
            // v2.7.5：输入繁忙挂起帧（绝不丢——丢帧=断续/无声）
            var carry: ByteArray? = null
            // 抖动缓冲：真正积累 4 帧才开播（修复旧版 primed 空轮询自增缺陷）
            var primed = 0
            var lastDiagAt = System.currentTimeMillis()

            while (running) {
                val opus = carry ?: if (primed < 4) {
                    if (pending.size >= 4 || primed > 0) pending.poll()?.also { primed++ } else null
                } else pending.poll()
                carry = null

                if (opus != null) {
                    framesFed++
                    if (useFfmpeg) {
                        val d = runCatching { ffmpegDecoder as? AndroidFfmpegOpusDecoder }.getOrNull()
                        if (d != null) {
                            val pcm = runCatching { d.decode(opus) }.getOrNull()
                            if (pcm != null && pcm.isNotEmpty()) {
                                // v2.7.5：计量计在解码产出（设备无关——自动切换判定不受输出设备影响）
                                renderedPcm += pcm.size
                                if (d.sampleRate != sampleRate || d.channels != channels) {
                                    sampleRate = d.sampleRate
                                    channels = d.channels
                                    outsSig = ""
                                }
                                if (ensureOuts(sampleRate, channels)) writeOut(pcm)
                            } else {
                                // 软解开小差（个别帧损坏）：跳过，下帧继续
                            }
                        } else {
                            useFfmpeg = false // 软解已失效 → 回主引擎
                        }
                    } else {
                        // v2.7.6 并入：单帧异常只丢帧+重建（最多5次），不让音频线程死亡
                        try {
                            val c = codec
                            if (c != null) {
                                val idx = c.dequeueInputBuffer(50_000) // 50ms
                                if (idx >= 0) {
                                    val buf = c.getInputBuffer(idx)
                                    if (buf != null) {
                                        buf.clear()
                                        buf.put(opus)
                                        c.queueInputBuffer(idx, 0, opus.size, System.nanoTime() / 1000, 0)
                                    }
                                } else {
                                    // v2.7.5：输入缓冲繁忙 → 挂起下轮重试（旧版直接丢帧 = 断续根因）
                                    carry = opus
                                    framesFed--
                                }
                            }
                        } catch (e: Exception) {
                            carry = null
                            if (decoderRebuilds < maxDecoderRebuilds) {
                                decoderRebuilds++
                                runCatching { codec?.stop(); codec?.release() }
                                codec = null
                                mcReady = false
                                ensureMediaCodec()
                            }
                        }
                    }
                }

                // MediaCodec 输出排空（v2.7.5：有输入时给 2ms 而非 0ms——解码器需要喘息出帧；
                //  v2.7.6 并入：排空异常同样容错重建，不杀线程）
                if (!useFfmpeg && codec != null) {
                    val c = codec!!
                    var outIdx = try {
                        c.dequeueOutputBuffer(info, if (carry != null || opus != null) 2_000 else 10_000)
                    } catch (e: Exception) {
                        if (decoderRebuilds < maxDecoderRebuilds) {
                            decoderRebuilds++
                            runCatching { codec?.stop(); codec?.release() }
                            codec = null
                            mcReady = false
                        }
                        -1
                    }
                    while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            // 解码器实际输出格式（声道/采样率）可能与配置不同：跟随并重配输出
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
                                val pcm = ByteArray(info.size)
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                outBuf.get(pcm)
                                // v2.7.5：计量计在解码产出（设备无关——自动切换判定不受输出设备影响）
                                renderedPcm += pcm.size
                                if (ensureOuts(sampleRate, channels)) writeOut(pcm)
                            }
                        }
                        runCatching { c.releaseOutputBuffer(outIdx, false) }
                        outIdx = runCatching { c.dequeueOutputBuffer(info, 0) }.getOrDefault(-1)
                    }
                }

                // ---- v2.7.5：自诊断（每 500ms）——帧在到达但 MediaCodec 无 PCM 输出 ----
                val now = System.currentTimeMillis()
                if (now - lastDiagAt > 500) {
                    lastDiagAt = now
                    if (!useFfmpeg && framesFed >= 25 && renderedPcm == 0L &&
                        now - startedAt > 2000 && carry == null
                    ) {
                        if (!ffmpegFailed && tryInitFfmpeg()) {
                            useFfmpeg = true
                            runCatching { codec?.stop(); codec?.release() }
                            codec = null
                            mcReady = false
                            onError?.invoke("系统音频解码器无输出，已切换内置 FFmpeg 软件解码")
                        } else if (now - mcRecreateAt > 5000) {
                            // 两者都不可用：定期重建 MediaCodec（设备临时故障自愈）
                            mcRecreateAt = now
                            runCatching { codec?.stop(); codec?.release() }
                            codec = null
                            mcReady = false
                            ensureMediaCodec()
                        }
                    }
                }

                if (carry == null && opus == null) Thread.sleep(4)
            }
        }.onFailure {
            running = false
            onError?.invoke("音频播放器错误：${it.message ?: it.javaClass.simpleName}")
        }
        runCatching { codec?.stop(); codec?.release() }
        codec = null
    }
}
