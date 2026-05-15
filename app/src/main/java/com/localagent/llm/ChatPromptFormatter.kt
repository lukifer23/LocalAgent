package com.localagent.llm

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Chat-template shaping for instruct-style GGUF models (Qwen-compatible markers). */
object ChatPromptFormatter {
    private const val IM_END = "\u003c\u007c\u0069\u006d\u005f\u0065\u006e\u0064\u007c\u003e"

    fun format(messages: List<ChatCompletionMessage>, tools: List<ToolDefinition>? = null): String {
        val sb = StringBuilder()
        
        // Handle tools in system prompt
        val hasTools = tools != null && tools.isNotEmpty()
        
        for (m in messages) {
            sb.append("<|im_start|>").append(m.role).append('\n')
            
            if (m.role == "system" && hasTools) {
                val content = when (val c = m.content) {
                    is MessageContent.Text -> c.text
                    is MessageContent.Multimodal -> c.parts.filter { it.type == "text" }.joinToString("\n") { it.text ?: "" }
                    null -> ""
                }
                sb.append(content).append("\n\n# Tools\n\nYou may call one or more functions to help with the user request.\nYou are provided with function signatures within <tools></tools> XML tags:\n<tools>\n")
                tools?.forEach { tool ->
                    sb.append(Json.encodeToString(ToolDefinition.serializer(), tool)).append("\n")
                }
                sb.append("</tools>\n\nFor each function call, return a json object with function name and arguments within <tool_call></tool_call> XML tags:\n<tool_call>\n{\"name\": \"function_name\", \"arguments\": {\"arg_1\": \"value_1\"}}\n</tool_call>\n")
            } else if (m.role == "tool") {
                val content = when (val c = m.content) {
                    is MessageContent.Text -> c.text
                    is MessageContent.Multimodal -> c.parts.filter { it.type == "text" }.joinToString("\n") { it.text ?: "" }
                    null -> ""
                }
                // Wrap tool response
                sb.append("<tool_response>\n").append(content).append("\n</tool_response>")
            } else {
                val content = when (val c = m.content) {
                    is MessageContent.Text -> c.text
                    is MessageContent.Multimodal -> c.parts.filter { it.type == "text" }.joinToString("\n") { it.text ?: "" }
                    null -> ""
                }
                sb.append(content)
                
                // Append tool calls from assistant if present
                m.toolCalls?.forEach { call ->
                    sb.append("\n<tool_call>\n")
                    sb.append("{\"name\": \"").append(call.function.name).append("\", \"arguments\": ").append(call.function.arguments).append("}")
                    sb.append("\n</tool_call>")
                }
            }
            sb.append(IM_END).append('\n')
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }
}
