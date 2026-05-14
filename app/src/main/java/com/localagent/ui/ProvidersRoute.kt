package com.localagent.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import com.localagent.R
import com.localagent.auth.OAuthCoordinator
import com.localagent.auth.OAuthDefinition
import com.localagent.auth.ProviderCatalog
import com.localagent.auth.CredentialVault
import com.localagent.auth.ProviderField
import android.content.Intent
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersRoute() {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    var issuer by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf("") }
    var scopes by remember { mutableStateOf("openid offline_access") }
    var clientSecret by remember { mutableStateOf("") }

    val visibility = remember { mutableStateMapOf<String, Boolean>() }
    val oauth = remember { OAuthCoordinator(container.credentialVault) }

    val askNotif =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
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

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data ?: return@rememberLauncherForActivityResult
            val act = activity
            if (act == null) {
                scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.providers_oauth_need_activity)) }
                return@rememberLauncherForActivityResult
            }
            oauth.completeAuthorization(act, data, clientSecret) { res ->
                scope.launch {
                    if (res.isSuccess) {
                        container.envWriter.syncFromVault(container.credentialVault.snapshot())
                        snackbarHostState.showSnackbar(context.getString(R.string.providers_oauth_connected))
                    } else {
                        snackbarHostState.showSnackbar(
                            res.exceptionOrNull()?.message ?: context.getString(R.string.providers_oauth_failed),
                        )
                    }
                }
            }
        }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.providers_title)) }) },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.providers_intro), style = MaterialTheme.typography.bodyMedium)
            }
            item {
                if (Build.VERSION.SDK_INT >= 33) {
                    OutlinedButton(
                        onClick = { askNotif.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.hermes_request_notifications))
                    }
                }
            }
            item {
                Text(stringResource(R.string.providers_quick_signin), style = MaterialTheme.typography.titleMedium)
            }
            item {
                Button(
                    onClick = {
                        val act = activity
                        if (act == null) {
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(R.string.providers_oauth_need_activity))
                            }
                            return@Button
                        }
                        oauth.fetchGoogleGeminiIntent(act) { intent ->
                            if (intent != null) {
                                launcher.launch(intent)
                                scope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.providers_oauth_started))
                                }
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.providers_google_oauth_unconfigured),
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.providers_sign_in_google))
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://platform.openai.com/api-keys"),
                            ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.providers_openai_get_key))
                }
            }
            item {
                Text(stringResource(R.string.providers_keys_title), style = MaterialTheme.typography.titleMedium)
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AssistChip(
                        onClick = {
                            container.credentialVault.put(
                                com.localagent.auth.CredentialVault.CUSTOM_OPENAI_BASE_URL,
                                "https://api.openai.com/v1",
                            )
                            container.envWriter.syncFromVault(container.credentialVault.snapshot())
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(R.string.providers_preset_openai))
                            }
                        },
                        label = { Text(stringResource(R.string.providers_preset_openai)) },
                    )
                    AssistChip(
                        onClick = {
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(R.string.providers_anthropic_hint))
                            }
                        },
                        label = { Text(stringResource(R.string.providers_preset_anthropic)) },
                    )
                }
            }
            items(
                ProviderCatalog.fields,
                key = { it.prefKey },
            ) { spec ->
                ProviderFieldRow(
                    spec = spec,
                    vault = container.credentialVault,
                    writer = container.envWriter,
                    visible = visibility[spec.prefKey] == true,
                    onToggleVisible = { visibility[spec.prefKey] = !(visibility[spec.prefKey] == true) },
                    onPaste = { clip ->
                        container.credentialVault.put(spec.prefKey, clip)
                        container.envWriter.syncFromVault(container.credentialVault.snapshot())
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.providers_saved)) }
                    },
                    clipboard = clipboard,
                    snackbar = { msg ->
                        if (msg.isNotEmpty()) {
                            scope.launch { snackbarHostState.showSnackbar(msg) }
                        }
                    },
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.providers_oauth_title), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = issuer,
                        onValueChange = { issuer = it },
                        label = { Text(stringResource(R.string.providers_label_issuer)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                capitalization = KeyboardCapitalization.None,
                            ),
                    )
                    OutlinedTextField(
                        value = clientId,
                        onValueChange = { clientId = it },
                        label = { Text(stringResource(R.string.providers_label_client_id)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = scopes,
                        onValueChange = { scopes = it },
                        label = { Text(stringResource(R.string.providers_label_scopes)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = clientSecret,
                        onValueChange = { clientSecret = it },
                        label = { Text(stringResource(R.string.providers_label_client_secret)) },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Button(
                        onClick = {
                            val act = activity
                            if (act == null) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.providers_oauth_need_activity))
                                }
                                return@Button
                            }
                            oauth.setPendingClientSecretForNextFlow(clientSecret.trim().takeIf { it.isNotEmpty() })
                            container.credentialVault.remove(CredentialVault.OAUTH_LAST_FLOW)
                            val def =
                                OAuthDefinition(
                                    issuer = issuer.trim(),
                                    clientId = clientId.trim(),
                                    scopes = scopes.trim(),
                                )
                            oauth.fetchAuthorizationIntent(act, def) { intent ->
                                if (intent != null) {
                                    launcher.launch(intent)
                                    scope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.providers_oauth_started))
                                    }
                                }
                            }
                        },
                        enabled = issuer.isNotBlank() && clientId.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.providers_authorize))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderFieldRow(
    spec: ProviderField,
    vault: com.localagent.auth.CredentialVault,
    writer: com.localagent.auth.HermesEnvWriter,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    onPaste: (String) -> Unit,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    snackbar: (String) -> Unit,
) {
    val context = LocalContext.current
    var value by remember(spec.prefKey) { mutableStateOf(vault.get(spec.prefKey).orEmpty()) }
    val warn = remember(value, spec.prefKey) { ProviderCatalog.formatWarning(spec.prefKey, value) }
    val transformation: VisualTransformation =
        if (spec.isSecret && !visible) PasswordVisualTransformation() else VisualTransformation.None
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = { next ->
                value = next
                vault.put(spec.prefKey, next)
                writer.syncFromVault(vault.snapshot())
            },
            label = { Text(stringResource(spec.labelRes)) },
            supportingText = {
                Column {
                    Text(stringResource(spec.helpRes), style = MaterialTheme.typography.bodySmall)
                    warn?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                }
            },
            visualTransformation = transformation,
            singleLine = spec.prefKey != com.localagent.auth.CredentialVault.CUSTOM_OPENAI_BASE_URL,
            minLines = if (spec.prefKey == com.localagent.auth.CredentialVault.CUSTOM_OPENAI_BASE_URL) 2 else 1,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (spec.isSecret) {
                TextButton(onClick = onToggleVisible) {
                    Text(stringResource(if (visible) R.string.providers_hide else R.string.providers_show))
                }
            }
            TextButton(
                onClick = {
                    val clip = clipboard.getText()?.text?.trim().orEmpty()
                    if (clip.isEmpty()) {
                        snackbar(context.getString(R.string.providers_clipboard_empty))
                    } else {
                        onPaste(clip)
                    }
                },
            ) {
                Text(stringResource(R.string.providers_paste))
            }
            TextButton(
                onClick = {
                    value = ""
                    vault.remove(spec.prefKey)
                    writer.syncFromVault(vault.snapshot())
                },
            ) {
                Text(stringResource(R.string.providers_clear_field))
            }
        }
    }
}
