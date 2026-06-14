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

package com.moneat.dashboards.routes

import com.moneat.auth.currentOrgContextOrNull
import com.moneat.dashboards.models.BatchQueryResult
import com.moneat.dashboards.models.BatchQueryResultMetadata
import com.moneat.dashboards.models.CreateCustomDataSourceRequest
import com.moneat.dashboards.models.CreateDashboardAlertRequest
import com.moneat.dashboards.models.CreateDashboardRequest
import com.moneat.dashboards.models.CreateFolderRequest
import com.moneat.dashboards.models.CreateWidgetRequest
import com.moneat.dashboards.models.CustomDataSourceQueryRequest
import com.moneat.dashboards.models.CustomDataSourceType
import com.moneat.dashboards.models.DashboardImportResult
import com.moneat.dashboards.models.Dashboards
import com.moneat.dashboards.models.ExecuteBatchQueryRequest
import com.moneat.dashboards.models.ExecuteQueryRequest
import com.moneat.dashboards.models.ImportDashboardRequest
import com.moneat.dashboards.models.InstantiateDashboardTemplateRequest
import com.moneat.dashboards.models.MoveToFolderRequest
import com.moneat.dashboards.models.QueryDsl
import com.moneat.dashboards.models.TestConnectionRequest
import com.moneat.dashboards.models.TestConnectionResult
import com.moneat.dashboards.models.UpdateCustomDataSourceRequest
import com.moneat.dashboards.models.UpdateDashboardAlertRequest
import com.moneat.dashboards.models.UpdateDashboardRequest
import com.moneat.dashboards.models.UpdateFolderRequest
import com.moneat.dashboards.services.CustomDashboardService
import com.moneat.dashboards.services.CustomDataSourceExecutor
import com.moneat.dashboards.services.CustomDataSourceService
import com.moneat.dashboards.services.handlers.withConnectionOptions
import com.moneat.dashboards.services.DashboardAlertService
import com.moneat.dashboards.services.DashboardQueryEngine
import com.moneat.dashboards.services.DashboardTemplateCatalogService
import com.moneat.dashboards.translation.DashboardTranslator
import com.moneat.dashboards.translation.DataDogTranslator
import com.moneat.dashboards.translation.GrafanaTranslator
import com.moneat.plugins.getDemoEpochMs
import com.moneat.shared.models.Projects
import com.moneat.shared.services.ProjectIdResolver
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.shared.services.toUuidOrNull
import com.moneat.utils.ErrorResponse
import com.moneat.utils.suspendRunCatching
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.context.GlobalContext

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

private const val DEFAULT_RETENTION_DAYS = 90
private const val MAX_QUERIES_PER_REQUEST = 10
private const val INACCESSIBLE_PROJECT_ID = -2L

private const val AUTH_JWT = "auth-jwt"
private const val ERR_NO_ORGANIZATION = "No organization found"
private const val ERR_INVALID_DASHBOARD_ID = "Invalid dashboard ID"
private const val ERR_DASHBOARD_NOT_FOUND = "Dashboard not found"
private const val ERR_INVALID_FOLDER_ID = "Invalid folder ID"
private const val ERR_FOLDER_NOT_FOUND = "Folder not found"
private const val ERR_INVALID_ALERT_ID = "Invalid alert ID"
private const val ERR_ALERT_NOT_FOUND = "Alert not found"
private const val ERR_DATA_SOURCE_NOT_FOUND = "Data source not found"
private const val ERR_UNKNOWN_SOURCE_TYPE = "Unknown source type"
private const val ERR_INVALID_DATA_SOURCE_ID = "Invalid data source ID"
private const val ERR_INVALID_QUERY = "Invalid query"
private const val ERR_FAILED_DECRYPT_CREDENTIALS = "Failed to decrypt credentials"

private fun currentOrgIdFromPrincipal(userId: Int, principal: JWTPrincipal): Long? =
    principal.currentOrgContextOrNull()
        ?.takeIf { it.userId == userId }
        ?.orgId
        ?.toLong()

private data class DashboardScope(
    val projectId: Long?
)

private data class DashboardQueryRouteDependencies(
    val dashboardService: CustomDashboardService,
    val queryEngine: DashboardQueryEngine,
    val retentionPolicyService: RetentionPolicyService,
    val dataSourceService: CustomDataSourceService,
    val dataSourceExecutor: CustomDataSourceExecutor,
    val projectIdResolver: ProjectIdResolver,
)

private data class DashboardQueryContext(
    val orgId: Long,
    val projectId: Long,
    val demoEpochMs: Long?,
    val retentionDays: Int,
)

private class DashboardQueryRouteException(
    val status: HttpStatusCode,
    message: String,
) : IllegalArgumentException(message)

private fun hasProjectAccess(orgId: Long, projectId: Long): Boolean {
    return transaction {
        Projects.selectAll()
            .where { (Projects.id eq projectId) and (Projects.organization_id eq orgId.toInt()) }
            .firstOrNull() != null
    }
}

