package com.velotrack.velotrack

import kotlinx.coroutines.flow.StateFlow

fun interface HistoryMaintenance {
    /** Invoked on IO before reading history; a failure must leave maintenance retryable. */
    suspend fun ensureCurrent()
}

fun interface RideAnalysisClient {
    suspend fun analyze(prompt: String, requestId: String): String
}

data class DebugLogState(val isRecording: Boolean = false, val lineCount: Int = 0)

interface TrackDebugLogs {
    val state: StateFlow<DebugLogState>
    fun toggle()
    fun appendLocation(message: String)
    /** Blocking export, called on IO. */
    fun save(): String
}
