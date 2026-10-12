package com.selkies.client.core

/**
 * 录屏自动保存钩子（进程级单例）：
 * Android 前台服务 onDestroy/onTaskRemoved（用户划掉任务、系统回收）与
 * 桌面 JVM shutdown hook 都调用 autosave()，确保「杀进程前先保存」。
 */
object RecordingShutdownHook {
    @Volatile
    private var active: StreamRecorder? = null

    fun register(recorder: StreamRecorder?) {
        active = recorder
    }

    fun autosave() {
        active?.let { r ->
            if (r.state == StreamRecorder.State.RECORDING) {
                r.stopAndSave("shutdown")
            }
        }
    }
}
