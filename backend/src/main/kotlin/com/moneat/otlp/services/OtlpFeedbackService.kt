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

import com.google.protobuf.InvalidProtocolBufferException
import com.moneat.events.repositories.EventRepository
import com.moneat.events.repositories.EventRepositoryImpl
import com.moneat.events.repositories.models.FeedbackInsertData
import com.moneat.otlp.OtlpParsingUtils
import com.moneat.otlp.OtlpProtobufParser
import com.moneat.otlp.ResourceContext
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.logs.v1.LogRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.util.UUID

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }
private const val FEEDBACK_EVENT_NAME = "moneat.user_feedback"
private const val OTLP_SOURCE_TYPE = "otlp"
private const val OTLP_SOURCE_NAME = "OpenTelemetry"
private const val EVENT_NAME_ATTRIBUTE = "event.name"
private const val FEEDBACK_ID_ATTRIBUTE = "moneat.feedback.id"
private const val FEEDBACK_MESSAGE_ATTRIBUTE = "moneat.feedback.message"
private const val FEEDBACK_CONTACT_EMAIL_ATTRIBUTE = "moneat.feedback.contact_email"
private const val FEEDBACK_NAME_ATTRIBUTE = "moneat.feedback.name"
private const val FEEDBACK_URL_ATTRIBUTE = "moneat.feedback.url"
private const val FEEDBACK_ASSOCIATED_EVENT_ID_ATTRIBUTE = "moneat.feedback.associated_event_id"
private const val FEEDBACK_REPLAY_ID_ATTRIBUTE = "moneat.feedback.replay_id"
private const val USER_ID_ATTRIBUTE = "user.id"
private const val USER_EMAIL_ATTRIBUTE = "user.email"
private const val USER_NAME_ATTRIBUTE = "user.name"
private const val USER_USERNAME_ATTRIBUTE = "user.username"
private const val URL_FULL_ATTRIBUTE = "url.full"

private val canonicalRecordAttributes = setOf(
    EVENT_NAME_ATTRIBUTE,
    FEEDBACK_ID_ATTRIBUTE,
    FEEDBACK_MESSAGE_ATTRIBUTE,
    FEEDBACK_CONTACT_EMAIL_ATTRIBUTE,
    FEEDBACK_NAME_ATTRIBUTE,
    FEEDBACK_URL_ATTRIBUTE,
    FEEDBACK_ASSOCIATED_EVENT_ID_ATTRIBUTE,
    FEEDBACK_REPLAY_ID_ATTRIBUTE,
    USER_ID_ATTRIBUTE,
    USER_EMAIL_ATTRIBUTE,
    USER_NAME_ATTRIBUTE,
    USER_USERNAME_ATTRIBUTE,
    URL_FULL_ATTRIBUTE,
)

data class OtlpFeedbackInsert(
    val feedbackId: String,
    val projectId: Long? = null,
    val timestampMs: Long,
    val message: String,
    val contactEmail: String,
    val name: String,
    val url: String,
    val associatedEventId: String,
    val replayId: String,
    val environment: String,
    val release: String,
    val platform: String,
    val userId: String,
    val userEmail: String,
    val userUsername: String,
    val traceId: String,
    val spanId: String,
    val sourceType: String,
    val sourceName: String,
    val sourceEventName: String,
    val serviceNamespace: String,
    val service: String,
    val tags: Map<String, String>,
    val resourceAttributes: Map<String, String>,
)

@Serializable
data class OtlpFeedbackIngestResult(
    val accepted: Int,
    val unmapped: Int,
)

