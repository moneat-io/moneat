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
import com.moneat.ingest.DecompressionService
import com.moneat.datadog.models.DatadogHostMetadata
import com.moneat.datadog.models.DatadogIntakePayload
import com.moneat.datadog.services.DatadogHostService
import com.moneat.datadog.services.DdHostInfo
import com.moneat.shared.services.toUuidOrNull
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.suspendRunCatching
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationCall
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mu.KotlinLogging
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

private const val PERCENTAGE_SCALE = 100.0
private const val HOST_METRIC_INTERVAL_MINUTES = 5
private const val MEM_AVAILABLE_METRIC = "system.mem.available"
private const val MEM_USED_METRIC = "system.mem.used"
private const val MEM_TOTAL_METRIC = "system.mem.total"
private const val DISK_PERCENT_METRIC = "system.disk.percent"
private const val NET_RECV_BYTES_METRIC = "system.net.recv_bytes"
private const val NET_SENT_BYTES_METRIC = "system.net.sent_bytes"
private const val LOAD_1_METRIC = "system.load.1"
private const val LOAD_5_METRIC = "system.load.5"
private const val LOAD_15_METRIC = "system.load.15"

private val hostMetricNames =
    listOf(
        "system.cpu.user",
        "system.cpu.system",
        "system.cpu.idle",
        MEM_AVAILABLE_METRIC,
        MEM_USED_METRIC,
        MEM_TOTAL_METRIC,
        DISK_PERCENT_METRIC,
        NET_RECV_BYTES_METRIC,
        NET_SENT_BYTES_METRIC,
        LOAD_1_METRIC,
        LOAD_5_METRIC,
        LOAD_15_METRIC,
    )

private data class ContainerDisplayFields(
    val id: String,
    val image: String,
    val name: String,
    val tags: JsonObject?
)

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

private fun parseHostResourceId(raw: String?): Uuid? =
    raw?.toUuidOrNull()

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
            get("/hosts") { call.respondHostsList() }
            get("/hosts/{hostId}") { call.respondHostDetail() }
            delete("/hosts/{hostId}") { call.respondHostDelete() }
            get("/hosts/{hostId}/metrics") { call.respondHostMetrics() }
            get("/hosts/{hostId}/containers") { call.respondHostContainers() }
        }
    }
}

private suspend fun ApplicationCall.respondHostsList() {
    val orgId = authenticatedOrgId() ?: return
    val hosts = DatadogHostService.listHosts(orgId)
    respond(
        buildJsonObject {
            putJsonArray("hosts") { hosts.forEach { add(hostToJson(it)) } }
            put("totalCount", hosts.size)
        }
    )
}

private suspend fun ApplicationCall.respondHostDetail() {
    val orgId = authenticatedOrgId() ?: return
    val hostId = parsedHostResourceId() ?: return
    val host = requestedHost(orgId, hostId) ?: return
    respond(hostToJson(host))
}

private suspend fun ApplicationCall.respondHostDelete() {
    val orgId = authenticatedOrgId() ?: return
    val hostId = parsedHostResourceId() ?: return

    if (DatadogHostService.deleteHost(orgId, hostId)) {
        respond(HttpStatusCode.NoContent)
    } else {
        respond(HttpStatusCode.NotFound, mapOf("error" to "Host not found"))
    }
}

private suspend fun ApplicationCall.respondHostMetrics() {
    val orgId = authenticatedOrgId() ?: return
    val hostId = parsedHostResourceId() ?: return
    val host = requestedHost(orgId, hostId) ?: return
    val buckets = loadHostMetricBuckets(
        organizationId = orgId,
        hostInternalId = host.internalId,
        from = parameters["from"],
        to = parameters["to"],
    )

    respond(
        buildJsonObject {
            putJsonArray("data_points") {
                buckets.forEach { (timestamp, metrics) ->
                    add(hostMetricDataPointJson(timestamp, metrics))
                }
            }
        }
    )
}

private suspend fun ApplicationCall.respondHostContainers() {
    val orgId = authenticatedOrgId() ?: return
    val hostId = parsedHostResourceId() ?: return
    val host = requestedHost(orgId, hostId) ?: return
    val containers = loadHostContainers(orgId, host.internalId)

    respond(
        buildJsonObject {
            putJsonArray("containers") { containers.forEach { add(it) } }
            put("totalCount", containers.size)
        }
    )
}

