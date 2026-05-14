package com.localagent.runtime

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class HermesManifestSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodes_install_script_pin_fields() {
        val raw =
            """
            {
              "revision": "main",
              "repository": "https://github.com/NousResearch/hermes-agent.git",
              "note": "test",
              "install_script_url": "https://example.com/install.sh",
              "install_script_sha256": "abcd"
            }
            """.trimIndent()
        val m = json.decodeFromString(HermesManifest.serializer(), raw)
        assertEquals("main", m.revision)
        assertEquals("https://example.com/install.sh", m.installScriptUrl)
        assertEquals("abcd", m.installScriptSha256Hex)
    }
}
