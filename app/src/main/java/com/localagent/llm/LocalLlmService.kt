package com.localagent.llm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.localagent.auth.HermesEnvWriter
import com.localagent.auth.OpenAiRoutingStore
import com.localagent.runtime.HermesBridgeEnv
import com.localagent.runtime.HermesPaths
import com.localagent.runtime.SandboxSkills
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.roundToInt

data class LlmBenchResult(
    val latencyMs: Long,
    val outputChars: Int,
    val approxTokensPerSec: Double,
)

class LocalLlmService(
    private val dataStore: DataStore<Preferences>,
    private val downloader: ModelDownloadManager,
    private val bearerToken: () -> String,
    private val appContext: Context,
    private val lanListenAllowed: () -> Boolean,
) {
    private val mutex = Mutex()
    private var handle: Long = 0

    @Volatile
    private var loadedModelLabel: String = ""

    private var mmprojLoadedPath: String? = null

    private val server =
        OpenAiCompatibleServer(
            port = HermesPaths.LLM_HTTP_PORT,
            bindHost = "0.0.0.0",
            bearerToken = bearerToken,
            infer = { req, onToken ->
                mutex.withLock {
                    val modelHandle = handle
                    if (modelHandle == 0L) {
                        return@withLock Result.failure(IllegalStateException("model not loaded"))
                    }
                    val prompt = ChatPromptFormatter.format(req.messages, req.tools)
                    val max = req.maxTokens?.coerceIn(8, 2048) ?: 256
                    val temperature = req.temperature?.toFloat()?.coerceIn(0f, 2f) ?: 0.8f
                    val topP = req.topP?.toFloat()?.coerceIn(0.01f, 1f) ?: 0.95f
                    
                    var pixels: IntArray? = null
                    var width = 0
                    var height = 0

                    // Extract image pixels from multimodal request if present
                    req.messages.lastOrNull()?.let { msg ->
                        when (val content = msg.content) {
                            is MessageContent.Multimodal -> {
                                content.parts.find { it.type == "image_url" }?.imageUrl?.url?.let { url ->
                                    runCatching {
                                        val uri = Uri.parse(url)
                                        appContext.contentResolver.openInputStream(uri)?.use { stream ->
                                            val original = BitmapFactory.decodeStream(stream)
                                            if (original != null) {
                                                val maxDim = 672 // Optimal for many vision models
                                                val scaled = if (original.width > maxDim || original.height > maxDim) {
                                                    val ratio = original.width.toFloat() / original.height.toFloat()
                                                    val (w, h) = if (ratio > 1) {
                                                        maxDim to (maxDim / ratio).toInt()
                                                    } else {
                                                        (maxDim * ratio).toInt() to maxDim
                                                    }
                                                    Bitmap.createScaledBitmap(original, w, h, true)
                                                } else {
                                                    original
                                                }
                                                
                                                width = scaled.width
                                                height = scaled.height
                                                pixels = IntArray(width * height)
                                                scaled.getPixels(pixels, 0, width, 0, 0, width, height)
                                                
                                                if (scaled !== original) scaled.recycle()
                                                original.recycle()
                                            }
                                        }
                                    }
                                }
                            }
                            else -> null
                        }
                    }

                    val raw =
                        if (onToken != null) {
                            LlamaNative.nativeStream(
                                modelHandle,
                                prompt,
                                pixels,
                                width,
                                height,
                                max,
                                false,
                                temperature,
                                topP,
                                onToken,
                            )
                        } else {
                            LlamaNative.nativeComplete(
                                modelHandle,
                                prompt,
                                pixels,
                                width,
                                height,
                                max,
                                false,
                                temperature,
                                topP,
                            )
                        }
                    if (raw.startsWith("ERROR:")) {
                        Result.failure(IllegalStateException(raw.removePrefix("ERROR:").trim()))
                    } else {
                        Result.success(raw)
                    }
                }
            },
            loadedModelLabel = { loadedModelLabel },
        )

    companion object {
        val LAST_MODEL_PATH = stringPreferencesKey("last_model_path")
        val N_GPU_LAYERS = intPreferencesKey("n_gpu_layers")
        val N_THREADS = intPreferencesKey("n_threads")
        val N_CTX = intPreferencesKey("n_ctx")
        val EXPERIMENTAL_GPU = booleanPreferencesKey("experimental_gpu_layers")
    }

    fun experimentalGpuFlow(): Flow<Boolean> =
        dataStore.data.map { it[EXPERIMENTAL_GPU] == true }.distinctUntilChanged()

    suspend fun setExperimentalGpuLayers(enabled: Boolean) {
        dataStore.edit { it[EXPERIMENTAL_GPU] = enabled }
    }

    fun nCtxFlow(): Flow<Int> = dataStore.data.map { it[N_CTX] ?: 4096 }.distinctUntilChanged()
    fun nThreadsFlow(): Flow<Int> = dataStore.data.map { it[N_THREADS] ?: optimalThreads() }.distinctUntilChanged()
    fun nGpuLayersFlow(): Flow<Int> = dataStore.data.map { it[N_GPU_LAYERS] ?: 0 }.distinctUntilChanged()

    suspend fun setNCtx(n: Int) { dataStore.edit { it[N_CTX] = n } }
    suspend fun setNThreads(n: Int) { dataStore.edit { it[N_THREADS] = n } }
    suspend fun setNGpuLayers(n: Int) { dataStore.edit { it[N_GPU_LAYERS] = n } }

    suspend fun loadModel(
        path: String,
        nGpuLayers: Int? = null,
        nCtx: Int? = null,
        nThreads: Int? = null,
        mmprojPath: String? = null,
    ): Boolean =
        mutex.withLock {
            unloadLocked()

            val prefs = dataStore.data.first()
            val layers =
                when {
                    nGpuLayers != null -> nGpuLayers
                    prefs[EXPERIMENTAL_GPU] == true -> LlamaGpuLayers.suggestedOffloadLayers()
                    else -> prefs[N_GPU_LAYERS] ?: 0
                }
            val ctx = nCtx ?: prefs[N_CTX] ?: 4096
            val threads = nThreads ?: prefs[N_THREADS] ?: optimalThreads()

            val h = LlamaNative.nativeLoad(path, layers, ctx, threads)
            if (h == 0L) {
                return@withLock false
            }
            handle = h
            loadedModelLabel = File(path).nameWithoutExtension
            
            if (mmprojPath != null) {
                if (LlamaNative.nativeLoadVision(h, mmprojPath)) {
                    mmprojLoadedPath = mmprojPath
                }
            }

            dataStore.edit {
                it[LAST_MODEL_PATH] = path
                it[N_GPU_LAYERS] = layers
                it[N_CTX] = ctx
                it[N_THREADS] = threads
            }
            true
        }

    suspend fun loadVisionProjector(path: String): Boolean =
        mutex.withLock {
            val h = handle
            if (h == 0L) return false
            if (LlamaNative.nativeLoadVision(h, path)) {
                mmprojLoadedPath = path
                true
            } else {
                false
            }
        }

    suspend fun bootstrapBundledQwenLocalFirst(
        ctx: Context,
        openAiRoutingStore: OpenAiRoutingStore,
        envWriter: HermesEnvWriter,
        secrets: Map<String, String>,
    ): Result<Unit> =
        runCatching {
            val entry = ModelDownloadManager.bundledCatalog().firstOrNull() ?: error("bundled catalog empty")
            val path = downloader.defaultQwenPath()
            downloader.download(entry.first, path, entry.second)
            openAiRoutingStore.setMode(OpenAiRoutingStore.Mode.LOCAL_FIRST)
            unload()
            if (!loadModel(path.absolutePath)) {
                error("failed to load GGUF")
            }
            SandboxSkills.bootstrapDefaultSkills(ctx)
            startHttpServer()
            envWriter.syncFromVault(secrets)
        }

    private fun optimalThreads(): Int {
        val p = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        return (if (p > 6) p - 2 else p).coerceIn(3, 8)
    }

    suspend fun autoLoad(): Boolean {
        val prefs = dataStore.data.first()
        val lastPath = prefs[LAST_MODEL_PATH] ?: return false
        val ok = loadModel(lastPath)
        if (ok) {
            startHttpServer()
        }
        return ok
    }

    fun startHttpServer() {
        if (!lanListenAllowed()) {
            return
        }
        server.start()
    }

    fun stopHttpServer() {
        server.stop()
    }

    suspend fun refreshHttpAfterNetworkPolicy() {
        val prefs = dataStore.data.first()
        if (prefs[LAST_MODEL_PATH].isNullOrBlank()) return
        if (lanListenAllowed()) {
            startHttpServer()
        } else {
            stopHttpServer()
        }
    }

    suspend fun benchWarmupOnce(): Result<LlmBenchResult> =
        mutex.withLock {
            val h = handle
            if (h == 0L) return@withLock Result.failure(IllegalStateException("model not loaded"))
            val t0 = System.nanoTime()
            val raw =
                LlamaNative.nativeComplete(
                    h,
                    "<|im_start|>user\nping\n<|im_start|>assistant\n",
                    null,
                    0,
                    0,
                    16,
                    false,
                    0.2f,
                    0.95f,
                )
            val ms = (System.nanoTime() - t0) / 1_000_000L
            if (raw.startsWith("ERROR:")) {
                return@withLock Result.failure(IllegalStateException(raw.removePrefix("ERROR:").trim()))
            }
            val chars = raw.length.coerceAtLeast(1)
            val approxTok = (chars / 4.0).coerceAtLeast(0.5)
            val tps = approxTok / (ms / 1000.0).coerceAtLeast(0.001)
            Result.success(
                LlmBenchResult(
                    latencyMs = ms,
                    outputChars = chars,
                    approxTokensPerSec = (tps * 10).roundToInt() / 10.0,
                ),
            )
        }

    suspend fun unload() {
        mutex.withLock { unloadLocked() }
    }

    fun loadedLabel(): String = loadedModelLabel

    private fun unloadLocked() {
        if (handle != 0L) {
            LlamaNative.nativeUnload(handle)
            handle = 0
        }
        loadedModelLabel = ""
        mmprojLoadedPath = null
    }

    fun modelDownloader(): ModelDownloadManager = downloader

    fun endpointBase(context: Context): String =
        HermesBridgeEnv.build(context).getValue("OPENAI_BASE_URL")
}
