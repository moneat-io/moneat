package com.moneat.services

import com.moneat.config.RedisConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import mu.KotlinLogging

private val cacheLogger = KotlinLogging.logger {}
private val cacheJson = Json { ignoreUnknownKeys = true }

/**
 * Redis-backed cache for dashboard and monitor queries.
 * On cache hit returns deserialized value; on miss runs loader, stores with TTL, and returns.
 */
object CacheService {

    suspend inline fun <reified T> cached(
        key: String,
        ttlSeconds: Long,
        noinline loader: suspend () -> T
    ): T = cachedImpl(key, ttlSeconds, serializer<T>(), loader)

    suspend fun <T> cachedImpl(
        key: String,
        ttlSeconds: Long,
        serializer: KSerializer<T>,
        loader: suspend () -> T
    ): T {
        val cached = try {
            withContext(Dispatchers.IO) {
                if (RedisConfig.isConnected()) RedisConfig.sync().get(key) else null
            }
        } catch (e: Exception) {
            cacheLogger.warn(e) { "Cache GET failed for key=$key" }
            null
        }
        if (cached != null) {
            return try {
                cacheJson.decodeFromString(serializer, cached)
            } catch (e: Exception) {
                cacheLogger.warn(e) { "Cache deserialize failed for key=$key" }
                loader()
            }
        }
        val value = loader()
        try {
            withContext(Dispatchers.IO) {
                if (RedisConfig.isConnected()) {
                    val encoded = cacheJson.encodeToString(serializer, value)
                    RedisConfig.sync().setex(key, ttlSeconds, encoded)
                }
            }
        } catch (e: Exception) {
            cacheLogger.warn(e) { "Cache SETEX failed for key=$key" }
        }
        return value
    }
}
