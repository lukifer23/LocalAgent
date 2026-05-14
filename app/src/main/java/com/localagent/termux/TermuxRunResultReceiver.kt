package com.localagent.termux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.localagent.LocalAgentApp

/**
 * Receives Termux plugin results delivered via [PendingIntent] from RUN_COMMAND.
 */
class TermuxRunResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as? LocalAgentApp ?: return
        val kind = intent?.getStringExtra(EXTRA_KIND).orEmpty()
        val bundle =
            intent?.getBundleExtra(TermuxPluginResultExtras.EXTRA_RESULT_BUNDLE)
                ?: intent?.extras?.getBundle(TermuxPluginResultExtras.EXTRA_RESULT_BUNDLE)
        val stdout = bundle?.getString(TermuxPluginResultExtras.STDOUT)
        val stderr = bundle?.getString(TermuxPluginResultExtras.STDERR)
        val exitRaw = bundle?.getInt(TermuxPluginResultExtras.EXIT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val pluginErr =
            if (bundle?.containsKey(TermuxPluginResultExtras.ERR) == true) {
                bundle.getInt(TermuxPluginResultExtras.ERR)
            } else {
                null
            }
        val errmsg = bundle?.getString(TermuxPluginResultExtras.ERRMSG)
        val resolvedExit = if (exitRaw != Int.MIN_VALUE) exitRaw else -1
        val logTail = 6000
        Log.i(
            TAG,
            "kind=$kind exit=$resolvedExit pluginErr=$pluginErr " +
                "errmsg=${errmsg?.take(logTail)?.replace("\n", "↵")} " +
                "stderr=${stderr?.take(logTail)?.replace("\n", "↵")} " +
                "stdout=${stdout?.take(logTail)?.replace("\n", "↵")}",
        )
        val summary =
            TermuxRunResultSummary(
                kind = kind.ifBlank { "termux" },
                exitCode = resolvedExit,
                stdout = stdout,
                stderr = stderr,
                pluginErr = pluginErr,
                errmsg = errmsg,
            )
        app.container.termuxRunResults.tryEmit(summary)
    }

    companion object {
        const val ACTION: String = "com.localagent.termux.RUN_RESULT"
        const val EXTRA_KIND: String = "com.localagent.termux.KIND"
        private const val TAG: String = "LocalAgentTermux"
    }
}
