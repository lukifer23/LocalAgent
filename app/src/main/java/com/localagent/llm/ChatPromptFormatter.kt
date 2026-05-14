package com.localagent.llm

/** Chat-template shaping for instruct-style GGUF models (Qwen-compatible markers). */
object ChatPromptFormatter {
    private const val IM_END = "\u003c\u007c\u0069\u006d\u005f\u0065\u006e\u0064\u007c\u003e"

    fun format(messages: List<ChatCompletionMessage>): String {
        val sb = StringBuilder()
        for (m in messages) {
            sb.append("<|im_start|>").append(m.role).append('\n')
            sb.append(m.content ?: "").append(IM_END).append('\n')
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }
}
