package com.localagent.auth

import android.content.Context
import android.system.Os
import com.localagent.runtime.HermesBridgeEnv
import com.localagent.runtime.HermesPaths
import java.io.File

class HermesEnvWriter(private val context: Context) {

    fun mergedDotEnvContent(secrets: Map<String, String>): String {
        val bridge = HermesBridgeEnv.build(context)
        val routing = OpenAiRoutingStore(context).mode()
        val vault = CredentialVault(context)
        val exportable = secrets.filterKeys { it !in CredentialVault.INTERNAL_KEYS }
        val merged = LinkedHashMap<String, String>()
        when (routing) {
            OpenAiRoutingStore.Mode.DEFAULT -> {
                merged.putAll(bridge)
                merged.putAll(exportable)
            }
            OpenAiRoutingStore.Mode.LOCAL_FIRST -> {
                merged.putAll(bridge)
                merged.putAll(exportable)
                merged["OPENAI_BASE_URL"] = bridge.getValue("OPENAI_BASE_URL")
                merged["OPENAI_API_BASE"] = bridge.getValue("OPENAI_API_BASE")
                val bearer = vault.localLlmHttpBearer()
                merged[CredentialVault.OPENAI_API_KEY] = bearer
            }
            OpenAiRoutingStore.Mode.VAULT_FIRST -> {
                bridge.forEach { (k, v) ->
                    if (k.startsWith("LOCALAGENT_") || k == CredentialVault.LOCAL_LLM_HTTP_BEARER) {
                        merged[k] = v
                    }
                }
                merged.putAll(exportable)
            }
        }
        return merged.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" } + "\n"
    }

    fun syncFromVault(secrets: Map<String, String>) {
        val dir = HermesPaths.hermesRoot(context)
        dir.mkdirs()
        val file = File(dir, ".env")
        file.writeText(mergedDotEnvContent(secrets))
        runCatching { Os.chmod(file.absolutePath, 384) } // 0600 rw-------
    }

    fun append(lines: Map<String, String>) {
        val secrets = CredentialVault(context).snapshot().toMutableMap()
        secrets.putAll(lines)
        syncFromVault(secrets)
    }
}
