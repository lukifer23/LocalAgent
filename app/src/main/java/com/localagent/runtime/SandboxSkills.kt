package com.localagent.runtime

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Sandbox mirror of Hermes skills under [HermesPaths.hermesRoot]/skills plus Termux push via bash stdin.
 */
object SandboxSkills {
    private const val MAX_PUSH_BYTES = 768 * 1024

    fun skillsDir(context: Context): File = File(HermesPaths.hermesRoot(context), "skills")

    fun bootstrapDefaultSkills(context: Context): Result<Unit> {
        val dir = skillsDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) {
            return Result.failure(IllegalStateException("could not create sandbox skills dir"))
        }
        return runCatching {
            context.assets.list("skills")?.forEach { name ->
                val target = File(dir, name)
                if (!target.exists()) {
                    context.assets.open("skills/$name").use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    fun sanitizeFilename(name: String): String? {
        if (name.isEmpty() || name.contains('/') || name.contains("..")) return null
        if (!name.matches(Regex("^[a-zA-Z0-9._-]+$"))) return null
        return name
    }

    fun buildTermuxPushScript(context: Context): Result<String> {
        val dir = skillsDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) {
            return Result.failure(IllegalStateException("could not create sandbox skills dir"))
        }
        val files =
            dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) {
            return Result.failure(IllegalStateException("sandbox skills directory is empty"))
        }
        val sb = StringBuilder()
        sb.appendLine("set -e")
        sb.appendLine("mkdir -p ~/.hermes/skills")
        var utf8Estimate = sb.toString().toByteArray(Charsets.UTF_8).size
        for (f in files) {
            val safe = sanitizeFilename(f.name) ?: continue
            val content = f.readText(Charsets.UTF_8)
            val delim = "LA_SK_${UUID.randomUUID().toString().replace("-", "")}_EOF"
            if (content.contains(delim)) {
                return Result.failure(IllegalStateException("skill file contains delimiter collision: ${f.name}"))
            }
            val chunk =
                buildString {
                    appendLine("cat > ~/.hermes/skills/$safe <<'$delim'")
                    append(content)
                    if (!content.endsWith('\n')) appendLine()
                    appendLine(delim)
                }
            utf8Estimate += chunk.toByteArray(Charsets.UTF_8).size
            if (utf8Estimate > MAX_PUSH_BYTES) {
                return Result.failure(IllegalStateException("skills bundle exceeds safe stdin limit"))
            }
            sb.append(chunk)
        }
        sb.appendLine("chmod -R u+rw ~/.hermes/skills 2>/dev/null || true")
        val script = sb.toString()
        if (script.toByteArray(Charsets.UTF_8).size > MAX_PUSH_BYTES) {
            return Result.failure(IllegalStateException("skills bundle exceeds safe stdin limit"))
        }
        return Result.success(script)
    }

    fun toggleSkillDisabled(context: Context, filenameArg: String, disable: Boolean): Result<Unit> {
        val dir = skillsDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) {
            return Result.failure(IllegalStateException("could not create sandbox skills dir"))
        }
        val stem = filenameArg.trim().removeSuffix(".disabled")
        val safe = sanitizeFilename(stem) ?: return Result.failure(IllegalArgumentException("invalid skill filename"))
        val active = File(dir, safe)
        val disabled = File(dir, "$safe.disabled")
        return runCatching {
            when {
                disable -> {
                    check(active.exists()) { "no active file: $safe" }
                    check(!disabled.exists()) { "already disabled: $safe" }
                    check(active.renameTo(disabled)) { "rename to disabled failed" }
                }
                else -> {
                    check(disabled.exists()) { "not disabled: $safe" }
                    check(!active.exists()) { "active file already exists: $safe" }
                    check(disabled.renameTo(active)) { "rename from disabled failed" }
                }
            }
        }
    }
}
