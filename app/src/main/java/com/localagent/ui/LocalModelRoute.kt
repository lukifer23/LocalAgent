package com.localagent.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localagent.R
import com.localagent.auth.OpenAiRoutingStore
import com.localagent.llm.ModelDownloadManager
import com.localagent.runtime.HermesBridgeEnv
import com.localagent.termux.TermuxRunCommand
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelRoute() {
    val container = LocalAppContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val catalog = remember { ModelDownloadManager.bundledCatalog() }
    var url by remember { mutableStateOf(catalog.first().first) }
    var sha by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var benchLine by remember { mutableStateOf("") }
    var downloading by remember { mutableStateOf(false) }
    val downloader = container.localLlm.modelDownloader()
    val target = remember { downloader.defaultQwenPath() }
    val llm = container.localLlm
    val routingStore = remember(ctx) { OpenAiRoutingStore(ctx) }
    val gpuExperimental by llm.experimentalGpuFlow().collectAsStateWithLifecycle(initialValue = false)
    var oneClickBusy by remember { mutableStateOf(false) }
    val piOneClickEnv =
        remember(ctx.applicationContext) {
            TermuxRunCommand.resultPendingIntent(ctx.applicationContext, 4411, "one_click_env")
        }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.local_model_title)) }) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
                    .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.local_model_intro),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                HermesBridgeEnv.build(ctx).getValue("OPENAI_BASE_URL"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                stringResource(R.string.local_model_one_click_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            if (oneClickBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.local_model_one_click_progress), style = MaterialTheme.typography.labelMedium)
            }
            Button(
                onClick = {
                    scope.launch {
                        oneClickBusy = true
                        llm
                            .bootstrapBundledQwenLocalFirst(
                                ctx,
                                routingStore,
                                container.envWriter,
                                container.credentialVault.snapshot(),
                            ).onSuccess {
                                status = ctx.getString(R.string.local_model_one_click_done)
                                snackbarHostState.showSnackbar(status)
                                val merged =
                                    container.envWriter.mergedDotEnvContent(
                                        container.credentialVault.snapshot(),
                                    )
                                when {
                                    TermuxRunCommand.isTermuxInstalled(ctx) &&
                                        TermuxRunCommand.hasRunCommandPermission(ctx) ->
                                        runCatching {
                                            TermuxRunCommand.start(
                                                ctx,
                                                TermuxRunCommand.pushHermesDotEnvStdin(
                                                    ctx,
                                                    merged,
                                                    piOneClickEnv,
                                                ),
                                            )
                                            snackbarHostState.showSnackbar(
                                                ctx.getString(R.string.local_model_termux_push_started),
                                            )
                                        }.onFailure { e ->
                                            snackbarHostState.showSnackbar(
                                                e.message ?: ctx.getString(R.string.local_model_termux_push_failed),
                                            )
                                        }
                                    else ->
                                        snackbarHostState.showSnackbar(
                                            ctx.getString(R.string.local_model_termux_push_skipped),
                                        )
                                }
                            }.onFailure {
                                status = it.message ?: ctx.getString(R.string.hermes_download_failed)
                                snackbarHostState.showSnackbar(status)
                            }
                        oneClickBusy = false
                    }
                },
                enabled = !downloading && !oneClickBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.local_model_one_click))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.local_model_experimental_gpu), modifier = Modifier.weight(1f))
                Switch(
                    checked = gpuExperimental,
                    onCheckedChange = { checked ->
                        scope.launch { llm.setExperimentalGpuLayers(checked) }
                    },
                )
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        llm.benchWarmupOnce().fold(
                            onSuccess = { b ->
                                benchLine =
                                    ctx.getString(
                                        R.string.local_model_bench_result,
                                        b.latencyMs.toInt(),
                                        b.approxTokensPerSec.toString(),
                                        b.outputChars,
                                    )
                                snackbarHostState.showSnackbar(benchLine)
                            },
                            onFailure = { e ->
                                benchLine = e.message ?: "bench failed"
                                snackbarHostState.showSnackbar(benchLine)
                            },
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.local_model_bench))
            }
            if (benchLine.isNotBlank()) {
                Text(benchLine, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.local_model_url_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = sha,
                onValueChange = { sha = it },
                label = { Text(stringResource(R.string.local_model_sha_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (downloading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.local_model_download_progress), style = MaterialTheme.typography.labelMedium)
            }
            Button(
                onClick = {
                    scope.launch {
                        downloading = true
                        runCatching {
                            downloader.download(url, target, sha.ifBlank { null })
                            status = "${ctx.getString(R.string.hermes_script_downloaded)} → ${target.absolutePath}"
                            snackbarHostState.showSnackbar(status)
                        }.onFailure {
                            status = it.message ?: ctx.getString(R.string.hermes_download_failed)
                            snackbarHostState.showSnackbar(status)
                        }
                        downloading = false
                    }
                },
                enabled = !downloading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.local_model_download))
                }
            }
            Button(
                onClick = {
                    scope.launch {
                        runCatching {
                            val ok =
                                llm.loadModel(
                                    target.absolutePath,
                                    nGpuLayers = null,
                                    nCtx = 4096,
                                    nThreads = null,
                                )
                            if (ok) {
                                llm.startHttpServer()
                                status =
                                    "${ctx.getString(R.string.local_model_loaded)} · ${ctx.getString(R.string.local_model_http_listening)} · ${llm.endpointBase(ctx)}"
                            } else {
                                status = ctx.getString(R.string.hermes_download_failed)
                            }
                            snackbarHostState.showSnackbar(status)
                        }.onFailure {
                            status = it.message ?: ctx.getString(R.string.hermes_download_failed)
                            snackbarHostState.showSnackbar(status)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.local_model_load))
                }
            }
            Button(
                onClick = {
                    llm.startHttpServer()
                    scope.launch {
                        snackbarHostState.showSnackbar("${ctx.getString(R.string.local_model_http_listening)} ${llm.endpointBase(ctx)}")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.local_model_start_http))
                }
            }
            Button(
                onClick = {
                    llm.stopHttpServer()
                    scope.launch {
                        llm.unload()
                        snackbarHostState.showSnackbar(ctx.getString(R.string.local_model_stop))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.local_model_stop))
                }
            }
            if (status.isNotBlank()) {
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
