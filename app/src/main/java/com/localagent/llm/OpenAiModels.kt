package com.localagent.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatCompletionMessage> = emptyList(),
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    val stream: Boolean? = null,
)

@Serializable
data class ChatCompletionMessage(
    val role: String,
    val content: String? = null,
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    val model: String,
    val choices: List<ChatChoice>,
)

@Serializable
data class ChatChoice(
    val index: Int,
    val message: ChatCompletionMessage,
    @SerialName("finish_reason") val finishReason: String? = "stop",
)

@Serializable
data class ModelsListResponse(
    val `object`: String = "list",
    val data: List<OpenAiModelDescriptor>,
)

@Serializable
data class OpenAiModelDescriptor(
    val id: String,
    val `object`: String = "model",
)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val model: String,
    val choices: List<ChunkChoice>,
)

@Serializable
data class ChunkChoice(
    val index: Int,
    val delta: ChunkDelta,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
)

@Serializable
data class OpenAiErrorEnvelope(
    val error: OpenAiErrorDetail,
)

@Serializable
data class OpenAiErrorDetail(
    val message: String,
    val type: String = "invalid_request_error",
    val code: String? = null,
)
