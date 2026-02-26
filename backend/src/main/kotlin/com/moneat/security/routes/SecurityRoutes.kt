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

package com.moneat.security.routes

import com.moneat.config.ClickHouseClient
import com.moneat.shared.models.Memberships
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

private val json = Json { ignoreUnknownKeys = true }

private const val DEFAULT_LIMIT = 100
private const val MAX_LIMIT = 500

private fun getOrgIdsForUser(userId: Int): List<Int> {
    return transaction {
        Memberships
            .selectAll()
            .where { Memberships.user_id eq userId }
            .map { it[Memberships.organization_id] }
    }
}

private fun orgIdsToChCondition(orgIds: List<Int>): String {
    return orgIds.joinToString(",") { "toUInt64($it)" }
}

private fun parseLimit(limitParam: String?): Int {
    val limit = limitParam?.toIntOrNull() ?: DEFAULT_LIMIT
    return limit.coerceIn(1, MAX_LIMIT)
}

private fun snakeToCamel(snake: String): String {
    return snake.split('_').mapIndexed { index, part ->
        if (index == 0) part.lowercase() else part.replaceFirstChar { it.uppercase() }
    }.joinToString("")
}

private fun camelCaseKeys(obj: JsonObject): JsonObject {
    val entries = obj.entries.associate { (k, v) -> snakeToCamel(k) to v }
    return JsonObject(entries)
}

private fun parseJsonEachRow(body: String): List<JsonObject> {
    return body.trim().lines()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            runCatching {
                camelCaseKeys(json.parseToJsonElement(line).jsonObject)
            }.getOrNull()
        }
}

private suspend fun executeChQuery(query: String): List<JsonObject>? {
    return runCatching {
        val response = ClickHouseClient.execute(query)
        if (response.status.value !in 200..299) {
            logger.warn { "ClickHouse query failed: ${response.status}" }
            return null
        }
        parseJsonEachRow(response.bodyAsText())
    }.getOrElse {
        logger.warn { "ClickHouse query error: ${it.message}" }
        null
    }
}

@Suppress("LongMethod")
fun Route.securityRoutes() {
    route("/v1") {
        authenticate("auth-jwt") {
            // --- Security Events ---

            get("/security/events") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respondText(
                        """{"events":[],"totalCount":0}""",
                        ContentType.Application.Json
                    )
                    return@get
                }

                val limit = parseLimit(call.parameters["limit"])
                val severity = call.parameters["severity"]
                val host = call.parameters["host"]

                val conditions = mutableListOf(
                    "organization_id IN (${orgIdsToChCondition(orgIds)})"
                )
                if (severity != null) conditions.add("severity = '$severity'")
                if (host != null) conditions.add("host = '$host'")

                val query = """
                    SELECT * FROM security_events
                    WHERE ${conditions.joinToString(" AND ")}
                    ORDER BY timestamp DESC
                    LIMIT $limit
                    FORMAT JSONEachRow
                """.trimIndent()

                val countQuery = """
                    SELECT count() FROM security_events
                    WHERE ${conditions.joinToString(" AND ")}
                """.trimIndent()

                val results = executeChQuery(query) ?: run {
                    call.respondText(
                        """{"events":[],"totalCount":0}""",
                        ContentType.Application.Json
                    )
                    return@get
                }

                val totalCount = runCatching {
                    val response = ClickHouseClient.execute(countQuery)
                    response.bodyAsText().trim().toLongOrNull() ?: 0L
                }.getOrElse { 0L }

                val responseJson = buildJsonObject {
                    put("events", JsonArray(results))
                    put("totalCount", JsonPrimitive(totalCount))
                }
                call.respondText(json.encodeToString(JsonObject.serializer(), responseJson), ContentType.Application.Json)
            }

            // --- Compliance Findings ---

            get("/security/compliance") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respondText(
                        """{"findings":[],"totalCount":0}""",
                        ContentType.Application.Json
                    )
                    return@get
                }

                val limit = parseLimit(call.parameters["limit"])
                val framework = call.parameters["framework"]
                val status = call.parameters["status"]

                val conditions = mutableListOf(
                    "organization_id IN (${orgIdsToChCondition(orgIds)})"
                )
                if (framework != null) conditions.add("framework = '$framework'")
                if (status != null) conditions.add("status = '$status'")

                val query = """
                    SELECT * FROM compliance_findings
                    WHERE ${conditions.joinToString(" AND ")}
                    ORDER BY evaluated_at DESC
                    LIMIT $limit
                    FORMAT JSONEachRow
                """.trimIndent()

                val countQuery = """
                    SELECT count() FROM compliance_findings
                    WHERE ${conditions.joinToString(" AND ")}
                """.trimIndent()

                val results = executeChQuery(query) ?: run {
                    call.respondText(
                        """{"findings":[],"totalCount":0}""",
                        ContentType.Application.Json
                    )
                    return@get
                }

                val totalCount = runCatching {
                    val response = ClickHouseClient.execute(countQuery)
                    response.bodyAsText().trim().toLongOrNull() ?: 0L
                }.getOrElse { 0L }

                val responseJson = buildJsonObject {
                    put("findings", JsonArray(results))
                    put("totalCount", JsonPrimitive(totalCount))
                }
                call.respondText(json.encodeToString(JsonObject.serializer(), responseJson), ContentType.Application.Json)
            }
        }
    }
}
