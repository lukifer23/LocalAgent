package com.localagent.auth

import com.localagent.R

data class ProviderField(
    val prefKey: String,
    val labelRes: Int,
    val helpRes: Int,
    val isSecret: Boolean,
)

object ProviderCatalog {

    val fields: List<ProviderField> =
        listOf(
            ProviderField(CredentialVault.OPENAI_API_KEY, R.string.provider_key_openai, R.string.provider_help_openai, true),
            ProviderField(CredentialVault.ANTHROPIC_API_KEY, R.string.provider_key_anthropic, R.string.provider_help_anthropic, true),
            ProviderField(CredentialVault.OPENROUTER_API_KEY, R.string.provider_key_openrouter, R.string.provider_help_openrouter, true),
            ProviderField(CredentialVault.GOOGLE_API_KEY, R.string.provider_key_gemini, R.string.provider_help_gemini, true),
            ProviderField(CredentialVault.CUSTOM_OPENAI_BASE_URL, R.string.provider_custom_base, R.string.provider_help_custom_base, false),
            ProviderField(CredentialVault.CUSTOM_OPENAI_API_KEY, R.string.provider_custom_key, R.string.provider_help_custom_key, true),
        )

    @Deprecated("Prefer fields with string resources", ReplaceWith("fields"))
    val apiKeyHints: Map<String, String> =
        mapOf(
            CredentialVault.OPENAI_API_KEY to "OpenAI / ChatGPT API key",
            CredentialVault.ANTHROPIC_API_KEY to "Anthropic API key",
            CredentialVault.OPENROUTER_API_KEY to "OpenRouter API key",
            CredentialVault.GOOGLE_API_KEY to "Google Gemini API key",
            CredentialVault.CUSTOM_OPENAI_BASE_URL to "Custom OpenAI-compatible base URL",
            CredentialVault.CUSTOM_OPENAI_API_KEY to "Custom endpoint API key",
        )

    /** Light-touch validation for pasted keys; warns only, never blocks save. */
    fun formatWarning(key: String, value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        return when (key) {
            CredentialVault.OPENAI_API_KEY ->
                if (!trimmed.startsWith("sk-")) "OpenAI secret keys usually begin with sk-" else null
            CredentialVault.CUSTOM_OPENAI_BASE_URL ->
                if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                    "Base URL should start with http:// or https://"
                } else {
                    null
                }
            CredentialVault.ANTHROPIC_API_KEY ->
                if (trimmed.length < 20) "Anthropic keys are typically longer" else null
            CredentialVault.GOOGLE_API_KEY ->
                if (trimmed.length < 20) "Google API / OAuth tokens are usually longer" else null
            else -> null
        }
    }
}

data class OAuthDefinition(
    val issuer: String,
    val clientId: String,
    val scopes: String,
)
