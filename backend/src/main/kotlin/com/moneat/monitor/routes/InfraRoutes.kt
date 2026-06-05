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
import com.moneat.monitor.models.InfrastructureMapSavedViewsResponse
import com.moneat.monitor.models.SaveInfrastructureMapViewRequest
import com.moneat.monitor.services.InfrastructureMapSavedViewService
import com.moneat.monitor.services.InvalidInfrastructureMapSavedViewException
import com.moneat.shared.models.Memberships
import com.moneat.utils.ErrorResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import com.moneat.utils.HttpConstants.HTTP_SUCCESS_MAX
import com.moneat.utils.HttpConstants.HTTP_SUCCESS_MIN

private val logger = KotlinLogging.logger {}

private val json = Json { ignoreUnknownKeys = true }

private const val DEFAULT_LIMIT = 100
private const val MAX_LIMIT = 500

private data class SavedViewScope(
    val userId: Int,
    val organizationId: Int
)

private data class ClickHouseFilter(
    val parameterName: String,
    val columnName: String
)

private data class ClickHouseListRouteConfig(
    val path: String,
    val responseKey: String,
    val tableName: String,
    val orderColumn: String,
    val filters: List<ClickHouseFilter> = emptyList()
)

private sealed class OrganizationScopeResult {
    data class Resolved(val organizationId: Int) : OrganizationScopeResult()
    object NoAccess : OrganizationScopeResult()
    object MissingContext : OrganizationScopeResult()
}

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

private suspend fun ApplicationCall.resolveSavedViewScope(): SavedViewScope? {
    val principal = principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val organizationIdClaim = principal.payload.getClaim("orgId").asInt()

    return when (val scope = resolveOrganizationId(userId, organizationIdClaim)) {
        is OrganizationScopeResult.Resolved -> SavedViewScope(userId, scope.organizationId)
        OrganizationScopeResult.NoAccess -> {
            respond(HttpStatusCode.Forbidden, ErrorResponse("No organization access"))
            null
        }
        OrganizationScopeResult.MissingContext -> {
            respond(HttpStatusCode.BadRequest, ErrorResponse("Organization context required"))
            null
        }
    }
}

private fun resolveOrganizationId(userId: Int, organizationIdClaim: Int?): OrganizationScopeResult {
    val organizationIds = getOrgIdsForUser(userId)
    if (organizationIds.isEmpty()) return OrganizationScopeResult.NoAccess
    if (organizationIdClaim != null) {
        return if (organizationIdClaim in organizationIds) {
            OrganizationScopeResult.Resolved(organizationIdClaim)
        } else {
            OrganizationScopeResult.NoAccess
        }
    }
    if (organizationIds.size == 1) return OrganizationScopeResult.Resolved(organizationIds.first())
    return OrganizationScopeResult.MissingContext
}

private fun parseLimit(limitParam: String?): Int {
    val limit = limitParam?.toIntOrNull() ?: DEFAULT_LIMIT
    return limit.coerceIn(1, MAX_LIMIT)
}

private fun ApplicationCall.currentUserOrganizationIds(): List<Int> {
    val principal = principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    return getOrgIdsForUser(userId)
}

/**
 * Validates that a filter value is safe for SQL interpolation (alphanumeric, hyphen, underscore, dot only).
 * Returns the value if safe, null otherwise to prevent SQL injection.
 */