private suspend fun ApplicationCall.authenticatedOrgId(): Int? {
    val orgId = principal<JWTPrincipal>()?.currentOrgIdOrNull()
    if (orgId == null) {
        respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing org context"))
    }
    return orgId
}

private suspend fun ApplicationCall.parsedHostResourceId(): Uuid? {
    val hostId = parseHostResourceId(parameters["hostId"])
    if (hostId == null) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid host id"))
    }
    return hostId
}

private suspend fun ApplicationCall.requestedHost(orgId: Int, hostId: Uuid): DdHostInfo? {
    val host = DatadogHostService.getHost(orgId, hostId)
    if (host == null) {
        respond(HttpStatusCode.NotFound, mapOf("error" to "Host not found"))
    }
    return host
}

private suspend fun loadHostMetricBuckets(
    organizationId: Int,
    hostInternalId: Int,
    from: String?,
    to: String?
): Map<Long, Map<String, Double>> {
    val response = ClickHouseClient.execute(hostMetricsSql(organizationId, hostInternalId, from, to))
    if (!response.status.isSuccess()) {
        return emptyMap()
    }
    return parseHostMetricBuckets(response.bodyAsText())
}

private fun hostMetricsSql(
    organizationId: Int,
    hostInternalId: Int,
    from: String?,
    to: String?
): String {
    val db = ClickHouseClient.getDatabase()
    val timeClause = hostMetricsTimeClause(from, to)
    return """
        SELECT
            toUnixTimestamp(toStartOfInterval(bucket_start, INTERVAL $HOST_METRIC_INTERVAL_MINUTES MINUTE)) as ts,
            metric_name,
            sum(value_sum) / nullIf(sum(value_count), 0) as value
        FROM `$db`.metrics_rollup_1m
        WHERE ${ClickHouseQueryUtils.orgIdClause(organizationId.toLong())}
          AND host_id = $hostInternalId
          AND metric_name IN (
${hostMetricNamesSql()}
          )
          $timeClause
        GROUP BY ts, metric_name
        ORDER BY ts ASC
        FORMAT JSONEachRow
    """.trimIndent()
}

private fun hostMetricsTimeClause(from: String?, to: String?): String =
    buildString {
        if (!from.isNullOrBlank()) {
            append(" AND bucket_start >= toDateTime('${escapeSqlHost(from)}')")
        }
        if (!to.isNullOrBlank()) {
            append(" AND bucket_start <= toDateTime('${escapeSqlHost(to)}')")
        }
    }

private fun hostMetricNamesSql(): String =
    hostMetricNames.joinToString(",\n") { metricName ->
        "            '$metricName'"
    }

