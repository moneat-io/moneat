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

package com.moneat.events.routes

import com.moneat.auth.routes.accountDeletionRoutes
import com.moneat.auth.services.Quadruple
import com.moneat.billing.routes.billingRoutes
import com.moneat.billing.routes.publicBillingRoutes
import com.moneat.billing.services.PricingTierService
import com.moneat.events.models.AddTargetRequest
import com.moneat.events.models.AlertNotificationPreferencesResponse
import com.moneat.events.models.CreateProjectRequest
import com.moneat.events.models.FeedbackUpdateRequest
import com.moneat.events.models.IssueUpdateRequest
import com.moneat.events.models.NotificationPreferencesData
import com.moneat.events.models.NotificationPreferencesResponse
import com.moneat.events.models.OnCallContactResponse
import com.moneat.events.models.ProjectNotificationPreferences
import com.moneat.events.models.SidebarPreferencesResponse
import com.moneat.events.models.UpdateAlertNotificationPreferenceRequest
import com.moneat.events.models.UpdateOnCallContactRequest
import com.moneat.events.models.UpdateProjectRequest
import com.moneat.events.models.UpdateSidebarPreferencesRequest
import com.moneat.events.models.UserResponse
import com.moneat.events.services.DashboardService
import com.moneat.notifications.services.AlertNotificationPreferencesService
import com.moneat.org.routes.integrationCallbackRoutes
import com.moneat.org.routes.integrationRoutes
import com.moneat.plugins.getDemoEpochMs
import com.moneat.plugins.getSentryTransaction
import com.moneat.plugins.isDemoUser
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.NotificationPreferences
import com.moneat.shared.models.OnCallPhoneConsentEvents
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import com.moneat.shared.services.SdkVersionService
import com.moneat.shared.services.SidebarPreferenceService
import com.moneat.utils.DetailedErrorResponse
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.context.GlobalContext
import kotlin.time.Clock
import com.moneat.utils.suspendRunCatching

private const val DEFAULT_PAGE_LIMIT = 25
private const val DEFAULT_EVENTS_LIMIT = 50
private const val DEFAULT_TRANSACTIONS_LIMIT = 20
private const val DEFAULT_REPLAYS_LIMIT = 10
private const val DEFAULT_ALERT_FREQUENCY_MINUTES = 30

