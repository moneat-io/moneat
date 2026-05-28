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

import com.moneat.dashboards.models.BatchQueryResult
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
import com.moneat.dashboards.services.DashboardAlertService
import com.moneat.dashboards.services.DashboardQueryEngine
import com.moneat.dashboards.translation.DashboardTranslator
import com.moneat.dashboards.translation.DataDogTranslator
import com.moneat.dashboards.translation.GrafanaTranslator
import com.moneat.plugins.getDemoEpochMs
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Projects
import com.moneat.shared.services.ProjectIdResolver
import com.moneat.shared.services.RetentionPolicyService
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

private const val AUTH_JWT = "auth-jwt"
private const val ERR_NO_ORGANIZATION = "No organization found"
private const val ERR_INVALID_DASHBOARD_ID = "Invalid dashboard ID"
private const val ERR_DASHBOARD_NOT_FOUND = "Dashboard not found"
private const val ERR_DATA_SOURCE_NOT_FOUND = "Data source not found"
private const val ERR_UNKNOWN_SOURCE_TYPE = "Unknown source type"
private const val ERR_INVALID_DATA_SOURCE_ID = "Invalid data source ID"

private fun getOrgIdForUser(userId: Int): Long? {
    return transaction {
        Memberships.selectAll()
            .where { Memberships.user_id eq userId }
            .firstOrNull()
            ?.get(Memberships.organization_id)
            ?.toLong()
    }
}

private data class DashboardScope(
    val projectId: Long?
)

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

private suspend fun io.ktor.server.routing.RoutingContext.handleListDashboards(
    dashboardService: CustomDashboardService,
    projectIdResolver: ProjectIdResolver,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = getOrgIdForUser(userId)
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
    val orgId = getOrgIdForUser(userId)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val request = call.receive<CreateDashboardRequest>()
    val dashboard = dashboardService.createDashboard(orgId, userId.toLong(), request)
    call.respond(HttpStatusCode.Created, dashboard)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleListFolders(
    dashboardService: CustomDashboardService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = getOrgIdForUser(userId)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val folders = dashboardService.listFolders(orgId)
    call.respond(folders)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleCreateFolder(
    dashboardService: CustomDashboardService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = getOrgIdForUser(userId)
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
    val orgId = getOrgIdForUser(userId)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val folderId = call.parameters["folderId"]?.toLongOrNull()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid folder ID"))
    val request = call.receive<UpdateFolderRequest>()
    val folder = dashboardService.updateFolder(folderId, orgId, request)
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse("Folder not found"))
    call.respond(folder)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleDeleteFolder(
    dashboardService: CustomDashboardService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = getOrgIdForUser(userId)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val folderId = call.parameters["folderId"]?.toLongOrNull()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid folder ID"))
    if (dashboardService.deleteFolder(folderId, orgId)) {
        call.respond(HttpStatusCode.NoContent, "")
    } else {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Folder not found"))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleGetDashboard(
    dashboardService: CustomDashboardService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = getOrgIdForUser(userId)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = call.parameters["id"]?.toLongOrNull()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_INVALID_DASHBOARD_ID))
    val dashboard = dashboardService.getDashboard(id, orgId, userId)
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DASHBOARD_NOT_FOUND))
    call.respond(dashboard)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleToggleFavorite(
    dashboardService: CustomDashboardService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = getOrgIdForUser(userId)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = call.parameters["id"]?.toLongOrNull()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_INVALID_DASHBOARD_ID))
    val isFavorited = dashboardService.toggleFavorite(userId, id, orgId)
    call.respond(HttpStatusCode.OK, mapOf("is_favorited" to isFavorited))
}

