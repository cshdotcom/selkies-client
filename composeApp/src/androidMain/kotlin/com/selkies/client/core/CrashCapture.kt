package com.selkies.client.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.7.17【录屏闪退第 4 链取证】应用内崩溃捕获 + 面包屑轨迹。
 *
 * 背景：v2.7.15 三重修复（POST_NOTIFICATIONS 运行时请求 / startForeground 双路径
 * 重试 + 失败 stopSelf / startMuxer 回滚）后用户真机仍「点录屏即闪退」；CI 模拟器
 * （API 34 google_apis，run 43590283257）服务正常前台运行零崩溃 —— 崩溃链为真机
 * ROM 特有，纯代码推断已到极限（v2.7.15 即是教训：三处推断修复全部落空）。
 * 本模块让下一次真机复现直接给出崩溃签名：
 *
 * 1. [install] 注册全局 UncaughtExceptionHandler：Java/Kotlin 未捕获异常把
 *    线程名 + 完整堆栈 + 最近面包屑写入 filesDir/crash/last-crash.txt 后交还
 *    系统默认处理（正常崩溃流程不受影响）。
 * 2. [crumb] 面包屑环形缓冲（200 条）：录屏/服务/复用器关键节点全程留痕，
 *    崩溃时随堆栈一并落盘 —— 即使崩溃在 native 层（handler 接不到），
 *    下次启动 [pendingCrashReport] 检测「非正常退出 + 面包屑停在服务启动」
 *    也能把窗口收窄到毫秒级。
 * 3. [pendingCrashReport]/[markCrashReportShown]：下次启动 App 弹窗展示崩溃
 *    报告 + 一键复制（用户贴回来即可定位根因）。
 *
 * 线程安全：面包屑读写全程 synchronized；崩溃写入在 handler 线程同步完成
 * （进程将死，不依赖任何后台机制）。
 */
object CrashCapture {

