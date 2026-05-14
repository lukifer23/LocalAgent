package com.localagent.runtime

import android.content.Context
import com.localagent.auth.CredentialVault

object HermesBridgeEnv {
    fun bridgeHost(context: Context): String =
        DeviceIpv4.primarySiteLocalIpv4(context)?.hostAddress ?: "127.0.0.1"

    fun build(context: Context): Map<String, String> {
        val vault = CredentialVault(context)
        val host = bridgeHost(context)
        val bp = HermesPaths.bridgePort()
        val lp = HermesPaths.LLM_HTTP_PORT
        val openAiBase = "http://$host:$lp/v1"
        val token = vault.bridgeAuthToken()
        val llmBearer = vault.localLlmHttpBearer()
        return mapOf(
            "LOCALAGENT_BRIDGE_HOST" to host,
            "LOCALAGENT_BRIDGE_PORT" to bp.toString(),
            "LOCALAGENT_BRIDGE_TOKEN" to token,
            "LOCALAGENT_LLM_HTTP_PORT" to lp.toString(),
            CredentialVault.LOCAL_LLM_HTTP_BEARER to llmBearer,
            "OPENAI_BASE_URL" to openAiBase,
            "OPENAI_API_BASE" to openAiBase,
        )
    }

    fun bridgeSnippet(context: Context): String =
        build(context).entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }

    fun reachabilityNote(hasIpv4: Boolean): String =
        if (hasIpv4) {
            ""
        } else {
            "No routable IPv4 on the active network. Connect to Wi‑Fi or Ethernet, or configure Termux manually."
        }
}