private suspend fun io.ktor.server.routing.RoutingContext.handleMoveDashboardToFolder(
    dashboardService: CustomDashboardService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = getOrgIdForUser(userId)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = call.parameters["id"]?.toLongOrNull()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_INVALID_DASHBOARD_ID))
    val request = call.receive<MoveToFolderRequest>()
    if (dashboardService.moveDashboardToFolder(id, orgId, request.folderId)) {
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
    val orgId = getOrgIdForUser(userId)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = call.parameters["id"]?.toLongOrNull()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_INVALID_DASHBOARD_ID))
    val request = call.receive<UpdateDashboardRequest>()
    val updated = dashboardService.updateDashboard(id, orgId, request)
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DASHBOARD_NOT_FOUND))
    call.respond(updated)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleDeleteDashboard(
    dashboardService: CustomDashboardService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = getOrgIdForUser(userId)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = call.parameters["id"]?.toLongOrNull()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_INVALID_DASHBOARD_ID))
    if (dashboardService.deleteDashboard(id, orgId)) {
        call.respond(HttpStatusCode.NoContent, "")
    } else {
        call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DASHBOARD_NOT_FOUND))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleDashboardQuery(
    queryEngine: DashboardQueryEngine,
    retentionPolicyService: RetentionPolicyService,
    dataSourceService: CustomDataSourceService,
    dataSourceExecutor: CustomDataSourceExecutor,
    projectIdResolver: ProjectIdResolver,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = getOrgIdForUser(userId)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = call.parameters["id"]?.toLongOrNull()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_INVALID_DASHBOARD_ID))
    val dashboardScope = getDashboardScope(id, orgId)
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DASHBOARD_NOT_FOUND))

    val request = call.receive<ExecuteQueryRequest>()
    val demoEpochMs = call.getDemoEpochMs()
    val isDemoUser = demoEpochMs != null

    // Demo users are scoped to demo projects; regular users must supply a projectId
    val projectId: Long = if (isDemoUser) {
        -1L // Queries against all 3 demo projects via ClickHouseQueryUtils.projectIdClause
    } else {
        call.request.queryParameters["projectId"]?.let(projectIdResolver::resolve)
            ?: return call.respond(
                HttpStatusCode.BadRequest, ErrorResponse("projectId query parameter required")
            )
    }
    if (!isDemoUser && !hasProjectAccess(orgId, projectId)) {
        return call.respond(HttpStatusCode.Forbidden, ErrorResponse("Project access denied"))
    }
    if (!isDemoUser && dashboardScope.projectId != null && dashboardScope.projectId != projectId) {
        return call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("Dashboard is scoped to project ${dashboardScope.projectId}")
        )
    }

    val retentionDays = if (isDemoUser) {
        DEFAULT_RETENTION_DAYS
    } else {
        retentionPolicyService.getRetentionDaysForProject(projectId) ?: DEFAULT_RETENTION_DAYS
    }
    val withTimeRange = if (request.timeRange != null) {
        request.queryConfig.copy(timeRange = request.timeRange)
    } else {
        request.queryConfig
    }
    val effectiveQuery = queryEngine.resolvePrometheusDataSource(
        queryEngine.applyVariables(withTimeRange, request.variables),
        orgId,
        dataSourceService
    )

    try {
        // Check if this is a custom data source query
        if (queryEngine.isCustomDataSource(effectiveQuery.dataSource)) {
            val sourceId = queryEngine.parseCustomDataSourceId(effectiveQuery.dataSource)
                ?: return call.respond(
                    HttpStatusCode.BadRequest, ErrorResponse("Invalid custom data source ID")
                )
            val source = dataSourceService.getDataSource(sourceId, orgId)
                ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DATA_SOURCE_NOT_FOUND))
            val creds = dataSourceService.getDecryptedCredentials(sourceId, orgId)
                ?: return call.respond(
                    HttpStatusCode.InternalServerError, ErrorResponse("Failed to decrypt credentials")
                )
            val sourceType = CustomDataSourceType.fromString(source.sourceType)
                ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_UNKNOWN_SOURCE_TYPE))
            val rawQuery = effectiveQuery.rawQuery
                ?: return call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Custom data source queries require a rawQuery")
                )
            val results = dataSourceExecutor.executeQuery(
                sourceId, sourceType, source.host, source.port,
                source.databaseName, creds, rawQuery, effectiveQuery.limit, effectiveQuery.timeRange
            )
            call.respond(results)
        } else {
            val results = queryEngine.executeQuery(effectiveQuery, projectId, demoEpochMs, retentionDays)
            call.respond(results)
        }
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid query"))
    }
}

