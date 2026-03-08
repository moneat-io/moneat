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

import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.datadog.models.DatadogEvent
import com.moneat.datadog.models.DatadogServiceCheck
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private const val EVENT_QUEUE_KEY = "moneat:infra_events:queue"

@Serializable
data class QueuedEventBatch(
    @SerialName("organization_id") val organizationId: Long,
    val events: List<QueuedEventEntry>
)

@Serializable
data class QueuedEventEntry(
    val title: String,
    val text: String = "",
    @SerialName("timestamp_ms") val timestampMs: Long,
    val priority: String = "normal",
    val host: String = "",
    val tags: Map<String, String> = emptyMap(),
    @SerialName("alert_type") val alertType: String = "info",
    @SerialName("aggregation_key") val aggregationKey: String = "",
    @SerialName("source_type_name") val sourceTypeName: String = "",
    @SerialName("device_name") val deviceName: String = ""
)

@Serializable
data class QueuedServiceCheckBatch(
    @SerialName("organization_id") val organizationId: Long,
    @SerialName("service_checks") val serviceChecks: List<QueuedServiceCheckEntry>
)

@Serializable
data class QueuedServiceCheckEntry(
    @SerialName("check_name") val checkName: String,
    val host: String = "",
    val status: Int = 0,
    @SerialName("timestamp_ms") val timestampMs: Long,
    val tags: Map<String, String> = emptyMap(),
    val message: String = ""
)

object DatadogEventService {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Suppress("MagicNumber")
    fun mapEvents(
        organizationId: Long,
        events: List<DatadogEvent>
    ): QueuedEventBatch {
        val now = System.currentTimeMillis()
        val entries = events.map { event ->
            val tags = DatadogMetricService.parseDdTagList(event.tags)
            val timestampMs = if (event.dateHappened != null) {
                event.dateHappened * 1000
            } else {
                now
            }
            val alertType = when (event.alertType.lowercase()) {
                "info" -> "info"
                "warning" -> "warning"
                "error" -> "error"
                "success" -> "success"
                else -> "info"
            }
            QueuedEventEntry(
                title = event.title,
                text = event.text,
                timestampMs = timestampMs,
                priority = event.priority,
                host = event.host,
                tags = tags,
                alertType = alertType,
                aggregationKey = event.aggregationKey,
                sourceTypeName = event.sourceTypeName,
                deviceName = event.deviceName
            )
        }
        return QueuedEventBatch(
            organizationId = organizationId,
            events = entries
        )
    }

    @Suppress("MagicNumber")
    fun mapServiceChecks(
        organizationId: Long,
        checks: List<DatadogServiceCheck>
    ): QueuedServiceCheckBatch {
        val now = System.currentTimeMillis()
        val entries = checks.map { sc ->
            val tags = DatadogMetricService.parseDdTagList(sc.tags)
            val timestampMs = if (sc.timestamp != null) {
                sc.timestamp * 1000
            } else {
                now
            }
            QueuedServiceCheckEntry(
                checkName = sc.check,
                host = sc.hostName,
                status = sc.status,
                timestampMs = timestampMs,
                tags = tags,
                message = sc.message
            )
        }
        return QueuedServiceCheckBatch(
            organizationId = organizationId,
            serviceChecks = entries
        )
    }

    suspend fun enqueueEvents(
        organizationId: Long,
        events: List<DatadogEvent>,
        queueKey: String = EVENT_QUEUE_KEY
    ): Int {
        val batch = mapEvents(organizationId, events)
        if (batch.events.isEmpty()) return 0
        val message = json.encodeToString(batch)
        RedisConfig.sync().lpush(queueKey, message)
        logger.debug {
            "Enqueued ${batch.events.size} DD events for org $organizationId"
        }
        return batch.events.size
    }

    suspend fun insertEventBatch(batch: QueuedEventBatch) {
        if (batch.events.isEmpty()) return
        val db = ClickHouseClient.getDatabase()

        val rows = batch.events.joinToString(",\n") { e ->
            val tagsMap = e.tags.entries.joinToString(",") { (k, v) ->
                "'${escapeSql(k)}','${escapeSql(v)}'"
            }
            """(
                ${batch.organizationId},
                '${escapeSql(e.title)}',
                '${escapeSql(e.text)}',
                fromUnixTimestamp64Milli(${e.timestampMs}),
                '${escapeSql(e.priority)}',
                '${escapeSql(e.host)}',
                map($tagsMap),
                '${escapeSql(e.alertType)}',
                '${escapeSql(e.aggregationKey)}',
                '${escapeSql(e.sourceTypeName)}',
                '${escapeSql(e.deviceName)}'
            )"""
        }

        val insert = """
            INSERT INTO `$db`.infra_events (
                organization_id, title, text, timestamp,
                priority, host, tags, alert_type,
                aggregation_key, source_type_name, device_name
            ) VALUES $rows
        """.trimIndent()

        val response = ClickHouseClient.execute(insert)
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw IllegalStateException(
                "Failed to insert DD events: ${errorBody.take(600)}"
            )
        }
    }

    suspend fun insertServiceCheckBatch(
        batch: QueuedServiceCheckBatch
    ) {
        if (batch.serviceChecks.isEmpty()) return
        val db = ClickHouseClient.getDatabase()

        val rows = batch.serviceChecks.joinToString(",\n") { sc ->
            val tagsMap = sc.tags.entries.joinToString(",") { (k, v) ->
                "'${escapeSql(k)}','${escapeSql(v)}'"
            }
            val statusStr = when (sc.status) {
                0 -> "ok"
                1 -> "warning"
                2 -> "critical"
                else -> "unknown"
            }
            """(
                ${batch.organizationId},
                '${escapeSql(sc.checkName)}',
                '${escapeSql(sc.host)}',
                '$statusStr',
                fromUnixTimestamp64Milli(${sc.timestampMs}),
                map($tagsMap),
                '${escapeSql(sc.message)}'
            )"""
        }

        val insert = """
            INSERT INTO `$db`.service_checks (
                organization_id, check_name, host, status,
                timestamp, tags, message
            ) VALUES $rows
        """.trimIndent()

        val response = ClickHouseClient.execute(insert)
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw IllegalStateException(
                "Failed to insert DD service checks: " +
                    errorBody.take(600)
            )
        }
    }

    fun decodeEventBatch(encoded: String): QueuedEventBatch {
        return json.decodeFromString(encoded)
    }

    fun decodeServiceCheckBatch(
        encoded: String
    ): QueuedServiceCheckBatch {
        return json.decodeFromString(encoded)
    }
}
