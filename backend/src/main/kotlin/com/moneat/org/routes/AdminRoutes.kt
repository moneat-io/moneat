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

package com.moneat.org.routes

import com.moneat.auth.repositories.UserRepositoryImpl
import com.moneat.shared.repositories.MembershipRepositoryImpl
import com.moneat.shared.repositories.OrganizationRepositoryImpl
import com.moneat.auth.services.AuthService
import com.moneat.billing.repositories.SubscriptionRepositoryImpl
import com.moneat.billing.services.PricingTierService
import com.moneat.config.ClickHouseClient
import com.moneat.config.isClickHouseError
import com.moneat.events.models.TriggerIncidentRequest
import com.moneat.incident.models.AlertSource
import com.moneat.incident.models.IncidentEvent
import com.moneat.incident.models.IncidentSeverity
import com.moneat.incident.models.IncidentStatus
import com.moneat.incident.services.IncidentService
import com.moneat.org.services.AdminOrgDetail
import com.moneat.org.services.AdminOrgUsagePoint
import com.moneat.org.services.AdminService
import com.moneat.shared.models.Users
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.isHandled
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}
private val adminJson = Json { ignoreUnknownKeys = true }

@Serializable
data class ReceivedPulse(
    val deploymentId: String,
    val receivedAt: String,
    val cpuCount: Int,
    val memTotalBytes: Long,
    val memUsedBytes: Long,
    val osName: String,
    val osArch: String,
    val jvmVersion: String,
    val projectCount: Long,
    val userCount: Long,
    val eventCount: Long,
    val issueCount: Long,
    val sslEnabled: Boolean,
)

@Serializable
data class ReceivedTelemetryStatus(
    val deploymentCount: Int,
    val lastSeenAt: String?,
    val deployments: List<ReceivedPulse>,
)