private suspend fun executeSingleQuery(
    effectiveQuery: QueryDsl,
    orgId: Long,
    projectId: Long,
    demoEpochMs: Long?,
    retentionDays: Int,
    queryEngine: DashboardQueryEngine,
    dataSourceService: CustomDataSourceService,
    dataSourceExecutor: CustomDataSourceExecutor,
): List<Map<String, kotlinx.serialization.json.JsonElement>>? {
    if (!queryEngine.isCustomDataSource(effectiveQuery.dataSource)) {
        return queryEngine.executeQuery(effectiveQuery, projectId, demoEpochMs, retentionDays)
    }
    val sourceId = queryEngine.parseCustomDataSourceId(effectiveQuery.dataSource) ?: return null
    val source = dataSourceService.getDataSource(sourceId, orgId) ?: return null
    val creds = dataSourceService.getDecryptedCredentials(sourceId, orgId) ?: return null
    val sourceType = CustomDataSourceType.fromString(source.sourceType) ?: return null
    val rawQuery = effectiveQuery.rawQuery ?: return null
    return dataSourceExecutor.executeQuery(
        sourceId, sourceType, source.host, source.port,
        source.databaseName, creds, rawQuery, effectiveQuery.limit, effectiveQuery.timeRange
    )
}

private suspend fun io.ktor.server.routing.RoutingContext.handleBatchDashboardQuery(
    queryEngine: DashboardQueryEngine,
    retentionPolicyService: RetentionPolicyService,
    dataSourceService: CustomDataSourceService,
    dataSourceExecutor: CustomDataSourceExecutor,
    projectIdResolver: ProjectIdResolver,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = getOrgIdForUser(userId)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = call.parameters["id"]?.toLongOrNull()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_INVALID_DASHBOARD_ID))
    val dashboardScope = getDashboardScope(id, orgId)
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DASHBOARD_NOT_FOUND))

    val request = call.receive<ExecuteBatchQueryRequest>()
    val demoEpochMs = call.getDemoEpochMs()
    val isDemoUser = demoEpochMs != null

    val projectId: Long = if (isDemoUser) {
        -1L
    } else {
        call.request.queryParameters["projectId"]?.let(projectIdResolver::resolve)
            ?: return call.respond(
                HttpStatusCode.BadRequest, ErrorResponse("projectId query parameter required")
            )
    }
    if (!isDemoUser && !hasProjectAccess(orgId, projectId)) {
        return call.respond(HttpStatusCode.Forbidden, ErrorResponse("Project access denied"))
    }
    if (!isDemoUser && dashboardScope.projectId != null && dashboardScope.projectId != projectId) {
        return call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("Dashboard is scoped to project ${dashboardScope.projectId}")
        )
    }

    if (request.queries.size > MAX_QUERIES_PER_REQUEST) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Maximum 10 queries per batch"))
        return
    }

    val retentionDays = if (isDemoUser) {
        DEFAULT_RETENTION_DAYS
    } else {
        retentionPolicyService.getRetentionDaysForProject(projectId) ?: DEFAULT_RETENTION_DAYS
    }
    val results = mutableMapOf<String, List<Map<String, kotlinx.serialization.json.JsonElement>>>()

    for ((index, query) in request.queries.withIndex()) {
        val refId = query.refId ?: ('A' + index).toString()
        val withTimeRange = if (request.timeRange != null) {
            query.copy(timeRange = request.timeRange)
        } else {
            query
        }
        val effectiveQuery = queryEngine.resolvePrometheusDataSource(
            queryEngine.applyVariables(withTimeRange, request.variables),
            orgId,
            dataSourceService
        )
        suspendRunCatching {
            executeSingleQuery(
                effectiveQuery,
                orgId,
                projectId,
                demoEpochMs,
                retentionDays,
                queryEngine,
                dataSourceService,
                dataSourceExecutor,
            )?.let { results[refId] = it }
        }.getOrElse { e ->
            logger.warn(e) { "Batch query $refId failed" }
            results[refId] = emptyList()
        }
    }

    call.respond(BatchQueryResult(results))
}

