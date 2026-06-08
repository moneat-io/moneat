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

package com.moneat.datadog.networkdevices

import com.moneat.auth.requireCurrentOrg
import com.moneat.config.ClickHouseClient
import com.moneat.config.isClickHouseError
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200

fun Route.ndmQueryRoutes() {
    route("/v1/network-devices") {
        authenticate("auth-jwt") {
            get("") { handleListDevices() }
            get("/{deviceId}") { handleDeviceDetail() }
            get("/traps") { handleListTraps() }
            get("/flows") { handleListFlows() }
            get("/paths") { handleListPaths() }
        }
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleListDevices() {
    val orgId = call.requireCurrentOrg()?.orgId ?: return
    val limit = paramLimit()
    val offset = paramOffset()
    val db = ClickHouseClient.getDatabase()
    val where = ClickHouseQueryUtils.orgIdClause(orgId.toLong())

    val totalCount = executeCount(
        "SELECT count() as cnt FROM `$db`.ndm_devices WHERE $where FORMAT JSONEachRow"
    )

    val rows = executeRows(
        """SELECT device_id, ip_address, hostname, vendor, model,
            os_version, device_type, status, reachability, snmp_version,
            tags, formatDateTime(collected_at, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as ts
        FROM `$db`.ndm_devices WHERE $where
        ORDER BY collected_at DESC LIMIT $limit OFFSET $offset
        FORMAT JSONEachRow"""
    ) { obj ->
        buildJsonObject {
            put("deviceId", obj.s("device_id"))
            put("ipAddress", obj.s("ip_address"))
            put("hostname", obj.s("hostname"))
            put("vendor", obj.s("vendor"))
            put("model", obj.s("model"))
            put("osVersion", obj.s("os_version"))
            put("deviceType", obj.s("device_type"))
            put("status", obj.s("status"))
            put("reachability", obj.s("reachability"))
            put("snmpVersion", obj.s("snmp_version"))
            obj["tags"]?.let { put("tags", it) }
            put("collectedAt", obj.s("ts"))
        }
    }

    call.respond(
        buildJsonObject {
            putJsonArray("devices") { rows.forEach { add(it) } }
            put("totalCount", totalCount)
        }
    )
}
private suspend fun io.ktor.server.routing.RoutingContext.handleDeviceDetail() {
    val orgId = call.requireCurrentOrg()?.orgId ?: return
    val deviceId = call.parameters["deviceId"] ?: return call.respond(
        HttpStatusCode.BadRequest, mapOf("error" to "Missing deviceId")
    )
    val db = ClickHouseClient.getDatabase()
    val where = "${ClickHouseQueryUtils.orgIdClause(orgId.toLong())} " +
        "AND device_id = '${escapeSql(deviceId)}'"

    val rows = executeRows(
        """SELECT device_id, ip_address, hostname, vendor, model,
            os_version, device_type, status, reachability, snmp_version,
            tags, formatDateTime(collected_at, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as ts
        FROM `$db`.ndm_devices WHERE $where
        ORDER BY collected_at DESC LIMIT 1
        FORMAT JSONEachRow"""
    ) { obj ->
        buildJsonObject {
            put("deviceId", obj.s("device_id"))
            put("ipAddress", obj.s("ip_address"))
            put("hostname", obj.s("hostname"))
            put("vendor", obj.s("vendor"))
            put("model", obj.s("model"))
            put("osVersion", obj.s("os_version"))
            put("deviceType", obj.s("device_type"))
            put("status", obj.s("status"))
            put("reachability", obj.s("reachability"))
            put("snmpVersion", obj.s("snmp_version"))
            obj["tags"]?.let { put("tags", it) }
            put("collectedAt", obj.s("ts"))
        }
    }

    if (rows.isEmpty()) {
        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Device not found"))
    } else {
        call.respond(rows.first())
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleListTraps() {
    val orgId = call.requireCurrentOrg()?.orgId ?: return
    val limit = paramLimit()
    val offset = paramOffset()
    val db = ClickHouseClient.getDatabase()
    val where = ClickHouseQueryUtils.orgIdClause(orgId.toLong())

    val totalCount = executeCount(
        "SELECT count() as cnt FROM `$db`.ndm_traps WHERE $where FORMAT JSONEachRow"
    )

    val rows = executeRows(
        """SELECT trap_id, device_ip, oid, severity, message, variables,
            formatDateTime(received_at, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as ts
        FROM `$db`.ndm_traps WHERE $where
        ORDER BY received_at DESC LIMIT $limit OFFSET $offset
        FORMAT JSONEachRow"""
    ) { obj ->
        buildJsonObject {
            put("trapId", obj.s("trap_id"))
            put("deviceIp", obj.s("device_ip"))
            put("oid", obj.s("oid"))
            put("severity", obj.s("severity"))
            put("message", obj.s("message"))
            obj["variables"]?.let { put("variables", it) }
            put("receivedAt", obj.s("ts"))
        }
    }

    call.respond(
        buildJsonObject {
            putJsonArray("traps") { rows.forEach { add(it) } }
            put("totalCount", totalCount)
        }
    )
}
private suspend fun io.ktor.server.routing.RoutingContext.handleListFlows() {
    val orgId = call.requireCurrentOrg()?.orgId ?: return
    val limit = paramLimit()
    val offset = paramOffset()
    val db = ClickHouseClient.getDatabase()
    val where = ClickHouseQueryUtils.orgIdClause(orgId.toLong())

    val totalCount = executeCount(
        "SELECT count() as cnt FROM `$db`.ndm_flows WHERE $where FORMAT JSONEachRow"
    )

    val rows = executeRows(
        """SELECT flow_id, src_ip, dst_ip, src_port, dst_port,
            protocol, bytes, packets, direction, flow_type,
            tags, formatDateTime(sampled_at, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as ts
        FROM `$db`.ndm_flows WHERE $where
        ORDER BY sampled_at DESC LIMIT $limit OFFSET $offset
        FORMAT JSONEachRow"""
    ) { obj ->
        buildJsonObject {
            put("flowId", obj.s("flow_id"))
            put("srcIp", obj.s("src_ip"))
            put("dstIp", obj.s("dst_ip"))
            obj["src_port"]?.let { put("srcPort", it) }
            obj["dst_port"]?.let { put("dstPort", it) }
            put("protocol", obj.s("protocol"))
            obj["bytes"]?.let { put("bytes", it) }
            obj["packets"]?.let { put("packets", it) }
            put("direction", obj.s("direction"))
            put("flowType", obj.s("flow_type"))
            obj["tags"]?.let { put("tags", it) }
            put("sampledAt", obj.s("ts"))
        }
    }

    call.respond(
        buildJsonObject {
            putJsonArray("flows") { rows.forEach { add(it) } }
            put("totalCount", totalCount)
        }
    )
}
private suspend fun io.ktor.server.routing.RoutingContext.handleListPaths() {
    val orgId = call.requireCurrentOrg()?.orgId ?: return
    val limit = paramLimit()
    val offset = paramOffset()
    val db = ClickHouseClient.getDatabase()
    val where = ClickHouseQueryUtils.orgIdClause(orgId.toLong())

    val totalCount = executeCount(
        "SELECT count() as cnt FROM `$db`.network_paths WHERE $where FORMAT JSONEachRow"
    )

    val rows = executeRows(
        """SELECT path_id, source, destination, hops, hop_rtts,
            tags, formatDateTime(collected_at, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as ts
        FROM `$db`.network_paths WHERE $where
        ORDER BY collected_at DESC LIMIT $limit OFFSET $offset
        FORMAT JSONEachRow"""
    ) { obj ->
        buildJsonObject {
            put("pathId", obj.s("path_id"))
            put("source", obj.s("source"))
            put("destination", obj.s("destination"))
            obj["hops"]?.let { put("hops", it) }
            obj["hop_rtts"]?.let { put("hopRtts", it) }
            obj["tags"]?.let { put("tags", it) }
            put("collectedAt", obj.s("ts"))
        }
    }

    call.respond(
        buildJsonObject {
            putJsonArray("paths") { rows.forEach { add(it) } }
            put("totalCount", totalCount)
        }
    )
}

private fun io.ktor.server.routing.RoutingContext.paramLimit(): Int =
    (call.parameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT)
        .coerceAtMost(MAX_LIMIT)

private fun io.ktor.server.routing.RoutingContext.paramOffset(): Int =
    call.parameters["offset"]?.toIntOrNull() ?: 0

private suspend fun executeCount(sql: String): Long {
    val resp = ClickHouseClient.execute(sql)
    val body = resp.bodyAsText()
    if (resp.isClickHouseError(body)) return 0L
    return body.trim().lines().firstOrNull()?.let {
        json.parseToJsonElement(it).jsonObject["cnt"]
            ?.jsonPrimitive?.content?.toLongOrNull()
    } ?: 0L
}

private suspend fun executeRows(
    sql: String,
    mapper: (JsonObject) -> JsonObject
): List<JsonObject> {
    val resp = ClickHouseClient.execute(sql)
    val body = resp.bodyAsText()
    if (resp.isClickHouseError(body)) return emptyList()
    return body.trim().lines().filter { it.isNotBlank() }.map { line ->
        mapper(json.parseToJsonElement(line).jsonObject)
    }
}

private fun JsonObject.s(key: String): String {
    val el = this[key] ?: return ""
    return if (el is kotlinx.serialization.json.JsonPrimitive) {
        el.content
    } else {
        el.toString()
    }
}