private suspend fun queryReceivedTelemetry(): ReceivedTelemetryStatus {
    val db = ClickHouseClient.getDatabase()
    val query = """
        SELECT
            deployment_id,
            toString(argMax(received_at, received_at)) AS last_seen,
            argMax(cpu_count, received_at)       AS cpu_count,
            argMax(mem_total_bytes, received_at) AS mem_total_bytes,
            argMax(mem_used_bytes, received_at)  AS mem_used_bytes,
            argMax(os_name, received_at)         AS os_name,
            argMax(os_arch, received_at)         AS os_arch,
            argMax(jvm_version, received_at)     AS jvm_version,
            argMax(project_count, received_at)   AS project_count,
            argMax(user_count, received_at)      AS user_count,
            argMax(event_count, received_at)     AS event_count,
            argMax(issue_count, received_at)     AS issue_count,
            argMax(ssl_enabled, received_at)     AS ssl_enabled
        FROM `$db`.telemetry_pulses
        GROUP BY deployment_id
        ORDER BY last_seen DESC
        LIMIT 500
        FORMAT JSONEachRow
    """.trimIndent()

    val response = ClickHouseClient.execute(query)
    val body = response.bodyAsText().trim()

    if (response.isClickHouseError(body)) {
        logger.warn { "Failed to query telemetry_pulses: ${body.take(200)}" }
        return ReceivedTelemetryStatus(deploymentCount = 0, lastSeenAt = null, deployments = emptyList())
    }

    val deployments = body.lines()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            try {
                val obj = adminJson.parseToJsonElement(line).jsonObject
                ReceivedPulse(
                    deploymentId = obj["deployment_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    receivedAt = obj["last_seen"]?.jsonPrimitive?.contentOrNull ?: "",
                    cpuCount = obj["cpu_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    memTotalBytes = obj["mem_total_bytes"]?.jsonPrimitive?.longOrNull ?: 0,
                    memUsedBytes = obj["mem_used_bytes"]?.jsonPrimitive?.longOrNull ?: 0,
                    osName = obj["os_name"]?.jsonPrimitive?.contentOrNull ?: "",
                    osArch = obj["os_arch"]?.jsonPrimitive?.contentOrNull ?: "",
                    jvmVersion = obj["jvm_version"]?.jsonPrimitive?.contentOrNull ?: "",
                    projectCount = obj["project_count"]?.jsonPrimitive?.longOrNull ?: 0,
                    userCount = obj["user_count"]?.jsonPrimitive?.longOrNull ?: 0,
                    eventCount = obj["event_count"]?.jsonPrimitive?.longOrNull ?: 0,
                    issueCount = obj["issue_count"]?.jsonPrimitive?.longOrNull ?: 0,
                    sslEnabled = (obj["ssl_enabled"]?.jsonPrimitive?.intOrNull ?: 0) == 1,
                )
            } catch (e: Exception) {
                logger.warn { "Failed to parse telemetry pulse row: ${e.message}" }
                null
            }
        }

    return ReceivedTelemetryStatus(
        deploymentCount = deployments.size,
        lastSeenAt = deployments.firstOrNull()?.receivedAt,
        deployments = deployments,
    )
}

@Serializable
private data class AdminUsersResponse(
    val users: List<com.moneat.org.services.AdminUserSummary>,
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
    val authService = AuthService(UserRepositoryImpl(), MembershipRepositoryImpl(), OrganizationRepositoryImpl())
    val pricingTierService = PricingTierService()
    val attributionAnalyticsService = com.moneat.shared.services.AttributionAnalyticsService()

    authenticate("auth-jwt") {
        route("/v1/admin") {
            // Use on(AuthenticationChecked) which runs in the AfterAuthentication phase,
            // guaranteeing the JWT principal is available after auth completes.
            // Note: createRouteScopedPlugin's onCall runs at the Plugins phase (before auth),
            // which is why the original code always saw a null principal.
            install(
                createRouteScopedPlugin("AdminCheck") {
                    on(AuthenticationChecked) { call ->
                        if (call.isHandled) return@on
                        val principal = call.principal<JWTPrincipal>()
                        if (principal == null) {
                            logger.warn { "Admin access denied: no JWT principal for ${call.request.path()}" }
                            call.respond(
                                HttpStatusCode.Unauthorized,
                                com.moneat.utils.ErrorResponse("Authentication required")
                            )
                            return@on
                        }
                        val userId = principal.payload.getClaim("userId").asInt()
                        val isAdmin =
                            transaction {
                                Users
                                    .selectAll()
                                    .where { Users.id eq userId }
                                    .firstOrNull()
                                    ?.get(Users.is_admin) ?: false
                            }
                        if (!isAdmin) {
                            logger.warn { "Admin access denied: user $userId is not admin (path=${call.request.path()})" }
                            call.respond(
                                HttpStatusCode.Forbidden,
                                com.moneat.utils.ErrorResponse("Admin access required")
                            )
                        }
                    }
                }
            )

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
                val response =
                    usage.map { u ->
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
                val targetUserId =
                    call.parameters["userId"]?.toIntOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, com.moneat.utils.ErrorResponse("Invalid user ID"))

                val targetUser =
                    transaction {
                        Users.selectAll().where { Users.id eq targetUserId }.firstOrNull()
                    } ?: return@post call.respond(HttpStatusCode.NotFound, com.moneat.utils.ErrorResponse("User not found"))

                val token =
                    authService.generateImpersonationToken(
                        targetUser[Users.id],
                        targetUser[Users.email]
                    )
                call.respond(AdminImpersonationTokenResponse(token = token))
            }

            post("/incidents/trigger") {
                val principal = call.principal<JWTPrincipal>()
                val userId =
                    principal?.payload?.getClaim("userId")?.asInt() ?: run {
                        call.respond(HttpStatusCode.Unauthorized, com.moneat.utils.ErrorResponse("Invalid token"))
                        return@post
                    }

                // Get user's organization
                val orgId =
                    transaction {
                        com.moneat.shared.models.Memberships
                            .selectAll()
                            .where { com.moneat.shared.models.Memberships.user_id eq userId }
                            .firstOrNull()
                            ?.get(com.moneat.shared.models.Memberships.organization_id)
                    } ?: run {
                        call.respond(HttpStatusCode.BadRequest, com.moneat.utils.ErrorResponse("User has no organization"))
                        return@post
                    }

                try {
                    val request = call.receive<TriggerIncidentRequest>()

                    val incidentService =
                        IncidentService()
                    val config = ApplicationConfig("application.conf")
                    val frontendUrl = config.property("email.frontendUrl").getString()

                    val severityEnum = IncidentSeverity.fromString(request.severity) ?: IncidentSeverity.MEDIUM
                    val sourceEnum =
                        try {
                            AlertSource.valueOf(request.source)
                        } catch (e: Exception) {
                            val msg = "Invalid AlertSource '${request.source}', defaulting to HOST_ALERT: ${e.message}"
                            logger.warn { msg }
                            AlertSource.HOST_ALERT
                        }

                    val event =
                        IncidentEvent(
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
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        com.moneat.utils.ErrorResponse(e.message ?: "Unknown error")
                    )
                }
            }

            post("/test-notification") {
                val principal = call.principal<JWTPrincipal>()
                val userId =
                    principal?.payload?.getClaim("userId")?.asInt() ?: run {
                        call.respond(HttpStatusCode.Unauthorized, com.moneat.utils.ErrorResponse("Invalid token"))
                        return@post
                    }

                try {
                    val request = call.receive<com.moneat.events.models.TestNotificationRequest>()

                    // Get user email for testing - use testEmail from request if provided
                    val userEmail =
                        if (!request.testEmail.isNullOrBlank()) {
                            request.testEmail
                        } else {
                            transaction {
                                Users
                                    .selectAll()
                                    .where { Users.id eq userId }
                                    .firstOrNull()
                                    ?.get(Users.email)
                            }
                        } ?: run {
                            call.respond(HttpStatusCode.BadRequest, com.moneat.utils.ErrorResponse("User not found"))
                            return@post
                        }

                    // Get user's organization for Slack testing
                    val orgId =
                        transaction {
                            com.moneat.shared.models.Memberships
                                .selectAll()
                                .where { com.moneat.shared.models.Memberships.user_id eq userId }
                                .firstOrNull()
                                ?.get(com.moneat.shared.models.Memberships.organization_id)
                        }

                    val emailService = com.moneat.notifications.services.EmailService()
                    val slackService = com.moneat.notifications.services.SlackService()
                    val discordService = com.moneat.notifications.services.DiscordService()
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
                                    val testData =
                                        com.moneat.notifications.services.EmailService.ErrorAlertData(
                                            issueTitle = "[TEST] NullPointerException in UserService",
                                            issueLevel = "error",
                                            issueCulprit = "com.example.UserService.getUser",
                                            issueMessage = "Cannot read property 'id' of null",
                                            issueCount = "42",
                                            issueUrl = "$frontendUrl/issues/12345",
                                            projectName = "Test Project",
                                            environment = "production",
                                            timestamp =
                                            java.time.Instant
                                                .now()
                                                .toString(),
                                            stackTrace = "  at UserService.getUser (UserService.kt:45)\n  at UserController.handleRequest (UserController.kt:23)\n  at Router.dispatch (Router.kt:89)",
                                            settingsUrl = "$frontendUrl/settings/notifications",
                                            unsubscribeUrl = "$frontendUrl/settings/notifications"
                                        )
                                    emailService.sendErrorAlertEmail(userEmail, testData)
                                    emailSent = true
                                }

                                "weekly_summary" -> {
                                    val notificationService =
                                        com.moneat.notifications.services.NotificationService(emailService)
                                    notificationService.sendWeeklySummaryForUser(userId, userEmail)
                                    emailSent = true
                                }

                                "verification" -> {
                                    emailService.sendVerificationEmail(userEmail, "test-token-12345", "Test User")
                                    emailSent = true
                                }

                                "password_reset" -> {
                                    emailService.sendPasswordResetEmail(
                                        userEmail,
                                        "test-reset-token-67890",
                                        "Test User"
                                    )
                                    emailSent = true
                                }

                                "system_up", "host_up" -> {
                                    emailService.sendHostUpEmail(
                                        userEmail,
                                        "[TEST] Production API",
                                        "$frontendUrl/monitoring/hosts/1"
                                    )
                                    emailSent = true
                                }

                                "system_down", "host_down" -> {
                                    emailService.sendHostDownEmail(
                                        userEmail,
                                        "[TEST] Production API",
                                        "2 minutes ago",
                                        "$frontendUrl/monitoring/hosts/1"
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
                                        slackSent =
                                            slackService.sendErrorAlert(
                                                organizationId = orgId,
                                                projectName = "[TEST] Test Project",
                                                issueTitle = "NullPointerException in UserService",
                                                level = "error",
                                                culprit = "com.example.UserService.getUser",
                                                issueId = 12345L,
                                                baseUrl = frontendUrl,
                                                occurrenceCount = 42,
                                                environment = "production",
                                                timestamp =
                                                java.time.Instant
                                                    .now()
                                                    .toString(),
                                                stackTrace = "  at UserService.getUser (UserService.kt:45)\n  at UserController.handleRequest (UserController.kt:23)\n  at Router.dispatch (Router.kt:89)"
                                            )
                                    }

                                    "system_up", "host_up" -> {
                                        slackSent =
                                            slackService.sendHostUp(
                                                organizationId = orgId,
                                                hostName = "[TEST] Production API",
                                                hostId = 1,
                                                baseUrl = frontendUrl
                                            )
                                    }

                                    "system_down", "host_down" -> {
                                        slackSent =
                                            slackService.sendHostDown(
                                                organizationId = orgId,
                                                hostName = "[TEST] Production API",
                                                lastSeen = "2 minutes ago",
                                                hostId = 1,
                                                baseUrl = frontendUrl
                                            )
                                    }

                                    "uptime_alert" -> {
                                        slackSent =
                                            slackService.sendUptimeAlert(
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
                                        errors.add(
                                            "Notification type '${request.type}' not supported for Slack channel"
                                        )
                                    }
                                }
                                if (!slackSent && errors.isEmpty()) {
                                    errors.add(
                                        "Slack notification failed (no Slack integration configured or error occurred)"
                                    )
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
                                        discordSent =
                                            discordService.sendErrorAlert(
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

                                    "system_up", "host_up" -> {
                                        discordSent =
                                            discordService.sendHostUp(
                                                organizationId = orgId,
                                                hostName = "[TEST] Production API",
                                                hostId = 1,
                                                baseUrl = frontendUrl
                                            )
                                    }

                                    "system_down", "host_down" -> {
                                        discordSent =
                                            discordService.sendHostDown(
                                                organizationId = orgId,
                                                hostName = "[TEST] Production API",
                                                lastSeen = "2 minutes ago",
                                                hostId = 1,
                                                baseUrl = frontendUrl
                                            )
                                    }

                                    "uptime_alert" -> {
                                        discordSent =
                                            discordService.sendUptimeAlert(
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
                                        errors.add(
                                            "Notification type '${request.type}' not supported for Discord channel"
                                        )
                                    }
                                }
                                if (!discordSent && errors.isEmpty()) {
                                    errors.add(
                                        "Discord notification failed (no Discord integration configured or error occurred)"
                                    )
                                }
                            } catch (e: Exception) {
                                errors.add("Discord failed: ${e.message}")
                            }
                        }
                    }

                    val response =
                        com.moneat.events.models.TestNotificationResponse(
                            success = emailSent || slackSent || discordSent,
                            emailSent = emailSent,
                            slackSent = slackSent,
                            discordSent = discordSent,
                            errors = errors
                        )

                    call.respond(
                        if (emailSent || slackSent || discordSent) HttpStatusCode.OK else HttpStatusCode.BadRequest,
                        response
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        com.moneat.events.models.TestNotificationResponse(
                            success = false,
                            emailSent = false,
                            slackSent = false,
                            errors = listOf(e.message ?: "Unknown error")
                        )
                    )
                }
            }

            post("/test-sms-call") {
                val principal = call.principal<JWTPrincipal>()
                val userId =
                    principal?.payload?.getClaim("userId")?.asInt() ?: run {
                        call.respond(HttpStatusCode.Unauthorized, com.moneat.utils.ErrorResponse("Invalid token"))
                        return@post
                    }

                @Serializable
                data class TestSmsCallRequest(val channel: String)

                try {
                    val request = call.receive<TestSmsCallRequest>()

                    // TwilioService is in the enterprise module — access via reflection
                    val twilioServiceClass =
                        try {
                            Class.forName("com.moneat.enterprise.services.oncall.TwilioService")
                        } catch (_: ClassNotFoundException) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                com.moneat.utils.ErrorResponse("On-call features require the enterprise module")
                            )
                            return@post
                        }
                    val twilioService = twilioServiceClass.getMethod("getInstance").invoke(null)
                    val isEnabled = twilioServiceClass.getMethod("isEnabled").invoke(twilioService) as Boolean

                    if (!isEnabled) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            com.moneat.utils.ErrorResponse(
                                "Twilio is not configured (missing TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, or TWILIO_FROM_NUMBER)"
                            )
                        )
                        return@post
                    }

                    // Use the authenticated admin's own saved, consented on-call number
                    val user =
                        transaction {
                            Users.selectAll().where { Users.id eq userId }.singleOrNull()
                        }
                    val phoneNumber = user?.get(Users.phone_number)
                    val consented = user?.get(Users.oncall_phone_opt_in) ?: false

                    if (phoneNumber.isNullOrBlank() || !consented) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            com.moneat.utils.ErrorResponse(
                                "No consented on-call phone number configured. Please set up your on-call contact in notification settings first."
                            )
                        )
                        return@post
                    }

                    when (request.channel) {
                        "sms" ->
                            twilioServiceClass
                                .getMethod("sendTestSmsBlocking", String::class.java)
                                .invoke(twilioService, phoneNumber)
                        "call" ->
                            twilioServiceClass
                                .getMethod("makeTestCallBlocking", String::class.java)
                                .invoke(twilioService, phoneNumber)
                        else -> {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                com.moneat.utils.ErrorResponse("channel must be 'sms' or 'call'")
                            )
                            return@post
                        }
                    }

                    call.respond(HttpStatusCode.OK, AdminSuccessResponse(success = true))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        com.moneat.utils.ErrorResponse(e.message ?: "Unknown error")
                    )
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
                    call.respond(HttpStatusCode.BadRequest, com.moneat.utils.ErrorResponse("Invalid user ID"))
                    return@patch
                }
                try {
                    val request = call.receive<com.moneat.org.services.UpdateUserRequest>()
                    val success = adminService.updateUser(userId, request)
                    if (success) {
                        call.respond(HttpStatusCode.OK, AdminSuccessResponse(success = true))
                    } else {
                        call.respond(HttpStatusCode.NotFound, com.moneat.utils.ErrorResponse("User not found"))
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        com.moneat.utils.ErrorResponse(e.message ?: "Invalid request")
                    )
                }
            }

            delete("/users") {
                try {
                    val request = call.receive<com.moneat.org.services.DeleteUsersRequest>()
                    val result = adminService.deleteUsers(request.userIds)
                    call.respond(HttpStatusCode.OK, result)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        com.moneat.utils.ErrorResponse(e.message ?: "Invalid request")
                    )
                }
            }

            route("/billing") {
                get("/tiers") {
                    val tierName = call.request.queryParameters["tier"]?.uppercase()
                    if (tierName.isNullOrBlank()) {
                        call.respond<List<com.moneat.billing.models.BillingPlanResponse>>(
                            pricingTierService.getCurrentPlans()
                        )
                    } else {
                        call.respond<List<com.moneat.billing.models.PricingTierConfigResponse>>(
                            pricingTierService.getTierVersions(tierName)
                        )
                    }
                }

                post("/tiers/{tierName}/versions") {
                    val tierName = call.parameters["tierName"]?.uppercase()
                    if (tierName.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, com.moneat.utils.ErrorResponse("Missing tier name"))
                        return@post
                    }
                    try {
                        val request = call.receive<com.moneat.billing.models.CreateTierVersionRequest>()
                        val created = pricingTierService.createTierVersion(tierName, request)
                        call.respond(HttpStatusCode.Created, created)
                    } catch (e: IllegalArgumentException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            com.moneat.utils.ErrorResponse(e.message ?: "Invalid request")
                        )
                    }
                }

                post("/tiers/{tierName}/migrate") {
                    val tierName = call.parameters["tierName"]?.uppercase()
                    if (tierName.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, com.moneat.utils.ErrorResponse("Missing tier name"))
                        return@post
                    }
                    try {
                        val request = call.receive<com.moneat.billing.models.TierMigrationRequest>()
                        val response = pricingTierService.migrateSubscribers(tierName, request)
                        call.respond(response)
                    } catch (e: IllegalArgumentException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            com.moneat.utils.ErrorResponse(e.message ?: "Invalid request")
                        )
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
                        call.respond(
                            HttpStatusCode.BadRequest,
                            com.moneat.utils.ErrorResponse("Missing tier name or version")
                        )
                        return@patch
                    }
                    try {
                        val request = call.receive<com.moneat.billing.models.UpdateStripePriceIdsRequest>()
                        val updated = pricingTierService.updateStripePriceIds(tierName, version, request)
                        call.respond(updated)
                    } catch (e: IllegalArgumentException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            com.moneat.utils.ErrorResponse(e.message ?: "Invalid request")
                        )
                    }
                }

                // Promotional credit management
                val adminBillingService = com.moneat.billing.services.AdminBillingService(SubscriptionRepositoryImpl())

                post("/organizations/{orgId}/promotional-credits") {
                    val principal = call.principal<JWTPrincipal>()
                    val adminUserId =
                        principal?.payload?.getClaim("userId")?.asInt() ?: run {
                            call.respond(HttpStatusCode.Unauthorized, com.moneat.utils.ErrorResponse("Invalid token"))
                            return@post
                        }

                    val orgId = call.parameters["orgId"]?.toIntOrNull()
                    if (orgId == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            com.moneat.utils.ErrorResponse("Invalid organization ID")
                        )
                        return@post
                    }

                    try {
                        val request = call.receive<com.moneat.billing.models.GrantPromotionalCreditRequest>()
                        val response =
                            adminBillingService.grantPromotionalCredit(
                                organizationId = orgId,
                                grantedByUserId = adminUserId,
                                bonusGb = request.bonusGb,
                                bonusUnits = request.bonusUnits,
                                reason = request.reason
                            )
                        call.respond(HttpStatusCode.Created, response)
                    } catch (e: IllegalArgumentException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            com.moneat.utils.ErrorResponse(e.message ?: "Invalid request")
                        )
                    } catch (e: IllegalStateException) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            com.moneat.utils.ErrorResponse(e.message ?: "Organization not found")
                        )
                    }
                }

                get("/organizations/{orgId}/promotional-credits") {
                    val orgId = call.parameters["orgId"]?.toIntOrNull()
                    if (orgId == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            com.moneat.utils.ErrorResponse("Invalid organization ID")
                        )
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
                    val adminUserId =
                        principal?.payload?.getClaim("userId")?.asInt() ?: run {
                            call.respond(HttpStatusCode.Unauthorized, com.moneat.utils.ErrorResponse("Invalid token"))
                            return@delete
                        }

                    val orgId = call.parameters["orgId"]?.toIntOrNull()
                    if (orgId == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            com.moneat.utils.ErrorResponse("Invalid organization ID")
                        )
                        return@delete
                    }

                    val success = adminBillingService.resetPromotionalCredits(orgId, adminUserId)
                    if (success) {
                        call.respond(HttpStatusCode.OK, AdminSuccessResponse(success = true))
                    } else {
                        call.respond(HttpStatusCode.NotFound, com.moneat.utils.ErrorResponse("Organization not found"))
                    }
                }
            }

            // Attribution analytics endpoint for ROAS tracking
            get("/attribution") {
                val groupBy = call.request.queryParameters["groupBy"] ?: "campaign"

                val analytics = attributionAnalyticsService.getAttributionMetrics(groupBy = groupBy)
                call.respond(analytics)
            }

            // Telemetry — received pulses from self-hosted deployments (cloud platform only)
            get("/telemetry") {
                val status = queryReceivedTelemetry()
                call.respond(status)
            }
        }
    }
}
