package com.localagent.termux

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * Termux [RUN_COMMAND](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent) integration.
 * Mirrors [com.termux.shared.termux.TermuxConstants] string constants without pulling termux-shared.
 */
object TermuxRunCommand {
    const val TERMUX_PACKAGE: String = "com.termux"
    private const val RUN_COMMAND_SERVICE: String = "$TERMUX_PACKAGE.app.RunCommandService"

    const val ACTION_RUN_COMMAND: String = "$TERMUX_PACKAGE.RUN_COMMAND"
    const val EXTRA_COMMAND_PATH: String = "$TERMUX_PACKAGE.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS: String = "$TERMUX_PACKAGE.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_STDIN: String = "$TERMUX_PACKAGE.RUN_COMMAND_STDIN"
    const val EXTRA_WORKDIR: String = "$TERMUX_PACKAGE.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND: String = "$TERMUX_PACKAGE.RUN_COMMAND_BACKGROUND"
    const val EXTRA_COMMAND_LABEL: String = "$TERMUX_PACKAGE.RUN_COMMAND_COMMAND_LABEL"
    const val EXTRA_COMMAND_DESCRIPTION: String = "$TERMUX_PACKAGE.RUN_COMMAND_COMMAND_DESCRIPTION"
    const val EXTRA_PENDING_INTENT: String = "$TERMUX_PACKAGE.RUN_COMMAND_PENDING_INTENT"

    /** Termux usr prefix (POSIX paths inside Termux). */
    const val TERMUX_PREFIX_BIN: String = "/data/data/com.termux/files/usr/bin"

    /**
     * Default Termux user home for [EXTRA_WORKDIR]. Do not use `~/`: Termux resolves cwd with
     * [java.io.File] before spawn — tilde is not expanded and breaks installs/commands.
     */
    const val TERMUX_DEFAULT_HOME: String = "/data/data/com.termux/files/home"

