package com.moneat.config

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
    try {
        val redisUrl = environment.config.property("redis.url").getString()
        log.info("Connecting to Redis at $redisUrl...")
        RedisConfig.init(redisUrl)
        log.info("Redis connection established")
        environment.monitor.subscribe(ApplicationStopped) {
            RedisConfig.close()
        }
    } catch (e: Exception) {
        log.error("Failed to connect to Redis. Make sure Redis is running and accessible.", e)
        throw e
    }
}
