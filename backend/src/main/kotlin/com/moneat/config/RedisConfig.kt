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

import com.moneat.utils.suspendRunCatching
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisConnectionException
import io.lettuce.core.RedisException
import io.lettuce.core.RedisURI
import io.lettuce.core.SocketOptions
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.api.reactive.RedisReactiveCommands
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.resource.ClientResources
import io.netty.resolver.DefaultAddressResolverGroup
import java.time.Duration
import java.util.concurrent.locks.ReentrantReadWriteLock

private const val BLOCKING_COMMAND_TIMEOUT_SECONDS = 15L
private const val MONITORING_COMMAND_TIMEOUT_SECONDS = 2L
private const val MONITORING_RECONNECT_COOLDOWN_SECONDS = 10L
private const val REDIS_NOT_INITIALIZED_MESSAGE = "RedisConfig is not initialized. Call init() first."

// Blocking command timeout for workers using XREADGROUP BLOCK. Each worker gets its own connection.
private val BLOCKING_COMMAND_TIMEOUT: Duration = Duration.ofSeconds(BLOCKING_COMMAND_TIMEOUT_SECONDS)
private val MONITORING_COMMAND_TIMEOUT: Duration = Duration.ofSeconds(MONITORING_COMMAND_TIMEOUT_SECONDS)
private val MONITORING_RECONNECT_COOLDOWN: Duration = Duration.ofSeconds(MONITORING_RECONNECT_COOLDOWN_SECONDS)

private sealed interface MonitoringRead<out T> {
    data class Success<T>(val value: T) : MonitoringRead<T>

    data class ConnectionFailure(
        val connection: StatefulRedisConnection<String, String>
    ) : MonitoringRead<Nothing>
}

object RedisConfig {
    @Volatile
    private var client: RedisClient? = null

    @Volatile
    private var connection: StatefulRedisConnection<String, String>? = null

    @Volatile
    private var monitoringConnection: StatefulRedisConnection<String, String>? = null
    private val monitoringConnectionLock = ReentrantReadWriteLock()

    @Volatile
    private var monitoringReconnectAllowedAtNanos = 0L
    private val blockingConnections = mutableListOf<StatefulRedisConnection<String, String>>()

    fun init(redisUrl: String) {
        if (connection != null) return
        val uri = RedisURI.create(redisUrl)
        val resources = ClientResources.builder()
            .addressResolverGroup(DefaultAddressResolverGroup.INSTANCE)
            .build()
        client = RedisClient.create(resources, uri).also {
            it.setOptions(
                ClientOptions.builder()
                    .socketOptions(
                        SocketOptions.builder()
                            .connectTimeout(MONITORING_COMMAND_TIMEOUT)
                            .build()
                    )
                    .build()
            )
        }
        connection = client!!.connect()
        monitoringConnection = client!!.connect().also { it.timeout = MONITORING_COMMAND_TIMEOUT }
    }

    fun sync(): RedisCommands<String, String> {
        val conn = checkNotNull(connection) { REDIS_NOT_INITIALIZED_MESSAGE }
        return conn.sync()
    }

    /**
     * Returns commands on the connection reserved for operational metric reads.
     *
     * Intake requests can keep the primary Redis connection busy during bursts. Keeping metric reads on a
     * separate connection prevents Prometheus gauges from degrading to NaN while queue admission is active.
     */
    fun <T> withMonitoringSync(operation: (RedisCommands<String, String>) -> T): T {
        when (val initialRead = withOpenMonitoringConnection(operation)) {
            is MonitoringRead.Success -> return initialRead.value
            is MonitoringRead.ConnectionFailure -> invalidateMonitoringConnection(initialRead.connection)
            null -> Unit
        }
        reconnectMonitoringConnectionIfDue()
        return when (val retriedRead = withOpenMonitoringConnection(operation)) {
            is MonitoringRead.Success -> retriedRead.value
            is MonitoringRead.ConnectionFailure -> {
                invalidateMonitoringConnection(retriedRead.connection)
                operation(sync())
            }
            null -> operation(sync())
        }
    }

