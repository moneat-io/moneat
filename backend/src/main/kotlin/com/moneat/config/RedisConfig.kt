// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package com.moneat.config

import io.ktor.events.*
import io.ktor.server.application.*
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.api.reactive.RedisReactiveCommands
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.resource.ClientResources
import io.netty.resolver.DefaultAddressResolverGroup
import java.time.Duration
import com.moneat.utils.suspendRunCatching

const val BRPOP_TIMEOUT_SECONDS = 5L

// Blocking command timeout — must exceed BRPOP wait time. Each worker gets its own connection.
private val BLOCKING_COMMAND_TIMEOUT: Duration = Duration.ofSeconds(BRPOP_TIMEOUT_SECONDS + 10)

object RedisConfig {
    @Volatile
    private var client: RedisClient? = null

    @Volatile
    private var connection: StatefulRedisConnection<String, String>? = null
    private val blockingConnections = mutableListOf<StatefulRedisConnection<String, String>>()

    fun init(redisUrl: String) {
        if (connection != null) return
        val uri = RedisURI.create(redisUrl)
        val resources = ClientResources.builder()
            .addressResolverGroup(DefaultAddressResolverGroup.INSTANCE)
            .build()
        client = RedisClient.create(resources, uri)
        connection = client!!.connect()
    }

    fun sync(): RedisCommands<String, String> {
        val conn = checkNotNull(connection) { "RedisConfig is not initialized. Call init() first." }
        return conn.sync()
    }

    /** Returns a dedicated blocking connection for a single worker. Each call creates a new connection. */
    fun newStatefulBlockingConnection(): StatefulRedisConnection<String, String> {
        val c = checkNotNull(client) { "RedisConfig is not initialized. Call init() first." }
        val conn = c.connect().also { it.timeout = BLOCKING_COMMAND_TIMEOUT }
        synchronized(blockingConnections) { blockingConnections.add(conn) }
        return conn
    }

    /**
     * Returns a dedicated blocking connection for a single worker (same as [newStatefulBlockingConnection]).
     * Callers must pass the returned connection to [closeBlockingConnection] when the worker stops.
     */
    fun newBlockingConnection(): StatefulRedisConnection<String, String> =
        newStatefulBlockingConnection()

    /**
     * Closes a blocking worker connection and removes it from the shutdown list so it is not closed twice.
     */
    fun closeBlockingConnection(conn: StatefulRedisConnection<String, String>) {
        val removed =
            synchronized(blockingConnections) {
                blockingConnections.remove(conn)
            }
        if (removed) {
            conn.close()
        }
    }

    fun async(): RedisAsyncCommands<String, String> {
        val conn = checkNotNull(connection) { "RedisConfig is not initialized. Call init() first." }
        return conn.async()
    }

    fun reactive(): RedisReactiveCommands<String, String> {
        val conn = checkNotNull(connection) { "RedisConfig is not initialized. Call init() first." }
        return conn.reactive()
    }

    fun isConnected(): Boolean = connection?.isOpen == true

    fun isInitialized(): Boolean = connection != null

    fun close() {
        connection?.close()
        connection = null
        synchronized(blockingConnections) {
            blockingConnections.forEach { it.close() }
            blockingConnections.clear()
        }
        client?.shutdown()
        client = null
    }
}

fun Application.configureRedis() {
    // Skip Redis in test environment if REDIS_URL is not set
    val redisUrl =
        suspendRunCatching {
            environment.config.property("redis.url").getString()
        }.getOrElse { e ->
            log.warn("Redis URL not configured, skipping Redis initialization (test environment)")
            return
        }

    suspendRunCatching {
        log.info("Connecting to Redis at $redisUrl...")
        RedisConfig.init(redisUrl)
        log.info("Redis connection established")
        // Note: Shutdown is handled by BackgroundJobs to ensure correct ordering
        // (workers must stop before Redis connection is closed)
    }.getOrElse { e ->
        log.error("Failed to connect to Redis. Make sure Redis is running and accessible.", e)
        throw e
    }
}