fun Route.apiRoutes() {
    val koin = GlobalContext.get()
    val dashboardService = koin.get<DashboardService>()

    // Public routes (no auth required)
    route("/v1") {
        // Public billing plans endpoint
        publicBillingRoutes()
    }

    authenticate("auth-jwt") {
        rateLimit(RateLimitName("api")) {
            route("/v1") {
                // Protected billing routes
                billingRoutes()

                // Subscription tier (for SSO visibility, etc.)
                get("/subscription") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val orgId = principal.payload.getClaim("orgId").asInt()
                    if (orgId == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("No organization access"))
                        return@get
                    }
                    val hasOrganizationAccess =
                        transaction {
                            Memberships
                                .selectAll()
                                .where {
                                    (Memberships.user_id eq userId) and
                                        (Memberships.organization_id eq orgId)
                                }.firstOrNull() != null
                        }
                    if (!hasOrganizationAccess) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("No organization access"))
                        return@get
                    }
                    val pricingTierService = koin.get<PricingTierService>()
                    val context = pricingTierService.getEffectiveTierForOrganization(orgId)
                    call.respond(
                        mapOf(
                            "tier" to mapOf("tierName" to context.tier.tierName)
                        )
                    )
                }

                // Integrations
                integrationRoutes()

                // User profile
                get("/user") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val demoEpochMs = call.getDemoEpochMs()

                    val (user, orgSlug, orgRole, sidebarHiddenItems) =
                        transaction {
                            val userRow =
                                Users.selectAll().where { Users.id eq userId }.firstOrNull()
                                    ?: return@transaction Quadruple(null, null, null, emptyList())

                            val membership =
                                Memberships
                                    .selectAll()
                                    .where { Memberships.user_id eq userId }
                                    .firstOrNull()

                            val slug =
                                membership?.let { m ->
                                    Organizations
                                        .selectAll()
                                        .where { Organizations.id eq m[Memberships.organization_id] }
                                        .firstOrNull()
                                        ?.get(Organizations.slug)
                                }

                            val role = membership?.get(Memberships.role)
                            val hiddenItems = membership?.get(Memberships.sidebar_hidden_items) ?: emptyList()

                            Quadruple(userRow, slug, role, hiddenItems)
                        }

                    if (user == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                    } else {
                        call.respond(
                            UserResponse(
                                user[Users.id],
                                user[Users.email],
                                user[Users.name],
                                user[Users.email_verified],
                                user[Users.onboarding_completed],
                                user[Users.is_admin],
                                orgSlug,
                                orgRole,
                                demoEpochMs,
                                sidebarHiddenItems,
                                user[Users.phone_number],
                                user[Users.timezone]
                            )
                        )
                    }
                }

                // Update phone number
                put("/user/phone-number") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()

                    @kotlinx.serialization.Serializable
                    data class UpdatePhoneRequest(val phoneNumber: String)

                    val request = call.receive<UpdatePhoneRequest>()
                    val phone = request.phoneNumber.trim()

                    // Basic E.164 validation
                    if (!phone.matches(Regex("^\\+[1-9]\\d{1,14}$"))) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Phone number must be in E.164 format (e.g. +15551234567)")
                        )
                        return@put
                    }

                    transaction {
                        Users.update({ Users.id eq userId }) {
                            it[phone_number] = phone
                            it[oncall_phone_opt_in] = false
                            it[oncall_phone_consented_at] = null
                            it[oncall_phone_consent_version] = null
                        }
                    }
                    call.respond(com.moneat.utils.MessageResponse("Phone number updated"))
                }

                // Remove phone number
                delete("/user/phone-number") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    transaction {
                        Users.update({ Users.id eq userId }) {
                            it[phone_number] = null
                            it[oncall_phone_opt_in] = false
                            it[oncall_phone_consented_at] = null
                            it[oncall_phone_consent_version] = null
                        }
                    }
                    call.respond(com.moneat.utils.MessageResponse("Phone number removed"))
                }

                // Get on-call contact/consent status
                get("/user/on-call-contact") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val user =
                        transaction {
                            Users.selectAll().where { Users.id eq userId }.singleOrNull()
                        }
                    if (user == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                        return@get
                    }
                    call.respond(
                        OnCallContactResponse(
                            phoneNumber = user[Users.phone_number],
                            onCallPhoneOptIn = user[Users.oncall_phone_opt_in],
                            onCallPhoneConsentedAt = user[Users.oncall_phone_consented_at]?.toString(),
                            onCallPhoneConsentVersion = user[Users.oncall_phone_consent_version]
                        )
                    )
                }

                // Set on-call phone number with explicit consent
                put("/user/on-call-contact") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val request = call.receive<UpdateOnCallContactRequest>()
                    val phone = request.phoneNumber.trim()

                    if (!phone.matches(Regex("^\\+[1-9]\\d{1,14}$"))) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Phone number must be in E.164 format (e.g. +15551234567)")
                        )
                        return@put
                    }
                    if (request.consentVersion != "v1") {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid consent version"))
                        return@put
                    }

                    val ip =
                        call.request.headers["X-Forwarded-For"]
                            ?.split(",")
                            ?.first()
                            ?.trim()
                            ?: call.request.local.remoteHost
                    val ua = call.request.headers["User-Agent"]

                    transaction {
                        val existing = Users.selectAll().where { Users.id eq userId }.singleOrNull()
                        val phoneChanged = existing?.get(Users.phone_number) != phone

                        Users.update({ Users.id eq userId }) {
                            it[phone_number] = phone
                            it[oncall_phone_opt_in] = request.consentAccepted
                            if (request.consentAccepted) {
                                it[oncall_phone_consented_at] = Clock.System.now()
                                it[oncall_phone_consent_version] = request.consentVersion
                                it[oncall_phone_consent_ip] = ip
                                it[oncall_phone_consent_user_agent] = ua
                                it[oncall_phone_opted_out_at] = null
                            } else if (phoneChanged) {
                                it[oncall_phone_consented_at] = null
                                it[oncall_phone_consent_version] = null
                                it[oncall_phone_consent_ip] = null
                                it[oncall_phone_consent_user_agent] = null
                            }
                        }

                        if (request.consentAccepted) {
                            OnCallPhoneConsentEvents.insert {
                                it[user_id] = userId
                                it[this.phone_number] = phone
                                it[event_type] = "OPT_IN"
                                it[consent_version] = request.consentVersion
                                it[ip_address] = ip
                                it[user_agent] = ua
                                it[created_at] = Clock.System.now()
                            }
                        }
                    }
                    call.respond(
                        com.moneat.utils.MessageResponse(
                            if (request.consentAccepted) {
                                "On-call contact saved and opted in"
                            } else {
                                "On-call contact saved"
                            }
                        )
                    )
                }

                // Remove on-call phone number and consent
                delete("/user/on-call-contact") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()

                    transaction {
                        val existing = Users.selectAll().where { Users.id eq userId }.singleOrNull()
                        val phone = existing?.get(Users.phone_number)
                        if (phone != null) {
                            OnCallPhoneConsentEvents.insert {
                                it[user_id] = userId
                                it[this.phone_number] = phone
                                it[event_type] = "PHONE_REMOVED"
                                it[created_at] = Clock.System.now()
                            }
                        }
                        Users.update({ Users.id eq userId }) {
                            it[phone_number] = null
                            it[oncall_phone_opt_in] = false
                            it[oncall_phone_consented_at] = null
                            it[oncall_phone_consent_version] = null
                            it[oncall_phone_consent_ip] = null
                            it[oncall_phone_consent_user_agent] = null
                            it[oncall_phone_opted_out_at] = null
                        }
                    }
                    call.respond(com.moneat.utils.MessageResponse("On-call contact removed"))
                }

                // Update sidebar preferences
                put("/user/sidebar-preferences") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val organizationIdClaim = principal.payload.getClaim("orgId").asInt()
                    val request = call.receive<UpdateSidebarPreferencesRequest>()

                    val (hiddenItems, errorStatus, errorMessage) =
                        transaction {
                            val membership =
                                if (organizationIdClaim != null) {
                                    Memberships
                                        .selectAll()
                                        .where {
                                            (Memberships.user_id eq userId) and
                                                (Memberships.organization_id eq organizationIdClaim)
                                        }.firstOrNull()
                                } else {
                                    // Avoid cross-org writes when token is missing org context.
                                    val memberships =
                                        Memberships
                                            .selectAll()
                                            .where { Memberships.user_id eq userId }
                                            .limit(2)
                                            .toList()
                                    when {
                                        memberships.isEmpty() -> null

                                        memberships.size == 1 -> memberships.first()

                                        else -> return@transaction Triple<List<String>?, HttpStatusCode?, String?>(
                                            null,
                                            HttpStatusCode.BadRequest,
                                            "Organization context required for users in multiple organizations"
                                        )
                                    }
                                }
                                    ?: return@transaction Triple<List<String>?, HttpStatusCode?, String?>(
                                        null,
                                        HttpStatusCode.NotFound,
                                        "User membership not found"
                                    )

                            val membershipId = membership[Memberships.id]
                            val organizationId = membership[Memberships.organization_id]

                            Triple(
                                SidebarPreferenceService.updatePreferences(
                                    membershipId = membershipId,
                                    userId = userId,
                                    organizationId = organizationId,
                                    hiddenItems = request.hiddenItems,
                                    source = "settings"
                                ),
                                null,
                                null
                            )
                        }

                    if (errorStatus != null) {
                        call.respond(errorStatus, ErrorResponse(errorMessage ?: "Unable to update sidebar preferences"))
                    } else {
                        call.respond(SidebarPreferencesResponse(hiddenItems!!))
                    }
                }

                put("/user/timezone") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()

                    @kotlinx.serialization.Serializable
                    data class UpdateTimezoneRequest(val timezone: String?)

                    @kotlinx.serialization.Serializable
                    data class UpdateTimezoneResponse(val timezone: String?)

                    val request = call.receive<UpdateTimezoneRequest>()
                    val tz = request.timezone?.trim()

                    if (tz != null && tz !in java.time.ZoneId.getAvailableZoneIds()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid timezone identifier"))
                        return@put
                    }

                    transaction {
                        Users.update({ Users.id eq userId }) {
                            it[Users.timezone] = tz
                        }
                    }
                    call.respond(UpdateTimezoneResponse(tz))
                }

                // SDK versions used in setup documentation
                get("/sdk-versions") {
                    val versions = SdkVersionService.getSdkVersions()
                    call.respond(versions)
                }

                // Projects
                get("/projects") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val demoEpochMs = call.getDemoEpochMs()

                    val projects = dashboardService.getProjects(userId, demoEpochMs)
                    call.respond(projects)
                }

                post("/projects") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val request = call.receive<CreateProjectRequest>()

                    try {
                        val project = dashboardService.createProject(userId, request)
                        call.respond(HttpStatusCode.Created, project)
                    } catch (e: IllegalStateException) {
                        if (e.message == "project_limit_reached") {
                            call.respond(
                                HttpStatusCode.Forbidden,
                                DetailedErrorResponse("project_limit_reached", "Project limit reached for your plan")
                            )
                        } else {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse((e.message ?: "Failed to create project"))
                            )
                        }
                    }
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

                post("/projects/{projectId}/targets") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()

                    val projectId = call.parameters["projectId"]?.toLongOrNull()
                    if (projectId == null) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid project ID")
                        return@post
                    }

                    if (!dashboardService.hasProjectAccess(userId, projectId)) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@post
                    }

                    val request = call.receive<AddTargetRequest>()
                    try {
                        val key = dashboardService.addProjectTarget(projectId, request.target)
                        call.respond(HttpStatusCode.Created, key)
                    } catch (e: IllegalStateException) {
                        call.respond(HttpStatusCode.Conflict, ErrorResponse((e.message ?: "Target already exists")))
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
                    val isDemo = call.isDemoUser()
                    val demoEpochMs = call.getDemoEpochMs()

                    val projectId = call.parameters["projectId"]?.toLongOrNull()
                    if (projectId == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }

                    if (!isDemo && !dashboardService.hasProjectAccess(userId, projectId)) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@get
                    }

                    val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_PAGE_LIMIT
                    val status = call.request.queryParameters["status"]

                    val issues = dashboardService.getIssues(projectId, page, limit, status, demoEpochMs)
                    call.respond(issues)
                }

                get("/issues/{issueId}") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val isDemo = call.isDemoUser()
                    val demoEpochMs = call.getDemoEpochMs()

                    val issueId = call.parameters["issueId"]
                    if (issueId == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }

                    val projectId = call.request.queryParameters["projectId"]?.toLongOrNull()

                    if (!isDemo) {
                        if (projectId != null) {
                            if (!dashboardService.hasProjectAccess(userId, projectId)) {
                                call.respond(HttpStatusCode.Forbidden)
                                return@get
                            }
                        } else if (!dashboardService.hasIssueAccess(userId, issueId)) {
                            call.respond(HttpStatusCode.Forbidden)
                            return@get
                        }
                    }

                    val issue = dashboardService.getIssue(issueId, demoEpochMs, projectId)
                    if (issue == null) {
                        call.respond(HttpStatusCode.NotFound)
                    } else {
                        call.respond(issue)
                    }
                }

                get("/issues/{issueId}/events") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val isDemo = call.isDemoUser()
                    val demoEpochMs = call.getDemoEpochMs()

                    val issueId = call.parameters["issueId"]
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_EVENTS_LIMIT
                    val projectId = call.request.queryParameters["projectId"]?.toLongOrNull()

                    if (issueId == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }

                    if (!isDemo) {
                        val hasAccess = if (projectId != null) {
                            dashboardService.hasProjectAccess(userId, projectId)
                        } else {
                            dashboardService.hasIssueAccess(userId, issueId)
                        }
                        if (!hasAccess) {
                            call.respond(HttpStatusCode.Forbidden)
                            return@get
                        }
                    }

                    val events = dashboardService.getIssueEvents(issueId, limit, demoEpochMs, projectId)
                    call.respond(events)
                }

                get("/issues/{issueId}/transactions") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val isDemo = call.isDemoUser()
                    val demoEpochMs = call.getDemoEpochMs()

                    val issueId = call.parameters["issueId"]
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_TRANSACTIONS_LIMIT
                    val projectId = call.request.queryParameters["projectId"]?.toLongOrNull()

                    if (issueId == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }

                    if (!isDemo) {
                        val hasAccess = if (projectId != null) {
                            dashboardService.hasProjectAccess(userId, projectId)
                        } else {
                            dashboardService.hasIssueAccess(userId, issueId)
                        }
                        if (!hasAccess) {
                            call.respond(HttpStatusCode.Forbidden)
                            return@get
                        }
                    }

                    val transactions = dashboardService.getIssueTransactions(issueId, limit, demoEpochMs, projectId)
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

                    val projectId = call.request.queryParameters["projectId"]?.toLongOrNull()
                    val hasAccess = if (projectId != null) {
                        dashboardService.hasProjectAccess(userId, projectId)
                    } else {
                        dashboardService.hasIssueAccess(userId, issueId)
                    }
                    if (!hasAccess) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@patch
                    }

                    val update = call.receive<IssueUpdateRequest>()
                    dashboardService.updateIssue(issueId, update)
                    call.respond(HttpStatusCode.NoContent)
                }

                // Stats
                get("/projects/{projectId}/stats") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val isDemo = call.isDemoUser()
                    val demoEpochMs = call.getDemoEpochMs()

                    val projectId = call.parameters["projectId"]?.toLongOrNull()
                    if (projectId == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }

                    if (!isDemo && !dashboardService.hasProjectAccess(userId, projectId)) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@get
                    }

                    val period = call.request.queryParameters["period"] ?: "7d"
                    val stats =
                        dashboardService.getProjectStats(
                            projectId,
                            period,
                            call.getSentryTransaction(),
                            demoEpochMs
                        )
                    call.respond(stats)
                }

                get("/projects/{projectId}/transactions") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val isDemo = call.isDemoUser()
                    val demoEpochMs = call.getDemoEpochMs()

                    val projectId = call.parameters["projectId"]?.toLongOrNull()
                    if (projectId == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }

                    if (!isDemo && !dashboardService.hasProjectAccess(userId, projectId)) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@get
                    }

                    val period = call.request.queryParameters["period"] ?: "7d"
                    val environment = call.request.queryParameters["environment"]
                    val operation = call.request.queryParameters["operation"]
                    val transactions =
                        dashboardService.getTransactions(
                            projectId,
                            period,
                            environment,
                            operation,
                            demoEpochMs
                        )
                    call.respond(transactions)
                }

                get("/projects/{projectId}/transactions/stats") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val isDemo = call.isDemoUser()
                    val demoEpochMs = call.getDemoEpochMs()

                    val projectId = call.parameters["projectId"]?.toLongOrNull()
                    if (projectId == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }

                    if (!isDemo && !dashboardService.hasProjectAccess(userId, projectId)) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@get
                    }

                    val period = call.request.queryParameters["period"] ?: "7d"
                    val environment = call.request.queryParameters["environment"]
                    val operation = call.request.queryParameters["operation"]
                    val stats =
                        dashboardService.getPerformanceStats(
                            projectId,
                            period,
                            environment,
                            operation,
                            demoEpochMs
                        )
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

                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_TRANSACTIONS_LIMIT
                    val relatedErrors = dashboardService.getRelatedErrorsForTransaction(eventId, limit)
                    call.respond(relatedErrors)
                }

                // Traces
                get("/projects/{projectId}/traces/{traceId}") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()

                    val projectId = call.parameters["projectId"]?.toLongOrNull()
                    val traceId = call.parameters["traceId"]

                    if (projectId == null || traceId == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }

                    if (!dashboardService.hasTraceAccess(userId, projectId)) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@get
                    }

                    val trace = dashboardService.getTraceDetails(projectId, traceId)
                    if (trace == null) {
                        call.respond(HttpStatusCode.NotFound)
                    } else {
                        call.respond(trace)
                    }
                }

                // Spans
                get("/projects/{projectId}/spans/{spanId}") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()

                    val projectId = call.parameters["projectId"]?.toLongOrNull()
                    val spanId = call.parameters["spanId"]

                    if (projectId == null || spanId == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }

                    if (!dashboardService.hasSpanAccess(userId, projectId)) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@get
                    }

                    val span = dashboardService.getSpanDetails(projectId, spanId)
                    if (span == null) {
                        call.respond(HttpStatusCode.NotFound)
                    } else {
                        call.respond(span)
                    }
                }

                // Replays
                get("/projects/{projectId}/replays") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val isDemo = call.isDemoUser()
                    val demoEpochMs = call.getDemoEpochMs()

                    val projectId = call.parameters["projectId"]?.toLongOrNull()
                    if (projectId == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }

                    if (!isDemo && !dashboardService.hasProjectAccess(userId, projectId)) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@get
                    }

                    val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_PAGE_LIMIT
                    val environment = call.request.queryParameters["environment"]
                    val period = call.request.queryParameters["period"] ?: "7d"

                    val replays = dashboardService.getReplays(projectId, page, limit, environment, period, demoEpochMs)
                    call.respond(replays)
                }

                get("/replays/{replayId}") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val isDemo = call.isDemoUser()
                    val demoEpochMs = call.getDemoEpochMs()

                    val replayId = call.parameters["replayId"]
                    if (replayId == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }

                    if (!isDemo && !dashboardService.hasReplayAccess(userId, replayId)) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@get
                    }

                    val replay = dashboardService.getReplay(replayId, demoEpochMs)
                    if (replay == null) {
                        call.respond(HttpStatusCode.NotFound)
                    } else {
                        call.respond(replay)
                    }
                }

                get("/replays/{replayId}/recording") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val isDemo = call.isDemoUser()

                    val replayId = call.parameters["replayId"]
                    if (replayId == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }

                    if (!isDemo && !dashboardService.hasReplayAccess(userId, replayId)) {
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
                    val isDemo = call.isDemoUser()
                    val demoEpochMs = call.getDemoEpochMs()

                    val replayId = call.parameters["replayId"]
                    if (replayId == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }

                    if (!isDemo && !dashboardService.hasReplayAccess(userId, replayId)) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@get
                    }

                    val timeline = dashboardService.getReplayTimeline(replayId, demoEpochMs)
                    call.respond(timeline)
                }

                get("/projects/{projectId}/feedback") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val isDemo = call.isDemoUser()
                    val demoEpochMs = call.getDemoEpochMs()

                    val projectId = call.parameters["projectId"]?.toLongOrNull()
                    if (projectId == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }

                    if (!isDemo && !dashboardService.hasProjectAccess(userId, projectId)) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@get
                    }

                    val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_PAGE_LIMIT
                    val status = call.request.queryParameters["status"]

                    val feedback = dashboardService.getFeedback(projectId, page, limit, status, demoEpochMs)
                    call.respond(feedback)
                }

                get("/feedback/{feedbackId}") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val isDemo = call.isDemoUser()

                    val feedbackId = call.parameters["feedbackId"]
                    if (feedbackId == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }

                    if (!isDemo && !dashboardService.hasFeedbackAccess(userId, feedbackId)) {
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

                    val projectId = call.request.queryParameters["projectId"]?.toLongOrNull()
                    val hasAccess = if (projectId != null) {
                        dashboardService.hasProjectAccess(userId, projectId)
                    } else {
                        dashboardService.hasIssueAccess(userId, issueId)
                    }
                    if (!hasAccess) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@get
                    }

                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_REPLAYS_LIMIT
                    val replays = dashboardService.getReplaysForIssue(issueId, limit)
                    call.respond(replays)
                }

                // Releases
                get("/projects/{projectId}/releases") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()
                    val isDemo = call.isDemoUser()

                    val projectId = call.parameters["projectId"]?.toLongOrNull()
                    if (projectId == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }

                    if (!isDemo && !dashboardService.hasProjectAccess(userId, projectId)) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@get
                    }

                    val releases = dashboardService.getReleases(projectId, call.getSentryTransaction())
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

                    val preferences =
                        transaction {
                            // Get global preferences
                            val global =
                                NotificationPreferences
                                    .selectAll()
                                    .where {
                                        (NotificationPreferences.user_id eq userId) and
                                            (NotificationPreferences.project_id.isNull())
                                    }.firstOrNull()

                            val globalPrefs =
                                if (global != null) {
                                    NotificationPreferencesData(
                                        issueAlerts = global[NotificationPreferences.issue_alerts],
                                        errorAlerts = global[NotificationPreferences.error_alerts],
                                        weeklySummary = global[NotificationPreferences.weekly_summary],
                                        alertFrequencyMinutes = global[NotificationPreferences.alert_frequency_minutes]
                                    )
                                } else {
                                    NotificationPreferencesData(
                                        issueAlerts = true,
                                        errorAlerts = true,
                                        weeklySummary = true,
                                        alertFrequencyMinutes = 30
                                    )
                                }

                            // Get per-project overrides
                            val projects =
                                NotificationPreferences
                                    .selectAll()
                                    .where {
                                        (NotificationPreferences.user_id eq userId) and
                                            (NotificationPreferences.project_id.isNotNull())
                                    }.map { pref ->
                                        val projectId = pref[NotificationPreferences.project_id]!!
                                        val projectName =
                                            Projects
                                                .selectAll()
                                                .where { Projects.id eq projectId }
                                                .firstOrNull()
                                                ?.get(Projects.name) ?: "Unknown"

                                        ProjectNotificationPreferences(
                                            projectId = projectId,
                                            projectName = projectName,
                                            issueAlerts = pref[NotificationPreferences.issue_alerts],
                                            errorAlerts = pref[NotificationPreferences.error_alerts],
                                            weeklySummary = pref[NotificationPreferences.weekly_summary],
                                            alertFrequencyMinutes =
                                            pref[NotificationPreferences.alert_frequency_minutes]
                                        )
                                    }

                            NotificationPreferencesResponse(
                                global = globalPrefs,
                                projects = projects
                            )
                        }

                    call.respond(preferences)
                }

                put("/notification-preferences") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()

                    val request = call.receive<Map<String, Any>>()

                    transaction {
                        val existing =
                            NotificationPreferences
                                .selectAll()
                                .where {
                                    (NotificationPreferences.user_id eq userId) and
                                        (NotificationPreferences.project_id.isNull())
                                }.firstOrNull()

                        val issueAlerts =
                            request["issueAlerts"] as? Boolean
                                ?: existing?.get(NotificationPreferences.issue_alerts) ?: true
                        val errorAlerts =
                            request["errorAlerts"] as? Boolean
                                ?: existing?.get(NotificationPreferences.error_alerts) ?: true
                        val weeklySummary =
                            request["weeklySummary"] as? Boolean
                                ?: existing?.get(NotificationPreferences.weekly_summary) ?: true
                        val alertFrequency =
                            (request["alertFrequencyMinutes"] as? Number)?.toInt()
                                ?: existing?.get(NotificationPreferences.alert_frequency_minutes)
                                ?: DEFAULT_ALERT_FREQUENCY_MINUTES // NOSONAR kotlin:S6619

                        if (existing != null) {
                            NotificationPreferences.update({
                                (NotificationPreferences.user_id eq userId) and
                                    (NotificationPreferences.project_id.isNull())
                            }) {
                                it[issue_alerts] = issueAlerts
                                it[error_alerts] = errorAlerts
                                it[weekly_summary] = weeklySummary
                                it[alert_frequency_minutes] = alertFrequency
                                it[updated_at] = Clock.System.now()
                            }
                        } else {
                            NotificationPreferences.insert {
                                it[user_id] = userId
                                it[project_id] = null
                                it[issue_alerts] = issueAlerts
                                it[error_alerts] = errorAlerts
                                it[weekly_summary] = weeklySummary
                                it[alert_frequency_minutes] = alertFrequency
                                it[created_at] = Clock.System.now()
                                it[updated_at] = Clock.System.now()
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
                        val existing =
                            NotificationPreferences
                                .selectAll()
                                .where {
                                    (NotificationPreferences.user_id eq userId) and
                                        (NotificationPreferences.project_id eq projectId)
                                }.firstOrNull()

                        val issueAlerts =
                            request["issueAlerts"] as? Boolean
                                ?: existing?.get(NotificationPreferences.issue_alerts) ?: true
                        val errorAlerts =
                            request["errorAlerts"] as? Boolean
                                ?: existing?.get(NotificationPreferences.error_alerts) ?: true
                        val weeklySummary =
                            request["weeklySummary"] as? Boolean
                                ?: existing?.get(NotificationPreferences.weekly_summary) ?: true
                        val alertFrequency =
                            (request["alertFrequencyMinutes"] as? Number)?.toInt()
                                ?: existing?.get(NotificationPreferences.alert_frequency_minutes)
                                ?: DEFAULT_ALERT_FREQUENCY_MINUTES // NOSONAR kotlin:S6619

                        if (existing != null) {
                            NotificationPreferences.update({
                                (NotificationPreferences.user_id eq userId) and
                                    (NotificationPreferences.project_id eq projectId)
                            }) {
                                it[issue_alerts] = issueAlerts
                                it[error_alerts] = errorAlerts
                                it[weekly_summary] = weeklySummary
                                it[alert_frequency_minutes] = alertFrequency
                                it[updated_at] = Clock.System.now()
                            }
                        } else {
                            NotificationPreferences.insert {
                                it[user_id] = userId
                                it[NotificationPreferences.project_id] = projectId
                                it[issue_alerts] = issueAlerts
                                it[error_alerts] = errorAlerts
                                it[weekly_summary] = weeklySummary
                                it[alert_frequency_minutes] = alertFrequency
                                it[created_at] = Clock.System.now()
                                it[updated_at] = Clock.System.now()
                            }
                        }
                    }

                    call.respond(HttpStatusCode.OK)
                }

                // Alert Notification Preferences (Unified Alerting System)
                get("/alert-notification-preferences") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()

                    // Get user's primary organization
                    val organizationId =
                        transaction {
                            Memberships
                                .selectAll()
                                .where { Memberships.user_id eq userId }
                                .firstOrNull()
                                ?.get(Memberships.organization_id)
                        }

                    if (organizationId == null) {
                        call.respond(HttpStatusCode.NotFound, "User not in any organization")
                        return@get
                    }

                    val prefsService = koin.get<AlertNotificationPreferencesService>()
                    val preferences = prefsService.getPreferences(userId, organizationId)

                    call.respond(AlertNotificationPreferencesResponse(preferences = preferences))
                }

                put("/alert-notification-preferences/{alertSource}") {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal!!.payload.getClaim("userId").asInt()

                    val alertSource = call.parameters["alertSource"]
                    if (alertSource.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, "Alert source required")
                        return@put
                    }

                    // Get user's primary organization
                    val organizationId =
                        transaction {
                            Memberships
                                .selectAll()
                                .where { Memberships.user_id eq userId }
                                .firstOrNull()
                                ?.get(Memberships.organization_id)
                        }

                    if (organizationId == null) {
                        call.respond(HttpStatusCode.NotFound, "User not in any organization")
                        return@put
                    }

                    val request =
                        suspendRunCatching {
                            call.receive<UpdateAlertNotificationPreferenceRequest>()
                        }.getOrElse { _ ->
                            call.respond(HttpStatusCode.BadRequest, "Invalid request body")
                            return@put
                        }

                    val prefsService = koin.get<AlertNotificationPreferencesService>()
                    try {
                        val updated =
                            prefsService.updatePreference(
                                userId = userId,
                                organizationId = organizationId,
                                alertSource = alertSource,
                                emailEnabled = request.emailEnabled,
                                slackEnabled = request.slackEnabled,
                                discordEnabled = request.discordEnabled
                            )
                        call.respond(updated)
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid alert source")
                    }
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

                // Account Deletion Routes
                accountDeletionRoutes()
            }
        }
    }

    // Unauthenticated routes (OAuth callbacks)
    route("/v1") {
        integrationCallbackRoutes()
    }
}
