package com.localagent.termux

/**
 * Normalized plugin callback payload from Termux [RUN_COMMAND](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)
 * when [EXTRA_PENDING_INTENT] is supplied.
 *
 * Bundle keys mirror `com.termux.shared.termux.TermuxConstants.TERMUX_SERVICE` defaults.
 */
data class TermuxRunResultSummary(
    val kind: String,
    val exitCode: Int,
    val stdout: String?,
    val stderr: String?,
    val pluginErr: Int?,
    val errmsg: String?,
)

object TermuxPluginResultExtras {
    /** Intent extra holding the nested [Bundle] of stdout/stderr/exitCode. */
    const val EXTRA_RESULT_BUNDLE: String = "result"
    const val STDOUT: String = "stdout"
    const val STDERR: String = "stderr"
    const val EXIT_CODE: String = "exitCode"
    const val ERR: String = "err"
    const val ERRMSG: String = "errmsg"
}
