package com.localagent.auth

import android.content.Context

/**
 * Controls how Hermes-facing OPENAI_* variables are merged into the sandbox `.env` and pushed `.env`.
 */
class OpenAiRoutingStore(private val context: Context) {

    enum class Mode {
        /** Bridge defaults first; vault/provider keys override (previous behavior). */
        DEFAULT,

        /** Always route Hermes OpenAI-compatible traffic to this device's llama HTTP + Bearer-as-key. */
        LOCAL_FIRST,

        /** Omit bridge OPENAI_* seeds so vault/custom URLs and keys win. */
        VAULT_FIRST,
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun mode(): Mode =
        prefs.getString(KEY_MODE, null)?.let { stored ->
            runCatching { Mode.valueOf(stored) }.getOrNull()
        } ?: Mode.DEFAULT

    fun setMode(mode: Mode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "localagent.openai_routing"
        private const val KEY_MODE = "mode"
    }
}
