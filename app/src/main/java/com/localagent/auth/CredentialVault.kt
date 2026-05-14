package com.localagent.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

import java.security.SecureRandom

class CredentialVault(context: Context) {

    private val prefs =
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun get(key: String): String? = prefs.getString(key, null)

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun snapshot(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (entry in prefs.all) {
            val v = entry.value as? String ?: continue
            if (v.isNotBlank()) {
                out[entry.key] = v
            }
        }
        return out
    }

    /** 256-bit secret; merged into `.env` as LOCALAGENT_BRIDGE_TOKEN for Hermes clients. */
    fun bridgeAuthToken(): String =
        synchronized(lock) {
            var t = get(LOCALAGENT_BRIDGE_TOKEN)
            if (t.isNullOrBlank()) {
                t = newRandomToken()
                put(LOCALAGENT_BRIDGE_TOKEN, t)
            }
            t
        }

    fun regenerateBridgeAuthToken(): String =
        synchronized(lock) {
            val t = newRandomToken()
            put(LOCALAGENT_BRIDGE_TOKEN, t)
            t
        }

    /** Bearer required by local OpenAI-compatible HTTP server (`Authorization: Bearer …`). */
    fun localLlmHttpBearer(): String =
        synchronized(lock) {
            var t = get(LOCAL_LLM_HTTP_BEARER)
            if (t.isNullOrBlank()) {
                t = newRandomToken()
                put(LOCAL_LLM_HTTP_BEARER, t)
            }
            t
        }

    private fun newRandomToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { b -> "%02x".format(b) }
    }

    private val lock = Any()

    companion object {
        const val PREFS_NAME = "localagent.secrets"
        const val LOCALAGENT_BRIDGE_TOKEN = "LOCALAGENT_BRIDGE_TOKEN"
        const val LOCAL_LLM_HTTP_BEARER = "LOCAL_LLM_HTTP_BEARER"
        const val OPENAI_API_KEY = "OPENAI_API_KEY"
        const val ANTHROPIC_API_KEY = "ANTHROPIC_API_KEY"
        const val OPENROUTER_API_KEY = "OPENROUTER_API_KEY"
        const val GOOGLE_API_KEY = "GOOGLE_API_KEY"
        const val GEMINI_API_KEY = "GEMINI_API_KEY"
        const val CUSTOM_OPENAI_BASE_URL = "CUSTOM_OPENAI_BASE_URL"
        const val CUSTOM_OPENAI_API_KEY = "CUSTOM_OPENAI_API_KEY"
        const val OAUTH_REFRESH_JSON = "OAUTH_REFRESH_JSON"
        const val OAUTH_PENDING_CLIENT_SECRET = "OAUTH_PENDING_CLIENT_SECRET"
        const val OAUTH_LAST_FLOW = "OAUTH_LAST_FLOW"

        /** Keys stored only for app use — never written to Hermes `.env`. */
        val INTERNAL_KEYS: Set<String> =
            setOf(OAUTH_REFRESH_JSON, OAUTH_PENDING_CLIENT_SECRET, OAUTH_LAST_FLOW)
    }
}