    fun isTermuxInstalled(context: Context): Boolean =
        try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(TERMUX_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(TERMUX_PACKAGE, 0)
            }
            true
        } catch (_: Throwable) {
            false
        }

    fun hasRunCommandPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, "com.termux.permission.RUN_COMMAND") ==
            PackageManager.PERMISSION_GRANTED

    fun notificationsGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /** Termux RUN_COMMAND lives under App info → Permissions and may require approving LocalAgent explicitly in Termux. */
    fun openTermuxAppInfo(context: Context) {
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", TERMUX_PACKAGE, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        context.startActivity(intent)
    }

    fun openLocalAgentAppInfo(context: Context) {
        val pkg = context.packageName
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", pkg, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        context.startActivity(intent)
    }

    /** F-Droid catalog page for Termux (official sideload path). */
    fun openTermuxOnFdroid(context: Context) {
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        context.startActivity(intent)
    }

    /** Per-app notification channel settings (Android 8+). */
    fun openAppNotificationSettings(context: Context) {
        val intent =
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        try {
            context.startActivity(intent)
        } catch (_: Throwable) {
            openLocalAgentAppInfo(context)
        }
    }

    fun ensureRunCommandOrThrow(context: Context) {
        if (!hasRunCommandPermission(context)) {
            throw SecurityException("RUN_COMMAND not granted — open Termux app info → Permissions.")
        }
    }

    fun resultPendingIntent(context: Context, requestCode: Int, kind: String): PendingIntent {
        val intent =
            Intent(context, TermuxRunResultReceiver::class.java).apply {
                action = TermuxRunResultReceiver.ACTION
                putExtra(TermuxRunResultReceiver.EXTRA_KIND, kind)
                setPackage(context.packageName)
            }
        val mutableFlag =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag,
        )
    }

    /**
     * Pipe upstream install.sh into bash -s inside Termux (long-running). Uses background runner.
     */
    fun installHermesFromStdin(context: Context, installScript: String, pendingResult: PendingIntent? = null): Intent {
        val bash = "$TERMUX_PREFIX_BIN/bash"
        return Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, bash)
            // stdin is the script; args after -- are passed to install.sh (non-interactive + skip Playwright on Termux).
            putExtra(EXTRA_ARGUMENTS, arrayOf("-s", "--", "--skip-setup", "--skip-browser"))
            putExtra(EXTRA_STDIN, installScript)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_WORKDIR, TERMUX_DEFAULT_HOME)
            putExtra(EXTRA_COMMAND_LABEL, "Hermes install")
            putExtra(
                EXTRA_COMMAND_DESCRIPTION,
                "Runs install.sh via bash -s with --skip-setup --skip-browser (piped stdin, background).",
            )
            pendingResult?.let { putExtra(EXTRA_PENDING_INTENT, it) }
        }
    }

    fun foregroundInteractiveHermes(context: Context): Intent =
        Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, "$TERMUX_PREFIX_BIN/hermes")
            putExtra(EXTRA_ARGUMENTS, emptyArray<String>())
            putExtra(EXTRA_BACKGROUND, false)
            putExtra(EXTRA_WORKDIR, TERMUX_DEFAULT_HOME)
            putExtra(EXTRA_COMMAND_LABEL, "Hermes")
            putExtra(EXTRA_COMMAND_DESCRIPTION, "Start Hermes CLI in a Termux terminal session.")
        }

    /** Same device / OAuth flows as desktop (`hermes model`); must run in a foreground Termux session. */
    fun foregroundHermesModel(context: Context): Intent =
        Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, "$TERMUX_PREFIX_BIN/hermes")
            putExtra(EXTRA_ARGUMENTS, arrayOf("model"))
            putExtra(EXTRA_BACKGROUND, false)
            putExtra(EXTRA_WORKDIR, TERMUX_DEFAULT_HOME)
            putExtra(EXTRA_COMMAND_LABEL, "hermes model")
            putExtra(EXTRA_COMMAND_DESCRIPTION, "Interactive provider and auth setup (ChatGPT / Codex-style login in Termux).")
        }

    /**
     * Best-effort stop for `hermes` processes inside Termux (RUN_COMMAND background).
     * Does not stop arbitrary `bash` installers; use Termux’s notification “Stop” for those.
     */
    fun backgroundKillHermesCli(context: Context, pendingResult: PendingIntent? = null): Intent {
        val bash = "$TERMUX_PREFIX_BIN/bash"
        val killRc =
            "killall -TERM hermes 2>/dev/null; sleep 0.35; killall -9 hermes 2>/dev/null; exit 0"
        return Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, bash)
            putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", killRc))
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_WORKDIR, TERMUX_DEFAULT_HOME)
            putExtra(EXTRA_COMMAND_LABEL, "Stop Hermes CLI")
            putExtra(
                EXTRA_COMMAND_DESCRIPTION,
                "Sends killall to Hermes processes in Termux (TERM then KILL).",
            )
            pendingResult?.let { putExtra(EXTRA_PENDING_INTENT, it) }
        }
    }

    fun backgroundDoctor(context: Context, pendingResult: PendingIntent? = null): Intent =
        Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, "$TERMUX_PREFIX_BIN/hermes")
            putExtra(EXTRA_ARGUMENTS, arrayOf("doctor"))
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_WORKDIR, TERMUX_DEFAULT_HOME)
            putExtra(EXTRA_COMMAND_LABEL, "hermes doctor")
            putExtra(EXTRA_COMMAND_DESCRIPTION, "Runs `hermes doctor` in Termux (background). Check Termux notifications for output.")
            pendingResult?.let { putExtra(EXTRA_PENDING_INTENT, it) }
        }

    fun backgroundChat(context: Context, message: String): Intent =
        Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, "$TERMUX_PREFIX_BIN/hermes")
            putExtra(EXTRA_ARGUMENTS, arrayOf("chat", message))
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_WORKDIR, TERMUX_DEFAULT_HOME)
            putExtra(EXTRA_COMMAND_LABEL, "Hermes chat")
            putExtra(EXTRA_COMMAND_DESCRIPTION, "Sends a message to Hermes via background intent.")
        }

    fun backgroundApprove(context: Context, promptId: String): Intent =
        Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, "$TERMUX_PREFIX_BIN/hermes")
            putExtra(EXTRA_ARGUMENTS, arrayOf("approve", promptId))
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_WORKDIR, TERMUX_DEFAULT_HOME)
            putExtra(EXTRA_COMMAND_LABEL, "Hermes approve")
            putExtra(EXTRA_COMMAND_DESCRIPTION, "Approves a pending agent action.")
        }

    fun backgroundDeny(context: Context, promptId: String): Intent =
        Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, "$TERMUX_PREFIX_BIN/hermes")
            putExtra(EXTRA_ARGUMENTS, arrayOf("deny", promptId))
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_WORKDIR, TERMUX_DEFAULT_HOME)
            putExtra(EXTRA_COMMAND_LABEL, "Hermes deny")
            putExtra(EXTRA_COMMAND_DESCRIPTION, "Denies a pending agent action.")
        }

    /**
     * Writes merged `.env` lines into Termux `~/.hermes/.env` via bash heredoc (stdin script).
     */
    fun pushSandboxSkillsStdin(context: Context, bashScriptBody: String, pendingResult: PendingIntent? = null): Intent {
        val bash = "$TERMUX_PREFIX_BIN/bash"
        return Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, bash)
            putExtra(EXTRA_ARGUMENTS, arrayOf("-s"))
            putExtra(EXTRA_STDIN, bashScriptBody)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_WORKDIR, TERMUX_DEFAULT_HOME)
            putExtra(EXTRA_COMMAND_LABEL, "Hermes skills sync")
            putExtra(
                EXTRA_COMMAND_DESCRIPTION,
                "Copies LocalAgent sandbox ~/.hermes/skills files into Termux ~/.hermes/skills via bash stdin.",
            )
            pendingResult?.let { putExtra(EXTRA_PENDING_INTENT, it) }
        }
    }

    fun pushHermesDotEnvStdin(context: Context, dotEnvBody: String, pendingResult: PendingIntent? = null): Intent {
        val delim = "LA_ENV_${UUID.randomUUID().toString().replace("-", "")}_EOF"
        val script =
            buildString {
                appendLine("set -e")
                appendLine("mkdir -p ~/.hermes")
                appendLine("cat > ~/.hermes/.env <<'$delim'")
                append(dotEnvBody.trimEnd())
                if (!dotEnvBody.endsWith('\n')) appendLine()
                appendLine(delim)
                appendLine("chmod 600 ~/.hermes/.env 2>/dev/null || true")
            }
        val bash = "$TERMUX_PREFIX_BIN/bash"
        return Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, bash)
            putExtra(EXTRA_ARGUMENTS, arrayOf("-s"))
            putExtra(EXTRA_STDIN, script)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_WORKDIR, TERMUX_DEFAULT_HOME)
            putExtra(EXTRA_COMMAND_LABEL, "Hermes .env sync")
            putExtra(EXTRA_COMMAND_DESCRIPTION, "Writes LocalAgent merged bridge/API vars into ~/.hermes/.env")
            pendingResult?.let { putExtra(EXTRA_PENDING_INTENT, it) }
        }
    }

    fun start(context: Context, intent: Intent) {
        ensureRunCommandOrThrow(context)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        } catch (e: IllegalStateException) {
            context.startService(intent)
        } catch (e: SecurityException) {
            throw SecurityException(
                "RUN_COMMAND rejected by Termux — open Termux → Settings → Apps → allow ${context.packageName}",
                e,
            )
        }
    }
}
