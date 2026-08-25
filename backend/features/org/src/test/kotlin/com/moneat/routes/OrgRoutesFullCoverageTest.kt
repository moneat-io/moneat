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
import com.moneat.billing.models.AdminQuotaUsageResetResponse
import com.moneat.billing.models.BillingUsageResponse
import com.moneat.billing.services.AdminBillingService
import com.moneat.billing.services.BillingQuotaService
import com.moneat.billing.services.PricingTierService
import com.moneat.incident.services.IncidentService
import com.moneat.notifications.services.DiscordService
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.SlackCapabilityDefinition
import com.moneat.notifications.services.SlackDeliveryConfig
import com.moneat.notifications.services.SlackInstallationHealthStatus
import com.moneat.notifications.services.SlackInstallationService
import com.moneat.notifications.services.SlackInstallationSummary
import com.moneat.notifications.services.SlackScopeExplanation
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
import com.moneat.shared.models.SlackInstallations
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
import io.ktor.http.parseQueryString
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
import io.mockk.verify
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import java.net.URI
import kotlin.uuid.Uuid
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
    private val mockSlackInstallationService = mockk<SlackInstallationService>(relaxed = true)
    private val mockSlackService = mockk<SlackService>(relaxed = true)
    private val mockDiscordService = mockk<DiscordService>(relaxed = true)
    private val mockIncidentService = mockk<IncidentService>(relaxed = true)
    private val mockAdminBillingService = mockk<AdminBillingService>(relaxed = true)
    private val mockQuotaService = mockk<BillingQuotaService>(relaxed = true)

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
                single<SlackInstallationService> { mockSlackInstallationService }
                single<SlackService> { mockSlackService }
                single<DiscordService> { mockDiscordService }
                single<IncidentService> { mockIncidentService }
                single<AdminBillingService> { mockAdminBillingService }
                single<BillingQuotaService> { mockQuotaService }
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
            SlackInstallations,
            SlackUserMappings
        )
        seedFixedOrg(42)
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

    private fun token(userId: Int, orgId: Int?): String = JWT.create()
        .withIssuer("moneat").withAudience("moneat-users")
        .withClaim("userId", userId)
        .apply { if (orgId != null) withClaim("orgId", orgId) }
        .withClaim("email", "user$userId@test.com")
        .sign(Algorithm.HMAC256(JWT_SECRET))

    private fun resourceId(id: Int): String =
        "00000000-0000-0000-0000-${id.toString().padStart(12, '0')}"

    private fun seedFixedOrg(id: Int) {
        transaction {
            Organizations.insert {
                it[Organizations.id] = id
                it[Organizations.resource_id] = Uuid.parse(resourceId(id))
                it[Organizations.name] = "Admin Org $id"
                it[Organizations.slug] = "admin-org-$id"
            }
        }
    }

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

    private fun userResourceId(userId: Int): String =
        transaction {
            Users
                .selectAll()
                .where { Users.id eq userId }
                .single()[Users.resource_id]
                .toString()
        }

    private fun seedMembership(orgId: Int, userId: Int, role: String) = transaction {
        Memberships.insert {
            it[Memberships.organization_id] = orgId
            it[Memberships.user_id] = userId
            it[Memberships.role] = role
        }
    }

    private fun adminOrgDetail(id: Int = 42): AdminOrgDetail =
        AdminOrgDetail(
            id = resourceId(id),
            name = "Acme",
            slug = "acme",
            companySize = null,
            plan = "pro",
            subscriptionStatus = "active",
            memberCount = 1,
            projectCount = 1,
            eventCountThisMonth = 100,
            bytesIngestedThisMonth = 5_000,
            quotaUsedPercent = 25.0,
            members = emptyList(),
            projects = emptyList()
        )

    private fun billingUsageResponse(organizationId: Int = 42): BillingUsageResponse =
        BillingUsageResponse(
            organizationId = resourceId(organizationId),
            periodStart = "2026-01-01",
            periodEnd = "2026-01-31",
            retentionDays = 30,
            apmTraceRetentionDays = 30,
            usedUnits = 100,
            usedErrors = 10,
            errorLimit = 1_000,
            usedTransactions = 20,
            transactionLimit = 2_000,
            usedReplays = 30,
            replayLimit = 3_000,
            usedFeedback = 40,
            feedbackLimit = 4_000,
            usedBytes = 1_024,
            bytesLimit = 10_240,
            baseLimitUnits = 1_000,
            paygLimitUnits = 0,
            totalLimitUnits = 1_000,
            paygBudgetCents = 0,
            paygUsedUnits = 0,
            paygUsedCentsEstimate = 0,
            usedApmSpans = 800,
            apmSpanLimit = 1_000,
            plan = "pro",
            status = "active",
            withinQuota = true
        )

    private fun quotaResetResponse(organizationId: Int = 42): AdminQuotaUsageResetResponse =
        AdminQuotaUsageResetResponse(
            organizationId = resourceId(organizationId),
            quotaType = "apm_spans",
            periodStart = "2026-01-01",
            periodEnd = "2026-01-31",
            previousUsed = 1_000,
            updatedUsed = 800,
            limit = 1_000,
            targetPercent = 80.0,
            usage = billingUsageResponse(organizationId)
        )

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

    private fun seedSlackInstallation(orgId: Int, teamId: String): Int = transaction {
        SlackInstallations.insert {
            it[SlackInstallations.organizationId] = orgId
            it[SlackInstallations.teamId] = teamId
            it[SlackInstallations.teamName] = "Team"
            it[SlackInstallations.isDefault] = true
            it[SlackInstallations.enabled] = true
        } get SlackInstallations.id
    }

    private fun slackInstallationSummary(
        id: String = resourceId(700),
        enabled: Boolean = true,
    ) = SlackInstallationSummary(
        id = id,
        teamId = "T1",
        teamName = "Team",
        enterpriseId = null,
        enterpriseName = null,
        isEnterpriseInstall = false,
        appId = "A1",
        botUserId = "B1",
        grantedScopes = listOf("chat:write"),
        grantedUserScopes = emptyList(),
        enabledCapabilities = listOf("alert_delivery"),
        missingScopes = emptyList(),
        workspaceBindings = emptyList(),
        grants = emptyList(),
        capabilityHealth = emptyList(),
        defaultChannelId = "C1",
        defaultChannelName = "incidents",
        isDefault = true,
        enabled = enabled,
        health = if (enabled) SlackInstallationHealthStatus.HEALTHY else SlackInstallationHealthStatus.DISABLED,
        healthDetail = null,
        lastVerifiedAt = "2026-08-22T00:00:00Z",
        createdAt = "2026-08-22T00:00:00Z",
        updatedAt = "2026-08-22T00:00:00Z",
    )

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
            id = resourceId(42), name = "Acme", slug = "acme", companySize = null, plan = "free",
            subscriptionStatus = null, memberCount = 1, projectCount = 0,
            eventCountThisMonth = 100, bytesIngestedThisMonth = 5000,
            quotaUsedPercent = null, members = emptyList(), projects = emptyList()
        )
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val r = client.get("/v1/admin/organizations/${resourceId(42)}") {
                header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
            }
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
                client.get("/v1/admin/organizations/${resourceId(999)}") {
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
                client.get("/v1/admin/organizations/${resourceId(42)}/usage") {
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
                client.get("/v1/admin/organizations/${resourceId(42)}/usage?period=30d") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    // ──── Admin: org quota usage ────

    @Test fun `admin org quota usage returns usage`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminService.getOrgDetail(42) } returns adminOrgDetail()
        every { mockQuotaService.getUsageForOrganization(42) } returns billingUsageResponse()
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response =
                client.get("/v1/admin/organizations/${resourceId(42)}/quota-usage") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains(resourceId(42)))
        }
    }

    @Test fun `admin org quota usage returns 404 for missing org`() {
        val id = seedUser("a@t.com", admin = true)
        every { mockAdminService.getOrgDetail(404) } returns null
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/v1/admin/organizations/${resourceId(404)}/quota-usage") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
        verify(exactly = 0) { mockQuotaService.getUsageForOrganization(404) }
    }

    @Test fun `admin org quota usage rejects bad id`() {
        val id = seedUser("a@t.com", admin = true)
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("/v1/admin/organizations/not-a-number/quota-usage") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                }.status
            )
        }
    }

    @Test fun `admin org quota reset returns reset response`() {
        val id = seedUser("a@t.com", admin = true)
        every {
            mockQuotaService.resetUsageForQuotaType(42, "apm_spans", 80.0, null, id)
        } returns quotaResetResponse()
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            val response =
                client.post("/v1/admin/organizations/${resourceId(42)}/quota-usage/reset") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"quotaType":"apm_spans","targetPercent":80.0}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"updatedUsed\":800"))
        }
    }

    @Test fun `admin org quota reset returns 400 for invalid target`() {
        val id = seedUser("a@t.com", admin = true)
        every {
            mockQuotaService.resetUsageForQuotaType(42, "errors", null, null, id)
        } throws IllegalArgumentException("targetPercent or targetValue is required")
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.BadRequest,
                client.post("/v1/admin/organizations/${resourceId(42)}/quota-usage/reset") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"quotaType":"errors"}""")
                }.status
            )
        }
    }

    @Test fun `admin org quota reset returns 404 for missing org`() {
        val id = seedUser("a@t.com", admin = true)
        every {
            mockQuotaService.resetUsageForQuotaType(404, "errors", null, 1L, id)
        } throws IllegalStateException("Organization not found")
        testApplication {
            application {
                installTestApp()
                routing { adminRoutes() }
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.post("/v1/admin/organizations/${resourceId(404)}/quota-usage/reset") {
                    header(HttpHeaders.Authorization, "Bearer ${token(id, 1)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"quotaType":"errors","targetValue":1}""")
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
                "/v1/admin/impersonate/${userResourceId(tgt)}"
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
                client.post("/v1/admin/impersonate/${resourceId(99999)}") {
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
                client.patch("/v1/admin/users/${resourceId(42)}") {
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
                client.patch("/v1/admin/users/${resourceId(999)}") {
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
                client.patch("/v1/admin/users/${resourceId(42)}") {
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
        every { mockAdminService.deleteUsers(listOf(resourceId(10), resourceId(20))) } returns
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
                    setBody("""{"userIds":["${resourceId(10)}","${resourceId(20)}"]}""")
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
                client.get("/v1/admin/billing/organizations/${resourceId(42)}/promotional-credits") {
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
                client.delete("/v1/admin/billing/organizations/${resourceId(42)}/promotional-credits") {
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
                client.delete("/v1/admin/billing/organizations/${resourceId(999)}/promotional-credits") {
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
                    header(HttpHeaders.Authorization, "Bearer ${token(u, null)}")
                }.status
            )
        }
    }

    @Test fun `slack installation catalog and list`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        every { mockSlackInstallationService.capabilityCatalog() } returns listOf(
            SlackCapabilityDefinition(
                id = "alert_delivery",
                label = "Alert delivery",
                description = "Send alerts.",
                scopes = listOf("chat:write"),
                botScopes = listOf("chat:write"),
                userScopes = emptyList(),
                optional = false,
            )
        )
        every { mockSlackInstallationService.scopeCatalog() } returns listOf(
            SlackScopeExplanation(
                scope = "chat:write",
                reason = "Send alerts.",
                capabilities = listOf("alert_delivery"),
            )
        )
        every { mockSlackInstallationService.listInstallations(o) } returns listOf(slackInstallationSummary())

        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            val auth = "Bearer ${token(u, o)}"
            val catalog = client.get("/integrations/slack/capabilities") {
                header(HttpHeaders.Authorization, auth)
            }
            val installations = client.get("/integrations/slack/installations") {
                header(HttpHeaders.Authorization, auth)
            }

            assertEquals(HttpStatusCode.OK, catalog.status)
            assertTrue(catalog.bodyAsText().contains("alert_delivery"))
            assertEquals(HttpStatusCode.OK, installations.status)
            assertTrue(installations.bodyAsText().contains(resourceId(700)))
        }
    }

    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    @Test
    fun `slack oauth reauthorization and callback cover success and failure states`() =
        withSystemProperties(
            mapOf(
                "JWT_SECRET" to "slack-oauth-state-secret",
                "SLACK_CLIENT_ID" to "client-id",
                "SLACK_CLIENT_SECRET" to "client-secret",
                "SLACK_REDIRECT_URI" to "https://api.test/integrations/slack/oauth/callback",
                "FRONTEND_URL" to "https://frontend.test",
                "SELF_HOSTED" to "true",
            )
        ) {
            val organizationId = seedOrg("Slack OAuth")
            val userId = seedUser("slack-oauth@test.com")
            val installationId = resourceId(700)
            val installation = slackInstallationSummary().copy(
                enabledCapabilities = listOf("alert_delivery", "privileged_access"),
            )
            seedMembership(organizationId, userId, "owner")
            every { mockSlackInstallationService.listInstallations(organizationId) } returns listOf(installation)
            every {
                mockSlackInstallationService.storeOAuthGrant(
                    organizationId,
                    installationId,
                    installation.enabledCapabilities,
                    any(),
                )
            } returns installation
            coEvery {
                mockSlackService.exchangeOAuthCode(any(), "client-id", "client-secret", any())
            } returns successfulSlackOAuthResponse()

            testApplication {
                application {
                    installTestApp()
                    routing {
                        authenticate("auth-jwt") { integrationRoutes() }
                        integrationCallbackRoutes()
                    }
                }
                val auth = "Bearer ${token(userId, organizationId)}"
                val startResponse = client.get(
                    "/integrations/slack/oauth/start?" +
                        "installationId=$installationId&" +
                        "capabilities=alert_delivery,%20privileged_access,alert_delivery",
                ) {
                    header(HttpHeaders.Authorization, auth)
                }
                assertEquals(HttpStatusCode.OK, startResponse.status)
                assertTrue(slackAuthUrl(startResponse.bodyAsText()).contains("user_scope="))

                val reauthorizeResponse = client.post(
                    "/integrations/slack/installations/$installationId/reauthorize"
                ) {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody("""{"capabilities":[]}""")
                }
                assertEquals(HttpStatusCode.OK, reauthorizeResponse.status)
                val state = slackOAuthState(reauthorizeResponse.bodyAsText())
                val noRedirectClient = createClient { followRedirects = false }

                val connected = noRedirectClient.get(
                    "/integrations/slack/oauth/callback?code=good&state=$state"
                )
                assertEquals(HttpStatusCode.Found, connected.status)
                assertEquals(
                    "https://frontend.test/settings?tab=integrations&slack=connected",
                    connected.headers[HttpHeaders.Location],
                )

                val updated = noRedirectClient.get(
                    "/integrations/slack/oauth/callback?code=good-again&state=$state"
                )
                assertEquals(HttpStatusCode.Found, updated.status)

                every {
                    mockSlackInstallationService.storeOAuthGrant(
                        organizationId,
                        installationId,
                        installation.enabledCapabilities,
                        any(),
                    )
                } throws IllegalStateException("cipher unavailable")
                val storeFailure = noRedirectClient.get(
                    "/integrations/slack/oauth/callback?code=store-failure&state=$state"
                )
                assertEquals(HttpStatusCode.Found, storeFailure.status)
                assertTrue(storeFailure.headers[HttpHeaders.Location].orEmpty().contains("cipher+unavailable"))

                every {
                    mockSlackInstallationService.storeOAuthGrant(
                        organizationId,
                        installationId,
                        installation.enabledCapabilities,
                        any(),
                    )
                } returns installation.copy(
                    isDefault = false,
                    health = SlackInstallationHealthStatus.WORKSPACE_MISMATCH,
                )
                val mismatch = noRedirectClient.get(
                    "/integrations/slack/oauth/callback?code=mismatch&state=$state"
                )
                assertTrue(mismatch.headers[HttpHeaders.Location].orEmpty().endsWith("message=workspace_mismatch"))

                coEvery {
                    mockSlackService.exchangeOAuthCode("denied", any(), any(), any())
                } returns SlackService.SlackOAuthResponse(ok = false, error = "invalid_auth")
                val denied = noRedirectClient.get(
                    "/integrations/slack/oauth/callback?code=denied&state=$state"
                )
                assertTrue(denied.headers[HttpHeaders.Location].orEmpty().endsWith("message=invalid_auth"))

                transaction {
                    Memberships.update({
                        (Memberships.organization_id eq organizationId) and
                            (Memberships.user_id eq userId)
                    }) {
                        it[Memberships.role] = "member"
                    }
                }
                val forbidden = noRedirectClient.get(
                    "/integrations/slack/oauth/callback?code=good&state=$state"
                )
                assertEquals(HttpStatusCode.Forbidden, forbidden.status)
            }

            verify(atLeast = 3) {
                mockSlackInstallationService.storeOAuthGrant(
                    organizationId,
                    installationId,
                    installation.enabledCapabilities,
                    match { grant ->
                        grant.teamId == "T1" &&
                            grant.enterpriseId == "E1" &&
                            grant.grantedScopes.contains("chat:write") &&
                            grant.userGrant?.slackUserId == "U1"
                    },
                )
            }
        }

    private fun slackAuthUrl(responseBody: String): String =
        Json.parseToJsonElement(responseBody).jsonObject.getValue("authUrl").jsonPrimitive.content

    private fun slackOAuthState(responseBody: String): String =
        checkNotNull(parseQueryString(URI(slackAuthUrl(responseBody)).rawQuery)["state"])

    private fun successfulSlackOAuthResponse() = SlackService.SlackOAuthResponse(
        ok = true,
        accessToken = "xoxb-grant",
        tokenType = "bot",
        scope = "chat:write, commands users:read",
        refreshToken = "bot-refresh",
        expiresIn = 3_600,
        botUserId = "B1",
        appId = "A1",
        team = SlackService.SlackTeam("T1", "Team"),
        enterprise = SlackService.SlackTeam("E1", "Enterprise"),
        authedUser = SlackService.SlackAuthedUser(
            id = "U1",
            scope = "admin users:read",
            accessToken = "xoxp-user",
            tokenType = "user",
            refreshToken = "user-refresh",
            expiresIn = 1_800,
        ),
    )

    private inline fun <T> withSystemProperties(
        properties: Map<String, String>,
        block: () -> T,
    ): T {
        val previous = properties.keys.associateWith(System::getProperty)
        properties.forEach(System::setProperty)
        return try {
            block()
        } finally {
            previous.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }

    @Test fun `slack installation scoped channels and usergroups`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        val installationId = resourceId(700)
        seedMembership(o, u, "owner")
        every { mockSlackInstallationService.accessToken(o, installationId) } returns "xoxb-modeled"
        coEvery { mockSlackService.listChannels("xoxb-modeled") } returns
            listOf(SlackService.SlackChannel("C1", "incidents"))
        coEvery { mockSlackService.listUsergroups("xoxb-modeled") } returns
            listOf(SlackService.SlackUsergroup("S1", "on-call", "On-call"))

        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            val auth = "Bearer ${token(u, o)}"
            val channels = client.get("/integrations/slack/installations/$installationId/channels") {
                header(HttpHeaders.Authorization, auth)
            }
            val usergroups = client.get("/integrations/slack/installations/$installationId/usergroups") {
                header(HttpHeaders.Authorization, auth)
            }

            assertEquals(HttpStatusCode.OK, channels.status)
            assertTrue(channels.bodyAsText().contains("incidents"))
            assertEquals(HttpStatusCode.OK, usergroups.status)
            assertTrue(usergroups.bodyAsText().contains("on-call"))
        }
    }

    @Test fun `slack installation settings and deletion`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        val installationId = resourceId(700)
        val summary = slackInstallationSummary()
        seedMembership(o, u, "owner")
        every {
            mockSlackInstallationService.updateChannel(o, installationId, "C2", "alerts")
        } returns summary.copy(defaultChannelId = "C2", defaultChannelName = "alerts")
        every { mockSlackInstallationService.setDefault(o, installationId) } returns summary
        every { mockSlackInstallationService.setEnabled(o, installationId, false) } returns
            slackInstallationSummary(enabled = false)
        every { mockSlackInstallationService.deleteInstallation(o, installationId) } returns true

        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            val auth = "Bearer ${token(u, o)}"
            assertEquals(
                HttpStatusCode.OK,
                client.put("/integrations/slack/installations/$installationId/channel") {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody("""{"channelId":"C2","channelName":"alerts"}""")
                }.status,
            )
            assertEquals(
                HttpStatusCode.OK,
                client.put("/integrations/slack/installations/$installationId/default") {
                    header(HttpHeaders.Authorization, auth)
                }.status,
            )
            assertEquals(
                HttpStatusCode.OK,
                client.put("/integrations/slack/installations/$installationId/enabled") {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody("""{"enabled":false}""")
                }.status,
            )
            assertEquals(
                HttpStatusCode.OK,
                client.delete("/integrations/slack/installations/$installationId") {
                    header(HttpHeaders.Authorization, auth)
                }.status,
            )
        }
    }

    @Test fun `slack installation mutations require an organization admin`() {
        val o = seedOrg("A")
        val u = seedUser("member@t.com")
        val installationId = resourceId(700)
        seedMembership(o, u, "member")

        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            val auth = "Bearer ${token(u, o)}"
            val requests = listOf(
                client.put("/integrations/slack/installations/$installationId/channel") {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody("""{"channelId":"C2","channelName":"alerts"}""")
                },
                client.put("/integrations/slack/installations/$installationId/default") {
                    header(HttpHeaders.Authorization, auth)
                },
                client.put("/integrations/slack/installations/$installationId/enabled") {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody("""{"enabled":false}""")
                },
                client.post("/integrations/slack/installations/$installationId/health") {
                    header(HttpHeaders.Authorization, auth)
                },
                client.post("/integrations/slack/installations/$installationId/test") {
                    header(HttpHeaders.Authorization, auth)
                },
                client.delete("/integrations/slack/installations/$installationId") {
                    header(HttpHeaders.Authorization, auth)
                },
            )

            assertTrue(requests.all { it.status == HttpStatusCode.Forbidden })
        }
    }

    @Test fun `slack installation health and test delivery`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        val installationId = resourceId(700)
        val summary = slackInstallationSummary()
        seedMembership(o, u, "owner")
        coEvery {
            mockSlackInstallationService.verifyInstallation(o, installationId, any())
        } returns summary
        every { mockSlackInstallationService.deliveryConfig(o, installationId) } returns SlackDeliveryConfig(
            installationId = installationId,
            accessToken = "xoxb-modeled",
            teamId = "T1",
            botUserId = "B1",
            channelId = "C1",
        )
        coEvery { mockSlackService.testConnection("xoxb-modeled", "C1") } returns (true to "OK")

        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            val auth = "Bearer ${token(u, o)}"
            assertEquals(
                HttpStatusCode.OK,
                client.post("/integrations/slack/installations/$installationId/health") {
                    header(HttpHeaders.Authorization, auth)
                }.status,
            )
            assertEquals(
                HttpStatusCode.OK,
                client.post("/integrations/slack/installations/$installationId/test") {
                    header(HttpHeaders.Authorization, auth)
                }.status,
            )
        }
    }

    @Test fun `slack installation errors map to client status`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")
        every { mockSlackInstallationService.listInstallations(o) } throws
            IllegalArgumentException("Invalid Slack installation")

        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            val response = client.get("/integrations/slack/installations") {
                header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid Slack installation"))
        }
    }

    @Test fun `slack installation request decoding errors return bad request`() {
        val o = seedOrg("A")
        val u = seedUser("u@t.com")
        seedMembership(o, u, "owner")

        testApplication {
            application {
                installTestApp()
                routing { authenticate("auth-jwt") { integrationRoutes() } }
            }
            val response = client.put("/integrations/slack/installations/${resourceId(700)}/enabled") {
                header(HttpHeaders.Authorization, "Bearer ${token(u, o)}")
                contentType(ContentType.Application.Json)
                setBody("""{"enabled":"not-a-boolean"}""")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
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
                    header(HttpHeaders.Authorization, "Bearer ${token(u, null)}")
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
                    header(HttpHeaders.Authorization, "Bearer ${token(u, null)}")
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
                    header(HttpHeaders.Authorization, "Bearer ${token(u, null)}")
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
                    header(HttpHeaders.Authorization, "Bearer ${token(u, null)}")
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
                    header(HttpHeaders.Authorization, "Bearer ${token(u, null)}")
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
                    header(HttpHeaders.Authorization, "Bearer ${token(u, null)}")
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
                    header(HttpHeaders.Authorization, "Bearer ${token(u, null)}")
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
                    header(HttpHeaders.Authorization, "Bearer ${token(u, null)}")
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
                    header(HttpHeaders.Authorization, "Bearer ${token(u, null)}")
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
                    header(HttpHeaders.Authorization, "Bearer ${token(u, null)}")
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
                    header(HttpHeaders.Authorization, "Bearer ${token(u, null)}")
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
        val installationId = seedSlackInstallation(o, "T1")
        every { mockSlackInstallationService.internalInstallationIdForTeam(o, "T1") } returns installationId
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
        val installationId = seedSlackInstallation(o, "NEW")
        every { mockSlackInstallationService.internalInstallationIdForTeam(o, "NEW") } returns installationId
        transaction {
            SlackUserMappings.insert {
                it[userId] = u
                it[slackInstallationId] = installationId
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
