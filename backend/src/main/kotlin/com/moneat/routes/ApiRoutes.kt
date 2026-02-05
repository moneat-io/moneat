package com.moneat.routes

import com.moneat.models.CreateProjectRequest
import com.moneat.models.IssueUpdateRequest
import com.moneat.models.UpdateProjectRequest
import com.moneat.services.DashboardService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.apiRoutes() {
    val dashboardService = DashboardService()
    
    authenticate("auth-jwt") {
        route("/api/v1") {
            // Projects
            get("/projects") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val projects = dashboardService.getProjects(userId)
                call.respond(projects)
            }
            
            post("/projects") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val request = call.receive<CreateProjectRequest>()
                
                val project = dashboardService.createProject(userId, request)
                call.respond(HttpStatusCode.Created, project)
            }
            
            get("/projects/{projectId}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val projectId = call.parameters["projectId"]?.toLongOrNull()
                if (projectId == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid project ID")
                    return@get
                }
                
                if (!dashboardService.hasProjectAccess(userId, projectId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }
                
                val project = dashboardService.getProject(projectId)
                if (project == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(project)
                }
            }
            
            put("/projects/{projectId}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val projectId = call.parameters["projectId"]?.toLongOrNull()
                if (projectId == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid project ID")
                    return@put
                }
                
                if (!dashboardService.hasProjectAccess(userId, projectId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@put
                }
                
                val request = call.receive<UpdateProjectRequest>()
                dashboardService.updateProject(projectId, request)
                call.respond(HttpStatusCode.OK)
            }
            
            delete("/projects/{projectId}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val projectId = call.parameters["projectId"]?.toLongOrNull()
                if (projectId == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid project ID")
                    return@delete
                }
                
                if (!dashboardService.hasProjectAccess(userId, projectId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@delete
                }
                
                dashboardService.deleteProject(projectId)
                call.respond(HttpStatusCode.NoContent)
            }
            
            // Issues
            get("/projects/{projectId}/issues") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val projectId = call.parameters["projectId"]?.toLongOrNull()
                if (projectId == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }
                
                if (!dashboardService.hasProjectAccess(userId, projectId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }
                
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 25
                val status = call.request.queryParameters["status"]
                
                val issues = dashboardService.getIssues(projectId, page, limit, status)
                call.respond(issues)
            }
            
            get("/issues/{issueId}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val issueId = call.parameters["issueId"]
                if (issueId == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }
                
                if (!dashboardService.hasIssueAccess(userId, issueId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }
                
                val issue = dashboardService.getIssue(issueId)
                if (issue == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(issue)
                }
            }
            
            get("/issues/{issueId}/events") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val issueId = call.parameters["issueId"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                
                if (issueId == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }
                
                if (!dashboardService.hasIssueAccess(userId, issueId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }
                
                val events = dashboardService.getIssueEvents(issueId, limit)
                call.respond(events)
            }
            
            patch("/issues/{issueId}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val issueId = call.parameters["issueId"]
                if (issueId == null) {
                    call.respond(HttpStatusCode.BadRequest, "Missing issue ID")
                    return@patch
                }
                
                if (!dashboardService.hasIssueAccess(userId, issueId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@patch
                }
                
                val update = call.receive<IssueUpdateRequest>()
                dashboardService.updateIssue(issueId, update)
                call.respond(HttpStatusCode.OK)
            }
            
            // Stats
            get("/projects/{projectId}/stats") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val projectId = call.parameters["projectId"]?.toLongOrNull()
                if (projectId == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }
                
                if (!dashboardService.hasProjectAccess(userId, projectId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }
                
                val stats = dashboardService.getProjectStats(projectId)
                call.respond(stats)
            }
        }
    }
}