private fun getDashboardScope(dashboardId: Long, orgId: Long): DashboardScope? {
    return transaction {
        Dashboards.selectAll()
            .where { (Dashboards.id eq dashboardId) and (Dashboards.orgId eq orgId) }
            .firstOrNull()
            ?.let { row ->
                DashboardScope(
                    projectId = row[Dashboards.projectId]
                )
            }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.resolveDashboardRouteId(
    dashboardService: CustomDashboardService,
    orgId: Long
): Long? {
    val resourceId = call.parameters["id"]?.takeIf(dashboardService::isValidResourceId)
    if (resourceId == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_INVALID_DASHBOARD_ID))
        return null
    }
    val dashboardId = dashboardService.resolveDashboardId(resourceId, orgId)
    if (dashboardId == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DASHBOARD_NOT_FOUND))
    }
    return dashboardId
}

private suspend fun io.ktor.server.routing.RoutingContext.resolveFolderRouteId(
    dashboardService: CustomDashboardService,
    orgId: Long
): Long? {
    val resourceId = call.parameters["folderId"]?.takeIf(dashboardService::isValidResourceId)
    if (resourceId == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_INVALID_FOLDER_ID))
        return null
    }
    val folderId = dashboardService.resolveFolderId(resourceId, orgId)
    if (folderId == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_FOLDER_NOT_FOUND))
    }
    return folderId
}

private suspend fun io.ktor.server.routing.RoutingContext.resolveDataSourceRouteId(
    dataSourceService: CustomDataSourceService,
    orgId: Long
): Long? {
    val resourceId = call.parameters["id"]?.takeIf(dataSourceService::isValidResourceId)
    if (resourceId == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_INVALID_DATA_SOURCE_ID))
        return null
    }
    val dataSourceId = dataSourceService.resolveDataSourceId(resourceId, orgId)
    if (dataSourceId == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DATA_SOURCE_NOT_FOUND))
    }
    return dataSourceId
}

private suspend fun io.ktor.server.routing.RoutingContext.resolveAlertRouteId(
    dashboardAlertService: DashboardAlertService,
    dashboardId: Long,
    orgId: Long
): Long? {
    val resourceId = call.parameters["alertId"]?.takeIf(dashboardAlertService::isValidResourceId)
    if (resourceId == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_INVALID_ALERT_ID))
        return null
    }
    val alertId = dashboardAlertService.resolveAlertId(resourceId, dashboardId, orgId)
    if (alertId == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_ALERT_NOT_FOUND))
    }
    return alertId
}

