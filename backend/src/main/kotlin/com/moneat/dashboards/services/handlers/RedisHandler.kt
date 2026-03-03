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

package com.moneat.dashboards.services.handlers

import com.moneat.dashboards.models.DataSourceField
import com.moneat.dashboards.models.TestConnectionRequest
import com.moneat.dashboards.models.TestConnectionResult
import com.moneat.dashboards.models.TimeRangeDef
import com.moneat.dashboards.services.DataSourceCredentials
import io.lettuce.core.RedisClient
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Redis handler using Lettuce client.
 * Query format: Redis command (e.g. GET key, HGETALL hash, LRANGE list 0 -1).
 * For SCAN-style discovery, use SCAN 0 MATCH * COUNT 10.
 */
class RedisHandler : DataSourceHandler {

    override suspend fun testConnection(request: TestConnectionRequest): TestConnectionResult {
        val uri = buildRedisUri(request.host, request.port ?: 6379, request.password)

        return try {
            RedisClient.create(uri).use { client ->
                client.connect().use { conn ->
                    conn.sync().ping()
                    val keys = scanKeys(conn.sync(), "*", 20)
                    TestConnectionResult(true, "Connected successfully", keys = keys)
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Redis connection test failed" }
            TestConnectionResult(false, "Connection failed: ${e.message}")
        }
    }

    override suspend fun executeQuery(
        sourceId: Long,
        host: String,
        port: Int?,
        databaseName: String?,
        credentials: DataSourceCredentials,
        query: String,
        limit: Int,
        timeRange: TimeRangeDef?,
    ): List<Map<String, JsonElement>> {
        val uri = buildRedisUri(host, port ?: 6379, credentials.password)
        val db = databaseName?.toIntOrNull() ?: 0

        return try {
            RedisClient.create(uri).use { client ->
                client.connect().use { conn ->
                    if (db != 0) conn.sync().select(db)
                    val cmd = conn.sync()
                    val parts = query.trim().split("\\s+".toRegex())
                    val result = when (parts.firstOrNull()?.uppercase()) {
                        "INFO" -> {
                            val section = parts.getOrNull(1)
                            val field = parts.getOrNull(2)
                            val raw = if (section != null) cmd.info(section) else cmd.info()
                            val rows = if (section?.lowercase() == "commandstats") {
                                parseCommandStats(raw)
                            } else {
                                parseInfoOutput(raw)
                            }
                            if (!field.isNullOrBlank() && rows.isNotEmpty()) {
                                val filtered = rows[0].filterKeys { it == field }
                                if (filtered.isEmpty()) rows else listOf(filtered)
                            } else {
                                rows
                            }
                        }
                        "CLIENT" -> {
                            if (parts.getOrNull(1)?.uppercase() == "LIST") {
                                parseClientList(cmd.clientList())
                            } else {
                                emptyList()
                            }
                        }
                        "SLOWLOG" -> {
                            if (parts.getOrNull(1)?.uppercase() == "GET") {
                                val count = parts.getOrNull(2)?.toIntOrNull() ?: limit
                                parseSlowlog(cmd.slowlogGet(count))
                            } else {
                                emptyList()
                            }
                        }
                        "CLUSTER" -> {
                            when (parts.getOrNull(1)?.uppercase()) {
                                "INFO" -> parseInfoOutput(cmd.clusterInfo())
                                "NODES" -> parseClusterNodes(cmd.clusterNodes())
                                else -> emptyList()
                            }
                        }
                        "DBSIZE" -> {
                            val size = cmd.dbsize()
                            listOf(
                                mapOf("db_keys" to JsonPrimitive(size))
                            )
                        }
                        "GET" -> {
                            val key = parts.getOrNull(1) ?: return emptyList()
                            val v = cmd.get(key)
                            listOf(
                                mapOf(
                                    "key" to JsonPrimitive(key),
                                    "value" to (v?.let { JsonPrimitive(it) } ?: JsonNull)
                                )
                            )
                        }
                        "HGETALL" -> {
                            val key = parts.getOrNull(1) ?: return emptyList()
                            val map = cmd.hgetall(key)
                            val hmap = map.entries.associate { (k, v) ->
                                k to JsonPrimitive(v)
                            } + ("_key" to JsonPrimitive(key))
                            listOf(hmap)
                        }
                        "LRANGE" -> {
                            val key = parts.getOrNull(1) ?: return emptyList()
                            val start = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                            val stop = parts.getOrNull(3)?.toLongOrNull() ?: -1L
                            cmd.lrange(key, start, stop).take(limit).mapIndexed { i, v ->
                                mapOf(
                                    "index" to JsonPrimitive(i),
                                    "key" to JsonPrimitive(key),
                                    "value" to JsonPrimitive(v)
                                )
                            }
                        }
                        "SMEMBERS" -> {
                            val key = parts.getOrNull(1) ?: return emptyList()
                            cmd.smembers(key).take(limit).map { v ->
                                mapOf("key" to JsonPrimitive(key), "value" to JsonPrimitive(v))
                            }
                        }
                        "SCAN" -> {
                            val matchPart = parts.drop(2).joinToString(" ")
                            val pattern = Regex("""MATCH\s+(\S+)""")
                                .find(matchPart)?.groupValues?.get(1) ?: "*"
                            scanKeys(cmd, pattern, limit).map { k ->
                                mapOf("key" to JsonPrimitive(k))
                            }
                        }
                        "KEYS" -> {
                            val pattern = parts.getOrNull(1) ?: "*"
                            scanKeys(cmd, pattern, limit).map { k ->
                                mapOf("key" to JsonPrimitive(k))
                            }
                        }
                        else -> {
                            // Generic: try as GET
                            val key = parts.firstOrNull() ?: return emptyList()
                            val v = cmd.get(key)
                            listOf(
                                mapOf(
                                    "key" to JsonPrimitive(key),
                                    "value" to (v?.let { JsonPrimitive(it) } ?: JsonNull)
                                )
                            )
                        }
                    }
                    result.take(limit)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Redis query failed" }
            emptyList()
        }
    }

    override suspend fun getSchema(
        host: String,
        port: Int?,
        databaseName: String?,
        credentials: DataSourceCredentials,
    ): List<DataSourceField> {
        val uri = buildRedisUri(host, port ?: 6379, credentials.password)
        val dbIndex = databaseName?.toIntOrNull() ?: 0
        return try {
            RedisClient.create(uri).use { client ->
                client.connect().use { conn ->
                    if (dbIndex != 0) conn.sync().select(dbIndex)
                    val keys = scanKeys(conn.sync(), "*", 100)
                    keys.map { DataSourceField(it, "key", "Redis key") }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Redis schema fetch failed" }
            emptyList()
        }
    }

    private fun buildRedisUri(host: String, port: Int, password: String?): String {
        val scheme = if (host.startsWith("rediss://")) "rediss://" else "redis://"
        val cleanHost = host.removePrefix("rediss://").removePrefix("redis://")
        val auth = if (!password.isNullOrBlank()) ":$password@" else ""
        return "$scheme$auth$cleanHost:$port"
    }

    /**
     * Scan Redis keys using the non-blocking SCAN command with cursor iteration.
     * Avoids [io.lettuce.core.api.sync.RedisKeyCommands.keys] which can block the server.
     */
    private fun scanKeys(
        cmd: io.lettuce.core.api.sync.RedisCommands<String, String>,
        pattern: String,
        maxKeys: Int,
    ): List<String> {
        val result = mutableListOf<String>()
        val args = ScanArgs.Builder.limit(maxKeys.toLong()).match(pattern)
        var cursor = ScanCursor.INITIAL
        do {
            val scanResult = cmd.scan(cursor, args)
            result.addAll(scanResult.keys)
            cursor = scanResult
        } while (!cursor.isFinished && result.size < maxKeys)
        return result.take(maxKeys)
    }

    companion object {
        /**
         * Parse Redis INFO output (key:value lines grouped by sections)
         * into a single flat map row.
         */
        internal fun parseInfoOutput(raw: String): List<Map<String, JsonElement>> {
            val map = mutableMapOf<String, JsonElement>()
            for (line in raw.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val colonIdx = trimmed.indexOf(':')
                if (colonIdx < 0) continue
                val key = trimmed.substring(0, colonIdx)
                val value = trimmed.substring(colonIdx + 1)
                // Try to parse as number for metrics
                val numVal = value.toLongOrNull() ?: value.toDoubleOrNull()
                map[key] = if (numVal != null) JsonPrimitive(numVal) else JsonPrimitive(value)
            }
            return if (map.isEmpty()) emptyList() else listOf(map)
        }

        /**
         * Parse INFO commandstats output into table rows.
         * Format: cmdstat_get:calls=100,usec=500,usec_per_call=5.00
         */
        internal fun parseCommandStats(raw: String): List<Map<String, JsonElement>> {
            return raw.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("cmdstat_") }
                .map { line ->
                    val colonIdx = line.indexOf(':')
                    val cmdName = line.substring("cmdstat_".length, colonIdx)
                    val fields = line.substring(colonIdx + 1).split(",").associate { kv ->
                        val eqIdx = kv.indexOf('=')
                        val k = kv.substring(0, eqIdx)
                        val v = kv.substring(eqIdx + 1)
                        val numVal = v.toLongOrNull() ?: v.toDoubleOrNull()
                        val label = k.replaceFirstChar { it.uppercase() }
                        if (numVal != null) {
                            label to JsonPrimitive(numVal)
                        } else {
                            label to JsonPrimitive(v)
                        }
                    }
                    mapOf("Command" to JsonPrimitive(cmdName)) + fields
                }
                .toList()
        }

        /**
         * Parse CLIENT LIST output into table rows.
         * Each client is a line of space-separated key=value pairs.
         */
        internal fun parseClientList(raw: String): List<Map<String, JsonElement>> {
            return raw.lines().filter { it.isNotBlank() }.map { line ->
                line.split(" ").associate { pair ->
                    val eqIdx = pair.indexOf('=')
                    if (eqIdx >= 0) {
                        pair.substring(0, eqIdx) to JsonPrimitive(pair.substring(eqIdx + 1))
                    } else {
                        pair to JsonPrimitive("")
                    }
                }
            }
        }

        /**
         * Parse SLOWLOG GET output. Lettuce returns List<Object> where each
         * entry contains [id, timestamp, duration_us, args].
         */
        @Suppress("UNCHECKED_CAST")
        internal fun parseSlowlog(entries: List<Any>): List<Map<String, JsonElement>> {
            return entries.mapNotNull { entry ->
                try {
                    val fields = entry as? List<*> ?: return@mapNotNull null
                    val id = (fields.getOrNull(0) as? Number)?.toLong() ?: 0L
                    val ts = (fields.getOrNull(1) as? Number)?.toLong() ?: 0L
                    val duration = (fields.getOrNull(2) as? Number)?.toLong() ?: 0L
                    val args = (fields.getOrNull(3) as? List<*>)
                        ?.joinToString(" ") ?: ""
                    mapOf(
                        "Id" to JsonPrimitive(id),
                        "Timestamp" to JsonPrimitive(ts),
                        "Duration" to JsonPrimitive(duration),
                        "Command" to JsonPrimitive(args)
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }

        /**
         * Parse CLUSTER NODES output into table rows.
         * Format: id ip:port@cport flags master ping pong epoch state slots...
         */
        internal fun parseClusterNodes(raw: String): List<Map<String, JsonElement>> {
            return raw.lines().filter { it.isNotBlank() }.map { line ->
                val parts = line.split(" ", limit = 9)
                mapOf(
                    "Id" to JsonPrimitive(parts.getOrElse(0) { "" }),
                    "Address" to JsonPrimitive(parts.getOrElse(1) { "" }),
                    "Flags" to JsonPrimitive(parts.getOrElse(2) { "" }),
                    "Master" to JsonPrimitive(parts.getOrElse(3) { "" }),
                    "Ping" to JsonPrimitive(parts.getOrElse(4) { "" }),
                    "Pong" to JsonPrimitive(parts.getOrElse(5) { "" }),
                    "Epoch" to JsonPrimitive(parts.getOrElse(6) { "" }),
                    "Link" to JsonPrimitive(parts.getOrElse(7) { "" }),
                    "Slot" to JsonPrimitive(parts.getOrElse(8) { "" })
                )
            }
        }
    }
}
