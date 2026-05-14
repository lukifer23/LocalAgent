package com.localagent.bridge

import java.text.SimpleDateFormat
import java.util.Locale

data class BridgeDiagEvent(
    val timestampMs: Long = System.currentTimeMillis(),
    val kind: String,
    val message: String,
) {
    fun line(): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return "${fmt.format(timestampMs)}  $kind  $message"
    }
}
