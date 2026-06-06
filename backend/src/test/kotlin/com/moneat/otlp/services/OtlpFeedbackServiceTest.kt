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
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.logs.v1.LogRecord
import io.opentelemetry.proto.logs.v1.ResourceLogs
import io.opentelemetry.proto.logs.v1.ScopeLogs
import io.opentelemetry.proto.resource.v1.Resource
import kotlin.test.Test
import kotlin.test.assertEquals

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
