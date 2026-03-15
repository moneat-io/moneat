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
import com.moneat.org.services.AdminEmailStats
import com.moneat.org.services.AdminInfrastructureHealth
import com.moneat.org.services.AdminOrgDetail
import com.moneat.org.services.AdminOverviewStats
import com.moneat.org.services.AdminRevenueMetrics
import com.moneat.org.services.AdminService
import com.moneat.org.services.AdminUsageBreakdown
import com.moneat.org.services.DeleteUsersResponse
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrganizationIntegrations
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.SlackUserMappings
import com.moneat.shared.models.Users
import com.moneat.shared.services.AttributionAnalyticsResponse
import com.moneat.shared.services.AttributionAnalyticsService
import com.moneat.shared.services.AttributionSummary
import com.moneat.shared.services.OrgUsageSummary
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
import kotlinx.datetime.LocalDate
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
import kotlin.time.Clock

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
    fun teardown() { stopTestKoin() }

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
        .withIssuer("moneat").withAudience("moneat-users")
        .withClaim("userId", userId).withClaim("orgId", orgId)
        .withClaim("email", "user$userId@test.com")
        .sign(Algorithm.HMAC256(JWT_SECRET))

    private fun seedOrg(name: String): Int = transaction {
        Organizations.insert {
            it[Organizations.name] = name
            it[Organizations.slug] = name.lowercase()
        } get Organizations.id
    }

    private fun seedUser(email: String, admin: Boolean = false): Int = transaction {
        Users.insert {
            it[Users.email] = email
            it[Users.password_hash] = "h"
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

    private fun seedIntegration(orgId: Int, type: String, tok: String? = "tok") = transaction {
        OrganizationIntegrations.insert {
            it[organization_id] = orgId
            it[integration_type] = type
            it[access_token] = tok
            it[team_id] = "T1"
            it[team_name] = "Team"
            it[enabled] = true
            it[created_at] = Clock.System.now()
            it[updated_at] = Clock.System.now()
        } get OrganizationIntegrations.id
    }

    // ── Admin: overview ────────────────────────────────────────

    @Test fun `admin overview returns stats`() {
        val id = seedUser("a@t.com", admin = true)
        coEvery { mockAdminService.getOverviewStats() } returns AdminOverviewStats(
            totalOrganizations = 5, totalUsers = 20, totalEventsAllTime = 1000,
            totalEventsLast30Days = 500, mrr = 100.0,
            subscriptionsByPlan = emptyMap(), eventsLast30Days = emptyList()
        )
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/v1/admin/overview") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    @Test fun `admin overview 403 for non-admin`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "member")
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/v1/admin/overview") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                }.status
            )
        }
    }

    @Test fun `admin overview 401 no auth`() {
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/overview").status)
        }
    }

    // ── Admin: organizations ───────────────────────────────────

    @Test fun `admin orgs list`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminService.getAllOrganizations(1, 25) } returns emptyList()
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/v1/admin/organizations") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    @Test fun `admin orgs pagination`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminService.getAllOrganizations(2, 10) } returns emptyList()
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/v1/admin/organizations?page=2&limit=10") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    // ── Admin: org detail ──────────────────────────────────────

    @Test fun `admin org detail`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminService.getOrgDetail(42) } returns AdminOrgDetail(
            id = 42, name = "Acme", slug = "acme", companySize = null, plan = "free",
            subscriptionStatus = null, memberCount = 1, projectCount = 0,
            eventCountThisMonth = 100, bytesIngestedThisMonth = 5000,
            quotaUsedPercent = null, members = emptyList(), projects = emptyList()
        )
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val r = client.get(
                "/v1/admin/organizations/42"
            ) { header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}") }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("Acme"))
        }
    }

    @Test fun `admin org detail 404`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminService.getOrgDetail(999) } returns null
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/v1/admin/organizations/999") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    @Test fun `admin org detail bad id`() {
        val id = seedUser("a@t.com", admin = true)
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("/v1/admin/organizations/abc") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    // ── Admin: org usage ───────────────────────────────────────

    @Test fun `admin org usage`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminService.getOrgUsage(42, "7d") } returns listOf(
            OrgUsageSummary(LocalDate(2026, 1, 1), "error", 100, 5000)
        )
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/v1/admin/organizations/42/usage") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    @Test fun `admin org usage bad id`() {
        val id = seedUser("a@t.com", admin = true)
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("/v1/admin/organizations/xyz/usage") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    @Test fun `admin org usage period param`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminService.getOrgUsage(42, "30d") } returns emptyList()
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/v1/admin/organizations/42/usage?period=30d") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    // ── Admin: usage, revenue, infra, top-consumers, emails ───

    @Test fun `admin usage breakdown`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminService.getUsageBreakdown("7d") } returns
            AdminUsageBreakdown(daily = emptyList(), totalBytes = 50000)
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/v1/admin/usage") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    @Test fun `admin revenue`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminService.getRevenueMetrics() } returns AdminRevenueMetrics(
            mrr = 100.0, subscriptionsByPlan = emptyMap(), estimatedCostPerOrg = emptyMap(), churnLast30Days = 0
        )
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/v1/admin/revenue") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    @Test fun `admin infrastructure`() {
        val id = seedUser("a@t.com", admin = true)
        coEvery { mockAdminService.getInfrastructureHealth() } returns AdminInfrastructureHealth(
            clickhouseTables = emptyList(), totalDiskBytes = 0, totalRows = 0,
            storageUsedPercent = 0.0, scalingTriggerAlerts = emptyList()
        )
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/v1/admin/infrastructure") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    @Test fun `admin top consumers`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminService.getTopConsumers(10) } returns emptyList()
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/v1/admin/top-consumers") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    @Test fun `admin top consumers limit`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminService.getTopConsumers(5) } returns emptyList()
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/v1/admin/top-consumers?limit=5") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    @Test fun `admin emails`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminService.getEmailStats("30d") } returns AdminEmailStats(
            totalSent = 100, byType = emptyMap(), last7Days = emptyList(), last30Days = emptyList(), estimatedCost = 0.0
        )
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/v1/admin/emails") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    @Test fun `admin emails period`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminService.getEmailStats("7d") } returns AdminEmailStats(
            totalSent = 50, byType = emptyMap(), last7Days = emptyList(), last30Days = emptyList(), estimatedCost = 0.0
        )
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/v1/admin/emails?period=7d") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    // ── Admin: impersonate ─────────────────────────────────────

    @Test fun `admin impersonate`() {
        val id = seedUser("a@t.com", admin = true)
        val tgt = seedUser("tgt@t.com")
        every { mockAuthService.generateImpersonationToken(tgt, "tgt@t.com") } returns "imp"
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val r = client.post(
                "/v1/admin/impersonate/$tgt"
            ) { header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}") }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("imp"))
        }
    }

    @Test fun `admin impersonate 404`() {
        val id = seedUser("a@t.com", admin = true)
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.post("/v1/admin/impersonate/99999") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    @Test fun `admin impersonate bad id`() {
        val id = seedUser("a@t.com", admin = true)
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.BadRequest,
                client.post("/v1/admin/impersonate/abc") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    // ── Admin: users ───────────────────────────────────────────

    @Test fun `admin users`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminService.getAllUsers(1, 25, null) } returns emptyList()
        every { mockAdminService.getTotalUserCount(null) } returns 0
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val r = client.get("/v1/admin/users") { header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}") }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("\"users\""))
        }
    }

    @Test fun `admin users search`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminService.getAllUsers(2, 10, "test") } returns emptyList()
        every { mockAdminService.getTotalUserCount("test") } returns 0
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/v1/admin/users?page=2&limit=10&search=test") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    // ── Admin: update user ─────────────────────────────────────

    @Test fun `admin update user ok`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminService.updateUser(any(), any()) } returns true
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.patch("/v1/admin/users/42") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"isAdmin":true}""")
                }.status
            )
        }
    }

    @Test fun `admin update user 404`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminService.updateUser(any(), any()) } returns false
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.patch("/v1/admin/users/999") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"isAdmin":false}""")
                }.status
            )
        }
    }

    @Test fun `admin update user bad id`() {
        val id = seedUser("a@t.com", admin = true)
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.BadRequest,
                client.patch("/v1/admin/users/abc") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"isAdmin":true}""")
                }.status
            )
        }
    }

    @Test fun `admin update user exception`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminService.updateUser(any(), any()) } throws IllegalArgumentException("bad")
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.BadRequest,
                client.patch("/v1/admin/users/42") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"isAdmin":true}""")
                }.status
            )
        }
    }

    // ── Admin: delete users ────────────────────────────────────

    @Test fun `admin delete users`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminService.deleteUsers(listOf(10, 20)) } returns
            DeleteUsersResponse(success = true, deletedCount = 2)
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.delete("/v1/admin/users") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"userIds":[10,20]}""")
                }.status
            )
        }
    }

    @Test fun `admin delete users exception`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminService.deleteUsers(any()) } throws IllegalArgumentException("no")
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.BadRequest,
                client.delete("/v1/admin/users") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"userIds":[1]}""")
                }.status
            )
        }
    }

    // ── Admin: billing ─────────────────────────────────────────

    @Test fun `admin billing tiers`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockPricingTierService.getCurrentPlans() } returns emptyList()
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/v1/admin/billing/tiers") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    @Test fun `admin billing tiers by name`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockPricingTierService.getTierVersions("PRO") } returns emptyList()
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/v1/admin/billing/tiers?tier=pro") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    @Test fun `admin billing subscriptions`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockPricingTierService.listAdminSubscriptions(500) } returns emptyList()
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/v1/admin/billing/subscriptions") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    // ── Admin: promo credits ───────────────────────────────────

    @Test fun `admin promo credits for org`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminBillingService.getPromotionalCreditHistory(42) } returns emptyList()
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/v1/admin/billing/organizations/42/promotional-credits") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    @Test fun `admin promo credits bad org id`() {
        val id = seedUser("a@t.com", admin = true)
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("/v1/admin/billing/organizations/abc/promotional-credits") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    @Test fun `admin all promo grants`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminBillingService.getAllPromotionalCreditGrants(100) } returns emptyList()
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/v1/admin/billing/promotional-credits") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    @Test fun `admin delete promo credits ok`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminBillingService.resetPromotionalCredits(42, id) } returns true
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.delete("/v1/admin/billing/organizations/42/promotional-credits") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    @Test fun `admin delete promo credits 404`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminBillingService.resetPromotionalCredits(999, id) } returns false
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.delete("/v1/admin/billing/organizations/999/promotional-credits") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    @Test fun `admin delete promo credits bad id`() {
        val id = seedUser("a@t.com", admin = true)
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.BadRequest,
                client.delete("/v1/admin/billing/organizations/abc/promotional-credits") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    // ── Admin: attribution ─────────────────────────────────────

    @Test fun `admin attribution`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAttributionService.getAttributionMetrics("campaign") } returns
            AttributionAnalyticsResponse(emptyList(), AttributionSummary(0, 0, 0.0, "$0"))
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/v1/admin/attribution") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    @Test fun `admin attribution groupBy`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAttributionService.getAttributionMetrics("source") } returns
            AttributionAnalyticsResponse(emptyList(), AttributionSummary(0, 0, 0.0, "$0"))
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/v1/admin/attribution?groupBy=source") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    // ── Integrations: list ─────────────────────────────────────

    @Test fun `list integrations`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        seedIntegration(o, "slack")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            val r = client.get("/integrations") { header(HttpHeaders.Authorization, "Bearer ${token(u, o)}") }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("slack"))
        }
    }

    @Test fun `list integrations 404`() {
        val u = seedUser("u@t.com")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/integrations") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, 1)}")
                }.status
            )
        }
    }

    // ── Slack: channels, channel, usergroups, toggle, delete, test ──

    @Test fun `slack channels`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        seedIntegration(o, "slack", "xoxb")
        coEvery { mockSlackService.listChannels("xoxb") } returns listOf(SlackService.SlackChannel("C1", "general"))
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/integrations/slack/channels") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                }.status
            )
        }
    }

    @Test fun `slack channels 404 no org`() {
        val u = seedUser("u@t.com")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/integrations/slack/channels") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, 1)}")
                }.status
            )
        }
    }

    @Test fun `slack channels 404 no integ`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/integrations/slack/channels") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                }.status
            )
        }
    }

    @Test fun `update slack channel`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        seedIntegration(o, "slack")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.put("/integrations/slack/channel") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"channelId":"C2","channelName":"a"}""")
                }.status
            )
        }
    }

    @Test fun `update slack channel 404`() {
        val u = seedUser("u@t.com")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.put("/integrations/slack/channel") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, 1)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"channelId":"C2","channelName":"a"}""")
                }.status
            )
        }
    }

    @Test fun `slack usergroups`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        seedIntegration(o, "slack", "xoxb")
        coEvery { mockSlackService.listUsergroups("xoxb") } returns listOf(SlackService.SlackUsergroup("S1", "h", "G"))
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/integrations/slack/usergroups") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                }.status
            )
        }
    }

    @Test fun `slack usergroups 404 no org`() {
        val u = seedUser("u@t.com")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/integrations/slack/usergroups") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, 1)}")
                }.status
            )
        }
    }

    @Test fun `slack usergroups 404 no integ`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/integrations/slack/usergroups") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                }.status
            )
        }
    }

    @Test fun `toggle slack`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        seedIntegration(o, "slack")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.put("/integrations/slack/toggle") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                }.status
            )
        }
    }

    @Test fun `toggle slack 404`() {
        val u = seedUser("u@t.com")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.put("/integrations/slack/toggle") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, 1)}")
                }.status
            )
        }
    }

    @Test fun `delete slack`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        seedIntegration(o, "slack")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.delete("/integrations/slack") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                }.status
            )
        }
    }

    @Test fun `delete slack 404 no integ`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.delete("/integrations/slack") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                }.status
            )
        }
    }

    @Test fun `delete slack 404 no org`() {
        val u = seedUser("u@t.com")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.delete("/integrations/slack") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, 1)}")
                }.status
            )
        }
    }

    @Test fun `test slack ok`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        coEvery { mockSlackService.testConnection(o) } returns Pair(true, "OK")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.post("/integrations/slack/test") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                }.status
            )
        }
    }

    @Test fun `test slack fail`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        coEvery { mockSlackService.testConnection(o) } returns Pair(false, "No")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.BadRequest,
                client.post("/integrations/slack/test") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                }.status
            )
        }
    }

    @Test fun `test slack 404`() {
        val u = seedUser("u@t.com")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.post("/integrations/slack/test") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, 1)}")
                }.status
            )
        }
    }

    // ── Discord: channels, channel, toggle, delete, test ───────

    @Test fun `discord channels`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        seedIntegration(o, "discord")
        coEvery { mockDiscordService.listChannels("T1") } returns listOf(DiscordService.DiscordChannel("D1", "gen", 0))
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/integrations/discord/channels") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                }.status
            )
        }
    }

    @Test fun `discord channels 404 no org`() {
        val u = seedUser("u@t.com")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/integrations/discord/channels") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, 1)}")
                }.status
            )
        }
    }

    @Test fun `discord channels 404 no integ`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/integrations/discord/channels") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                }.status
            )
        }
    }

    @Test fun `update discord channel`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        seedIntegration(o, "discord")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.put("/integrations/discord/channel") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"channelId":"D2","channelName":"a"}""")
                }.status
            )
        }
    }

    @Test fun `update discord channel 404 no org`() {
        val u = seedUser("u@t.com")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.put("/integrations/discord/channel") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, 1)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"channelId":"D2","channelName":"a"}""")
                }.status
            )
        }
    }

    @Test fun `update discord channel 404 no integ`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.put("/integrations/discord/channel") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"channelId":"D2","channelName":"a"}""")
                }.status
            )
        }
    }

    @Test fun `toggle discord`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        seedIntegration(o, "discord")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.put("/integrations/discord/toggle") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                }.status
            )
        }
    }

    @Test fun `toggle discord 404 no org`() {
        val u = seedUser("u@t.com")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.put("/integrations/discord/toggle") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, 1)}")
                }.status
            )
        }
    }

    @Test fun `toggle discord 404 no integ`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.put("/integrations/discord/toggle") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                }.status
            )
        }
    }

    @Test fun `delete discord`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        seedIntegration(o, "discord")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.delete("/integrations/discord") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                }.status
            )
        }
    }

    @Test fun `delete discord 404 no integ`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.delete("/integrations/discord") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                }.status
            )
        }
    }

    @Test fun `delete discord 404 no org`() {
        val u = seedUser("u@t.com")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.delete("/integrations/discord") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, 1)}")
                }.status
            )
        }
    }

    @Test fun `test discord ok`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        coEvery { mockDiscordService.testConnection(o, any()) } returns Pair(true, "OK")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.post("/integrations/discord/test") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                }.status
            )
        }
    }

    @Test fun `test discord fail`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        coEvery { mockDiscordService.testConnection(o, any()) } returns Pair(false, "No")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.BadRequest,
                client.post("/integrations/discord/test") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                }.status
            )
        }
    }

    @Test fun `test discord 404`() {
        val u = seedUser("u@t.com")
        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.post("/integrations/discord/test") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, 1)}")
                }.status
            )
        }
    }

    // ── Callbacks: OAuth ───────────────────────────────────────

    @Test fun `slack oauth 400 no code`() {
        testApplication {
            application {
                installTestApp()
                routing { integrationCallbackRoutes() }
            }
            val r = client.get("/integrations/slack/oauth/callback?state=abc")
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertTrue(r.bodyAsText().contains("Missing code"))
        }
    }

    @Test fun `slack oauth 400 no state`() {
        testApplication {
            application {
                installTestApp()
                routing { integrationCallbackRoutes() }
            }
            val r = client.get("/integrations/slack/oauth/callback?code=abc")
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertTrue(r.bodyAsText().contains("Missing state"))
        }
    }

    @Test fun `slack oauth 400 invalid state`() {
        testApplication {
            application {
                installTestApp()
                routing { integrationCallbackRoutes() }
            }
            val r = client.get("/integrations/slack/oauth/callback?code=abc&state=invalid")
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertTrue(r.bodyAsText().contains("Invalid or expired state"))
        }
    }

    @Test fun `discord oauth 400 no code`() {
        testApplication {
            application {
                installTestApp()
                routing { integrationCallbackRoutes() }
            }
            val r = client.get("/integrations/discord/oauth/callback?state=abc")
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertTrue(r.bodyAsText().contains("Missing code"))
        }
    }

    @Test fun `discord oauth 400 no state`() {
        testApplication {
            application {
                installTestApp()
                routing { integrationCallbackRoutes() }
            }
            val r = client.get("/integrations/discord/oauth/callback?code=abc")
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertTrue(r.bodyAsText().contains("Missing state"))
        }
    }

    @Test fun `discord oauth 400 invalid state`() {
        testApplication {
            application {
                installTestApp()
                routing { integrationCallbackRoutes() }
            }
            val r = client.get("/integrations/discord/oauth/callback?code=abc&state=invalid")
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertTrue(r.bodyAsText().contains("Invalid or expired state"))
        }
    }

    // ── Callbacks: Slack link user ──────────────────────────────

    @Test fun `slack link user`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        testApplication {
            application {
                installTestApp()
                routing { integrationCallbackRoutes() }
            }
            val r = client.post("/integrations/slack/link-user") {
                header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                contentType(ContentType.Application.Json)
                setBody("""{"slackUserId":"U1","slackTeamId":"T1"}""")
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("linked"))
        }
    }

    @Test fun `slack link user update`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        transaction {
            SlackUserMappings.insert {
                it[userId] = u
                it[slackUserId] = "OLD"
                it[slackTeamId] = "OLD"
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }
        }
        testApplication {
            application {
                installTestApp()
                routing { integrationCallbackRoutes() }
            }
            assertEquals(
                HttpStatusCode.OK,
                client.post("/integrations/slack/link-user") {
                    header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"slackUserId":"NEW","slackTeamId":"NEW"}""")
                }.status
            )
        }
    }

    @Test fun `slack link user 401`() {
        testApplication {
            application {
                installTestApp()
                routing { integrationCallbackRoutes() }
            }
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.post("/integrations/slack/link-user") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"slackUserId":"U1","slackTeamId":"T1"}""")
                }.status
            )
        }
    }
}
