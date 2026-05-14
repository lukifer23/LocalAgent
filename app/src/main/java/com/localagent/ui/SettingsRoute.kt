package com.localagent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localagent.R
import com.localagent.bridge.BridgeDiagEvent
import com.localagent.runtime.HermesPaths
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute() {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snack = remember { SnackbarHostState() }
    val bridgeWifiOnly by container.bridgeWifiOnly.collectAsStateWithLifecycle()

    val logLines = remember { mutableStateListOf<String>() }

    LaunchedEffect(container) {
        container.bridgeServer.diagnostics.collect { ev: BridgeDiagEvent ->
            logLines.add(ev.line())
            while (logLines.size > 250) {
                logLines.removeAt(0)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_screen_title)) }) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .fillMaxSize(),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(
                    stringResource(R.string.security_lan_notice),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.settings_connection_note), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.settings_bridge_wifi_only),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = bridgeWifiOnly,
                    onCheckedChange = { on ->
                        scope.launch {
                            container.setBridgeWifiOnly(on)
                            snack.showSnackbar(context.getString(R.string.settings_network_policy_saved))
                        }
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.settings_synthetic_home), style = MaterialTheme.typography.titleMedium)
            Text(HermesPaths.syntheticHome(context).absolutePath, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.settings_bridge_hint), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.settings_bridge_port, HermesPaths.bridgePort()))
            Text(stringResource(R.string.settings_llm_port, HermesPaths.LLM_HTTP_PORT))
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = {
                    val slice = container.credentialVault.bridgeAuthToken().take(24)
                    clipboard.setText(AnnotatedString(slice))
                    scope.launch { snack.showSnackbar(context.getString(R.string.settings_copied)) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_copy_bridge_token))
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.settings_bridge_log_title), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = {
                    val blob = logLines.joinToString("\n")
                    clipboard.setText(AnnotatedString(blob))
                    scope.launch { snack.showSnackbar(context.getString(R.string.settings_copied)) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_diag_copy))
            }
            LazyColumn(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 8.dp),
            ) {
                if (logLines.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.settings_bridge_log_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                } else {
                    itemsIndexed(logLines, key = { ix, line -> ix to line.hashCode() }) { _, line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
            TextButton(
                onClick = {
                    scope.launch {
                        container.resetOnboarding()
                        snack.showSnackbar(context.getString(R.string.settings_onboarding_again))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_onboarding_again))
            }
        }
    }
}
