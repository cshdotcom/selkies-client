package com.selkies.client.core

import com.selkies.client.platform.VideoFrameHeader
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Selkies WebSocket 协议编解码
 * 全部字段格式见官方源码 websockets_mode.py / input_handler.py / selkies-web-core
 */
object Protocol {
    // ---- 二进制帧类型 ----
    const val FRAME_AUDIO: Byte = 0x01
    const val FRAME_MIC: Byte = 0x02
    const val FRAME_JPEG: Byte = 0x03
    const val FRAME_VIDEO: Byte = 0x04
    const val FRAME_GZ: Byte = 0x05
    const val FRAME_WEBCAM: Byte = 0x06

    const val AUDIO_QUIET: Int = 0x80
    const val CLIPBOARD_CHUNK_RAW = 16383

    // ---- 鼠标按键掩码 ----
    const val BUTTON_LEFT = 0x01
    const val BUTTON_MIDDLE = 0x02
    const val BUTTON_RIGHT = 0x04
    const val SCROLL_DOWN_BIT = 0x08
    const val SCROLL_UP_BIT = 0x10
    const val BUTTON_BACK = 0x20
    const val SCROLL_LEFT_BIT = 0x40
    const val SCROLL_RIGHT_BIT = 0x80

    /**
     * 解析 0x04 视频帧头（12 字节）
     * [0x04][typeByte][u16 frameId][u16 stripeY][u16 width][u16 height][u16 refId][data...]
     */
    fun parseVideoFrameHeader(data: ByteArray, length: Int): Pair<VideoFrameHeader, Int>? {
        if (length < 12) return null
        val typeByte = data[1].toInt() and 0xff
        val codecId = (typeByte shr 4) and 0x0f
        val kind = typeByte and 0x0f
        val header = VideoFrameHeader(
            codecId = if (codecId == 0) 1 else codecId,
            keyframe = kind == 0x01,
            frameId = u16(data, 2),
            stripeY = u16(data, 4),
            width = u16(data, 6),
            height = u16(data, 8),
            refFrameId = u16(data, 10)
        )
        return header to 12
    }

    /** 解析 0x03 JPEG 条带头（6 字节）：[0x03][u8 resv][u16 frameId][u16 stripeY][jpeg] */
    fun parseJpegStripeHeader(data: ByteArray, length: Int): Triple<Int, Int, Int>? {
        if (length < 6) return null
        return Triple(u16(data, 2), u16(data, 4), 6)
    }

    /**
     * 解析 0x01 音频帧，返回有序 Opus 帧列表（处理 RED 冗余）
     * [0x01][flags][opus]  flags=n_red(低7位)|quiet(0x80)
     * n_red>0: [0x01][n][pts32][n*(4B头)][1B主头][blocks]
     */
    fun parseAudioFrames(data: ByteArray, length: Int, state: AudioParseState): List<Pair<ByteArray, Boolean>> {
        if (length <= 2) {
            // 纯 quiet 标记
            if (length == 2 && (data[1].toInt() and AUDIO_QUIET) != 0) {
                return listOf(Pair(ByteArray(0), true))
            }
            return emptyList()
        }
        val flags = data[1].toInt() and 0xff
        val quiet = (flags and AUDIO_QUIET) != 0
        val nRed = flags and (AUDIO_QUIET.inv())
        if (nRed == 0) {
            state.lastTs = null
            val opus = data.copyOfRange(2, length)
            return listOf(Pair(opus, quiet))
        }
        if (length < 6 + nRed * 4 + 1) {
            state.lastTs = null
            return emptyList()
        }
        val pts = ((data[2].toInt() and 0xff) shl 24) or ((data[3].toInt() and 0xff) shl 16) or
                ((data[4].toInt() and 0xff) shl 8) or (data[5].toInt() and 0xff)
        var pos = 6
        val offsets = IntArray(nRed)
        val lens = IntArray(nRed)
        for (i in 0 until nRed) {
            val field = ((data[pos + 1].toInt() and 0xff) shl 16) or
                    ((data[pos + 2].toInt() and 0xff) shl 8) or (data[pos + 3].toInt() and 0xff)
            offsets[i] = (field shr 10) and 0x3fff
            lens[i] = field and 0x3ff
            pos += 4
        }
        pos += 1
        var declared = pos
        for (i in 0 until nRed) declared += lens[i]
        if (declared > length) {
            state.lastTs = null
            return emptyList()
        }
        data class Block(val ts: Long, val from: Int, val to: Int)
        val blocks = ArrayList<Block>(nRed + 1)
        for (i in 0 until nRed) {
            blocks.add(Block((pts - offsets[i]).toLong() and 0xffffffffL, pos, pos + lens[i]))
            pos += lens[i]
        }
        blocks.add(Block(pts.toLong() and 0xffffffffL, pos, length))
        if (state.lastTs == null) {
            state.lastTs = pts.toLong() and 0xffffffffL
            val last = blocks.last()
            return listOf(Pair(data.copyOfRange(last.from, last.to), quiet))
        }
        val out = ArrayList<Pair<ByteArray, Boolean>>()
        var last = state.lastTs!!
        for ((idx, b) in blocks.withIndex()) {
            if (audioTsNewer(b.ts, last)) {
                val isLast = idx == blocks.size - 1
                out.add(Pair(data.copyOfRange(b.from, b.to), quiet && isLast))
                last = b.ts
            }
        }
        state.lastTs = last
        return out
    }

