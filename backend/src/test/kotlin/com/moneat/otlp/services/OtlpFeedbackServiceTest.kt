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

package com.moneat.otlp.services

import com.google.protobuf.ByteString
import com.moneat.events.repositories.EventRepository
import com.moneat.events.repositories.models.FeedbackInsertData
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.logs.v1.LogRecord
import io.opentelemetry.proto.logs.v1.ResourceLogs
import io.opentelemetry.proto.logs.v1.ScopeLogs
import io.opentelemetry.proto.resource.v1.Resource
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OtlpFeedbackServiceTest {
    private val service = OtlpFeedbackService()

    @Test
    fun `parseOtlpFeedbackJson extracts moneat feedback events from log records`() {
        val payload =
            """
            {
              "resourceLogs": [{
                "resource": {"attributes": [
                  {"key": "service.namespace", "value": {"stringValue": "checkout"}},
                  {"key": "service.name", "value": {"stringValue": "api"}},
                  {"key": "deployment.environment.name", "value": {"stringValue": "production"}},
                  {"key": "service.version", "value": {"stringValue": "1.2.3"}},
                  {"key": "telemetry.sdk.language", "value": {"stringValue": "javascript"}}
                ]},
                "scopeLogs": [{
                  "scope": {"name": "feedback-widget", "version": "1.0.0"},
                  "logRecords": [{
                    "timeUnixNano": "1700000000000000000",
                    "traceId": "00000000000000000000000000000001",
                    "spanId": "0000000000000001",
                    "body": {"stringValue": "Checkout is confusing"},
                    "attributes": [
                      {"key": "event.name", "value": {"stringValue": "moneat.user_feedback"}},
                      {"key": "moneat.feedback.contact_email", "value": {"stringValue": "user@example.com"}},
                      {"key": "moneat.feedback.name", "value": {"stringValue": "User Example"}},
                      {"key": "url.full", "value": {"stringValue": "https://app.example.com/checkout"}},
                      {"key": "moneat.feedback.associated_event_id", "value": {"stringValue": "evt-1"}},
                      {"key": "moneat.feedback.replay_id", "value": {"stringValue": "replay-1"}},
                      {"key": "user.id", "value": {"stringValue": "user-1"}},
                      {"key": "feature", "value": {"stringValue": "checkout"}}
                    ]
                  }, {
                    "body": {"stringValue": "ordinary log"},
                    "attributes": [
                      {"key": "event.name", "value": {"stringValue": "ordinary.event"}}
                    ]
                  }]
                }]
              }]
            }
            """.trimIndent()

        val feedback = service.parseOtlpFeedbackJson(payload)

        assertEquals(1, feedback?.size)
        val row = feedback?.single()
        assertEquals("Checkout is confusing", row?.message)
        assertEquals("user@example.com", row?.contactEmail)
        assertEquals("User Example", row?.name)
        assertEquals("https://app.example.com/checkout", row?.url)
        assertEquals("evt-1", row?.associatedEventId)
        assertEquals("replay-1", row?.replayId)
        assertEquals("production", row?.environment)
        assertEquals("1.2.3", row?.release)
        assertEquals("javascript", row?.platform)
        assertEquals("checkout", row?.serviceNamespace)
        assertEquals("api", row?.service)
        assertEquals("otlp", row?.sourceType)
        assertEquals("OpenTelemetry", row?.sourceName)
        assertEquals("moneat.user_feedback", row?.sourceEventName)
        assertEquals(mapOf("feature" to "checkout"), row?.tags)
        assertEquals("api", row?.resourceAttributes?.get("service.name"))
    }

    @Test
    fun `parseOtlpFeedbackJson supports eventName and fallback attributes`() {
        val feedbackId = "4f01ede1-5802-4ff1-81b7-f2fe9add31e5"
        val payload =
            """
            {
              "resourceLogs": [{
                "resource": {"attributes": [
                  {"key": "service.name", "value": {"stringValue": "checkout-web"}},
                  {"key": "os.type", "value": {"stringValue": "linux"}}
                ]},
                "instrumentationLibraryLogs": [{
                  "logRecords": [{
                    "eventName": "moneat.user_feedback",
                    "observedTimeUnixNano": "1700000001000000000",
                    "body": {"stringValue": ""},
                    "attributes": [
                      {"key": "moneat.feedback.id", "value": {"stringValue": "$feedbackId"}},
                      {"key": "moneat.feedback.message", "value": {"stringValue": "Need a clearer CTA"}},
                      {"key": "user.email", "value": {"stringValue": "fallback@example.com"}},
                      {"key": "user.name", "value": {"stringValue": "Fallback User"}},
                      {"key": "moneat.feedback.url", "value": {"stringValue": "https://app.example.com/pricing"}}
                    ]
                  }]
                }]
              }]
            }
            """.trimIndent()

        val row = service.parseOtlpFeedbackJson(payload)?.single()

        assertEquals(feedbackId, row?.feedbackId)
        assertEquals("Need a clearer CTA", row?.message)
        assertEquals("fallback@example.com", row?.contactEmail)
        assertEquals("Fallback User", row?.name)
        assertEquals("https://app.example.com/pricing", row?.url)
        assertEquals("linux", row?.platform)
        assertEquals("checkout-web", row?.service)
        assertEquals(1_700_000_001_000L, row?.timestampMs)
    }

    @Test
    fun `parseOtlpFeedbackJson rejects invalid json and accepts missing resource logs`() {
        assertNull(service.parseOtlpFeedbackJson("{"))
        assertEquals(emptyList(), service.parseOtlpFeedbackJson("{}"))
    }

    @Test
    fun `parseOtlpFeedbackProtobuf extracts eventName feedback records`() {
        val record = LogRecord.newBuilder()
            .setEventName("moneat.user_feedback")
            .setTimeUnixNano(1_700_000_000_000_000_000L)
            .setTraceId(ByteString.copyFrom(hexBytes("00000000000000000000000000000002")))
            .setSpanId(ByteString.copyFrom(hexBytes("0000000000000002")))
            .setBody(stringValue("Checkout button does nothing"))
            .addAttributes(attribute("moneat.feedback.contact_email", "user@example.com"))
            .addAttributes(attribute("user.name", "User Example"))
            .addAttributes(attribute("url.full", "https://app.example.com/checkout"))
            .addAttributes(attribute("priority", "high"))
            .build()
        val request = ExportLogsServiceRequest.newBuilder()
            .addResourceLogs(
                ResourceLogs.newBuilder()
                    .setResource(
                        Resource.newBuilder()
                            .addAttributes(attribute("service.namespace", "checkout"))
                            .addAttributes(attribute("service.name", "api"))
                            .addAttributes(attribute("deployment.environment.name", "production"))
                            .addAttributes(attribute("service.version", "1.2.3"))
                            .addAttributes(attribute("telemetry.sdk.language", "javascript"))
                    )
                    .addScopeLogs(ScopeLogs.newBuilder().addLogRecords(record))
            )
            .build()

        val feedback = service.parseOtlpFeedbackProtobuf(request.toByteArray())

        assertEquals(1, feedback.size)
        val row = feedback.single()
        assertEquals("Checkout button does nothing", row.message)
        assertEquals("User Example", row.name)
        assertEquals("00000000000000000000000000000002", row.traceId)
        assertEquals("0000000000000002", row.spanId)
        assertEquals(mapOf("priority" to "high"), row.tags)
    }

    @Test
    fun `parseOtlpFeedbackProtobuf supports event name attributes and message fallback`() {
        val record = LogRecord.newBuilder()
            .setObservedTimeUnixNano(1_700_000_001_000_000_000L)
            .addAttributes(attribute("event.name", "moneat.user_feedback"))
            .addAttributes(attribute("moneat.feedback.message", "Message from attributes"))
            .addAttributes(attribute("user.email", "fallback@example.com"))
            .build()
        val request = ExportLogsServiceRequest.newBuilder()
            .addResourceLogs(
                ResourceLogs.newBuilder()
                    .setResource(
                        Resource.newBuilder()
                            .addAttributes(attribute("service.name", "worker"))
                            .addAttributes(attribute("os.type", "linux"))
                    )
                    .addScopeLogs(ScopeLogs.newBuilder().addLogRecords(record))
            )
            .build()

        val row = service.parseOtlpFeedbackProtobuf(request.toByteArray()).single()

        assertEquals("Message from attributes", row.message)
        assertEquals("fallback@example.com", row.contactEmail)
        assertEquals("linux", row.platform)
        assertEquals(1_700_000_001_000L, row.timestampMs)
    }

    @Test
    fun `parseOtlpFeedbackProtobuf returns empty for invalid protobuf`() {
        assertEquals(emptyList(), service.parseOtlpFeedbackProtobuf(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `insertFeedback inserts mapped rows and preserves source metadata`() = runBlocking {
        val repository = mockk<EventRepository>()
        val capturedFeedback = slot<FeedbackInsertData>()
        coEvery { repository.insertFeedback(capture(capturedFeedback)) } returns true
        val service = OtlpFeedbackService(repository)
        val mapped = feedbackInsert(projectId = 42L)
        val unmapped = feedbackInsert(projectId = null)

        val result = service.insertFeedback(organizationId = 7, rows = listOf(mapped, unmapped))

        assertEquals(OtlpFeedbackIngestResult(accepted = 1, unmapped = 0), result)
        assertEquals(42L, capturedFeedback.captured.projectId)
        assertEquals(7, capturedFeedback.captured.organizationId)
        assertEquals("otlp", capturedFeedback.captured.sourceType)
        assertEquals("OpenTelemetry", capturedFeedback.captured.sourceName)
        assertEquals("moneat.user_feedback", capturedFeedback.captured.sourceEventName)
        assertEquals("00000000000000000000000000000001", capturedFeedback.captured.traceId)
        assertEquals("0000000000000001", capturedFeedback.captured.spanId)
        assertEquals(mapOf("feature" to "checkout"), capturedFeedback.captured.tags)
        assertEquals(mapOf("service.name" to "api"), capturedFeedback.captured.resourceAttributes)
    }

    private fun feedbackInsert(projectId: Long?): OtlpFeedbackInsert =
        OtlpFeedbackInsert(
            feedbackId = "4f01ede1-5802-4ff1-81b7-f2fe9add31e5",
            projectId = projectId,
            timestampMs = 1_700_000_000_000L,
            message = "Checkout is confusing",
            contactEmail = "user@example.com",
            name = "User Example",
            url = "https://app.example.com/checkout",
            associatedEventId = "event-1",
            replayId = "replay-1",
            environment = "production",
            release = "1.2.3",
            platform = "javascript",
            userId = "user-1",
            userEmail = "user@example.com",
            userUsername = "user",
            traceId = "00000000000000000000000000000001",
            spanId = "0000000000000001",
            sourceType = "otlp",
            sourceName = "OpenTelemetry",
            sourceEventName = "moneat.user_feedback",
            serviceNamespace = "checkout",
            service = "api",
            tags = mapOf("feature" to "checkout"),
            resourceAttributes = mapOf("service.name" to "api"),
        )

    private fun attribute(key: String, value: String): KeyValue =
        KeyValue.newBuilder()
            .setKey(key)
            .setValue(stringValue(value))
            .build()

    private fun stringValue(value: String): AnyValue =
        AnyValue.newBuilder().setStringValue(value).build()

    private fun hexBytes(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
