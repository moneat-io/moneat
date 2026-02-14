package com.moneat.routes

import com.moneat.models.Users
import com.moneat.models.TriggerIncidentRequest
import com.moneat.models.IncidentEvent
import com.moneat.models.AlertSource
import com.moneat.models.IncidentSeverity
import com.moneat.models.IncidentStatus
import com.moneat.services.AdminOrgDetail
import com.moneat.services.AdminOrgUsagePoint
import com.moneat.services.AdminService
import com.moneat.services.AuthService
import com.moneat.services.PricingTierService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.config.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
private data class AdminUsersResponse(
    val users: List<com.moneat.services.AdminUserSummary>,
    val total: Int,
    val page: Int,
    val limit: Int
)

@Serializable
private data class AdminImpersonationTokenResponse(
    val token: String
)

@Serializable
private data class AdminSuccessResponse(
    val success: Boolean
)

fun Route.adminRoutes() {
    val adminService = AdminService()
    val authService = AuthService()
    val pricingTierService = PricingTierService()
    val attributionAnalyticsService = com.moneat.services.AttributionAnalyticsService()
    
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
                    call.respond<AdminOrgDetail>(detail)
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
                    AdminOrgUsagePoint(
                        date = u.date.toString(),
                        eventType = u.eventType,
                        eventCount = u.eventCount,
                        bytesIngested = u.bytesIngested
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
            
            post("/impersonate/{userId}") {
                val targetUserId = call.parameters["userId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, com.moneat.models.ErrorResponse("Invalid user ID"))
                
                val targetUser = transaction {
                    Users.selectAll().where { Users.id eq targetUserId }.firstOrNull()
                } ?: return@post call.respond(HttpStatusCode.NotFound, com.moneat.models.ErrorResponse("User not found"))
                
                val token = authService.generateImpersonationToken(
                    targetUser[Users.id],
                    targetUser[Users.email]
                )
                call.respond(AdminImpersonationTokenResponse(token = token))
            }

            post("/incidents/trigger") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt() ?: run {
                    call.respond(HttpStatusCode.Unauthorized, com.moneat.models.ErrorResponse("Invalid token"))
                    return@post
                }
                
                // Get user's organization
                val orgId = transaction {
                     com.moneat.models.Memberships.selectAll()
                        .where { com.moneat.models.Memberships.user_id eq userId }
                        .firstOrNull()?.get(com.moneat.models.Memberships.organization_id)
                } ?: run {
                    call.respond(HttpStatusCode.BadRequest, com.moneat.models.ErrorResponse("User has no organization"))
                    return@post
                }

                try {
                    val request = call.receive<TriggerIncidentRequest>()
                    
                    val incidentService = com.moneat.services.incident.IncidentService()
                    val config = ApplicationConfig("application.conf")
                    val frontendUrl = config.property("email.frontendUrl").getString()
                    
                    val severityEnum = IncidentSeverity.fromString(request.severity) ?: IncidentSeverity.MEDIUM
                    val sourceEnum = try { AlertSource.valueOf(request.source) } catch(e: Exception) { AlertSource.SYSTEM_ALERT }
                    
                    val event = IncidentEvent(
                        title = request.title,
                        description = request.description,
                        severity = severityEnum,
                        status = IncidentStatus.FIRING,
                        source = sourceEnum,
                        deduplicationKey = "manual-trigger-${java.util.UUID.randomUUID()}",
                        organizationId = orgId,
                        moneatUrl = frontendUrl,
                        metadata = mapOf("triggered_by" to JsonPrimitive(userId.toString()))
                    )
                    
                    incidentService.fireAlert(event)
                    call.respond(HttpStatusCode.OK, AdminSuccessResponse(success = true))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, com.moneat.models.ErrorResponse(e.message ?: "Unknown error"))
                }
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
                    val discordService = com.moneat.services.DiscordService()
                    val config = ApplicationConfig("application.conf")
                    val frontendUrl = config.property("email.frontendUrl").getString()
                    
                    var emailSent = false
                    var slackSent = false
                    var discordSent = false
                    val errors = mutableListOf<String>()
                    
                    val testEmail = request.channel == "email" || request.channel == "both" || request.channel == "all"
                    val testSlack = request.channel == "slack" || request.channel == "both" || request.channel == "all"
                    val testDiscord = request.channel == "discord" || request.channel == "all"
                    
                    // Send email notification if requested
                    if (testEmail) {
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
                                "system_up" -> {
                                    emailService.sendSystemUpEmail(
                                        userEmail,
                                        "[TEST] Production API",
                                        "$frontendUrl/monitoring"
                                    )
                                    emailSent = true
                                }
                                "system_down" -> {
                                    emailService.sendSystemDownEmail(
                                        userEmail,
                                        "[TEST] Production API",
                                        "2 minutes ago",
                                        "$frontendUrl/monitoring"
                                    )
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
                    if (testSlack) {
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

                    // Send Discord notification if requested
                    if (testDiscord) {
                        if (orgId == null) {
                            errors.add("No organization found for Discord testing")
                        } else {
                            try {
                                when (request.type) {
                                    "error_alert" -> {
                                        discordSent = discordService.sendErrorAlert(
                                            organizationId = orgId,
                                            projectName = "[TEST] Test Project",
                                            issueTitle = "NullPointerException in UserService",
                                            level = "error",
                                            firstSeen = "Just now",
                                            eventCount = 42,
                                            userCount = 12,
                                            issueUrl = "$frontendUrl/issues/12345"
                                        )
                                    }
                                    "system_up" -> {
                                        discordSent = discordService.sendSystemUp(
                                            organizationId = orgId,
                                            systemName = "[TEST] Production API",
                                            systemId = java.util.UUID.randomUUID(),
                                            baseUrl = frontendUrl
                                        )
                                    }
                                    "system_down" -> {
                                        discordSent = discordService.sendSystemDown(
                                            organizationId = orgId,
                                            systemName = "[TEST] Production API",
                                            lastSeen = "2 minutes ago",
                                            systemId = java.util.UUID.randomUUID(),
                                            baseUrl = frontendUrl
                                        )
                                    }
                                    "uptime_alert" -> {
                                        discordSent = discordService.sendUptimeAlert(
                                            organizationId = orgId,
                                            monitorUrl = "https://api.example.com/health",
                                            isDown = true,
                                            statusCode = 500,
                                            responseTime = 1245,
                                            errorMessage = "Internal Server Error",
                                            monitorId = java.util.UUID.randomUUID(),
                                            baseUrl = frontendUrl
                                        )
                                    }
                                    else -> {
                                        errors.add("Notification type '${request.type}' not supported for Discord channel")
                                    }
                                }
                                if (!discordSent && errors.isEmpty()) {
                                    errors.add("Discord notification failed (no Discord integration configured or error occurred)")
                                }
                            } catch (e: Exception) {
                                errors.add("Discord failed: ${e.message}")
                            }
                        }
                    }
                    
                    val response = com.moneat.models.TestNotificationResponse(
                        success = emailSent || slackSent || discordSent,
                        emailSent = emailSent,
                        slackSent = slackSent,
                        discordSent = discordSent,
                        errors = errors
                    )
                    
                    call.respond(if (emailSent || slackSent || discordSent) HttpStatusCode.OK else HttpStatusCode.BadRequest, response)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, com.moneat.models.TestNotificationResponse(
                        success = false,
                        emailSent = false,
                        slackSent = false,
                        errors = listOf(e.message ?: "Unknown error")
                    ))
                }
            }

            get("/users") {
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 25
                val search = call.request.queryParameters["search"]
                val users = adminService.getAllUsers(page, limit, search)
                val total = adminService.getTotalUserCount(search)
                call.respond(
                    AdminUsersResponse(
                        users = users,
                        total = total,
                        page = page,
                        limit = limit
                    )
                )
            }

            patch("/users/{userId}") {
                val userId = call.parameters["userId"]?.toIntOrNull()
                if (userId == null) {
                    call.respond(HttpStatusCode.BadRequest, com.moneat.models.ErrorResponse("Invalid user ID"))
                    return@patch
                }
                try {
                    val request = call.receive<com.moneat.services.UpdateUserRequest>()
                    val success = adminService.updateUser(userId, request)
                    if (success) {
                        call.respond(HttpStatusCode.OK, AdminSuccessResponse(success = true))
                    } else {
                        call.respond(HttpStatusCode.NotFound, com.moneat.models.ErrorResponse("User not found"))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, com.moneat.models.ErrorResponse(e.message ?: "Invalid request"))
                }
            }

            route("/billing") {
                get("/tiers") {
                    val tierName = call.request.queryParameters["tier"]?.uppercase()
                    if (tierName.isNullOrBlank()) {
                        call.respond<List<com.moneat.models.BillingPlanResponse>>(pricingTierService.getCurrentPlans())
                    } else {
                        call.respond<List<com.moneat.models.PricingTierConfigResponse>>(pricingTierService.getTierVersions(tierName))
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

                patch("/tiers/{tierName}/versions/{version}") {
                    val tierName = call.parameters["tierName"]?.uppercase()
                    val version = call.parameters["version"]?.toIntOrNull()
                    if (tierName.isNullOrBlank() || version == null) {
                        call.respond(HttpStatusCode.BadRequest, com.moneat.models.ErrorResponse("Missing tier name or version"))
                        return@patch
                    }
                    try {
                        val request = call.receive<com.moneat.models.UpdateStripePriceIdsRequest>()
                        val updated = pricingTierService.updateStripePriceIds(tierName, version, request)
                        call.respond(updated)
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, com.moneat.models.ErrorResponse(e.message ?: "Invalid request"))
                    }
                }

                // Promotional credit management
                val adminBillingService = com.moneat.services.AdminBillingService()

                post("/organizations/{orgId}/promotional-credits") {
                    val principal = call.principal<JWTPrincipal>()
                    val adminUserId = principal?.payload?.getClaim("userId")?.asInt() ?: run {
                        call.respond(HttpStatusCode.Unauthorized, com.moneat.models.ErrorResponse("Invalid token"))
                        return@post
                    }

                    val orgId = call.parameters["orgId"]?.toIntOrNull()
                    if (orgId == null) {
                        call.respond(HttpStatusCode.BadRequest, com.moneat.models.ErrorResponse("Invalid organization ID"))
                        return@post
                    }

                    try {
                        val request = call.receive<com.moneat.models.GrantPromotionalCreditRequest>()
                        val response = adminBillingService.grantPromotionalCredit(
                            organizationId = orgId,
                            grantedByUserId = adminUserId,
                            bonusGb = request.bonusGb,
                            bonusUnits = request.bonusUnits,
                            reason = request.reason
                        )
                        call.respond(HttpStatusCode.Created, response)
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, com.moneat.models.ErrorResponse(e.message ?: "Invalid request"))
                    } catch (e: IllegalStateException) {
                        call.respond(HttpStatusCode.NotFound, com.moneat.models.ErrorResponse(e.message ?: "Organization not found"))
                    }
                }

                get("/organizations/{orgId}/promotional-credits") {
                    val orgId = call.parameters["orgId"]?.toIntOrNull()
                    if (orgId == null) {
                        call.respond(HttpStatusCode.BadRequest, com.moneat.models.ErrorResponse("Invalid organization ID"))
                        return@get
                    }

                    val history = adminBillingService.getPromotionalCreditHistory(orgId)
                    call.respond(history)
                }

                get("/promotional-credits") {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                    val grants = adminBillingService.getAllPromotionalCreditGrants(limit)
                    call.respond(grants)
                }

                delete("/organizations/{orgId}/promotional-credits") {
                    val principal = call.principal<JWTPrincipal>()
                    val adminUserId = principal?.payload?.getClaim("userId")?.asInt() ?: run {
                        call.respond(HttpStatusCode.Unauthorized, com.moneat.models.ErrorResponse("Invalid token"))
                        return@delete
                    }

                    val orgId = call.parameters["orgId"]?.toIntOrNull()
                    if (orgId == null) {
                        call.respond(HttpStatusCode.BadRequest, com.moneat.models.ErrorResponse("Invalid organization ID"))
                        return@delete
                    }

                    val success = adminBillingService.resetPromotionalCredits(orgId, adminUserId)
                    if (success) {
                        call.respond(HttpStatusCode.OK, AdminSuccessResponse(success = true))
                    } else {
                        call.respond(HttpStatusCode.NotFound, com.moneat.models.ErrorResponse("Organization not found"))
                    }
                }
            }
            
            // Attribution analytics endpoint for ROAS tracking
            get("/attribution") {
                val groupBy = call.request.queryParameters["groupBy"] ?: "campaign"
                
                val analytics = attributionAnalyticsService.getAttributionMetrics(groupBy = groupBy)
                call.respond(analytics)
            }
        }
    }
}
