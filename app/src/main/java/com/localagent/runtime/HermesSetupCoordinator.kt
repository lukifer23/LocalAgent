package com.localagent.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import com.localagent.auth.CredentialVault
import com.localagent.bridge.BridgeSocketServer

class HermesSetupCoordinator(
    private val context: Context,
    private val manifest: HermesManifest,
) {
    private val cacheDir: File =
        File(HermesPaths.runtimeStaging(context), "hermes-bootstrap").apply { mkdirs() }

    private val cachedScript: File = File(cacheDir, "install.sh")

    private val http =
        OkHttpClient.Builder()
            .followRedirects(true)
            .callTimeout(5, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()

    fun cachedInstallScriptText(): String? =
        if (cachedScript.isFile && cachedScript.length() > 0L) {
            cachedScript.readText(Charsets.UTF_8)
        } else {
            null
        }

    fun hasCachedInstallScript(): Boolean = cachedScript.isFile && cachedScript.length() > 0L

    suspend fun downloadInstallScript(): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(manifest.installScriptUrl).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(IllegalStateException("download failed HTTP ${resp.code}"))
                    }
                    val body = resp.body?.bytes() ?: return@withContext Result.failure(IllegalStateException("empty body"))
                    val expected = manifest.installScriptSha256Hex.trim().lowercase()
                    if (expected.isNotEmpty()) {
                        val hex = sha256Hex(body).lowercase()
                        if (!hex.equals(expected, ignoreCase = true)) {
                            return@withContext Result.failure(
                                IllegalStateException(
                                    "install script SHA-256 mismatch — upstream changed or manifest pin is stale",
                                ),
                            )
                        }
                    }
                    cachedScript.parentFile?.mkdirs()
                    cachedScript.writeBytes(body)
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun probeBridgeTcp(): Boolean =
        withContext(Dispatchers.IO) {
            val host = HermesBridgeEnv.bridgeHost(context)
            val token = CredentialVault(context).bridgeAuthToken()
            BridgeSocketServer.probeAuthenticated(host, HermesPaths.bridgePort(), token)
        }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}
