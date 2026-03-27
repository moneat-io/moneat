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

package com.moneat.datadog.security

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

fun Route.securityQueryRoutes() {
    route("/v1/security") {
        authenticate("auth-jwt") {
            get("/events") { handleListEvents() }
            get("/events/{eventId}") { handleEventDetail() }
            get("/compliance") { handleListFindings() }
            get("/compliance/summary") { handleComplianceSummary() }
        }
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleListEvents() {
    val orgId = extractOrgId() ?: return
    val limit = paramLimit()
    val offset = paramOffset()
    val db = ClickHouseClient.getDatabase()
    val conditions = mutableListOf(
        ClickHouseQueryUtils.orgIdClause(orgId.toLong())
    )
    call.parameters["severity"]?.let {
        conditions.add("severity = '${escapeSql(it)}'")
    }
    call.parameters["host"]?.let {
        conditions.add("host LIKE '%${escapeSql(it)}%'")
    }
    call.parameters["rule_id"]?.let {
        conditions.add("rule_id = '${escapeSql(it)}'")
    }
    val where = conditions.joinToString(" AND ")

    val totalCount = executeCount(
        "SELECT count() as cnt FROM `$db`.security_events WHERE $where FORMAT JSONEachRow"
    )

    val rows = executeRows(
        """SELECT event_id, rule_id, rule_name, rule_category,
            severity, event_type, process_name, file_path,
            host, env, tags,
            formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as ts
        FROM `$db`.security_events WHERE $where
        ORDER BY timestamp DESC LIMIT $limit OFFSET $offset
        FORMAT JSONEachRow"""
    ) { obj ->
        buildJsonObject {
            put("eventId", obj.s("event_id"))
            put("ruleId", obj.s("rule_id"))
            put("ruleName", obj.s("rule_name"))
            put("ruleCategory", obj.s("rule_category"))
            put("severity", obj.s("severity"))
            put("eventType", obj.s("event_type"))
            put("processName", obj.s("process_name"))
            put("filePath", obj.s("file_path"))
            put("host", obj.s("host"))
            put("env", obj.s("env"))
            obj["tags"]?.let { put("tags", it) }
            put("timestamp", obj.s("ts"))
        }
    }

    call.respond(
        buildJsonObject {
            putJsonArray("events") { rows.forEach { add(it) } }
            put("totalCount", totalCount)
        }
    )
}
private suspend fun io.ktor.server.routing.RoutingContext.handleEventDetail() {
    val orgId = extractOrgId() ?: return
    val eventId = call.parameters["eventId"] ?: return call.respond(
        HttpStatusCode.BadRequest, mapOf("error" to "Missing eventId")
    )
    val db = ClickHouseClient.getDatabase()
    val where = "${ClickHouseQueryUtils.orgIdClause(orgId.toLong())} " +
        "AND event_id = '${escapeSql(eventId)}'"

    val rows = executeRows(
        """SELECT event_id, rule_id, rule_name, rule_category,
            severity, agent_rule_version, event_type,
            process_name, file_path, host, env, tags,
            formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as ts
        FROM `$db`.security_events WHERE $where LIMIT 1
        FORMAT JSONEachRow"""
    ) { obj ->
        buildJsonObject {
            put("eventId", obj.s("event_id"))
            put("ruleId", obj.s("rule_id"))
            put("ruleName", obj.s("rule_name"))
            put("ruleCategory", obj.s("rule_category"))
            put("severity", obj.s("severity"))
            put("agentRuleVersion", obj.s("agent_rule_version"))
            put("eventType", obj.s("event_type"))
            put("processName", obj.s("process_name"))
            put("filePath", obj.s("file_path"))
            put("host", obj.s("host"))
            put("env", obj.s("env"))
            obj["tags"]?.let { put("tags", it) }
            put("timestamp", obj.s("ts"))
        }
    }

    if (rows.isEmpty()) {
        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Event not found"))
    } else {
        call.respond(rows.first())
    }
}
private suspend fun io.ktor.server.routing.RoutingContext.handleListFindings() {
    val orgId = extractOrgId() ?: return
    val limit = paramLimit()
    val offset = paramOffset()
    val db = ClickHouseClient.getDatabase()
    val conditions = mutableListOf(
        ClickHouseQueryUtils.orgIdClause(orgId.toLong())
    )
    call.parameters["framework"]?.let {
        conditions.add("framework = '${escapeSql(it)}'")
    }
    call.parameters["status"]?.let {
        conditions.add("status = '${escapeSql(it)}'")
    }
    val where = conditions.joinToString(" AND ")

    val totalCount = executeCount(
        "SELECT count() as cnt FROM `$db`.compliance_findings WHERE $where FORMAT JSONEachRow"
    )

    val rows = executeRows(
        """SELECT finding_id, framework, rule_id, rule_name,
            status, resource_type, resource_id, resource_name, tags,
            formatDateTime(evaluated_at, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as ts
        FROM `$db`.compliance_findings WHERE $where
        ORDER BY evaluated_at DESC LIMIT $limit OFFSET $offset
        FORMAT JSONEachRow"""
    ) { obj ->
        buildJsonObject {
            put("findingId", obj.s("finding_id"))
            put("framework", obj.s("framework"))
            put("ruleId", obj.s("rule_id"))
            put("ruleName", obj.s("rule_name"))
            put("status", obj.s("status"))
            put("resourceType", obj.s("resource_type"))
            put("resourceId", obj.s("resource_id"))
            put("resourceName", obj.s("resource_name"))
            obj["tags"]?.let { put("tags", it) }
            put("evaluatedAt", obj.s("ts"))
        }
    }

    call.respond(
        buildJsonObject {
            putJsonArray("findings") { rows.forEach { add(it) } }
            put("totalCount", totalCount)
        }
    )
}
private suspend fun io.ktor.server.routing.RoutingContext.handleComplianceSummary() {
    val orgId = extractOrgId() ?: return
    val db = ClickHouseClient.getDatabase()
    val where = ClickHouseQueryUtils.orgIdClause(orgId.toLong())

    val rows = executeRows(
        """SELECT framework, status, count() as cnt
        FROM `$db`.compliance_findings WHERE $where
        GROUP BY framework, status
        ORDER BY framework, status
        FORMAT JSONEachRow"""
    ) { obj ->
        buildJsonObject {
            put("framework", obj.s("framework"))
            put("status", obj.s("status"))
            obj["cnt"]?.let { put("count", it) }
        }
    }

    call.respond(
        buildJsonObject {
            putJsonArray("summary") { rows.forEach { add(it) } }
        }
    )
}

private fun io.ktor.server.routing.RoutingContext.extractOrgId(): Int? {
    val principal = call.principal<JWTPrincipal>()
    return principal?.payload?.getClaim("orgId")?.asInt()
}

private fun io.ktor.server.routing.RoutingContext.paramLimit(): Int =
    (call.parameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT)
        .coerceAtMost(MAX_LIMIT)

private fun io.ktor.server.routing.RoutingContext.paramOffset(): Int =
    call.parameters["offset"]?.toIntOrNull() ?: 0

private suspend fun executeCount(sql: String): Long {
    val resp = ClickHouseClient.execute(sql)
    val body = resp.bodyAsText()
    if (resp.isClickHouseError(body)) {
        logger.error { "ClickHouse error in executeCount. SQL: ${sql.take(200)} Body: ${body.take(300)}" }
        throw IllegalStateException("ClickHouse query error: ${body.take(300)}")
    }
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
    if (resp.isClickHouseError(body)) {
        logger.error { "ClickHouse error in executeRows. SQL: ${sql.take(200)} Body: ${body.take(300)}" }
        throw IllegalStateException("ClickHouse query error: ${body.take(300)}")
    }
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
