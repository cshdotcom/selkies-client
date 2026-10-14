package com.selkies.client.webcam

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import com.selkies.client.model.HardwareDevice
import com.selkies.client.platform.AndroidPlatform
import com.selkies.client.platform.WebcamCapture
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor

/**
 * Android 摄像头上行：CameraX ImageAnalysis (YUV_420_888) → NV12 → MediaCodec H.264 → 0x06 帧
 * v2.4.0：支持按 cameraId 选择设备（前置/后置/外接多摄）、分辨率可配、编码器可配。
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
    @Volatile
    private var camW = 1280
    @Volatile
    private var camH = 720

    override val active: Boolean get() = running
    override val width: Int get() = camW
    override val height: Int get() = camH

    private val pendingFrames = java.util.concurrent.LinkedBlockingQueue<Pair<ByteArray, Long>>(4)

    companion object {
        /** 枚举摄像头（Camera2 API：含前后/外接与朝向/等级信息） */
        fun devices(): List<HardwareDevice> {
            val ctx = runCatching { AndroidPlatform.appCtx() }.getOrNull() ?: return emptyList()
            val cm = ctx.getSystemService(android.content.Context.CAMERA_SERVICE) as? CameraManager ?: return emptyList()
            return runCatching {
                cm.cameraIdList.mapIndexedNotNull { idx, id ->
                    val ch = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: return@mapIndexedNotNull null
                    val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "前置"
                        CameraCharacteristics.LENS_FACING_BACK -> "后置"
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "外接"
                        else -> "未知朝向"
                    }
                    val level = when (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "Legacy"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "Limited"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "Full"
                        3 -> "Level-3"   // INFO_SUPPORTED_HARDWARE_LEVEL_3
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "External"
                        else -> ""
                    }
                    // 物理多摄（logical camera）标注
                    val physical = ch.physicalCameraIds
                    val physNote = if (physical.isNotEmpty()) " · 逻辑多摄(${physical.size})" else ""
                    HardwareDevice(
                        id = id,
                        name = "${facing}摄像头" + if (idx > 1) " ${idx + 1}" else "",
                        type = "camera",
                        detail = facing + (level.takeIf { it.isNotBlank() }?.let { " · $level" } ?: "") + physNote
                    )
                }
            }.getOrDefault(emptyList())
        }
    }

    override fun start(
        encoder: String,
        deviceId: String,
        targetW: Int,
        targetH: Int,
        onFrame: (codecId: Int, keyframe: Boolean, data: ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        if (running) return
        this.onFrame = onFrame
        camW = targetW.coerceIn(160, 1920)
        camH = targetH.coerceIn(120, 1080)

        val ctx = runCatching { AndroidPlatform.appCtx() }.getOrNull()
        if (ctx != null &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            AndroidPlatform.ensurePermissions(listOf(Manifest.permission.CAMERA)) { granted ->
                if (granted) start(encoder, deviceId, targetW, targetH, onFrame, onError) else onError("未授予摄像头权限")
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

                // 【v2.6.0】多摄像头：并发相机（ConcurrentCamera，上限 2 路）各路独立 ImageAnalysis → Bitmap 级拼接 → MJPEG 上行
                // CameraX 1.3.4 无公开的能力探测 API：直接尝试绑定，UnsupportedOperationException = 设备不支持 → 回退单摄
                val deviceIds = deviceId.split(',').map { it.trim() }.filter { it.isNotBlank() }
                if (deviceIds.size >= 2 && android.os.Build.VERSION.SDK_INT >= 30) {
                    val bound = runCatching {
                        startConcurrentCameras(activity, cameraProvider, deviceIds.take(2), onFrame, onError)
                        true
                    }.getOrDefault(false)
                    if (bound) return@runCatching
                    onError("此设备不支持同时开启多摄像头，已使用第一个所选摄像头")
                }

                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(camW, camH))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor!!) { image ->
                    processImage(image)
                }

                cameraProvider.unbindAll()
                // 设备选择：按朝向绑定；后置不存在时回退前置
                val selector = buildSelector(deviceIds.firstOrNull() ?: "")
                try {
                    cameraProvider.bindToLifecycle(activity, selector, analysis)
                } catch (e: Exception) {
                    cameraProvider.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                }
                startEncoderLoop(encoder, onError)
            }.onFailure { onError("摄像头启动失败：${it.message}") }
        }, ContextCompat.getMainExecutor(ctx ?: return))
    }

    /** v2.6.0 并发相机多路合成：每路 ImageAnalysis → 最新 NV12 → Bitmap 拼接 → JPEG 上行（0x06 codec 0）
     *  服务器虚拟摄像头只有一路，多选必须在客户端合成（与麦克风多路混音同思路）。 */
    private fun startConcurrentCameras(
        activity: LifecycleOwner,
        cameraProvider: ProcessCameraProvider,
        deviceIds: List<String>,
        onFrame: (Int, Boolean, ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        class CamSlot {
            @Volatile var nv12: ByteArray? = null
            @Volatile var w = 0
            @Volatile var h = 0
        }
        val slots = deviceIds.map { CamSlot() }

        cameraProvider.unbindAll()
        val configs = ArrayList<androidx.camera.core.ConcurrentCamera.SingleCameraConfig>()
        for ((i, id) in deviceIds.withIndex()) {
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(camW, camH))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val slot = slots[i]
            analysis.setAnalyzer(analysisExecutor!!) { image ->
                try {
                    if (running) {
                        val nv12 = toNv12(image)
                        if (nv12 != null) {
                            slot.nv12 = nv12
                            slot.w = image.width
                            slot.h = image.height
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    image.close()
                }
            }
            val selector = try {
                buildSelector(id)
            } catch (_: Exception) {
                if (i == 0) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            }
            val useCaseGroup = androidx.camera.core.UseCaseGroup.Builder().addUseCase(analysis).build()
            configs.add(
                androidx.camera.core.ConcurrentCamera.SingleCameraConfig(selector, useCaseGroup, activity)
            )
        }
        // 不支持并发相机的设备在此抛 UnsupportedOperationException / IllegalArgumentException
        cameraProvider.bindToLifecycle(configs)

        // 合成线程：Bitmap 级横向拼接 → JPEG
        encoderThread = Thread({
            runCatching {
                val quality = 80
                while (running) {
                    val frames = slots.mapNotNull { s -> s.nv12?.let { Triple(it, s.w, s.h) } }
                    if (frames.isEmpty()) {
                        Thread.sleep(66)
                        continue
                    }
                    val bitmaps = frames.mapNotNull { (nv12, w, h) -> nv12ToBitmap(nv12, w, h) }
                    if (bitmaps.isNotEmpty()) {
                        val h = bitmaps.maxOf { it.height }
                        val widths = bitmaps.map { (it.width.toFloat() / it.height * h).toInt().coerceAtLeast(16) }
                        val composite = android.graphics.Bitmap.createBitmap(widths.sum(), h, android.graphics.Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(composite)
                        var x = 0
                        for ((i, bmp) in bitmaps.withIndex()) {
                            canvas.drawBitmap(bmp, null, android.graphics.Rect(x, 0, x + widths[i], h), null)
                            x += widths[i]
                            bmp.recycle()
                        }
                        camW = composite.width
                        camH = composite.height
                        val out = ByteArrayOutputStream()
                        composite.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
                        composite.recycle()
                        onFrame(0, true, out.toByteArray())
                    }
                    Thread.sleep(80) // ~12fps
                }
            }.onFailure {
                if (running) onError("多摄像头合成失败：${it.message}")
            }
            running = false
        }, "selkies-webcam-composite").apply { isDaemon = true; start() }
    }

    /** ImageProxy → NV12（并发多摄用；返回 null 表示无效帧） */
    private fun toNv12(image: ImageProxy): ByteArray? {
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0) return null
        val ySize = w * h
        val nv12 = ByteArray(ySize + ySize / 2)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        var pos = 0
        val yBuf = yPlane.buffer
        for (row in 0 until h) {
            yBuf.position(row * yPlane.rowStride)
            yBuf.get(nv12, pos, w)
            pos += w
        }
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
        return nv12
    }

    /** NV12 → Bitmap（NV21 → YuvImage → JPEG → decode） */
    private fun nv12ToBitmap(nv12: ByteArray, w: Int, h: Int): android.graphics.Bitmap? {
        return runCatching {
            val ySize = w * h
            val nv21 = nv12.copyOf()
            var i = 0
            while (i < ySize / 2 - 1) {
                val tmp = nv21[ySize + i]
                nv21[ySize + i] = nv21[ySize + i + 1]
                nv21[ySize + i + 1] = tmp
                i += 2
            }
            val yuv = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, w, h, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(android.graphics.Rect(0, 0, w, h), 82, out)
            android.graphics.BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
        }.getOrNull()
    }

    /** deviceId → CameraSelector（按朝向选择；CameraX 不支持直接按 camera2 id 绑定） */
    private fun buildSelector(deviceId: String): CameraSelector {
        if (deviceId.equals("front", true)) return CameraSelector.DEFAULT_FRONT_CAMERA
        if (deviceId.equals("external", true)) {
            return CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_EXTERNAL).build()
        }
        if (deviceId.isNotBlank() && deviceId.all { it.isDigit() }) {
            // 指定 id：查其朝向后选择（同朝向多摄由 CameraX 决定具体打开哪颗）
            if (isFacing(deviceId, CameraCharacteristics.LENS_FACING_FRONT)) {
                return CameraSelector.DEFAULT_FRONT_CAMERA
            }
            if (isFacing(deviceId, CameraCharacteristics.LENS_FACING_BACK)) {
                return CameraSelector.DEFAULT_BACK_CAMERA
            }
        }
        return CameraSelector.DEFAULT_BACK_CAMERA
    }

    private fun isFacing(cameraId: String, facing: Int): Boolean {
        val ctx = runCatching { AndroidPlatform.appCtx() }.getOrNull() ?: return false
        val cm = ctx.getSystemService(android.content.Context.CAMERA_SERVICE) as? CameraManager ?: return false
        return runCatching {
            cm.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING) == facing
        }.getOrDefault(false)
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
            camW = w
            camH = h
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
            pendingFrames.offer(nv12 to System.nanoTime() / 1000)
            if (pendingFrames.size > 3) pendingFrames.poll()
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    private fun startEncoderLoop(requestedEncoder: String, onError: (String) -> Unit) {
        encoderThread = Thread({
            runCatching {
                val useMime = when {
                    requestedEncoder.equals("mjpeg", true) || requestedEncoder.equals("jpeg", true) -> ""
                    requestedEncoder.equals("h265", true) || requestedEncoder.equals("hevc", true) -> MediaFormat.MIMETYPE_VIDEO_HEVC
                    requestedEncoder.equals("vp8", true) -> MediaFormat.MIMETYPE_VIDEO_VP8
                    requestedEncoder.equals("vp9", true) -> MediaFormat.MIMETYPE_VIDEO_VP9
                    else -> MediaFormat.MIMETYPE_VIDEO_AVC
                }
                if (useMime.isEmpty()) {
                    // JPEG 软编码路径：YUV→JPEG（Bitmap 压缩）走 0x06 codec 0
                    startJpegLoop(onError)
                    return@runCatching
                }
                val format = MediaFormat.createVideoFormat(useMime, camW, camH)
                format.setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 20)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                val enc = MediaCodec.createEncoderByType(useMime)
                enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                enc.start()
                encoder = enc
                val codecId = when (useMime) {
                    MediaFormat.MIMETYPE_VIDEO_HEVC -> 5
                    MediaFormat.MIMETYPE_VIDEO_VP8 -> 2
                    MediaFormat.MIMETYPE_VIDEO_VP9 -> 3
                    else -> 1
                }

                val info = MediaCodec.BufferInfo()
                val spsPps = ByteArrayOutputStream()
                var sentCsd = false
                while (running) {
                    val frame = pendingFrames.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    var idx = enc.dequeueInputBuffer(50_000)
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
                                    onFrame?.invoke(codecId, true, spsPps.toByteArray())
                                    sentCsd = true
                                }
                                onFrame?.invoke(codecId, isKey, chunk)
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

    /** JPEG 上行循环（服务器 MJPEG 透传：pixel_format=auto 时设备为 MJPEG） */
    private fun startJpegLoop(onError: (String) -> Unit) {
        var quality = 80
        while (running) {
            val frame = pendingFrames.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
            runCatching {
                val w = camW; val h = camH
                // NV12(UV) → NV21(VU)：YuvImage 要求 NV21
                val nv12 = frame.first
                val ySize = w * h
                val nv21 = nv12.copyOf()
                var i = 0
                while (i < ySize / 2 - 1) {
                    val tmp = nv21[ySize + i]
                    nv21[ySize + i] = nv21[ySize + i + 1]
                    nv21[ySize + i + 1] = tmp
                    i += 2
                }
                val yuvImage = android.graphics.YuvImage(
                    nv21, android.graphics.ImageFormat.NV21, w, h, null
                )
                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(android.graphics.Rect(0, 0, w, h), quality, out)
                onFrame?.invoke(0, true, out.toByteArray())
            }.onFailure {
                if (running) { onError("JPEG 编码失败：${it.message}"); running = false }
            }
        }
    }
}
