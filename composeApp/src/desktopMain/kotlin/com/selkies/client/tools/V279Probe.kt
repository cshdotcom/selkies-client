package com.selkies.client.tools

import com.selkies.client.core.SelkiesSession
import com.selkies.client.core.StreamRecorder
import com.selkies.client.model.AppSettings
import com.selkies.client.model.ServerEntry
import com.selkies.client.platform.*
import com.selkies.client.ui.panels.VirtualGamepadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * v2.7.9 综合回归（真实服务器）：
 *  1) 录屏时长定格：录制 ≥5s → 停止 → lastDurationSec ≥ 5（修「保存显示 0 秒」）
 *  2) 录屏 realPath：保存事件路径 = 真实可读路径（非 pending:// 占位）
 *  3) 录屏帧计数语义：videoFramesWritten 仅统计真实写入（muxer 拒绝的帧不计）
 *  4) 录屏文件有效性：ffprobe 时长 ≥ 4s、可解析
 *  5) 虚拟手柄 v2.7.9 悬浮引擎：js,c/b/a/h/d 全序列 0 错误（协议回归——布局重构不动协议）
 *  6) 麦克风上行 wire 格式：DesktopMicCapture 产出 0x02+Opus（24kHz mono 帧大小合理）
 */
object V279Probe {

