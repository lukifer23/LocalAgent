package com.localagent.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localagent.bridge.BridgeDiagEvent
import com.localagent.di.AppContainer
import com.localagent.termux.TermuxRunResultSummary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ActivityLogViewModel(
    private val container: AppContainer
) : ViewModel() {

    val logLines = mutableStateListOf<String>()

    init {
        viewModelScope.launch {
            container.termuxRunResults.collect { s ->
                appendTermuxRunToLog(s)
            }
        }
        viewModelScope.launch {
            container.bridgeServer.diagnostics.collect { d ->
                appendBridgeDiagToLog(d.kind, d.message)
            }
        }
    }

    private fun hermesLogTime(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun appendTermuxRunToLog(s: TermuxRunResultSummary) {
        val sb = StringBuilder()
        sb.append("── ").append(hermesLogTime()).append(" Termux [").append(s.kind).append("] exit=").append(s.exitCode)
        s.pluginErr?.let { sb.append(" plugin=").append(it) }
        sb.append(" ──\n")
        if (!s.errmsg.isNullOrBlank()) sb.append(s.errmsg.trim()).append('\n')
        if (!s.stderr.isNullOrBlank()) sb.append(s.stderr.trimEnd().take(4000)).append('\n')
        if (!s.stdout.isNullOrBlank()) sb.append(s.stdout.trimEnd().take(4000)).append('\n')
        
        logLines.add(0, sb.toString().trimEnd())
        while (logLines.size > 100) logLines.removeAt(logLines.lastIndex)
    }

    private fun appendBridgeDiagToLog(kind: String, message: String) {
        logLines.add(
            0,
            "── ${hermesLogTime()} bridge [$kind] ${message.trim().take(2000)} ──",
        )
        while (logLines.size > 100) logLines.removeAt(logLines.lastIndex)
    }

    fun clear() {
        logLines.clear()
    }
}
