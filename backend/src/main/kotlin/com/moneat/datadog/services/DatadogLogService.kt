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

package com.moneat.datadog.services

import com.moneat.datadog.models.DatadogLogEntry
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueClient
import com.moneat.logs.services.LogLineClassification
import com.moneat.logs.services.LogLineClassifier
import com.moneat.logs.models.QueuedLogBatch
import com.moneat.logs.models.QueuedLogEntry
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.util.UUID

private val logger = KotlinLogging.logger {}
private const val LOG_QUEUE_KEY = "moneat:logs:queue"
private const val DEFAULT_LOG_LEVEL = "info"
private const val TAG_CATEGORY = "category"
private const val TAG_DD_SOURCE = "ddsource"
private const val TAG_VERSION = "version"
private const val RESOURCE_SERVICE_VERSION = "service.version"

object DatadogLogService {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun mapDdLogs(
        organizationId: Long,
        entries: List<DatadogLogEntry>
    ): QueuedLogBatch {
        val logs = entries.map { entry ->
            val tags = parseDdTags(entry.ddtags).toMutableMap()
            if (entry.ddsource.isNotBlank()) {
                tags[TAG_DD_SOURCE] = entry.ddsource
            }
            val classification = LogLineClassifier.classify(entry.message)
            addClassificationTags(tags, classification)
            val resourceAttributes = serviceVersionResourceAttributes(tags)

            QueuedLogEntry(
                logId = UUID.randomUUID().toString(),
                timestampMs = entry.timestamp
                    ?: System.currentTimeMillis(),
                level = resolveLogLevel(entry.status, classification.level),
                message = classification.message,
                body = classification.body.orEmpty(),
                service = entry.service,
                environment = tags.remove("env") ?: "",
                host = entry.hostname,
                source = "datadog",
                containerName = "",
                containerId = "",
                containerImage = "",
                traceId = "",
                spanId = "",
                tags = tags,
                resourceAttributes = resourceAttributes
            )
        }

        return QueuedLogBatch(
            organizationId = organizationId,
            legacyProjectId = null,
            systemId = null,
            source = "datadog",
            logs = logs
        )
    }

    suspend fun enqueueLogs(
        organizationId: Long,
        entries: List<DatadogLogEntry>,
        queueKey: String = LOG_QUEUE_KEY
    ): Int {
        if (entries.isEmpty()) return 0

        val batch = mapDdLogs(organizationId, entries)
        if (batch.logs.isEmpty()) return 0

        val message = json.encodeToString(batch)
        IngestionQueueClient.enqueue(IngestionPipeline.LOGS, queueKey, message)
        logger.debug {
            "Enqueued ${batch.logs.size} DD logs for org $organizationId"
        }
        return batch.logs.size
    }

    internal fun parseDdTags(ddtags: String): MutableMap<String, String> {
        if (ddtags.isBlank()) return mutableMapOf()
        val tags = mutableMapOf<String, String>()
        ddtags.split(",").forEach { tag ->
            val trimmed = tag.trim()
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx > 0) {
                val key = trimmed.substring(0, colonIdx)
                val value = trimmed.substring(colonIdx + 1)
                tags[key] = value
            } else if (trimmed.isNotEmpty()) {
                tags[trimmed] = ""
            }
        }
        return tags
    }

    private fun resolveLogLevel(
        status: String,
        classifiedLevel: String?
    ): String {
        val envelopeLevel = LogLineClassifier.normalizeLevel(status) ?: DEFAULT_LOG_LEVEL
        return LogLineClassifier.resolveLevel(envelopeLevel, classifiedLevel)
    }

    private fun addClassificationTags(
        tags: MutableMap<String, String>,
        classification: LogLineClassification
    ) {
        classification.tags.forEach { (key, value) ->
            if (key == TAG_CATEGORY) {
                tags.putIfAbsent(key, value)
            } else {
                tags[key] = value
            }
        }
    }

    private fun serviceVersionResourceAttributes(tags: Map<String, String>): Map<String, String> {
        val serviceVersion = tags[RESOURCE_SERVICE_VERSION]
            ?.takeIf { it.isNotBlank() }
            ?: tags[TAG_VERSION]?.takeIf { it.isNotBlank() }

        return serviceVersion?.let { mapOf(RESOURCE_SERVICE_VERSION to it) } ?: emptyMap()
    }
}
