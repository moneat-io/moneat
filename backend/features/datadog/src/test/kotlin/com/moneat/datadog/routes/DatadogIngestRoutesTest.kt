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

package com.moneat.datadog.routes

import com.google.protobuf.CodedOutputStream
import com.moneat.billing.models.BillingUsageResponse
import com.moneat.billing.services.BillingQuotaService
import com.moneat.billing.services.QuotaReservationResult
import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.datadog.services.DatadogEventService
import com.moneat.datadog.services.DatadogHostService
import com.moneat.datadog.services.DatadogLogService
import com.moneat.datadog.services.DatadogMetricService
import com.moneat.datadog.services.DatadogService
import com.moneat.datadog.services.DbmIngestionService
import com.moneat.datadog.services.DebuggerIngestionService
import com.moneat.datadog.services.MiscIngestionService
import com.moneat.datadog.services.OrchestratorIngestionService
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.get
import io.ktor.client.request.head
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
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatadogIngestRoutesTest {

    companion object {
        private const val DD_API_KEY_HEADER = "DD-API-KEY"
        private const val VALID_KEY = "dd-ingest-test-key"
        private const val ORG_ID = 5
        private const val ORG_RESOURCE_ID = "00000000-0000-0000-0000-000000000005"

        @JvmStatic
        @BeforeAll
        fun installObjectMocks() {
            mockkObject(
                DatadogService,
                DatadogMetricService,
                DatadogLogService,
                DatadogEventService,
                DatadogHostService,
                MiscIngestionService,
                DbmIngestionService,
                DebuggerIngestionService,
                OrchestratorIngestionService,
            )
        }

        @JvmStatic
        @AfterAll
        fun removeObjectMocks() {
            unmockkAll()
        }
    }

    private val quotaService = mockk<BillingQuotaService> {
        every { isEnforcementEnabled() } returns false
    }

    @BeforeTest
    fun setup() {
        startTestKoin()
        DatadogAuthMiddleware.clearCache()
        clearMocks(
            DatadogService,
            DatadogMetricService,
            DatadogLogService,
            DatadogEventService,
            DatadogHostService,
            MiscIngestionService,
            DbmIngestionService,
            DebuggerIngestionService,
            OrchestratorIngestionService,
            quotaService,
        )

        every { quotaService.isEnforcementEnabled() } returns false
        every { DatadogService.validateApiKeyContext(any()) } answers {
            if (firstArg<String>() == VALID_KEY) {
                DatadogService.ApiKeyValidation(ORG_ID, null)
            } else {
                null
            }
        }
        coEvery { DatadogMetricService.enqueueMetrics(any(), any(), any()) } returns 0
        coEvery { DatadogMetricService.enqueueSketches(any(), any(), any()) } returns 0
        coEvery { DatadogLogService.enqueueLogs(any(), any()) } returns 0
        coEvery { DatadogEventService.enqueueEvents(any(), any()) } returns 0
        coEvery { DatadogEventService.enqueueServiceChecks(any(), any()) } returns 0
        every { DatadogHostService.touchHostLastSeen(any(), any()) } returns Unit
        every { MiscIngestionService.enqueueSymbolDb(any(), any()) } returns Unit
        every { MiscIngestionService.enqueuePipelineStats(any(), any()) } returns 0
        every { MiscIngestionService.enqueueDataLineage(any(), any()) } returns Unit
        every { MiscIngestionService.enqueueDataStreams(any(), any()) } returns 0
        every { MiscIngestionService.enqueueSynthetics(any(), any()) } returns 0
        every { MiscIngestionService.enqueueContainerImage(any(), any()) } returns Unit
        every { MiscIngestionService.enqueueContainerImages(any(), any()) } returns 0
        every { MiscIngestionService.enqueueSbom(any(), any()) } returns 0
        every { DbmIngestionService.enqueueQueries(any(), any()) } returns 0
        every { DbmIngestionService.enqueueQueryPayloads(any(), any()) } returns 0
        every { DbmIngestionService.enqueueMetrics(any(), any()) } returns 0
        every { DbmIngestionService.enqueueMetricPayloads(any(), any()) } returns 0
        every { DbmIngestionService.enqueueActivity(any(), any()) } returns 0
        every { DbmIngestionService.enqueueActivityPayloads(any(), any()) } returns 0
        every { DbmIngestionService.enqueueMetadata(any(), any()) } returns 0
        every { DbmIngestionService.enqueueMetadataPayloads(any(), any()) } returns 0
        every { DbmIngestionService.enqueueHealth(any(), any()) } returns 0
        every { DbmIngestionService.enqueueHealthPayloads(any(), any()) } returns 0
        every { DebuggerIngestionService.enqueueDebuggerLogs(any(), any()) } returns 0
        every { DebuggerIngestionService.enqueueDiagnostics(any(), any()) } returns 0
        every { OrchestratorIngestionService.enqueueResources(any(), any()) } returns 0
        every { OrchestratorIngestionService.enqueueManifests(any(), any()) } returns 0
    }

    @AfterTest
    fun teardown() {
        DatadogAuthMiddleware.clearCache()
        stopTestKoin()
    }

    private fun installRoutes(): io.ktor.server.testing.ApplicationTestBuilder.() -> Unit = {
        application {
            install(ContentNegotiation) { json() }
            routing {
                datadogMetricRoutes(quotaService)
                datadogLogRoutes(quotaService)
                datadogEventRoutes(quotaService)
                datadogValidateRoutes()
                miscIngestRoutes(quotaService)
                dbmIngestRoutes(quotaService)
                debuggerIngestRoutes(quotaService)
                orchestratorIngestRoutes(quotaService)
            }
        }
    }

    private fun buildProto(block: CodedOutputStream.() -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        val coded = CodedOutputStream.newInstance(output)
        coded.block()
        coded.flush()
        return output.toByteArray()
    }

    private fun deniedQuotaResult(eventType: String) = QuotaReservationResult(
        allowed = false,
        reason = "event_type_quota_exceeded",
        eventType = eventType,
        usage = quotaUsageResponse(),
    )

    private fun quotaUsageResponse() = BillingUsageResponse(
        organizationId = ORG_RESOURCE_ID,
        periodStart = "2026-06-01",
        periodEnd = "2026-06-30",
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
        plan = "FREE",
        status = "active",
        withinQuota = false,
    )

    // ──── V1 Metric Series ────

    @Test
    fun `v1 series returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v1/series") {
            contentType(ContentType.Application.Json)
            setBody("""{"series":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("API key"))
    }

    @Test
    fun `v1 series returns 403 when api key is invalid`() = testApplication {
        every { DatadogService.validateApiKey("bad-key") } returns null
        installRoutes()()
        val response = client.post("/dd/api/v1/series") {
            header(DD_API_KEY_HEADER, "bad-key")
            contentType(ContentType.Application.Json)
            setBody("""{"series":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v1 series returns 400 for malformed json`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v1/series") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{not valid json")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `v1 series accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v1/series") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"series":[{"metric":"cpu","points":[[1,0.5]],"host":"h1"}]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    // ──── V2 Metric Series ────

    @Test
    fun `v2 series returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/series") {
            contentType(ContentType.Application.Json)
            setBody("""{"series":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v2 series returns 403 with invalid api key`() = testApplication {
        every { DatadogService.validateApiKey("wrong") } returns null
        installRoutes()()
        val response = client.post("/dd/api/v2/series") {
            header(DD_API_KEY_HEADER, "wrong")
            contentType(ContentType.Application.Json)
            setBody("""{"series":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v2 series accepts valid json payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/series") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"series":[{"metric":"mem","points":[[1,100]],"host":""}]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `v2 series returns 400 for malformed json`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/series") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{broken")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ──── V3 Metric Series ────

    @Test
    fun `v3 series returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v3/series") {
            contentType(ContentType.Application.Json)
            setBody("""{"series":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v3 series accepts valid json payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v3/series") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"series":[{"metric":"disk","points":[[1,42]],"host":"h1"}]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `v3 series returns 400 for malformed json`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v3/series") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{bad")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ──── Sketches ────

    @Test
    fun `v1 sketches returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v1/sketches") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `beta sketches returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/beta/sketches") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v3 sketches returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v3/sketches") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v1 sketches accepts empty body with valid key`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v1/sketches") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `v1 sketches accepts valid json payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v1/sketches") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"sketches":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    // ──── Logs ────

    @Test
    fun `v2 logs returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/logs") {
            contentType(ContentType.Application.Json)
            setBody("""[{"message":"hello"}]""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v2 logs returns 403 with invalid api key`() = testApplication {
        every { DatadogService.validateApiKey("nope") } returns null
        installRoutes()()
        val response = client.post("/dd/api/v2/logs") {
            header(DD_API_KEY_HEADER, "nope")
            contentType(ContentType.Application.Json)
            setBody("""[{"message":"hello"}]""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v2 logs returns 400 for malformed json`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/logs") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{broken")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `v2 logs accepts empty array`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/logs") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("[]")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `v2 logs accepts single log object`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/logs") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"message":"test","ddsource":"app","ddtags":"env:test","hostname":"h1"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `v2 logs accepts array of multiple log objects`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val body = """[{"message":"a","ddsource":"s","ddtags":"","hostname":"h"},""" +
            """{"message":"b","ddsource":"s","ddtags":"","hostname":"h"}]"""
        val response = client.post("/dd/api/v2/logs") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `v2 logs accepts newline-delimited browser log batches`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val body = """
            {"message":"first console error","ddsource":"browser","ddtags":"env:production","hostname":"moneat.io"}
            {"message":"second console error","ddsource":"browser","ddtags":"env:production","hostname":"moneat.io"}
        """.trimIndent()
        val response = client.post("/dd/api/v2/logs") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Text.Plain)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        coVerify {
            DatadogLogService.enqueueLogs(
                ORG_ID.toLong(),
                match {
                    it.size == 2 &&
                        it[0].message == "first console error" &&
                        it[1].message == "second console error"
                },
            )
        }
    }

    @Test
    fun `v2 logs skips malformed lines in newline-delimited browser batches`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val body = """
            {"message":"first console error","ddsource":"browser","ddtags":"env:production","hostname":"moneat.io"}
            {not json}
            {"message":"second console error","ddsource":"browser","ddtags":"env:production","hostname":"moneat.io"}
        """.trimIndent()
        val response = client.post("/dd/api/v2/logs") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Text.Plain)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        coVerify {
            DatadogLogService.enqueueLogs(
                ORG_ID.toLong(),
                match {
                    it.size == 2 &&
                        it[0].message == "first console error" &&
                        it[1].message == "second console error"
                },
            )
        }
    }

    // ──── Events ────

    @Test
    fun `v2 events returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/events") {
            contentType(ContentType.Application.Json)
            setBody("""{"events":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v2 events returns 400 for malformed json`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/events") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{corrupt")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `v2 events accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/events") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"events":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    // ──── Service Checks ────

    @Test
    fun `v1 check_run returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v1/check_run") {
            contentType(ContentType.Application.Json)
            setBody("[]")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v1 check_run accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v1/check_run") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("""[{"check":"test","host_name":"h1","status":0}]""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `v1 check_run returns 400 for malformed json`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v1/check_run") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{bad")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `v2 service_checks returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/service_checks") {
            contentType(ContentType.Application.Json)
            setBody("""{"service_checks":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v2 service_checks accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/service_checks") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"service_checks":[{"check":"sc","host_name":"h","status":0}]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `v2 service_checks returns 400 for malformed json`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/service_checks") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{bad")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ──── Validate ────

    @Test
    fun `v1 validate GET returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.get("/dd/api/v1/validate")
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v1 validate returns valid with valid api key`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.get("/dd/api/v1/validate") {
            header(DD_API_KEY_HEADER, VALID_KEY)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().filterNot(Char::isWhitespace).contains(""""valid":true"""))
    }

    @Test
    fun `unprefixed v1 validate returns valid with valid api key`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.get("/api/v1/validate") {
            header(DD_API_KEY_HEADER, VALID_KEY)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().filterNot(Char::isWhitespace).contains(""""valid":true"""))
    }

    @Test
    fun `v1 validate accepts trailing slash`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.get("/dd/api/v1/validate/") {
            header(DD_API_KEY_HEADER, VALID_KEY)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().filterNot(Char::isWhitespace).contains(""""valid":true"""))
    }

    @Test
    fun `v1 validate accepts HEAD`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.head("/dd/api/v1/validate") {
            header(DD_API_KEY_HEADER, VALID_KEY)
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    // ──── Misc Ingest – /dd prefix ────

    @Test
    fun `symdb input returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/symdb/v1/input") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `symdb input accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/symdb/v1/input") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `pipeline stats returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/v0.1/pipeline_stats") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `pipeline stats accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/v0.1/pipeline_stats") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `data lineage returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v1/lineage") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `data lineage accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v1/lineage") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `dd data streams returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/data_streams") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `dd data streams accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/data_streams") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `dd synthetics returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/synthetics") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `dd synthetics accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/synthetics") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `dd contimage returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/contimage") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `dd contimage accepts valid json payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/contimage") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `dd contimage returns 429 and skips enqueue when misc quota is exceeded`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        every { quotaService.isEnforcementEnabled() } returns true
        every {
            quotaService.reserveUnits(
                organizationId = ORG_ID,
                requestedUnits = 1,
                eventType = "dd_misc",
                requestedBytes = any(),
            )
        } returns deniedQuotaResult("dd_misc")
        installRoutes()()

        val response = client.post("/dd/api/v2/contimage") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        verify(exactly = 0) {
            MiscIngestionService.enqueueContainerImage(any(), any())
        }
    }

    @Test
    fun `dd contimage protobuf decodes and enqueues images`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        every { MiscIngestionService.enqueueContainerImages(any(), any()) } returns 1
        installRoutes()()
        val os = buildProto {
            writeString(1, "linux")
            writeString(3, "arm64")
        }
        val image = buildProto {
            writeString(2, "registry.example.com/team/worker")
            writeString(3, "registry.example.com")
            writeString(5, "registry.example.com/team/worker:v2")
            writeString(6, "sha256:worker")
            writeInt64(7, 2048L)
            writeByteArray(9, os)
            writeString(12, "env:test")
        }
        val payload = buildProto {
            writeString(2, "host-a")
            writeByteArray(3, image)
        }

        val response = client.post("/dd/api/v2/contimage") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            header(HttpHeaders.ContentType, "application/x-protobuf")
            setBody(payload)
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        verify {
            MiscIngestionService.enqueueContainerImages(
                ORG_ID,
                match {
                    it.single().imageName == "registry.example.com/team/worker" &&
                        it.single().imageTag == "v2" &&
                        it.single().architecture == "arm64"
                },
            )
        }
    }

    @Test
    fun `dd sbom returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/sbom") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `dd sbom accepts valid json payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/sbom") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `dd sbom protobuf decodes and enqueues packages`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        every { MiscIngestionService.enqueueSbom(any(), any()) } returns 1
        installRoutes()()
        val component = buildProto {
            writeEnum(1, 3)
            writeString(3, "pkg-ref")
            writeString(8, "openssl")
            writeString(9, "3.0.0")
            writeString(16, "pkg:deb/openssl@3.0.0")
        }
        val bom = buildProto {
            writeByteArray(5, component)
        }
        val entity = buildProto {
            writeEnum(1, 1)
            writeString(2, "sha256:image")
            writeString(4, "registry.example.com/team/api:v1")
            writeString(7, "service:api")
            writeByteArray(10, bom)
        }
        val payload = buildProto {
            writeString(2, "host-b")
            writeByteArray(4, entity)
        }

        val response = client.post("/dd/api/v2/sbom") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            header(HttpHeaders.ContentType, "application/x-protobuf")
            setBody(payload)
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        verify {
            MiscIngestionService.enqueueSbom(
                ORG_ID,
                match {
                    it.host == "host-b" &&
                        it.packages.single().name == "openssl" &&
                        it.tags.contains("service:api")
                },
            )
        }
    }

    // ──── Misc Ingest – non-/dd prefix ────

    @Test
    fun `contlcycle returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/api/v2/contlcycle") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `contlcycle returns accepted with valid api key`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/api/v2/contlcycle") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `contlcycle accepts whitespace empty json probes`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()

        listOf("{ }", "{\n}", "{\n\t}").forEach { payload ->
            val response = client.post("/api/v2/contlcycle") {
                header(DD_API_KEY_HEADER, VALID_KEY)
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            assertEquals(HttpStatusCode.Accepted, response.status)
        }

        coVerify(exactly = 3) {
            DatadogEventService.enqueueEvents(ORG_ID.toLong(), emptyList())
        }
    }

    @Test
    fun `contlcycle returns 400 for non empty json payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/api/v2/contlcycle") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"container_id":"container-1"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `contlcycle protobuf decodes and enqueues lifecycle events`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        coEvery { DatadogEventService.enqueueEvents(any(), any()) } returns 1
        installRoutes()()
        val container = buildProto {
            writeString(1, "container-1")
            writeString(2, "containerd")
            writeInt32(3, 137)
        }
        val event = buildProto {
            writeEnum(1, 0)
            writeByteArray(2, container)
        }
        val payload = buildProto {
            writeString(2, "host-c")
            writeEnum(3, 0)
            writeByteArray(4, event)
        }

        val response = client.post("/api/v2/contlcycle") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            header(HttpHeaders.ContentType, "application/x-protobuf")
            setBody(payload)
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        coVerify {
            DatadogEventService.enqueueEvents(
                ORG_ID.toLong(),
                match {
                    it.single().host == "host-c" &&
                        it.single().tags.contains("object_id:container-1") &&
                        it.single().tags.contains("exit_code:137")
                },
            )
        }
    }

    @Test
    fun `event management returns accepted with valid api key`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        coEvery { DatadogEventService.enqueueEvents(any(), any()) } returns 1
        installRoutes()()
        val response = client.post("/api/v2/events") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"events":[{"title":"deploy","host":"web-01","tags":["env:test"]}]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        coVerify {
            DatadogEventService.enqueueEvents(
                ORG_ID.toLong(),
                match {
                    it.single().title == "deploy" &&
                        it.single().host == "web-01" &&
                        it.single().tags.contains("env:test")
                },
            )
        }
    }

    @Test
    fun `event management returns 429 and skips enqueue when event quota is exceeded`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        every { quotaService.isEnforcementEnabled() } returns true
        every {
            quotaService.reserveUnits(
                organizationId = ORG_ID,
                requestedUnits = 1,
                eventType = "dd_event",
                requestedBytes = any(),
            )
        } returns deniedQuotaResult("dd_event")
        installRoutes()()

        val response = client.post("/api/v2/events") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"events":[{"title":"deploy","host":"web-01","tags":["env:test"]}]}""")
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        coVerify(exactly = 0) {
            DatadogEventService.enqueueEvents(any(), any())
        }
    }

    @Test
    fun `non-dd contimage returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/api/v2/contimage") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `non-dd sbom returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/api/v2/sbom") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `non-dd synthetics returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/api/v2/synthetics") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `non-dd data_streams_messages returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/api/v2/data_streams_messages") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `non-dd events returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/api/v2/events") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ──── DBM Ingest – /dd prefix ────

    @Test
    fun `dbm databasequery returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/databasequery") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `dbm databasequery accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/databasequery") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `dbm databasequery accepts top-level array payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/databasequery") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody(
                """
                [
                  {
                    "db_host": "pg-primary",
                    "db_system": "postgresql",
                    "rows": [
                      {
                        "query_signature": "sig-1",
                        "statement": "SELECT 1",
                        "timestamp": 1700000000
                      }
                    ]
                  }
                ]
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        verify {
            DbmIngestionService.enqueueQueryPayloads(
                ORG_ID,
                match { it.single().dbHost == "pg-primary" && it.single().rows.single().querySignature == "sig-1" },
            )
        }
    }

    @Test
    fun `dbm databasequery accepts empty array probe`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/databasequery") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("[{}]")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        verify { DbmIngestionService.enqueueQueryPayloads(ORG_ID, match { it.isEmpty() }) }
    }

    @Test
    fun `dbm dbmmetrics returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/dbmmetrics") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `dbm dbmmetrics accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/dbmmetrics") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `dbm dbmactivity returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/dbmactivity") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `dbm dbmactivity accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/dbmactivity") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `dbm dbmactivity accepts top-level array payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/dbmactivity") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody(
                """
                [
                  {
                    "db_host": "pg-primary",
                    "db_system": "postgresql",
                    "activity": [
                      {
                        "db_name": "postgres",
                        "query_signature": "sig-activity",
                        "statement": "SELECT pg_sleep(1)",
                        "state": "active",
                        "timestamp": 1700000000
                      }
                    ]
                  }
                ]
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        verify {
            DbmIngestionService.enqueueActivityPayloads(
                ORG_ID,
                match {
                    it.single().dbHost == "pg-primary" &&
                        it.single().activity.single().querySignature == "sig-activity"
                },
            )
        }
    }

    @Test
    fun `dbm metadata returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/dbmmetadata") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `dbm metadata accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/dbmmetadata") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `dbm health returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/dbmhealth") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `dbm health accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/dbmhealth") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    // ──── DBM Ingest – non-/dd prefix ────

    @Test
    fun `non-dd dbm databasequery returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/api/v2/databasequery") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `non-dd dbm dbmmetrics returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/api/v2/dbmmetrics") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `non-dd dbm dbmactivity returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/api/v2/dbmactivity") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `non-dd dbm dbmmetadata returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/api/v2/dbmmetadata") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `non-dd dbm dbmhealth returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/api/v2/dbmhealth") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ──── Debugger Ingest ────

    @Test
    fun `debugger v1 input returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/debugger/v1/input") {
            contentType(ContentType.Application.Json)
            setBody("[]")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `debugger v1 input accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/debugger/v1/input") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("[{}]")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `debugger v1 diagnostics returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/debugger/v1/diagnostics") {
            contentType(ContentType.Application.Json)
            setBody("[]")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `debugger v1 diagnostics accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/debugger/v1/diagnostics") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("[{}]")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `debugger v2 input returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/debugger/v2/input") {
            contentType(ContentType.Application.Json)
            setBody("[]")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `debugger v2 input accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/debugger/v2/input") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("[{}]")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    // ──── Orchestrator Ingest ────

    @Test
    fun `orchestrator orch returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/orch") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `orchestrator orch accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/orch") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `orchestrator orchmanif returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/orchmanif") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `orchestrator orchmanif accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/orchmanif") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    // ──── API key via query parameter ────

    @Test
    fun `v1 series accepts api key via query parameter`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v1/series?api_key=$VALID_KEY") {
            contentType(ContentType.Application.Json)
            setBody("""{"series":[{"metric":"cpu","points":[[1,0.5]],"host":""}]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `v2 logs accepts api key via query parameter`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/logs?api_key=$VALID_KEY") {
            contentType(ContentType.Application.Json)
            setBody("[]")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `v1 validate accepts api key via query parameter`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.get("/dd/api/v1/validate?api_key=$VALID_KEY")
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
