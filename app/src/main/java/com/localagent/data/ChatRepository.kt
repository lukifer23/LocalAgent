package com.localagent.data

import com.localagent.bridge.BridgeEvent
import com.localagent.bridge.Role
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepository(
    private val database: LocalAgentDatabase,
    private val ioScope: CoroutineScope,
) {
    private val sessionDao = database.sessions()
    private val messageDao = database.messages()

    companion object {
        private const val STREAM_DEBOUNCE_MS = 48L
    }

    private val streamBuffers = ConcurrentHashMap<String, String>()
    private val streamFlushJobs = ConcurrentHashMap<String, Job>()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: Flow<String?> = _activeSessionId

    val allSessions: Flow<List<ChatSessionEntity>> = sessionDao.observe()

    val activeMessages: Flow<List<ChatMessageEntity>> =
        _activeSessionId.flatMapLatest { id ->
            if (id == null) {
                flowOf(emptyList())
            } else {
                messageDao.observe(id)
            }
        }

    fun selectSession(id: String?) {
        _activeSessionId.value = id
    }

    suspend fun deleteSession(id: String) {
        if (_activeSessionId.value == id) {
            _activeSessionId.value = null
        }
        messageDao.deleteBySessionId(id)
        sessionDao.deleteById(id)
    }

    suspend fun appendLocalMeta(sessionId: String, body: String, ts: Long = System.currentTimeMillis()) {
        messageDao.upsert(
            ChatMessageEntity(
                id = "local-meta-$ts-${(0..9999).random()}",
                sessionId = sessionId,
                role = Role.Meta.name,
                body = body,
                createdAtEpochMs = ts,
                approvalPromptId = null,
            ),
        )
    }

    suspend fun handleEvent(event: BridgeEvent) {
        when (event) {
            is BridgeEvent.Session -> {
                _activeSessionId.value = event.id
                sessionDao.upsert(
                    ChatSessionEntity(
                        id = event.id,
                        title = event.title ?: "New Chat",
                        updatedAtEpochMs = event.ts,
                    ),
                )
            }

            is BridgeEvent.UserMessage -> {
                val sessionId = event.sessionId ?: _activeSessionId.value ?: return
                messageDao.upsert(
                    ChatMessageEntity(
                        id = "u-${event.ts}",
                        sessionId = sessionId,
                        role = Role.User.name,
                        body = event.text,
                        createdAtEpochMs = event.ts,
                        approvalPromptId = null,
                    ),
                )
            }

            is BridgeEvent.AssistantDelta -> {
                val sessionId = event.sessionId ?: _activeSessionId.value ?: return
                streamBuffers.merge(sessionId, event.text) { prev, frag -> prev + frag }
                streamFlushJobs[sessionId]?.cancel()
                streamFlushJobs[sessionId] =
                    ioScope.launch(Dispatchers.IO) {
                        delay(STREAM_DEBOUNCE_MS)
                        flushAssistantStreaming(sessionId, event.ts)
                    }
            }

            is BridgeEvent.AssistantDone -> {
                val sessionId = event.sessionId ?: _activeSessionId.value ?: return
                streamFlushJobs.remove(sessionId)?.cancel()
                withContext(Dispatchers.IO) {
                    val currentId = "a-streaming-$sessionId"
                    val prior = messageDao.getById(currentId)
                    val finalBody =
                        streamBuffers.remove(sessionId)
                            ?: prior?.body
                            ?: return@withContext
                    messageDao.deleteById(currentId)
                    messageDao.upsert(
                        ChatMessageEntity(
                            id = "a-${event.ts}",
                            sessionId = sessionId,
                            role = Role.Assistant.name,
                            body = finalBody,
                            createdAtEpochMs = prior?.createdAtEpochMs ?: event.ts,
                            approvalPromptId = null,
                        ),
                    )
                }
            }

            is BridgeEvent.ToolCall -> {
                val sessionId = event.sessionId ?: _activeSessionId.value ?: return
                messageDao.upsert(
                    ChatMessageEntity(
                        id = "t-${event.ts}",
                        sessionId = sessionId,
                        role = Role.Tool.name,
                        body = "${event.name}: ${event.argsPreview} (${event.status})",
                        createdAtEpochMs = event.ts,
                        approvalPromptId = null,
                    ),
                )
            }

            is BridgeEvent.Approval -> {
                val sessionId = event.sessionId ?: _activeSessionId.value ?: return
                messageDao.upsert(
                    ChatMessageEntity(
                        id = "p-${event.ts}",
                        sessionId = sessionId,
                        role = Role.Meta.name,
                        body = "Approve? `${event.command}`",
                        createdAtEpochMs = event.ts,
                        approvalPromptId = event.promptId,
                    ),
                )
            }

            is BridgeEvent.Usage -> {
                val sessionId = event.sessionId ?: _activeSessionId.value ?: return
                val text =
                    buildString {
                        append("Usage:")
                        event.model?.let { append(" ").append(it) }
                        event.promptTokens?.let { append(" P:").append(it) }
                        event.completionTokens?.let { append(" C:").append(it) }
                    }
                messageDao.upsert(
                    ChatMessageEntity(
                        id = "meta-${event.ts}",
                        sessionId = sessionId,
                        role = Role.Meta.name,
                        body = text,
                        createdAtEpochMs = event.ts,
                        approvalPromptId = null,
                    ),
                )
            }
        }
    }

    private suspend fun flushAssistantStreaming(sessionId: String, firstTs: Long) {
        val snapshot = streamBuffers[sessionId] ?: return
        val currentId = "a-streaming-$sessionId"
        val existing = messageDao.getById(currentId)
        messageDao.upsert(
            ChatMessageEntity(
                id = currentId,
                sessionId = sessionId,
                role = Role.Assistant.name,
                body = snapshot,
                createdAtEpochMs = existing?.createdAtEpochMs ?: firstTs,
                approvalPromptId = null,
            ),
        )
    }
}
