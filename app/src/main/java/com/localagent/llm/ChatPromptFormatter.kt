package com.localagent.llm

/** Chat-template shaping for instruct-style GGUF models (Qwen-compatible markers). */
object ChatPromptFormatter {
    private const val IM_END = "\u003c\u007c\u0069\u006d\u005f\u0065\u006e\u0064\u007c\u003e"

    fun format(messages: List<ChatCompletionMessage>): String {
        val sb = StringBuilder()
        for (m in messages) {
            val content = when (val c = m.content) {
                is MessageContent.Text -> c.text
                is MessageContent.Multimodal -> c.parts.filter { it.type == "text" }.joinToString("\n") { it.text ?: "" }
                null -> ""
            }
            sb.append("<|im_start|>").append(m.role).append('\n')
            sb.append(content).append(IM_END).append('\n')
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }
}
