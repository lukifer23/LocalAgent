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
    val content: MessageContent? = null,
)

@Serializable(with = MessageContentSerializer::class)
sealed class MessageContent {
    data class Text(val text: String) : MessageContent()
    data class Multimodal(val parts: List<ContentPart>) : MessageContent()
}

@Serializable
data class ContentPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: ImageUrl? = null,
)

@Serializable
data class ImageUrl(
    val url: String, // base64 data:image/jpeg;base64,...
)

object MessageContentSerializer : kotlinx.serialization.KSerializer<MessageContent> {
    override val descriptor = kotlinx.serialization.descriptors.buildClassSerialDescriptor("MessageContent")
    
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: MessageContent) {
        when (value) {
            is MessageContent.Text -> encoder.encodeString(value.text)
            is MessageContent.Multimodal -> encoder.encodeSerializableValue(kotlinx.serialization.builtins.ListSerializer(ContentPart.serializer()), value.parts)
        }
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): MessageContent {
        // Simple heuristic for now: if it's a string, it's text. If it's an array, it's multimodal.
        val input = decoder as? kotlinx.serialization.json.JsonDecoder ?: error("Only JSON supported")
        val element = input.decodeJsonElement()
        return if (element is kotlinx.serialization.json.JsonPrimitive && element.isString) {
            MessageContent.Text(element.content)
        } else {
            MessageContent.Multimodal(input.json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(ContentPart.serializer()), element))
        }
    }
}

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
