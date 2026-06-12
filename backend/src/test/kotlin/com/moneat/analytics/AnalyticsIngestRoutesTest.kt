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

package com.moneat.analytics

import com.moneat.analytics.routes.analyticsIngestRoutes
import com.moneat.analytics.routes.AnalyticsIngestRouteDependencies
import com.moneat.analytics.routes.extractAnalyticsPublicKey
import com.moneat.analytics.routes.extractPathname
import com.moneat.analytics.routes.extractUtmParams
import com.moneat.analytics.services.GeoIpService
import com.moneat.analytics.services.SessionHashService
import com.moneat.billing.models.BillingUsageResponse
import com.moneat.billing.models.OrgUsageCounters
import com.moneat.billing.models.PricingTierConfigs
import com.moneat.billing.services.BillingQuotaService
import com.moneat.billing.services.QuotaReservationResult
import com.moneat.config.RedisConfig
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.ProjectKeys
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Subscriptions
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.context.GlobalContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyticsIngestRoutesTest {
    companion object {
        private var dbInitialized = false
    }

    private val mockRedis = mockk<RedisCommands<String, String>>(relaxed = true)

    private fun analyticsDependencies(
        quotaService: BillingQuotaService = GlobalContext.get().get(),
        enqueueEvent: (String) -> Unit = {},
    ): AnalyticsIngestRouteDependencies =
        AnalyticsIngestRouteDependencies().apply {
            sessionHashService = SessionHashService()
            geoIpService = GeoIpService()
            eventService = GlobalContext.get().get()
            this.quotaService = quotaService
            this.enqueueEvent = enqueueEvent
        }

    @BeforeTest
    fun setupKoin() {
        startTestKoin()
        mockkObject(RedisConfig)
        every { RedisConfig.sync() } returns mockRedis
        every { mockRedis.get(match { it.startsWith("moneat:analytics:salt:") }) } returns
            "dGVzdHNhbHR0ZXN0c2FsdHRlc3RzYWx0dGVzdHNhbHR0ZXN0c2FsdA=="
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_analytics_routes;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            dbInitialized = true
        }
        TestDatabaseHelper.resetSchema(
            Organizations,
            Projects,
            ProjectKeys,
            PricingTierConfigs,
            Subscriptions,
            OrgUsageCounters
        )
    }

    @AfterTest
    fun teardownKoin() {
        unmockkObject(RedisConfig)
        stopTestKoin()
    }

    @Test
    fun `extractAnalyticsPublicKey reads sentry_key from X-Sentry-Auth header`() {
        val header =
            "Sentry sentry_version=7, sentry_key=abc123def, sentry_client=test/1"
        assertEquals("abc123def", extractAnalyticsPublicKey(header, null))
    }

    @Test
    fun `extractAnalyticsPublicKey matches sentry_key prefix case insensitively`() {
        val header = "SENTRY sentry_key=abc123"
        assertEquals("abc123", extractAnalyticsPublicKey(header, null))
    }

    @Test
    fun `extractAnalyticsPublicKey falls back to query param`() {
        assertEquals("pubkey999", extractAnalyticsPublicKey(null, "pubkey999"))
    }

    @Test
    fun `extractAnalyticsPublicKey prefers header over query param`() {
        val key = extractAnalyticsPublicKey("Sentry sentry_key=fromheader", "fromquery")
        assertEquals("fromheader", key)
    }

    @Test
    fun `extractAnalyticsPublicKey returns null when missing or invalid`() {
        assertNull(extractAnalyticsPublicKey(null, null))
        assertNull(extractAnalyticsPublicKey("no key here", null))
        assertNull(extractAnalyticsPublicKey(null, "bad/key"))
    }

    @Test
    fun `extractPathname returns path from absolute URL`() {
        assertEquals("/blog/post", extractPathname("https://example.com/blog/post?x=1#frag"))
    }

    @Test
    fun `extractPathname returns slash on invalid URL`() {
        assertEquals("/", extractPathname("not a valid uri with spaces only"))
    }

    @Test
    fun `extractUtmParams collects utm query pairs`() {
        val url = "https://x.test/page?utm_source=news&utm_medium=email&utm_campaign=spring&other=1"
        val m = extractUtmParams(url)
        assertEquals("news", m["utm_source"])
        assertEquals("email", m["utm_medium"])
        assertEquals("spring", m["utm_campaign"])
        assertEquals(3, m.size)
    }

    @Test
    fun `extractUtmParams decodes percent and plus encoding in values`() {
        val m = extractUtmParams("https://x.test/?utm_term=a+b&utm_content=hello%20world")
        assertEquals("a b", m["utm_term"])
        assertEquals("hello world", m["utm_content"])
    }

    @Test
    fun `extractUtmParams returns empty map for URL without utm`() {
        assertTrue(extractUtmParams("https://example.com/").isEmpty())
        assertTrue(extractUtmParams("https://example.com/page?foo=bar").isEmpty())
    }

    @Test
    fun `extractUtmParams returns empty map for invalid URL`() {
        assertTrue(extractUtmParams("not a valid uri with utm_source=x").isEmpty())
    }

    @Test
    fun `POST domain analytics event without sentry_key returns 401`() = testApplication {
        application {
            routing {
                analyticsIngestRoutes(
                    dependencies = analyticsDependencies(enqueueEvent = { }),
                )
            }
        }
        val response = client.post("/api/myapp.example/analytics/event") {
            contentType(ContentType.Application.Json)
            setBody("""{"n":"pageview","u":"https://myapp.example/","d":"myapp.example"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("sentry_key"))
    }

    @Test
    fun `POST project analytics event without auth returns 401`() = testApplication {
        application {
            routing {
                analyticsIngestRoutes(
                    dependencies = analyticsDependencies(enqueueEvent = { }),
                )
            }
        }
        val response = client.post("/api/123/analytics/event") {
            contentType(ContentType.Application.Json)
            setBody("""{"n":"pageview","u":"https://x.test/","d":"x.test"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET tracking script returns JavaScript`() = testApplication {
        application {
            routing {
                analyticsIngestRoutes(
                    dependencies = analyticsDependencies(enqueueEvent = { }),
                )
            }
        }
        val response = client.get("/js/m.js")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.JavaScript, response.contentType()?.withoutParameters())
        val body = response.bodyAsText()
        assertTrue(body.contains("function"))
        assertTrue(body.contains("moneat"))
    }

    @Test
    fun `POST project analytics event reserves quota and enqueues event`() = testApplication {
        val (orgId, projectId, publicKey) = seedAnalyticsProject()
        var queuedMessage: String? = null

        application {
            install(ContentNegotiation) { json() }
            routing {
                analyticsIngestRoutes(
                    dependencies = analyticsDependencies(enqueueEvent = { queuedMessage = it }),
                )
            }
        }

        val response = client.post("/api/$projectId/analytics/event?sentry_key=$publicKey") {
            contentType(ContentType.Application.Json)
            header("User-Agent", "Mozilla/5.0 Test Browser")
            setBody(
                """
                {
                  "n": "pageview",
                  "u": "https://analytics.example/pricing?utm_source=newsletter",
                  "d": "analytics.example",
                  "r": "https://google.com/search?q=moneat",
                  "w": 1440,
                  "p": {"plan": "pro"}
                }
                """.trimIndent()
            )
        }

        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.Accepted, response.status, body)
        assertEquals("ok", body)
        val message = requireNotNull(queuedMessage)
        assertTrue(message.contains("\"projectId\":$projectId"))
        assertTrue(message.contains("\"hostname\":\"analytics.example\""))
        assertEquals(1L, usedAnalyticsPageviews(orgId))
    }

    @Test
    fun `POST domain analytics event accepts text payload`() = testApplication {
        val (orgId, _, publicKey) = seedAnalyticsProject()
        var queuedMessage: String? = null

        application {
            routing {
                analyticsIngestRoutes(
                    dependencies = analyticsDependencies(enqueueEvent = { queuedMessage = it }),
                )
            }
        }

        val response = client.post("/api/analytics.example/analytics/event?sentry_key=$publicKey") {
            contentType(ContentType.Text.Plain)
            setBody("""{"n":"pageview","u":"https://analytics.example/docs","d":"analytics.example"}""")
        }

        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        assertTrue(requireNotNull(queuedMessage).contains("\"pathname\":\"/docs\""))
        assertEquals(1L, usedAnalyticsPageviews(orgId))
    }

    @Test
    fun `POST project analytics event returns 400 for invalid payload after auth`() = testApplication {
        val (_, projectId, publicKey) = seedAnalyticsProject()

        application {
            install(ContentNegotiation) { json() }
            routing {
                analyticsIngestRoutes(
                    dependencies = analyticsDependencies(enqueueEvent = { }),
                )
            }
        }

        val response = client.post("/api/$projectId/analytics/event?sentry_key=$publicKey") {
            contentType(ContentType.Application.Json)
            setBody("""{"n":""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid payload"))
    }

    @Test
    fun `POST project analytics event returns 429 when quota is exceeded`() = testApplication {
        val (orgId, projectId, publicKey) = seedAnalyticsProject()
        val quotaService = mockk<BillingQuotaService>(relaxed = true)
        every { quotaService.isEnforcementEnabled() } returns true
        every { quotaService.reserveUnits(orgId, 1, "analytics_pageview", 0) } returns
            QuotaReservationResult(
                allowed = false,
                reason = "analytics_pageview_quota_exceeded",
                usage = quotaUsage(orgId)
            )

        application {
            install(ContentNegotiation) { json() }
            routing {
                analyticsIngestRoutes(
                    dependencies = analyticsDependencies(quotaService = quotaService, enqueueEvent = { }),
                )
            }
        }

        val response = client.post("/api/$projectId/analytics/event?sentry_key=$publicKey") {
            contentType(ContentType.Application.Json)
            setBody("""{"n":"pageview","u":"https://analytics.example/","d":"analytics.example"}""")
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("analytics_pageview_quota_exceeded"))
    }

    @Test
    fun `POST project analytics event refunds quota when enqueue fails`() = testApplication {
        val (orgId, projectId, publicKey) = seedAnalyticsProject()
        val quotaService = mockk<BillingQuotaService>(relaxed = true)
        every { quotaService.isEnforcementEnabled() } returns true
        every { quotaService.reserveUnits(orgId, 1, "analytics_pageview", 0) } returns
            QuotaReservationResult(allowed = true, usage = quotaUsage(orgId))

        application {
            install(ContentNegotiation) { json() }
            routing {
                analyticsIngestRoutes(
                    dependencies = analyticsDependencies(
                        quotaService = quotaService,
                        enqueueEvent = { throw RuntimeException("queue down") },
                    ),
                )
            }
        }

        val response = client.post("/api/$projectId/analytics/event?sentry_key=$publicKey") {
            contentType(ContentType.Application.Json)
            setBody("""{"n":"pageview","u":"https://analytics.example/","d":"analytics.example"}""")
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status, response.bodyAsText())
        verify { quotaService.refundUnits(orgId, 1, "analytics_pageview", 0) }
    }

    @Test
    fun `POST project analytics event returns 403 when analytics site limit is reached`() = testApplication {
        val (orgId, projectId, publicKey) = seedAnalyticsProject()
        seedAnalyticsTier(orgId, maxSites = 0)

        application {
            install(ContentNegotiation) { json() }
            routing {
                analyticsIngestRoutes(
                    dependencies = analyticsDependencies(enqueueEvent = { }),
                )
            }
        }

        val response = client.post("/api/$projectId/analytics/event?sentry_key=$publicKey") {
            contentType(ContentType.Application.Json)
            setBody("""{"n":"pageview","u":"https://new.example/","d":"new.example"}""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("Analytics site limit reached"))
    }

    private fun seedAnalyticsProject(): Triple<Int, Long, String> {
        return transaction {
            val orgId = Organizations.insert {
                it[name] = "Analytics Test Org"
                it[slug] = "analytics-test-org"
            } get Organizations.id
            val projectId = Projects.insert {
                it[organization_id] = orgId
                it[name] = "Analytics Project"
                it[slug] = "analytics-project"
                it[framework] = "web"
            } get Projects.id
            val publicKey = "analytics-public-key"
            ProjectKeys.insert {
                it[project_id] = projectId
                it[public_key] = publicKey
                it[secret_key] = null
                it[platform_target] = "web"
                it[is_active] = true
            }
            Triple(orgId, projectId, publicKey)
        }
    }

    private fun seedAnalyticsTier(orgId: Int, maxSites: Int) {
        transaction {
            val tierId = PricingTierConfigs.insert {
                it[tier_name] = "ANALYTICS_TEST"
                it[monthly_unit_limit] = 1_000
                it[retention_days] = 30
                it[log_retention_days] = 30
                it[max_systems] = 10
                it[monitor_interval_seconds] = 60
                it[monthly_price_cents] = 0
                it[max_analytics_sites] = maxSites
            } get PricingTierConfigs.id
            Subscriptions.insert {
                it[organization_id] = orgId
                it[plan] = "ANALYTICS_TEST"
                it[status] = "active"
                it[pricing_tier_config_id] = tierId
            }
        }
    }

    private fun quotaUsage(orgId: Int): BillingUsageResponse {
        return BillingUsageResponse(
            organizationId = resourceId(orgId),
            periodStart = "2026-05-01",
            periodEnd = "2026-05-31",
            retentionDays = 30,
            apmTraceRetentionDays = 30,
            usedUnits = 0,
            usedErrors = 0,
            errorLimit = 0,
            usedTransactions = 0,
            transactionLimit = 0,
            usedReplays = 0,
            replayLimit = 0,
            usedFeedback = 0,
            feedbackLimit = 0,
            usedBytes = 0,
            bytesLimit = 0,
            baseLimitUnits = 0,
            paygLimitUnits = 0,
            totalLimitUnits = 0,
            paygBudgetCents = 0,
            paygUsedUnits = 0,
            paygUsedCentsEstimate = 0,
            plan = "PRO",
            status = "active",
            withinQuota = true
        )
    }

    private fun resourceId(id: Int): String =
        "00000000-0000-0000-0000-${id.toString().padStart(12, '0')}"

    private fun usedAnalyticsPageviews(orgId: Int): Long {
        return transaction {
            OrgUsageCounters
                .selectAll()
                .where { OrgUsageCounters.organization_id eq orgId }
                .first()[OrgUsageCounters.used_analytics_pageviews]
        }
    }
}