private suspend fun io.ktor.server.routing.RoutingContext.handleVariablesResolve(
    dashboardService: CustomDashboardService,
    dataSourceService: CustomDataSourceService,
    dataSourceExecutor: CustomDataSourceExecutor,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = getOrgIdForUser(userId)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = call.parameters["id"]?.toLongOrNull()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_INVALID_DASHBOARD_ID))
    getDashboardScope(id, orgId)
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DASHBOARD_NOT_FOUND))

    val dashboard = dashboardService.getDashboard(id, orgId)
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DASHBOARD_NOT_FOUND))

    val variables = dashboard.variables
    val currentValues = call.receive<Map<String, String>>()

    // Find the org's Prometheus datasource
    val promSource = dataSourceService.listDataSources(orgId)
        .firstOrNull { it.sourceType.equals("prometheus", ignoreCase = true) }

    val resolved = mutableMapOf<String, List<String>>()
    for (v in variables) {
        val query = v.query ?: continue
        if (!query.startsWith("label_values(")) continue
        if (promSource == null) continue

        // Substitute variable references in the query
        var substituted = query
        for ((name, value) in currentValues) {
            substituted = substituted
                .replace("\${$name}", value)
                .replace("\$$name", value)
        }

        val creds = dataSourceService.getDecryptedCredentials(promSource.id, orgId) ?: continue
        val sourceType = CustomDataSourceType.fromString(promSource.sourceType)
            ?: CustomDataSourceType.PROMETHEUS
        val options = dataSourceExecutor.executeLabelValuesQuery(
            sourceType,
            promSource.host,
            promSource.port,
            creds,
            substituted
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
    val orgId = getOrgIdForUser(userId)
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
    val orgId = getOrgIdForUser(userId)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = call.parameters["id"]?.toLongOrNull()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_INVALID_DASHBOARD_ID))
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
    dashboardAlertService: DashboardAlertService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = getOrgIdForUser(userId)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = call.parameters["id"]?.toLongOrNull()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_INVALID_DASHBOARD_ID))
    call.respond(dashboardAlertService.listAlerts(id, orgId))
}

private suspend fun io.ktor.server.routing.RoutingContext.handleCreateAlert(
    dashboardAlertService: DashboardAlertService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = getOrgIdForUser(userId)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = call.parameters["id"]?.toLongOrNull()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_INVALID_DASHBOARD_ID))
    val request = call.receive<CreateDashboardAlertRequest>()
    try {
        val alert = dashboardAlertService.createAlert(id, orgId, userId.toLong(), request)
        call.respond(HttpStatusCode.Created, alert)
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid request"))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleUpdateAlert(
    dashboardAlertService: DashboardAlertService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = getOrgIdForUser(userId)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = call.parameters["id"]?.toLongOrNull()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_INVALID_DASHBOARD_ID))
    val alertId = call.parameters["alertId"]?.toLongOrNull()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert ID"))
    val request = receiveUpdateAlertRequest()
    try {
        val updated = dashboardAlertService.updateAlert(alertId, id, orgId, request)
            ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse("Alert not found"))
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
    dashboardAlertService: DashboardAlertService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = getOrgIdForUser(userId)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = call.parameters["id"]?.toLongOrNull()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_INVALID_DASHBOARD_ID))
    val alertId = call.parameters["alertId"]?.toLongOrNull()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert ID"))
    if (dashboardAlertService.deleteAlert(alertId, id, orgId)) {
        call.respond(HttpStatusCode.NoContent, "")
    } else {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Alert not found"))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleGetAvailableDataSources(
    dataSourceService: CustomDataSourceService,
    queryEngine: DashboardQueryEngine,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = getOrgIdForUser(userId)
    if (orgId != null) {
        val customSources = dataSourceService.listDataSources(orgId)
        call.respond(queryEngine.getDataSources(customSources))
    } else {
        call.respond(queryEngine.getDataSources())
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleSearch(
    dashboardService: CustomDashboardService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = getOrgIdForUser(userId)
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
    val orgId = getOrgIdForUser(userId)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    call.respond(dataSourceService.listDataSources(orgId))
}

private suspend fun io.ktor.server.routing.RoutingContext.handleCreateCustomDataSource(
    dataSourceService: CustomDataSourceService,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = getOrgIdForUser(userId)
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
    val orgId = getOrgIdForUser(userId)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = call.parameters["id"]?.toLongOrNull()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_INVALID_DATA_SOURCE_ID))
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
    val orgId = getOrgIdForUser(userId)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = call.parameters["id"]?.toLongOrNull()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_INVALID_DATA_SOURCE_ID))
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
    val orgId = getOrgIdForUser(userId)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = call.parameters["id"]?.toLongOrNull()
        ?: return call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(ERR_INVALID_DATA_SOURCE_ID)
        )
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
    val orgId = getOrgIdForUser(userId)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = call.parameters["id"]?.toLongOrNull()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_INVALID_DATA_SOURCE_ID))
    val source = dataSourceService.getDataSource(id, orgId)
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DATA_SOURCE_NOT_FOUND))
    val creds = dataSourceService.getDecryptedCredentials(id, orgId)
        ?: return call.respond(
            HttpStatusCode.InternalServerError, ErrorResponse("Failed to decrypt credentials")
        )
    val sourceType = CustomDataSourceType.fromString(source.sourceType)
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_UNKNOWN_SOURCE_TYPE))
    val schema = dataSourceExecutor.getSchema(
        sourceType,
        source.host,
        source.port,
        source.databaseName,
        creds
    )
    call.respond(schema)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleCustomDataSourceQuery(
    dataSourceService: CustomDataSourceService,
    dataSourceExecutor: CustomDataSourceExecutor,
) {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal!!.payload.getClaim("userId").asInt()
    val orgId = getOrgIdForUser(userId)
        ?: return call.respond(HttpStatusCode.Forbidden, ErrorResponse(ERR_NO_ORGANIZATION))
    val id = call.parameters["id"]?.toLongOrNull()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_INVALID_DATA_SOURCE_ID))
    val request = call.receive<CustomDataSourceQueryRequest>()
    val source = dataSourceService.getDataSource(id, orgId)
        ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse(ERR_DATA_SOURCE_NOT_FOUND))
    val creds = dataSourceService.getDecryptedCredentials(id, orgId)
        ?: return call.respond(
            HttpStatusCode.InternalServerError, ErrorResponse("Failed to decrypt credentials")
        )
    val sourceType = CustomDataSourceType.fromString(source.sourceType)
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(ERR_UNKNOWN_SOURCE_TYPE))
    try {
        val results = dataSourceExecutor.executeQuery(
            id, sourceType, source.host, source.port,
            source.databaseName, creds, request.query, request.limit, request.timeRange
        )
        call.respond(results)
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid query"))
    }
}

