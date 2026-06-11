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

package com.moneat.otlp

import com.moneat.billing.services.BillingQuotaService
import com.moneat.billing.models.BillingUsageResponse
import com.moneat.billing.services.QuotaReservationResult
import com.moneat.otlp.routes.otlpMetricsRoutes
import com.moneat.otlp.routes.otlpFeedbackRoutes
import com.moneat.otlp.routes.otlpTraceRoutes
import com.moneat.otlp.services.OtlpApiKeyService
import com.moneat.otlp.services.OtlpFeedbackIngestResult
import com.moneat.otlp.services.OtlpFeedbackInsert
import com.moneat.otlp.services.OtlpFeedbackService
import com.moneat.otlp.services.OtlpMetricInsert
import com.moneat.otlp.services.OtlpMetricsService
import com.moneat.otlp.services.OtlpServiceDescriptor
import com.moneat.otlp.services.OtlpServiceIdentity
import com.moneat.otlp.services.OtlpServiceRoutingService
import com.moneat.otlp.services.OtlpSignalType
import com.moneat.otlp.services.OtlpSpanInsert
import com.moneat.otlp.services.OtlpTraceService
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OtlpIngestRoutesTest {

    companion object {
        private const val VALID_KEY = "otlp-test-api-key-abc"
        private const val ORG_ID = 3
    }

    private val otlpApiKeyService = mockk<OtlpApiKeyService>()
    private val traceService = mockk<OtlpTraceService>(relaxed = true)
    private val metricsService = mockk<OtlpMetricsService>(relaxed = true)
    private val feedbackService = mockk<OtlpFeedbackService>(relaxed = true)
    private val quotaService = mockk<BillingQuotaService>(relaxed = true)
    private val routingService = mockk<OtlpServiceRoutingService>(relaxed = true)

    @BeforeTest
    fun setup() {
        startTestKoin()
        every { quotaService.isEnforcementEnabled() } returns false
    }

    @AfterTest
    fun teardown() {
        stopTestKoin()
    }

    private fun installRoutes(): io.ktor.server.testing.ApplicationTestBuilder.() -> Unit = {
        application {
            install(ContentNegotiation) { json() }
            routing {
                otlpTraceRoutes(traceService, quotaService, otlpApiKeyService, routingService)
                otlpMetricsRoutes(metricsService, quotaService, otlpApiKeyService, routingService)
                otlpFeedbackRoutes(feedbackService, quotaService, otlpApiKeyService, routingService)
            }
        }
    }

    // ──── Trace Routes ────

    @Test
    fun `POST traces returns 401 without bearer token`() = testApplication {
        installRoutes()()
        val response = client.post("/v1/traces") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("Missing or invalid"))
    }

    @Test
    fun `POST traces returns 401 with invalid bearer token`() = testApplication {
        every { otlpApiKeyService.validateKey("invalid-key") } returns null
        installRoutes()()
        val response = client.post("/v1/traces") {
            header(HttpHeaders.Authorization, "Bearer invalid-key")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST traces returns 415 for unsupported content type`() = testApplication {
        every { otlpApiKeyService.validateKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/v1/traces") {
            header(HttpHeaders.Authorization, "Bearer $VALID_KEY")
            header(HttpHeaders.ContentType, "text/plain")
            setBody("data")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `POST traces otlp alias returns 401 without bearer token`() = testApplication {
        installRoutes()()
        val response = client.post("/v1/traces/otlp") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST traces otlp alias returns 401 with invalid token`() = testApplication {
        every { otlpApiKeyService.validateKey("bad") } returns null
        installRoutes()()
        val response = client.post("/v1/traces/otlp") {
            header(HttpHeaders.Authorization, "Bearer bad")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST traces otlp alias returns 415 for unsupported content type`() = testApplication {
        every { otlpApiKeyService.validateKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/v1/traces/otlp") {
            header(HttpHeaders.Authorization, "Bearer $VALID_KEY")
            header(HttpHeaders.ContentType, "text/plain")
            setBody("data")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `POST traces accepts valid json with empty resourceSpans`() = testApplication {
        every { otlpApiKeyService.validateKey(VALID_KEY) } returns ORG_ID
        every { traceService.parseOtlpTracesJson(any()) } returns emptyList()
        installRoutes()()
        val response = client.post("/v1/traces") {
            header(HttpHeaders.Authorization, "Bearer $VALID_KEY")
            contentType(ContentType.Application.Json)
            setBody("""{"resourceSpans":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertTrue(response.bodyAsText().contains("0"))
    }

    @Test
    fun `POST traces routes spans by service mapping before enqueue`() = testApplication {
        val checkoutIdentity = OtlpServiceIdentity("checkout", "api")
        val routedSpans = slot<List<OtlpSpanInsert>>()
        every { otlpApiKeyService.validateKey(VALID_KEY) } returns ORG_ID
        every { traceService.parseOtlpTracesJson(any()) } returns listOf(
            traceSpan(serviceNamespace = "checkout", service = "api", env = "production"),
            traceSpan(serviceNamespace = "", service = "", env = "")
        )
        every {
            routingService.resolveProjectIds(
                ORG_ID,
                any<List<OtlpServiceDescriptor>>(),
                OtlpSignalType.TRACES
            )
        } returns mapOf(checkoutIdentity to 42L)
        every { routingService.normalizeIdentity("checkout", "api") } returns checkoutIdentity
        every { routingService.normalizeIdentity("", "") } returns null
        every { traceService.enqueueTraces(ORG_ID.toLong(), capture(routedSpans), any()) } returns 2
        installRoutes()()

        val response = client.post("/v1/traces") {
            header(HttpHeaders.Authorization, "Bearer $VALID_KEY")
            contentType(ContentType.Application.Json)
            setBody("""{"resourceSpans":[{}]}""")
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertTrue(response.bodyAsText().contains("2"))
        assertEquals(42L, routedSpans.captured[0].projectId)
        assertEquals(null, routedSpans.captured[1].projectId)
        verify {
            routingService.resolveProjectIds(
                ORG_ID,
                match<List<OtlpServiceDescriptor>> {
                    it.first().serviceNamespace == "checkout" &&
                        it.first().serviceName == "api" &&
                        it.first().environment == "production"
                },
                OtlpSignalType.TRACES
            )
        }
    }

    @Test
    fun `POST traces returns 401 with empty bearer token`() = testApplication {
        installRoutes()()
        val response = client.post("/v1/traces") {
            header(HttpHeaders.Authorization, "Bearer ")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST traces returns 401 with non-bearer auth`() = testApplication {
        installRoutes()()
        val response = client.post("/v1/traces") {
            header(HttpHeaders.Authorization, "Basic dGVzdDp0ZXN0")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ──── Metrics Routes ────

    @Test
    fun `POST metrics returns 401 without bearer token`() = testApplication {
        installRoutes()()
        val response = client.post("/v1/metrics") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST metrics returns 415 for unsupported content type`() = testApplication {
        every { otlpApiKeyService.validateKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/v1/metrics") {
            header(HttpHeaders.Authorization, "Bearer $VALID_KEY")
            header(HttpHeaders.ContentType, "text/plain")
            setBody("data")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `POST metrics otlp alias returns 401 without bearer token`() = testApplication {
        installRoutes()()
        val response = client.post("/v1/metrics/otlp") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST metrics returns 401 with invalid bearer token`() = testApplication {
        every { otlpApiKeyService.validateKey("bad") } returns null
        installRoutes()()
        val response = client.post("/v1/metrics") {
            header(HttpHeaders.Authorization, "Bearer bad")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST metrics otlp alias returns 401 with invalid token`() = testApplication {
        every { otlpApiKeyService.validateKey("wrong") } returns null
        installRoutes()()
        val response = client.post("/v1/metrics/otlp") {
            header(HttpHeaders.Authorization, "Bearer wrong")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST metrics otlp alias returns 415 for unsupported content type`() = testApplication {
        every { otlpApiKeyService.validateKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/v1/metrics/otlp") {
            header(HttpHeaders.Authorization, "Bearer $VALID_KEY")
            header(HttpHeaders.ContentType, "text/plain")
            setBody("data")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `POST metrics accepts valid json with empty resourceMetrics`() = testApplication {
        every { otlpApiKeyService.validateKey(VALID_KEY) } returns ORG_ID
        every { metricsService.parseOtlpMetricsJson(any()) } returns emptyList()
        installRoutes()()
        val response = client.post("/v1/metrics") {
            header(HttpHeaders.Authorization, "Bearer $VALID_KEY")
            contentType(ContentType.Application.Json)
            setBody("""{"resourceMetrics":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertTrue(response.bodyAsText().contains("0"))
    }

    @Test
    fun `POST metrics routes metrics by service mapping before enqueue`() = testApplication {
        val checkoutIdentity = OtlpServiceIdentity("checkout", "api")
        val routedMetrics = slot<List<OtlpMetricInsert>>()
        every { otlpApiKeyService.validateKey(VALID_KEY) } returns ORG_ID
        every { metricsService.parseOtlpMetricsJson(any()) } returns listOf(
            metric(serviceNamespace = "checkout", service = "api", env = "production"),
            metric(serviceNamespace = "", service = "", env = "")
        )
        every {
            routingService.resolveProjectIds(
                ORG_ID,
                any<List<OtlpServiceDescriptor>>(),
                OtlpSignalType.METRICS
            )
        } returns mapOf(checkoutIdentity to 43L)
        every { routingService.normalizeIdentity("checkout", "api") } returns checkoutIdentity
        every { routingService.normalizeIdentity("", "") } returns null
        every { metricsService.enqueueMetrics(ORG_ID.toLong(), capture(routedMetrics), any()) } returns 2
        installRoutes()()

        val response = client.post("/v1/metrics") {
            header(HttpHeaders.Authorization, "Bearer $VALID_KEY")
            contentType(ContentType.Application.Json)
            setBody("""{"resourceMetrics":[{}]}""")
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertTrue(response.bodyAsText().contains("2"))
        assertEquals(43L, routedMetrics.captured[0].projectId)
        assertEquals(null, routedMetrics.captured[1].projectId)
        verify {
            routingService.resolveProjectIds(
                ORG_ID,
                match<List<OtlpServiceDescriptor>> {
                    it.first().serviceNamespace == "checkout" &&
                        it.first().serviceName == "api" &&
                        it.first().environment == "production"
                },
                OtlpSignalType.METRICS
            )
        }
    }

    @Test
    fun `POST metrics returns 400 for null parsed result`() = testApplication {
        every { otlpApiKeyService.validateKey(VALID_KEY) } returns ORG_ID
        every { metricsService.parseOtlpMetricsJson(any()) } returns null
        installRoutes()()
        val response = client.post("/v1/metrics") {
            header(HttpHeaders.Authorization, "Bearer $VALID_KEY")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid OTLP metrics payload"))
    }

    @Test
    fun `POST metrics returns 401 with empty bearer token`() = testApplication {
        installRoutes()()
        val response = client.post("/v1/metrics") {
            header(HttpHeaders.Authorization, "Bearer ")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST metrics returns 401 with non-bearer auth`() = testApplication {
        installRoutes()()
        val response = client.post("/v1/metrics") {
            header(HttpHeaders.Authorization, "Basic dGVzdA==")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ──── Feedback Routes ────

    @Test
    fun `POST feedback otlp returns 401 without bearer token`() = testApplication {
        installRoutes()()
        val response = client.post("/v1/feedback/otlp") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST feedback otlp returns 415 for unsupported content type`() = testApplication {
        every { otlpApiKeyService.validateKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/v1/feedback/otlp") {
            header(HttpHeaders.Authorization, "Bearer $VALID_KEY")
            header(HttpHeaders.ContentType, "text/plain")
            setBody("feedback")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `POST feedback otlp routes feedback by service mapping before insert`() = testApplication {
        val checkoutIdentity = OtlpServiceIdentity("checkout", "api")
        val storedFeedback = slot<List<OtlpFeedbackInsert>>()
        every { otlpApiKeyService.validateKey(VALID_KEY) } returns ORG_ID
        every { feedbackService.parseOtlpFeedbackJson(any()) } returns listOf(
            feedback(serviceNamespace = "checkout", service = "api", environment = "production"),
            feedback(serviceNamespace = "", service = "", environment = "")
        )
        every {
            routingService.resolveProjectIds(
                ORG_ID,
                any<List<OtlpServiceDescriptor>>(),
                OtlpSignalType.FEEDBACK
            )
        } returns mapOf(checkoutIdentity to 44L)
        every { routingService.normalizeIdentity("checkout", "api") } returns checkoutIdentity
        every { routingService.normalizeIdentity("", "") } returns null
        coEvery { feedbackService.insertFeedback(ORG_ID, capture(storedFeedback)) } returns
            OtlpFeedbackIngestResult(accepted = 1, unmapped = 0)
        installRoutes()()

        val response = client.post("/v1/feedback/otlp") {
            header(HttpHeaders.Authorization, "Bearer $VALID_KEY")
            contentType(ContentType.Application.Json)
            setBody("""{"resourceLogs":[{}]}""")
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertTrue(response.bodyAsText().contains(""""accepted":1"""))
        assertTrue(response.bodyAsText().contains(""""unmapped":1"""))
        assertEquals(44L, storedFeedback.captured.single().projectId)
        verify {
            routingService.resolveProjectIds(
                ORG_ID,
                match<List<OtlpServiceDescriptor>> {
                    it.first().serviceNamespace == "checkout" &&
                        it.first().serviceName == "api" &&
                        it.first().environment == "production"
                },
                OtlpSignalType.FEEDBACK
            )
        }
    }

    @Test
    fun `POST feedback otlp reserves feedback quota for mapped records`() = testApplication {
        every { otlpApiKeyService.validateKey(VALID_KEY) } returns ORG_ID
        every { feedbackService.parseOtlpFeedbackJson(any()) } returns listOf(
            feedback(serviceNamespace = "checkout", service = "api", environment = "production")
        )
        every {
            routingService.resolveProjectIds(
                ORG_ID,
                any<List<OtlpServiceDescriptor>>(),
                OtlpSignalType.FEEDBACK
            )
        } returns mapOf(OtlpServiceIdentity("checkout", "api") to 44L)
        every { routingService.normalizeIdentity("checkout", "api") } returns OtlpServiceIdentity("checkout", "api")
        every { quotaService.isEnforcementEnabled() } returns true
        every { quotaService.reserveUnits(ORG_ID, 1, "feedback", any()) } returns
            QuotaReservationResult(allowed = true, usage = billingUsage())
        coEvery { feedbackService.insertFeedback(ORG_ID, any()) } returns
            OtlpFeedbackIngestResult(accepted = 1, unmapped = 0)
        installRoutes()()

        val response = client.post("/v1/feedback/otlp") {
            header(HttpHeaders.Authorization, "Bearer $VALID_KEY")
            contentType(ContentType.Application.Json)
            setBody("""{"resourceLogs":[{}]}""")
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        verify { quotaService.reserveUnits(ORG_ID, 1, "feedback", any()) }
    }

    @Test
    fun `POST feedback otlp returns 429 when feedback quota is exceeded`() = testApplication {
        every { otlpApiKeyService.validateKey(VALID_KEY) } returns ORG_ID
        every { feedbackService.parseOtlpFeedbackJson(any()) } returns listOf(
            feedback(serviceNamespace = "checkout", service = "api", environment = "production")
        )
        every {
            routingService.resolveProjectIds(
                ORG_ID,
                any<List<OtlpServiceDescriptor>>(),
                OtlpSignalType.FEEDBACK
            )
        } returns mapOf(OtlpServiceIdentity("checkout", "api") to 44L)
        every { routingService.normalizeIdentity("checkout", "api") } returns OtlpServiceIdentity("checkout", "api")
        every { quotaService.isEnforcementEnabled() } returns true
        every { quotaService.reserveUnits(ORG_ID, 1, "feedback", any()) } returns
            QuotaReservationResult(allowed = false, reason = "feedback limit reached", usage = billingUsage())
        installRoutes()()

        val response = client.post("/v1/feedback/otlp") {
            header(HttpHeaders.Authorization, "Bearer $VALID_KEY")
            contentType(ContentType.Application.Json)
            setBody("""{"resourceLogs":[{}]}""")
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
    }

    private fun traceSpan(
        serviceNamespace: String,
        service: String,
        env: String,
    ): OtlpSpanInsert =
        OtlpSpanInsert(
            traceIdHex = "00000000000000000000000000000001",
            spanIdHex = "0000000000000001",
            parentIdHex = "",
            organizationId = 0,
            name = "GET /checkout",
            serviceNamespace = serviceNamespace,
            service = service,
            resource = "GET /checkout",
            kind = "server",
            startNanos = 1_700_000_000_000_000_000L,
            durationNanos = 1_000_000,
            error = 0,
            statusCode = 1,
            statusMessage = "",
            meta = emptyMap(),
            resourceAttributes = emptyMap(),
            host = "api-host",
            env = env,
            version = "1.0.0",
            scopeName = "test",
            scopeVersion = "1.0.0",
            events = "[]",
            links = "[]"
        )

    private fun metric(
        serviceNamespace: String,
        service: String,
        env: String,
    ): OtlpMetricInsert =
        OtlpMetricInsert(
            organizationId = 0,
            metricName = "checkout.requests",
            metricType = "sum",
            description = "",
            unit = "1",
            timestampMs = 1_700_000_000_000L,
            value = 1.0,
            isMonotonic = 1,
            aggregationTemporality = "delta",
            histCount = 0,
            histSum = null,
            histMin = null,
            histMax = null,
            histBucketCounts = emptyList(),
            histExplicitBounds = emptyList(),
            tags = emptyMap(),
            resourceAttributes = emptyMap(),
            serviceNamespace = serviceNamespace,
            service = service,
            env = env,
            host = "api-host"
        )

    private fun feedback(
        serviceNamespace: String,
        service: String,
        environment: String,
    ): OtlpFeedbackInsert =
        OtlpFeedbackInsert(
            feedbackId = "4f01ede1-5802-4ff1-81b7-f2fe9add31e5",
            projectId = null,
            timestampMs = 1_700_000_000_000L,
            message = "Checkout is confusing",
            contactEmail = "user@example.com",
            name = "User Example",
            url = "https://app.example.com/checkout",
            associatedEventId = "",
            replayId = "",
            environment = environment,
            release = "1.0.0",
            platform = "javascript",
            userId = "user-1",
            userEmail = "user@example.com",
            userUsername = "user",
            traceId = "00000000000000000000000000000001",
            spanId = "0000000000000001",
            sourceType = "otlp",
            sourceName = "OpenTelemetry",
            sourceEventName = "moneat.user_feedback",
            serviceNamespace = serviceNamespace,
            service = service,
            tags = emptyMap(),
            resourceAttributes = emptyMap()
        )

    private fun billingUsage(): BillingUsageResponse =
        BillingUsageResponse(
            organizationId = resourceId(ORG_ID),
            periodStart = "2026-06-01T00:00:00Z",
            periodEnd = "2026-07-01T00:00:00Z",
            retentionDays = 30,
            logRetentionDays = 30,
            replayRetentionDays = 30,
            llmRetentionDays = 30,
            apmTraceRetentionDays = 30,
            usedUnits = 1,
            usedErrors = 0,
            errorLimit = 1_000,
            usedTransactions = 0,
            transactionLimit = 1_000,
            usedReplays = 0,
            replayLimit = 1_000,
            usedFeedback = 1,
            feedbackLimit = 1_000,
            usedBytes = 100,
            bytesLimit = 1_000_000,
            baseLimitUnits = 1_000,
            paygLimitUnits = 0,
            totalLimitUnits = 1_000,
            paygBudgetCents = 0,
            paygUsedUnits = 0,
            paygUsedCentsEstimate = 0,
            plan = "free",
            status = "active",
            withinQuota = true
        )

    private fun resourceId(id: Int): String =
        "00000000-0000-0000-0000-${id.toString().padStart(12, '0')}"
}