    private fun audioTsNewer(a: Long, b: Long): Boolean {
        val d = (a - b) and 0xffffffffL
        return d != 0L && d < 0x80000000L
    }

    class AudioParseState {
        var lastTs: Long? = null
    }

    private fun u16(data: ByteArray, off: Int): Int =
        ((data[off].toInt() and 0xff) shl 8) or (data[off + 1].toInt() and 0xff)

    // ---- 输入消息构造 ----
    // ⚠️ 官方格式（selkies-web-core _sendPointer）：每条指针消息末尾附递增序列号，
    //    服务器据此丢弃乱序/重复事件；固定 0 可能导致事件被丢弃（v2.0.1 隐患）

    fun keyEvent(down: Boolean, keysym: Long): String =
        (if (down) "kd," else "ku,") + keysym

    fun keyHeartbeat(held: List<Long>): String = "kh," + held.joinToString(",")

    fun keyReset(): String = "kr"

    /** IME 提交文本 */
    fun commitText(text: String): String = "co,end,$text"

    /** 鼠标绝对移动（官方：m,x,y,mask,0,seq） */
    fun mouseMove(x: Int, y: Int, mask: Int, seq: Int): String = "m,$x,$y,$mask,0,$seq"

    /** 鼠标相对移动（官方：m2,dx,dy,mask,0,seq；游戏/触摸板模式） */
    fun mouseMoveRelative(dx: Int, dy: Int, mask: Int, seq: Int): String = "m2,$dx,$dy,$mask,0,$seq"

    /**
     * 滚动脉冲（官方 _triggerMouseWheel）：
     * 纵向三连发 m2,0,0,cleared→cleared|bit→buttonMask，幅度 1..8；
     * 横向两连发 set→restore。
     * bit: 上=0x10 下=0x08 左=0x40 右=0x80
     */
    fun scrollPulse(buttonMask: Int, vertical: Boolean, amount: Int): List<String> {
        val magnitude = min(8, abs(amount).coerceAtLeast(1))
        val bit = if (vertical) {
            if (amount > 0) SCROLL_DOWN_BIT else SCROLL_UP_BIT
        } else {
            if (amount > 0) SCROLL_RIGHT_BIT else SCROLL_LEFT_BIT
        }
        val cleared = buttonMask and bit.inv()
        return if (vertical) {
            listOf(
                "m2,0,0,$cleared,$magnitude",
                "m2,0,0,${cleared or bit},$magnitude",
                "m2,0,0,$buttonMask,$magnitude"
            )
        } else {
            listOf(
                "m2,0,0,${buttonMask or bit},$magnitude",
                "m2,0,0,$buttonMask,$magnitude"
            )
        }
    }

    /** 手柄连接 */
    fun gamepadConnect(index: Int, idBase64: String, nAxes: Int, nButtons: Int): String =
        "js,c,$index,$idBase64,$nAxes,$nButtons"

    fun gamepadDisconnect(index: Int): String = "js,d,$index"

    fun gamepadButton(index: Int, button: Int, value: Double): String {
        val v = (value * 1000).roundToInt().toDouble() / 1000.0
        return "js,b,$index,$button,$v"
    }

    fun gamepadAxis(index: Int, axis: Int, value: Double): String {
        val v = (value * 1000).roundToInt().toDouble() / 1000.0
        return "js,a,$index,$axis,$v"
    }

    fun gamepadHeartbeat(index: Int): String = "js,h,$index"

    /** 视口声明 */
    fun viewport(ox: Double, oy: Double, sx: Double, sy: Double): String =
        "vp," + String.format(Locale.US, "%.2f", ox) + "," + String.format(Locale.US, "%.2f", oy) +
                "," + String.format(Locale.US, "%.5f", sx) + "," + String.format(Locale.US, "%.5f", sy)

    /** 分辨率请求 */
    fun resolution(width: Int, height: Int, displayId: String = "primary"): String =
        "r,${width}x${height},$displayId"

    /** DPI 缩放 */
    fun scaling(scale: Double): String = "s," + String.format(Locale.US, "%.4f", scale)
}
