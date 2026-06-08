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

package com.moneat.datadog.routes

import com.moneat.auth.currentOrgIdOrNull
import com.moneat.config.ClickHouseClient
import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.datadog.decompression.DecompressionService
import com.moneat.datadog.models.DatadogHostMetadata
import com.moneat.datadog.models.DatadogIntakePayload
import com.moneat.datadog.services.DatadogHostService
import com.moneat.utils.ClickHouseQueryUtils
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mu.KotlinLogging
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}

private const val PERCENTAGE_SCALE = 100.0

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

fun Route.datadogHostIngestRoutes() {
    // DD agent intake endpoints
    route("/dd") {
        route("/api/v1") {
            post("/metadata") {
                val orgId = DatadogAuthMiddleware.authenticate(call)
                    ?: return@post

                val contentEncoding =
                    call.request.headers["Content-Encoding"]
                val rawBody = call.receive<ByteArray>()
                val body = DecompressionService.decompress(
                    rawBody,
                    contentEncoding
                )
                val bodyStr = body.decodeToString()

                val metadata = suspendRunCatching {
                    json.decodeFromString<DatadogHostMetadata>(
                        bodyStr
                    )
                }.getOrElse { e ->
                    logger.warn(e) {
                        "Failed to parse DD host metadata"
                    }
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "errors" to listOf("Invalid payload")
                        )
                    )
                }

                DatadogHostService.upsertFromMetadata(
                    organizationId = orgId,
                    metadata = metadata
                )

                logger.debug {
                    "Accepted DD host metadata for " +
                        "${metadata.hostname}, " +
                        "org $orgId"
                }

                call.respond(
                    HttpStatusCode.Accepted,
                    mapOf("status" to "ok")
                )
            }
        }

        route("/api/v2") {
            post("/host_metadata") {
                val orgId = DatadogAuthMiddleware.authenticate(call)
                    ?: return@post

                val contentEncoding =
                    call.request.headers["Content-Encoding"]
                val rawBody = call.receive<ByteArray>()
                val body = DecompressionService.decompress(
                    rawBody,
                    contentEncoding
                )
                val bodyStr = body.decodeToString()

                val metadata = suspendRunCatching {
                    json.decodeFromString<DatadogHostMetadata>(
                        bodyStr
                    )
                }.getOrElse { e ->
                    logger.warn(e) {
                        "Failed to parse DD V2 host metadata"
                    }
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "errors" to listOf("Invalid payload")
                        )
                    )
                }

                DatadogHostService.upsertFromMetadata(
                    organizationId = orgId,
                    metadata = metadata
                )

                call.respond(
                    HttpStatusCode.Accepted,
                    mapOf("status" to "ok")
                )
            }
        }

        post("/intake/") {
            val orgId = DatadogAuthMiddleware.authenticate(call)
                ?: return@post

            val contentEncoding =
                call.request.headers["Content-Encoding"]
            val rawBody = call.receive<ByteArray>()
            val body = DecompressionService.decompress(
                rawBody,
                contentEncoding
            )
            val bodyStr = body.decodeToString()

            val payload = suspendRunCatching {
                json.decodeFromString<DatadogIntakePayload>(
                    bodyStr
                )
            }.getOrElse { e ->
                logger.warn(e) {
                    "Failed to parse DD intake payload"
                }
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf(
                        "errors" to listOf("Invalid payload")
                    )
                )
            }

            DatadogHostService.upsertFromIntake(
                organizationId = orgId,
                payload = payload
            )

            call.respond(
                HttpStatusCode.Accepted,
                mapOf("status" to "ok")
            )
        }
    }
}

