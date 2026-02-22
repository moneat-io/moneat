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
    grafanaTranslator: GrafanaTranslator = GrafanaTranslator()
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
                    val results = queryEngine.executeQuery(effectiveQuery, projectId, demoEpochMs, retentionDays)
                    call.respond(results)
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

            // List available data sources and fields
            get("/datasources") {
                call.respond(queryEngine.getDataSources())
            }

            // Get default dashboard templates
            get("/templates") {
                call.respond(dashboardService.getDefaultDashboardTemplates())
            }
        }
    }
}
