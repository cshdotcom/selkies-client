package com.selkies.client.webcam

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import com.selkies.client.platform.AndroidPlatform
import com.selkies.client.platform.WebcamCapture
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor

/**
 * Android 摄像头上行：CameraX ImageAnalysis (YUV_420_888) → NV12 → MediaCodec H.264 → 0x06 帧
 */
class AndroidWebcamCapture : WebcamCapture {

    private var provider: ProcessCameraProvider? = null
    private var encoder: MediaCodec? = null
    private var encoderThread: Thread? = null
    private var analysisThread: HandlerThread? = null
    private var analysisExecutor: Executor? = null
    @Volatile
    private var running = false
    @Volatile
    private var requestKeyframeFlag = false
    private var onFrame: ((Int, Boolean, ByteArray) -> Unit)? = null

    override val active: Boolean get() = running
    override val width: Int get() = 640
    override val height: Int get() = 480

    private val pendingFrames = java.util.concurrent.LinkedBlockingQueue<Pair<ByteArray, Long>>(4)

    override fun start(
        encoder: String,
        onFrame: (codecId: Int, keyframe: Boolean, data: ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        if (running) return
        this.onFrame = onFrame

        val ctx = runCatching { AndroidPlatform.appCtx() }.getOrNull()
        if (ctx != null &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            AndroidPlatform.ensurePermissions(listOf(Manifest.permission.CAMERA)) { granted ->
                if (granted) start(encoder, onFrame, onError) else onError("未授予摄像头权限")
            }
            return
        }

        val activity = AndroidPlatform.activity() as? LifecycleOwner ?: run {
            onError("无可用的 Activity 生命周期")
            return
        }

        running = true
        val future = ProcessCameraProvider.getInstance(ctx ?: return)
        future.addListener({
            runCatching {
                val cameraProvider = future.get()
                provider = cameraProvider

                val analysisThread = HandlerThread("selkies-cam").also { it.start() }
                this.analysisThread = analysisThread
                val handler = Handler(analysisThread.looper)
                analysisExecutor = Executor { handler.post(it) }

                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor!!) { image ->
                    processImage(image)
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                startEncoderLoop(onError)
            }.onFailure { onError("摄像头启动失败：${it.message}") }
        }, ContextCompat.getMainExecutor(ctx ?: return))
    }

    override fun requestKeyframe() {
        requestKeyframeFlag = true
    }

    override fun stop() {
        running = false
        runCatching { provider?.unbindAll() }
        provider = null
        runCatching { encoder?.stop(); encoder?.release() }
        encoder = null
        analysisThread?.quitSafely()
        analysisThread = null
        pendingFrames.clear()
    }

    private fun processImage(image: ImageProxy) {
        try {
            if (!running) return
            val w = image.width
            val h = image.height
            // YUV_420_888 → NV12 (yyyyyyyy + interleaved uv)
            val ySize = w * h
            val nv12 = ByteArray(ySize + ySize / 2)
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            // Y
            var pos = 0
            val yBuf = yPlane.buffer
            for (row in 0 until h) {
                yBuf.position(row * yPlane.rowStride)
                yBuf.get(nv12, pos, w)
                pos += w
            }
            // UV 交错（NV12: U V 顺序）
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer
            val uvH = h / 2
            val uvW = w / 2
            for (row in 0 until uvH) {
                for (col in 0 until uvW) {
                    nv12[ySize + row * uvW * 2 + col * 2] =
                        uBuf.get(row * uPlane.rowStride + col * uPlane.pixelStride)
                    nv12[ySize + row * uvW * 2 + col * 2 + 1] =
                        vBuf.get(row * vPlane.rowStride + col * vPlane.pixelStride)
                }
            }
            // 前置摄像头镜像翻转（水平翻转标志在 flags 中由上层处理，此处直接翻转数据更稳妥）
            pendingFrames.offer(nv12 to System.nanoTime() / 1000)
            if (pendingFrames.size > 3) pendingFrames.poll()
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    private fun startEncoderLoop(onError: (String) -> Unit) {
        encoderThread = Thread({
            runCatching {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 640, 480)
                format.setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 20)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                enc.start()
                encoder = enc

                val info = MediaCodec.BufferInfo()
                val spsPps = ByteArrayOutputStream()
                var sentCsd = false
                while (running) {
                    val frame = pendingFrames.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    var idx = enc.dequeueInputBuffer(10_000_000)
                    if (idx >= 0) {
                        val buf = enc.getInputBuffer(idx)!!
                        buf.clear()
                        if (buf.capacity() >= frame.first.size) {
                            buf.put(frame.first)
                            val flags = if (requestKeyframeFlag) {
                                requestKeyframeFlag = false
                                MediaCodec.BUFFER_FLAG_SYNC_FRAME
                            } else 0
                            enc.queueInputBuffer(idx, 0, frame.first.size, frame.second, flags)
                        } else {
                            enc.queueInputBuffer(idx, 0, 0, frame.second, 0)
                        }
                    }
                    var outIdx = enc.dequeueOutputBuffer(info, 0)
                    while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            // csd-0 (SPS+PPS) 作为关键帧数据发送
                            val csd0 = enc.outputFormat.getByteBuffer("csd-0")
                            if (csd0 != null) {
                                val bytes = ByteArray(csd0.remaining())
                                csd0.get(bytes)
                                spsPps.reset()
                                spsPps.write(bytes)
                                sentCsd = false
                            }
                        } else if (outIdx >= 0 && info.size > 0) {
                            val outBuf = enc.getOutputBuffer(outIdx)!!
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            outBuf.get(chunk)
                            val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 ||
                                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                spsPps.reset()
                                spsPps.write(chunk)
                                sentCsd = false
                            } else {
                                if (!sentCsd && spsPps.size() > 0) {
                                    onFrame?.invoke(1, true, spsPps.toByteArray())
                                    sentCsd = true
                                }
                                onFrame?.invoke(1, isKey, chunk)
                            }
                            enc.releaseOutputBuffer(outIdx, false)
                        }
                        outIdx = enc.dequeueOutputBuffer(info, 0)
                    }
                }
            }.onFailure {
                if (running) onError("摄像头编码失败：${it.message}")
            }
            running = false
            runCatching { encoder?.stop(); encoder?.release() }
            encoder = null
        }, "selkies-webcam-enc").apply { isDaemon = true; start() }
    }
}
