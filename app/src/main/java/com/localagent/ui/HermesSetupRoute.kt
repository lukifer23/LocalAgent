package com.localagent.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localagent.LocalAgentApp
import com.localagent.R
import com.localagent.runtime.DeviceIpv4
import com.localagent.runtime.HermesBridgeEnv
import com.localagent.runtime.HermesPaths
import com.localagent.runtime.SandboxSkills
import com.localagent.termux.TermuxRunCommand
import com.localagent.termux.TermuxRunResultSummary
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File

private enum class PendingTermuxAction {
    None,
    InstallHermes,
    PushEnv,
    KillHermes,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesSetupRoute() {
    val context = LocalContext.current
    val app = context.applicationContext as LocalAgentApp
    val container = app.container
    val coordinator = container.hermesSetup
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val activityVm: ActivityLogViewModel = viewModel(factory = ActivityLogViewModelFactory(container))

    val appCtx = context.applicationContext
    val piInstall = remember(appCtx) { TermuxRunCommand.resultPendingIntent(appCtx, 4401, "install") }
    val piDoctor = remember(appCtx) { TermuxRunCommand.resultPendingIntent(appCtx, 4402, "doctor") }
    val piPushEnv = remember(appCtx) { TermuxRunCommand.resultPendingIntent(appCtx, 4403, "push_env") }
    val piSkills = remember(appCtx) { TermuxRunCommand.resultPendingIntent(appCtx, 4404, "push_skills") }
    val piKill = remember(appCtx) { TermuxRunCommand.resultPendingIntent(appCtx, 4405, "kill_hermes") }

    val sandboxSkillsDir = remember(context) { File(HermesPaths.hermesRoot(context), "skills") }

    var bridgeStatusLine by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        coroutineScope {
            launch {
                container.termuxRunResults.collect { s ->
                    val internalOk = s.pluginErr == null || s.pluginErr == Activity.RESULT_OK
                    val failed = s.exitCode != 0 || !internalOk
                    if (failed) {
                        val tail =
                            listOfNotNull(s.stderr?.lineSequence()?.firstOrNull()?.trim(), s.errmsg?.lineSequence()?.firstOrNull()?.trim())
                                .filter { it.isNotEmpty() }
                                .joinToString(" │ ")
                                .take(220)
                        val msg =
                            buildString {
                                append("[${s.kind}] exit=${s.exitCode}")
                                if (!internalOk) {
                                    append(" pluginErr=").append(s.pluginErr)
                                }
                                if (tail.isNotEmpty()) {
                                    append(" — ").append(tail)
                                }
                            }
                        snackbarHostState.showSnackbar(msg)
                    }
                }
            }
            launch {
                container.bridgeServer.diagnostics.collect { d ->
                    bridgeStatusLine = "${d.kind}: ${d.message.take(120)}"
                }
            }
        }
    }

