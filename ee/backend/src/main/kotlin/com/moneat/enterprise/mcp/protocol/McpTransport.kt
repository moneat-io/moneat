// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private const val SESSION_CHANNEL_CAPACITY = 256
private const val STALE_SESSION_TIMEOUT_MS = 120_000L
private const val STALE_SESSION_REAP_INTERVAL_MS = 30_000L

/**
 * Represents an active MCP session connected via SSE.
 */
class McpSession(
    val id: String = UUID.randomUUID().toString(),
    val organizationId: Int,
    val userId: Int,
    val channel: Channel<String> = Channel(SESSION_CHANNEL_CAPACITY)
) {
    @Volatile
    var lastActivity: Long = System.currentTimeMillis()
        private set

    fun touch() {
        lastActivity = System.currentTimeMillis()
    }

    fun send(event: String, data: String) {
        val message = "event: $event\ndata: $data\n\n"
        val result = channel.trySend(message)
        if (result.isFailure) {
            logger.warn { "MCP session $id channel full or closed, dropping $event message" }
            if (event == "message") {
                // RPC response lost — close session to force client reconnect
                logger.error { "MCP session $id: RPC response dropped, closing session" }
                close()
            }
        } else {
            touch()
        }
    }

    fun sendHeartbeat() {
        val result = channel.trySend(": heartbeat\n\n")
        if (result.isSuccess) {
            touch()
        }
        // No warn on heartbeat drop — if channel is full, data is flowing
    }

    suspend fun sendMessage(response: JsonRpcResponse) {
        val json = McpTransport.json.encodeToString(
            JsonRpcResponse.serializer(),
            response
        )
        send("message", json)
    }

    fun close() {
        channel.close()
    }
}

/**
 * Manages MCP SSE sessions and message routing.
 */
object McpTransport {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        explicitNulls = false
    }

    private val sessions = ConcurrentHashMap<String, McpSession>()

    private val reaperScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        reaperScope.launch {
            while (isActive) {
                delay(STALE_SESSION_REAP_INTERVAL_MS)
                reapStaleSessions()
            }
        }
    }

    fun createSession(organizationId: Int, userId: Int): McpSession {
        val session = McpSession(
            organizationId = organizationId,
            userId = userId
        )
        sessions[session.id] = session
        logger.debug { "Created MCP session ${session.id} for org=$organizationId" }
        return session
    }

    fun getSession(sessionId: String): McpSession? = sessions[sessionId]

    fun removeSession(sessionId: String) {
        sessions.remove(sessionId)?.also { session ->
            session.close()
            logger.debug { "Removed MCP session $sessionId" }
        }
    }

    fun activeSessions(): Int = sessions.size

    fun shutdown() {
        reaperScope.cancel()
    }

    internal fun reapStaleSessions() {
        val now = System.currentTimeMillis()
        val staleIds = sessions.entries
            .filter { (_, session) -> now - session.lastActivity > STALE_SESSION_TIMEOUT_MS }
            .map { it.key }
        for (id in staleIds) {
            logger.debug { "Reaping stale MCP session $id" }
            removeSession(id)
        }
        if (staleIds.isNotEmpty()) {
            logger.info { "Reaped ${staleIds.size} stale MCP session(s)" }
        }
    }
}