private suspend fun io.ktor.server.routing.RoutingContext.handleListDashboards(
    dashboardService: CustomDashboardService,
    projectIdResolver: ProjectIdResolver,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val projectId = call.request.queryParameters["projectId"]?.let(projectIdResolver::resolve)
    val dashboards = dashboardService.listDashboards(orgId, projectId, userId)
    call.respond(dashboards)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleCreateDashboard(
    dashboardService: CustomDashboardService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val request = call.receive<CreateDashboardRequest>()
    try {
        val dashboard = dashboardService.createDashboard(orgId, userId.toLong(), request)
        call.respond(HttpStatusCode.Created, dashboard)
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid dashboard request"))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleListFolders(
    dashboardService: CustomDashboardService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val folders = dashboardService.listFolders(orgId)
    call.respond(folders)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleCreateFolder(
    dashboardService: CustomDashboardService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val request = call.receive<CreateFolderRequest>()
    val folder = dashboardService.createFolder(orgId, request)
    call.respond(HttpStatusCode.Created, folder)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleUpdateFolder(
    dashboardService: CustomDashboardService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val folderId = resolveFolderRouteId(dashboardService, orgId) ?: return
    val request = call.receive<UpdateFolderRequest>()
    val folder = dashboardService.updateFolder(folderId, orgId, request)
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_FOLDER_NOT_FOUND))
    call.respond(folder)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleDeleteFolder(
    dashboardService: CustomDashboardService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val folderId = resolveFolderRouteId(dashboardService, orgId) ?: return
    if (dashboardService.deleteFolder(folderId, orgId)) {
        call.respond(HttpStatusCode.NoContent, "")
    } else {
        call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_FOLDER_NOT_FOUND))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleGetDashboard(
    dashboardService: CustomDashboardService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = resolveDashboardRouteId(dashboardService, orgId) ?: return
    val dashboard = dashboardService.getDashboard(id, orgId, userId)
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DASHBOARD_NOT_FOUND))
    call.respond(dashboard)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleToggleFavorite(
    dashboardService: CustomDashboardService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = resolveDashboardRouteId(dashboardService, orgId) ?: return
    val isFavorited = dashboardService.toggleFavorite(userId, id, orgId)
    call.respond(HttpStatusCode.OK, mapOf("is_favorited" to isFavorited))
}

private suspend fun io.ktor.server.routing.RoutingContext.handleMoveDashboardToFolder(
    dashboardService: CustomDashboardService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = resolveDashboardRouteId(dashboardService, orgId) ?: return
    val request = call.receive<MoveToFolderRequest>()
    val folderId = request.folderId?.let {
        if (!dashboardService.isValidResourceId(it)) {
            return call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_INVALID_FOLDER_ID))
        }
        dashboardService.resolveFolderId(it, orgId)
            ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_FOLDER_NOT_FOUND))
    }
    if (dashboardService.moveDashboardToFolder(id, orgId, folderId)) {
        call.respond(HttpStatusCode.OK, mapOf("folder_id" to request.folderId))
    } else {
        call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DASHBOARD_NOT_FOUND))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleUpdateDashboard(
    dashboardService: CustomDashboardService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = resolveDashboardRouteId(dashboardService, orgId) ?: return
    val request = call.receive<UpdateDashboardRequest>()
    try {
        val updated = dashboardService.updateDashboard(id, orgId, request)
            ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DASHBOARD_NOT_FOUND))
        call.respond(updated)
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid dashboard request"))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleDeleteDashboard(
    dashboardService: CustomDashboardService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = resolveDashboardRouteId(dashboardService, orgId) ?: return
    if (dashboardService.deleteDashboard(id, orgId)) {
        call.respond(HttpStatusCode.NoContent, "")
    } else {
        call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DASHBOARD_NOT_FOUND))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleDuplicateDashboard(
    dashboardService: CustomDashboardService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = resolveDashboardRouteId(dashboardService, orgId) ?: return
    val duplicate = dashboardService.duplicateDashboard(id, orgId, userId.toLong())
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DASHBOARD_NOT_FOUND))
    call.respond(HttpStatusCode.Created, duplicate)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleSetDefaultDashboard(
    dashboardService: CustomDashboardService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = resolveDashboardRouteId(dashboardService, orgId) ?: return
    if (dashboardService.setDefaultDashboard(id, orgId)) {
        call.respond(HttpStatusCode.OK, mapOf("is_default" to true))
    } else {
        call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DASHBOARD_NOT_FOUND))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleDashboardQuery(
    dependencies: DashboardQueryRouteDependencies,
) {
    val queryContext = resolveDashboardQueryContext(dependencies) ?: return
    val request = call.receive<ExecuteQueryRequest>()
    val requestedTimeRange = request.timeRange
    val withTimeRange = if (requestedTimeRange != null) {
        request.queryConfig.copy(timeRange = requestedTimeRange)
    } else {
        request.queryConfig
    }
    val effectiveQuery = dependencies.queryEngine.resolveTemplateDataSource(
        dependencies.queryEngine.applyVariables(withTimeRange, request.variables),
        queryContext.orgId,
        dependencies.dataSourceService
    )

    try {
        call.respond(executeDashboardQuery(effectiveQuery, queryContext, dependencies))
    } catch (e: DashboardQueryRouteException) {
        call.respond(e.status, ErrorResponse(e.message ?: ERR_INVALID_QUERY))
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: ERR_INVALID_QUERY))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.resolveDashboardQueryContext(
    dependencies: DashboardQueryRouteDependencies,
): DashboardQueryContext? {
    val principal = call.principal<JWTPrincipal>() ?: return null
    val userId = principal.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return respondForbidden(ERR_NO_ORGANIZATION)
    val dashboardId = resolveDashboardRouteId(dependencies.dashboardService, orgId) ?: return null
    val dashboardScope = getDashboardScope(dashboardId, orgId)
        ?: return respondNotFound(ERR_DASHBOARD_NOT_FOUND)
    val demoEpochMs = call.getDemoEpochMs()
    val projectId = resolveQueryProjectId(orgId, demoEpochMs != null, dependencies.projectIdResolver) ?: return null
    if (!validateQueryProjectScope(orgId, projectId, dashboardScope, demoEpochMs != null)) return null
    return DashboardQueryContext(
        orgId = orgId,
        projectId = projectId,
        demoEpochMs = demoEpochMs,
        retentionDays = resolveRetentionDays(projectId, demoEpochMs, dependencies.retentionPolicyService),
    )
}

private suspend fun io.ktor.server.routing.RoutingContext.resolveQueryProjectId(
    orgId: Long,
    isDemoUser: Boolean,
    projectIdResolver: ProjectIdResolver,
): Long? {
    if (isDemoUser) return -1L
    val resourceId = call.request.queryParameters["projectId"]
        ?: return respondBadRequest("projectId query parameter required")
    if (resourceId.toUuidOrNull() == null) {
        return respondBadRequest("projectId query parameter required")
    }
    return projectIdResolver.resolve(resourceId, orgId)
        ?: INACCESSIBLE_PROJECT_ID
}

private suspend fun io.ktor.server.routing.RoutingContext.validateQueryProjectScope(
    orgId: Long,
    projectId: Long,
    dashboardScope: DashboardScope,
    isDemoUser: Boolean,
): Boolean {
    if (isDemoUser) return true
    if (!hasProjectAccess(orgId, projectId)) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Project access denied"))
        return false
    }
    if (dashboardScope.projectId != null && dashboardScope.projectId != projectId) {
        call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("Dashboard is scoped to project ${dashboardScope.projectId}")
        )
        return false
    }
    return true
}

