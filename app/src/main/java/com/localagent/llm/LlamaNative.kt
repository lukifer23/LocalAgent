package com.localagent.llm

object LlamaNative {
    init {
        System.loadLibrary("localagent_llama")
    }

    external fun nativeLoad(path: String, nGpuLayers: Int, nCtx: Int, nThreads: Int): Long

    external fun nativeLoadVision(handle: Long, mmprojPath: String): Boolean

    external fun nativeUnload(handle: Long)

    external fun nativeComplete(
        handle: Long,
        prompt: String,
        imagePixels: IntArray?,
        imageWidth: Int,
        imageHeight: Int,
        maxNewTokens: Int,
        addBos: Boolean,
        temperature: Float,
        topP: Float,
    ): String

    external fun nativeStream(
        handle: Long,
        prompt: String,
        imagePixels: IntArray?,
        imageWidth: Int,
        imageHeight: Int,
        maxNewTokens: Int,
        addBos: Boolean,
        temperature: Float,
        topP: Float,
        onToken: (String) -> Unit,
    ): String
}