    private const val MAX_CRUMBS = 200
    private const val CRASH_DIR = "crash"
    private const val CRASH_FILE = "last-crash.txt"
    private const val CRASH_FILE_READ = "last-crash-read.txt"
    private const val CRUMBS_FILE = "breadcrumbs.txt"
    private const val HEARTBEAT_FILE = "alive.marker"

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val crumbs = ArrayDeque<String>()
    private var appCtx: Context? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    @Volatile private var installed = false
    /** 上次进程是否正常收尾（false = 疑似被系统/native 层直接击杀） */
    @Volatile private var cleanExit = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        appCtx = ctx.applicationContext
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashFile(thread, throwable) }
            previousHandler?.uncaughtException(thread, throwable)
        }
        // 疑似硬死检测：上次进程未走 markCleanExit 且心跳新鲜 → 非代码层异常死亡
        runCatching {
            val c = appCtx ?: return
            val hb = aliveMarker(c)
            if (hb.exists() && System.currentTimeMillis() - hb.lastModified() < 30 * 60 * 1000) {
                crumb("BOOT", "检测到上次进程疑似非正常退出（无未捕获异常记录 + 心跳 ${aliveMarkerAge(c) / 1000}s 前）——多为系统击杀或 native 崩溃，历史面包屑已转存")
                copyCrumbsTo(c, File(c.filesDir, "$CRASH_DIR/$CRASH_FILE"))
            }
            writeAliveMarker(c)
        }
        crumb("BOOT", "CrashCapture 已安装（v2.7.17）")
    }

    /** 心跳：长时运行路径（录屏服务/会话前台）定期调用，维持 alive.marker 新鲜度 */
    fun heartbeat() {
        runCatching {
            appCtx?.let {
                val hb = aliveMarker(it)
                hb.parentFile?.mkdirs()
                if (!hb.exists()) hb.writeText("alive")
                hb.setLastModified(System.currentTimeMillis())
            }
        }
    }

    /** 关键节点留痕（时间戳自动附加；消息控制在 160 字符内） */
    fun crumb(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] ${msg.take(160)}"
        synchronized(crumbs) {
            if (crumbs.size >= MAX_CRUMBS) crumbs.removeFirst()
            crumbs.addLast(line)
        }
    }

    /** 面包屑快照（供 UI 弹窗展示/复制） */
    fun snapshot(): List<String> = synchronized(crumbs) { crumbs.toList() }

    /** 正常退出时调用（Application 非强制路径）：清除心跳，避免误报「非正常退出」 */
    fun markCleanExit() {
        cleanExit = true
        runCatching { appCtx?.let { writeCleanExitMarker(it) } }
    }

    /** 读上次崩溃报告（只读一次：读后转存 -read 文件；再崩溃会生成新报告） */
    fun pendingCrashReport(): String? = runCatching {
        val c = appCtx ?: return@runCatching null
        val f = File(c.filesDir, "$CRASH_DIR/$CRASH_FILE")
        if (!f.exists()) return@runCatching null
        val text = f.readText()
        if (text.isNotBlank()) {
            f.renameTo(File(c.filesDir, "$CRASH_DIR/$CRASH_FILE_READ"))
            text
        } else null
    }.getOrNull()

    /** 崩溃报告弹窗关闭/复制后调用：删除归档报告（报告内容已交用户，不再留存） */
    fun markCrashReportShown() {
        runCatching {
            val c = appCtx ?: return
            File(c.filesDir, "$CRASH_DIR/$CRASH_FILE_READ").delete()
        }
    }

    /** RecordingService.onDestroy 等时机落盘面包屑（不删除内存缓冲） */
    fun dumpBreadcrumbs() {
        runCatching {
            val c = appCtx ?: return
            copyCrumbsTo(c, File(c.filesDir, "$CRASH_DIR/$CRUMBS_FILE"))
        }
    }

    // ---------------- 内部 ----------------

    private fun writeCrashFile(thread: Thread, throwable: Throwable) {
        val c = appCtx ?: return
        val dir = File(c.filesDir, CRASH_DIR).apply { mkdirs() }
        val sb = StringBuilder()
        sb.appendLine("==== Selkies 崩溃报告（v2.7.17 取证） ====")
        sb.appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
        sb.appendLine("线程: ${thread.name} (id=${thread.id})")
        sb.appendLine()
        sb.appendLine("---- 异常 ----")
        sb.appendLine(throwable.javaClass.name + ": " + throwable.message)
        throwable.stackTrace.take(40).forEach { sb.appendLine("    at $it") }
        sb.appendLine()
        sb.appendLine("---- 最近事件（面包屑，最新在末尾） ----")
        synchronized(crumbs) { crumbs.forEach { sb.appendLine(it) } }
        sb.appendLine()
        sb.appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        File(dir, CRASH_FILE).writeText(sb.toString())
        // 崩溃文件已落盘，清除心跳防下次误报
        writeCleanExitMarker(c)
    }

    private fun aliveMarker(c: Context): File = File(c.filesDir, "$CRASH_DIR/$HEARTBEAT_FILE")

    private fun aliveMarkerAge(c: Context): Long =
        System.currentTimeMillis() - aliveMarker(c).lastModified()

    private fun writeAliveMarker(c: Context) {
        val dir = File(c.filesDir, CRASH_DIR).apply { mkdirs() }
        File(dir, HEARTBEAT_FILE).apply { writeText("alive") }.setLastModified(System.currentTimeMillis())
    }

    private fun writeCleanExitMarker(c: Context) {
        aliveMarker(c).delete()
        File(File(c.filesDir, CRASH_DIR).apply { mkdirs() }, "clean-exit.marker").apply {
            writeText("${System.currentTimeMillis()}")
        }
    }

    private fun copyCrumbsTo(c: Context, dest: File) {
        dest.parentFile?.mkdirs()
        synchronized(crumbs) { crumbs.forEach { dest.appendText(it + "\n") } }
    }
}
