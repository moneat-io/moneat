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

import com.moneat.billing.models.ApmSpanUsageDebugGroup
import com.moneat.billing.models.ApmSpanUsageDebugResponse
import com.moneat.billing.models.BillingUsageInsightsResponse
import com.moneat.billing.models.BillingUsageResponse
import com.moneat.billing.models.CancelSubscriptionResponse
import com.moneat.billing.models.CheckoutSessionResponse
import com.moneat.billing.models.InvoiceResponse
import com.moneat.billing.models.PaymentMethodResponse
import com.moneat.billing.models.SetupIntentResponse
import com.moneat.billing.models.UpdateOnCallSeatsResponse
import com.moneat.billing.routes.billingRoutes
import com.moneat.billing.services.BillingQuotaService
import com.moneat.billing.services.BillingUsageInsightsService
import com.moneat.billing.services.EffectiveTierContext
import com.moneat.billing.services.PricingTierService
import com.moneat.billing.services.StripeService
import com.moneat.config.EnvConfig
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.Users
import com.moneat.shared.services.UsageTrackingService
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.TestDatabaseHelper
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BillingRoutesTest {
    companion object {
        private const val BILLING_USAGE = "/v1/billing/usage"
        private const val BILLING_USAGE_INSIGHTS = "/v1/billing/usage/insights"
        private const val BILLING_APM_SPAN_DEBUG = "/v1/billing/usage/apm-spans"
        private const val BILLING_CHECKOUT = "/v1/billing/checkout"
        private const val BILLING_INVOICES = "/v1/billing/invoices"
        private const val BILLING_PAYMENT_METHOD = "/v1/billing/payment-method"
        private const val BILLING_SETUP_INTENT = "/v1/billing/setup-intent"
        private const val BILLING_CANCEL = "/v1/billing/cancel"
        private const val BILLING_PAYG_BUDGET = "/v1/billing/payg-budget"
        private const val BILLING_ONCALL_SEATS = "/v1/billing/oncall-seats"
        private var db: Database? = null
    }

    private lateinit var mockPricingTierService: PricingTierService
    private lateinit var mockQuotaService: BillingQuotaService
    private lateinit var mockInsightsService: BillingUsageInsightsService
    private lateinit var mockStripeService: StripeService
    private lateinit var mockUsageTrackingService: UsageTrackingService

    @BeforeTest
    fun setupDatabase() {
        mockPricingTierService = mockk<PricingTierService>(relaxed = true)
        mockQuotaService = mockk<BillingQuotaService>(relaxed = true)
        mockInsightsService = mockk<BillingUsageInsightsService>(relaxed = true)
        mockStripeService = mockk<StripeService>(relaxed = true)
        mockUsageTrackingService = mockk<UsageTrackingService>(relaxed = true)
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_billing_routes;" +
                    "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
                    "DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Memberships,
            Subscriptions
        )
        mockkObject(UsageTrackingService)
        every { UsageTrackingService.instance } returns mockUsageTrackingService
        mockkObject(EnvConfig.SelfHost)
        every { EnvConfig.SelfHost.enabled } returns false
    }

    @AfterTest
    fun tearDown() {
        if (::mockPricingTierService.isInitialized) {
            clearMocks(
                mockPricingTierService,
                mockQuotaService,
                mockInsightsService,
                mockStripeService,
                mockUsageTrackingService
            )
        }
        unmockkObject(UsageTrackingService)
        unmockkObject(EnvConfig.SelfHost)
    }

    private fun Application.installAuth() {
        installJwtAuth()
    }

    private fun token(userId: Int): String = RouteTestSupport.createToken(userId)

    private fun seedUser(): Int = transaction {
        Users.insert {
            it[email] = "billing-${System.nanoTime()}@test.com"
            it[password_hash] = "hash"
            it[email_verified] = true
        } get Users.id
    }

    private fun seedOrg(): Int = transaction {
        Organizations.insert {
            it[name] = "Billing Org"
            it[slug] = "billing-org-${System.nanoTime()}"
        } get Organizations.id
    }

    private fun seedMembership(userId: Int, orgId: Int) {
        transaction {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }
        }
    }

    private fun seedUserAndOrg(): Pair<Int, Int> {
        val orgId = seedOrg()
        val userId = seedUser()
        seedMembership(userId, orgId)
        return Pair(userId, orgId)
    }

    private fun installRoutes(app: Application) {
        app.installAuth()
        app.routing {
            authenticate("auth-jwt") {
                route("/v1") {
                    billingRoutes(
                        pricingTierService = mockPricingTierService,
                        quotaService = mockQuotaService,
                        stripeService = mockStripeService,
                        insightsService = mockInsightsService,
                    )
                }
            }
        }
    }

    private fun makeUsageResponse(orgId: Int) = BillingUsageResponse(
        organizationId = orgId,
        periodStart = "2024-01-01",
        periodEnd = "2024-01-31",
        retentionDays = 30,
        apmTraceRetentionDays = 30,
        usedUnits = 100,
        usedErrors = 10,
        errorLimit = 1000,
        usedTransactions = 5,
        transactionLimit = 500,
        usedReplays = 0,
        replayLimit = 100,
        usedFeedback = 0,
        feedbackLimit = 100,
        usedBytes = 1024,
        bytesLimit = 1073741824,
        baseLimitUnits = 10000,
        paygLimitUnits = 0,
        totalLimitUnits = 10000,
        paygBudgetCents = 0,
        paygUsedUnits = 0,
        paygUsedCentsEstimate = 0,
        plan = "FREE",
        status = "active",
        withinQuota = true,
    )

    private fun makeApmSpanDebugResponse(
        orgId: Int,
        usage: BillingUsageResponse,
        totalSpans: Long = 0
    ) = ApmSpanUsageDebugResponse(
        organizationId = orgId,
        periodStart = usage.periodStart,
        periodEnd = usage.periodEnd,
        totalSpans = totalSpans,
        groups = emptyList()
    )

    private fun stubApmSpanDebug(
        orgId: Int,
        usage: BillingUsageResponse,
        expectedLimit: Int
    ): ApmSpanUsageDebugResponse {
        val response = makeApmSpanDebugResponse(orgId, usage)
        coEvery {
            mockQuotaService.getApmSpanUsageDebug(
                organizationId = orgId,
                periodStart = LocalDate.parse(usage.periodStart),
                periodEnd = LocalDate.parse(usage.periodEnd),
                limit = expectedLimit
            )
        } returns response
        return response
    }

    private fun makeUsageInsightsResponse(
        orgId: Int,
        usage: BillingUsageResponse = makeUsageResponse(orgId)
    ) = BillingUsageInsightsResponse(
        organizationId = orgId,
        periodStart = usage.periodStart,
        periodEnd = usage.periodEnd,
        generatedAt = "2026-01-15T12:00:00Z",
        billingMode = "cloud",
        usage = usage,
        dimensions = emptyList(),
        apmSpanDebug = makeApmSpanDebugResponse(orgId, usage)
    )

    // ──── Auth ────

    @Test
    fun `GET usage returns 401 when unauthenticated`() =
        testApplication {
            application { installRoutes(this) }
            val r = client.get(BILLING_USAGE)
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }

    @Test
    fun `GET usage apm spans returns 401 when unauthenticated`() =
        testApplication {
            application { installRoutes(this) }
            val r = client.get(BILLING_APM_SPAN_DEBUG)
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }

    @Test
    fun `GET usage insights returns 401 when unauthenticated`() =
        testApplication {
            application { installRoutes(this) }
            val r = client.get(BILLING_USAGE_INSIGHTS)
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }

    @Test
    fun `POST checkout returns 401 when unauthenticated`() =
        testApplication {
            application { installRoutes(this) }
            val r = client.post(BILLING_CHECKOUT)
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }

    @Test
    fun `GET invoices returns 401 when unauthenticated`() =
        testApplication {
            application { installRoutes(this) }
            val r = client.get(BILLING_INVOICES)
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }

    @Test
    fun `GET payment-method returns 401 when unauthenticated`() =
        testApplication {
            application { installRoutes(this) }
            val r = client.get(BILLING_PAYMENT_METHOD)
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }

    @Test
    fun `POST setup-intent returns 401 when unauthenticated`() =
        testApplication {
            application { installRoutes(this) }
            val r = client.post(BILLING_SETUP_INTENT)
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }

    @Test
    fun `POST cancel returns 401 when unauthenticated`() =
        testApplication {
            application { installRoutes(this) }
            val r = client.post(BILLING_CANCEL)
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }

    @Test
    fun `PUT payg-budget returns 401 when unauthenticated`() =
        testApplication {
            application { installRoutes(this) }
            val r = client.put(BILLING_PAYG_BUDGET)
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }

    @Test
    fun `PUT oncall-seats returns 401 when unauthenticated`() =
        testApplication {
            application { installRoutes(this) }
            val r = client.put(BILLING_ONCALL_SEATS)
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }

    // ──── Forbidden (no org) ────

    @Test
    fun `GET usage returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns null
            application { installRoutes(this) }
            val r = client.get(BILLING_USAGE) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    @Test
    fun `GET usage apm spans returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns null
            application { installRoutes(this) }
            val r = client.get(BILLING_APM_SPAN_DEBUG) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    @Test
    fun `GET usage insights returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns null
            application { installRoutes(this) }
            val r = client.get(BILLING_USAGE_INSIGHTS) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    @Test
    fun `POST checkout returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns null
            application { installRoutes(this) }
            val r = client.post(BILLING_CHECKOUT) {
                withAuth(token(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"tierName":"PRO","successUrl":"http://x","cancelUrl":"http://y"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    @Test
    fun `GET invoices returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns null
            application { installRoutes(this) }
            val r = client.get(BILLING_INVOICES) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    @Test
    fun `POST cancel returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns null
            application { installRoutes(this) }
            val r = client.post(BILLING_CANCEL) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    // ──── GET /billing/usage ────

    @Test
    fun `GET usage returns 200 with usage data`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            every { mockQuotaService.getUsageForOrganization(orgId) } returns makeUsageResponse(orgId)
            every {
                mockUsageTrackingService.getTotalBytesForOrg(orgId, any(), any())
            } returns 2048L
            every {
                mockUsageTrackingService.getEventCountForOrg(orgId, any(), any(), any())
            } returns 0L
            application { installRoutes(this) }

            val r = client.get(BILLING_USAGE) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.OK, r.status)
            val body = r.bodyAsText()
            assertTrue(body.contains("\"organizationId\""))
            assertTrue(body.contains("\"withinQuota\""))
        }

    @Test
    fun `GET usage apm spans returns 200 with debug groups`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val usage = makeUsageResponse(orgId)
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            every { mockQuotaService.getUsageForOrganization(orgId) } returns usage
            coEvery {
                mockQuotaService.getApmSpanUsageDebug(
                    organizationId = orgId,
                    periodStart = kotlinx.datetime.LocalDate.parse(usage.periodStart),
                    periodEnd = kotlinx.datetime.LocalDate.parse(usage.periodEnd),
                    limit = 20
                )
            } returns ApmSpanUsageDebugResponse(
                organizationId = orgId,
                periodStart = usage.periodStart,
                periodEnd = usage.periodEnd,
                totalSpans = 42,
                groups = listOf(
                    ApmSpanUsageDebugGroup(
                        source = "otlp",
                        service = "api",
                        operation = "GET /checkout",
                        resource = "GET /checkout",
                        spanType = "",
                        env = "prod",
                        kind = "SERVER",
                        scopeName = "opentelemetry.instrumentation.ktor",
                        scopeVersion = "1.0.0",
                        projectId = null,
                        projectName = null,
                        projectSlug = null,
                        spanCount = 42,
                        traceCount = 12,
                        errorCount = 1,
                        avgDurationMs = 12.5,
                        maxDurationMs = 200.0,
                        percentage = 100.0,
                        sampleTraceId = "trace-1",
                        latestSpanAt = "2024-01-15 12:00:00"
                    )
                )
            )
            application { installRoutes(this) }

            val r = client.get(BILLING_APM_SPAN_DEBUG) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.OK, r.status)
            val body = r.bodyAsText()
            assertTrue(body.contains("\"totalSpans\":42"))
            assertTrue(body.contains("\"service\":\"api\""))
        }

    @Test
    fun `GET usage insights returns 200 with insights data`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val response = makeUsageInsightsResponse(orgId)
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            coEvery { mockInsightsService.getUsageInsights(orgId) } returns response
            application { installRoutes(this) }

            val r = client.get(BILLING_USAGE_INSIGHTS) {
                withAuth(token(userId))
            }

            assertEquals(HttpStatusCode.OK, r.status)
            val body = r.bodyAsText()
            assertTrue(body.contains("\"billingMode\":\"cloud\""))
            assertTrue(body.contains("\"dimensions\":[]"))
        }

    @Test
    fun `GET usage insights returns 503 when insights are unavailable`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            coEvery { mockInsightsService.getUsageInsights(orgId) } throws IllegalStateException("boom")
            application { installRoutes(this) }

            val r = client.get(BILLING_USAGE_INSIGHTS) {
                withAuth(token(userId))
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
            assertTrue(r.bodyAsText().contains("Usage insights are temporarily unavailable"))
        }

    @Test
    fun `GET usage insights returns 403 for self hosted deployments`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { EnvConfig.SelfHost.enabled } returns true
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            application { installRoutes(this) }

            val r = client.get(BILLING_USAGE_INSIGHTS) {
                withAuth(token(userId))
            }

            assertEquals(HttpStatusCode.Forbidden, r.status)
            assertTrue(r.bodyAsText().contains("Moneat Cloud"))
        }

    @Test
    fun `GET usage apm spans clamps low limit to one`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val usage = makeUsageResponse(orgId)
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            every { mockQuotaService.getUsageForOrganization(orgId) } returns usage
            stubApmSpanDebug(orgId, usage, expectedLimit = 1)
            application { installRoutes(this) }

            val r = client.get("$BILLING_APM_SPAN_DEBUG?limit=0") {
                withAuth(token(userId))
            }

            assertEquals(HttpStatusCode.OK, r.status)
            coVerify(exactly = 1) {
                mockQuotaService.getApmSpanUsageDebug(
                    organizationId = orgId,
                    periodStart = LocalDate.parse(usage.periodStart),
                    periodEnd = LocalDate.parse(usage.periodEnd),
                    limit = 1
                )
            }
        }

    @Test
    fun `GET usage apm spans clamps high limit to max`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val usage = makeUsageResponse(orgId)
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            every { mockQuotaService.getUsageForOrganization(orgId) } returns usage
            stubApmSpanDebug(orgId, usage, expectedLimit = 100)
            application { installRoutes(this) }

            val r = client.get("$BILLING_APM_SPAN_DEBUG?limit=500") {
                withAuth(token(userId))
            }

            assertEquals(HttpStatusCode.OK, r.status)
            coVerify(exactly = 1) {
                mockQuotaService.getApmSpanUsageDebug(
                    organizationId = orgId,
                    periodStart = LocalDate.parse(usage.periodStart),
                    periodEnd = LocalDate.parse(usage.periodEnd),
                    limit = 100
                )
            }
        }

    @Test
    fun `GET usage apm spans defaults non-numeric limit`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            val usage = makeUsageResponse(orgId)
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            every { mockQuotaService.getUsageForOrganization(orgId) } returns usage
            stubApmSpanDebug(orgId, usage, expectedLimit = 20)
            application { installRoutes(this) }

            val r = client.get("$BILLING_APM_SPAN_DEBUG?limit=abc") {
                withAuth(token(userId))
            }

            assertEquals(HttpStatusCode.OK, r.status)
            coVerify(exactly = 1) {
                mockQuotaService.getApmSpanUsageDebug(
                    organizationId = orgId,
                    periodStart = LocalDate.parse(usage.periodStart),
                    periodEnd = LocalDate.parse(usage.periodEnd),
                    limit = 20
                )
            }
        }

    // ──── POST /billing/checkout ────

    @Test
    fun `POST checkout returns 200 on success`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            every {
                mockStripeService.createCheckoutSession(
                    organizationId = orgId,
                    tierName = "PRO",
                    billingInterval = "monthly",
                    successUrl = "http://success",
                    cancelUrl = "http://cancel",
                    oncallSeats = 0
                )
            } returns CheckoutSessionResponse(sessionId = "cs_123", url = "https://stripe.com/checkout")
            application { installRoutes(this) }

            val r = client.post(BILLING_CHECKOUT) {
                withAuth(token(userId))
                contentType(ContentType.Application.Json)
                setBody(
                    """{"tierName":"PRO","billingInterval":"monthly",""" +
                        """"successUrl":"http://success","cancelUrl":"http://cancel"}"""
                )
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("cs_123"))
        }

    @Test
    fun `POST checkout returns 400 on invalid request`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            every {
                mockStripeService.createCheckoutSession(
                    organizationId = orgId,
                    tierName = any(),
                    billingInterval = any(),
                    successUrl = any(),
                    cancelUrl = any(),
                    oncallSeats = any()
                )
            } throws IllegalArgumentException("Invalid tier")
            application { installRoutes(this) }

            val r = client.post(BILLING_CHECKOUT) {
                withAuth(token(userId))
                contentType(ContentType.Application.Json)
                setBody(
                    """{"tierName":"INVALID","successUrl":"http://s","cancelUrl":"http://c"}"""
                )
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `POST checkout returns 500 on service error`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            every {
                mockStripeService.createCheckoutSession(
                    organizationId = orgId,
                    tierName = any(),
                    billingInterval = any(),
                    successUrl = any(),
                    cancelUrl = any(),
                    oncallSeats = any()
                )
            } throws RuntimeException("Stripe error")
            application { installRoutes(this) }

            val r = client.post(BILLING_CHECKOUT) {
                withAuth(token(userId))
                contentType(ContentType.Application.Json)
                setBody(
                    """{"tierName":"PRO","successUrl":"http://s","cancelUrl":"http://c"}"""
                )
            }
            assertEquals(HttpStatusCode.InternalServerError, r.status)
        }

    // ──── GET /billing/invoices ────

    @Test
    fun `GET invoices returns 200 with list`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            every { mockStripeService.listInvoices(orgId) } returns listOf(
                InvoiceResponse(
                    id = "inv_1",
                    date = "2024-01-15",
                    amountCents = 2900,
                    status = "paid",
                    pdfUrl = "https://stripe.com/inv.pdf"
                )
            )
            application { installRoutes(this) }

            val r = client.get(BILLING_INVOICES) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("inv_1"))
        }

    @Test
    fun `GET invoices returns 400 on service error`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            every { mockStripeService.listInvoices(orgId) } throws RuntimeException("Stripe error")
            application { installRoutes(this) }

            val r = client.get(BILLING_INVOICES) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    // ──── GET /billing/payment-method ────

    @Test
    fun `GET payment-method returns 200`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            every { mockStripeService.getPaymentMethod(orgId) } returns PaymentMethodResponse(
                brand = "visa", last4 = "4242", expMonth = 12, expYear = 2026
            )
            application { installRoutes(this) }

            val r = client.get(BILLING_PAYMENT_METHOD) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("4242"))
        }

    @Test
    fun `GET payment-method returns 400 on error`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            every { mockStripeService.getPaymentMethod(orgId) } throws RuntimeException("No customer")
            application { installRoutes(this) }

            val r = client.get(BILLING_PAYMENT_METHOD) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    // ──── POST /billing/setup-intent ────

    @Test
    fun `POST setup-intent returns 200`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            every { mockStripeService.createSetupIntent(orgId) } returns SetupIntentResponse(
                clientSecret = "seti_secret_123"
            )
            application { installRoutes(this) }

            val r = client.post(BILLING_SETUP_INTENT) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("seti_secret_123"))
        }

    @Test
    fun `POST setup-intent returns 400 on error`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            every { mockStripeService.createSetupIntent(orgId) } throws RuntimeException("Failed")
            application { installRoutes(this) }

            val r = client.post(BILLING_SETUP_INTENT) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    // ──── POST /billing/setup-intent/confirm ────

    @Test
    fun `POST setup-intent confirm returns 200`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            every {
                mockStripeService.confirmSetupIntentAndUpdatePaymentMethod(orgId, "seti_123")
            } returns Unit
            application { installRoutes(this) }

            val r = client.post("/v1/billing/setup-intent/confirm") {
                withAuth(token(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"setupIntentId":"seti_123"}""")
            }
            assertEquals(HttpStatusCode.OK, r.status)
        }

    @Test
    fun `POST setup-intent confirm returns 400 on error`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            every {
                mockStripeService.confirmSetupIntentAndUpdatePaymentMethod(orgId, any())
            } throws RuntimeException("Invalid intent")
            application { installRoutes(this) }

            val r = client.post("/v1/billing/setup-intent/confirm") {
                withAuth(token(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"setupIntentId":"bad_id"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    // ──── POST /billing/cancel ────

    @Test
    fun `POST cancel returns 200 on success`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            every { mockStripeService.cancelSubscription(orgId) } returns CancelSubscriptionResponse(
                status = "canceled",
                cancelAtPeriodEnd = true,
                currentPeriodEnd = "2024-02-01"
            )
            application { installRoutes(this) }

            val r = client.post(BILLING_CANCEL) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("canceled"))
        }

    @Test
    fun `POST cancel returns 400 when no subscription`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            every {
                mockStripeService.cancelSubscription(orgId)
            } throws IllegalStateException("No cancelable subscription")
            application { installRoutes(this) }

            val r = client.post(BILLING_CANCEL) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `POST cancel returns 500 on unexpected error`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            every {
                mockStripeService.cancelSubscription(orgId)
            } throws RuntimeException("Stripe outage")
            application { installRoutes(this) }

            val r = client.post(BILLING_CANCEL) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.InternalServerError, r.status)
        }

    // ──── PUT /billing/payg-budget ────

    @Test
    fun `PUT payg-budget returns 400 for negative budget`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            application { installRoutes(this) }

            val r = client.put(BILLING_PAYG_BUDGET) {
                withAuth(token(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"paygBudgetCents":-500}""")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `PUT payg-budget returns 400 for non-500 increment`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            application { installRoutes(this) }

            val r = client.put(BILLING_PAYG_BUDGET) {
                withAuth(token(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"paygBudgetCents":123}""")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `PUT payg-budget returns 400 for free tier`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            val tierContext = mockk<EffectiveTierContext>(relaxed = true)
            every { tierContext.tier.paygEnabled } returns false
            every { tierContext.tier.tierName } returns "FREE"
            every { mockPricingTierService.getEffectiveTierForOrganization(orgId) } returns tierContext
            application { installRoutes(this) }

            val r = client.put(BILLING_PAYG_BUDGET) {
                withAuth(token(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"paygBudgetCents":500}""")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertTrue(r.bodyAsText().contains("paid tiers"))
        }

    @Test
    fun `PUT payg-budget returns 200 on valid update`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            val tierContext = mockk<EffectiveTierContext>(relaxed = true)
            every { tierContext.tier.paygEnabled } returns true
            every { tierContext.tier.tierName } returns "PRO"
            every { mockPricingTierService.getEffectiveTierForOrganization(orgId) } returns tierContext

            // Seed an active subscription for the org
            transaction {
                Subscriptions.insert {
                    it[organization_id] = orgId
                    it[plan] = "PRO"
                    it[status] = "active"
                }
            }
            application { installRoutes(this) }

            val r = client.put(BILLING_PAYG_BUDGET) {
                withAuth(token(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"paygBudgetCents":1000}""")
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("1000"))
        }

    @Test
    fun `PUT payg-budget returns 404 when no active subscription`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            val tierContext = mockk<EffectiveTierContext>(relaxed = true)
            every { tierContext.tier.paygEnabled } returns true
            every { tierContext.tier.tierName } returns "PRO"
            every { mockPricingTierService.getEffectiveTierForOrganization(orgId) } returns tierContext
            application { installRoutes(this) }

            val r = client.put(BILLING_PAYG_BUDGET) {
                withAuth(token(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"paygBudgetCents":500}""")
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    // ──── PUT /billing/oncall-seats ────

    @Test
    fun `PUT oncall-seats returns 400 for negative seats`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            application { installRoutes(this) }

            val r = client.put(BILLING_ONCALL_SEATS) {
                withAuth(token(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"seats":-1}""")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertTrue(r.bodyAsText().contains("non-negative"))
        }

    @Test
    fun `PUT oncall-seats returns 400 for seats over 200`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            application { installRoutes(this) }

            val r = client.put(BILLING_ONCALL_SEATS) {
                withAuth(token(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"seats":201}""")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertTrue(r.bodyAsText().contains("200"))
        }

    @Test
    fun `PUT oncall-seats returns 200 on success`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            every {
                mockStripeService.updateOnCallSeats(orgId, 5)
            } returns UpdateOnCallSeatsResponse(seats = 5, proratedAmountCents = 1450)
            application { installRoutes(this) }

            val r = client.put(BILLING_ONCALL_SEATS) {
                withAuth(token(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"seats":5}""")
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("\"seats\":5"))
        }

    @Test
    fun `PUT oncall-seats returns 400 on invalid argument`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            every {
                mockStripeService.updateOnCallSeats(orgId, any())
            } throws IllegalArgumentException("No subscription")
            application { installRoutes(this) }

            val r = client.put(BILLING_ONCALL_SEATS) {
                withAuth(token(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"seats":3}""")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `PUT oncall-seats returns 500 on service error`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockPricingTierService.getPrimaryOrganizationIdForUser(userId) } returns orgId
            every {
                mockStripeService.updateOnCallSeats(orgId, any())
            } throws RuntimeException("Stripe outage")
            application { installRoutes(this) }

            val r = client.put(BILLING_ONCALL_SEATS) {
                withAuth(token(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"seats":3}""")
            }
            assertEquals(HttpStatusCode.InternalServerError, r.status)
        }
}
