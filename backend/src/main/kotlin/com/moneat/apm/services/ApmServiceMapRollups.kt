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

package com.moneat.apm.services

import com.moneat.config.ClickHouseClient
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import com.moneat.utils.suspendRunCatching
import io.ktor.http.isSuccess
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

private const val NANOS_PER_SECOND = 1_000_000_000L
private const val SECONDS_PER_HOUR = 3_600L
private const val SERVICE_STATS_TABLE = "apm_service_stats_hourly"
private const val SERVICE_EDGES_TABLE = "apm_service_edges_hourly"

data class ApmServiceMapSpan(
    val organizationId: Long,
    val traceKey: String,
    val spanKey: String,
    val parentKey: String,
    val service: String,
    val env: String,
    val source: String,
    val startNanos: Long,
    val durationNanos: Long,
    val error: Int,
)

object ApmServiceMapRollups {

    suspend fun insertForSpans(
        clickhouseDb: String,
        spans: List<ApmServiceMapSpan>,
    ) {
        val validSpans = spans.filter { it.isRollupEligible() }
        if (validSpans.isEmpty()) return

        listOfNotNull(
            buildServiceStatsInsert(clickhouseDb, validSpans),
            buildServiceEdgesInsert(clickhouseDb, validSpans),
        ).forEach { statement ->
            executeRollupInsert(statement)
        }
    }

    internal fun buildServiceStatsInsert(
        clickhouseDb: String,
        spans: List<ApmServiceMapSpan>,
    ): String? {
        val stats = mutableMapOf<ServiceStatsKey, ServiceStatsAccumulator>()
        spans.filter { it.isRollupEligible() }.forEach { span ->
            val key = ServiceStatsKey(
                organizationId = span.organizationId,
                bucketStartSeconds = bucketStartSeconds(span.startNanos),
                service = span.service,
                env = span.env,
                source = span.source,
            )
            stats.getOrPut(key) { ServiceStatsAccumulator() }.add(span)
        }
        if (stats.isEmpty()) return null

        val rows = stats.entries.joinToString(",\n") { (key, value) ->
            """(
                ${key.organizationId},
                toDateTime(${key.bucketStartSeconds}, 'UTC'),
                '${escapeSql(key.service)}',
                '${escapeSql(key.env)}',
                '${escapeSql(key.source)}',
                ${value.spanCount},
                ${value.errorCount},
                ${value.durationSum},
                ${value.durationCount}
            )
            """.trimIndent()
        }

        return """
            INSERT INTO `$clickhouseDb`.$SERVICE_STATS_TABLE (
                organization_id, bucket_start, service, env, source,
                span_count, error_count, duration_sum, duration_count
            ) VALUES
            $rows
        """.trimIndent()
    }

    internal fun buildServiceEdgesInsert(
        clickhouseDb: String,
        spans: List<ApmServiceMapSpan>,
    ): String? {
        val spansByTraceAndId = spans
            .filter { it.isRollupEligible() }
            .associateBy { TraceSpanKey(it.organizationId, it.traceKey, it.spanKey) }
        val edges = mutableMapOf<ServiceEdgeKey, ServiceEdgeAccumulator>()

        spans.filter { it.isRollupEligible() && it.parentKey.isNotBlank() }.forEach { child ->
            val parent = spansByTraceAndId[
                TraceSpanKey(child.organizationId, child.traceKey, child.parentKey)
            ] ?: return@forEach
            if (parent.service == child.service) return@forEach

            val key = ServiceEdgeKey(
                organizationId = child.organizationId,
                bucketStartSeconds = bucketStartSeconds(child.startNanos),
                fromService = parent.service,
                toService = child.service,
                env = child.env,
                source = child.source,
            )
            edges.getOrPut(key) { ServiceEdgeAccumulator() }.add(child)
        }
        if (edges.isEmpty()) return null

        val rows = edges.entries.joinToString(",\n") { (key, value) ->
            """(
                ${key.organizationId},
                toDateTime(${key.bucketStartSeconds}, 'UTC'),
                '${escapeSql(key.fromService)}',
                '${escapeSql(key.toService)}',
                '${escapeSql(key.env)}',
                '${escapeSql(key.source)}',
                ${value.callCount},
                ${value.errorCount},
                ${value.durationSum},
                ${value.durationCount}
            )
            """.trimIndent()
        }

        return """
            INSERT INTO `$clickhouseDb`.$SERVICE_EDGES_TABLE (
                organization_id, bucket_start, from_service, to_service, env, source,
                call_count, error_count, duration_sum, duration_count
            ) VALUES
            $rows
        """.trimIndent()
    }

    private suspend fun executeRollupInsert(statement: String) {
        val response = suspendRunCatching { ClickHouseClient.execute(statement) }
            .getOrElse { e ->
                logger.warn(e) { "Failed to insert APM service-map rollup rows" }
                return
            }

        if (!response.status.isSuccess()) {
            logger.warn { "ClickHouse rejected APM service-map rollup insert: ${response.status}" }
        }
    }

    private fun ApmServiceMapSpan.isRollupEligible(): Boolean =
        organizationId > 0 &&
            traceKey.isNotBlank() &&
            spanKey.isNotBlank() &&
            service.isNotBlank()

    private fun bucketStartSeconds(startNanos: Long): Long {
        val startSeconds = Math.floorDiv(startNanos, NANOS_PER_SECOND)
        return Math.floorDiv(startSeconds, SECONDS_PER_HOUR) * SECONDS_PER_HOUR
    }

    private data class TraceSpanKey(
        val organizationId: Long,
        val traceKey: String,
        val spanKey: String,
    )

    private data class ServiceStatsKey(
        val organizationId: Long,
        val bucketStartSeconds: Long,
        val service: String,
        val env: String,
        val source: String,
    )

    private data class ServiceEdgeKey(
        val organizationId: Long,
        val bucketStartSeconds: Long,
        val fromService: String,
        val toService: String,
        val env: String,
        val source: String,
    )

    private class ServiceStatsAccumulator {
        var spanCount: Long = 0
            private set
        var errorCount: Long = 0
            private set
        var durationSum: Long = 0
            private set
        var durationCount: Long = 0
            private set

        fun add(span: ApmServiceMapSpan) {
            spanCount += 1
            errorCount += span.error.toLong()
            durationSum += span.durationNanos
            durationCount += 1
        }
    }

    private class ServiceEdgeAccumulator {
        var callCount: Long = 0
            private set
        var errorCount: Long = 0
            private set
        var durationSum: Long = 0
            private set
        var durationCount: Long = 0
            private set

        fun add(span: ApmServiceMapSpan) {
            callCount += 1
            errorCount += span.error.toLong()
            durationSum += span.durationNanos
            durationCount += 1
        }
    }
}