    val termuxInstalled = TermuxRunCommand.isTermuxInstalled(context)
    var runCmdGranted by remember { mutableStateOf(TermuxRunCommand.hasRunCommandPermission(context)) }
    var postNotifGranted by remember { mutableStateOf(TermuxRunCommand.notificationsGranted(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val obs =
            LifecycleEventObserver { _, ev ->
                if (ev == Lifecycle.Event.ON_RESUME) {
                    runCmdGranted = TermuxRunCommand.hasRunCommandPermission(context)
                    postNotifGranted = TermuxRunCommand.notificationsGranted(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    var pendingRunCmd by remember { mutableStateOf(PendingTermuxAction.None) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            runCmdGranted = ok
            scope.launch {
                snackbarHostState.showSnackbar(
                    if (ok) {
                        context.getString(R.string.hermes_perm_granted)
                    } else {
                        context.getString(R.string.hermes_perm_denied)
                    },
                )
                if (ok) {
                    when (pendingRunCmd) {
                        PendingTermuxAction.InstallHermes -> {
                            val script = coordinator.cachedInstallScriptText()
                            if (script != null && termuxInstalled) {
                                runCatching {
                                    TermuxRunCommand.start(context, TermuxRunCommand.installHermesFromStdin(context, script, piInstall))
                                    snackbarHostState.showSnackbar(context.getString(R.string.hermes_install_started))
                                }.onFailure { e ->
                                    snackbarHostState.showSnackbar(e.message ?: context.getString(R.string.hermes_install_failed))
                                }
                            }
                        }
                        PendingTermuxAction.PushEnv -> {
                            val merged = container.envWriter.mergedDotEnvContent(container.credentialVault.snapshot())
                            runCatching {
                                TermuxRunCommand.start(context, TermuxRunCommand.pushHermesDotEnvStdin(context, merged, piPushEnv))
                                snackbarHostState.showSnackbar(context.getString(R.string.hermes_push_env_started))
                            }.onFailure { e ->
                                snackbarHostState.showSnackbar(e.message ?: context.getString(R.string.hermes_install_failed))
                            }
                        }
                        PendingTermuxAction.KillHermes ->
                            runCatching {
                                TermuxRunCommand.start(
                                    context,
                                    TermuxRunCommand.backgroundKillHermesCli(context, piKill),
                                )
                                snackbarHostState.showSnackbar(context.getString(R.string.hermes_kill_termux_started))
                            }.onFailure { e ->
                                snackbarHostState.showSnackbar(e.message ?: context.getString(R.string.hermes_install_failed))
                            }
                        PendingTermuxAction.None -> Unit
                    }
                    pendingRunCmd = PendingTermuxAction.None
                } else {
                    pendingRunCmd = PendingTermuxAction.None
                }
            }
        }

    val notifPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            postNotifGranted = ok || Build.VERSION.SDK_INT < 33
            scope.launch {
                snackbarHostState.showSnackbar(
                    if (ok) {
                        context.getString(R.string.hermes_post_notif_granted)
                    } else {
                        context.getString(R.string.hermes_post_notif_denied)
                    },
                )
            }
        }

    var downloading by remember { mutableStateOf(false) }
    var hasScript by remember { mutableStateOf(coordinator.hasCachedInstallScript()) }
    var probing by remember { mutableStateOf(false) }
    var probeOk by remember { mutableStateOf<Boolean?>(null) }

    val hasIpv4 = DeviceIpv4.primarySiteLocalIpv4(context) != null
    val bridgeSnippet = HermesBridgeEnv.bridgeSnippet(context)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.hermes_setup_title)) }) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.hermes_bridge_listen_warning), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.hermes_bridge_auth_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.hermes_bridge_auth_body), style = MaterialTheme.typography.bodySmall)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.hermes_session_control_title), style = MaterialTheme.typography.titleSmall)
                    if (bridgeStatusLine.isNotBlank()) {
                        Text(
                            stringResource(R.string.hermes_bridge_status_line, bridgeStatusLine),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(stringResource(R.string.hermes_session_control_body), style = MaterialTheme.typography.bodySmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                val n = container.bridgeServer.disconnectAllClients()
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.hermes_disconnect_bridge_done, n),
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.LinkOff, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                            Text(stringResource(R.string.hermes_disconnect_bridge_clients), maxLines = 2)
                        }
                        OutlinedButton(
                            onClick = {
                                if (!runCmdGranted) {
                                    pendingRunCmd = PendingTermuxAction.KillHermes
                                    permissionLauncher.launch("com.termux.permission.RUN_COMMAND")
                                    return@OutlinedButton
                                }
                                try {
                                    TermuxRunCommand.start(
                                        context,
                                        TermuxRunCommand.backgroundKillHermesCli(context, piKill),
                                    )
                                    scope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.hermes_kill_termux_started))
                                    }
                                } catch (e: Exception) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            e.message ?: context.getString(R.string.hermes_install_failed),
                                        )
                                    }
                                }
                            },
                            enabled = termuxInstalled,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                            Text(stringResource(R.string.hermes_kill_termux_hermes), maxLines = 2)
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.hermes_activity_log_title), style = MaterialTheme.typography.titleSmall)
                        TextButton(
                            onClick = { activityVm.clear() },
                            enabled = activityVm.logLines.isNotEmpty(),
                        ) {
                            Text(stringResource(R.string.hermes_activity_log_clear))
                        }
                    }
                    val innerScroll = rememberScrollState()
                    if (activityVm.logLines.isEmpty()) {
                        Text(
                            stringResource(R.string.hermes_activity_log_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else {
                        Text(
                            activityVm.logLines.joinToString("\n\n"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier =
                                Modifier
                                    .padding(top = 8.dp)
                                    .heightIn(max = 320.dp)
                                    .verticalScroll(innerScroll)
                                    .fillMaxWidth(),
                        )
                    }
                }
            }

            Text(
                stringResource(
                    R.string.hermes_termux_status,
                    stringResource(if (termuxInstalled) R.string.yes else R.string.no),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(
                    R.string.hermes_run_cmd_status,
                    stringResource(if (runCmdGranted) R.string.yes else R.string.no),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )

            if (!termuxInstalled) {
                OutlinedButton(
                    onClick = { TermuxRunCommand.openTermuxOnFdroid(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.hermes_install_termux_fdroid))
                }
            }

            Text(stringResource(R.string.hermes_steps_run_cmd), style = MaterialTheme.typography.bodySmall)

            if (!runCmdGranted) {
                Button(
                    onClick = {
                        pendingRunCmd = PendingTermuxAction.None
                        permissionLauncher.launch("com.termux.permission.RUN_COMMAND")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.hermes_request_termux_permission))
                }
            }

            if (Build.VERSION.SDK_INT >= 33) {
                Text(
                    stringResource(
                        R.string.hermes_notifications_status,
                        stringResource(if (postNotifGranted) R.string.yes else R.string.no),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (!postNotifGranted) {
                    Button(
                        onClick = { notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.hermes_request_notifications))
                    }
                    OutlinedButton(
                        onClick = { TermuxRunCommand.openAppNotificationSettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.hermes_open_notification_settings))
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.hermes_permissions_card_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.hermes_permissions_card_body), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = { TermuxRunCommand.openTermuxAppInfo(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.hermes_open_termux_permissions))
                    }
                    OutlinedButton(
                        onClick = { TermuxRunCommand.openLocalAgentAppInfo(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.hermes_open_localagent_settings))
                    }
                }
            }

            Text(
                stringResource(
                    R.string.hermes_ipv4_status,
                    stringResource(if (hasIpv4) R.string.yes else R.string.no),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (HermesBridgeEnv.reachabilityNote(hasIpv4).isNotEmpty()) {
                Text(HermesBridgeEnv.reachabilityNote(hasIpv4), style = MaterialTheme.typography.bodySmall)
            }

            Text(
                "${stringResource(R.string.hermes_advertised_host)} ${HermesBridgeEnv.bridgeHost(context)}:${HermesPaths.bridgePort()}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${stringResource(R.string.hermes_llm_http)} ${HermesBridgeEnv.bridgeHost(context)}:${HermesPaths.LLM_HTTP_PORT}",
                style = MaterialTheme.typography.bodyMedium,
            )

            BridgeEnvActions(
                onCopyBridge = {
                    clipboard.setText(AnnotatedString(HermesBridgeEnv.bridgeSnippet(context)))
                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.hermes_copied_clipboard)) }
                },
                onRefreshEnv = {
                    container.envWriter.syncFromVault(container.credentialVault.snapshot())
                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.hermes_env_rewritten)) }
                },
                onRegenerateBridgeToken = {
                    container.credentialVault.regenerateBridgeAuthToken()
                    container.envWriter.syncFromVault(container.credentialVault.snapshot())
                    scope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.hermes_token_regenerated))
                    }
                },
            )

            OutlinedButton(
                onClick = {
                    val merged = container.envWriter.mergedDotEnvContent(container.credentialVault.snapshot())
                    if (!runCmdGranted) {
                        pendingRunCmd = PendingTermuxAction.PushEnv
                        permissionLauncher.launch("com.termux.permission.RUN_COMMAND")
                        return@OutlinedButton
                    }
                    try {
                        TermuxRunCommand.start(context, TermuxRunCommand.pushHermesDotEnvStdin(context, merged, piPushEnv))
                        scope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.hermes_push_env_started))
                        }
                    } catch (e: Exception) {
                        scope.launch {
                            snackbarHostState.showSnackbar(e.message ?: context.getString(R.string.hermes_install_failed))
                        }
                    }
                },
                enabled = termuxInstalled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.hermes_push_termux_env))
            }

            Button(
                onClick = {
                    probing = true
                    probeOk = null
                    scope.launch {
                        probeOk = coordinator.probeBridgeTcp()
                        probing = false
                        snackbarHostState.showSnackbar(
                            if (probeOk == true) {
                                context.getString(R.string.hermes_probe_ok)
                            } else {
                                context.getString(R.string.hermes_probe_fail)
                            },
                        )
                    }
                },
                enabled = !probing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (probing) stringResource(R.string.hermes_probing) else stringResource(R.string.hermes_probe_bridge))
            }
            when (probeOk) {
                true ->
                    Text(stringResource(R.string.hermes_last_probe_ok), style = MaterialTheme.typography.bodySmall)
                false ->
                    Text(stringResource(R.string.hermes_last_probe_fail), style = MaterialTheme.typography.bodySmall)
                null -> Unit
            }

            Text(stringResource(R.string.hermes_install_script_help), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = {
                    downloading = true
                    scope.launch {
                        val r = coordinator.downloadInstallScript()
                        downloading = false
                        hasScript = coordinator.hasCachedInstallScript()
                        r.onSuccess {
                            snackbarHostState.showSnackbar(context.getString(R.string.hermes_script_downloaded))
                        }
                        r.onFailure { e ->
                            snackbarHostState.showSnackbar(e.message ?: context.getString(R.string.hermes_download_failed))
                        }
                    }
                },
                enabled = !downloading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (downloading) {
                            stringResource(R.string.hermes_downloading)
                        } else {
                            stringResource(R.string.hermes_download_installer)
                        },
                    )
                }
            }

            Button(
                onClick = {
                    val script = coordinator.cachedInstallScriptText()
                    if (script == null) {
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.hermes_need_script_first)) }
                        return@Button
                    }
                    if (!runCmdGranted) {
                        pendingRunCmd = PendingTermuxAction.InstallHermes
                        permissionLauncher.launch("com.termux.permission.RUN_COMMAND")
                        return@Button
                    }
                    try {
                        TermuxRunCommand.start(context, TermuxRunCommand.installHermesFromStdin(context, script, piInstall))
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.hermes_install_started)) }
                    } catch (e: Exception) {
                        scope.launch {
                            snackbarHostState.showSnackbar(e.message ?: context.getString(R.string.hermes_install_failed))
                        }
                    }
                },
                enabled = termuxInstalled && hasScript,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.hermes_run_install_termux))
            }

            OutlinedButton(
                onClick = {
                    try {
                        TermuxRunCommand.start(context, TermuxRunCommand.foregroundInteractiveHermes(context))
                    } catch (e: Exception) {
                        scope.launch {
                            snackbarHostState.showSnackbar(e.message ?: context.getString(R.string.hermes_termux_start_failed))
                        }
                    }
                },
                enabled = termuxInstalled && runCmdGranted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.hermes_launch_cli_termux))
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.hermes_chatgpt_auth_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.hermes_chatgpt_auth_body),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    OutlinedButton(
                        onClick = {
                            try {
                                TermuxRunCommand.start(context, TermuxRunCommand.foregroundHermesModel(context))
                            } catch (e: Exception) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(e.message ?: context.getString(R.string.hermes_termux_start_failed))
                                }
                            }
                        },
                        enabled = termuxInstalled && runCmdGranted,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) {
                        Text(stringResource(R.string.hermes_open_model_picker_termux))
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    try {
                        TermuxRunCommand.start(context, TermuxRunCommand.backgroundDoctor(context, piDoctor))
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.hermes_doctor_started)) }
                    } catch (e: Exception) {
                        scope.launch {
                            snackbarHostState.showSnackbar(e.message ?: context.getString(R.string.hermes_doctor_failed))
                        }
                    }
                },
                enabled = termuxInstalled && runCmdGranted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.hermes_run_doctor))
            }

            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://hermes-agent.nousresearch.com/docs/getting-started/termux")),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.OpenInBrowser, contentDescription = null)
                Text(stringResource(R.string.hermes_open_termux_docs), modifier = Modifier.padding(start = 8.dp))
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.hermes_terminal_vs_termux_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.hermes_terminal_vs_termux_body),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.hermes_skills_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        sandboxSkillsDir.takeIf { it.isDirectory }
                            ?.listFiles()
                            ?.map { it.name }
                            ?.sorted()
                            ?.joinToString(", ")
                            .orEmpty()
                            .ifBlank { stringResource(R.string.hermes_skills_empty) },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(stringResource(R.string.hermes_skills_body), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    OutlinedButton(
                        onClick = {
                            val script =
                                SandboxSkills.buildTermuxPushScript(context).getOrElse { e ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(e.message ?: context.getString(R.string.hermes_skills_push_failed))
                                    }
                                    return@OutlinedButton
                                }
                            try {
                                TermuxRunCommand.start(context, TermuxRunCommand.pushSandboxSkillsStdin(context, script, piSkills))
                                scope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.hermes_skills_push_started))
                                }
                            } catch (e: Exception) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(e.message ?: context.getString(R.string.hermes_skills_push_failed))
                                }
                            }
                        },
                        enabled = termuxInstalled && runCmdGranted && sandboxSkillsDir.isDirectory &&
                            sandboxSkillsDir.listFiles()?.any { it.isFile } == true,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) {
                        Text(stringResource(R.string.hermes_push_skills_termux))
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.hermes_manual_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.hermes_manual_body),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.hermes_termux_env_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.hermes_termux_env_body), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun BridgeEnvActions(
    onCopyBridge: () -> Unit,
    onRefreshEnv: () -> Unit,
    onRegenerateBridgeToken: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCopyBridge, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null)
            Text(stringResource(R.string.hermes_copy_bridge_vars), modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = onRefreshEnv, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.hermes_rewrite_app_env))
        }
        OutlinedButton(onClick = onRegenerateBridgeToken, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.hermes_regenerate_bridge_token))
        }
    }
}