fun Route.datadogHostQueryRoutes() {
    // Dashboard query endpoints (JWT-authenticated)
    route("/v1") {
        authenticate("auth-jwt") {
            get("/hosts") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal?.currentOrgIdOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "Missing org context")
                    )

                val hosts = DatadogHostService.listHosts(orgId)
                call.respond(
                    buildJsonObject {
                        putJsonArray("hosts") {
                            hosts.forEach { h -> add(hostToJson(h)) }
                        }
                        put("totalCount", hosts.size)
                    }
                )
            }

            get("/hosts/{hostId}") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal?.currentOrgIdOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "Missing org context")
                    )
                val hostId = call.parameters["hostId"]?.toIntOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid host id")
                    )

                val host = DatadogHostService.getHost(orgId, hostId)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Host not found")
                    )
                call.respond(hostToJson(host))
            }

            delete("/hosts/{hostId}") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal?.currentOrgIdOrNull()
                if (orgId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing org context"))
                    return@delete
                }
                val hostId = call.parameters["hostId"]?.toIntOrNull()
                if (hostId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid host id"))
                    return@delete
                }

                val deleted = DatadogHostService.deleteHost(orgId, hostId)
                if (deleted) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Host not found"))
                }
            }

            get("/hosts/{hostId}/metrics") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal?.currentOrgIdOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "Missing org context")
                    )
                val hostId = call.parameters["hostId"]?.toIntOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid host id")
                    )
                val host = DatadogHostService.getHost(orgId, hostId)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Host not found")
                    )
                val from = call.parameters["from"]
                val to = call.parameters["to"]

                val db = ClickHouseClient.getDatabase()
                val timeClause = buildString {
                    if (!from.isNullOrBlank()) append(" AND timestamp >= toDateTime('${escapeSqlHost(from)}')")
                    if (!to.isNullOrBlank()) append(" AND timestamp <= toDateTime('${escapeSqlHost(to)}')")
                }
                // Bucket by 5-minute intervals and pivot known system metrics
                // Use max() for disk to get the highest-utilized partition
                // (avg across partitions dilutes the value)
                val sql = """
                    SELECT
                        toUnixTimestamp(toStartOfInterval(timestamp, INTERVAL 5 MINUTE)) as ts,
                        metric_name,
                        if(metric_name = 'system.disk.in_use', max(value), avg(value)) as value
                    FROM `$db`.metrics
                    WHERE ${ClickHouseQueryUtils.orgIdClause(orgId.toLong())}
                      AND host = '${escapeSqlHost(host.hostname)}'
                      AND metric_name IN (
                        'system.cpu.user', 'system.cpu.system', 'system.cpu.idle',
                        'system.mem.pct_usable', 'system.mem.used', 'system.mem.total',
                        'system.disk.in_use',
                        'system.net.bytes_rcvd', 'system.net.bytes_sent',
                        'system.load.1', 'system.load.5', 'system.load.15'
                      )
                      AND NOT mapContains(tags, 'system_id')
                      $timeClause
                    GROUP BY ts, metric_name
                    ORDER BY ts ASC
                    FORMAT JSONEachRow
                """.trimIndent()

                val response = ClickHouseClient.execute(sql)
                // Pivot: group rows by timestamp bucket → field map
                val buckets = linkedMapOf<Long, MutableMap<String, Double>>()
                if (response.status.isSuccess()) {
                    response.bodyAsText().lines().filter { it.isNotBlank() }.forEach { line ->
                        val obj = json.parseToJsonElement(line).jsonObject
                        val ts = obj["ts"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@forEach
                        val name = obj["metric_name"]?.jsonPrimitive?.content ?: return@forEach
                        val value = obj["value"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@forEach
                        buckets.getOrPut(ts) { mutableMapOf() }[name] = value
                    }
                }
                val dataPoints = buckets.map { (ts, m) ->
                    buildJsonObject {
                        put("timestamp", ts)
                        // cpu: sum user+system, capped at 100
                        val cpuUser = m["system.cpu.user"] ?: 0.0
                        val cpuSys = m["system.cpu.system"] ?: 0.0
                        put("cpu_percent", minOf(cpuUser + cpuSys, PERCENTAGE_SCALE))
                        // mem: prefer pct_usable → derive used%, else skip
                        val pctUsable = m["system.mem.pct_usable"]
                        if (pctUsable != null) {
                            put("mem_percent", (1.0 - pctUsable) * PERCENTAGE_SCALE)
                        } else {
                            val used = m["system.mem.used"]
                            val total = m["system.mem.total"]
                            if (used != null && total != null && total > 0) {
                                put("mem_percent", (used / total) * PERCENTAGE_SCALE)
                            }
                        }
                        m["system.disk.in_use"]?.let { put("disk_percent", it * PERCENTAGE_SCALE) }
                        m["system.net.bytes_rcvd"]?.let { put("net_recv_bytes", it) }
                        m["system.net.bytes_sent"]?.let { put("net_sent_bytes", it) }
                        m["system.load.1"]?.let { put("load_1", it) }
                        m["system.load.5"]?.let { put("load_5", it) }
                        m["system.load.15"]?.let { put("load_15", it) }
                    }
                }

                call.respond(
                    buildJsonObject {
                        putJsonArray("data_points") { dataPoints.forEach { add(it) } }
                    }
                )
            }

            get("/hosts/{hostId}/containers") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal?.currentOrgIdOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "Missing org context")
                    )
                val hostId = call.parameters["hostId"]?.toIntOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid host id")
                    )
                DatadogHostService.getHost(orgId, hostId)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Host not found")
                    )

                val db = ClickHouseClient.getDatabase()
                // Only show containers seen in the last 10 minutes — the DD agent reports every
                // ~10s, so anything older is no longer running. The agent only reports running
                // containers (stopped ones simply stop appearing).
                val sql = """
                    SELECT
                        argMax(host, timestamp) as host,
                        container_id,
                        argMax(name, timestamp) as name,
                        argMax(image, timestamp) as image,
                        argMax(state, timestamp) as state,
                        argMax(cpu_percent, timestamp) as cpu_percent,
                        argMax(mem_usage, timestamp) as mem_usage,
                        argMax(mem_limit, timestamp) as mem_limit,
                        argMax(net_rx_bytes, timestamp) as net_rx_bytes,
                        argMax(net_tx_bytes, timestamp) as net_tx_bytes,
                        argMax(tags, timestamp) as tags,
                        formatDateTime(max(timestamp), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as ts
                    FROM `$db`.containers_latest_by_host
                    WHERE ${ClickHouseQueryUtils.orgIdClause(orgId.toLong())}
                      AND host_id = $hostId
                      AND timestamp >= now64(3) - INTERVAL 10 MINUTE
                    GROUP BY container_id
                    ORDER BY max(timestamp) DESC
                    LIMIT 100
                    FORMAT JSONEachRow
                """.trimIndent()

                val response = ClickHouseClient.execute(sql)
                val containers = if (response.status.isSuccess()) {
                    response.bodyAsText().lines().filter { it.isNotBlank() }.map { line ->
                        val obj = json.parseToJsonElement(line).jsonObject
                        val containerId = obj["container_id"]?.jsonPrimitive?.content ?: ""
                        // Tags come back as a JSON object from ClickHouse Map type
                        val tagsObj = obj["tags"]?.let {
                            runCatching { it.jsonObject }.getOrNull()
                        }
                        val rawName = obj["name"]?.jsonPrimitive?.content ?: ""
                        val rawImage = obj["image"]?.jsonPrimitive?.content ?: ""
                        // Fall back to tags for name/image if proto fields are empty
                        val image = rawImage.ifBlank {
                            tagsObj?.get("docker_image")?.jsonPrimitive?.content ?: ""
                        }
                        val displayName = rawName.ifBlank {
                            tagsObj?.get("docker_container_name")?.jsonPrimitive?.content
                                ?: tagsObj?.get("container_name")?.jsonPrimitive?.content
                                ?: image.substringAfterLast('/').substringBefore(':')
                        }
                        buildJsonObject {
                            put("id", containerId)
                            put("host", obj["host"]?.jsonPrimitive?.content ?: "")
                            put("containerId", containerId)
                            put("name", displayName)
                            put("image", image)
                            put("state", obj["state"]?.jsonPrimitive?.content ?: "")
                            obj["cpu_percent"]?.let { put("cpuPercent", it) }
                            obj["mem_usage"]?.let { put("memUsage", it) }
                            obj["mem_limit"]?.let { put("memLimit", it) }
                            obj["net_rx_bytes"]?.let { put("netRxBytes", it) }
                            obj["net_tx_bytes"]?.let { put("netTxBytes", it) }
                            tagsObj?.let { put("tags", it) }
                            put("timestamp", obj["ts"]?.jsonPrimitive?.content ?: "")
                        }
                    }
                } else {
                    emptyList()
                }

                call.respond(
                    buildJsonObject {
                        putJsonArray("containers") { containers.forEach { add(it) } }
                        put("totalCount", containers.size)
                    }
                )
            }
        }
    }
}

private fun hostToJson(h: com.moneat.datadog.services.DdHostInfo) = buildJsonObject {
    put("id", h.id)
    put("hostname", h.hostname)
    put("os", h.os)
    put("platform", h.platform)
    put("processor", h.processor)
    put("cpuCores", h.cpuCores)
    put("memoryTotalKb", h.memoryTotalKb)
    put("agentVersion", h.agentVersion)
    putJsonObject("tags") { h.tags.forEach { (k, v) -> put(k, v) } }
    put("firstSeenAt", h.firstSeenAt)
    put("lastSeenAt", h.lastSeenAt)
    put("isOnline", h.isOnline)
}

private fun escapeSqlHost(value: String) = value.replace("'", "''")
