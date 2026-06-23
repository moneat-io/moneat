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

import com.moneat.otlp.OtlpParsingUtils
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }
private const val OTLP_SPAN_STATUS_ERROR = 2

/**
 * Extracts exception events from OTLP span data.
 *
 * OTLP encodes errors as span events with name="exception" carrying:
 * - exception.type
 * - exception.message
 * - exception.stacktrace
 *
 * These are extracted here so they can be fed into Moneat's error tracking pipeline.
 *
 * Exceptions are persisted only when the span has already been routed to a project through
 * OpenTelemetry service mappings.
 */
object OtlpErrorExtractor {

    fun extractExceptions(spans: List<OtlpSpanInsert>): List<OtlpExceptionEvent> {
        val exceptions = mutableListOf<OtlpExceptionEvent>()

        for (span in spans) {
            val parsedEvents = parseSpanEvents(span.events)
            val hasExceptionEvent =
                parsedEvents.any { it["name"]?.jsonPrimitive?.contentOrNull == "exception" }
            if (span.statusCode != OTLP_SPAN_STATUS_ERROR && !hasExceptionEvent) {
                continue
            }

            for (event in parsedEvents) {
                val eventName = event["name"]?.jsonPrimitive?.contentOrNull ?: continue
                if (eventName != "exception") continue

                val attrs = OtlpParsingUtils.attributesToMap(event["attributes"])
                val exType = attrs["exception.type"] ?: "UnknownError"
                val exMessage = attrs["exception.message"] ?: ""
                val stackTrace = attrs["exception.stacktrace"] ?: ""

                val tsNs = OtlpParsingUtils.extractTimestampNanos(event, "timeUnixNano")
                val tsMs = OtlpParsingUtils.nanoToEpochMs(tsNs)
                    ?: OtlpParsingUtils.nanoToEpochMs(span.startNanos)
                    ?: System.currentTimeMillis()

                exceptions += OtlpExceptionEvent(
                    traceIdHex = span.traceIdHex,
                    spanIdHex = span.spanIdHex,
                    organizationId = span.organizationId,
                    projectId = span.projectId,
                    serviceNamespace = span.serviceNamespace,
                    service = span.service,
                    environment = span.env,
                    host = span.host,
                    serviceVersion = span.version,
                    exceptionType = exType,
                    exceptionMessage = exMessage,
                    stackTrace = stackTrace,
                    timestampMs = tsMs,
                )
            }

            if (parsedEvents.none {
                    it["name"]?.jsonPrimitive?.contentOrNull == "exception"
                } && span.statusCode == OTLP_SPAN_STATUS_ERROR
            ) {
                val tsMs = OtlpParsingUtils.nanoToEpochMs(span.startNanos)
                    ?: System.currentTimeMillis()
                exceptions += OtlpExceptionEvent(
                    traceIdHex = span.traceIdHex,
                    spanIdHex = span.spanIdHex,
                    organizationId = span.organizationId,
                    projectId = span.projectId,
                    serviceNamespace = span.serviceNamespace,
                    service = span.service,
                    environment = span.env,
                    host = span.host,
                    serviceVersion = span.version,
                    exceptionType = "SpanError",
                    exceptionMessage = span.statusMessage.ifEmpty { "Span completed with ERROR status" },
                    stackTrace = "",
                    timestampMs = tsMs,
                )
            }
        }

        return exceptions
    }

    private fun parseSpanEvents(eventsJson: String): List<JsonObject> {
        if (eventsJson == "[]" || eventsJson.isBlank()) return emptyList()
        return try {
            json.parseToJsonElement(eventsJson).jsonArray.map { it.jsonObject }
        } catch (e: SerializationException) {
            logger.debug(e) { "Failed to parse span events JSON" }
            emptyList()
        }
    }
}
