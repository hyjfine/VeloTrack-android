package com.velotrack.velotrack.debug

import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DEBUG 面板日志缓冲：开始录制后收集 LOC/PROC/SPEED 等行，供导出为文本文件。
 */
object DebugLogRecorder {
    private const val MAX_LINES = 4000

    private val lines = ArrayDeque<String>(512)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    var isRecording: Boolean = false
        private set

    /** UI 刷新回调（主线程调用方负责切线程）。 */
    var onStateChanged: (() -> Unit)? = null

    val lineCount: Int
        get() = lines.size

    fun start() {
        lines.clear()
        isRecording = true
        append("META", "log started")
        notifyChanged()
    }

    fun stop() {
        if (!isRecording) return
        append("META", "log stopped lines=$lineCount")
        isRecording = false
        notifyChanged()
    }

    fun toggle(): Boolean {
        if (isRecording) stop() else start()
        return isRecording
    }

    fun append(tag: String, message: String) {
        if (!isRecording) return
        val ts = timeFormat.format(Date())
        val elapsed = SystemClock.elapsedRealtime()
        val line = "$ts +${elapsed}ms $tag  $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
            }
        }
        notifyChanged()
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    private fun notifyChanged() {
        onStateChanged?.invoke()
    }
}
