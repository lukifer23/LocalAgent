package com.localagent.bridge

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object BridgeWireAuth {
    const val AUTH_PREFIX: String = "AUTH "

    fun tokensEqual(expected: String, presented: String): Boolean {
        val a = expected.toByteArray(StandardCharsets.UTF_8)
        val b = presented.toByteArray(StandardCharsets.UTF_8)
        if (a.size != b.size) {
            return false
        }
        return MessageDigest.isEqual(a, b)
    }

    fun parseAuthToken(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith(AUTH_PREFIX)) {
            return null
        }
        return trimmed.substring(AUTH_PREFIX.length).trim().takeIf { it.isNotEmpty() }
    }
}