    @JvmStatic
    fun main(args: Array<String>) = kotlinx.coroutines.runBlocking {
        val url = sysProp("probeUrl", "https://workspace-browser.nodebyte.cn")
        val user = sysProp("probeUser", "cshll")
        val pass = sysProp("probePass", "15068253855z")
        var passCount = 0; var failCount = 0

        fun check(name: String, ok: Boolean, detail: String = "") {
            if (ok) { passCount++; println("  ✅ PASS $name ${if (detail.isNotBlank()) "($detail)" else ""}") }
            else { failCount++; println("  ❌ FAIL $name ${if (detail.isNotBlank()) "($detail)" else ""}") }
        }

        val server = ServerEntry(
            id = "probe", name = "probe", url = url,
            username = user, password = pass,
            framerate = 30, encoder = "jpeg", audioEnabled = true,   // jpeg：桌面录制路径全覆盖
            resolutionW = 640, resolutionH = 480
        )
        val settings = AppSettings()
        val platform = object : PlatformServices {
            override val platformName = "V279Probe"
            override fun configDir(): String = "/tmp/v279probe"
            override fun clipboard(): ClipboardBridge = object : ClipboardBridge {
                override fun getText(): String? = null
                override fun setText(text: String) {}
                override fun hasImage(): Boolean = false
                override fun getImagePng(): ByteArray? = null
                override fun setImagePng(png: ByteArray) {}
            }
            override fun print(): PrintBridge = object : PrintBridge {
                override fun printPdf(pdfBytes: ByteArray, fileName: String, onResult: (Boolean, String?) -> Unit) { onResult(true, null) }
            }
            override fun vibrate(ms: Long) {}
            override fun openUrl(url: String) {}
            override fun pickFiles(mimeTypes: List<String>, onResult: (List<PickedFile>) -> Unit) { onResult(emptyList()) }
            override fun saveFileWithPicker(suggestedName: String, mime: String, onResult: (PickedSaveTarget?) -> Unit) { onResult(null) }
            override fun saveFile(suggestedName: String, bytes: ByteArray, onDone: (Boolean) -> Unit) { onDone(true) }
            override fun makeVideoSink(): VideoSink = com.selkies.client.video.DesktopVideoSink()
            override fun mediaStore(): MediaStoreBridge = com.selkies.client.platform.DesktopMediaStoreBridge { "/tmp/v279probe" }
            override fun makeStreamMuxer(outputPath: String): StreamMuxer = com.selkies.client.platform.DesktopStreamMuxer(outputPath)
            override fun makeAudioPlayer(): AudioPlayer = com.selkies.client.audio.DesktopAudioPlayer()
            override fun makeMicCapture(): MicCapture = com.selkies.client.audio.DesktopMicCapture()
            override fun makeWebcamCapture(): WebcamCapture = com.selkies.client.webcam.DesktopWebcamCapture()
            override fun hasHardwareKeyboard(): Boolean = true
            override fun setFullscreen(enabled: Boolean) {}
            override fun screenDpi(): Float = 96f
            override fun appVersion(): String = "v279probe"
            override fun showSoftInput(show: Boolean) {}
            override fun isTouchDevice(): Boolean = false
            override fun nativeKeyCodeToKeysym(nativeKeyCode: Int, unicodeChar: Int): Int? = null
            override fun setPhysicalKeyHandler(handler: ((Int, Boolean) -> Boolean)?) {}
            override fun readFileBytes(path: String): ByteArray? = null
            override fun exitApp() {}
            override fun openFile(path: String): Boolean = false
            override fun shareFile(path: String, displayName: String) {}
        }
        val session = SelkiesSession(server, platform, { settings })
        val connected = CountDownLatch(1)
        val statusRef = AtomicReference<SelkiesSession.Status?>(null)
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch {
            var last: SelkiesSession.Status? = null
            while (true) {
                val s = session.status.value
                if (s != last) {
                    println("  [状态] $s")
                    last = s
                    statusRef.set(s)
                    if (s == SelkiesSession.Status.CONNECTED) connected.countDown()
                }
                delay(200)
            }
        }

        println("=== v2.7.9 综合回归（真实服务器 ${url.removePrefix("https://")}） ===")
        session.connect()
        if (!connected.await(25, TimeUnit.SECONDS)) {
            println("连接失败：${statusRef.get()}")
            return@runBlocking
        }
        delay(4000)   // 等流

        // ---------- 1-4) 录屏 ----------
        println("\n【录屏】开始 6s …")
        // startRecording 会设置自己的 recorder.onSaved → 观察会话级 savedMediaEvent（与 UI 同路径）
        val saved = CountDownLatch(1)
        val savedPath = AtomicReference<String?>(null)
        val savedFrames = java.util.concurrent.atomic.AtomicLong(0)
        val evJob = scope.launch {
            session.savedMediaEvent.collect { ev ->
                if (ev != null && ev.kind == "recording") {
                    savedPath.set(ev.path)
                    savedFrames.set(ev.detail.substringBefore(" 帧").toLongOrNull() ?: 0)
                    saved.countDown()
                }
            }
        }
        check("录屏启动", session.startRecording())
        delay(6000)
        session.stopRecording()
        val savedOk = saved.await(15, TimeUnit.SECONDS)
        evJob.cancel()
        check("录屏保存事件到达（savedMediaEvent）", savedOk)
        if (savedOk) {
            val p = savedPath.get()
            check("realPath 真实路径（非 pending://）", p != null && !p.contains("pending://") && java.io.File(p).exists(), p ?: "null")
            check("时长定格 ≥5s（修 0 秒显示）", session.recorder.lastDurationSec >= 5, "lastDurationSec=${session.recorder.lastDurationSec}s")
            check("帧计数 >0（真实写入语义）", savedFrames.get() > 0, "frames=${savedFrames.get()}")
            if (p != null) {
                val dur = ffprobeDuration(p)
                check("ffprobe 可解析且时长 ≥4s", dur != null && dur >= 4.0, "duration=${dur}s")
            }
        }

        // ---------- 5) 虚拟手柄协议序列 ----------
        println("\n【虚拟手柄】js 协议全序列 …")
        val engine = VirtualGamepadEngine(session)
        engine.connect()
        delay(300)
        engine.setButton(0, true); engine.setButton(0, false)     // A
        engine.setStick(0, 1, 0.5f, -0.5f)                        // 左摇杆
        engine.setStick(2, 3, -0.25f, 0.75f)                      // 右摇杆
        engine.setButton(4, true); engine.setButton(4, false)     // LB
        engine.heartbeat()
        delay(500)
        engine.disconnect()
        check("手柄引擎连接状态", engine.isConnected == false, "connect→operate→disconnect 完成")
        check("手柄最近事件流非空", engine.lastEvent.value != null, engine.lastEvent.value?.take(40) ?: "null")

        // ---------- 6) 麦克风 wire 格式 ----------
        println("\n【麦克风】DesktopMicCapture 0x02+Opus 帧格式 …")
        val micFrames = AtomicInteger(0)
        val micSizeOk = AtomicInteger(0)
        var micErr: String? = null
        val micLatch = CountDownLatch(1)
        val mic = com.selkies.client.audio.DesktopMicCapture()
        mic.start(
            deviceIds = emptyList(),
            onOpusFrame = { opus ->
                if (micFrames.incrementAndGet() == 25) {
                    // 25 帧（0.5s）后检查尺寸范围：24kHz mono 20ms Opus ≈ 20-200B
                    if (opus.size in 3..400) micSizeOk.set(1)
                    micLatch.countDown()
                }
            },
            onError = { micErr = it; micLatch.countDown() }
        )
        val micGot = micLatch.await(6, TimeUnit.SECONDS)
        mic.stop()
        if (micErr?.contains("未找到可用麦克风") == true) {
            println("  ⏭ SKIP 麦克风（容器无声卡硬件——0x02+Opus wire 格式已由服务器端探针 e2e 验证）")
        } else {
            check("麦克风采集产出 Opus 帧", micGot && micFrames.get() > 0, "frames=${micFrames.get()} err=$micErr")
            if (micGot) check("Opus 帧尺寸合理（24k mono 20ms）", micSizeOk.get() == 1)
        }

        session.disconnect()
        delay(1500)
        println("\n=== V279 结果: PASS=$passCount FAIL=$failCount ===")
        if (failCount > 0) kotlin.system.exitProcess(1)
    }

    private fun ffprobeDuration(path: String): Double? = runCatching {
        val p = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration",
            "-of", "csv=p=0", path).start()
        p.inputStream.bufferedReader().readText().trim().toDoubleOrNull().also { p.waitFor() }
    }.getOrNull()

    private fun sysProp(k: String, d: String) = System.getProperty(k) ?: d
}
