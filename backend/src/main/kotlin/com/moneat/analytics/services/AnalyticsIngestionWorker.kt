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

import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.analytics.models.EnrichedAnalyticsEvent
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import mu.KotlinLogging

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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var jobs: List<Job> = emptyList()

    fun start() {
        logger.info { "Starting AnalyticsIngestionWorker with $workerCount workers, queue=$queueKey" }
        jobs = (1..workerCount).map { id ->
            scope.launch { runWorker(id) }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        scope.cancel()
        logger.info { "AnalyticsIngestionWorker stopped" }
    }

    private suspend fun runWorker(workerId: Int) {
        val redis = RedisConfig.newBlockingConnection()
        while (scope.isActive) {
            try {
                val result = redis.brpop(
                    BRPOP_TIMEOUT,
                    queueKey
                )
                val value = result?.value ?: continue
                processMessage(workerId, value)
            } catch (_: CancellationException) {
                break
            } catch (e: Exception) {
                logger.error(e) { "Analytics worker $workerId error in BRPOP loop" }
                delay(ERROR_BACKOFF_MS)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    internal suspend fun processMessage(workerId: Int, value: String) {
        try {
            val event = json.decodeFromString<EnrichedAnalyticsEvent>(value)
            insertEvent(event)
        } catch (e: Exception) {
            logger.error(e) { "Analytics worker $workerId failed to process message, sending to DLQ" }
            try {
                RedisConfig.sync().rpush(dlqKey, value)
            } catch (dlqError: Exception) {
                logger.error(dlqError) { "Failed to push to analytics DLQ" }
            }
        }
    }

    private suspend fun insertEvent(event: EnrichedAnalyticsEvent) {
        val propsEntries = event.props.entries.joinToString(", ") { (k, v) ->
            "'${escapeCH(k)}', '${escapeCH(v)}'"
        }
        val propsMap = if (propsEntries.isEmpty()) "map()" else "map($propsEntries)"

        val sql = """
            INSERT INTO analytics_events (
                project_id, session_id, event_name, hostname, pathname,
                referrer, referrer_source,
                utm_source, utm_medium, utm_campaign, utm_term, utm_content,
                country_code, subdivision, city,
                browser, browser_version, os, os_version, device_type,
                screen_width, props, timestamp
            ) VALUES (
                ${event.projectId},
                '${escapeCH(event.sessionId)}',
                '${escapeCH(event.eventName)}',
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
        if (!response.status.isSuccess() || body.trimStart().startsWith("Code:")) {
            throw RuntimeException("ClickHouse insert failed: $body")
        }
    }

    companion object {
        const val QUEUE_KEY = "moneat:analytics:queue"
        const val DLQ_KEY = "moneat:analytics:dlq"
        const val REALTIME_KEY_PREFIX = "moneat:analytics:realtime:"
        private const val DEFAULT_WORKER_COUNT = 2
        private const val BRPOP_TIMEOUT = 5L
        private const val ERROR_BACKOFF_MS = 1000L

        fun escapeCH(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'")
    }
}
