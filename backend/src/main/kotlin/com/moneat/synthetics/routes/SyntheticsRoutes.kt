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

package com.moneat.synthetics.routes

import com.moneat.config.ClickHouseClient
import com.moneat.shared.models.Memberships
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
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
import org.koin.core.context.GlobalContext
import java.util.UUID

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

fun Route.syntheticsRoutes() {
    val syntheticsService = GlobalContext.get().get<SyntheticsService>()
    route("/v1") {
        authenticate("auth-jwt") {
            // --- Synthetic Test Results ---

            get("/synthetics/results") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respondText(
                        """{"results":[],"totalCount":0}""",
                        ContentType.Application.Json
                    )
                    return@get
                }

                val limit = parseLimit(call.parameters["limit"])
                val testType = call.parameters["test_type"]
                val status = call.parameters["status"]
                val probeDc = call.parameters["probe_dc"]

                val conditions = mutableListOf(
                    "organization_id IN (${orgIdsToChCondition(orgIds)})"
                )
                if (!testType.isNullOrBlank()) {
                    conditions.add(
                        "test_type = '${escapeSql(testType)}'"
                    )
                }
                if (!status.isNullOrBlank()) {
                    conditions.add("status = '${escapeSql(status)}'")
                }
                if (!probeDc.isNullOrBlank()) {
                    conditions.add(
                        "probe_dc = '${escapeSql(probeDc)}'"
                    )
                }

                val query = """
                    SELECT * FROM synthetic_results
                    WHERE ${conditions.joinToString(" AND ")}
                    ORDER BY timestamp DESC
                    LIMIT $limit
                    FORMAT JSONEachRow
                """.trimIndent()

                val countQuery = """
                    SELECT count() FROM synthetic_results
                    WHERE ${conditions.joinToString(" AND ")}
                """.trimIndent()

                val results = executeChQuery(query) ?: run {
                    call.respondText(
                        """{"results":[],"totalCount":0}""",
                        ContentType.Application.Json
                    )
                    return@get
                }

                val totalCount = runCatching {
                    val response = ClickHouseClient.execute(countQuery)
                    response.bodyAsText().trim().toLongOrNull() ?: 0L
                }.getOrElse { 0L }

                val responseJson = buildJsonObject {
                    put("results", JsonArray(results))
                    put("totalCount", JsonPrimitive(totalCount))
                }
                call.respondText(
                    json.encodeToString(
                        JsonObject.serializer(),
                        responseJson
                    ),
                    ContentType.Application.Json
                )
            }

            // --- Synthetic Test Management ---

            get("/synthetics/tests") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload
                    .getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "No organization")
                    )
                    return@get
                }
                val tests = syntheticsService.listTests(orgIds.first())
                call.respond(HttpStatusCode.OK, tests)
            }

            get("/synthetics/tests/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload
                    .getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "No organization")
                    )
                    return@get
                }
                val testId = try {
                    UUID.fromString(call.parameters["id"])
                } catch (_: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid ID")
                    )
                    return@get
                }
                val test = syntheticsService.getTest(
                    testId,
                    orgIds.first()
                )
                if (test == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Test not found")
                    )
                } else {
                    call.respond(HttpStatusCode.OK, test)
                }
            }

            get("/synthetics/tests/{id}/results") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload
                    .getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respondText(
                        """{"results":[],"totalCount":0}""",
                        ContentType.Application.Json
                    )
                    return@get
                }
                val testId = try {
                    UUID.fromString(call.parameters["id"])
                        .toString()
                } catch (_: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid ID")
                    )
                    return@get
                }
                val limit = parseLimit(call.parameters["limit"])

                val conditions = listOf(
                    "organization_id IN " +
                        "(${orgIdsToChCondition(orgIds)})",
                    "test_id = '${escapeSql(testId)}'"
                )
                val query = """
                    SELECT * FROM synthetic_results
                    WHERE ${conditions.joinToString(" AND ")}
                    ORDER BY timestamp DESC
                    LIMIT $limit
                    FORMAT JSONEachRow
                """.trimIndent()
                val countQuery = """
                    SELECT count() FROM synthetic_results
                    WHERE ${conditions.joinToString(" AND ")}
                """.trimIndent()

                val results = executeChQuery(query) ?: run {
                    call.respondText(
                        """{"results":[],"totalCount":0}""",
                        ContentType.Application.Json
                    )
                    return@get
                }
                val totalCount = runCatching {
                    val response = ClickHouseClient.execute(countQuery)
                    response.bodyAsText().trim().toLongOrNull() ?: 0L
                }.getOrElse { 0L }

                val responseJson = buildJsonObject {
                    put("results", JsonArray(results))
                    put("totalCount", JsonPrimitive(totalCount))
                }
                call.respondText(
                    json.encodeToString(
                        JsonObject.serializer(),
                        responseJson
                    ),
                    ContentType.Application.Json
                )
            }

            get("/synthetics/tests/{id}/summary") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload
                    .getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "No organization")
                    )
                    return@get
                }
                val testId = try {
                    UUID.fromString(call.parameters["id"])
                        .toString()
                } catch (_: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid ID")
                    )
                    return@get
                }
                val summary = syntheticsService.getTestSummary(
                    testId,
                    orgIds
                )
                if (summary == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "No data found")
                    )
                } else {
                    call.respond(HttpStatusCode.OK, summary)
                }
            }

            post("/synthetics/tests") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload
                    .getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "No organization")
                    )
                    return@post
                }
                val request = call.receive<CreateSyntheticTestRequest>()
                val test = try {
                    syntheticsService.createTest(
                        orgIds.first(),
                        request
                    )
                } catch (e: IllegalStateException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                    return@post
                }
                call.respond(HttpStatusCode.Created, test)
            }

            put("/synthetics/tests/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload
                    .getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "No organization")
                    )
                    return@put
                }
                val testId = try {
                    UUID.fromString(call.parameters["id"])
                } catch (_: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid ID")
                    )
                    return@put
                }
                val request = call.receive<UpdateSyntheticTestRequest>()
                val test = syntheticsService.updateTest(
                    testId,
                    orgIds.first(),
                    request
                )
                if (test == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Test not found")
                    )
                } else {
                    call.respond(HttpStatusCode.OK, test)
                }
            }

            delete("/synthetics/tests/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload
                    .getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "No organization")
                    )
                    return@delete
                }
                val testId = try {
                    UUID.fromString(call.parameters["id"])
                } catch (_: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid ID")
                    )
                    return@delete
                }
                val deleted = syntheticsService.deleteTest(
                    testId,
                    orgIds.first()
                )
                if (deleted) {
                    call.respond(HttpStatusCode.OK, mapOf("ok" to true))
                } else {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Test not found")
                    )
                }
            }

            post("/synthetics/tests/{id}/run") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload
                    .getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "No organization")
                    )
                    return@post
                }
                val testId = try {
                    UUID.fromString(call.parameters["id"])
                } catch (_: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid ID")
                    )
                    return@post
                }
                val started = syntheticsService.runTestNow(
                    testId,
                    orgIds.first()
                )
                if (!started) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Test not found")
                    )
                    return@post
                }
                call.respond(
                    HttpStatusCode.Accepted,
                    mapOf("ok" to true)
                )
            }

            // --- Synthetic Variables ---

            get("/synthetics/variables") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload
                    .getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "No organization")
                    )
                    return@get
                }
                val variables = syntheticsService.listVariables(
                    orgIds.first()
                )
                call.respond(HttpStatusCode.OK, variables)
            }

            post("/synthetics/variables") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload
                    .getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "No organization")
                    )
                    return@post
                }
                val request = call.receive<SyntheticVariableRequest>()
                val variable = syntheticsService.createVariable(
                    orgIds.first(),
                    request
                )
                call.respond(HttpStatusCode.Created, variable)
            }

            put("/synthetics/variables/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload
                    .getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "No organization")
                    )
                    return@put
                }
                val varId = call.parameters["id"]?.toIntOrNull()
                if (varId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid ID")
                    )
                    return@put
                }
                val request = call.receive<SyntheticVariableRequest>()
                val variable = syntheticsService.updateVariable(
                    varId,
                    orgIds.first(),
                    request
                )
                if (variable == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Variable not found")
                    )
                } else {
                    call.respond(HttpStatusCode.OK, variable)
                }
            }

            delete("/synthetics/variables/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload
                    .getClaim("userId").asInt()
                val orgIds = getOrgIdsForUser(userId)
                if (orgIds.isEmpty()) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "No organization")
                    )
                    return@delete
                }
                val varId = call.parameters["id"]?.toIntOrNull()
                if (varId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid ID")
                    )
                    return@delete
                }
                val deleted = syntheticsService.deleteVariable(
                    varId,
                    orgIds.first()
                )
                if (deleted) {
                    call.respond(HttpStatusCode.OK, mapOf("ok" to true))
                } else {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Variable not found")
                    )
                }
            }
        }
    }
}
