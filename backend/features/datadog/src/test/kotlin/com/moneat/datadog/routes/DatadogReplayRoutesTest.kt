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

import com.moneat.billing.models.BillingUsageResponse
import com.moneat.billing.services.QuotaReservationResult
import com.moneat.billing.services.BillingQuotaService
import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.datadog.services.DatadogReplayIngestRequest
import com.moneat.datadog.services.DatadogReplayIngestResult
import com.moneat.datadog.services.DatadogReplayIngestionService
import com.moneat.datadog.services.DatadogService
import io.ktor.client.request.forms.FormBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
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
import io.mockk.slot
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatadogReplayRoutesTest {

    companion object {
        private const val VALID_KEY = "dd-replay-test-key"
        private const val ORG_ID = 5
        private const val PROJECT_ID = 42
        private const val REPLAY_SEGMENT_JSON =
            """{"records":[{"type":4,"timestamp":1700000000000,"data":{"href":"https://example.com/cart"}}]}"""
        private val quotaExceededUsage = BillingUsageResponse(
            organizationId = "123",
            periodStart = "2026-01-01T00:00:00Z",
            periodEnd = "2026-02-01T00:00:00Z",
            retentionDays = 30,
            apmTraceRetentionDays = 30,
            usedUnits = 0,
            usedErrors = 0,
            errorLimit = 1000,
            usedTransactions = 0,
            transactionLimit = 1000,
            usedReplays = 0,
            replayLimit = 1000,
            usedFeedback = 0,
            feedbackLimit = 1000,
            usedBytes = 0,
            bytesLimit = 1024,
            baseLimitUnits = 1000,
            paygLimitUnits = 1000,
            totalLimitUnits = 2000,
            paygBudgetCents = 0,
            paygUsedUnits = 0,
            paygUsedCentsEstimate = 0,
            plan = "pro",
            status = "active",
            withinQuota = true,
        )

        @JvmStatic
        @BeforeAll
        fun installObjectMocks() {
            mockkObject(DatadogService, DatadogReplayIngestionService)
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
        DatadogAuthMiddleware.clearCache()
        clearMocks(DatadogService, DatadogReplayIngestionService, quotaService)
        every { quotaService.isEnforcementEnabled() } returns false
        every { DatadogService.validateApiKeyContext(VALID_KEY) } returns
            DatadogService.ApiKeyValidation(ORG_ID, PROJECT_ID)
    }

    @AfterTest
    fun teardown() {
        DatadogAuthMiddleware.clearCache()
    }

    @Test
    fun `post replay accepts browser sdk multipart payload with query api key`() = testApplication {
        val requestSlot = slot<DatadogReplayIngestRequest>()
        coEvery { DatadogReplayIngestionService.ingestReplaySegment(capture(requestSlot)) } returns
            DatadogReplayIngestResult(
                replayId = "11111111-2222-3333-4444-555555555555",
                segmentId = 3,
                recordCount = 2,
                bytesStored = 128,
            )
        installRoutes()

        val response = client.post(
            "/dd/api/v2/replay?dd-api-key=$VALID_KEY&dd-evp-encoding=deflate" +
                "&ddtags=env:prod,version:1.2.3&dd-evp-origin-version=6.33.0"
        ) {
            setBody(replayMultipartBody(deflate(REPLAY_SEGMENT_JSON.toByteArray())))
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertTrue(response.bodyAsText().contains("11111111-2222-3333-4444-555555555555"))
        val captured = requestSlot.captured
        assertEquals(ORG_ID, captured.organizationId)
        assertEquals(PROJECT_ID.toLong(), captured.projectId)
        assertEquals("deflate", captured.declaredEncoding)
        assertEquals("prod", captured.tags["env"])
        assertEquals("1.2.3", captured.tags["version"])
        assertEquals("6.33.0", captured.tags["sdk_version"])

        coVerify(exactly = 1) { DatadogReplayIngestionService.ingestReplaySegment(any()) }
    }

    @Test
    fun `post replay rejects org-wide Datadog API keys`() = testApplication {
        every { DatadogService.validateApiKeyContext(VALID_KEY) } returns
            DatadogService.ApiKeyValidation(ORG_ID, null)
        installRoutes()

        val response = client.post("/dd/api/v2/replay?dd-api-key=$VALID_KEY") {
            setBody(replayMultipartBody(REPLAY_SEGMENT_JSON.toByteArray()))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        coVerify(exactly = 0) { DatadogReplayIngestionService.ingestReplaySegment(any()) }
    }

    @Test
    fun `post replay rejects non-multipart bodies`() = testApplication {
        installRoutes()

        val response = client.post("/api/v2/replay?dd-api-key=$VALID_KEY") {
            contentType(ContentType.Application.Json)
            setBody("""{"records":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        coVerify(exactly = 0) { DatadogReplayIngestionService.ingestReplaySegment(any()) }
    }

    @Test
    fun `post replay rejects invalid replay event JSON`() = testApplication {
        installRoutes()

        val response = client.post("/dd/api/v2/replay?dd-api-key=$VALID_KEY") {
            setBody(
                replayEventOnlyBody("{not json".toByteArray()),
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid replay event JSON"))
        coVerify(exactly = 0) { DatadogReplayIngestionService.ingestReplaySegment(any()) }
    }

    @Test
    fun `post replay rejects malformed replay payload lacking segment`() = testApplication {
        val eventBytes = replayEventJson().toByteArray()
        coEvery { DatadogReplayIngestionService.ingestReplaySegment(any()) } returns
            DatadogReplayIngestResult(
                replayId = "11111111-2222-3333-4444-555555555555",
                segmentId = 3,
                recordCount = 2,
                bytesStored = 128,
            )
        installRoutes()

        val response = client.post("/dd/api/v2/replay?dd-api-key=$VALID_KEY") {
            setBody(replayEventOnlyBody(eventBytes))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Replay event and segment parts are required"))
        coVerify(exactly = 0) { DatadogReplayIngestionService.ingestReplaySegment(any()) }
    }

    @Test
    fun `post replay rejects multipart payload missing event`() = testApplication {
        installRoutes()

        val response = client.post("/dd/api/v2/replay?dd-api-key=$VALID_KEY") {
            setBody(replaySegmentOnlyBody(deflate(REPLAY_SEGMENT_JSON.toByteArray())))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Replay event and segment parts are required"))
        coVerify(exactly = 0) { DatadogReplayIngestionService.ingestReplaySegment(any()) }
    }

    @Test
    fun `post replay accepts event form item without filename`() = testApplication {
        val requestSlot = slot<DatadogReplayIngestRequest>()
        coEvery { DatadogReplayIngestionService.ingestReplaySegment(capture(requestSlot)) } returns
            DatadogReplayIngestResult(
                replayId = "11111111-2222-3333-4444-555555555555",
                segmentId = 3,
                recordCount = 2,
                bytesStored = 128,
            )
        installRoutes()

        val response = client.post(
            "/dd/api/v2/replay?dd-api-key=$VALID_KEY&dd-evp-encoding=deflate"
        ) {
            setBody(replayFormFieldMultipartBody(deflate(REPLAY_SEGMENT_JSON.toByteArray())))
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals(3, requestSlot.captured.event.indexInView)
        assertEquals("11111111-2222-3333-4444-555555555555", requestSlot.captured.event.session?.id)
        coVerify(exactly = 1) { DatadogReplayIngestionService.ingestReplaySegment(any()) }
    }

    @Test
    fun `post replay returns 429 when quota is exceeded`() = testApplication {
        coEvery { quotaService.reserveUnits(any(), any(), any(), any()) } returns
            QuotaReservationResult(
                allowed = false,
                reason = "replay quota exceeded",
                usage = quotaExceededUsage,
            )
        every { quotaService.isEnforcementEnabled() } returns true
        installRoutes()

        val response = client.post("/dd/api/v2/replay?dd-api-key=$VALID_KEY") {
            setBody(replayMultipartBody(deflate(REPLAY_SEGMENT_JSON.toByteArray())))
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertFalse(response.bodyAsText().contains("replay_id"))
        coVerify(exactly = 0) { DatadogReplayIngestionService.ingestReplaySegment(any()) }
    }

    @Test
    fun `post replay returns 400 when replay ingestion request is invalid`() = testApplication {
        coEvery { DatadogReplayIngestionService.ingestReplaySegment(any()) } throws
            IllegalArgumentException("Invalid replay payload")
        installRoutes()

        val response = client.post("/dd/api/v2/replay?dd-api-key=$VALID_KEY") {
            setBody(replayMultipartBody(deflate(REPLAY_SEGMENT_JSON.toByteArray())))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid replay payload"))
        coVerify(exactly = 1) { DatadogReplayIngestionService.ingestReplaySegment(any()) }
    }

    @Test
    fun `post replay returns 500 when replay ingestion fails for non-validation reason`() = testApplication {
        coEvery { DatadogReplayIngestionService.ingestReplaySegment(any()) } throws
            IllegalStateException("backend failure")
        installRoutes()

        val response = client.post("/dd/api/v2/replay?dd-api-key=$VALID_KEY") {
            setBody(replayMultipartBody(deflate(REPLAY_SEGMENT_JSON.toByteArray())))
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertTrue(response.bodyAsText().contains("Failed to ingest replay payload"))
        coVerify(exactly = 1) { DatadogReplayIngestionService.ingestReplaySegment(any()) }
    }

    @Test
    fun `parseDdTags parses browser SDK ddtags query`() {
        val tags = parseDdTags("env:prod,version:1.2.3,url:http://localhost:8080")

        assertEquals("prod", tags["env"])
        assertEquals("1.2.3", tags["version"])
        assertEquals("http://localhost:8080", tags["url"])
    }

    @Test
    fun `parseDdTags ignores malformed entries`() {
        val tags = parseDdTags("env:prod,malformed,service:backend,:,url:http://localhost:8080")

        assertEquals(
            mapOf(
                "env" to "prod",
                "service" to "backend",
                "url" to "http://localhost:8080",
            ),
            tags,
        )
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.installRoutes() {
        application {
            install(ContentNegotiation) { json() }
            routing {
                datadogReplayRoutes(quotaService)
            }
        }
    }

    private fun replayMultipartBody(segmentBytes: ByteArray): MultiPartFormDataContent =
        MultiPartFormDataContent(
            formData {
                appendFile(
                    name = "event",
                    fileName = "event.json",
                    bytes = replayEventJson().toByteArray(),
                    contentType = ContentType.Application.Json,
                )
                appendFile("segment", "segment.bin", segmentBytes)
            }
        )

    private fun replayMultipartBody(
        eventJson: String,
        segmentBytes: ByteArray,
    ): MultiPartFormDataContent =
        MultiPartFormDataContent(
            formData {
                appendFile(
                    name = "event",
                    fileName = "event.json",
                    bytes = eventJson.toByteArray(),
                    contentType = ContentType.Application.Json,
                )
                appendFile("segment", "segment.bin", segmentBytes)
            }
        )

    private fun replayFormFieldMultipartBody(segmentBytes: ByteArray): MultiPartFormDataContent =
        MultiPartFormDataContent(
            formData {
                append(
                    "event",
                    replayEventJson(),
                    Headers.build {
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                )
                appendFile("segment", "segment.bin", segmentBytes)
            }
        )

    private fun replayEventOnlyBody(eventBytes: ByteArray): MultiPartFormDataContent =
        MultiPartFormDataContent(
            formData {
                append(
                    "event",
                    eventBytes.toString(Charsets.UTF_8),
                    Headers.build {
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        append(
                            HttpHeaders.ContentDisposition,
                            "form-data; name=\"event\"; filename=\"event.json\"",
                        )
                    },
                )
            }
        )

    private fun replaySegmentOnlyBody(segmentBytes: ByteArray): MultiPartFormDataContent =
        MultiPartFormDataContent(
            formData {
                appendFile("segment", "segment.bin", segmentBytes)
            }
        )

    private fun FormBuilder.appendFile(
        name: String,
        fileName: String,
        bytes: ByteArray,
        contentType: ContentType = ContentType.Application.OctetStream,
    ) {
        append(
            name,
            bytes,
            Headers.build {
                append(HttpHeaders.ContentDisposition, "form-data; name=\"$name\"; filename=\"$fileName\"")
                append(HttpHeaders.ContentType, contentType.toString())
            }
        )
    }

    private fun replayEventJson(): String =
        """
        {
          "source":"browser",
          "creation_reason":"segment_duration_limit",
          "start":1700000000000,
          "end":1700000005000,
          "records_count":2,
          "index_in_view":3,
          "application":{"id":"application-id"},
          "session":{"id":"11111111-2222-3333-4444-555555555555"},
          "view":{"id":"view-id"}
        }
        """.trimIndent()

    private fun deflate(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output).use { it.write(data) }
        return output.toByteArray()
    }
}
