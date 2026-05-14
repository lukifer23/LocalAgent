package com.localagent.llm

object LlamaNative {
    init {
        System.loadLibrary("localagent_llama")
    }

    external fun nativeLoad(path: String, nGpuLayers: Int, nCtx: Int, nThreads: Int): Long

    external fun nativeUnload(handle: Long)

    external fun nativeComplete(
        handle: Long,
        prompt: String,
        maxNewTokens: Int,
        addBos: Boolean,
        temperature: Float,
        topP: Float,
    ): String
}
