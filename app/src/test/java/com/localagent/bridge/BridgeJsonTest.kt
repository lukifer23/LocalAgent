package com.localagent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeJsonTest {
    @Test
    fun roundtripUserMessage() {
        val original =
            BridgeEvent.UserMessage(
                ts = 12L,
                text = "ping",
            )
        val line = BridgeJson.json.encodeToString(BridgeEvent.serializer(), original)
        val decoded = BridgeJson.parse(line)
        assertTrue(decoded is BridgeEvent.UserMessage)
        assertEquals("ping", (decoded as BridgeEvent.UserMessage).text)
    }

    @Test
    fun roundtripUserMessageWithSessionId() {
        val original =
            BridgeEvent.UserMessage(
                ts = 12L,
                text = "ping",
                sessionId = "sess-1",
            )
        val line = BridgeJson.json.encodeToString(BridgeEvent.serializer(), original)
        val decoded = BridgeJson.parse(line)
        assertTrue(decoded is BridgeEvent.UserMessage)
        assertEquals("sess-1", (decoded as BridgeEvent.UserMessage).sessionId)
    }
}
