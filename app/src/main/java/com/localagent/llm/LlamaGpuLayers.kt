package com.localagent.llm

import android.os.Build

object LlamaGpuLayers {
    /** Conservative offload trial when experimental GPU preference is enabled; load may still fall back on unsupported GPUs. */
    fun suggestedOffloadLayers(): Int =
        when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> 48
            "x86_64" -> 24
            else -> 0
        }
}