private fun parseHostMetricBuckets(body: String): Map<Long, Map<String, Double>> {
    val buckets = linkedMapOf<Long, MutableMap<String, Double>>()
    body.lineSequence()
        .filter { it.isNotBlank() }
        .forEach { line ->
            val obj = json.parseToJsonElement(line).jsonObject
            val timestamp = obj["ts"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@forEach
            val metricName = obj["metric_name"]?.jsonPrimitive?.content ?: return@forEach
            val value = obj["value"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@forEach
            buckets.getOrPut(timestamp) { mutableMapOf() }[metricName] = value
        }
    return buckets
}

private fun hostMetricDataPointJson(timestamp: Long, metrics: Map<String, Double>): JsonObject =
    buildJsonObject {
        put("timestamp", timestamp)
        put("cpu_percent", hostCpuPercent(metrics))
        hostMemoryPercent(metrics)?.let { put("mem_percent", it) }
        metrics[DISK_PERCENT_METRIC]?.let { put("disk_percent", it) }
        metrics[NET_RECV_BYTES_METRIC]?.let { put("net_recv_bytes", it) }
        metrics[NET_SENT_BYTES_METRIC]?.let { put("net_sent_bytes", it) }
        metrics[LOAD_1_METRIC]?.let { put("load_1", it) }
        metrics[LOAD_5_METRIC]?.let { put("load_5", it) }
        metrics[LOAD_15_METRIC]?.let { put("load_15", it) }
    }

private fun hostCpuPercent(metrics: Map<String, Double>): Double =
    minOf(
        (metrics["system.cpu.user"] ?: 0.0) + (metrics["system.cpu.system"] ?: 0.0),
        PERCENTAGE_SCALE,
    )

private fun hostMemoryPercent(metrics: Map<String, Double>): Double? {
    val total = metrics[MEM_TOTAL_METRIC]?.takeIf { it > 0 } ?: return null
    return metrics[MEM_AVAILABLE_METRIC]?.let { available ->
        (1.0 - (available / total)) * PERCENTAGE_SCALE
    } ?: metrics[MEM_USED_METRIC]?.let { used ->
        (used / total) * PERCENTAGE_SCALE
    }
}

private suspend fun loadHostContainers(organizationId: Int, hostInternalId: Int): List<JsonObject> {
    val response = ClickHouseClient.execute(hostContainersSql(organizationId, hostInternalId))
    if (!response.status.isSuccess()) {
        return emptyList()
    }
    return response.bodyAsText()
        .lineSequence()
        .filter { it.isNotBlank() }
        .map { containerJson(json.parseToJsonElement(it).jsonObject) }
        .toList()
}

private fun hostContainersSql(organizationId: Int, hostInternalId: Int): String {
    val db = ClickHouseClient.getDatabase()
    return """
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
        WHERE ${ClickHouseQueryUtils.orgIdClause(organizationId.toLong())}
          AND host_id = $hostInternalId
          AND timestamp >= now64(3) - INTERVAL 10 MINUTE
        GROUP BY container_id
        ORDER BY max(timestamp) DESC
        LIMIT 100
        FORMAT JSONEachRow
    """.trimIndent()
}

private fun containerJson(obj: JsonObject): JsonObject {
    val display = containerDisplayFields(obj)
    return buildJsonObject {
        put("id", display.id)
        put("host", obj.stringValue("host"))
        put("containerId", display.id)
        put("name", display.name)
        put("image", display.image)
        put("state", obj.stringValue("state"))
        putSourceValue("cpuPercent", obj, "cpu_percent")
        putSourceValue("memUsage", obj, "mem_usage")
        putSourceValue("memLimit", obj, "mem_limit")
        putSourceValue("netRxBytes", obj, "net_rx_bytes")
        putSourceValue("netTxBytes", obj, "net_tx_bytes")
        display.tags?.let { put("tags", it) }
        put("timestamp", obj.stringValue("ts"))
    }
}

private fun containerDisplayFields(obj: JsonObject): ContainerDisplayFields {
    val tags = obj.tagsObject()
    val image = containerImage(obj.stringValue("image"), tags)
    return ContainerDisplayFields(
        id = obj.stringValue("container_id"),
        image = image,
        name = containerDisplayName(obj.stringValue("name"), tags, image),
        tags = tags,
    )
}

private fun containerImage(rawImage: String, tags: JsonObject?): String =
    rawImage.ifBlank { tags.stringValueOrBlank("docker_image") }

private fun containerDisplayName(rawName: String, tags: JsonObject?, image: String): String {
    if (rawName.isNotBlank()) {
        return rawName
    }
    val dockerName = tags.stringValueOrBlank("docker_container_name")
    if (dockerName.isNotBlank()) {
        return dockerName
    }
    val containerName = tags.stringValueOrBlank("container_name")
    return containerName.ifBlank { image.substringAfterLast('/').substringBefore(':') }
}

private fun JsonObject.tagsObject(): JsonObject? =
    this["tags"]?.let { runCatching { it.jsonObject }.getOrNull() }

private fun JsonObject.stringValue(key: String): String =
    this[key]?.jsonPrimitive?.content ?: ""

private fun JsonObject?.stringValueOrBlank(key: String): String =
    this?.get(key)?.jsonPrimitive?.content ?: ""

private fun JsonObjectBuilder.putSourceValue(targetKey: String, source: JsonObject, sourceKey: String) {
    source[sourceKey]?.let { put(targetKey, it) }
}

private fun hostToJson(h: DdHostInfo) = buildJsonObject {
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
