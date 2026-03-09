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
import com.moneat.dashboards.models.CreateWidgetRequest
import com.moneat.dashboards.models.CustomDataSourceQueryRequest
import com.moneat.dashboards.models.CustomDataSourceType
import com.moneat.dashboards.models.DashboardImportResult
import com.moneat.dashboards.models.ExecuteBatchQueryRequest
import com.moneat.dashboards.models.ExecuteQueryRequest
import com.moneat.dashboards.models.ImportDashboardRequest
import com.moneat.dashboards.models.QueryDsl
import com.moneat.dashboards.models.TestConnectionRequest
import com.moneat.dashboards.models.TestConnectionResult
import com.moneat.dashboards.models.CreateFolderRequest
import com.moneat.dashboards.models.UpdateCustomDataSourceRequest
import com.moneat.dashboards.models.UpdateDashboardAlertRequest
import com.moneat.dashboards.models.UpdateDashboardRequest
import com.moneat.dashboards.models.MoveToFolderRequest
import com.moneat.dashboards.models.UpdateFolderRequest
import com.moneat.dashboards.models.Dashboards
import com.moneat.dashboards.repositories.DashboardFolderRepositoryImpl
import com.moneat.dashboards.repositories.DashboardRepositoryImpl
import com.moneat.dashboards.repositories.DashboardWidgetRepositoryImpl
import com.moneat.dashboards.services.CustomDashboardService
import com.moneat.dashboards.services.CustomDataSourceExecutor
import com.moneat.dashboards.services.CustomDataSourceService
import com.moneat.dashboards.services.DashboardAlertService
import com.moneat.dashboards.services.DashboardQueryEngine
import com.moneat.dashboards.translation.DataDogTranslator
import com.moneat.dashboards.translation.GrafanaTranslator
import com.moneat.plugins.getDemoEpochMs
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Projects
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

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

