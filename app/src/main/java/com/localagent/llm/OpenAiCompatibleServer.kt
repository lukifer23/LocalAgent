package com.localagent.llm

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
class OpenAiCompatibleServer(
    private val port: Int,
    private val bindHost: String,
    private val bearerToken: () -> String,
    private val infer: suspend (ChatCompletionRequest) -> Result<String>,
    private val loadedModelLabel: () -> String,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private var engine: EmbeddedServer<*, *>? = null

    fun start() {
        if (engine != null) {
            return
        }
        engine =
            embeddedServer(CIO, port = port, host = bindHost) {
                routing {
                    get("/v1/models") {
                        if (!authorize(call)) return@get
                        val label = loadedModelLabel()
                        val resp =
                            ModelsListResponse(
                                data =
                                    listOf(
                                        OpenAiModelDescriptor(id = label.ifBlank { "localagent-local" }),
                                    ),
                            )
                        call.respondText(json.encodeToString(ModelsListResponse.serializer(), resp), ContentType.Application.Json)
                    }

                    post("/v1/chat/completions") {
                        if (!authorize(call)) return@post
                        val body =
                            try {
                                call.receiveText()
                            } catch (e: Exception) {
                                respondError(call, HttpStatusCode.BadRequest, "invalid body: ${e.message}")
                                return@post
                            }
                        val req =
                            try {
                                json.decodeFromString(ChatCompletionRequest.serializer(), body)
                            } catch (e: Exception) {
                                respondError(call, HttpStatusCode.BadRequest, "invalid JSON: ${e.message}")
                                return@post
                            }

                        val stream = req.stream == true
                        val result = infer(req)
                        val text =
                            result.getOrElse { err ->
                                respondError(call, HttpStatusCode.ServiceUnavailable, err.message ?: "inference failed")
                                return@post
                            }

                        val id = "localagent-${System.currentTimeMillis()}"
                        val model = req.model.ifBlank { loadedModelLabel().ifBlank { "localagent-local" } }

                        if (!stream) {
                            val resp =
                                ChatCompletionResponse(
                                    id = id,
                                    model = model,
                                    choices =
                                        listOf(
                                            ChatChoice(
                                                index = 0,
                                                message =
                                                    ChatCompletionMessage(
                                                        role = "assistant",
                                                        content = text,
                                                    ),
                                                finishReason = "stop",
                                            ),
                                        ),
                                )
                            call.respondText(json.encodeToString(ChatCompletionResponse.serializer(), resp), ContentType.Application.Json)
                            return@post
                        }

                        call.respondOutputStream(ContentType.Text.EventStream, HttpStatusCode.OK) {
                            fun send(obj: Any) {
                                val payload =
                                    when (obj) {
                                        is ChatCompletionChunk -> json.encodeToString(ChatCompletionChunk.serializer(), obj)
                                        is ChatCompletionResponse -> json.encodeToString(ChatCompletionResponse.serializer(), obj)
                                        else -> obj.toString()
                                    }
                                write("data: $payload\n\n".toByteArray(Charsets.UTF_8))
                                flush()
                            }

                            send(
                                ChatCompletionChunk(
                                    id = id,
                                    model = model,
                                    choices =
                                        listOf(
                                            ChunkChoice(index = 0, delta = ChunkDelta(role = "assistant")),
                                        ),
                                ),
                            )

                            val chunkSize = 48
                            var offset = 0
                            while (offset < text.length) {
                                val end = minOf(offset + chunkSize, text.length)
                                val piece = text.substring(offset, end)
                                offset = end
                                send(
                                    ChatCompletionChunk(
                                        id = id,
                                        model = model,
                                        choices =
                                            listOf(
                                                ChunkChoice(index = 0, delta = ChunkDelta(content = piece)),
                                            ),
                                    ),
                                )
                            }

                            send(
                                ChatCompletionChunk(
                                    id = id,
                                    model = model,
                                    choices =
                                        listOf(
                                            ChunkChoice(index = 0, delta = ChunkDelta(), finishReason = "stop"),
                                        ),
                                ),
                            )
                            write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                            flush()
                        }
                    }
                }
            }.apply {
                start(wait = false)
            }
    }

    private suspend fun authorize(call: ApplicationCall): Boolean {
        val expected = bearerToken()
        val auth = call.request.headers[HttpHeaders.Authorization]
        val ok = auth == "Bearer $expected"
        if (!ok) {
            respondError(call, HttpStatusCode.Unauthorized, "missing or invalid Authorization Bearer token")
        }
        return ok
    }

    private suspend fun respondError(call: ApplicationCall, status: HttpStatusCode, message: String) {
        val envelope =
            OpenAiErrorEnvelope(
                error = OpenAiErrorDetail(message = message),
            )
        call.respondText(
            json.encodeToString(OpenAiErrorEnvelope.serializer(), envelope),
            ContentType.Application.Json,
            status,
        )
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 200, timeoutMillis = 2000)
        engine = null
    }
}
