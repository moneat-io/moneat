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

package com.moneat.monitor.routes

import com.moneat.config.ClickHouseClient
import com.moneat.shared.models.Memberships
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

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

private fun parseLimit(limitParam: String?): Int {
    val limit = limitParam?.toIntOrNull() ?: DEFAULT_LIMIT
    return limit.coerceIn(1, MAX_LIMIT)
}

/**
 * Parses a ClickHouse TSVWithNamesAndTypes response into a list of JSON objects.
 * Format: first line = column names (tab-separated), second line = types, rest = data rows.
 */
private fun parseTsvResponse(body: String): List<JsonObject> {
    val lines = body.trim().lines()
    if (lines.size < 2) return emptyList()

    val headers = lines[0].split('\t')
    val types = lines[1].split('\t')

    return lines.drop(2).mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val values = line.split('\t')
        buildJsonObject {
            headers.forEachIndexed { i, header ->
                val value = values.getOrElse(i) { "" }
                val type = types.getOrElse(i) { "" }
                val camelKey = snakeToCamel(header)
                when {
                    type.startsWith("Array") -> {
                        put(camelKey, parseClickHouseArray(value))
                    }
                    type.startsWith("Map") -> {
                        put(camelKey, parseClickHouseMap(value))
                    }
                    type.startsWith("UInt") || type.startsWith("Int") ||
                        type.startsWith("Float") || type.startsWith("Decimal") -> {
                        val num = value.toLongOrNull()
                        if (num != null) {
                            put(camelKey, JsonPrimitive(num))
                        } else {
                            val dbl = value.toDoubleOrNull()
                            if (dbl != null) {
                                put(camelKey, JsonPrimitive(dbl))
                            } else {
                                put(camelKey, JsonPrimitive(value))
                            }
                        }
                    }
                    else -> put(camelKey, JsonPrimitive(value))
                }
            }
        }
    }
}

private fun snakeToCamel(snake: String): String {
    return snake.split('_').mapIndexed { index, part ->
        if (index == 0) part.lowercase() else part.replaceFirstChar { it.uppercase() }
    }.joinToString("")
}

private fun parseClickHouseArray(value: String): JsonArray {
    val trimmed = value.trim()
    if (trimmed == "[]" || trimmed.isEmpty()) return buildJsonArray {}
    val inner = trimmed.removePrefix("[").removeSuffix("]")
    return buildJsonArray {
        inner.split(',').forEach { item ->
            add(JsonPrimitive(item.trim().removeSurrounding("'")))
        }
    }
}

