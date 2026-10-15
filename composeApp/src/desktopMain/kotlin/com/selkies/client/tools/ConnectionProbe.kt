package com.selkies.client.tools

import com.selkies.client.core.SelkiesConnection
import com.selkies.client.model.ServerEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 连接层回归测试：
 * 1) [local-abrupt]  服务器突然掐断 TCP（无 Close 帧）→ 必须回调 onClosed(-1)（静默死亡修复）
 * 2) [local-clean]   服务器正常 Close(1000) → 必须回调 onClosed(1000)
 * 3) [local-guard]   客户端主动 close() → 不得产生任何回调（防重连竞争守卫）
 * 4) [real-hold]     真实服务器保持 N 秒，验证无静默断连
 *
 * 用法：ConnectionProbe local-abrupt [wsUrl]
 *       ConnectionProbe local-clean [wsUrl]
 *       ConnectionProbe local-guard [wsUrl]
 *       ConnectionProbe real-hold [seconds] [httpsUrl] [user] [pass]
 */
object ConnectionProbe {

    private fun entry(url: String, user: String = "", pass: String = "") = ServerEntry(
        id = "probe", name = "probe", url = url, username = user, password = pass
    )

    @JvmStatic
    fun main(args: Array<String>) {
        fun p(name: String, def: String): String = System.getProperty(name) ?: System.getenv(name) ?: def
        val mode = args.getOrNull(0) ?: p("mode", "local-abrupt")
        val url = args.getOrNull(1) ?: p("url", "ws://127.0.0.1:18081")
        when (mode) {
            "local-abrupt" -> runBlocking { testLocal(url, expect = Expect.ANY_TERMINAL) }
            "local-fin" -> runBlocking { testLocal(url, expect = Expect.ANY_TERMINAL) }
            "local-clean" -> runBlocking { testLocal(url, expect = Expect.CLOSED_EITHER) }
            "local-guard" -> runBlocking { testGuard(url) }
            "real-hold" -> {
                val secs = p("holdSecs", "300").toLong()
                val realUrl = p("url", "https://workspace-browser.nodebyte.cn")
                val user = p("user", "cshll")
                val pass = p("pass", "15068253855z")
                runBlocking { testRealHold(secs, realUrl, user, pass) }
            }
            else -> println("未知模式 $mode")
        }
    }

    /** 预期结果分类 */
    private enum class Expect {
        ANY_TERMINAL,      // RST/FIN/网络错误：onError 或 onClosed 任一即可
        CLOSED_EITHER,     // 服务器主动关：onClosed(-1)（OkHttp 引擎不投递 Frame.Close）或 onClosed(1000)
    }

    private class Recorder : SelkiesConnection.Listener {
        val opened = AtomicInteger(0)
        val texts = AtomicInteger(0)
        val closed = AtomicInteger(0)
        val closedCode = AtomicInteger(0)
        val closedReason = AtomicReference<String>("")
        val errors = AtomicInteger(0)
        val errMsg = AtomicReference<String>("")

        fun awaitEnd(): Boolean {
            // 等待 任一终结回调（closed/error）或超时
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                if (closed.get() > 0 || errors.get() > 0) return true
                Thread.sleep(50)
            }
            return false
        }