    private fun <T> withOpenMonitoringConnection(
        operation: (RedisCommands<String, String>) -> T
    ): MonitoringRead<T>? {
        val readLock = monitoringConnectionLock.readLock()
        if (!readLock.tryLock()) return null
        return try {
            val monitoring = monitoringConnection?.takeIf { it.isOpen } ?: return null
            try {
                MonitoringRead.Success(operation(monitoring.sync()))
            } catch (error: RedisException) {
                if (!isMonitoringConnectionFailure(error)) throw error
                MonitoringRead.ConnectionFailure(monitoring)
            }
        } finally {
            readLock.unlock()
        }
    }

    private fun invalidateMonitoringConnection(
        failedConnection: StatefulRedisConnection<String, String>
    ) {
        val writeLock = monitoringConnectionLock.writeLock()
        if (!writeLock.tryLock()) return
        try {
            if (monitoringConnection !== failedConnection) return
            failedConnection.close()
            monitoringConnection = null
            monitoringReconnectAllowedAtNanos = 0L
        } finally {
            writeLock.unlock()
        }
    }

    private fun reconnectMonitoringConnectionIfDue() {
        val now = System.nanoTime()
        if (now < monitoringReconnectAllowedAtNanos) return

        val writeLock = monitoringConnectionLock.writeLock()
        if (!writeLock.tryLock()) return
        try {
            if (monitoringConnection?.isOpen == true) return
            val attemptStartedAt = System.nanoTime()
            if (attemptStartedAt < monitoringReconnectAllowedAtNanos) return
            monitoringReconnectAllowedAtNanos = attemptStartedAt + MONITORING_RECONNECT_COOLDOWN.toNanos()
            reconnectClosedMonitoringConnection(monitoringConnection, client)?.let { replacement ->
                monitoringConnection = replacement
                monitoringReconnectAllowedAtNanos = 0L
            }
        } finally {
            writeLock.unlock()
        }
    }

    /** Returns a dedicated blocking connection for a single worker. Each call creates a new connection. */
    fun newStatefulBlockingConnection(): StatefulRedisConnection<String, String> {
        val c = checkNotNull(client) { REDIS_NOT_INITIALIZED_MESSAGE }
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
        val conn = checkNotNull(connection) { REDIS_NOT_INITIALIZED_MESSAGE }
        return conn.async()
    }

    fun reactive(): RedisReactiveCommands<String, String> {
        val conn = checkNotNull(connection) { REDIS_NOT_INITIALIZED_MESSAGE }
        return conn.reactive()
    }

    fun isConnected(): Boolean = connection?.isOpen == true

    fun isMonitoringConnected(): Boolean = monitoringConnection?.isOpen == true || isConnected()

    fun isInitialized(): Boolean = connection != null

    fun close() {
        val writeLock = monitoringConnectionLock.writeLock()
        writeLock.lock()
        try {
            monitoringConnection?.close()
            monitoringConnection = null
            monitoringReconnectAllowedAtNanos = 0L
            connection?.close()
            connection = null
            synchronized(blockingConnections) {
                blockingConnections.forEach { it.close() }
                blockingConnections.clear()
            }
            client?.shutdown()
            client = null
        } finally {
            writeLock.unlock()
        }
    }
}

internal fun reconnectClosedMonitoringConnection(
    current: StatefulRedisConnection<String, String>?,
    client: RedisClient?,
): StatefulRedisConnection<String, String>? {
    if (current?.isOpen == true) return current
    return runCatching {
        current?.close()
        checkNotNull(client) { REDIS_NOT_INITIALIZED_MESSAGE }
            .connect()
            .also { it.timeout = MONITORING_COMMAND_TIMEOUT }
    }.getOrNull()
}

internal fun isMonitoringConnectionFailure(error: RedisException): Boolean =
    error is RedisCommandTimeoutException || error is RedisConnectionException

fun Application.configureRedis() {
    // Skip Redis in test environment if REDIS_URL is not set
    val redisUrl =
        suspendRunCatching {
            environment.config.property("redis.url").getString()
        }.getOrElse { _ ->
            log.warn("Redis URL not configured, skipping Redis initialization (test environment)")
            return
        }

    suspendRunCatching {
        log.info("Connecting to Redis...")
        RedisConfig.init(redisUrl)
        log.info("Redis connection established")
        // Note: Shutdown is handled by BackgroundJobs to ensure correct ordering
        // (workers must stop before Redis connection is closed)
    }.getOrElse { e ->
        log.error("Failed to connect to Redis. Make sure Redis is running and accessible.", e)
        throw e
    }
}
