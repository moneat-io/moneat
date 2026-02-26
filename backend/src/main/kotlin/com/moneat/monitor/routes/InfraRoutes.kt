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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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

/**
 * Converts ClickHouse snake_case JSON keys to camelCase.
 */
private fun snakeToCamel(snake: String): String {
    return snake.split('_').mapIndexed { index, part ->
        if (index == 0) {
            part.lowercase()
        } else {
            part.replaceFirstChar { it.uppercase() }
        }
    }.joinToString("")
}

private fun camelCaseKeys(obj: JsonObject): JsonObject {
    val entries = obj.entries.associate { (k, v) -> snakeToCamel(k) to v }
    return JsonObject(entries)
}

/**
 * Parses ClickHouse JSONEachRow response into a list of camelCase JsonObjects.
 */
private fun parseJsonEachRow(body: String): List<JsonObject> {
    return body.trim().lines()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            runCatching {
                camelCaseKeys(json.parseToJsonElement(line).jsonObject)
            }.getOrNull()
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
                    "organization_id IN (${orgIdsToChCondition(orgIds)})"
                )
                if (host != null) conditions.add("host = '$host'")
                if (alertType != null) conditions.add("alert_type = '$alertType'")

                val query = """
                    SELECT * FROM infra_events
                    WHERE ${conditions.joinToString(" AND ")}
                    ORDER BY timestamp DESC
                    LIMIT $limit
                    FORMAT JSONEachRow
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
                    WHERE organization_id IN (${orgIdsToChCondition(orgIds)})
                    ORDER BY timestamp DESC
                    LIMIT $limit
                    FORMAT JSONEachRow
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
                    "organization_id IN (${orgIdsToChCondition(orgIds)})"
                )
                if (host != null) conditions.add("host = '$host'")

                val query = """
                    SELECT * FROM processes
                    WHERE ${conditions.joinToString(" AND ")}
                    ORDER BY timestamp DESC
                    LIMIT $limit
                    FORMAT JSONEachRow
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
                    "organization_id IN (${orgIdsToChCondition(orgIds)})"
                )
                if (host != null) conditions.add("host = '$host'")

                val query = """
                    SELECT * FROM containers
                    WHERE ${conditions.joinToString(" AND ")}
                    ORDER BY timestamp DESC
                    LIMIT $limit
                    FORMAT JSONEachRow
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
                    WHERE organization_id IN (${orgIdsToChCondition(orgIds)})
                    ORDER BY timestamp DESC
                    LIMIT $limit
                    FORMAT JSONEachRow
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
                    "organization_id IN (${orgIdsToChCondition(orgIds)})"
                )
                if (resourceType != null) {
                    conditions.add("resource_type = '$resourceType'")
                }

                val query = """
                    SELECT * FROM k8s_resources
                    WHERE ${conditions.joinToString(" AND ")}
                    ORDER BY collected_at DESC
                    LIMIT $limit
                    FORMAT JSONEachRow
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
                    WHERE organization_id IN (${orgIdsToChCondition(orgIds)})
                    ORDER BY timestamp DESC
                    LIMIT $limit
                    FORMAT JSONEachRow
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
                    WHERE organization_id IN (${orgIdsToChCondition(orgIds)})
                    ORDER BY timestamp DESC
                    LIMIT $limit
                    FORMAT JSONEachRow
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
                    WHERE organization_id IN (${orgIdsToChCondition(orgIds)})
                    ORDER BY timestamp DESC
                    LIMIT $limit
                    FORMAT JSONEachRow
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
                    WHERE organization_id IN (${orgIdsToChCondition(orgIds)})
                    ORDER BY collected_at DESC
                    LIMIT $limit
                    FORMAT JSONEachRow
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
                    WHERE organization_id IN (${orgIdsToChCondition(orgIds)})
                    ORDER BY collected_at DESC
                    LIMIT $limit
                    FORMAT JSONEachRow
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
                    WHERE organization_id IN (${orgIdsToChCondition(orgIds)})
                    ORDER BY sampled_at DESC
                    LIMIT $limit
                    FORMAT JSONEachRow
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
                    WHERE organization_id IN (${orgIdsToChCondition(orgIds)})
                    ORDER BY received_at DESC
                    LIMIT $limit
                    FORMAT JSONEachRow
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
                    WHERE organization_id IN (${orgIdsToChCondition(orgIds)})
                    ORDER BY collected_at DESC
                    LIMIT $limit
                    FORMAT JSONEachRow
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
        parseJsonEachRow(response.bodyAsText())
    }.getOrElse {
        logger.warn { "ClickHouse query error: ${it.message}" }
        null
    }
}
