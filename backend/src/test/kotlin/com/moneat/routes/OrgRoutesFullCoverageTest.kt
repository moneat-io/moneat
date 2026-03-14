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

package com.moneat.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.auth.services.AuthService
import com.moneat.billing.services.AdminBillingService
import com.moneat.billing.services.PricingTierService
import com.moneat.incident.services.IncidentService
import com.moneat.notifications.services.DiscordService
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.SlackService
import com.moneat.org.routes.adminRoutes
import com.moneat.org.routes.integrationCallbackRoutes
import com.moneat.org.routes.integrationRoutes
import com.moneat.org.services.AdminService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrganizationIntegrations
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.SlackUserMappings
import com.moneat.shared.models.Users
import com.moneat.shared.services.AttributionAnalyticsResponse
import com.moneat.shared.services.AttributionAnalyticsService
import com.moneat.shared.services.AttributionSummary
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrgRoutesFullCoverageTest {

    companion object {
        private const val JWT_SECRET = "org-full-coverage-secret"
        private var dbInitialized = false
    }

    private val mockAdminService = mockk<AdminService>(relaxed = true)
    private val mockAuthService = mockk<AuthService>(relaxed = true)
    private val mockPricingTierService = mockk<PricingTierService>(relaxed = true)
    private val mockAttributionService = mockk<AttributionAnalyticsService>(relaxed = true)
    private val mockEmailService = mockk<EmailService>(relaxed = true)
    private val mockSlackService = mockk<SlackService>(relaxed = true)
    private val mockDiscordService = mockk<DiscordService>(relaxed = true)
    private val mockIncidentService = mockk<IncidentService>(relaxed = true)
    private val mockAdminBillingService = mockk<AdminBillingService>(relaxed = true)

    @BeforeTest
    fun setup() {
        startTestKoin()
        loadKoinModules(
            module {
                single<AdminService> { mockAdminService }
                single<AuthService> { mockAuthService }
                single<PricingTierService> { mockPricingTierService }
                single<AttributionAnalyticsService> { mockAttributionService }
                single<EmailService> { mockEmailService }
                single<SlackService> { mockSlackService }
                single<DiscordService> { mockDiscordService }
                single<IncidentService> { mockIncidentService }
                single<AdminBillingService> { mockAdminBillingService }
            }
        )
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_org_full;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            dbInitialized = true
        }
        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Memberships,
            OrganizationIntegrations,
            SlackUserMappings
        )
    }

    @AfterTest
    fun teardown() {
        stopTestKoin()
    }

    private fun Application.installTestApp() {
        install(ContentNegotiation) { json() }
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(
                    JWT.require(Algorithm.HMAC256(JWT_SECRET))
                        .withIssuer("moneat").withAudience("moneat-users").build()
                )
                validate { JWTPrincipal(it.payload) }
            }
        }
    }

    private fun token(userId: Int, orgId: Int): String = JWT.create()
        .withIssuer("moneat")
        .withAudience("moneat-users")
        .withClaim("userId", userId)
        .withClaim("orgId", orgId)
        .withClaim("email", "user$userId@test.com")
        .sign(Algorithm.HMAC256(JWT_SECRET))

    private fun seedOrg(name: String): Int = transaction {
        Organizations.insert {
            it[Organizations.name] = name
            it[Organizations.slug] = name.lowercase()
        } get Organizations.id
    }

    private fun seedUser(
        email: String,
        admin: Boolean = false
    ): Int = transaction {
        Users.insert {
            it[Users.email] = email
            it[Users.password_hash] = "hashed"
            it[Users.name] = email.substringBefore("@")
            it[Users.email_verified] = true
            it[Users.is_admin] = admin
        } get Users.id
    }

    private fun seedMembership(orgId: Int, userId: Int, role: String) = transaction {
        Memberships.insert {
            it[Memberships.organization_id] = orgId
            it[Memberships.user_id] = userId
            it[Memberships.role] = role
        }
    }

    private fun seedIntegration(
        orgId: Int,
        type: String,
        accessToken: String? = "tok-123",
        teamId: String? = "T123",
        teamName: String? = "TestTeam",
        channelId: String? = null,
        channelName: String? = null
    ): Int = transaction {
        OrganizationIntegrations.insert {
            it[OrganizationIntegrations.organization_id] = orgId
            it[OrganizationIntegrations.integration_type] = type
            it[OrganizationIntegrations.access_token] = accessToken
            it[OrganizationIntegrations.team_id] = teamId
            it[OrganizationIntegrations.team_name] = teamName
            it[OrganizationIntegrations.channel_id] = channelId
            it[OrganizationIntegrations.channel_name] = channelName
            it[OrganizationIntegrations.enabled] = true
            it[OrganizationIntegrations.created_at] = kotlin.time.Clock.System.now()
            it[OrganizationIntegrations.updated_at] = kotlin.time.Clock.System.now()
        } get OrganizationIntegrations.id
    }

    // ═══════════════════════════════════════════════════════════════
    //  AdminRoutes — GET /v1/admin/overview
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `admin overview returns stats for admin user`() {
        val adminId = seedUser("admin@test.com", admin = true)
        coEvery { mockAdminService.getOverviewStats() } returns
            com.moneat.org.services.AdminOverviewStats(
                totalOrganizations = 5, totalUsers = 20,
                totalEventsAllTime = 1000L, totalEventsLast30Days = 500L,
                mrr = 100.0, subscriptionsByPlan = emptyMap(),
                eventsLast30Days = emptyList()
            )

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/overview") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("totalOrganizations"))
        }
    }

    @Test
    fun `admin overview returns 403 for non-admin user`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("regular@test.com", admin = false)
        seedMembership(orgId, userId, "member")

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/overview") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    @Test
    fun `admin endpoint returns 401 without auth`() {
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/overview")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  AdminRoutes — GET /v1/admin/organizations
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `admin organizations returns list`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAdminService.getAllOrganizations(1, 25) } returns emptyList()

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/organizations") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `admin organizations respects pagination params`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAdminService.getAllOrganizations(2, 10) } returns emptyList()

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/organizations?page=2&limit=10") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  AdminRoutes — GET /v1/admin/organizations/{orgId}
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `admin org detail returns org data`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAdminService.getOrgDetail(42) } returns
            com.moneat.org.services.AdminOrgDetail(
                id = 42, name = "Acme", slug = "acme",
                companySize = null, plan = "free",
                subscriptionStatus = null, memberCount = 1,
                projectCount = 0, eventCountThisMonth = 100,
                bytesIngestedThisMonth = 5000, quotaUsedPercent = null,
                members = emptyList(), projects = emptyList()
            )

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/organizations/42") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Acme"))
        }
    }

    @Test
    fun `admin org detail returns 404 for nonexistent org`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAdminService.getOrgDetail(999) } returns null

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/organizations/999") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `admin org detail returns 400 for invalid org id`() {
        val adminId = seedUser("admin@test.com", admin = true)

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/organizations/abc") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  AdminRoutes — GET /v1/admin/organizations/{orgId}/usage
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `admin org usage returns usage data`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAdminService.getOrgUsage(42, "7d") } returns listOf(
            com.moneat.shared.services.OrgUsageSummary(
                date = kotlinx.datetime.LocalDate(2026, 1, 1),
                eventType = "error", eventCount = 100, bytesIngested = 5000
            )
        )

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/organizations/42/usage") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("error"))
        }
    }

    @Test
    fun `admin org usage returns 400 for invalid org id`() {
        val adminId = seedUser("admin@test.com", admin = true)

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/organizations/xyz/usage") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `admin org usage accepts period param`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAdminService.getOrgUsage(42, "30d") } returns emptyList()

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/organizations/42/usage?period=30d") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  AdminRoutes — GET /v1/admin/usage
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `admin usage breakdown returns data`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAdminService.getUsageBreakdown("7d") } returns
            com.moneat.org.services.AdminUsageBreakdown(
                daily = emptyList(), totalBytes = 50000L
            )

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/usage") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  AdminRoutes — GET /v1/admin/revenue
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `admin revenue returns metrics`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAdminService.getRevenueMetrics() } returns
            com.moneat.org.services.AdminRevenueMetrics(
                mrr = 100.0, subscriptionsByPlan = emptyMap(),
                estimatedCostPerOrg = emptyMap(), churnLast30Days = 0
            )

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/revenue") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  AdminRoutes — GET /v1/admin/infrastructure
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `admin infrastructure returns health data`() {
        val adminId = seedUser("admin@test.com", admin = true)
        coEvery { mockAdminService.getInfrastructureHealth() } returns
            com.moneat.org.services.AdminInfrastructureHealth(
                clickhouseTables = emptyList(), totalDiskBytes = 0L,
                totalRows = 0L, storageUsedPercent = 0.0,
                scalingTriggerAlerts = emptyList()
            )

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/infrastructure") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  AdminRoutes — GET /v1/admin/top-consumers
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `admin top consumers returns list`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAdminService.getTopConsumers(10) } returns emptyList()

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/top-consumers") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `admin top consumers accepts limit param`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAdminService.getTopConsumers(5) } returns emptyList()

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/top-consumers?limit=5") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  AdminRoutes — GET /v1/admin/emails
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `admin emails returns stats`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAdminService.getEmailStats("30d") } returns
            com.moneat.org.services.AdminEmailStats(
                totalSent = 100L, byType = emptyMap(),
                last7Days = emptyList(), last30Days = emptyList(),
                estimatedCost = 0.0
            )

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/emails") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `admin emails accepts period param`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAdminService.getEmailStats("7d") } returns
            com.moneat.org.services.AdminEmailStats(
                totalSent = 50L, byType = emptyMap(),
                last7Days = emptyList(), last30Days = emptyList(),
                estimatedCost = 0.0
            )

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/emails?period=7d") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  AdminRoutes — POST /v1/admin/impersonate/{userId}
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `admin impersonate returns token`() {
        val adminId = seedUser("admin@test.com", admin = true)
        val targetId = seedUser("target@test.com")
        every { mockAuthService.generateImpersonationToken(targetId, "target@test.com") } returns "imp-token-123"

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.post("/v1/admin/impersonate/$targetId") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("imp-token-123"))
        }
    }

    @Test
    fun `admin impersonate returns 404 for nonexistent user`() {
        val adminId = seedUser("admin@test.com", admin = true)

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.post("/v1/admin/impersonate/99999") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `admin impersonate returns 400 for invalid user id`() {
        val adminId = seedUser("admin@test.com", admin = true)

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.post("/v1/admin/impersonate/abc") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  AdminRoutes — GET /v1/admin/users
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `admin users returns paginated list`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAdminService.getAllUsers(1, 25, null) } returns emptyList()
        every { mockAdminService.getTotalUserCount(null) } returns 0

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/users") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"users\""))
        }
    }

    @Test
    fun `admin users accepts search and pagination params`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAdminService.getAllUsers(2, 10, "test") } returns emptyList()
        every { mockAdminService.getTotalUserCount("test") } returns 0

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/users?page=2&limit=10&search=test") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  AdminRoutes — PATCH /v1/admin/users/{userId}
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `admin update user returns success`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAdminService.updateUser(any(), any()) } returns true

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.patch("/v1/admin/users/42") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
                contentType(ContentType.Application.Json)
                setBody("""{"isAdmin":true}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("true"))
        }
    }

    @Test
    fun `admin update user returns 404 for nonexistent user`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAdminService.updateUser(any(), any()) } returns false

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.patch("/v1/admin/users/999") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
                contentType(ContentType.Application.Json)
                setBody("""{"isAdmin":false}""")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `admin update user returns 400 for invalid user id`() {
        val adminId = seedUser("admin@test.com", admin = true)

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.patch("/v1/admin/users/abc") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
                contentType(ContentType.Application.Json)
                setBody("""{"isAdmin":true}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `admin update user returns 400 for service exception`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAdminService.updateUser(any(), any()) } throws
            IllegalArgumentException("Invalid field")

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.patch("/v1/admin/users/42") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Bad"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  AdminRoutes — DELETE /v1/admin/users
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `admin delete users returns result`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAdminService.deleteUsers(listOf(10, 20)) } returns
            com.moneat.org.services.DeleteUsersResponse(
                success = true, deletedCount = 2, errors = emptyList()
            )

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.delete("/v1/admin/users") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
                contentType(ContentType.Application.Json)
                setBody("""{"userIds":[10,20]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("deleted"))
        }
    }

    @Test
    fun `admin delete users returns 400 on exception`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAdminService.deleteUsers(any()) } throws
            IllegalArgumentException("Cannot delete admin")

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.delete("/v1/admin/users") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
                contentType(ContentType.Application.Json)
                setBody("""{"userIds":[1]}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  AdminRoutes — Billing tiers
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `admin billing tiers returns current plans`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockPricingTierService.getCurrentPlans() } returns emptyList()

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/billing/tiers") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `admin billing tiers with tier param returns versions`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockPricingTierService.getTierVersions("PRO") } returns emptyList()

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/billing/tiers?tier=pro") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `admin create tier version returns 400 for missing tier name`() {
        val adminId = seedUser("admin@test.com", admin = true)

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.post("/v1/admin/billing/tiers/ /versions") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
                contentType(ContentType.Application.Json)
                setBody("""{}""")
            }
            // Blank tier name leads to 400
            assertTrue(response.status == HttpStatusCode.BadRequest || response.status == HttpStatusCode.NotFound)
        }
    }

    @Test
    fun `admin billing subscriptions returns list`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockPricingTierService.listAdminSubscriptions(500) } returns emptyList()

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/billing/subscriptions") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `admin billing subscriptions accepts limit param`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockPricingTierService.listAdminSubscriptions(10) } returns emptyList()

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/billing/subscriptions?limit=10") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  AdminRoutes — Promotional credits
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `admin get promotional credits for org`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAdminBillingService.getPromotionalCreditHistory(42) } returns emptyList()

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/billing/organizations/42/promotional-credits") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `admin get promotional credits returns 400 for invalid org id`() {
        val adminId = seedUser("admin@test.com", admin = true)

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/billing/organizations/abc/promotional-credits") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `admin get all promotional credit grants`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAdminBillingService.getAllPromotionalCreditGrants(100) } returns emptyList()

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/billing/promotional-credits") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `admin delete promotional credits returns success`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAdminBillingService.resetPromotionalCredits(42, adminId) } returns true

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.delete("/v1/admin/billing/organizations/42/promotional-credits") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `admin delete promotional credits returns 404 for nonexistent org`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAdminBillingService.resetPromotionalCredits(999, adminId) } returns false

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.delete("/v1/admin/billing/organizations/999/promotional-credits") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `admin delete promotional credits returns 400 for invalid org id`() {
        val adminId = seedUser("admin@test.com", admin = true)

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.delete("/v1/admin/billing/organizations/abc/promotional-credits") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  AdminRoutes — GET /v1/admin/attribution
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `admin attribution returns metrics`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAttributionService.getAttributionMetrics("campaign") } returns
            AttributionAnalyticsResponse(
                metrics = emptyList(),
                summary = AttributionSummary(
                    totalSignups = 0, totalPaidOrganizations = 0,
                    overallConversionRate = 0.0, totalMrr = "$0"
                )
            )

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/attribution") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `admin attribution accepts groupBy param`() {
        val adminId = seedUser("admin@test.com", admin = true)
        every { mockAttributionService.getAttributionMetrics("source") } returns
            AttributionAnalyticsResponse(
                metrics = emptyList(),
                summary = AttributionSummary(
                    totalSignups = 0, totalPaidOrganizations = 0,
                    overallConversionRate = 0.0, totalMrr = "$0"
                )
            )

        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response = client.get("/v1/admin/attribution?groupBy=source") {
                header(HttpHeaders.Authorization, "Bearer ${token(adminId, 1)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  IntegrationRoutes — GET /integrations (list integrations)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `list integrations returns integrations for org`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("user@test.com")
        seedMembership(orgId, userId, "owner")
        seedIntegration(orgId, "slack")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.get("/integrations") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("slack"))
        }
    }

    @Test
    fun `list integrations returns 404 when user has no org`() {
        val userId = seedUser("orphan@test.com")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.get("/integrations") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, 1)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  IntegrationRoutes — Slack channels
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `get slack channels returns channel list`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("user@test.com")
        seedMembership(orgId, userId, "owner")
        seedIntegration(orgId, "slack", accessToken = "xoxb-token")

        coEvery { mockSlackService.listChannels("xoxb-token") } returns listOf(
            SlackService.SlackChannel(id = "C123", name = "general")
        )

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.get("/integrations/slack/channels") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("general"))
        }
    }

    @Test
    fun `get slack channels returns 404 when no org found`() {
        val userId = seedUser("orphan@test.com")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.get("/integrations/slack/channels") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, 1)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `get slack channels returns 404 when no slack integration`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("user@test.com")
        seedMembership(orgId, userId, "owner")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.get("/integrations/slack/channels") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  IntegrationRoutes — PUT /integrations/slack/channel
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `update slack channel returns success`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("user@test.com")
        seedMembership(orgId, userId, "owner")
        seedIntegration(orgId, "slack")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.put("/integrations/slack/channel") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"channelId":"C456","channelName":"alerts"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Channel updated"))
        }
    }

    @Test
    fun `update slack channel returns 404 when no org`() {
        val userId = seedUser("orphan@test.com")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.put("/integrations/slack/channel") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, 1)}")
                contentType(ContentType.Application.Json)
                setBody("""{"channelId":"C456","channelName":"alerts"}""")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  IntegrationRoutes — Slack usergroups
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `get slack usergroups returns list`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("user@test.com")
        seedMembership(orgId, userId, "owner")
        seedIntegration(orgId, "slack", accessToken = "xoxb-token")

        coEvery { mockSlackService.listUsergroups("xoxb-token") } returns listOf(
            SlackService.SlackUsergroup(id = "S1", handle = "oncall", name = "On-Call Team")
        )

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.get("/integrations/slack/usergroups") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("On-Call Team"))
        }
    }

    @Test
    fun `get slack usergroups returns 404 when no org`() {
        val userId = seedUser("orphan@test.com")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.get("/integrations/slack/usergroups") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, 1)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `get slack usergroups returns 404 when no slack integration`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("user@test.com")
        seedMembership(orgId, userId, "owner")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.get("/integrations/slack/usergroups") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  IntegrationRoutes — PUT /integrations/slack/toggle
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `toggle slack integration returns success`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("user@test.com")
        seedMembership(orgId, userId, "owner")
        seedIntegration(orgId, "slack")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.put("/integrations/slack/toggle") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("disabled"))
        }
    }

    @Test
    fun `toggle slack integration returns 404 when no org`() {
        val userId = seedUser("orphan@test.com")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.put("/integrations/slack/toggle") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, 1)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  IntegrationRoutes — DELETE /integrations/slack
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `delete slack integration returns success`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("user@test.com")
        seedMembership(orgId, userId, "owner")
        seedIntegration(orgId, "slack")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.delete("/integrations/slack") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("deleted"))
        }
    }

    @Test
    fun `delete slack integration returns 404 when no integration`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("user@test.com")
        seedMembership(orgId, userId, "owner")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.delete("/integrations/slack") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `delete slack integration returns 404 when no org`() {
        val userId = seedUser("orphan@test.com")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.delete("/integrations/slack") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, 1)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  IntegrationRoutes — POST /integrations/slack/test
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `test slack integration returns success`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("user@test.com")
        seedMembership(orgId, userId, "owner")

        coEvery { mockSlackService.testConnection(orgId) } returns Pair(true, "OK")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.post("/integrations/slack/test") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("true"))
        }
    }

    @Test
    fun `test slack integration returns 400 on failure`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("user@test.com")
        seedMembership(orgId, userId, "owner")

        coEvery { mockSlackService.testConnection(orgId) } returns Pair(false, "Not configured")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.post("/integrations/slack/test") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `test slack integration returns 404 when no org`() {
        val userId = seedUser("orphan@test.com")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.post("/integrations/slack/test") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, 1)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  IntegrationRoutes — Discord channels
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `get discord channels returns channel list`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("user@test.com")
        seedMembership(orgId, userId, "owner")
        seedIntegration(orgId, "discord", teamId = "G123")

        coEvery { mockDiscordService.listChannels("G123") } returns listOf(
            DiscordService.DiscordChannel(id = "C1", name = "general", type = 0)
        )

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.get("/integrations/discord/channels") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("general"))
        }
    }

    @Test
    fun `get discord channels returns 404 when no org`() {
        val userId = seedUser("orphan@test.com")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.get("/integrations/discord/channels") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, 1)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `get discord channels returns 404 when no discord integration`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("user@test.com")
        seedMembership(orgId, userId, "owner")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.get("/integrations/discord/channels") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  IntegrationRoutes — PUT /integrations/discord/channel
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `update discord channel returns success`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("user@test.com")
        seedMembership(orgId, userId, "owner")
        seedIntegration(orgId, "discord")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.put("/integrations/discord/channel") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"channelId":"D456","channelName":"alerts"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Channel updated"))
        }
    }

    @Test
    fun `update discord channel returns 404 when no org`() {
        val userId = seedUser("orphan@test.com")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.put("/integrations/discord/channel") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, 1)}")
                contentType(ContentType.Application.Json)
                setBody("""{"channelId":"D456","channelName":"alerts"}""")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `update discord channel returns 404 when no integration`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("user@test.com")
        seedMembership(orgId, userId, "owner")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.put("/integrations/discord/channel") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"channelId":"D456","channelName":"alerts"}""")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  IntegrationRoutes — PUT /integrations/discord/toggle
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `toggle discord integration returns success`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("user@test.com")
        seedMembership(orgId, userId, "owner")
        seedIntegration(orgId, "discord")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.put("/integrations/discord/toggle") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("toggled"))
        }
    }

    @Test
    fun `toggle discord integration returns 404 when no org`() {
        val userId = seedUser("orphan@test.com")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.put("/integrations/discord/toggle") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, 1)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `toggle discord integration returns 404 when no integration`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("user@test.com")
        seedMembership(orgId, userId, "owner")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.put("/integrations/discord/toggle") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  IntegrationRoutes — DELETE /integrations/discord
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `delete discord integration returns success`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("user@test.com")
        seedMembership(orgId, userId, "owner")
        seedIntegration(orgId, "discord")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.delete("/integrations/discord") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("deleted"))
        }
    }

    @Test
    fun `delete discord integration returns 404 when no integration`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("user@test.com")
        seedMembership(orgId, userId, "owner")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.delete("/integrations/discord") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `delete discord integration returns 404 when no org`() {
        val userId = seedUser("orphan@test.com")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.delete("/integrations/discord") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, 1)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  IntegrationRoutes — POST /integrations/discord/test
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `test discord integration returns success`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("user@test.com")
        seedMembership(orgId, userId, "owner")

        coEvery {
            mockDiscordService.testConnection(orgId, any())
        } returns Pair(true, "Connected")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.post("/integrations/discord/test") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("true"))
        }
    }

    @Test
    fun `test discord integration returns 400 on failure`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("user@test.com")
        seedMembership(orgId, userId, "owner")

        coEvery {
            mockDiscordService.testConnection(orgId, any())
        } returns Pair(false, "Not configured")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.post("/integrations/discord/test") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `test discord integration returns 404 when no org`() {
        val userId = seedUser("orphan@test.com")

        testApplication {
            application {
                installTestApp()
                routing {
                    authenticate("auth-jwt") {
                        integrationRoutes()
                    }
                }
            }
            val response = client.post("/integrations/discord/test") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, 1)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  IntegrationCallbackRoutes — Slack OAuth callback
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `slack oauth callback returns 400 without code`() {
        testApplication {
            application {
                installTestApp()
                routing { integrationCallbackRoutes() }
            }
            val response = client.get("/integrations/slack/oauth/callback?state=abc")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Missing code"))
        }
    }

    @Test
    fun `slack oauth callback returns 400 without state`() {
        testApplication {
            application {
                installTestApp()
                routing { integrationCallbackRoutes() }
            }
            val response = client.get("/integrations/slack/oauth/callback?code=abc")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Missing state"))
        }
    }

    @Test
    fun `slack oauth callback returns 400 for invalid state`() {
        testApplication {
            application {
                installTestApp()
                routing { integrationCallbackRoutes() }
            }
            val response = client.get("/integrations/slack/oauth/callback?code=abc&state=invalid")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid or expired state"))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  IntegrationCallbackRoutes — Discord OAuth callback
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `discord oauth callback returns 400 without code`() {
        testApplication {
            application {
                installTestApp()
                routing { integrationCallbackRoutes() }
            }
            val response = client.get("/integrations/discord/oauth/callback?state=abc")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Missing code"))
        }
    }

    @Test
    fun `discord oauth callback returns 400 without state`() {
        testApplication {
            application {
                installTestApp()
                routing { integrationCallbackRoutes() }
            }
            val response = client.get("/integrations/discord/oauth/callback?code=abc")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Missing state"))
        }
    }

    @Test
    fun `discord oauth callback returns 400 for invalid state`() {
        testApplication {
            application {
                installTestApp()
                routing { integrationCallbackRoutes() }
            }
            val response = client.get("/integrations/discord/oauth/callback?code=abc&state=invalid")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid or expired state"))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  IntegrationRoutes — Slack link user
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `slack link user creates new mapping`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("user@test.com")
        seedMembership(orgId, userId, "owner")

        testApplication {
            application {
                installTestApp()
                routing { integrationCallbackRoutes() }
            }
            val response = client.post("/integrations/slack/link-user") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"slackUserId":"U123","slackTeamId":"T456"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("linked"))
        }
    }

    @Test
    fun `slack link user updates existing mapping`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser("user@test.com")
        seedMembership(orgId, userId, "owner")

        // Seed existing mapping
        transaction {
            SlackUserMappings.insert {
                it[SlackUserMappings.userId] = userId
                it[SlackUserMappings.slackUserId] = "U-OLD"
                it[SlackUserMappings.slackTeamId] = "T-OLD"
                it[SlackUserMappings.createdAt] = kotlin.time.Clock.System.now()
                it[SlackUserMappings.updatedAt] = kotlin.time.Clock.System.now()
            }
        }

        testApplication {
            application {
                installTestApp()
                routing { integrationCallbackRoutes() }
            }
            val response = client.post("/integrations/slack/link-user") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"slackUserId":"U-NEW","slackTeamId":"T-NEW"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("linked"))
        }
    }

    @Test
    fun `slack link user returns 401 without auth`() {
        testApplication {
            application {
                installTestApp()
                routing { integrationCallbackRoutes() }
            }
            val response = client.post("/integrations/slack/link-user") {
                contentType(ContentType.Application.Json)
                setBody("""{"slackUserId":"U123","slackTeamId":"T456"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }
}
