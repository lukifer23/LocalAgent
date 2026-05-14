package com.localagent.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localagent.R
import com.localagent.auth.OpenAiRoutingStore
import com.localagent.bridge.Role
import com.localagent.ui.markdown.AssistantMarkdown
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRoute() {
    val container = LocalAppContainer.current
    val vm: ChatViewModel =
        viewModel(
            factory =
                ChatViewModelFactory(
                    container.appContext,
                    container.chatRepository,
                    container.localLlm,
                    container.credentialVault,
                    container.envWriter,
                ),
        )
    val state by vm.state.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val modelSheetVisible by vm.modelSheetVisible.collectAsStateWithLifecycle()
    val routingMode by vm.routingMode.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.hints.collect { snackbarHostState.showSnackbar(it) }
    }

    val lastScrollTarget = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.lines.lastOrNull()?.id) {
        val id = state.lines.lastOrNull()?.id ?: return@LaunchedEffect
        if (id != lastScrollTarget.value && state.lines.isNotEmpty()) {
            lastScrollTarget.value = id
            listState.animateScrollToItem(state.lines.size - 1)
        }
    }

    if (modelSheetVisible) {
        ModelRoutingSheet(
            vm = vm,
            currentMode = routingMode,
            onDismiss = { vm.dismissModelSheet() },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    stringResource(R.string.chat_history_title),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (sessions.isEmpty()) {
                    Text(
                        stringResource(R.string.chat_empty_sessions),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                LazyColumn {
                    items(sessions, key = { it.id }) { session ->
                        NavigationDrawerItem(
                            label = { Text(session.title) },
                            selected = session.id == state.sessionId,
                            onClick = {
                                vm.selectSession(session.id)
                                scope.launch { drawerState.close() }
                            },
                            icon = { Icon(Icons.Default.Chat, contentDescription = null) },
                            badge = {
                                IconButton(onClick = { vm.deleteSession(session.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.chat_drawer_delete))
                                }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.chat_title)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.chat_drawer_menu))
                        }
                    },
                )
            },
            bottomBar = {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                            .imePadding(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.chat_message_placeholder)) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                vm.sendMessage(inputText)
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send))
                    }
                }
            },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .padding(padding)
                        .fillMaxSize(),
            ) {
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                        ),
                ) {
                    Text(
                        stringResource(R.string.security_lan_notice),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.lines, key = { it.id }) { line ->
                        ChatLineBubble(
                            line = line,
                            vm = vm,
                            clipboard = clipboard,
                            animateModifier = Modifier,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelRoutingSheet(
    vm: ChatViewModel,
    currentMode: OpenAiRoutingStore.Mode,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.chat_model_sheet_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.chat_model_sheet_body),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                color = MaterialTheme.colorScheme.secondary,
            )
            OpenAiRoutingStore.Mode.entries.forEach { mode ->
                val pickMode = {
                    vm.applyOpenAiRouting(mode)
                    onDismiss()
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = mode == currentMode,
                        onClick = pickMode,
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable(onClick = pickMode)
                            .padding(start = 8.dp),
                    ) {
                        Text(mode.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            when (mode) {
                                OpenAiRoutingStore.Mode.DEFAULT -> stringResource(R.string.chat_model_route_default_desc)
                                OpenAiRoutingStore.Mode.LOCAL_FIRST -> stringResource(R.string.chat_model_route_local_first_desc)
                                OpenAiRoutingStore.Mode.VAULT_FIRST -> stringResource(R.string.chat_model_route_vault_first_desc)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatLineBubble(
    line: com.localagent.bridge.ChatLine,
    vm: ChatViewModel,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    animateModifier: Modifier = Modifier,
) {
    val metaModifier =
        animateModifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    clipboard.setText(AnnotatedString(line.text))
                },
            )
    when (line.role) {
        Role.User ->
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    animateModifier
                        .padding(bottom = 4.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                clipboard.setText(AnnotatedString(line.text))
                            },
                        ),
            )
        Role.Assistant ->
            Column(
                modifier =
                    animateModifier
                        .animateContentSize()
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                clipboard.setText(AnnotatedString(line.text))
                            },
                        ),
            ) {
                AssistantMarkdown(text = line.text)
                if (line.id.startsWith("a-streaming")) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.chat_thinking),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
        Role.Tool ->
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                modifier =
                    animateModifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                clipboard.setText(AnnotatedString(line.text))
                            },
                        ),
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Chat,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        Role.Meta ->
            Column(modifier = metaModifier) {
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                val pid = line.approvalPromptId
                if (!pid.isNullOrBlank()) {
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { vm.approveAction(pid) }) {
                            Text(stringResource(R.string.chat_approve))
                        }
                        OutlinedButton(onClick = { vm.denyAction(pid) }) {
                            Text(stringResource(R.string.chat_deny))
                        }
                    }
                }
            }
    }
}