class OtlpFeedbackService(
    private val eventRepository: EventRepository = EventRepositoryImpl(),
) {
    fun parseOtlpFeedbackJson(payload: String): List<OtlpFeedbackInsert>? {
        val parsed =
            runCatching {
                json.parseToJsonElement(payload).jsonObject
            }.getOrElse { e ->
                logger.warn(e) { "Invalid OTLP JSON feedback payload" }
                return null
            }

        val rows = mutableListOf<OtlpFeedbackInsert>()
        val resourceLogs = parsed["resourceLogs"]?.jsonArray ?: return emptyList()
        resourceLogs.forEach { resourceLogElement ->
            appendJsonResourceLog(resourceLogElement.jsonObject, rows)
        }
        return rows
    }

    fun parseOtlpFeedbackProtobuf(bytes: ByteArray): List<OtlpFeedbackInsert> {
        val request =
            try {
                ExportLogsServiceRequest.parseFrom(bytes)
            } catch (e: InvalidProtocolBufferException) {
                logger.warn { "Invalid OTLP protobuf feedback payload: ${e.message}" }
                return emptyList()
            }

        val rows = mutableListOf<OtlpFeedbackInsert>()
        request.resourceLogsList.forEach { resourceLogs ->
            val resourceCtx = OtlpProtobufParser.extractResourceContext(resourceLogs.resource)
            resourceLogs.scopeLogsList.forEach { scopeLogs ->
                scopeLogs.logRecordsList.mapNotNullTo(rows) { record ->
                    feedbackFromProtobufRecord(record, resourceCtx)
                }
            }
        }
        return rows
    }

    suspend fun insertFeedback(
        organizationId: Int,
        rows: List<OtlpFeedbackInsert>,
    ): OtlpFeedbackIngestResult {
        var accepted = 0
        for (row in rows) {
            val projectId = row.projectId ?: continue
            val inserted = eventRepository.insertFeedback(row.toInsertData(organizationId, projectId))
            if (inserted) accepted++
        }
        return OtlpFeedbackIngestResult(accepted = accepted, unmapped = 0)
    }

    private fun appendJsonResourceLog(
        resourceLog: JsonObject,
        rows: MutableList<OtlpFeedbackInsert>,
    ) {
        val resourceCtx = OtlpParsingUtils.extractResourceContext(resourceLog["resource"]?.jsonObject)
        val scopeLogs =
            resourceLog["scopeLogs"]?.jsonArray
                ?: resourceLog["instrumentationLibraryLogs"]?.jsonArray
                ?: JsonArray(emptyList())
        scopeLogs.forEach { scopeElement ->
            val scopeLog = scopeElement.jsonObject
            OtlpParsingUtils.safeJsonArray(scopeLog["logRecords"]).forEach { recordElement ->
                feedbackFromJsonRecord(recordElement.jsonObject, resourceCtx)?.let(rows::add)
            }
        }
    }

    private fun feedbackFromJsonRecord(
        record: JsonObject,
        resourceCtx: ResourceContext,
    ): OtlpFeedbackInsert? {
        val attributes = OtlpParsingUtils.attributesToMap(record["attributes"])
        val eventName = record["eventName"]?.jsonPrimitive?.contentOrNull
            ?: attributes[EVENT_NAME_ATTRIBUTE]
            ?: return null
        if (eventName != FEEDBACK_EVENT_NAME) return null

        val timestampNs = OtlpParsingUtils.extractTimestampNanos(
            record,
            "timeUnixNano",
            "observedTimeUnixNano"
        )
        val timestampMs = OtlpParsingUtils.nanoToEpochMs(timestampNs) ?: System.currentTimeMillis()
        val bodyText = OtlpParsingUtils.extractAnyValue(record["body"]) ?: ""
        return buildFeedbackRow(
            attributes = attributes,
            resourceCtx = resourceCtx,
            eventName = eventName,
            timestampMs = timestampMs,
            message = bodyText.ifBlank { attributes[FEEDBACK_MESSAGE_ATTRIBUTE].orEmpty() },
            traceId = record["traceId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            spanId = record["spanId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    private fun feedbackFromProtobufRecord(
        record: LogRecord,
        resourceCtx: ResourceContext,
    ): OtlpFeedbackInsert? {
        val attributes = OtlpProtobufParser.attributesToMap(record.attributesList)
        val eventName = record.eventName.ifBlank { attributes[EVENT_NAME_ATTRIBUTE].orEmpty() }
        if (eventName != FEEDBACK_EVENT_NAME) return null

        val timestampMs =
            OtlpProtobufParser.nanoToEpochMs(
                record.timeUnixNano.takeIf { it != 0L } ?: record.observedTimeUnixNano
            ) ?: System.currentTimeMillis()
        val bodyText =
            if (record.hasBody()) {
                OtlpProtobufParser.extractAnyValue(record.body).orEmpty()
            } else {
                ""
            }
        return buildFeedbackRow(
            attributes = attributes,
            resourceCtx = resourceCtx,
            eventName = eventName,
            timestampMs = timestampMs,
            message = bodyText.ifBlank { attributes[FEEDBACK_MESSAGE_ATTRIBUTE].orEmpty() },
            traceId = OtlpProtobufParser.bytesToHex(record.traceId),
            spanId = OtlpProtobufParser.bytesToHex(record.spanId),
        )
    }

    private fun buildFeedbackRow(
        attributes: Map<String, String>,
        resourceCtx: ResourceContext,
        eventName: String,
        timestampMs: Long,
        message: String,
        traceId: String,
        spanId: String,
    ): OtlpFeedbackInsert =
        OtlpFeedbackInsert(
            feedbackId = normalizeFeedbackId(attributes[FEEDBACK_ID_ATTRIBUTE]),
            timestampMs = timestampMs,
            message = message,
            contactEmail = attributes[FEEDBACK_CONTACT_EMAIL_ATTRIBUTE] ?: attributes[USER_EMAIL_ATTRIBUTE].orEmpty(),
            name = attributes[FEEDBACK_NAME_ATTRIBUTE] ?: attributes[USER_NAME_ATTRIBUTE].orEmpty(),
            url = attributes[URL_FULL_ATTRIBUTE] ?: attributes[FEEDBACK_URL_ATTRIBUTE].orEmpty(),
            associatedEventId = attributes[FEEDBACK_ASSOCIATED_EVENT_ID_ATTRIBUTE].orEmpty(),
            replayId = attributes[FEEDBACK_REPLAY_ID_ATTRIBUTE].orEmpty(),
            environment = resourceCtx.environment,
            release = resourceCtx.serviceVersion,
            platform = resourceCtx.attributes["telemetry.sdk.language"] ?: resourceCtx.attributes["os.type"].orEmpty(),
            userId = attributes[USER_ID_ATTRIBUTE].orEmpty(),
            userEmail = attributes[USER_EMAIL_ATTRIBUTE] ?: attributes[FEEDBACK_CONTACT_EMAIL_ATTRIBUTE].orEmpty(),
            userUsername = attributes[USER_USERNAME_ATTRIBUTE].orEmpty(),
            traceId = traceId,
            spanId = spanId,
            sourceType = OTLP_SOURCE_TYPE,
            sourceName = OTLP_SOURCE_NAME,
            sourceEventName = eventName,
            serviceNamespace = resourceCtx.serviceNamespace,
            service = resourceCtx.serviceName,
            tags = attributes.filterKeys { it !in canonicalRecordAttributes },
            resourceAttributes = resourceCtx.attributes,
        )

    private fun normalizeFeedbackId(rawId: String?): String =
        rawId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { id ->
                runCatching { UUID.fromString(id).toString() }.getOrNull()
            }
            ?: UUID.randomUUID().toString()

    private fun OtlpFeedbackInsert.toInsertData(
        organizationId: Int,
        projectId: Long,
    ): FeedbackInsertData =
        FeedbackInsertData(
            feedbackId = feedbackId,
            projectId = projectId,
            organizationId = organizationId,
            timestampMs = timestampMs,
            message = message,
            contactEmail = contactEmail,
            name = name,
            url = url,
            associatedEventId = associatedEventId,
            replayId = replayId,
            environment = environment,
            release = release,
            platform = platform,
            userId = userId,
            userEmail = userEmail,
            userUsername = userUsername,
            userIpAddress = "",
            sdkName = sourceName,
            sdkVersion = "",
            tags = tags,
            sourceType = sourceType,
            sourceName = sourceName,
            sourceEventName = sourceEventName,
            traceId = traceId,
            spanId = spanId,
            resourceAttributes = resourceAttributes
        )
}