private fun parseClickHouseMap(value: String): JsonObject {
    val trimmed = value.trim()
    if (trimmed == "{}" || trimmed.isEmpty()) return buildJsonObject {}
    val inner = trimmed.removePrefix("{").removeSuffix("}")
    return buildJsonObject {
        inner.split(',').forEach { pair ->
            val parts = pair.split(':', limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim().removeSurrounding("'")
                val v = parts[1].trim().removeSurrounding("'")
                put(key, JsonPrimitive(v))
            }
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
fun Route.infraRoutes() {
    route("/v1") {
        authenticate("auth-jwt") {
            // --- Infrastructure Events ---

            get("/infra/events") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(HttpStatusCode.OK, mapOf("events" to emptyList<Any>()))
                    return@get
                }

                val limit = parseLimit(call.parameters["limit"])
                val host = call.parameters["host"]
                val alertType = call.parameters["alert_type"]

                val conditions = mutableListOf(
                    "organization_id IN (${orgIds.joinToString(",")})"
                )
                if (host != null) conditions.add("host = '$host'")
                if (alertType != null) conditions.add("alert_type = '$alertType'")

                val query = """
                    SELECT * FROM infra_events
                    WHERE ${conditions.joinToString(" AND ")}
                    ORDER BY timestamp DESC
                    LIMIT $limit
                    FORMAT TSVWithNamesAndTypes
                """.trimIndent()

                val result = executeChQuery(query) ?: run {
                    call.respond(HttpStatusCode.OK, mapOf("events" to emptyList<Any>()))
                    return@get
                }
                call.respond(HttpStatusCode.OK, mapOf("events" to result))
            }

            // --- Service Checks ---

            get("/infra/service-checks") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(HttpStatusCode.OK, mapOf("serviceChecks" to emptyList<Any>()))
                    return@get
                }

                val limit = parseLimit(call.parameters["limit"])
                val query = """
                    SELECT * FROM service_checks
                    WHERE organization_id IN (${orgIds.joinToString(",")})
                    ORDER BY timestamp DESC
                    LIMIT $limit
                    FORMAT TSVWithNamesAndTypes
                """.trimIndent()

                val result = executeChQuery(query) ?: run {
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("serviceChecks" to emptyList<Any>())
                    )
                    return@get
                }
                call.respond(HttpStatusCode.OK, mapOf("serviceChecks" to result))
            }

            // --- Processes ---

            get("/infra/processes") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(HttpStatusCode.OK, mapOf("processes" to emptyList<Any>()))
                    return@get
                }

                val limit = parseLimit(call.parameters["limit"])
                val host = call.parameters["host"]
                val conditions = mutableListOf(
                    "organization_id IN (${orgIds.joinToString(",")})"
                )
                if (host != null) conditions.add("host = '$host'")

                val query = """
                    SELECT * FROM processes
                    WHERE ${conditions.joinToString(" AND ")}
                    ORDER BY timestamp DESC
                    LIMIT $limit
                    FORMAT TSVWithNamesAndTypes
                """.trimIndent()

                val result = executeChQuery(query) ?: run {
                    call.respond(HttpStatusCode.OK, mapOf("processes" to emptyList<Any>()))
                    return@get
                }
                call.respond(HttpStatusCode.OK, mapOf("processes" to result))
            }

            // --- Containers ---

            get("/infra/containers") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(HttpStatusCode.OK, mapOf("containers" to emptyList<Any>()))
                    return@get
                }

                val limit = parseLimit(call.parameters["limit"])
                val host = call.parameters["host"]
                val conditions = mutableListOf(
                    "organization_id IN (${orgIds.joinToString(",")})"
                )
                if (host != null) conditions.add("host = '$host'")

                val query = """
                    SELECT * FROM containers
                    WHERE ${conditions.joinToString(" AND ")}
                    ORDER BY timestamp DESC
                    LIMIT $limit
                    FORMAT TSVWithNamesAndTypes
                """.trimIndent()

                val result = executeChQuery(query) ?: run {
                    call.respond(HttpStatusCode.OK, mapOf("containers" to emptyList<Any>()))
                    return@get
                }
                call.respond(HttpStatusCode.OK, mapOf("containers" to result))
            }

            // --- Network Connections ---

            get("/infra/connections") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(HttpStatusCode.OK, mapOf("connections" to emptyList<Any>()))
                    return@get
                }

                val limit = parseLimit(call.parameters["limit"])
                val query = """
                    SELECT * FROM network_connections
                    WHERE organization_id IN (${orgIds.joinToString(",")})
                    ORDER BY timestamp DESC
                    LIMIT $limit
                    FORMAT TSVWithNamesAndTypes
                """.trimIndent()

                val result = executeChQuery(query) ?: run {
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("connections" to emptyList<Any>())
                    )
                    return@get
                }
                call.respond(HttpStatusCode.OK, mapOf("connections" to result))
            }

            // --- Kubernetes Resources ---

            get("/infra/k8s-resources") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(HttpStatusCode.OK, mapOf("resources" to emptyList<Any>()))
                    return@get
                }

                val limit = parseLimit(call.parameters["limit"])
                val resourceType = call.parameters["resource_type"]

                val conditions = mutableListOf(
                    "organization_id IN (${orgIds.joinToString(",")})"
                )
                if (resourceType != null) {
                    conditions.add("resource_type = '$resourceType'")
                }

                val query = """
                    SELECT * FROM k8s_resources
                    WHERE ${conditions.joinToString(" AND ")}
                    ORDER BY collected_at DESC
                    LIMIT $limit
                    FORMAT TSVWithNamesAndTypes
                """.trimIndent()

                val result = executeChQuery(query) ?: run {
                    call.respond(HttpStatusCode.OK, mapOf("resources" to emptyList<Any>()))
                    return@get
                }
                call.respond(HttpStatusCode.OK, mapOf("resources" to result))
            }

            // --- Database Monitoring ---

            get("/infra/dbm/queries") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(HttpStatusCode.OK, mapOf("queries" to emptyList<Any>()))
                    return@get
                }

                val limit = parseLimit(call.parameters["limit"])
                val query = """
                    SELECT * FROM dbm_queries
                    WHERE organization_id IN (${orgIds.joinToString(",")})
                    ORDER BY timestamp DESC
                    LIMIT $limit
                    FORMAT TSVWithNamesAndTypes
                """.trimIndent()

                val result = executeChQuery(query) ?: run {
                    call.respond(HttpStatusCode.OK, mapOf("queries" to emptyList<Any>()))
                    return@get
                }
                call.respond(HttpStatusCode.OK, mapOf("queries" to result))
            }

            // --- Dynamic Instrumentation (Debugger) ---

            get("/infra/debugger/logs") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(HttpStatusCode.OK, mapOf("logs" to emptyList<Any>()))
                    return@get
                }

                val limit = parseLimit(call.parameters["limit"])
                val query = """
                    SELECT * FROM debugger_logs
                    WHERE organization_id IN (${orgIds.joinToString(",")})
                    ORDER BY timestamp DESC
                    LIMIT $limit
                    FORMAT TSVWithNamesAndTypes
                """.trimIndent()

                val result = executeChQuery(query) ?: run {
                    call.respond(HttpStatusCode.OK, mapOf("logs" to emptyList<Any>()))
                    return@get
                }
                call.respond(HttpStatusCode.OK, mapOf("logs" to result))
            }

            get("/infra/debugger/diagnostics") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("diagnostics" to emptyList<Any>())
                    )
                    return@get
                }

                val limit = parseLimit(call.parameters["limit"])
                val query = """
                    SELECT * FROM debugger_diagnostics
                    WHERE organization_id IN (${orgIds.joinToString(",")})
                    ORDER BY timestamp DESC
                    LIMIT $limit
                    FORMAT TSVWithNamesAndTypes
                """.trimIndent()

                val result = executeChQuery(query) ?: run {
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("diagnostics" to emptyList<Any>())
                    )
                    return@get
                }
                call.respond(HttpStatusCode.OK, mapOf("diagnostics" to result))
            }

            // --- SBOM ---

            get("/infra/sbom") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(HttpStatusCode.OK, mapOf("packages" to emptyList<Any>()))
                    return@get
                }

                val limit = parseLimit(call.parameters["limit"])
                val query = """
                    SELECT * FROM sbom_packages
                    WHERE organization_id IN (${orgIds.joinToString(",")})
                    ORDER BY collected_at DESC
                    LIMIT $limit
                    FORMAT TSVWithNamesAndTypes
                """.trimIndent()

                val result = executeChQuery(query) ?: run {
                    call.respond(HttpStatusCode.OK, mapOf("packages" to emptyList<Any>()))
                    return@get
                }
                call.respond(HttpStatusCode.OK, mapOf("packages" to result))
            }

            // --- Network Devices ---

            get("/network-devices") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(HttpStatusCode.OK, mapOf("devices" to emptyList<Any>()))
                    return@get
                }

                val limit = parseLimit(call.parameters["limit"])
                val query = """
                    SELECT * FROM ndm_devices
                    WHERE organization_id IN (${orgIds.joinToString(",")})
                    ORDER BY collected_at DESC
                    LIMIT $limit
                    FORMAT TSVWithNamesAndTypes
                """.trimIndent()

                val result = executeChQuery(query) ?: run {
                    call.respond(HttpStatusCode.OK, mapOf("devices" to emptyList<Any>()))
                    return@get
                }
                call.respond(HttpStatusCode.OK, mapOf("devices" to result))
            }

            get("/network-devices/flows") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(HttpStatusCode.OK, mapOf("flows" to emptyList<Any>()))
                    return@get
                }

                val limit = parseLimit(call.parameters["limit"])
                val query = """
                    SELECT * FROM ndm_flows
                    WHERE organization_id IN (${orgIds.joinToString(",")})
                    ORDER BY sampled_at DESC
                    LIMIT $limit
                    FORMAT TSVWithNamesAndTypes
                """.trimIndent()

                val result = executeChQuery(query) ?: run {
                    call.respond(HttpStatusCode.OK, mapOf("flows" to emptyList<Any>()))
                    return@get
                }
                call.respond(HttpStatusCode.OK, mapOf("flows" to result))
            }

            get("/network-devices/traps") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(HttpStatusCode.OK, mapOf("traps" to emptyList<Any>()))
                    return@get
                }

                val limit = parseLimit(call.parameters["limit"])
                val query = """
                    SELECT * FROM ndm_traps
                    WHERE organization_id IN (${orgIds.joinToString(",")})
                    ORDER BY received_at DESC
                    LIMIT $limit
                    FORMAT TSVWithNamesAndTypes
                """.trimIndent()

                val result = executeChQuery(query) ?: run {
                    call.respond(HttpStatusCode.OK, mapOf("traps" to emptyList<Any>()))
                    return@get
                }
                call.respond(HttpStatusCode.OK, mapOf("traps" to result))
            }

            get("/network-devices/paths") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(HttpStatusCode.OK, mapOf("paths" to emptyList<Any>()))
                    return@get
                }

                val limit = parseLimit(call.parameters["limit"])
                val query = """
                    SELECT * FROM network_paths
                    WHERE organization_id IN (${orgIds.joinToString(",")})
                    ORDER BY collected_at DESC
                    LIMIT $limit
                    FORMAT TSVWithNamesAndTypes
                """.trimIndent()

                val result = executeChQuery(query) ?: run {
                    call.respond(HttpStatusCode.OK, mapOf("paths" to emptyList<Any>()))
                    return@get
                }
                call.respond(HttpStatusCode.OK, mapOf("paths" to result))
            }
        }
    }
}

private suspend fun executeChQuery(query: String): List<JsonObject>? {
    return runCatching {
        val response = ClickHouseClient.execute(query)
        if (response.status.value !in 200..299) {
            logger.warn { "ClickHouse query failed: ${response.status}" }
            return null
        }
        parseTsvResponse(response.bodyAsText())
    }.getOrElse {
        logger.warn { "ClickHouse query error: ${it.message}" }
        null
    }
}
