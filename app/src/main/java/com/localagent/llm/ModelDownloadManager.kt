package com.localagent.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ModelDownloadManager(private val context: Context) {

    private val client =
        OkHttpClient.Builder()
            .followRedirects(true)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.MINUTES)
            .writeTimeout(60, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(12, 5, TimeUnit.MINUTES))
            .build()

    fun modelsDir(): File {
        val dir = File(context.filesDir, "models")
        dir.mkdirs()
        return dir
    }

    suspend fun download(
        url: String,
        targetFile: File,
        expectedSha256Hex: String?,
        onProgress: ((bytesRead: Long, totalBytes: Long?) -> Unit)? = null,
    ) =
        withContext(Dispatchers.IO) {
            targetFile.parentFile?.mkdirs()
            val tmp = File(targetFile.absolutePath + ".part")
            val pinnedSha = expectedSha256Hex?.trim()?.takeIf { it.isNotEmpty() }
            if (pinnedSha != null) {
                tmp.delete()
                downloadPinnedSha(url, tmp, pinnedSha, onProgress)
            } else {
                downloadUnpinnedAdaptive(url, tmp, onProgress)
            }
            if (!tmp.renameTo(targetFile)) {
                error("rename failed")
            }
        }

    private fun downloadPinnedSha(
        url: String,
        tmp: File,
        expectedHex: String,
        onProgress: ((Long, Long?) -> Unit)?,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("download failed ${resp.code}")
            val body = resp.body ?: error("empty body")
            val total = body.contentLength().takeIf { it >= 0 }
            FileOutputStream(tmp, false).use { out ->
                body.byteStream().use { input ->
                    copyStreamToOutput(input, out, digest, 0L, total, onProgress)
                }
            }
            val hex = digest.digest().joinToString("") { b -> "%02x".format(b) }
            if (!hex.equals(expectedHex, ignoreCase = true)) {
                error("sha256 mismatch expected=$expectedHex got=$hex")
            }
        }
    }

    private suspend fun downloadUnpinnedAdaptive(
        url: String,
        tmp: File,
        onProgress: ((Long, Long?) -> Unit)?,
    ) {
        val resumeFrom = if (tmp.exists()) tmp.length() else 0L
        if (resumeFrom > 0L) {
            downloadResumable(url, tmp, onProgress)
            return
        }
        tmp.delete()
        val probe = probeParallel(url) ?: run {
            downloadResumable(url, tmp, onProgress)
            return
        }
        val minTotal = 8L * 1024L * 1024L
        if (!probe.acceptRanges || probe.contentLength < minTotal) {
            downloadResumable(url, tmp, onProgress)
            return
        }
        val parts =
            (probe.contentLength / (4L * 1024L * 1024L)).toInt().coerceIn(2, 8)
        runCatching {
            downloadParallel(url, tmp, probe.contentLength, parts, onProgress)
        }.onFailure {
            tmp.delete()
            downloadResumable(url, tmp, onProgress)
        }
    }

    private data class ParallelProbe(
        val acceptRanges: Boolean,
        val contentLength: Long,
    )

    private fun probeParallel(url: String): ParallelProbe? {
        client
            .newCall(
                Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-0")
                    .build(),
            ).execute()
            .use { r ->
                when (r.code) {
                    206 -> {
                        val total =
                            parseContentRangeTotal(r.header("Content-Range"))
                                ?: return null
                        r.consumeBodyQuietly()
                        return ParallelProbe(true, total)
                    }
                    else -> r.discardBounded()
                }
            }
        client.newCall(Request.Builder().url(url).head().build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val accept =
                r.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true ||
                    r.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
            val len = r.header("Content-Length")?.toLongOrNull() ?: return null
            return ParallelProbe(accept, len)
        }
    }

    private fun Response.consumeBodyQuietly() {
        runCatching { body?.bytes() }
    }

    private fun Response.discardBounded() {
        val body = body ?: return
        runCatching {
            body.byteStream().use { inp ->
                val buf = ByteArray(8192)
                var drained = 0
                while (drained < 65536) {
                    val rd = inp.read(buf)
                    if (rd <= 0) break
                    drained += rd
                }
            }
        }
    }

    private suspend fun downloadParallel(
        url: String,
        out: File,
        total: Long,
        parts: Int,
        onProgress: ((Long, Long?) -> Unit)?,
    ) {
        require(total > 0 && parts >= 2)
        val dir =
            File(out.parentFile, "${out.name}.par").apply {
                deleteRecursively()
                mkdirs()
            }
        val segFiles = Array(parts) { File(dir, "p$it") }
        val received = AtomicLong(0)
        coroutineScope {
            (0 until parts)
                .map { i ->
                    async(Dispatchers.IO) {
                        val start = i * total / parts
                        val endExclusive = (i + 1) * total / parts
                        val endInclusive = endExclusive - 1
                        val req =
                            Request.Builder()
                                .url(url)
                                .header("Range", "bytes=$start-$endInclusive")
                                .build()
                        client.newCall(req).execute().use { resp ->
                            check(resp.code == 206) { "expected 206 for parallel segment, got ${resp.code}" }
                            val body = resp.body ?: error("empty segment body")
                            FileOutputStream(segFiles[i], false).use { fos ->
                                body.byteStream().use { input ->
                                    val buf = ByteArray(65536)
                                    while (true) {
                                        val rd = input.read(buf)
                                        if (rd <= 0) break
                                        fos.write(buf, 0, rd)
                                        val acc = received.addAndGet(rd.toLong())
                                        onProgress?.invoke(acc, total)
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
        }
        FileOutputStream(out, false).use { mux ->
            for (seg in segFiles) {
                FileInputStream(seg).use { fs -> fs.copyTo(mux) }
                seg.delete()
            }
        }
        require(out.length() == total) {
            "parallel merge incomplete: got ${out.length()} expected $total"
        }
        dir.deleteRecursively()
        onProgress?.invoke(total, total)
    }

    private fun downloadResumable(
        url: String,
        tmp: File,
        onProgress: ((Long, Long?) -> Unit)?,
    ) {
        var retries = 0
        while (true) {
            check(retries++ < 12) { "download stalled after Range/full retries" }
            val existing = if (tmp.exists()) tmp.length() else 0L
            val reqBuilder = Request.Builder().url(url)
            if (existing > 0) {
                reqBuilder.header("Range", "bytes=$existing-")
            }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                when (resp.code) {
                    416 -> {
                        tmp.delete()
                        return@use
                    }
                    200 -> {
                        val body = resp.body ?: error("empty body")
                        val total = body.contentLength().takeIf { it >= 0 }
                        FileOutputStream(tmp, false).use { out ->
                            body.byteStream().use { input ->
                                copyStreamToOutput(input, out, null, 0L, total, onProgress)
                            }
                        }
                        return
                    }
                    206 -> {
                        val body = resp.body ?: error("empty body")
                        val append = existing > 0
                        val totalBytes =
                            parseContentRangeTotal(resp.header("Content-Range"))
                                ?: body.contentLength().takeIf { it >= 0 }?.plus(existing)
                        FileOutputStream(tmp, append).use { out ->
                            body.byteStream().use { input ->
                                copyStreamToOutput(input, out, null, existing, totalBytes, onProgress)
                            }
                        }
                        return
                    }
                    else -> error("download failed ${resp.code}")
                }
            }
        }
    }

    private fun parseContentRangeTotal(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val slash = header.lastIndexOf('/')
        if (slash < 0 || slash >= header.length - 1) return null
        val tail = header.substring(slash + 1).trim()
        if (tail == "*") return null
        return tail.toLongOrNull()
    }

    private fun copyStreamToOutput(
        input: java.io.InputStream,
        out: FileOutputStream,
        digest: MessageDigest?,
        initialBytesOnDisk: Long,
        totalExpected: Long?,
        onProgress: ((Long, Long?) -> Unit)?,
    ) {
        val buffer = ByteArray(65536)
        var sessionRead = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest?.update(buffer, 0, read)
            out.write(buffer, 0, read)
            sessionRead += read
            val absolute = initialBytesOnDisk + sessionRead
            onProgress?.invoke(absolute, totalExpected)
        }
        out.flush()
    }

    fun defaultQwenPath(): File =
        File(modelsDir(), "qwen2.5-0.5b-instruct-q4_k_m.gguf")

    companion object {
        /** Pin URL + digest when distributing a known-good GGUF; empty digest skips verification. */
        fun bundledCatalog(): List<Pair<String, String?>> =
            listOf(
                "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf" to null,
            )
    }
}
