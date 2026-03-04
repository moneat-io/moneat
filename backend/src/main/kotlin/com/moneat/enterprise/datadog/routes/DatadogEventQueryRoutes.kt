// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.routes

import com.moneat.config.ClickHouseClient
import com.moneat.utils.ClickHouseQueryUtils
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

fun Route.datadogEventQueryRoutes() {
    route("/v1/infra") {
        authenticate("auth-jwt") {
            get("/events") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal?.payload
                    ?.getClaim("orgId")?.asInt()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "Missing org context")
                    )

                val limit = (call.parameters["limit"]
                    ?.toIntOrNull() ?: DEFAULT_LIMIT)
                    .coerceAtMost(MAX_LIMIT)
                val offset = call.parameters["offset"]
                    ?.toIntOrNull() ?: 0
                val alertType = call.parameters["alert_type"]
                val host = call.parameters["host"]

                val db = ClickHouseClient.getDatabase()
                val conditions = mutableListOf(
                    ClickHouseQueryUtils.orgIdClause(orgId.toLong())
                )
                if (!alertType.isNullOrBlank()) {
                    conditions.add(
                        "alert_type = " +
                            "'${escapeSql(alertType)}'"
                    )
                }
                if (!host.isNullOrBlank()) {
                    conditions.add(
                        "host LIKE '%${escapeSql(host)}%'"
                    )
                }
                val where = conditions.joinToString(" AND ")

                val countSql = """
                    SELECT count() as cnt
                    FROM $db.infra_events
                    WHERE $where
                    FORMAT JSONEachRow
                """.trimIndent()
                val countResp =
                    ClickHouseClient.execute(countSql)
                val countBody = countResp.bodyAsText()
                val totalCount = countBody.trim()
                    .lines().firstOrNull()?.let {
                        json.parseToJsonElement(it)
                            .jsonObject["cnt"]
                            ?.jsonPrimitive?.content
                            ?.toLongOrNull()
                    } ?: 0L

                val dataSql = """
                    SELECT
                        event_id, title, text,
                        formatDateTime(
                            timestamp,
                            '%Y-%m-%dT%H:%i:%S.000Z',
                            'UTC'
                        ) as ts,
                        priority, host, tags, alert_type,
                        aggregation_key, source_type_name,
                        device_name
                    FROM $db.infra_events
                    WHERE $where
                    ORDER BY timestamp DESC
                    LIMIT $limit OFFSET $offset
                    FORMAT JSONEachRow
                """.trimIndent()

                val dataResp =
                    ClickHouseClient.execute(dataSql)
                val dataBody = dataResp.bodyAsText()
                val events = dataBody.trim()
                    .lines()
                    .filter { it.isNotBlank() }
                    .map { line ->
                        val obj = json
                            .parseToJsonElement(line)
                            .jsonObject
                        buildJsonObject {
                            put("eventId", obj.s("event_id"))
                            put("title", obj.s("title"))
                            put("text", obj.s("text"))
                            put("timestamp", obj.s("ts"))
                            put("priority", obj.s("priority"))
                            put("host", obj.s("host"))
                            obj["tags"]?.let { put("tags", it) }
                            put("alertType", obj.s("alert_type"))
                            put("aggregationKey", obj.s("aggregation_key"))
                            put("sourceTypeName", obj.s("source_type_name"))
                            put("deviceName", obj.s("device_name"))
                        }
                    }

                call.respond(
                    buildJsonObject {
                        putJsonArray("events") { events.forEach { add(it) } }
                        put("totalCount", totalCount)
                    }
                )
            }

            get("/service-checks") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal?.payload
                    ?.getClaim("orgId")?.asInt()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "Missing org context")
                    )

                val limit = (call.parameters["limit"]
                    ?.toIntOrNull() ?: DEFAULT_LIMIT)
                    .coerceAtMost(MAX_LIMIT)
                val offset = call.parameters["offset"]
                    ?.toIntOrNull() ?: 0
                val checkName =
                    call.parameters["check_name"]
                val host = call.parameters["host"]

                val db = ClickHouseClient.getDatabase()
                val conditions = mutableListOf(
                    ClickHouseQueryUtils.orgIdClause(orgId.toLong())
                )
                if (!checkName.isNullOrBlank()) {
                    conditions.add(
                        "check_name = " +
                            "'${escapeSql(checkName)}'"
                    )
                }
                if (!host.isNullOrBlank()) {
                    conditions.add(
                        "host LIKE '%${escapeSql(host)}%'"
                    )
                }
                val where = conditions.joinToString(" AND ")

                val countSql = """
                    SELECT count() as cnt
                    FROM $db.service_checks
                    WHERE $where
                    FORMAT JSONEachRow
                """.trimIndent()
                val countResp =
                    ClickHouseClient.execute(countSql)
                val countBody = countResp.bodyAsText()
                val totalCount = countBody.trim()
                    .lines().firstOrNull()?.let {
                        json.parseToJsonElement(it)
                            .jsonObject["cnt"]
                            ?.jsonPrimitive?.content
                            ?.toLongOrNull()
                    } ?: 0L

                val dataSql = """
                    SELECT
                        check_id, check_name, host,
                        status,
                        formatDateTime(
                            timestamp,
                            '%Y-%m-%dT%H:%i:%S.000Z',
                            'UTC'
                        ) as ts,
                        tags, message
                    FROM $db.service_checks
                    WHERE $where
                    ORDER BY timestamp DESC
                    LIMIT $limit OFFSET $offset
                    FORMAT JSONEachRow
                """.trimIndent()

                val dataResp =
                    ClickHouseClient.execute(dataSql)
                val dataBody = dataResp.bodyAsText()
                val checks = dataBody.trim()
                    .lines()
                    .filter { it.isNotBlank() }
                    .map { line ->
                        val obj = json
                            .parseToJsonElement(line)
                            .jsonObject
                        buildJsonObject {
                            put("checkId", obj.s("check_id"))
                            put("checkName", obj.s("check_name"))
                            put("host", obj.s("host"))
                            put("status", obj.s("status"))
                            put("timestamp", obj.s("ts"))
                            obj["tags"]?.let { put("tags", it) }
                            put("message", obj.s("message"))
                        }
                    }

                call.respond(
                    buildJsonObject {
                        putJsonArray("serviceChecks") { checks.forEach { add(it) } }
                        put("totalCount", totalCount)
                    }
                )
            }
        }
    }
}

private fun kotlinx.serialization.json.JsonObject.s(
    key: String
): String {
    val el = this[key] ?: return ""
    return if (el is kotlinx.serialization.json.JsonPrimitive) {
        el.content
    } else {
        el.toString()
    }
}

private fun escapeSql(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("'", "\\'")
}
