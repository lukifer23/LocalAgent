package com.localagent.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.localagent.BuildConfig
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse

class OAuthCoordinator(private val vault: CredentialVault) {

    fun fetchAuthorizationIntent(activity: Activity, def: OAuthDefinition, ready: (Intent?) -> Unit) {
        AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(def.issuer)) { config, _ ->
            if (config == null) {
                ready(null)
                return@fetchFromIssuer
            }
            val redirect = Uri.parse(BuildConfig.OAUTH_REDIRECT_URI)
            val req =
                AuthorizationRequest.Builder(config, def.clientId, ResponseTypeValues.CODE, redirect)
                    .setScope(def.scopes)
                    .build()
            val service = AuthorizationService(activity.applicationContext)
            ready(service.getAuthorizationRequestIntent(req))
        }
    }

    fun fetchGoogleGeminiIntent(activity: Activity, ready: (Intent?) -> Unit) {
        val clientId = BuildConfig.GOOGLE_OAUTH_CLIENT_ID.trim()
        if (clientId.isEmpty()) {
            ready(null)
            return
        }
        vault.put(CredentialVault.OAUTH_LAST_FLOW, FLOW_GOOGLE_GEMINI)
        stashPendingSecret(null)
        val def =
            OAuthDefinition(
                issuer = GOOGLE_ISSUER,
                clientId = clientId,
                scopes = GOOGLE_GEMINI_SCOPES,
            )
        fetchAuthorizationIntent(activity, def, ready)
    }

    fun completeAuthorization(activity: Activity, data: Intent, clientSecret: String?, done: (Result<Unit>) -> Unit) {
        val response =
            AuthorizationResponse.fromIntent(data)
                ?: run {
                    done(Result.failure(IllegalStateException("missing authorization response")))
                    return
                }
        val service = AuthorizationService(activity.applicationContext)
        val secret =
            clientSecret?.takeIf { it.isNotBlank() }
                ?: vault.get(CredentialVault.OAUTH_PENDING_CLIENT_SECRET)?.takeIf { it.isNotBlank() }
        stashPendingSecret(null)
        val extras: Map<String, String> =
            if (secret == null) {
                emptyMap()
            } else {
                mapOf("client_secret" to secret)
            }
        val tokenRequest = response.createTokenExchangeRequest(extras)
        service.performTokenRequest(tokenRequest) { tokenResp, ex ->
            val main = Handler(Looper.getMainLooper())
            if (tokenResp != null) {
                vault.put(CredentialVault.OAUTH_REFRESH_JSON , tokenResp.jsonSerializeString())
                applyPostToken(activity , tokenResp)
                main.post { done(Result.success(Unit)) }
            } else {
                main.post { done(Result.failure(ex ?: IllegalStateException("token exchange failed"))) }
            }
        }
    }

    private fun stashPendingSecret(secret: String?) {
        if (secret.isNullOrBlank()) {
            vault.remove(CredentialVault.OAUTH_PENDING_CLIENT_SECRET)
        } else {
            vault.put(CredentialVault.OAUTH_PENDING_CLIENT_SECRET , secret)
        }
    }

    fun setPendingClientSecretForNextFlow(secret: String?) {
        stashPendingSecret(secret)
    }

    private fun applyPostToken(activity: Activity, tokenResp: TokenResponse) {
        val flow = vault.get(CredentialVault.OAUTH_LAST_FLOW)
        vault.remove(CredentialVault.OAUTH_LAST_FLOW)
        val access = tokenResp.accessToken?.trim().orEmpty()
        if (access.isEmpty()) return
        when (flow) {
            FLOW_GOOGLE_GEMINI -> {
                vault.put("OPENAI_BASE_URL", GOOGLE_GEMINI_OPENAI_BASE)
                vault.put("OPENAI_API_BASE", GOOGLE_GEMINI_OPENAI_BASE)
                vault.put(CredentialVault.OPENAI_API_KEY, access)
                vault.put(CredentialVault.GOOGLE_API_KEY, access)
                vault.put(CredentialVault.CUSTOM_OPENAI_BASE_URL, GOOGLE_GEMINI_OPENAI_BASE)
                vault.put(CredentialVault.CUSTOM_OPENAI_API_KEY, access)
                HermesEnvWriter(activity.applicationContext).syncFromVault(vault.snapshot())
            }
            else -> {
                HermesEnvWriter(activity.applicationContext).syncFromVault(vault.snapshot())
            }
        }
    }

    companion object {
        const val GOOGLE_ISSUER = "https://accounts.google.com"
        const val GOOGLE_GEMINI_OPENAI_BASE = "https://generativelanguage.googleapis.com/v1beta/openai"
        const val GOOGLE_GEMINI_SCOPES = "openid email profile https://www.googleapis.com/auth/generative-language.retriever"
        private const val FLOW_GOOGLE_GEMINI = "google_gemini"
    }
}
