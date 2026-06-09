package com.velotrack.velotrack.debug

import android.content.Context
import android.os.Build
import com.velotrack.velotrack.BuildConfig
import com.velotrack.velotrack.MapProviderSelector
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogExporter {

    fun save(context: Context, lines: List<String>): Result {
        if (lines.isEmpty()) {
            return Result(message = "no lines to save", path = null)
        }
        return runCatching {
            val dir = File(context.getExternalFilesDir(null), "velotrack-debug").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "velotrack-debug-$stamp.txt")
            val provider = MapProviderSelector.select()
            val header = buildString {
                appendLine("# VeloTrack debug log")
                appendLine("# generated: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())}")
                appendLine("# version: ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
                appendLine("# device: ${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}")
                appendLine("# map provider: ${provider.displayName}")
                appendLine("# lines: ${lines.size}")
                appendLine()
            }
            file.writeText(header + lines.joinToString("\n") + "\n")
            Result(message = "saved (${lines.size} lines)", path = file.absolutePath)
        }.getOrElse { error ->
            Result(message = "save failed: ${error.message ?: error::class.java.simpleName}", path = null)
        }
    }

    data class Result(
        val message: String,
        val path: String?,
    ) {
        val statusText: String
            get() = if (path != null) "$message\n$path" else message
    }
}
