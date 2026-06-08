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

import com.moneat.config.ClickHouseClient
import com.moneat.config.isClickHouseError
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
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
private const val LOG_BODY_MAX_LEN = 200

fun Route.datadogInfraQueryRoutes() {
    route("/v1/infra") {
        authenticate("auth-jwt") {
            get("/processes") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal?.payload
                    ?.getClaim("orgId")?.asInt()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "Missing org context")
                    )

                val limit = (
                    call.parameters["limit"]
                        ?.toIntOrNull() ?: DEFAULT_LIMIT
                    )
                    .coerceAtMost(MAX_LIMIT)
                val offset = call.parameters["offset"]
                    ?.toIntOrNull() ?: 0
                val host = call.parameters["host"]

                val db = ClickHouseClient.getDatabase()
                val conditions = mutableListOf(
                    ClickHouseQueryUtils.orgIdClause(orgId.toLong())
                )
                if (!host.isNullOrBlank()) {
                    conditions.add(
                        "host LIKE '%${escapeSql(host)}%'"
                    )
                }
                val where = conditions.joinToString(" AND ")

                val countSql = """
                    SELECT count() as cnt
                    FROM `$db`.processes
                    WHERE $where
                    FORMAT JSONEachRow
                """.trimIndent()
                val totalCount = executeCount(countSql)

                val dataSql = """
                    SELECT
                        process_id, host, pid, name,
                        command, user, cpu_percent,
                        mem_rss, mem_vms, state,
                        thread_count, open_fd_count,
                        tags,
                        formatDateTime(
                            timestamp,
                            '%Y-%m-%dT%H:%i:%S.000Z',
                            'UTC'
                        ) as ts
                    FROM `$db`.processes
                    WHERE $where
                    ORDER BY timestamp DESC
                    LIMIT $limit OFFSET $offset
                    FORMAT JSONEachRow
                """.trimIndent()

                val processes = executeRows(dataSql) { obj ->
                    buildJsonObject {
                        put("processId", obj.s("process_id"))
                        put("host", obj.s("host"))
                        obj["pid"]?.let { put("pid", it) }
                        put("name", obj.s("name"))
                        put("command", obj.s("command"))
                        put("user", obj.s("user"))
                        obj["cpu_percent"]?.let { put("cpuPercent", it) }
                        obj["mem_rss"]?.let { put("memRss", it) }
                        obj["mem_vms"]?.let { put("memVms", it) }
                        put("state", obj.s("state"))
                        obj["thread_count"]?.let { put("threadCount", it) }
                        obj["open_fd_count"]?.let { put("openFdCount", it) }
                        obj["tags"]?.let { put("tags", it) }
                        put("timestamp", obj.s("ts"))
                    }
                }

                call.respond(
                    buildJsonObject {
                        putJsonArray("processes") { processes.forEach { add(it) } }
                        put("totalCount", totalCount)
                    }
                )
            }

            get("/containers") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal?.payload
                    ?.getClaim("orgId")?.asInt()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "Missing org context")
                    )

                val limit = (
                    call.parameters["limit"]
                        ?.toIntOrNull() ?: DEFAULT_LIMIT
                    )
                    .coerceAtMost(MAX_LIMIT)
                val offset = call.parameters["offset"]
                    ?.toIntOrNull() ?: 0
                val host = call.parameters["host"]

                val db = ClickHouseClient.getDatabase()
                val conditions = mutableListOf(
                    ClickHouseQueryUtils.orgIdClause(orgId.toLong())
                )
                if (!host.isNullOrBlank()) {
                    conditions.add(
                        "host LIKE '%${escapeSql(host)}%'"
                    )
                }
                val where = conditions.joinToString(" AND ")

                val countSql = """
                    SELECT count() as cnt
                    FROM (
                        SELECT 1
                        FROM `$db`.containers_latest_by_host
                        WHERE $where
                        GROUP BY organization_id, host_id, host, container_id
                    )
                    FORMAT JSONEachRow
                """.trimIndent()
                val totalCount = executeCount(countSql)

                val dataSql = """
                    SELECT
                        toString(cityHash64(host, container_id)) as container_id_hash,
                        host,
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
                        formatDateTime(
                            max(timestamp),
                            '%Y-%m-%dT%H:%i:%S.000Z',
                            'UTC'
                        ) as ts
                    FROM `$db`.containers_latest_by_host
                    WHERE $where
                    GROUP BY organization_id, host_id, host, container_id
                    ORDER BY max(timestamp) DESC
                    LIMIT $limit OFFSET $offset
                    FORMAT JSONEachRow
                """.trimIndent()

                val containers =
                    executeRows(dataSql) { obj ->
                        buildJsonObject {
                            put("id", obj.s("container_id_hash"))
                            put("host", obj.s("host"))
                            put("containerId", obj.s("container_id"))
                            put("name", obj.s("name"))
                            put("image", obj.s("image"))
                            put("state", obj.s("state"))
                            obj["cpu_percent"]?.let { put("cpuPercent", it) }
                            obj["mem_usage"]?.let { put("memUsage", it) }
                            obj["mem_limit"]?.let { put("memLimit", it) }
                            obj["net_rx_bytes"]?.let { put("netRxBytes", it) }
                            obj["net_tx_bytes"]?.let { put("netTxBytes", it) }
                            obj["tags"]?.let { put("tags", it) }
                            put("timestamp", obj.s("ts"))
                        }
                    }

                call.respond(
                    buildJsonObject {
                        putJsonArray("containers") { containers.forEach { add(it) } }
                        put("totalCount", totalCount)
                    }
                )
            }

            get("/connections") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal?.payload
                    ?.getClaim("orgId")?.asInt()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "Missing org context")
                    )

                val limit = (
                    call.parameters["limit"]
                        ?.toIntOrNull() ?: DEFAULT_LIMIT
                    )
                    .coerceAtMost(MAX_LIMIT)
                val offset = call.parameters["offset"]
                    ?.toIntOrNull() ?: 0
                val host = call.parameters["host"]

                val db = ClickHouseClient.getDatabase()
                val conditions = mutableListOf(
                    ClickHouseQueryUtils.orgIdClause(orgId.toLong())
                )
                if (!host.isNullOrBlank()) {
                    conditions.add(
                        "host LIKE '%${escapeSql(host)}%'"
                    )
                }
                val where = conditions.joinToString(" AND ")

                val countSql = """
                    SELECT count() as cnt
                    FROM `$db`.network_connections
                    WHERE $where
                    FORMAT JSONEachRow
                """.trimIndent()
                val totalCount = executeCount(countSql)

                val dataSql = """
                    SELECT
                        connection_id, host, pid,
                        local_addr, local_port,
                        remote_addr, remote_port,
                        protocol, family, direction,
                        bytes_sent, bytes_recv,
                        tags,
                        formatDateTime(
                            timestamp,
                            '%Y-%m-%dT%H:%i:%S.000Z',
                            'UTC'
                        ) as ts
                    FROM `$db`.network_connections
                    WHERE $where
                    ORDER BY timestamp DESC
                    LIMIT $limit OFFSET $offset
                    FORMAT JSONEachRow
                """.trimIndent()

                val conns = executeRows(dataSql) { obj ->
                    buildJsonObject {
                        put("connectionId", obj.s("connection_id"))
                        put("host", obj.s("host"))
                        obj["pid"]?.let { put("pid", it) }
                        put("localAddr", obj.s("local_addr"))
                        obj["local_port"]?.let { put("localPort", it) }
                        put("remoteAddr", obj.s("remote_addr"))
                        obj["remote_port"]?.let { put("remotePort", it) }
                        put("protocol", obj.s("protocol"))
                        put("family", obj.s("family"))
                        put("direction", obj.s("direction"))
                        obj["bytes_sent"]?.let { put("bytesSent", it) }
                        obj["bytes_recv"]?.let { put("bytesRecv", it) }
                        obj["tags"]?.let { put("tags", it) }
                        put("timestamp", obj.s("ts"))
                    }
                }

                call.respond(
                    buildJsonObject {
                        putJsonArray("connections") { conns.forEach { add(it) } }
                        put("totalCount", totalCount)
                    }
                )
            }
        }
    }
}

private suspend fun executeCount(sql: String): Long {
    val resp = ClickHouseClient.execute(sql)
    val body = resp.bodyAsText()
    if (resp.isClickHouseError(body)) {
        logger.warn { "ClickHouse query failed (${resp.status.value}): ${body.take(LOG_BODY_MAX_LEN)}" }
        return 0L
    }
    return body.trim().lines().firstOrNull()?.let {
        json.parseToJsonElement(it)
            .jsonObject["cnt"]
            ?.jsonPrimitive?.content
            ?.toLongOrNull()
    } ?: 0L
}

private suspend fun executeRows(
    sql: String,
    mapper: (JsonObject) -> JsonObject
): List<JsonObject> {
    val resp = ClickHouseClient.execute(sql)
    val body = resp.bodyAsText()
    if (resp.isClickHouseError(body)) {
        logger.warn { "ClickHouse query failed (${resp.status.value}): ${body.take(LOG_BODY_MAX_LEN)}" }
        return emptyList()
    }
    return body.trim()
        .lines()
        .filter { it.isNotBlank() }
        .map { line ->
            mapper(
                json.parseToJsonElement(line).jsonObject
            )
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