        override fun onOpened() { println("[cb] onOpened"); opened.incrementAndGet() }
        override fun onText(text: String) { texts.incrementAndGet() }
        override fun onBinary(data: ByteArray, length: Int) {}
        override fun onClosed(code: Int, reason: String) {
            println("[cb] onClosed code=$code reason=$reason")
            closed.incrementAndGet(); closedCode.set(code); closedReason.set(reason)
        }
        override fun onError(userMessage: String, technical: String) {
            println("[cb] onError user=$userMessage")
            println("[cb] onError tech =$technical")
            errors.incrementAndGet(); errMsg.set("$userMessage | $technical")
        }
        override fun onRedirected(from: String, to: String) { println("[cb] redirect $from -> $to") }
    }

    private suspend fun testLocal(url: String, expect: Expect) {
        val rec = Recorder()
        val conn = SelkiesConnection(entry(url), rec)
        conn.start()
        // 等 opened
        val t0 = System.currentTimeMillis()
        while (rec.opened.get() == 0 && System.currentTimeMillis() - t0 < 10_000) delay(50)
        check(rec.opened.get() == 1) { "未能连接本地测试服务器" }
        println("[OK] 已连接")
        // 本地服务器会在 ~3s 后掐线/正常关闭
        val ended = rec.awaitEnd()
        conn.close()
        when (expect) {
            Expect.ANY_TERMINAL -> {
                check(ended && (rec.closed.get() > 0 || rec.errors.get() > 0)) {
                    "❌ 网络断线未被上报（静默死亡）：ended=$ended closed=${rec.closed.get()} errors=${rec.errors.get()}"
                }
                println("[OK] 断线已被上报（${if (rec.closed.get() > 0) "onClosed(${rec.closedCode.get()})" else "onError"}）")
            }
            Expect.CLOSED_EITHER -> {
                check(ended && rec.closed.get() == 1 && (rec.closedCode.get() == -1 || rec.closedCode.get() == 1000)) {
                    "❌ 服务器主动关闭未被上报（修复前这里会永久冻结）：ended=$ended closed=${rec.closed.get()} code=${rec.closedCode.get()}"
                }
                println("[OK] 服务器主动关闭已上报：onClosed(${rec.closedCode.get()})")
            }
        }
        println("✅ 测试通过")
        kotlin.system.exitProcess(0)
    }

    private suspend fun testGuard(url: String) {
        val rec = Recorder()
        val conn = SelkiesConnection(entry(url), rec)
        conn.start()
        val t0 = System.currentTimeMillis()
        while (rec.opened.get() == 0 && System.currentTimeMillis() - t0 < 10_000) delay(50)
        check(rec.opened.get() == 1) { "未能连接本地测试服务器" }
        println("[OK] 已连接")
        conn.close()
        delay(2500)
        check(rec.closed.get() == 0 && rec.errors.get() == 0) {
            "❌ 主动关闭守卫失效：closed=${rec.closed.get()} errors=${rec.errors.get()} reason=${rec.closedReason.get()}"
        }
        println("[OK] 主动关闭后无任何虚假回调")
        println("✅ local-guard 通过")
        kotlin.system.exitProcess(0)
    }

    private suspend fun testRealHold(secs: Long, url: String, user: String, pass: String) {
        val rec = Recorder()
        val conn = SelkiesConnection(entry(url, user, pass), rec)
        conn.start()
        val t0 = System.currentTimeMillis()
        while (rec.opened.get() == 0 && System.currentTimeMillis() - t0 < 15_000) delay(50)
        check(rec.opened.get() == 1) { "连接真实服务器失败" }
        println("[OK] 已连接真实服务器，保持 ${secs}s ...")
        var lastTexts = 0
        var lastReport = 0L
        while (System.currentTimeMillis() - t0 < secs * 1000) {
            delay(1000)
            val t = rec.texts.get()
            if (t != lastTexts) {
                lastTexts = t; lastReport = System.currentTimeMillis()
            }
            if (rec.closed.get() > 0 || rec.errors.get() > 0) {
                println("[!!] 会话中断：code=${rec.closedCode.get()} reason=${rec.closedReason.get()} err=${rec.errMsg.get()}")
                break
            }
        }
        val held = (System.currentTimeMillis() - t0) / 1000
        conn.close()
        val alive = rec.closed.get() == 0 && rec.errors.get() == 0
        println(if (alive) "✅ real-hold 通过：稳定保持 ${held}s（texts=${rec.texts.get()}）"
        else "❌ real-hold 失败：仅保持 ${held}s")
        kotlin.system.exitProcess(if (alive) 0 else 1)
    }
}