private suspend fun resolveRetentionDays(
    projectId: Long,
    demoEpochMs: Long?,
    retentionPolicyService: RetentionPolicyService,
): Int =
    if (demoEpochMs != null) {
        DEFAULT_RETENTION_DAYS
    } else {
        retentionPolicyService.getRetentionDaysForProject(projectId) ?: DEFAULT_RETENTION_DAYS
    }

private suspend fun executeDashboardQuery(
    effectiveQuery: QueryDsl,
    queryContext: DashboardQueryContext,
    dependencies: DashboardQueryRouteDependencies,
): List<Map<String, kotlinx.serialization.json.JsonElement>> {
    if (!dependencies.queryEngine.isCustomDataSource(effectiveQuery.dataSource)) {
        return dependencies.queryEngine.executeQuery(
            effectiveQuery,
            queryContext.projectId,
            queryContext.demoEpochMs,
            queryContext.retentionDays,
            queryContext.orgId
        )
    }
    return executeCustomDashboardQuery(effectiveQuery, queryContext.orgId, dependencies)
}

private suspend fun executeCustomDashboardQuery(
    effectiveQuery: QueryDsl,
    orgId: Long,
    dependencies: DashboardQueryRouteDependencies,
): List<Map<String, kotlinx.serialization.json.JsonElement>> {
    val sourceResourceId = dependencies.queryEngine.parseCustomDataSourceId(effectiveQuery.dataSource)
        ?: throw DashboardQueryRouteException(HttpStatusCode.BadRequest, "Invalid custom data source ID")
    val sourceId = dependencies.dataSourceService.resolveDataSourceId(sourceResourceId, orgId)
        ?: throw DashboardQueryRouteException(HttpStatusCode.NotFound, ERR_DATA_SOURCE_NOT_FOUND)
    val source = dependencies.dataSourceService.getDataSource(sourceId, orgId)
        ?: throw DashboardQueryRouteException(HttpStatusCode.NotFound, ERR_DATA_SOURCE_NOT_FOUND)
    val creds = dependencies.dataSourceService.getDecryptedCredentials(sourceId, orgId)
        ?: throw DashboardQueryRouteException(HttpStatusCode.InternalServerError, ERR_FAILED_DECRYPT_CREDENTIALS)
    val sourceType = CustomDataSourceType.fromString(source.sourceType)
        ?: throw DashboardQueryRouteException(HttpStatusCode.BadRequest, ERR_UNKNOWN_SOURCE_TYPE)
    val rawQuery = effectiveQuery.rawQuery
        ?: throw DashboardQueryRouteException(
            HttpStatusCode.BadRequest,
            "Custom data source queries require a rawQuery"
        )
    return dependencies.dataSourceExecutor.executeQuery(
        sourceId, sourceType, source.host, source.port,
        source.databaseName, creds.withConnectionOptions(source.extraConfig),
        rawQuery, effectiveQuery.limit, effectiveQuery.timeRange,
    )
}

private suspend fun io.ktor.server.routing.RoutingContext.respondBadRequest(message: String): Nothing? {
    call.respond(HttpStatusCode.BadRequest, ErrorResponse(message))
    return null
}

private suspend fun io.ktor.server.routing.RoutingContext.respondForbidden(message: String): Nothing? {
    call.respond(HttpStatusCode.Forbidden, ErrorResponse(message))
    return null
}

private suspend fun io.ktor.server.routing.RoutingContext.respondNotFound(message: String): Nothing? {
    call.respond(HttpStatusCode.NotFound, ErrorResponse(message))
    return null
}

private suspend fun executeSingleQuery(
    effectiveQuery: QueryDsl,
    queryContext: DashboardQueryContext,
    dependencies: DashboardQueryRouteDependencies,
): List<Map<String, kotlinx.serialization.json.JsonElement>>? {
    if (!dependencies.queryEngine.isCustomDataSource(effectiveQuery.dataSource)) {
        return dependencies.queryEngine.executeQuery(
            effectiveQuery,
            queryContext.projectId,
            queryContext.demoEpochMs,
            queryContext.retentionDays,
            queryContext.orgId
        )
    }
    val sourceResourceId = dependencies.queryEngine.parseCustomDataSourceId(effectiveQuery.dataSource) ?: return null
    val sourceId = dependencies.dataSourceService.resolveDataSourceId(
        sourceResourceId,
        queryContext.orgId
    ) ?: return null
    val source = dependencies.dataSourceService.getDataSource(sourceId, queryContext.orgId) ?: return null
    val creds = dependencies.dataSourceService.getDecryptedCredentials(sourceId, queryContext.orgId) ?: return null
    val sourceType = CustomDataSourceType.fromString(source.sourceType) ?: return null
    val rawQuery = effectiveQuery.rawQuery ?: return null
    return dependencies.dataSourceExecutor.executeQuery(
        sourceId, sourceType, source.host, source.port,
        source.databaseName, creds.withConnectionOptions(source.extraConfig),
        rawQuery, effectiveQuery.limit, effectiveQuery.timeRange,
    )
}

