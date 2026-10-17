package com.selkies.client.tools

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer

/**
 * v2.7.10【releaseCodec 双重释放定位】：纯原生分配/释放循环（无服务器无解码无 UI）。
 * 完全模拟 DesktopVideoSink.releaseCodec + ensureDecoder 的分配模式：
 *   cycle N: alloc ctx(指针包装)/pkt/frame/bgraFrame/bgraPtr/sws → open codec →
 *            av_new_packet+put 数据 → 解一个空 packet → 释放全部（与 releaseCodec 同序）
 * -Dselkies.cycles=20 循环次数；哪个 free 崩溃一目了然（可加 -Dselkies.skip=X 跳过某项）
 * skip 取值: ctx|pkt|frame|bgraframe|bgraptr|sws（逗号分隔多项）
 */
object NativeCycleProbe {

    @JvmStatic
    fun main(args: Array<String>) {
        avutil.av_log_set_level(avutil.AV_LOG_ERROR)
        val cycles = (System.getProperty("selkies.cycles") ?: "20").toInt()
        val skip = (System.getProperty("selkies.skip") ?: "").split(",").filter { it.isNotBlank() }.toSet()
        // v2.7.10：真实 H264 数据（-Dselkies.h264=/tmp/test.h264）——垃圾数据解码器立即拒绝
        //   不触及内部状态；真实帧才触发解码器内部 DPB/引用分配（= 真实崩溃条件）
        val h264File = System.getProperty("selkies.h264")
        val realNals: List<ByteArray> = if (h264File != null) {
            val data = java.io.File(h264File).readBytes()
            val starts = ArrayList<Int>()
            var i = 0
            while (i + 3 < data.size) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                    starts.add(i); i += 3
                } else i++
            }
            starts.mapIndexed { idx, s ->
                val e = if (idx + 1 < starts.size) starts[idx + 1] else data.size
                data.copyOfRange(s, e)
            }
        } else emptyList()
        println("真实 NAL: ${realNals.size} 个${if (realNals.isEmpty()) "（随机垃圾数据模式）" else ""}")
        val payload = ByteArray(4096) { (it % 251).toByte() }

        for (i in 1..cycles) {
            println("=== cycle $i ===")
            var codecCtx: AVCodecContext? = null
            var packet: AVPacket? = null
            var frame: AVFrame? = null
            var bgraFrame: AVFrame? = null
            var bgraPtr: BytePointer? = null
            var bgraAlloc: BytePointer? = null
            var sws: SwsContext? = null
            try {
                // --- ensureDecoder 的分配模式 ---
                val codec = avcodec.avcodec_find_decoder(avcodec.AV_CODEC_ID_H264)
                val ctx = AVCodecContext(avcodec.avcodec_alloc_context3(codec))
                ctx.width(410); ctx.height(862)
                ctx.flags2(ctx.flags2() or avcodec.AV_CODEC_FLAG2_FAST)
                val openRet = avcodec.avcodec_open2(ctx, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?)
                check(openRet >= 0) { "open2 failed ret=$openRet" }
                codecCtx = ctx
                packet = AVPacket()
                frame = AVFrame()
                bgraFrame = AVFrame()
                // v2.7.10：64 字节对齐行距 + 前后各 64 字节保护垫（sws SIMD 双向越界实测：
                // 旧行距 1640 非 64 对齐 → 末行溢出 ~24B；同时 SIMD 前导回写会写到 dst 起点之前
                // → 前垫 64B 吸收；后垫 64B 吸收行尾 SIMD 整块）
                val rowStride = (410 * 4 + 63) and 63.inv()
                val size = rowStride * 862 + 128
                val allocBase = BytePointer(size.toLong())  // javacpp 分配（GC 释放器+可 deallocate）
                bgraPtr = allocBase.position(64)
                bgraAlloc = allocBase
                bgraBytes = ByteArray(410 * 862 * 4)
                val f = bgraFrame!!
                f.data(0, bgraPtr)
                f.linesize(0, rowStride)
                sws = swscale.sws_getContext(410, 862, avutil.AV_PIX_FMT_YUV420P, 410, 862, avutil.AV_PIX_FMT_BGRA, swscale.SWS_BILINEAR, null as org.bytedeco.ffmpeg.swscale.SwsFilter?, null as org.bytedeco.ffmpeg.swscale.SwsFilter?, null as org.bytedeco.javacpp.DoublePointer?)
                bgraBytes = ByteArray(size)

                // --- decoderLoop 的使用模式（真实 NAL 流式送入） ---
                if (realNals.isEmpty()) {
                    avcodec.av_packet_unref(packet)
                    avcodec.av_new_packet(packet, payload.size)
                    packet.data().position(0).put(payload, 0, payload.size)
                    avcodec.avcodec_send_packet(ctx, packet)
                    var rr = avcodec.avcodec_receive_frame(ctx, frame)
                    while (rr >= 0) { avutil.av_frame_unref(frame); rr = avcodec.avcodec_receive_frame(ctx, frame) }
                } else {
                    for (nal in realNals) {
                        avcodec.av_packet_unref(packet)
                        avcodec.av_new_packet(packet, nal.size)
                        packet.data().position(0).put(nal, 0, nal.size)
                        avcodec.avcodec_send_packet(ctx, packet)
                        var rr = avcodec.avcodec_receive_frame(ctx, frame)
                        while (rr >= 0) {
                            // 真实转换：sws_scale → bgraPtr → bgraBytes（完整解码→转换→读取路径）
                            val scw = swscale.sws_getCachedContext(sws, frame.width(), frame.height(), frame.format(), frame.width(), frame.height(), avutil.AV_PIX_FMT_BGRA, swscale.SWS_BILINEAR, null as org.bytedeco.ffmpeg.swscale.SwsFilter?, null as org.bytedeco.ffmpeg.swscale.SwsFilter?, null as org.bytedeco.javacpp.DoublePointer?)
                            if (scw != null) {
                                sws = scw
                                swscale.sws_scale(scw, frame.data() as org.bytedeco.javacpp.PointerPointer, frame.linesize() as org.bytedeco.javacpp.IntPointer, 0, frame.height(), bgraFrame!!.data() as org.bytedeco.javacpp.PointerPointer, bgraFrame!!.linesize() as org.bytedeco.javacpp.IntPointer)
                                // 逐行紧凑读出（行距对齐 ≠ 紧凑布局）
                                val stride2 = (frame.width() * 4 + 63) and 63.inv()
                                for (r in 0 until frame.height()) {
                                    bgraPtr!!.position((64 + r * stride2).toLong()).get(bgraBytes, r * frame.width() * 4, frame.width() * 4)
                                }
                            }
                            avutil.av_frame_unref(frame)
                            rr = avcodec.avcodec_receive_frame(ctx, frame)
                        }
                    }
                }
            } finally {
                // --- releaseCodec 的释放模式（逐项可跳过） ---
                runCatching {
                    if ("ctx" !in skip) { print(" free(ctx)"); System.out.flush(); codecCtx?.let { c -> avcodec.avcodec_close(c); avcodec.avcodec_free_context(c) } }
                    if ("pkt" !in skip) { print(" free(pkt)"); System.out.flush(); packet?.deallocate() }
                    if ("frame" !in skip) { print(" free(frame)"); System.out.flush(); frame?.deallocate() }
                    if ("bgraframe" !in skip) { print(" free(bgraFrame)"); System.out.flush(); bgraFrame?.deallocate() }
                    if ("bgraptr" !in skip) {
                        // v2.7.10 诊断：释放前 dump 缓冲区边界（前垫/后垫区域）
                        bgraPtr?.let { bp ->
                            try {
                                val rowStride = (410 * 4 + 63) and 63.inv()
                                val realSize = rowStride * 862
                                val bpd = bgraAlloc?.position(0) ?: bp.position(0)
                                val before = StringBuilder()
                                for (k in 0 until 16) before.append("%02x ".format(bpd.get(k.toLong())))
                                val after = StringBuilder()
                                for (k in 0 until 32) after.append("%02x ".format(bpd.get((realSize + 64 + k).toLong())))
                                println("\n[dump cycle $i] ptr=0x${java.lang.Long.toHexString(bp.address())} realSize=$realSize")
                                println("  16B at alloc start: $before")
                                println("  32B after user end: $after")
                            } catch (e: Throwable) { println("[dump fail] ${e.javaClass.simpleName}: ${e.message}") }
                        }
                        print(" free(bgraPtr)"); System.out.flush(); bgraAlloc?.let { it.deallocate() }
                    }
                    if ("sws" !in skip) { print(" free(sws)"); System.out.flush(); sws?.let { swscale.sws_freeContext(it) } }
                }
                println(" [cycle $i done]")
                System.out.flush()
            }
        }
        println("=== 全部 $cycles 轮完成，无崩溃 ===")
    }

    private var bgraBytes = ByteArray(0)
}
