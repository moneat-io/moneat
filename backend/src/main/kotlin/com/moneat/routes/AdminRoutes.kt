package com.moneat.routes

import com.moneat.models.Users
import com.moneat.services.AdminService
import com.moneat.services.PricingTierService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.config.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun Route.adminRoutes() {
    val adminService = AdminService()
    val pricingTierService = PricingTierService()
    
    authenticate("auth-jwt") {
        route("/v1/admin") {
            intercept(ApplicationCallPipeline.Call) {
                val principal = call.principal<JWTPrincipal>()
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, "Authentication required")
                    finish()
                    return@intercept
                }
                val userId = principal.payload.getClaim("userId").asInt()
                val isAdmin = transaction {
                    Users.selectAll().where { Users.id eq userId }
                        .firstOrNull()?.get(Users.is_admin) ?: false
                }
                if (!isAdmin) {
                    call.respond(HttpStatusCode.Forbidden, com.moneat.models.ErrorResponse("Admin access required"))
                    finish()
                    return@intercept
                }
            }
            
            get("/overview") {
                val stats = adminService.getOverviewStats()
                call.respond(stats)
            }
            
            get("/organizations") {
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 25
                val orgs = adminService.getAllOrganizations(page, limit)
                call.respond(orgs)
            }
            
            get("/organizations/{orgId}") {
                val orgId = call.parameters["orgId"]?.toIntOrNull()
                if (orgId == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid org ID")
                    return@get
                }
                val detail = adminService.getOrgDetail(orgId)
                if (detail == null) {
                    call.respond(HttpStatusCode.NotFound, "Organization not found")
                } else {
                    call.respond(detail)
                }
            }
            
            get("/organizations/{orgId}/usage") {
                val orgId = call.parameters["orgId"]?.toIntOrNull()
                if (orgId == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid org ID")
                    return@get
                }
                val period = call.request.queryParameters["period"] ?: "7d"
                val usage = adminService.getOrgUsage(orgId, period)
                val response = usage.map { u ->
                    mapOf(
                        "date" to u.date.toString(),
                        "eventType" to u.eventType,
                        "eventCount" to u.eventCount,
                        "bytesIngested" to u.bytesIngested
                    )
                }
                call.respond(response)
            }
            
            get("/usage") {
                val period = call.request.queryParameters["period"] ?: "7d"
                val breakdown = adminService.getUsageBreakdown(period)
                call.respond(breakdown)
            }
            
            get("/revenue") {
                val metrics = adminService.getRevenueMetrics()
                call.respond(metrics)
            }
            
            get("/infrastructure") {
                val health = adminService.getInfrastructureHealth()
                call.respond(health)
            }
            
            get("/top-consumers") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
                val consumers = adminService.getTopConsumers(limit)
                call.respond(consumers)
            }
            
            get("/emails") {
                val period = call.request.queryParameters["period"] ?: "30d"
                val stats = adminService.getEmailStats(period)
                call.respond(stats)
            }
            
            post("/test-notification") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt() ?: run {
                    call.respond(HttpStatusCode.Unauthorized, com.moneat.models.ErrorResponse("Invalid token"))
                    return@post
                }
                
                try {
                    val request = call.receive<com.moneat.models.TestNotificationRequest>()
                    
                    // Get user email for testing - use testEmail from request if provided
                    val userEmail = if (!request.testEmail.isNullOrBlank()) {
                        request.testEmail
                    } else {
                        transaction {
                            Users.selectAll().where { Users.id eq userId }
                                .firstOrNull()?.get(Users.email)
                        }
                    } ?: run {
                        call.respond(HttpStatusCode.BadRequest, com.moneat.models.ErrorResponse("User not found"))
                        return@post
                    }
                    
                    // Get user's organization for Slack testing
                    val orgId = transaction {
                        com.moneat.models.Memberships.selectAll()
                            .where { com.moneat.models.Memberships.user_id eq userId }
                            .firstOrNull()?.get(com.moneat.models.Memberships.organization_id)
                    }
                    
                    val emailService = com.moneat.services.EmailService()
                    val slackService = com.moneat.services.SlackService()
                    val config = ApplicationConfig("application.conf")
                    val frontendUrl = config.property("email.frontendUrl").getString()
                    
                    var emailSent = false
                    var slackSent = false
                    val errors = mutableListOf<String>()
                    
                    // Send email notification if requested
                    if (request.channel == "email" || request.channel == "both") {
                        try {
                            when (request.type) {
                                "error_alert" -> {
                                    val testData = com.moneat.services.EmailService.ErrorAlertData(
                                        issueTitle = "[TEST] NullPointerException in UserService",
                                        issueLevel = "error",
                                        issueCulprit = "com.example.UserService.getUser",
                                        issueMessage = "Cannot read property 'id' of null",
                                        issueCount = "42",
                                        issueUrl = "$frontendUrl/issues/12345",
                                        projectName = "Test Project",
                                        environment = "production",
                                        timestamp = java.time.Instant.now().toString(),
                                        stackTrace = "  at UserService.getUser (UserService.kt:45)\n  at UserController.handleRequest (UserController.kt:23)\n  at Router.dispatch (Router.kt:89)",
                                        settingsUrl = "$frontendUrl/settings/notifications",
                                        unsubscribeUrl = "$frontendUrl/settings/notifications"
                                    )
                                    emailService.sendErrorAlertEmail(userEmail, testData)
                                    emailSent = true
                                }
                                "weekly_summary" -> {
                                    val testData = com.moneat.services.EmailService.WeeklySummaryData(
                                        startDate = "Jan 1, 2026",
                                        endDate = "Jan 7, 2026",
                                        totalEvents = "12.5K",
                                        eventsTrend = 15,
                                        newIssues = "23",
                                        issuesTrend = -8,
                                        affectedUsers = "1.2K",
                                        usersTrend = 5,
                                        topIssues = listOf(
                                            com.moneat.services.EmailService.TopIssue(
                                                title = "[TEST] Database timeout",
                                                culprit = "DatabaseConnection.query",
                                                project = "Test API",
                                                count = "156"
                                            ),
                                            com.moneat.services.EmailService.TopIssue(
                                                title = "[TEST] Invalid token",
                                                culprit = "AuthMiddleware.validate",
                                                project = "Test Mobile",
                                                count = "89"
                                            )
                                        ),
                                        projects = listOf(
                                            com.moneat.services.EmailService.ProjectSummary(
                                                name = "Test API",
                                                events = "8.2K",
                                                issues = "15",
                                                crashFree = "99.8"
                                            )
                                        ),
                                        dashboardUrl = frontendUrl,
                                        settingsUrl = "$frontendUrl/settings/notifications",
                                        unsubscribeUrl = "$frontendUrl/settings/notifications"
                                    )
                                    emailService.sendWeeklySummaryEmail(userEmail, testData)
                                    emailSent = true
                                }
                                "verification" -> {
                                    emailService.sendVerificationEmail(userEmail, "test-token-12345", "Test User")
                                    emailSent = true
                                }
                                "password_reset" -> {
                                    emailService.sendPasswordResetEmail(userEmail, "test-reset-token-67890", "Test User")
                                    emailSent = true
                                }
                                else -> {
                                    errors.add("Email type '${request.type}' not supported for email channel")
                                }
                            }
                        } catch (e: Exception) {
                            errors.add("Email failed: ${e.message}")
                        }
                    }
                    
                    // Send Slack notification if requested
                    if (request.channel == "slack" || request.channel == "both") {
                        if (orgId == null) {
                            errors.add("No organization found for Slack testing")
                        } else {
                            try {
                                when (request.type) {
                                    "error_alert" -> {
                                        slackSent = slackService.sendErrorAlert(
                                            organizationId = orgId,
                                            projectName = "[TEST] Test Project",
                                            issueTitle = "NullPointerException in UserService",
                                            level = "error",
                                            culprit = "com.example.UserService.getUser",
                                            issueId = 12345L,
                                            projectId = 1L,
                                            baseUrl = frontendUrl,
                                            occurrenceCount = 42,
                                            environment = "production",
                                            timestamp = java.time.Instant.now().toString(),
                                            stackTrace = "  at UserService.getUser (UserService.kt:45)\n  at UserController.handleRequest (UserController.kt:23)\n  at Router.dispatch (Router.kt:89)"
                                        )
                                    }
                                    "system_up" -> {
                                        slackSent = slackService.sendSystemUp(
                                            organizationId = orgId,
                                            systemName = "[TEST] Production API",
                                            systemId = java.util.UUID.randomUUID(),
                                            baseUrl = frontendUrl
                                        )
                                    }
                                    "system_down" -> {
                                        slackSent = slackService.sendSystemDown(
                                            organizationId = orgId,
                                            systemName = "[TEST] Production API",
                                            lastSeen = "2 minutes ago",
                                            systemId = java.util.UUID.randomUUID(),
                                            baseUrl = frontendUrl
                                        )
                                    }
                                    "uptime_alert" -> {
                                        slackSent = slackService.sendUptimeAlert(
                                            organizationId = orgId,
                                            monitorName = "[TEST] API Health Check",
                                            oldStatus = "up",
                                            newStatus = "down",
                                            message = "HTTP 500 - Internal Server Error",
                                            monitorId = java.util.UUID.randomUUID(),
                                            baseUrl = frontendUrl
                                        )
                                    }
                                    else -> {
                                        errors.add("Notification type '${request.type}' not supported for Slack channel")
                                    }
                                }
                                if (!slackSent && errors.isEmpty()) {
                                    errors.add("Slack notification failed (no Slack integration configured or error occurred)")
                                }
                            } catch (e: Exception) {
                                errors.add("Slack failed: ${e.message}")
                            }
                        }
                    }
                    
                    val response = com.moneat.models.TestNotificationResponse(
                        success = emailSent || slackSent,
                        emailSent = emailSent,
                        slackSent = slackSent,
                        errors = errors
                    )
                    
                    call.respond(if (emailSent || slackSent) HttpStatusCode.OK else HttpStatusCode.BadRequest, response)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, com.moneat.models.TestNotificationResponse(
                        success = false,
                        emailSent = false,
                        slackSent = false,
                        errors = listOf(e.message ?: "Unknown error")
                    ))
                }
            }

            route("/billing") {
                get("/tiers") {
                    val tierName = call.request.queryParameters["tier"]?.uppercase()
                    if (tierName.isNullOrBlank()) {
                        call.respond(pricingTierService.getCurrentPlans())
                    } else {
                        call.respond(pricingTierService.getTierVersions(tierName))
                    }
                }

                post("/tiers/{tierName}/versions") {
                    val tierName = call.parameters["tierName"]?.uppercase()
                    if (tierName.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, com.moneat.models.ErrorResponse("Missing tier name"))
                        return@post
                    }
                    try {
                        val request = call.receive<com.moneat.models.CreateTierVersionRequest>()
                        val created = pricingTierService.createTierVersion(tierName, request)
                        call.respond(HttpStatusCode.Created, created)
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, com.moneat.models.ErrorResponse(e.message ?: "Invalid request"))
                    }
                }

                post("/tiers/{tierName}/migrate") {
                    val tierName = call.parameters["tierName"]?.uppercase()
                    if (tierName.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, com.moneat.models.ErrorResponse("Missing tier name"))
                        return@post
                    }
                    try {
                        val request = call.receive<com.moneat.models.TierMigrationRequest>()
                        val response = pricingTierService.migrateSubscribers(tierName, request)
                        call.respond(response)
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, com.moneat.models.ErrorResponse(e.message ?: "Invalid request"))
                    }
                }

                get("/subscriptions") {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 500
                    call.respond(pricingTierService.listAdminSubscriptions(limit))
                }
            }
        }
    }
}
