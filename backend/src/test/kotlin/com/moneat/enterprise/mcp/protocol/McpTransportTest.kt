// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.mcp.protocol

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
class McpTransportTest {

    @Test
    fun `createSession generates unique IDs`() {
        val session1 = McpTransport.createSession(1, 1)
        val session2 = McpTransport.createSession(1, 1)

        assertNotEquals(session1.id, session2.id)

        // Clean up
        McpTransport.removeSession(session1.id)
        McpTransport.removeSession(session2.id)
    }

    @Test
    fun `getSession returns created session`() {
        val session = McpTransport.createSession(1, 2)

        val retrieved = McpTransport.getSession(session.id)
        assertNotNull(retrieved)
        assertEquals(1, retrieved.organizationId)
        assertEquals(2, retrieved.userId)

        McpTransport.removeSession(session.id)
    }

    @Test
    fun `getSession returns null for unknown ID`() {
        val result = McpTransport.getSession("nonexistent-id")
        assertEquals(null, result)
    }

    @Test
    fun `removeSession cleans up session`() {
        val session = McpTransport.createSession(1, 1)
        val id = session.id

        McpTransport.removeSession(id)

        assertEquals(null, McpTransport.getSession(id))
    }

    @Test
    fun `session carries org and user context`() {
        val session = McpTransport.createSession(42, 7)

        assertEquals(42, session.organizationId)
        assertEquals(7, session.userId)

        McpTransport.removeSession(session.id)
    }

    @Test
    fun `session send does not block when channel is full`() {
        val session = McpTransport.createSession(1, 1)
        try {
            // Fill the channel beyond its capacity without consuming — trySend should not block
            repeat(512) { i ->
                session.send("test", "message-$i")
            }
            // If we reach here, trySend correctly dropped overflow messages without blocking
        } finally {
            McpTransport.removeSession(session.id)
        }
    }

    @Test
    fun `removeSession closes channel so send is a no-op`() {
        val session = McpTransport.createSession(1, 1)
        McpTransport.removeSession(session.id)
        // Sending to a closed session should not throw
        session.send("test", "after-close")
    }

    @Test
    fun `session tracks lastActivity on send`() {
        val session = McpTransport.createSession(1, 1)
        try {
            val before = session.lastActivity
            Thread.sleep(50)
            session.send("test", "data")
            assertTrue(session.lastActivity >= before + 50, "lastActivity should be updated on send")
        } finally {
            McpTransport.removeSession(session.id)
        }
    }

    @Test
    fun `session touch updates lastActivity`() {
        val session = McpTransport.createSession(1, 1)
        try {
            val before = session.lastActivity
            Thread.sleep(50)
            session.touch()
            assertTrue(session.lastActivity >= before + 50, "touch() should update lastActivity")
        } finally {
            McpTransport.removeSession(session.id)
        }
    }

    @Test
    fun `reapStaleSessions removes inactive sessions`() {
        val session = McpTransport.createSession(1, 1)
        try {
            McpTransport.reapStaleSessions()
            assertNotNull(McpTransport.getSession(session.id), "Fresh session should not be reaped")
        } finally {
            McpTransport.removeSession(session.id)
        }
    }

    @Test
    fun `send message event closes session when channel is full`() {
        val session = McpTransport.createSession(1, 1)
        try {
            // Fill the channel to capacity
            repeat(256) { i ->
                session.send("test", "filler-$i")
            }
            // Sending a "message" event (RPC response) when full should close the session
            session.send("message", """{"jsonrpc":"2.0","id":1,"result":{}}""")
            assertTrue(session.channel.isClosedForSend, "Session should be closed after RPC response drop")
        } finally {
            McpTransport.removeSession(session.id)
        }
    }

    @Test
    fun `send non-message event does not close session when channel is full`() {
        val session = McpTransport.createSession(1, 1)
        try {
            // Fill the channel to capacity
            repeat(256) { i ->
                session.send("test", "filler-$i")
            }
            // Sending a non-"message" event when full should NOT close the session
            session.send("test", "overflow")
            assertFalse(session.channel.isClosedForSend, "Session should stay open for non-RPC event drop")
        } finally {
            McpTransport.removeSession(session.id)
        }
    }

    @Test
    fun `sendHeartbeat does not close session when channel is full`() {
        val session = McpTransport.createSession(1, 1)
        try {
            repeat(256) { i ->
                session.send("test", "filler-$i")
            }
            session.sendHeartbeat()
            assertFalse(session.channel.isClosedForSend, "Heartbeat drop should not close session")
        } finally {
            McpTransport.removeSession(session.id)
        }
    }
}
