package com.selkies.client.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.selkies.client.model.HardwareDevice
import com.selkies.client.platform.AndroidPlatform
import com.selkies.client.platform.MicCapture

/**
 * Android 麦克风采集：AudioRecord 24kHz 单声道 → MediaCodec Opus 编码（0x02 上行）
 * v2.4.0：支持指定设备（setPreferredDevice）与多设备同时采集 PCM 混音为单流。
 */
class AndroidMicCapture : MicCapture {

    private var thread: Thread? = null
    @Volatile
    private var running = false
    @Volatile
    private var wantedDevices: List<String> = emptyList()
    @Volatile
    private var currentDevices: List<String> = emptyList()
    private var onOpus: ((ByteArray) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    override val active: Boolean get() = running

    companion object {
        /** 枚举麦克风设备（内置/外接/蓝牙） */
        fun devices(): List<HardwareDevice> {
            val ctx = runCatching { AndroidPlatform.appCtx() }.getOrNull() ?: return emptyList()
            val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
            return am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_FM_TUNER ||
                            it.type == AudioDeviceInfo.TYPE_TELEPHONY
                }
                .mapIndexed { idx, d ->
                    val kind = when (d.type) {
                        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
                        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机麦克风"
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙麦克风"
                        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 麦克风"
                        else -> "其他输入"
                    }
                    val addr = d.address?.ifBlank { null }
                    HardwareDevice(
                        id = addr ?: "mic-$idx",
                        name = d.productName?.toString()?.ifBlank { kind } ?: kind,
                        type = "mic",
                        detail = kind + (addr?.let { " · $it" } ?: "")
                    )
                }
        }
    }

    @SuppressLint("MissingPermission")
    override fun start(
        deviceIds: List<String>,
        onOpusFrame: (ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        // ⚠️【闪退修复】防重入：权限回调/快速双击/服务器 CAPTURE_DEMAND 并发触发 start
        //    时旧版会开两条采集线程（MediaCodec 竞争 → 部分设备 native crash）。
        synchronized(this) {
            if (running || starting) return
            starting = true
        }
        this.onOpus = onOpusFrame
        this.onError = onError
        this.wantedDevices = deviceIds

        val ctx = runCatching { AndroidPlatform.appCtx() }.getOrNull()
        if (ctx != null &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            AndroidPlatform.ensurePermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    synchronized(this) { starting = false }
                    if (granted) start(deviceIds, onOpusFrame, onError)
                    else onError("未授予麦克风权限")
                }
            }
            return
        }

        synchronized(this) {
            starting = false
            if (running) return
            running = true
        }
        // ⚠️ Thread 启动本身也可能抛（OOM/平台限制）——包一层防闪退
        thread = runCatching {
            Thread(this::loopSafe, "selkies-mic").apply { isDaemon = true; start() }
        }.getOrNull().also {
            if (it == null) {
                running = false
                postError("无法启动采集线程")
            }
        }
    }

    @Volatile
    private var starting = false

    private fun postError(msg: String) {
        val cb = onError ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { cb(msg) } }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        // 等待采集线程退出（最多 800ms），避免快速 stop→start 时双线程竞争 AudioRecord
        val t = thread
        thread = null
        runCatching { t?.join(800) }
    }

    private fun findAudioDeviceInfo(id: String): AudioDeviceInfo? {
        val ctx = runCatching { AndroidPlatform.appCtx() }.getOrNull() ?: return null
        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.address == id || (id.startsWith("mic-") && it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) }
    }

    /** 【闪退修复】loop 外再包一层 Throwable（MediaCodec native 层可抛 Error 而非 Exception） */
    private fun loopSafe() {
        runCatching {
            loop()
        }.onFailure { t ->
            if (running) {
                postError(
                    if (t.message?.contains("opus", true) == true || t is java.lang.IllegalArgumentException)
                        "此设备不支持 Opus 编码（需 Android 10+）" else (t.message ?: t.javaClass.simpleName)
                )
            }
            running = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val sampleRate = 24000
        val frameSize = 480 // 20ms @ 24kHz
        var encoder: MediaCodec? = null
        val records = ArrayList<AudioRecord>()
        try {
            // 打开全部选中的设备（失败则跳过该设备）
            val devices = if (wantedDevices.isEmpty()) listOf(null) else wantedDevices.map { findAudioDeviceInfo(it) }
            var opened = 0
            for (dev in devices) {
                runCatching {
                    val minBuf = AudioRecord.getMinBufferSize(
                        sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                    )
                    val r = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minBuf, frameSize * 2 * 4) * 2
                    )
                    if (r.state == AudioRecord.STATE_INITIALIZED) {
                        if (dev != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            // setPreferredDevice 失败也能录制（回退默认设备）
                            runCatching { r.setPreferredDevice(dev) }
                        }
                        r.startRecording()
                        records.add(r)
                        opened++
                    } else {
                        r.release()
                    }
                }
            }
            if (opened == 0) {
                onError?.invoke("麦克风初始化失败（${if (wantedDevices.isEmpty()) "默认设备" else wantedDevices.joinToString("、")}）")
                return
            }
            currentDevices = wantedDevices

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 32000)
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val enc = encoder!!
            val pcm = ByteArray(frameSize * 2)
            val mixBuf = ShortArray(frameSize)
            val readBuf = ShortArray(frameSize)
            val info = MediaCodec.BufferInfo()
            var ptsUs = 0L
            while (running) {
                // 多设备混音：逐设备读同长帧后平均
                java.util.Arrays.fill(mixBuf, 0)
                var got = 0
                for (r in records) {
                    val n = r.read(readBuf, 0, frameSize, AudioRecord.READ_BLOCKING)
                    if (n == frameSize) {
                        for (i in 0 until frameSize) mixBuf[i] = (mixBuf[i] + readBuf[i] / records.size).toShort()
                        got++
                    }
                }
                if (got == 0) continue
                // ShortArray → LE bytes
                java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(mixBuf)

                var idx = enc.dequeueInputBuffer(50_000)
                if (idx >= 0) {
                    val buf = enc.getInputBuffer(idx)
                    if (buf != null) {
                        buf.clear()
                        buf.put(pcm)
                        enc.queueInputBuffer(idx, 0, pcm.size, ptsUs, 0)
                        ptsUs += frameSize * 1_000_000L / sampleRate
                    }
                }
                var outIdx = enc.dequeueOutputBuffer(info, 0)
                while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIdx >= 0 && info.size > 0) {
                        val outBuf = enc.getOutputBuffer(outIdx)
                        if (outBuf != null) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val opus = ByteArray(info.size)
                            outBuf.get(opus)
                            onOpus?.invoke(opus)
                        }
                        enc.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx = enc.dequeueOutputBuffer(info, 0)
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
            for (r in records) runCatching { r.stop(); r.release() }
            runCatching { encoder?.stop(); encoder?.release() }
        }
    }
}