private fun safeFilterValue(value: String?): String? {
    if (value == null || value.isEmpty()) return null
    val safePattern = Regex("^[a-zA-Z0-9_\\-.]+$")
    return if (safePattern.matches(value)) value else null
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

fun Route.infraRoutes(
    infrastructureMapSavedViewService: InfrastructureMapSavedViewService = InfrastructureMapSavedViewService(),
) {
    route("/v1") {
        authenticate("auth-jwt") {
            registerInfrastructureMapSavedViewRoutes(infrastructureMapSavedViewService)
            registerClickHouseListRoutes()
        }
    }
}

private fun Route.registerInfrastructureMapSavedViewRoutes(
    infrastructureMapSavedViewService: InfrastructureMapSavedViewService
) {
    get("/infra/map/saved-views") {
        call.listSavedViews(infrastructureMapSavedViewService)
    }

    post("/infra/map/saved-views") {
        call.saveSavedView(infrastructureMapSavedViewService)
    }

    delete("/infra/map/saved-views/{id}") {
        call.deleteSavedView(infrastructureMapSavedViewService)
    }
}

private suspend fun ApplicationCall.listSavedViews(
    infrastructureMapSavedViewService: InfrastructureMapSavedViewService
) {
    val scope = resolveSavedViewScope() ?: return
    val views = infrastructureMapSavedViewService.listViews(
        organizationId = scope.organizationId,
        userId = scope.userId
    )
    respond(HttpStatusCode.OK, InfrastructureMapSavedViewsResponse(views))
}

private suspend fun ApplicationCall.saveSavedView(
    infrastructureMapSavedViewService: InfrastructureMapSavedViewService
) {
    val scope = resolveSavedViewScope() ?: return
    val request = receive<SaveInfrastructureMapViewRequest>()
    val result = try {
        infrastructureMapSavedViewService.saveView(
            organizationId = scope.organizationId,
            userId = scope.userId,
            request = request
        )
    } catch (error: InvalidInfrastructureMapSavedViewException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Invalid saved view"))
        return
    }

    val statusCode = if (result.created) HttpStatusCode.Created else HttpStatusCode.OK
    respond(statusCode, result.view)
}

private suspend fun ApplicationCall.deleteSavedView(
    infrastructureMapSavedViewService: InfrastructureMapSavedViewService
) {
    val scope = resolveSavedViewScope() ?: return
    val viewId = parameters["id"]?.toIntOrNull()
    if (viewId == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid saved view ID"))
        return
    }

    val deleted = infrastructureMapSavedViewService.deleteView(
        organizationId = scope.organizationId,
        userId = scope.userId,
        viewId = viewId
    )
    if (!deleted) {
        respond(HttpStatusCode.NotFound, ErrorResponse("Saved view not found"))
        return
    }
    respond(HttpStatusCode.NoContent)
}

private fun Route.registerClickHouseListRoutes() {
    clickHouseListRoutes.forEach { config ->
        registerClickHouseListRoute(config)
    }
}

private fun Route.registerClickHouseListRoute(config: ClickHouseListRouteConfig) {
    get(config.path) {
        call.respondClickHouseList(config)
    }
}

private suspend fun ApplicationCall.respondClickHouseList(config: ClickHouseListRouteConfig) {
    val orgIds = currentUserOrganizationIds()
    val payload = if (orgIds.isEmpty()) {
        emptyList()
    } else {
        val query = buildClickHouseListQuery(config, orgIds, this)
        executeChQuery(query) ?: emptyList()
    }
    respond(HttpStatusCode.OK, mapOf(config.responseKey to payload))
}

private fun buildClickHouseListQuery(
    config: ClickHouseListRouteConfig,
    orgIds: List<Int>,
    call: ApplicationCall
): String {
    val limit = parseLimit(call.parameters["limit"])
    val conditions = mutableListOf("organization_id IN (${orgIdsToChCondition(orgIds)})")
    config.filters.forEach { filter ->
        safeFilterValue(call.parameters[filter.parameterName])?.let { value ->
            conditions.add("${filter.columnName} = '$value'")
        }
    }
    return """
        SELECT * FROM ${config.tableName}
        WHERE ${conditions.joinToString(" AND ")}
        ORDER BY ${config.orderColumn} DESC
        LIMIT $limit
        FORMAT JSONEachRow
    """.trimIndent()
}

private val clickHouseListRoutes = listOf(
    ClickHouseListRouteConfig(
        path = "/infra/events",
        responseKey = "events",
        tableName = "infra_events",
        orderColumn = "timestamp",
        filters = listOf(
            ClickHouseFilter(parameterName = "host", columnName = "host"),
            ClickHouseFilter(parameterName = "alert_type", columnName = "alert_type")
        )
    ),
    ClickHouseListRouteConfig(
        path = "/infra/service-checks",
        responseKey = "serviceChecks",
        tableName = "service_checks",
        orderColumn = "timestamp"
    ),
    ClickHouseListRouteConfig(
        path = "/infra/processes",
        responseKey = "processes",
        tableName = "processes",
        orderColumn = "timestamp",
        filters = listOf(ClickHouseFilter(parameterName = "host", columnName = "host"))
    ),
    ClickHouseListRouteConfig(
        path = "/infra/containers",
        responseKey = "containers",
        tableName = "containers",
        orderColumn = "timestamp",
        filters = listOf(ClickHouseFilter(parameterName = "host", columnName = "host"))
    ),
    ClickHouseListRouteConfig(
        path = "/infra/connections",
        responseKey = "connections",
        tableName = "network_connections",
        orderColumn = "timestamp"
    ),
    ClickHouseListRouteConfig(
        path = "/infra/k8s-resources",
        responseKey = "resources",
        tableName = "k8s_resources",
        orderColumn = "collected_at",
        filters = listOf(ClickHouseFilter(parameterName = "resource_type", columnName = "resource_type"))
    ),
    ClickHouseListRouteConfig(
        path = "/infra/dbm/queries",
        responseKey = "queries",
        tableName = "dbm_queries",
        orderColumn = "timestamp"
    ),
    ClickHouseListRouteConfig(
        path = "/infra/debugger/logs",
        responseKey = "logs",
        tableName = "debugger_logs",
        orderColumn = "timestamp"
    ),
    ClickHouseListRouteConfig(
        path = "/infra/debugger/diagnostics",
        responseKey = "diagnostics",
        tableName = "debugger_diagnostics",
        orderColumn = "timestamp"
    ),
    ClickHouseListRouteConfig(
        path = "/infra/sbom",
        responseKey = "packages",
        tableName = "sbom_packages",
        orderColumn = "collected_at"
    ),
    ClickHouseListRouteConfig(
        path = "/network-devices",
        responseKey = "devices",
        tableName = "ndm_devices",
        orderColumn = "collected_at"
    ),
    ClickHouseListRouteConfig(
        path = "/network-devices/flows",
        responseKey = "flows",
        tableName = "ndm_flows",
        orderColumn = "sampled_at"
    ),
    ClickHouseListRouteConfig(
        path = "/network-devices/traps",
        responseKey = "traps",
        tableName = "ndm_traps",
        orderColumn = "received_at"
    ),
    ClickHouseListRouteConfig(
        path = "/network-devices/paths",
        responseKey = "paths",
        tableName = "network_paths",
        orderColumn = "collected_at"
    )
)

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