private suspend fun io.ktor.server.routing.RoutingContext.handleBatchDashboardQuery(
    dependencies: DashboardQueryRouteDependencies,
) {
    val queryContext = resolveDashboardQueryContext(dependencies) ?: return
    val request = call.receive<ExecuteBatchQueryRequest>()
    if (request.queries.size > MAX_QUERIES_PER_REQUEST) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Maximum 10 queries per batch"))
        return
    }

    val results = mutableMapOf<String, List<Map<String, kotlinx.serialization.json.JsonElement>>>()
    val metadata = mutableMapOf<String, BatchQueryResultMetadata>()

    for ((index, query) in request.queries.withIndex()) {
        val refId = ('A' + index).toString()
        val originalRefId = query.refId
        metadata[refId] = BatchQueryResultMetadata(originalRefId = originalRefId, queryIndex = index)
        val requestedTimeRange = request.timeRange
        val withTimeRange = if (requestedTimeRange != null) {
            query.copy(timeRange = requestedTimeRange)
        } else {
            query
        }
        val effectiveQuery = dependencies.queryEngine.resolveTemplateDataSource(
            dependencies.queryEngine.applyVariables(withTimeRange, request.variables),
            queryContext.orgId,
            dependencies.dataSourceService
        )
        suspendRunCatching {
            executeSingleQuery(
                effectiveQuery,
                queryContext,
                dependencies,
            )?.let { results[refId] = it }
        }.getOrElse { e ->
            logger.warn(e) { "Batch query ${originalRefId ?: refId} failed" }
            results[refId] = emptyList()
        }
    }

    call.respond(BatchQueryResult(results, metadata))
}

private suspend fun io.ktor.server.routing.RoutingContext.handleVariablesResolve(
    dashboardService: CustomDashboardService,
    dataSourceService: CustomDataSourceService,
    dataSourceExecutor: CustomDataSourceExecutor,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = resolveDashboardRouteId(dashboardService, orgId) ?: return
    getDashboardScope(id, orgId)
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DASHBOARD_NOT_FOUND))

    val dashboard = dashboardService.getDashboard(id, orgId)
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DASHBOARD_NOT_FOUND))

    val variables = dashboard.variables
    val currentValues = call.receive<Map<String, String>>()

    val sources = dataSourceService.listDataSources(orgId)

    val resolved = mutableMapOf<String, List<String>>()
    for (v in variables) {
        val query = v.query ?: continue
        if (!query.startsWith("label_values(")) continue
        val requiredSourceType = DashboardQueryEngine.templateDataSourceType(v.datasource)
        val source = sources.firstOrNull {
            it.enabled && it.sourceType.equals(requiredSourceType, ignoreCase = true)
        } ?: continue

        // Substitute variable references in the query
        var substituted = query
        for ((name, value) in currentValues) {
            substituted = substituted
                .replace("\${$name}", value)
                .replace("\$$name", value)
        }

        val creds = dataSourceService.getDecryptedCredentials(source.numericId, orgId) ?: continue
        val sourceType = CustomDataSourceType.fromString(source.sourceType)
            ?: continue
        val options = dataSourceExecutor.executeLabelValuesQuery(
            sourceType,
            source.host,
            source.port,
            creds.withConnectionOptions(source.extraConfig),
            substituted,
        )
        if (options.isNotEmpty()) {
            resolved[v.name] = options
        }
    }

    call.respond(resolved)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleImportDashboard(
    dashboardService: CustomDashboardService,
    dataDogTranslator: DataDogTranslator,
    grafanaTranslator: GrafanaTranslator,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))

    val request = call.receive<ImportDashboardRequest>()
    val jsonParseResult = suspendRunCatching {
        json.parseToJsonElement(request.json) as JsonObject
    }
    if (jsonParseResult.isFailure) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid JSON"))
        return
    }
    val jsonObj = jsonParseResult.getOrThrow()

    val translator: DashboardTranslator = when (request.format.lowercase()) {
        "datadog" -> dataDogTranslator
        "grafana" -> grafanaTranslator
        else -> return call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("Unsupported format: ${request.format}. Use 'datadog' or 'grafana'")
        )
    }

    suspendRunCatching {
        val importResult = translator.import(jsonObj)
        val createRequest = CreateDashboardRequest(
            title = importResult.dashboard.title,
            description = importResult.dashboard.description,
            layoutType = importResult.dashboard.layoutType,
            variables = importResult.variables,
            widgets = importResult.dashboard.widgets.map { w ->
                CreateWidgetRequest(
                    title = w.title,
                    widgetType = w.widgetType,
                    gridX = w.gridX,
                    gridY = w.gridY,
                    gridW = w.gridW,
                    gridH = w.gridH,
                    queryConfigs = w.queryConfigs,
                    displayConfig = w.displayConfig,
                    sortOrder = w.sortOrder
                )
            }
        )
        val created = dashboardService.createDashboard(orgId, userId.toLong(), createRequest)
        call.respond(
            HttpStatusCode.Created,
            DashboardImportResult(created, importResult.warnings, importResult.variables)
        )
    }.getOrElse { e ->
        logger.error(e) { "Failed to import dashboard" }
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Failed to import: ${e.message}"))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleExportDashboard(
    dashboardService: CustomDashboardService,
    dataDogTranslator: DataDogTranslator,
    grafanaTranslator: GrafanaTranslator,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = resolveDashboardRouteId(dashboardService, orgId) ?: return
    val format = call.parameters["format"]
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Format required"))
    val dashboard = dashboardService.getDashboard(id, orgId)
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DASHBOARD_NOT_FOUND))

    when (format.lowercase()) {
        "moneat" -> call.respond(dashboard)
        "datadog" -> call.respond(dataDogTranslator.export(dashboard))
        "grafana" -> call.respond(grafanaTranslator.export(dashboard))
        else -> call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("Unsupported format: $format. Use 'moneat', 'datadog', or 'grafana'")
        )
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleListAlerts(
    dashboardService: CustomDashboardService,
    dashboardAlertService: DashboardAlertService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = resolveDashboardRouteId(dashboardService, orgId) ?: return
    call.respond(dashboardAlertService.listAlerts(id, orgId))
}

