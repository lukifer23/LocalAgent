package com.localagent.runtime

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class HermesManifest(
    val revision: String,
    val repository: String,
    val note: String = "",
    @SerialName("install_script_url")
    val installScriptUrl: String = DEFAULT_INSTALL_SCRIPT_URL,
    /** Lowercase SHA-256 of install script bytes; empty disables verification (not recommended). */
    @SerialName("install_script_sha256")
    val installScriptSha256Hex: String = "",
) {
    companion object {
        const val DEFAULT_INSTALL_SCRIPT_URL: String =
            "https://raw.githubusercontent.com/NousResearch/hermes-agent/main/scripts/install.sh"
    }
}

object HermesManifestReader {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): HermesManifest {
        val raw = context.assets.open("hermes-manifest.json").bufferedReader().use { it.readText() }
        return json.decodeFromString(HermesManifest.serializer(), raw)
    }
}
