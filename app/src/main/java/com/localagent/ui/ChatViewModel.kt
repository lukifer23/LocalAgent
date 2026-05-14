package com.localagent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.localagent.auth.CredentialVault
import com.localagent.auth.HermesEnvWriter
import com.localagent.auth.OpenAiRoutingStore
import com.localagent.bridge.ChatLine
import com.localagent.bridge.ChatUiState
import com.localagent.bridge.Role
import com.localagent.data.ChatMessageEntity
import com.localagent.data.ChatRepository
import com.localagent.data.ChatSessionEntity
import com.localagent.llm.LocalLlmService
import com.localagent.runtime.SandboxSkills
import com.localagent.termux.TermuxRunCommand
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(
    private val appContext: Context,
    private val repository: ChatRepository,
    private val localLlm: LocalLlmService,
    private val credentialVault: CredentialVault,
    private val envWriter: HermesEnvWriter,
) : ViewModel() {

    private val hintsInternal = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val hints = hintsInternal.asSharedFlow()

    private val routingStore = OpenAiRoutingStore(appContext)

    private val _modelSheetVisible = MutableStateFlow(false)
    val modelSheetVisible: StateFlow<Boolean> = _modelSheetVisible.asStateFlow()

    private val _routingMode = MutableStateFlow(routingStore.mode())
    val routingMode: StateFlow<OpenAiRoutingStore.Mode> = _routingMode.asStateFlow()

    val state: StateFlow<ChatUiState> =
        combine(repository.activeSessionId, repository.activeMessages) { id, messages ->
            ChatUiState(
                sessionId = id,
                lines = messages.map { it.toChatLine() },
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ChatUiState(null, emptyList()),
        )

    val sessions: StateFlow<List<ChatSessionEntity>> =
        repository.allSessions.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    fun selectSession(id: String?) {
        repository.selectSession(id)
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            repository.deleteSession(id)
        }
    }

    fun approveAction(promptId: String) {
        startTermux(TermuxRunCommand.backgroundApprove(appContext, promptId))
    }

    fun denyAction(promptId: String) {
        startTermux(TermuxRunCommand.backgroundDeny(appContext, promptId))
    }

    fun openModelSheet() {
        _routingMode.value = routingStore.mode()
        _modelSheetVisible.value = true
    }

    fun dismissModelSheet() {
        _modelSheetVisible.value = false
    }

    fun applyOpenAiRouting(mode: OpenAiRoutingStore.Mode) {
        viewModelScope.launch {
            val sid = state.value.sessionId
            routingStore.setMode(mode)
            _routingMode.value = mode
            envWriter.syncFromVault(credentialVault.snapshot())
            appendOrHint(
                sid,
                appContext.getString(com.localagent.R.string.chat_model_routing_applied, mode.name),
            )
        }
    }

    fun sendMessage(text: String) {
        viewModelScope.launch {
            val trimmed = text.trim()
            val sid = state.value.sessionId
            when {
                trimmed.equals("/help", ignoreCase = true) ->
                    appendOrHint(
                        sid,
                        "/help — Hermes CLI handles chat; LocalAgent slash: /help, /model, /skills. Send messages otherwise routes to Termux `hermes chat`.",
                    )

                trimmed.startsWith("/model", ignoreCase = true) -> {
                    val rest = trimmed.removePrefix("/model").trim()
                    when {
                        rest.isEmpty() -> openModelSheet()
                        rest.equals("status", ignoreCase = true) -> {
                            val label = localLlm.loadedLabel().ifBlank { "(none loaded)" }
                            val endpoint = localLlm.endpointBase(appContext)
                            val bearerNote =
                                appContext.getString(
                                    com.localagent.R.string.chat_slash_model_hint,
                                    label,
                                    endpoint,
                                    credentialVault.localLlmHttpBearer().take(12),
                                )
                            appendOrHint(sid, bearerNote)
                            appendOrHint(
                                sid,
                                appContext.getString(
                                    com.localagent.R.string.chat_model_routing_status,
                                    routingStore.mode().name,
                                ),
                            )
                        }
                        else ->
                            appendOrHint(sid, appContext.getString(com.localagent.R.string.chat_model_unknown_subcommand))
                    }
                }

                trimmed.startsWith("/skills", ignoreCase = true) -> {
                    val rest = trimmed.removePrefix("/skills").trim()
                    when {
                        rest.isEmpty() -> {
                            val dir = SandboxSkills.skillsDir(appContext)
                            val listing =
                                dir.takeIf { it.isDirectory }
                                    ?.listFiles()
                                    ?.map { it.name }
                                    ?.sorted()
                                    ?.joinToString(", ")
                                    .orEmpty()
                                    .ifBlank { "(empty sandbox skills/)" }
                            appendOrHint(
                                sid,
                                appContext.getString(com.localagent.R.string.chat_slash_skills_hint, listing),
                            )
                            appendOrHint(sid, appContext.getString(com.localagent.R.string.chat_skills_commands_hint))
                        }
                        rest.equals("push", ignoreCase = true) ->
                            SandboxSkills.buildTermuxPushScript(appContext).fold(
                                onSuccess = { script ->
                                    startTermux(
                                        TermuxRunCommand.pushSandboxSkillsStdin(appContext, script),
                                    )
                                    appendOrHint(sid, appContext.getString(com.localagent.R.string.chat_skills_push_started))
                                },
                                onFailure = { e ->
                                    appendOrHint(
                                        sid,
                                        e.message ?: appContext.getString(com.localagent.R.string.chat_skills_push_failed),
                                    )
                                },
                            )
                        rest.startsWith("disable ", ignoreCase = true) -> {
                            val name = rest.removePrefix("disable ").trim()
                            if (name.isEmpty()) {
                                appendOrHint(sid, appContext.getString(com.localagent.R.string.chat_skills_usage))
                            } else {
                                SandboxSkills.toggleSkillDisabled(appContext, name, disable = true).fold(
                                    onSuccess = {
                                        appendOrHint(sid, appContext.getString(com.localagent.R.string.chat_skills_disabled, name))
                                    },
                                    onFailure = { e ->
                                        appendOrHint(
                                            sid,
                                            e.message ?: appContext.getString(com.localagent.R.string.chat_skills_toggle_failed),
                                        )
                                    },
                                )
                            }
                        }
                        rest.startsWith("enable ", ignoreCase = true) -> {
                            val name = rest.removePrefix("enable ").trim()
                            if (name.isEmpty()) {
                                appendOrHint(sid, appContext.getString(com.localagent.R.string.chat_skills_usage))
                            } else {
                                SandboxSkills.toggleSkillDisabled(appContext, name, disable = false).fold(
                                    onSuccess = {
                                        appendOrHint(sid, appContext.getString(com.localagent.R.string.chat_skills_enabled, name))
                                    },
                                    onFailure = { e ->
                                        appendOrHint(
                                            sid,
                                            e.message ?: appContext.getString(com.localagent.R.string.chat_skills_toggle_failed),
                                        )
                                    },
                                )
                            }
                        }
                        else ->
                            appendOrHint(sid, appContext.getString(com.localagent.R.string.chat_skills_usage))
                    }
                }

                else -> {
                    val intent = TermuxRunCommand.backgroundChat(appContext, text)
                    startTermux(intent)
                }
            }
        }
    }

    private fun startTermux(intent: android.content.Intent) {
        try {
            TermuxRunCommand.start(appContext, intent)
        } catch (_: SecurityException) {
            viewModelScope.launch {
                hintsInternal.emit(appContext.getString(com.localagent.R.string.chat_termux_perm_missing))
            }
        }
    }

    private suspend fun appendOrHint(sessionId: String?, body: String) {
        if (sessionId != null) {
            repository.appendLocalMeta(sessionId, body)
        } else {
            hintsInternal.emit(body)
        }
    }
}

private fun ChatMessageEntity.toChatLine(): ChatLine {
    val role = runCatching { Role.valueOf(this.role) }.getOrDefault(Role.Meta)
    return ChatLine(
        id = id,
        role = role,
        text = body,
        approvalPromptId = approvalPromptId,
    )
}

class ChatViewModelFactory(
    private val appContext: Context,
    private val repository: ChatRepository,
    private val localLlm: LocalLlmService,
    private val credentialVault: CredentialVault,
    private val envWriter: HermesEnvWriter,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass != ChatViewModel::class.java) {
            error("unknown VM")
        }
        @Suppress("UNCHECKED_CAST")
        return ChatViewModel(appContext, repository, localLlm, credentialVault, envWriter) as T
    }
}