private suspend fun io.ktor.server.routing.RoutingContext.handleCreateAlert(
    dashboardService: CustomDashboardService,
    dashboardAlertService: DashboardAlertService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = resolveDashboardRouteId(dashboardService, orgId) ?: return
    val request = call.receive<CreateDashboardAlertRequest>()
    try {
        val alert = dashboardAlertService.createAlert(id, orgId, userId.toLong(), request)
        call.respond(HttpStatusCode.Created, alert)
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid request"))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleUpdateAlert(
    dashboardService: CustomDashboardService,
    dashboardAlertService: DashboardAlertService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = resolveDashboardRouteId(dashboardService, orgId) ?: return
    val alertId = resolveAlertRouteId(dashboardAlertService, id, orgId) ?: return
    val request = receiveUpdateAlertRequest()
    try {
        val updated = dashboardAlertService.updateAlert(alertId, id, orgId, request)
            ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_ALERT_NOT_FOUND))
        call.respond(updated)
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid request"))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.receiveUpdateAlertRequest(): UpdateDashboardAlertRequest {
    val body = call.receiveText()
    return suspendRunCatching {
        val request = json.decodeFromString<UpdateDashboardAlertRequest>(body)
        val hasWarningThreshold = json.parseToJsonElement(body).jsonObject.containsKey("warning_threshold")
        request.copy(warningThresholdProvided = hasWarningThreshold)
    }.getOrElse { e ->
        throw BadRequestException("Invalid alert update payload", e)
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleDeleteAlert(
    dashboardService: CustomDashboardService,
    dashboardAlertService: DashboardAlertService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = resolveDashboardRouteId(dashboardService, orgId) ?: return
    val alertId = resolveAlertRouteId(dashboardAlertService, id, orgId) ?: return
    if (dashboardAlertService.deleteAlert(alertId, id, orgId)) {
        call.respond(HttpStatusCode.NoContent, "")
    } else {
        call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_ALERT_NOT_FOUND))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleGetAvailableDataSources(
    dataSourceService: CustomDataSourceService,
    queryEngine: DashboardQueryEngine,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
    if (orgId != null) {
        val customSources = dataSourceService.listDataSources(orgId)
        call.respond(queryEngine.getDataSources(customSources))
    } else {
        call.respond(queryEngine.getDataSources())
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleListDashboardTemplates(
    templateCatalogService: DashboardTemplateCatalogService,
) {
    call.respond(templateCatalogService.listTemplates())
}

private suspend fun io.ktor.server.routing.RoutingContext.handleGetDashboardTemplate(
    templateCatalogService: DashboardTemplateCatalogService,
) {
    val templateId = call.parameters["templateId"]
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Template ID required"))
    val template = templateCatalogService.getTemplate(templateId)
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse("Template not found"))
    call.respond(template)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleInstantiateDashboardTemplate(
    dashboardService: CustomDashboardService,
    templateCatalogService: DashboardTemplateCatalogService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val templateId = call.parameters["templateId"]
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Template ID required"))
    val template = templateCatalogService.getTemplate(templateId)
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse("Template not found"))
    val request = receiveInstantiateDashboardTemplateRequest()
    val createRequest = template.dashboard.copy(
        projectId = request.projectId ?: template.dashboard.projectId,
        folderId = request.folderId ?: template.dashboard.folderId,
    )
    val dashboard = dashboardService.createDashboard(orgId, userId.toLong(), createRequest)
    call.respond(HttpStatusCode.Created, dashboard)
}

private suspend fun io.ktor.server.routing.RoutingContext.receiveInstantiateDashboardTemplateRequest():
    InstantiateDashboardTemplateRequest {
    val body = call.receiveText()
    if (body.isBlank()) return InstantiateDashboardTemplateRequest()
    return suspendRunCatching {
        json.decodeFromString<InstantiateDashboardTemplateRequest>(body)
    }.getOrElse { e ->
        throw BadRequestException("Invalid dashboard template payload", e)
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleSearch(
    dashboardService: CustomDashboardService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val query = call.request.queryParameters["q"]?.trim().orEmpty()
    val result = dashboardService.search(orgId, userId, query)
    call.respond(result)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleListCustomDataSources(
    dataSourceService: CustomDataSourceService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    call.respond(dataSourceService.listDataSources(orgId))
}

private suspend fun io.ktor.server.routing.RoutingContext.handleCreateCustomDataSource(
    dataSourceService: CustomDataSourceService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val request = call.receive<CreateCustomDataSourceRequest>()
    try {
        val source = dataSourceService.createDataSource(orgId, userId.toLong(), request)
        call.respond(HttpStatusCode.Created, source)
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid request"))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleTestConnection(
    dataSourceExecutor: CustomDataSourceExecutor,
) {
    val request = call.receive<TestConnectionRequest>()
    suspendRunCatching {
        val result = dataSourceExecutor.testConnection(request)
        call.respond(result)
    }.getOrElse { e ->
        call.respond(TestConnectionResult(false, "Test failed: ${e.message}"))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleGetCustomDataSource(
    dataSourceService: CustomDataSourceService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = resolveDataSourceRouteId(dataSourceService, orgId) ?: return
    val source = dataSourceService.getDataSource(id, orgId)
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DATA_SOURCE_NOT_FOUND))
    call.respond(source)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleUpdateCustomDataSource(
    dataSourceService: CustomDataSourceService,
    dataSourceExecutor: CustomDataSourceExecutor,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = resolveDataSourceRouteId(dataSourceService, orgId) ?: return
    val request = call.receive<UpdateCustomDataSourceRequest>()
    val updated = dataSourceService.updateDataSource(id, orgId, request)
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DATA_SOURCE_NOT_FOUND))

    // Invalidate any cached connection pool
    dataSourceExecutor.closePool(id)
    call.respond(updated)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleDeleteCustomDataSource(
    dataSourceService: CustomDataSourceService,
    dataSourceExecutor: CustomDataSourceExecutor,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = resolveDataSourceRouteId(dataSourceService, orgId) ?: return
    if (dataSourceService.deleteDataSource(id, orgId)) {
        dataSourceExecutor.closePool(id)
        call.respond(HttpStatusCode.NoContent, "")
    } else {
        call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DATA_SOURCE_NOT_FOUND))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleGetDataSourceSchema(
    dataSourceService: CustomDataSourceService,
    dataSourceExecutor: CustomDataSourceExecutor,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = resolveDataSourceRouteId(dataSourceService, orgId) ?: return
    val source = dataSourceService.getDataSource(id, orgId)
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DATA_SOURCE_NOT_FOUND))
    val creds = dataSourceService.getDecryptedCredentials(id, orgId)
        ?: return call.respond(
            HttpStatusCode.InternalServerError, ErrorResponse(ERR_FAILED_DECRYPT_CREDENTIALS)
        )
    val sourceType = CustomDataSourceType.fromString(source.sourceType)
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_UNKNOWN_SOURCE_TYPE))
    val schema = dataSourceExecutor.getSchema(
        sourceType,
        source.host,
        source.port,
        source.databaseName,
        creds.withConnectionOptions(source.extraConfig),
    )
    call.respond(schema)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleCustomDataSourceQuery(
    dataSourceService: CustomDataSourceService,
    dataSourceExecutor: CustomDataSourceExecutor,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = currentOrgIdFromPrincipal(userId, principal)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = resolveDataSourceRouteId(dataSourceService, orgId) ?: return
    val request = call.receive<CustomDataSourceQueryRequest>()
    val source = dataSourceService.getDataSource(id, orgId)
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DATA_SOURCE_NOT_FOUND))
    val creds = dataSourceService.getDecryptedCredentials(id, orgId)
        ?: return call.respond(
            HttpStatusCode.InternalServerError, ErrorResponse(ERR_FAILED_DECRYPT_CREDENTIALS)
        )
    val sourceType = CustomDataSourceType.fromString(source.sourceType)
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_UNKNOWN_SOURCE_TYPE))
    try {
        val results = dataSourceExecutor.executeQuery(
            id, sourceType, source.host, source.port,
            source.databaseName, creds.withConnectionOptions(source.extraConfig),
            request.query, request.limit, request.timeRange,
        )
        call.respond(results)
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: ERR_INVALID_QUERY))
    }
}

