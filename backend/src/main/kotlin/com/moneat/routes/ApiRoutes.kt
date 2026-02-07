package com.moneat.routes

import com.moneat.models.*
import com.moneat.services.DashboardService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

fun Route.apiRoutes() {
    val dashboardService = DashboardService()
    
    authenticate("auth-jwt") {
        route("/api/v1") {
            // User profile
            get("/user") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val user = transaction {
                    Users.selectAll().where { Users.id eq userId }.firstOrNull()
                }
                
                if (user == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))
                } else {
                    call.respond(UserResponse(
                        user[Users.id],
                        user[Users.email],
                        user[Users.name],
                        user[Users.email_verified],
                        user[Users.onboarding_completed],
                        user[Users.is_admin]
                    ))
                }
            }
            
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

            get("/issues/{issueId}/transactions") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val issueId = call.parameters["issueId"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20

                if (issueId == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }

                if (!dashboardService.hasIssueAccess(userId, issueId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                val transactions = dashboardService.getIssueTransactions(issueId, limit)
                call.respond(transactions)
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
                
                val period = call.request.queryParameters["period"] ?: "7d"
                val stats = dashboardService.getProjectStats(projectId, period)
                call.respond(stats)
            }

            get("/projects/{projectId}/transactions") {
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

                val period = call.request.queryParameters["period"] ?: "7d"
                val environment = call.request.queryParameters["environment"]
                val operation = call.request.queryParameters["operation"]
                val transactions = dashboardService.getTransactions(projectId, period, environment, operation)
                call.respond(transactions)
            }

            get("/projects/{projectId}/transactions/stats") {
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

                val period = call.request.queryParameters["period"] ?: "7d"
                val environment = call.request.queryParameters["environment"]
                val operation = call.request.queryParameters["operation"]
                val stats = dashboardService.getPerformanceStats(projectId, period, environment, operation)
                call.respond(stats)
            }

            get("/transactions/{eventId}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val eventId = call.parameters["eventId"]
                if (eventId == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }

                if (!dashboardService.hasTransactionAccess(userId, eventId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                val transaction = dashboardService.getTransaction(eventId)
                if (transaction == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(transaction)
                }
            }

            get("/transactions/{eventId}/spans") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val eventId = call.parameters["eventId"]
                if (eventId == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }

                if (!dashboardService.hasTransactionAccess(userId, eventId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                val spansResponse = dashboardService.getTransactionSpans(eventId)
                if (spansResponse == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(spansResponse)
                }
            }

            get("/transactions/{eventId}/related-errors") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val eventId = call.parameters["eventId"]
                if (eventId == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }

                if (!dashboardService.hasTransactionAccess(userId, eventId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val relatedErrors = dashboardService.getRelatedErrorsForTransaction(eventId, limit)
                call.respond(relatedErrors)
            }

            // Replays
            get("/projects/{projectId}/replays") {
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
                val environment = call.request.queryParameters["environment"]
                val period = call.request.queryParameters["period"] ?: "7d"

                val replays = dashboardService.getReplays(projectId, page, limit, environment, period)
                call.respond(replays)
            }

            get("/replays/{replayId}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val replayId = call.parameters["replayId"]
                if (replayId == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }

                if (!dashboardService.hasReplayAccess(userId, replayId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                val replay = dashboardService.getReplay(replayId)
                if (replay == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(replay)
                }
            }

            get("/replays/{replayId}/recording") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val replayId = call.parameters["replayId"]
                if (replayId == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }

                if (!dashboardService.hasReplayAccess(userId, replayId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                val recording = dashboardService.getReplayRecording(replayId)
                if (recording == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(recording)
                }
            }

            get("/replays/{replayId}/timeline") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val replayId = call.parameters["replayId"]
                if (replayId == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }

                if (!dashboardService.hasReplayAccess(userId, replayId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                val timeline = dashboardService.getReplayTimeline(replayId)
                call.respond(timeline)
            }

            get("/projects/{projectId}/feedback") {
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

                val feedback = dashboardService.getFeedback(projectId, page, limit, status)
                call.respond(feedback)
            }

            get("/feedback/{feedbackId}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val feedbackId = call.parameters["feedbackId"]
                if (feedbackId == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }

                if (!dashboardService.hasFeedbackAccess(userId, feedbackId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                val feedback = dashboardService.getFeedbackDetail(feedbackId)
                if (feedback == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(feedback)
                }
            }

            patch("/feedback/{feedbackId}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val feedbackId = call.parameters["feedbackId"]
                if (feedbackId == null) {
                    call.respond(HttpStatusCode.BadRequest, "Missing feedback ID")
                    return@patch
                }

                if (!dashboardService.hasFeedbackAccess(userId, feedbackId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@patch
                }

                val update = call.receive<FeedbackUpdateRequest>()
                dashboardService.updateFeedback(feedbackId, update)
                call.respond(HttpStatusCode.OK)
            }

            get("/events/{eventId}/issue") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val eventId = call.parameters["eventId"]
                if (eventId == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }

                val projectId = dashboardService.getProjectIdForEvent(eventId)
                if (projectId == null || !dashboardService.hasProjectAccess(userId, projectId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                val issueId = dashboardService.getIssueIdForEvent(eventId)
                if (issueId == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(mapOf("issueId" to issueId))
                }
            }

            get("/issues/{issueId}/replays") {
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

                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
                val replays = dashboardService.getReplaysForIssue(issueId, limit)
                call.respond(replays)
            }
            
            // Releases
            get("/projects/{projectId}/releases") {
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
                
                val releases = dashboardService.getReleases(projectId)
                call.respond(releases)
            }
            
            get("/projects/{projectId}/releases/{version}/stats") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val projectId = call.parameters["projectId"]?.toLongOrNull()
                val version = call.parameters["version"]
                if (projectId == null || version == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }
                
                if (!dashboardService.hasProjectAccess(userId, projectId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }
                
                val stats = dashboardService.getReleaseStats(projectId, version)
                if (stats == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(stats)
                }
            }
            
            // Notification Preferences
            get("/notification-preferences") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val preferences = transaction {
                    // Get global preferences
                    val global = NotificationPreferences.selectAll()
                        .where { 
                            (NotificationPreferences.user_id eq userId) and
                            (NotificationPreferences.project_id.isNull())
                        }
                        .firstOrNull()
                    
                    val globalPrefs = if (global != null) {
                        mapOf(
                            "issueAlerts" to global[NotificationPreferences.issue_alerts],
                            "errorAlerts" to global[NotificationPreferences.error_alerts],
                            "weeklySummary" to global[NotificationPreferences.weekly_summary],
                            "alertFrequencyMinutes" to global[NotificationPreferences.alert_frequency_minutes]
                        )
                    } else {
                        mapOf(
                            "issueAlerts" to true,
                            "errorAlerts" to true,
                            "weeklySummary" to true,
                            "alertFrequencyMinutes" to 30
                        )
                    }
                    
                    // Get per-project overrides
                    val projects = NotificationPreferences.selectAll()
                        .where { 
                            (NotificationPreferences.user_id eq userId) and
                            (NotificationPreferences.project_id.isNotNull())
                        }
                        .map { pref ->
                            val projectId = pref[NotificationPreferences.project_id]!!
                            val projectName = Projects.selectAll()
                                .where { Projects.id eq projectId }
                                .firstOrNull()?.get(Projects.name) ?: "Unknown"
                            
                            mapOf(
                                "projectId" to projectId,
                                "projectName" to projectName,
                                "issueAlerts" to pref[NotificationPreferences.issue_alerts],
                                "errorAlerts" to pref[NotificationPreferences.error_alerts],
                                "weeklySummary" to pref[NotificationPreferences.weekly_summary],
                                "alertFrequencyMinutes" to pref[NotificationPreferences.alert_frequency_minutes]
                            )
                        }
                    
                    mapOf(
                        "global" to globalPrefs,
                        "projects" to projects
                    )
                }
                
                call.respond(preferences)
            }
            
            put("/notification-preferences") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val request = call.receive<Map<String, Any>>()
                
                transaction {
                    val existing = NotificationPreferences.selectAll()
                        .where { 
                            (NotificationPreferences.user_id eq userId) and
                            (NotificationPreferences.project_id.isNull())
                        }
                        .firstOrNull()
                    
                    val issueAlerts = request["issueAlerts"] as? Boolean ?: existing?.get(NotificationPreferences.issue_alerts) ?: true
                    val errorAlerts = request["errorAlerts"] as? Boolean ?: existing?.get(NotificationPreferences.error_alerts) ?: true
                    val weeklySummary = request["weeklySummary"] as? Boolean ?: existing?.get(NotificationPreferences.weekly_summary) ?: true
                    val alertFrequency = (request["alertFrequencyMinutes"] as? Number)?.toInt() ?: existing?.get(NotificationPreferences.alert_frequency_minutes) ?: 30
                    
                    if (existing != null) {
                        NotificationPreferences.update({ 
                            (NotificationPreferences.user_id eq userId) and
                            (NotificationPreferences.project_id.isNull())
                        }) {
                            it[issue_alerts] = issueAlerts
                            it[error_alerts] = errorAlerts
                            it[weekly_summary] = weeklySummary
                            it[alert_frequency_minutes] = alertFrequency
                            it[updated_at] = kotlinx.datetime.Clock.System.now()
                        }
                    } else {
                        NotificationPreferences.insert {
                            it[user_id] = userId
                            it[project_id] = null
                            it[issue_alerts] = issueAlerts
                            it[error_alerts] = errorAlerts
                            it[weekly_summary] = weeklySummary
                            it[alert_frequency_minutes] = alertFrequency
                            it[created_at] = kotlinx.datetime.Clock.System.now()
                            it[updated_at] = kotlinx.datetime.Clock.System.now()
                        }
                    }
                }
                
                call.respond(HttpStatusCode.OK)
            }
            
            put("/notification-preferences/{projectId}") {
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
                
                val request = call.receive<Map<String, Any>>()
                
                transaction {
                    val existing = NotificationPreferences.selectAll()
                        .where { 
                            (NotificationPreferences.user_id eq userId) and
                            (NotificationPreferences.project_id eq projectId)
                        }
                        .firstOrNull()
                    
                    val issueAlerts = request["issueAlerts"] as? Boolean ?: existing?.get(NotificationPreferences.issue_alerts) ?: true
                    val errorAlerts = request["errorAlerts"] as? Boolean ?: existing?.get(NotificationPreferences.error_alerts) ?: true
                    val weeklySummary = request["weeklySummary"] as? Boolean ?: existing?.get(NotificationPreferences.weekly_summary) ?: true
                    val alertFrequency = (request["alertFrequencyMinutes"] as? Number)?.toInt() ?: existing?.get(NotificationPreferences.alert_frequency_minutes) ?: 30
                    
                    if (existing != null) {
                        NotificationPreferences.update({ 
                            (NotificationPreferences.user_id eq userId) and
                            (NotificationPreferences.project_id eq projectId)
                        }) {
                            it[issue_alerts] = issueAlerts
                            it[error_alerts] = errorAlerts
                            it[weekly_summary] = weeklySummary
                            it[alert_frequency_minutes] = alertFrequency
                            it[updated_at] = kotlinx.datetime.Clock.System.now()
                        }
                    } else {
                        NotificationPreferences.insert {
                            it[user_id] = userId
                            it[NotificationPreferences.project_id] = projectId
                            it[issue_alerts] = issueAlerts
                            it[error_alerts] = errorAlerts
                            it[weekly_summary] = weeklySummary
                            it[alert_frequency_minutes] = alertFrequency
                            it[created_at] = kotlinx.datetime.Clock.System.now()
                            it[updated_at] = kotlinx.datetime.Clock.System.now()
                        }
                    }
                }
                
                call.respond(HttpStatusCode.OK)
            }
            
            delete("/notification-preferences/{projectId}") {
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
                
                transaction {
                    NotificationPreferences.deleteWhere { 
                        (NotificationPreferences.user_id eq userId) and
                        (NotificationPreferences.project_id eq projectId)
                    }
                }
                
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