fun Route.customDashboardRoutes(
    dashboardService: CustomDashboardService = CustomDashboardService(
        DashboardFolderRepositoryImpl(),
        DashboardRepositoryImpl(),
        DashboardWidgetRepositoryImpl()
    ),
    queryEngine: DashboardQueryEngine = DashboardQueryEngine(),
    retentionPolicyService: RetentionPolicyService = RetentionPolicyService(),
    dataDogTranslator: DataDogTranslator = DataDogTranslator(),
    grafanaTranslator: GrafanaTranslator = GrafanaTranslator(),
    dataSourceService: CustomDataSourceService = CustomDataSourceService(),
    dataSourceExecutor: CustomDataSourceExecutor = CustomDataSourceExecutor(),
    dashboardAlertService: DashboardAlertService = DashboardAlertService()
) {
    // Resolve __prometheus marker to the org's first Prometheus custom datasource
    fun resolvePrometheusDataSource(dsl: QueryDsl, orgId: Long): QueryDsl {
        if (dsl.dataSource != "__prometheus") return dsl
        val sources = dataSourceService.listDataSources(orgId)
        val promSource = sources.firstOrNull { it.sourceType.equals("prometheus", ignoreCase = true) }
        if (promSource == null) {
            logger.warn {
                val sourcesList = sources.map { "${it.id}:${it.sourceType}" }
                val shortQuery = dsl.rawQuery?.take(80) ?: ""
                "No Prometheus datasource found for org $orgId (${sources.size} sources: $sourcesList), " +
                    "cannot resolve __prometheus for rawQuery=$shortQuery"
            }
            return dsl
        }
        logger.debug { "Resolved __prometheus -> custom:${promSource.id} for rawQuery=${dsl.rawQuery?.take(80)}" }
        return dsl.copy(dataSource = "custom:${promSource.id}")
    }

    route("/v1/dashboards") {
        authenticate("auth-jwt") {
            // List dashboards for org
            get {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = getOrgIdForUser(userId)
                    ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                val projectId = call.request.queryParameters["projectId"]?.toLongOrNull()
                val dashboards = dashboardService.listDashboards(orgId, projectId, userId)
                call.respond(dashboards)
            }

            // Create dashboard
            post {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = getOrgIdForUser(userId)
                    ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                val request = call.receive<CreateDashboardRequest>()
                val dashboard = dashboardService.createDashboard(orgId, userId.toLong(), request)
                call.respond(HttpStatusCode.Created, dashboard)
            }

            // Folder management (must be before /{id} to avoid "folders" matching as id)
            route("/folders") {
                get {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val orgId = getOrgIdForUser(userId)
                        ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))
                    val folders = dashboardService.listFolders(orgId)
                    call.respond(folders)
                }
                post {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val orgId = getOrgIdForUser(userId)
                        ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))
                    val request = call.receive<CreateFolderRequest>()
                    val folder = dashboardService.createFolder(orgId, request)
                    call.respond(HttpStatusCode.Created, folder)
                }
                put("/{folderId}") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val orgId = getOrgIdForUser(userId)
                        ?: return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))
                    val folderId = call.parameters["folderId"]?.toLongOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid folder ID"))
                    val request = call.receive<UpdateFolderRequest>()
                    val folder = dashboardService.updateFolder(folderId, orgId, request)
                        ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("Folder not found"))
                    call.respond(folder)
                }
                delete("/{folderId}") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val orgId = getOrgIdForUser(userId)
                        ?: return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))
                    val folderId = call.parameters["folderId"]?.toLongOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid folder ID"))
                    if (dashboardService.deleteFolder(folderId, orgId)) {
                        call.respond(HttpStatusCode.NoContent, "")
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Folder not found"))
                    }
                }
            }

            // Get dashboard with widgets
            get("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = getOrgIdForUser(userId)
                    ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid dashboard ID"))

                val dashboard = dashboardService.getDashboard(id, orgId, userId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Dashboard not found"))

                call.respond(dashboard)
            }

            // Toggle favorite
            post("/{id}/favorite") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = getOrgIdForUser(userId)
                    ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid dashboard ID"))

                val isFavorited = dashboardService.toggleFavorite(userId, id, orgId)
                call.respond(HttpStatusCode.OK, mapOf("is_favorited" to isFavorited))
            }

            // Move dashboard to folder
            put("/{id}/folder") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = getOrgIdForUser(userId)
                    ?: return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid dashboard ID"))

                val request = call.receive<MoveToFolderRequest>()
                if (dashboardService.moveDashboardToFolder(id, orgId, request.folderId)) {
                    call.respond(HttpStatusCode.OK, mapOf("folder_id" to request.folderId))
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Dashboard not found"))
                }
            }

            // Update dashboard
            put("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = getOrgIdForUser(userId)
                    ?: return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid dashboard ID"))

                val request = call.receive<UpdateDashboardRequest>()
                val updated = dashboardService.updateDashboard(id, orgId, request)
                    ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("Dashboard not found"))

                call.respond(updated)
            }

            // Delete dashboard
            delete("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = getOrgIdForUser(userId)
                    ?: return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid dashboard ID"))

                if (dashboardService.deleteDashboard(id, orgId)) {
                    call.respond(HttpStatusCode.NoContent, "")
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Dashboard not found"))
                }
            }

            // Execute widget query
            post("/{id}/query") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = getOrgIdForUser(userId)
                    ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid dashboard ID"))
                val dashboardScope = getDashboardScope(id, orgId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Dashboard not found"))

                val request = call.receive<ExecuteQueryRequest>()
                val demoEpochMs = call.getDemoEpochMs()
                val isDemoUser = demoEpochMs != null

                // Demo users are scoped to demo projects; regular users must supply a projectId
                val projectId: Long = if (isDemoUser) {
                    -1L // Queries against all 3 demo projects via ClickHouseQueryUtils.projectIdClause
                } else {
                    call.request.queryParameters["projectId"]?.toLongOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest, ErrorResponse("projectId query parameter required")
                        )
                }
                if (!isDemoUser && !hasProjectAccess(orgId, projectId)) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Project access denied"))
                }
                if (!isDemoUser && dashboardScope.projectId != null && dashboardScope.projectId != projectId) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Dashboard is scoped to project ${dashboardScope.projectId}")
                    )
                }

                val retentionDays =
                    if (isDemoUser) 90 else retentionPolicyService.getRetentionDaysForProject(projectId) ?: 90
                val withTimeRange = if (request.timeRange != null) {
                    request.queryConfig.copy(timeRange = request.timeRange)
                } else {
                    request.queryConfig
                }
                val effectiveQuery = resolvePrometheusDataSource(
                    queryEngine.applyVariables(withTimeRange, request.variables),
                    orgId
                )

                try {
                    // Check if this is a custom data source query
                    if (queryEngine.isCustomDataSource(effectiveQuery.dataSource)) {
                        val sourceId = queryEngine.parseCustomDataSourceId(effectiveQuery.dataSource)
                            ?: return@post call.respond(
                                HttpStatusCode.BadRequest, ErrorResponse("Invalid custom data source ID")
                            )

                        val source = dataSourceService.getDataSource(sourceId, orgId)
                            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Data source not found"))

                        val creds = dataSourceService.getDecryptedCredentials(sourceId, orgId)
                            ?: return@post call.respond(
                                HttpStatusCode.InternalServerError, ErrorResponse("Failed to decrypt credentials")
                            )

                        val sourceType = CustomDataSourceType.fromString(source.sourceType)
                            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unknown source type"))

                        val rawQuery = effectiveQuery.rawQuery
                            ?: return@post call.respond(
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

            // Execute batch query (multiple queries keyed by refId)
            post("/{id}/query/batch") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = getOrgIdForUser(userId)
                    ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid dashboard ID"))
                val dashboardScope = getDashboardScope(id, orgId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Dashboard not found"))

                val request = call.receive<ExecuteBatchQueryRequest>()
                val demoEpochMs = call.getDemoEpochMs()
                val isDemoUser = demoEpochMs != null

                val projectId: Long = if (isDemoUser) {
                    -1L
                } else {
                    call.request.queryParameters["projectId"]?.toLongOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest, ErrorResponse("projectId query parameter required")
                        )
                }
                if (!isDemoUser && !hasProjectAccess(orgId, projectId)) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Project access denied"))
                }
                if (!isDemoUser && dashboardScope.projectId != null && dashboardScope.projectId != projectId) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Dashboard is scoped to project ${dashboardScope.projectId}")
                    )
                }

                if (request.queries.size > 10) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Maximum 10 queries per batch"))
                    return@post
                }

                val retentionDays =
                    if (isDemoUser) 90 else retentionPolicyService.getRetentionDaysForProject(projectId) ?: 90
                val results = mutableMapOf<String, List<Map<String, kotlinx.serialization.json.JsonElement>>>()

                for ((index, query) in request.queries.withIndex()) {
                    val refId = query.refId ?: ('A' + index).toString()
                    val withTimeRange = if (request.timeRange != null) {
                        query.copy(timeRange = request.timeRange)
                    } else {
                        query
                    }
                    val effectiveQuery = resolvePrometheusDataSource(
                        queryEngine.applyVariables(withTimeRange, request.variables),
                        orgId
                    )

                    try {
                        if (queryEngine.isCustomDataSource(effectiveQuery.dataSource)) {
                            val sourceId = queryEngine.parseCustomDataSourceId(effectiveQuery.dataSource)
                                ?: continue
                            val source = dataSourceService.getDataSource(sourceId, orgId) ?: continue
                            val creds = dataSourceService.getDecryptedCredentials(sourceId, orgId) ?: continue
                            val sourceType = CustomDataSourceType.fromString(source.sourceType) ?: continue
                            val rawQuery = effectiveQuery.rawQuery ?: continue

                            results[refId] = dataSourceExecutor.executeQuery(
                                sourceId, sourceType, source.host, source.port,
                                source.databaseName, creds, rawQuery, effectiveQuery.limit, effectiveQuery.timeRange
                            )
                        } else {
                            results[refId] = queryEngine.executeQuery(
                                effectiveQuery,
                                projectId,
                                demoEpochMs,
                                retentionDays
                            )
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Batch query $refId failed" }
                        results[refId] = emptyList()
                    }
                }

                call.respond(BatchQueryResult(results))
            }

            // Resolve variable options (e.g., Grafana label_values queries)
            post("/{id}/variables/resolve") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = getOrgIdForUser(userId)
                    ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid dashboard ID"))
                getDashboardScope(id, orgId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Dashboard not found"))

                val dashboard = dashboardService.getDashboard(id, orgId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Dashboard not found"))

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
                    val sourceType = com.moneat.dashboards.models.CustomDataSourceType.fromString(promSource.sourceType)
                        ?: com.moneat.dashboards.models.CustomDataSourceType.PROMETHEUS
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

            // Import dashboard from DataDog/Grafana JSON
            post("/import") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = getOrgIdForUser(userId)
                    ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                val request = call.receive<ImportDashboardRequest>()
                val jsonObj = try {
                    json.parseToJsonElement(request.json) as JsonObject
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid JSON"))
                }

                val translator = when (request.format.lowercase()) {
                    "datadog" -> dataDogTranslator
                    "grafana" -> grafanaTranslator
                    else -> return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Unsupported format: ${request.format}. Use 'datadog' or 'grafana'")
                    )
                }

                try {
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
                } catch (e: Exception) {
                    logger.error(e) { "Failed to import dashboard" }
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Failed to import: ${e.message}"))
                }
            }

            // Export dashboard
            get("/{id}/export/{format}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = getOrgIdForUser(userId)
                    ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid dashboard ID"))

                val format = call.parameters["format"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Format required"))

                val dashboard = dashboardService.getDashboard(id, orgId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Dashboard not found"))

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

            // Dashboard alert CRUD routes
            get("/{id}/alerts") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = getOrgIdForUser(userId)
                    ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid dashboard ID"))

                call.respond(dashboardAlertService.listAlerts(id, orgId))
            }

            post("/{id}/alerts") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = getOrgIdForUser(userId)
                    ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid dashboard ID"))

                val request = call.receive<CreateDashboardAlertRequest>()
                try {
                    val alert = dashboardAlertService.createAlert(id, orgId, userId.toLong(), request)
                    call.respond(HttpStatusCode.Created, alert)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid request"))
                }
            }

            put("/{id}/alerts/{alertId}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = getOrgIdForUser(userId)
                    ?: return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid dashboard ID"))
                val alertId = call.parameters["alertId"]?.toLongOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert ID"))

                val request = call.receive<UpdateDashboardAlertRequest>()
                try {
                    val updated = dashboardAlertService.updateAlert(alertId, id, orgId, request)
                        ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("Alert not found"))
                    call.respond(updated)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid request"))
                }
            }

            delete("/{id}/alerts/{alertId}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = getOrgIdForUser(userId)
                    ?: return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid dashboard ID"))
                val alertId = call.parameters["alertId"]?.toLongOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert ID"))

                if (dashboardAlertService.deleteAlert(alertId, id, orgId)) {
                    call.respond(HttpStatusCode.NoContent, "")
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Alert not found"))
                }
            }

            // List available data sources and fields (built-in + custom)
            get("/datasources") {
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

            // Get default dashboard templates
            get("/templates") {
                call.respond(dashboardService.getDefaultDashboardTemplates())
            }
        }
    }

    // Global search (dashboards, projects)
    route("/v1/search") {
        authenticate("auth-jwt") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = getOrgIdForUser(userId)
                    ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))
                val query = call.request.queryParameters["q"]?.trim().orEmpty()
                val result = dashboardService.search(orgId, userId, query)
                call.respond(result)
            }
        }
    }

    // Custom data source management routes
    route("/v1") {
        route("/datasources") {
            authenticate("auth-jwt") {
                // List custom data sources for org
                get {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val orgId = getOrgIdForUser(userId)
                        ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                    call.respond(dataSourceService.listDataSources(orgId))
                }

                // Create custom data source
                post {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val orgId = getOrgIdForUser(userId)
                        ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                    val request = call.receive<CreateCustomDataSourceRequest>()
                    try {
                        val source = dataSourceService.createDataSource(orgId, userId.toLong(), request)
                        call.respond(HttpStatusCode.Created, source)
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid request"))
                    }
                }

                // Test connection (without saving) — must be before /{id} routes
                post("/test") {
                    val request = call.receive<TestConnectionRequest>()
                    try {
                        val result = dataSourceExecutor.testConnection(request)
                        call.respond(result)
                    } catch (e: Exception) {
                        call.respond(TestConnectionResult(false, "Test failed: ${e.message}"))
                    }
                }

                // Get custom data source
                get("/{id}") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val orgId = getOrgIdForUser(userId)
                        ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                    val id = call.parameters["id"]?.toLongOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid data source ID"))

                    val source = dataSourceService.getDataSource(id, orgId)
                        ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Data source not found"))

                    call.respond(source)
                }

                // Update custom data source
                put("/{id}") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val orgId = getOrgIdForUser(userId)
                        ?: return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                    val id = call.parameters["id"]?.toLongOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid data source ID"))

                    val request = call.receive<UpdateCustomDataSourceRequest>()
                    val updated = dataSourceService.updateDataSource(id, orgId, request)
                        ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("Data source not found"))

                    // Invalidate any cached connection pool
                    dataSourceExecutor.closePool(id)
                    call.respond(updated)
                }

                // Delete custom data source
                delete("/{id}") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val orgId = getOrgIdForUser(userId)
                        ?: return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                    val id = call.parameters["id"]?.toLongOrNull()
                        ?: return@delete call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Invalid data source ID")
                        )

                    if (dataSourceService.deleteDataSource(id, orgId)) {
                        dataSourceExecutor.closePool(id)
                        call.respond(HttpStatusCode.NoContent, "")
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Data source not found"))
                    }
                }

                // Get schema for a custom data source
                get("/{id}/schema") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val orgId = getOrgIdForUser(userId)
                        ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                    val id = call.parameters["id"]?.toLongOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid data source ID"))

                    val source = dataSourceService.getDataSource(id, orgId)
                        ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Data source not found"))

                    val creds = dataSourceService.getDecryptedCredentials(id, orgId)
                        ?: return@get call.respond(
                            HttpStatusCode.InternalServerError, ErrorResponse("Failed to decrypt credentials")
                        )

                    val sourceType = CustomDataSourceType.fromString(source.sourceType)
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unknown source type"))

                    val schema = dataSourceExecutor.getSchema(
                        sourceType,
                        source.host,
                        source.port,
                        source.databaseName,
                        creds
                    )
                    call.respond(schema)
                }

                // Execute query against custom data source
                post("/{id}/query") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val orgId = getOrgIdForUser(userId)
                        ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                    val id = call.parameters["id"]?.toLongOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid data source ID"))

                    val request = call.receive<CustomDataSourceQueryRequest>()
                    val source = dataSourceService.getDataSource(id, orgId)
                        ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Data source not found"))

                    val creds = dataSourceService.getDecryptedCredentials(id, orgId)
                        ?: return@post call.respond(
                            HttpStatusCode.InternalServerError, ErrorResponse("Failed to decrypt credentials")
                        )

                    val sourceType = CustomDataSourceType.fromString(source.sourceType)
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unknown source type"))

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
            }
        }
    }
}
