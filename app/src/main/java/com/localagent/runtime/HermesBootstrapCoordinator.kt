package com.localagent.runtime

import android.content.Context
import java.io.File

class HermesBootstrapCoordinator(private val context: Context) {

    private var manifestCached: HermesManifest? = null

    fun ensureFilesystemLayout() {
        val home = HermesPaths.syntheticHome(context)
        val hermes = HermesPaths.hermesRoot(context)
        home.mkdirs()
        hermes.mkdirs()
        File(hermes, "skills").mkdirs()
        File(hermes, "memories").mkdirs()
        File(hermes, "logs").mkdirs()
        HermesPaths.runtimeStaging(context)
        manifestCached = HermesManifestReader.load(context)
    }

    fun manifest(): HermesManifest =
        manifestCached ?: HermesManifestReader.load(context).also { manifestCached = it }
}
