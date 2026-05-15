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

import androidx.compose.material3.Slider
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelRoute(windowSizeClass: WindowSizeClass) {
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
    val nCtx by llm.nCtxFlow().collectAsStateWithLifecycle(initialValue = 4096)
    val nThreads by llm.nThreadsFlow().collectAsStateWithLifecycle(initialValue = 4)
    val nGpuLayers by llm.nGpuLayersFlow().collectAsStateWithLifecycle(initialValue = 0)

    var oneClickBusy by remember { mutableStateOf(false) }
    val piOneClickEnv =
        remember(ctx.applicationContext) {
            TermuxRunCommand.resultPendingIntent(ctx.applicationContext, 4411, "one_click_env")
        }

    val isExpanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

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
            if (isExpanded) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ModelIntroSection(ctx)
                        OneClickSection(scope, llm, ctx, routingStore, container, piOneClickEnv, downloading, oneClickBusy) { oneClickBusy = it; status = if(it) "" else status }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PerformanceTuningSection(llm, scope, gpuExperimental, nCtx, nThreads, nGpuLayers)
                    }
                }
            } else {
                ModelIntroSection(ctx)
                OneClickSection(scope, llm, ctx, routingStore, container, piOneClickEnv, downloading, oneClickBusy) { oneClickBusy = it; status = if(it) "" else status }
                PerformanceTuningSection(llm, scope, gpuExperimental, nCtx, nThreads, nGpuLayers)
            }

            Spacer(Modifier.padding(vertical = 8.dp))
            Text("Advanced Configuration", style = MaterialTheme.typography.titleMedium)

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
                                llm.loadModel(target.absolutePath)
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
                    scope.launch {
                        llm.unload()
                        llm.stopHttpServer()
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

@Composable
private fun ModelIntroSection(ctx: android.content.Context) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.local_model_intro),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            HermesBridgeEnv.build(ctx).getValue("OPENAI_BASE_URL"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun OneClickSection(
    scope: kotlinx.coroutines.CoroutineScope,
    llm: com.localagent.llm.LocalLlmService,
    ctx: android.content.Context,
    routingStore: com.localagent.auth.OpenAiRoutingStore,
    container: com.localagent.di.AppContainer,
    piOneClickEnv: android.app.PendingIntent,
    downloading: Boolean,
    oneClickBusy: Boolean,
    onBusyChange: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    onBusyChange(true)
                    llm
                        .bootstrapBundledQwenLocalFirst(
                            ctx,
                            routingStore,
                            container.envWriter,
                            container.credentialVault.snapshot(),
                        ).onSuccess {
                            val merged =
                                container.envWriter.mergedDotEnvContent(
                                    container.credentialVault.snapshot(),
                                )
                            if (TermuxRunCommand.isTermuxInstalled(ctx) &&
                                TermuxRunCommand.hasRunCommandPermission(ctx)) {
                                TermuxRunCommand.start(
                                    ctx,
                                    TermuxRunCommand.pushHermesDotEnvStdin(
                                        ctx,
                                        merged,
                                        piOneClickEnv,
                                    ),
                                )
                            }
                        }
                    onBusyChange(false)
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
    }
}

@Composable
private fun PerformanceTuningSection(
    llm: com.localagent.llm.LocalLlmService,
    scope: kotlinx.coroutines.CoroutineScope,
    gpuExperimental: Boolean,
    nCtx: Int,
    nThreads: Int,
    nGpuLayers: Int
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Performance Tuning", style = MaterialTheme.typography.titleMedium)
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Vulkan GPU Acceleration", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = gpuExperimental,
                onCheckedChange = { checked ->
                    scope.launch { llm.setExperimentalGpuLayers(checked) }
                },
            )
        }

        TuningSlider("Context Window", nCtx.toFloat(), 512f, 16384f, 512f) { 
            scope.launch { llm.setNCtx(it.toInt()) }
        }
        
        TuningSlider("CPU Threads", nThreads.toFloat(), 1f, 12f, 1f) { 
            scope.launch { llm.setNThreads(it.toInt()) }
        }

        if (gpuExperimental) {
            TuningSlider("GPU Layers", nGpuLayers.toFloat(), 0f, 99f, 1f) { 
                scope.launch { llm.setNGpuLayers(it.toInt()) }
            }
        }
    }
}

@Composable
private fun TuningSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value.toInt().toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = min..max,
            steps = ((max - min) / step).toInt() - 1
        )
    }
}
