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

package com.moneat.services

import com.moneat.config.RedisConfig
import com.moneat.utils.SentryUtils
import io.sentry.ISpan
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
        parentSpan: ISpan? = null,
        noinline loader: suspend () -> T
    ): T = cachedImpl(key, ttlSeconds, serializer<T>(), parentSpan, loader)

    suspend fun <T> cachedImpl(
        key: String,
        ttlSeconds: Long,
        serializer: KSerializer<T>,
        parentSpan: ISpan? = null,
        loader: suspend () -> T
    ): T {
        val cached =
            try {
                withContext(Dispatchers.IO) {
                    if (RedisConfig.isConnected()) {
                        val value =
                            if (parentSpan != null && io.sentry.Sentry.isEnabled()) {
                                SentryUtils.withSpan(parentSpan, "cache.get", "Redis GET $key") { span ->
                                    span?.setData("cache.key", key)
                                    span?.setData("cache.hit", false)
                                    RedisConfig.sync().get(key)
                                }
                            } else {
                                RedisConfig.sync().get(key)
                            }
                        value
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                cacheLogger.warn(e) { "Cache GET failed for key=$key" }
                SentryUtils.breadcrumb("cache", "Cache GET failed", mapOf("key" to key, "error" to (e.message ?: "")))
                null
            }

        if (cached != null) {
            SentryUtils.breadcrumb("cache", "Cache HIT", mapOf("key" to key))
            return try {
                cacheJson.decodeFromString(serializer, cached)
            } catch (e: Exception) {
                cacheLogger.warn(e) { "Cache deserialize failed for key=$key" }
                loader()
            }
        }

        SentryUtils.breadcrumb("cache", "Cache MISS", mapOf("key" to key))
        val value = loader()

        try {
            withContext(Dispatchers.IO) {
                if (RedisConfig.isConnected()) {
                    val encoded = cacheJson.encodeToString(serializer, value)
                    if (parentSpan != null && io.sentry.Sentry.isEnabled()) {
                        SentryUtils.withSpan(parentSpan, "cache.set", "Redis SETEX $key") { span ->
                            span?.setData("cache.key", key)
                            span?.setData("cache.ttl", ttlSeconds)
                            RedisConfig.sync().setex(key, ttlSeconds, encoded)
                        }
                    } else {
                        RedisConfig.sync().setex(key, ttlSeconds, encoded)
                    }
                }
            }
        } catch (e: Exception) {
            cacheLogger.warn(e) { "Cache SETEX failed for key=$key" }
            SentryUtils.breadcrumb("cache", "Cache SET failed", mapOf("key" to key, "error" to (e.message ?: "")))
        }
        return value
    }
}
