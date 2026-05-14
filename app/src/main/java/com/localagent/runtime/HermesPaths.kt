package com.localagent.runtime

import android.content.Context
import java.io.File

object HermesPaths {
    const val BRIDGE_PORT: Int = 17852
    const val LLM_HTTP_PORT: Int = 17853

    fun syntheticHome(context: Context): File = File(context.filesDir, "hermes/home")

    fun hermesRoot(context: Context): File = File(syntheticHome(context), ".hermes")

    fun bridgePort(): Int = BRIDGE_PORT

    fun runtimeStaging(context: Context): File {
        val dir = File(context.filesDir, "runtime")
        dir.mkdirs()
        return dir
    }
}
