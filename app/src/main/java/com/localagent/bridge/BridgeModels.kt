package com.localagent.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@Serializable
sealed class BridgeEvent {
    abstract val ts: Long

    @Serializable
    @SerialName("session")
    data class Session(
        override val ts: Long,
        val id: String,
        val title: String? = null,
    ) : BridgeEvent()

    @Serializable
    @SerialName("user")
    data class UserMessage(
        override val ts: Long,
        val text: String,
        val sessionId: String? = null,
    ) : BridgeEvent()

    @Serializable
    @SerialName("assistant_delta")
    data class AssistantDelta(
        override val ts: Long,
        val text: String,
        val sessionId: String? = null,
    ) : BridgeEvent()

    @Serializable
    @SerialName("assistant_done")
    data class AssistantDone(
        override val ts: Long,
        val sessionId: String? = null,
    ) : BridgeEvent()

    @Serializable
    @SerialName("tool")
    data class ToolCall(
        override val ts: Long,
        val name: String,
        val argsPreview: String,
        val status: String,
        val sessionId: String? = null,
    ) : BridgeEvent()

    @Serializable
    @SerialName("approval")
    data class Approval(
        override val ts: Long,
        val command: String,
        val promptId: String,
        val sessionId: String? = null,
    ) : BridgeEvent()

    @Serializable
    @SerialName("usage")
    data class Usage(
        override val ts: Long,
        val promptTokens: Int?,
        val completionTokens: Int?,
        val model: String?,
        val usd: Double?,
        val sessionId: String? = null,
    ) : BridgeEvent()
}

object BridgeJson {
    private val module =
        SerializersModule {
            polymorphic(BridgeEvent::class) {
                subclass(BridgeEvent.Session::class)
                subclass(BridgeEvent.UserMessage::class)
                subclass(BridgeEvent.AssistantDelta::class)
                subclass(BridgeEvent.AssistantDone::class)
                subclass(BridgeEvent.ToolCall::class)
                subclass(BridgeEvent.Approval::class)
                subclass(BridgeEvent.Usage::class)
            }
        }

    val json =
        Json {
            serializersModule = module
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "type"
        }

    fun parse(line: String): BridgeEvent = json.decodeFromString(BridgeEvent.serializer(), line)
}
