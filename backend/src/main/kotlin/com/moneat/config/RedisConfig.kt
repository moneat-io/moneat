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
    val redisUrl = environment.config.property("redis.url").getString()
    RedisConfig.init(redisUrl)
    environment.monitor.subscribe(ApplicationStopped) {
        RedisConfig.close()
    }
}
