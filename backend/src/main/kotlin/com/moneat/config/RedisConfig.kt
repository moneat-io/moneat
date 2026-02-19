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

object RedisConfig {
    private var client: RedisClient? = null
    private var connection: StatefulRedisConnection<String, String>? = null
    private var blockingConnection: StatefulRedisConnection<String, String>? = null

    fun init(redisUrl: String) {
        if (connection != null) return
        val uri = RedisURI.create(redisUrl)
        client = RedisClient.create(uri)
        connection = client!!.connect()
        blockingConnection = client!!.connect()
    }

    fun sync(): RedisCommands<String, String> {
        return connection!!.sync()
    }

    fun syncBlocking(): RedisCommands<String, String> {
        return blockingConnection!!.sync()
    }

    fun async(): RedisAsyncCommands<String, String> {
        return connection!!.async()
    }

    fun reactive(): RedisReactiveCommands<String, String> {
        return connection!!.reactive()
    }

    fun isConnected(): Boolean = connection?.isOpen == true

    fun close() {
        connection?.close()
        connection = null
        blockingConnection?.close()
        blockingConnection = null
        client?.shutdown()
        client = null
    }
}

fun Application.configureRedis() {
    // Skip Redis in test environment if REDIS_URL is not set
    val redisUrl = try {
        environment.config.property("redis.url").getString()
    } catch (e: Exception) {
        log.warn("Redis URL not configured, skipping Redis initialization (test environment)")
        return
    }

    try {
        log.info("Connecting to Redis at $redisUrl...")
        RedisConfig.init(redisUrl)
        log.info("Redis connection established")
        monitor.subscribe(ApplicationStopping) {
            RedisConfig.close()
        }
    } catch (e: Exception) {
        log.error("Failed to connect to Redis. Make sure Redis is running and accessible.", e)
        throw e
    }
}