data class DashboardTranslators(
    val dataDog: DataDogTranslator = DataDogTranslator(),
    val grafana: GrafanaTranslator = GrafanaTranslator(),
)

fun Route.customDashboardRoutes(
    dashboardService: CustomDashboardService = GlobalContext.get().get(),
    queryEngine: DashboardQueryEngine = GlobalContext.get().get(),
    retentionPolicyService: RetentionPolicyService = GlobalContext.get().get(),
    projectIdResolver: ProjectIdResolver = ProjectIdResolver(),
    translators: DashboardTranslators = DashboardTranslators(),
    dataSourceService: CustomDataSourceService = GlobalContext.get().get(),
    dataSourceExecutor: CustomDataSourceExecutor = GlobalContext.get().get(),
    dashboardAlertService: DashboardAlertService = GlobalContext.get().get(),
) {
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
            get("/{id}") { handleGetDashboard(dashboardService) }
            post("/{id}/favorite") { handleToggleFavorite(dashboardService) }
            put("/{id}/folder") { handleMoveDashboardToFolder(dashboardService) }
            put("/{id}") { handleUpdateDashboard(dashboardService) }
            delete("/{id}") { handleDeleteDashboard(dashboardService) }
            post("/{id}/query") {
                handleDashboardQuery(
                    queryEngine,
                    retentionPolicyService,
                    dataSourceService,
                    dataSourceExecutor,
                    projectIdResolver
                )
            }
            post("/{id}/query/batch") {
                handleBatchDashboardQuery(
                    queryEngine,
                    retentionPolicyService,
                    dataSourceService,
                    dataSourceExecutor,
                    projectIdResolver
                )
            }
            post("/{id}/variables/resolve") {
                handleVariablesResolve(dashboardService, dataSourceService, dataSourceExecutor)
            }
            post("/import") { handleImportDashboard(dashboardService, translators.dataDog, translators.grafana) }
            get("/{id}/export/{format}") {
                handleExportDashboard(dashboardService, translators.dataDog, translators.grafana)
            }
            get("/{id}/alerts") { handleListAlerts(dashboardAlertService) }
            post("/{id}/alerts") { handleCreateAlert(dashboardAlertService) }
            put("/{id}/alerts/{alertId}") { handleUpdateAlert(dashboardAlertService) }
            delete("/{id}/alerts/{alertId}") { handleDeleteAlert(dashboardAlertService) }
            get("/datasources") { handleGetAvailableDataSources(dataSourceService, queryEngine) }
            get("/templates") { call.respond(dashboardService.getDefaultDashboardTemplates()) }
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
