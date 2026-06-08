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

import com.moneat.auth.requireCurrentOrg
import com.moneat.config.ClickHouseClient
import com.moneat.utils.HttpConstants.HTTP_SUCCESS_MAX
import com.moneat.utils.HttpConstants.HTTP_SUCCESS_MIN
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.server.auth.authenticate
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

private val logger = KotlinLogging.logger {}

private val json = Json { ignoreUnknownKeys = true }

private const val DEFAULT_LIMIT = 100
private const val MAX_LIMIT = 500
private const val MAX_IDENTIFIER_LENGTH = 255
private const val MAX_NAME_LENGTH = 64
private const val MAX_SHORT_NAME_LENGTH = 32

private fun orgIdToChValue(orgId: Int): String = "toUInt64($orgId)"

private fun parseLimit(limitParam: String?): Int {
    val limit = limitParam?.toIntOrNull() ?: DEFAULT_LIMIT
    return limit.coerceIn(1, MAX_LIMIT)
}

private val ALLOWED_SEVERITY = setOf("critical", "high", "medium", "low", "info")
private val SAFE_IDENTIFIER_REGEX = Regex("^[a-zA-Z0-9._-]+\$")

private fun sanitizeSeverity(value: String?): String? =
    value?.takeIf { it.lowercase() in ALLOWED_SEVERITY }?.let { it }

private fun sanitizeIdentifier(value: String?): String? =
    value?.takeIf { it.matches(SAFE_IDENTIFIER_REGEX) && it.length <= MAX_IDENTIFIER_LENGTH }

private fun sanitizeFramework(value: String?): String? =
    value?.takeIf { it.matches(SAFE_IDENTIFIER_REGEX) && it.length <= MAX_NAME_LENGTH }

private fun sanitizeStatus(value: String?): String? =
    value?.takeIf { it.matches(SAFE_IDENTIFIER_REGEX) && it.length <= MAX_SHORT_NAME_LENGTH }

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
        if (response.status.value !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
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
                val orgId = call.requireCurrentOrg()?.orgId ?: return@get

                val db = ClickHouseClient.getDatabase()
                val limit = parseLimit(call.parameters["limit"])
                val severity = sanitizeSeverity(call.parameters["severity"])
                val host = sanitizeIdentifier(call.parameters["host"])

                val conditions = mutableListOf(
                    "organization_id = ${orgIdToChValue(orgId)}"
                )
                if (severity != null) conditions.add("severity = '$severity'")
                if (host != null) conditions.add("host = '$host'")

                val query = """
                    SELECT * FROM `$db`.security_events
                    WHERE ${conditions.joinToString(" AND ")}
                    ORDER BY timestamp DESC
                    LIMIT $limit
                    FORMAT JSONEachRow
                """.trimIndent()

                val countQuery = """
                    SELECT count() FROM `$db`.security_events
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
                call.respondText(
                    json.encodeToString(JsonObject.serializer(), responseJson),
                    ContentType.Application.Json
                )
            }

            // --- Compliance Findings ---

            get("/security/compliance") {
                val orgId = call.requireCurrentOrg()?.orgId ?: return@get

                val db = ClickHouseClient.getDatabase()
                val limit = parseLimit(call.parameters["limit"])
                val framework = sanitizeFramework(call.parameters["framework"])
                val status = sanitizeStatus(call.parameters["status"])

                val conditions = mutableListOf(
                    "organization_id = ${orgIdToChValue(orgId)}"
                )
                if (framework != null) conditions.add("framework = '$framework'")
                if (status != null) conditions.add("status = '$status'")

                val query = """
                    SELECT * FROM `$db`.compliance_findings
                    WHERE ${conditions.joinToString(" AND ")}
                    ORDER BY evaluated_at DESC
                    LIMIT $limit
                    FORMAT JSONEachRow
                """.trimIndent()

                val countQuery = """
                    SELECT count() FROM `$db`.compliance_findings
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
                call.respondText(
                    json.encodeToString(JsonObject.serializer(), responseJson),
                    ContentType.Application.Json
                )
            }
        }
    }
}