data class DashboardTranslators(
    val dataDog: DataDogTranslator = DataDogTranslator(),
    val grafana: GrafanaTranslator = GrafanaTranslator(),
)

data class DashboardCoreRouteDependencies(
    val dashboardService: CustomDashboardService = GlobalContext.get().get(),
    val queryEngine: DashboardQueryEngine = GlobalContext.get().get(),
    val retentionPolicyService: RetentionPolicyService = GlobalContext.get().get(),
    val projectIdResolver: ProjectIdResolver = ProjectIdResolver(),
)

data class DashboardDataSourceRouteDependencies(
    val dataSourceService: CustomDataSourceService = GlobalContext.get().get(),
    val dataSourceExecutor: CustomDataSourceExecutor = GlobalContext.get().get(),
)

data class DashboardRouteDependencies(
    val core: DashboardCoreRouteDependencies = DashboardCoreRouteDependencies(),
    val translators: DashboardTranslators = DashboardTranslators(),
    val dataSources: DashboardDataSourceRouteDependencies = DashboardDataSourceRouteDependencies(),
    val dashboardAlertService: DashboardAlertService = GlobalContext.get().get(),
    val templateCatalogService: DashboardTemplateCatalogService = GlobalContext.get().get(),
)

fun Route.customDashboardRoutes(
    dependencies: DashboardRouteDependencies = DashboardRouteDependencies(),
) {
    val dashboardService = dependencies.core.dashboardService
    val queryEngine = dependencies.core.queryEngine
    val retentionPolicyService = dependencies.core.retentionPolicyService
    val projectIdResolver = dependencies.core.projectIdResolver
    val translators = dependencies.translators
    val dataSourceService = dependencies.dataSources.dataSourceService
    val dataSourceExecutor = dependencies.dataSources.dataSourceExecutor
    val dashboardAlertService = dependencies.dashboardAlertService
    val templateCatalogService = dependencies.templateCatalogService
    val queryDependencies = DashboardQueryRouteDependencies(
        dashboardService = dashboardService,
        queryEngine = queryEngine,
        retentionPolicyService = retentionPolicyService,
        dataSourceService = dataSourceService,
        dataSourceExecutor = dataSourceExecutor,
        projectIdResolver = projectIdResolver,
    )

    route("/v1/dashboards") {
        authenticate(AUTH_JWT) {
            get { handleListDashboards(dashboardService, projectIdResolver) }
            post { handleCreateDashboard(dashboardService) }
            route("/folders") {
                get { handleListFolders(dashboardService) }
                post { handleCreateFolder(dashboardService) }
                put("/{folderId}") { handleUpdateFolder(dashboardService) }
                delete("/{folderId}") { handleDeleteFolder(dashboardService) }
            }
            get("/templates") { handleListDashboardTemplates(templateCatalogService) }
            get("/templates/{templateId}") { handleGetDashboardTemplate(templateCatalogService) }
            post("/templates/{templateId}") {
                handleInstantiateDashboardTemplate(dashboardService, templateCatalogService)
            }
            get("/{id}") { handleGetDashboard(dashboardService) }
            post("/{id}/favorite") { handleToggleFavorite(dashboardService) }
            post("/{id}/duplicate") { handleDuplicateDashboard(dashboardService) }
            post("/{id}/default") { handleSetDefaultDashboard(dashboardService) }
            put("/{id}/folder") { handleMoveDashboardToFolder(dashboardService) }
            put("/{id}") { handleUpdateDashboard(dashboardService) }
            delete("/{id}") { handleDeleteDashboard(dashboardService) }
            post("/{id}/query") { handleDashboardQuery(queryDependencies) }
            post("/{id}/query/batch") { handleBatchDashboardQuery(queryDependencies) }
            post("/{id}/variables/resolve") {
                handleVariablesResolve(dashboardService, dataSourceService, dataSourceExecutor)
            }
            post("/import") { handleImportDashboard(dashboardService, translators.dataDog, translators.grafana) }
            get("/{id}/export/{format}") {
                handleExportDashboard(dashboardService, translators.dataDog, translators.grafana)
            }
            get("/{id}/alerts") { handleListAlerts(dashboardService, dashboardAlertService) }
            post("/{id}/alerts") { handleCreateAlert(dashboardService, dashboardAlertService) }
            put("/{id}/alerts/{alertId}") { handleUpdateAlert(dashboardService, dashboardAlertService) }
            delete("/{id}/alerts/{alertId}") { handleDeleteAlert(dashboardService, dashboardAlertService) }
            get("/datasources") { handleGetAvailableDataSources(dataSourceService, queryEngine) }
        }
    }

    // Global search (dashboards, projects)
    route("/v1/search") {
        authenticate(AUTH_JWT) {
            get { handleSearch(dashboardService) }
        }
    }

    // Custom data source management routes
    route("/v1") {
        route("/datasources") {
            authenticate(AUTH_JWT) {
                get { handleListCustomDataSources(dataSourceService) }
                post { handleCreateCustomDataSource(dataSourceService) }
                // Test connection (without saving) — must be before /{id} routes
                post("/test") { handleTestConnection(dataSourceExecutor) }
                get("/{id}") { handleGetCustomDataSource(dataSourceService) }
                put("/{id}") { handleUpdateCustomDataSource(dataSourceService, dataSourceExecutor) }
                delete("/{id}") { handleDeleteCustomDataSource(dataSourceService, dataSourceExecutor) }
                get("/{id}/schema") { handleGetDataSourceSchema(dataSourceService, dataSourceExecutor) }
                post("/{id}/query") { handleCustomDataSourceQuery(dataSourceService, dataSourceExecutor) }
            }
        }
    }
}
