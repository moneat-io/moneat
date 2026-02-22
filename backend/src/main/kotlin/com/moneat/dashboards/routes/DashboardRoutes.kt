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

import com.moneat.dashboards.models.*
import com.moneat.dashboards.services.CustomDashboardService
import com.moneat.dashboards.services.CustomDataSourceExecutor
import com.moneat.dashboards.services.CustomDataSourceService
import com.moneat.dashboards.services.DashboardQueryEngine
import com.moneat.dashboards.translation.DataDogTranslator
import com.moneat.dashboards.translation.GrafanaTranslator
import com.moneat.plugins.getDemoEpochMs
import com.moneat.shared.models.Memberships
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

fun Route.customDashboardRoutes(
    dashboardService: CustomDashboardService = CustomDashboardService(),
    queryEngine: DashboardQueryEngine = DashboardQueryEngine(),
    retentionPolicyService: RetentionPolicyService = RetentionPolicyService(),
    dataDogTranslator: DataDogTranslator = DataDogTranslator(),
    grafanaTranslator: GrafanaTranslator = GrafanaTranslator(),
    dataSourceService: CustomDataSourceService = CustomDataSourceService(),
    dataSourceExecutor: CustomDataSourceExecutor = CustomDataSourceExecutor()
) {
    route("/v1/dashboards") {
        authenticate("auth-jwt") {
            // List dashboards for org
            get {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = getOrgIdForUser(userId)
                    ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                val projectId = call.request.queryParameters["projectId"]?.toLongOrNull()
                val dashboards = dashboardService.listDashboards(orgId, projectId)
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

            // Get dashboard with widgets
            get("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val orgId = getOrgIdForUser(userId)
                    ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("No organization found"))

                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid dashboard ID"))

                val dashboard = dashboardService.getDashboard(id, orgId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Dashboard not found"))

                call.respond(dashboard)
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

                val request = call.receive<ExecuteQueryRequest>()
                val demoEpochMs = call.getDemoEpochMs()

                val projectId = call.request.queryParameters["projectId"]?.toLongOrNull()
                if (projectId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("projectId query parameter required"))
                    return@post
                }

                val retentionDays = retentionPolicyService.getRetentionDaysForProject(projectId) ?: 90
                val effectiveQuery = if (request.timeRange != null) {
                    request.queryConfig.copy(timeRange = request.timeRange)
                } else {
                    request.queryConfig
                }

                try {
                    // Check if this is a custom data source query
                    if (queryEngine.isCustomDataSource(effectiveQuery.dataSource)) {
                        val sourceId = queryEngine.parseCustomDataSourceId(effectiveQuery.dataSource)
                            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid custom data source ID"))

                        val source = dataSourceService.getDataSource(sourceId, orgId)
                            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Data source not found"))

                        val creds = dataSourceService.getDecryptedCredentials(sourceId, orgId)
                            ?: return@post call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to decrypt credentials"))

                        val sourceType = CustomDataSourceType.fromString(source.sourceType)
                            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unknown source type"))

                        val rawQuery = effectiveQuery.rawQuery
                            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Custom data source queries require a rawQuery"))

                        val results = dataSourceExecutor.executeQuery(
                            sourceId, sourceType, source.host, source.port ?: 5432,
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
                        widgets = importResult.dashboard.widgets.map { w ->
                            CreateWidgetRequest(
                                title = w.title,
                                widgetType = w.widgetType,
                                gridX = w.gridX,
                                gridY = w.gridY,
                                gridW = w.gridW,
                                gridH = w.gridH,
                                queryConfig = w.queryConfig,
                                displayConfig = w.displayConfig,
                                sortOrder = w.sortOrder
                            )
                        }
                    )
                    val created = dashboardService.createDashboard(orgId, userId.toLong(), createRequest)
                    call.respond(HttpStatusCode.Created, DashboardImportResult(created, importResult.warnings))
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

    // Custom data source management routes
    route("/v1/datasources") {
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
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid data source ID"))

                if (dataSourceService.deleteDataSource(id, orgId)) {
                    dataSourceExecutor.closePool(id)
                    call.respond(HttpStatusCode.NoContent, "")
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Data source not found"))
                }
            }

            // Test connection (without saving)
            post("/test") {
                val request = call.receive<TestConnectionRequest>()
                try {
                    val result = dataSourceExecutor.testConnection(request)
                    call.respond(result)
                } catch (e: Exception) {
                    call.respond(TestConnectionResult(false, "Test failed: ${e.message}"))
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
                    ?: return@get call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to decrypt credentials"))

                val sourceType = CustomDataSourceType.fromString(source.sourceType)
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unknown source type"))

                val schema = dataSourceExecutor.getSchema(
                    sourceType, source.host, source.port ?: 5432, source.databaseName, creds
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
                    ?: return@post call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to decrypt credentials"))

                val sourceType = CustomDataSourceType.fromString(source.sourceType)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unknown source type"))

                try {
                    val results = dataSourceExecutor.executeQuery(
                        id, sourceType, source.host, source.port ?: 5432,
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
