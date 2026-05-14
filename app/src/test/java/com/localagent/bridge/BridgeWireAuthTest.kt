package com.localagent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeWireAuthTest {
    private val token = "a".repeat(64)

    @Test
    fun parseAuthToken_valid() {
        assertEquals(token, BridgeWireAuth.parseAuthToken("AUTH $token"))
        assertEquals(token, BridgeWireAuth.parseAuthToken("  AUTH $token  "))
    }

    @Test
    fun parseAuthToken_invalid() {
        assertNull(BridgeWireAuth.parseAuthToken(""))
        assertNull(BridgeWireAuth.parseAuthToken("   "))
        assertNull(BridgeWireAuth.parseAuthToken("auth $token"))
        assertNull(BridgeWireAuth.parseAuthToken("AUTH"))
        assertNull(BridgeWireAuth.parseAuthToken("AUTH "))
        assertNull(BridgeWireAuth.parseAuthToken("AUTHNOTOK $token"))
    }

    @Test
    fun tokensEqual_constantTimeShape() {
        assertTrue(BridgeWireAuth.tokensEqual(token, token))
        assertFalse(BridgeWireAuth.tokensEqual(token, token.dropLast(1) + "b"))
        assertFalse(BridgeWireAuth.tokensEqual(token, token + "x"))
    }
}
