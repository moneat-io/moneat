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

package com.moneat.analytics.services

import com.moneat.analytics.models.EnrichedAnalyticsEvent
import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.config.isClickHouseError
import com.moneat.ingestion.queue.IngestionDlqRequest
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueClient
import com.moneat.ingestion.queue.IngestionQueueSettings
import com.moneat.ingestion.queue.RedisQueueWorker
import com.moneat.monitoring.OperationalMetrics
import com.moneat.utils.ClickHouseSqlUtils
import com.moneat.utils.suspendRunCatching
import io.ktor.client.statement.bodyAsText
import io.lettuce.core.RedisException
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.io.IOException

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

/**
 * Background worker that drains the analytics Redis queue and batch-inserts
 * enriched events into ClickHouse.
 */
class AnalyticsIngestionWorker(
    private val queueKey: String = QUEUE_KEY,
    private val dlqKey: String = DLQ_KEY,
    private val workerCount: Int = DEFAULT_WORKER_COUNT,
) {
    private var queueWorker: RedisQueueWorker? = null

    fun start() {
        logger.info { "Starting AnalyticsIngestionWorker with $workerCount workers, queue=$queueKey" }
        val spec = IngestionQueueSettings.spec(IngestionPipeline.ANALYTICS, queueKey, dlqKey, workerCount)
        queueWorker = RedisQueueWorker(spec, logger, processMessage = { workerId, payload ->
            processMessage(workerId, payload) { message ->
                IngestionQueueClient.pushToDlq(
                    logger = logger,
                    request = IngestionDlqRequest(
                        spec = spec,
                        payload = message,
                        workerId = workerId,
                        cause = IllegalStateException("Analytics processing failed"),
                    ),
                )
            }
        }).also { it.start() }
    }

    fun stop() {
        queueWorker?.stop()
        logger.info { "AnalyticsIngestionWorker stopped" }
    }

    internal suspend fun processMessage(
        workerId: Int,
        value: String,
        onDlq: (String) -> Unit = { message -> RedisConfig.sync().rpush(dlqKey, message) },
    ) {
        suspendRunCatching {
            val event = json.decodeFromString<EnrichedAnalyticsEvent>(value)
            insertEvent(event)
            OperationalMetrics.recordWorkerMessageProcessed("Analytics", workerId)
        }.getOrElse { e ->
            logProcessFailureAndDlq(workerId, value, e, onDlq)
        }
    }

    private fun logProcessFailureAndDlq(
        workerId: Int,
        value: String,
        e: Throwable,
        onDlq: (String) -> Unit,
    ) {
        logger.error(e) { "Analytics worker $workerId failed to process message, sending to DLQ" }
        OperationalMetrics.recordWorkerProcessingFailure("Analytics", workerId, e)
        pushToAnalyticsDlq(workerId, value, onDlq)
    }

    private fun pushToAnalyticsDlq(
        workerId: Int,
        value: String,
        onDlq: (String) -> Unit,
    ) {
        try {
            onDlq(value)
            OperationalMetrics.recordDlqPush("Analytics", dlqKey, "success")
        } catch (e: RedisException) {
            OperationalMetrics.recordDlqPush("Analytics", dlqKey, "failure")
            logger.error(e) { "Failed to push to analytics DLQ (worker $workerId)" }
        }
    }

    private suspend fun insertEvent(event: EnrichedAnalyticsEvent) {
        val propsEntries = event.props.entries.joinToString(", ") { (k, v) ->
            "'${escapeCH(k)}', '${escapeCH(v)}'"
        }
        val propsMap = if (propsEntries.isEmpty()) "map()" else "map($propsEntries)"

        val sql = """
            INSERT INTO analytics_events (
                service_id, project_id, session_id, user_id, event_name, source, hostname, pathname,
                referrer, referrer_source,
                utm_source, utm_medium, utm_campaign, utm_term, utm_content,
                country_code, subdivision, city,
                browser, browser_version, os, os_version, device_type,
                screen_width, props, timestamp
            ) VALUES (
                ${event.projectId},
                ${event.projectId},
                '${escapeCH(event.sessionId)}',
                '${escapeCH(event.userId)}',
                '${escapeCH(event.eventName)}',
                '${escapeCH(event.source)}',
                '${escapeCH(event.hostname)}',
                '${escapeCH(event.pathname)}',
                '${escapeCH(event.referrer)}',
                '${escapeCH(event.referrerSource)}',
                '${escapeCH(event.utmSource)}',
                '${escapeCH(event.utmMedium)}',
                '${escapeCH(event.utmCampaign)}',
                '${escapeCH(event.utmTerm)}',
                '${escapeCH(event.utmContent)}',
                '${escapeCH(event.countryCode)}',
                '${escapeCH(event.subdivision)}',
                '${escapeCH(event.city)}',
                '${escapeCH(event.browser)}',
                '${escapeCH(event.browserVersion)}',
                '${escapeCH(event.os)}',
                '${escapeCH(event.osVersion)}',
                '${escapeCH(event.deviceType)}',
                ${event.screenWidth},
                $propsMap,
                fromUnixTimestamp64Milli(${event.timestamp})
            )
        """.trimIndent()

        val response = ClickHouseClient.execute(sql)
        val body = response.bodyAsText()
        if (response.isClickHouseError(body)) {
            throw IOException("ClickHouse insert failed: ${body.take(ERROR_BODY_MAX_LENGTH)}")
        }
    }

    companion object {
        const val QUEUE_KEY = "moneat:analytics:queue"
        const val DLQ_KEY = "moneat:analytics:dlq"
        const val REALTIME_KEY_PREFIX = "moneat:analytics:realtime:"
        private const val DEFAULT_WORKER_COUNT = 2
        private const val ERROR_BODY_MAX_LENGTH = 500

        fun escapeCH(s: String): String = ClickHouseSqlUtils.escapeSql(s)
    }
}
