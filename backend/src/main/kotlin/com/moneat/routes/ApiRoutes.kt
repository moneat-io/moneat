package com.moneat.routes

import com.moneat.services.DashboardService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
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
            
            get("/projects/{projectId}") {
                val projectId = call.parameters["projectId"]?.toLongOrNull()
                if (projectId == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid project ID")
                    return@get
                }
                
                val project = dashboardService.getProject(projectId)
                if (project == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(project)
                }
            }
            
            // Issues
            get("/projects/{projectId}/issues") {
                val projectId = call.parameters["projectId"]?.toLongOrNull()
                if (projectId == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }
                
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 25
                val status = call.request.queryParameters["status"]
                
                val issues = dashboardService.getIssues(projectId, page, limit, status)
                call.respond(issues)
            }
            
            get("/issues/{issueId}") {
                val issueId = call.parameters["issueId"]
                if (issueId == null) {
                    call.respond(HttpStatusCode.BadRequest)
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
                val issueId = call.parameters["issueId"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                
                if (issueId == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }
                
                val events = dashboardService.getIssueEvents(issueId, limit)
                call.respond(events)
            }
            
            // Stats
            get("/projects/{projectId}/stats") {
                val projectId = call.parameters["projectId"]?.toLongOrNull()
                if (projectId == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }
                
                val stats = dashboardService.getProjectStats(projectId)
                call.respond(stats)
            }
        }
    }
}
