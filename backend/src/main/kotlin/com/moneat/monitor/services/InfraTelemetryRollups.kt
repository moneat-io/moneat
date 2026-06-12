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

package com.moneat.monitor.services

import com.moneat.config.ClickHouseClient
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import mu.KotlinLogging
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}

data class InfraMetricRollupRow(
    val organizationId: Long,
    val metricName: String,
    val timestampMs: Long,
    val value: Double,
    val host: String,
    val tags: Map<String, String>,
    val unit: String,
    val source: String,
)

data class InfraContainerRollupRow(
    val organizationId: Long,
    val host: String,
    val containerId: String,
    val name: String,
    val image: String,
    val state: String,
    val cpuPercent: Double,
    val memUsage: Long,
    val memLimit: Long,
    val netRxBytes: Long,
    val netTxBytes: Long,
    val tags: Map<String, String>,
    val timestampMs: Long,
)

object InfraTelemetryRollups {
    private val infraMetricNames = setOf(
        "system.cpu.percent",
        "system.cpu.user",
        "system.cpu.system",
        "system.cpu.idle",
        "system.mem.total",
        "system.mem.used",
        "system.mem.available",
        "system.mem.pct_usable",
        "system.disk.total",
        "system.disk.used",
        "system.disk.in_use",
        "system.net.recv_bytes",
        "system.net.sent_bytes",
        "system.net.bytes_rcvd",
        "system.net.bytes_sent",
        "system.load.1",
        "system.load.5",
        "system.load.15",
        "system.temp.max",
        "system.gpu.percent",
        "system.battery.percent",
    )

    suspend fun insertMetricRollups(rows: List<InfraMetricRollupRow>) {
        val resolvedRows = rows.mapNotNull { row ->
            val hostId = resolveHostId(row.tags) ?: return@mapNotNull null
            if (row.metricName !in infraMetricNames) return@mapNotNull null
            ResolvedMetricRollupRow(row, hostId)
        }
        if (resolvedRows.isEmpty()) return

        val db = ClickHouseClient.getDatabase()
        val latestRows = resolvedRows.joinToString(",\n") { (row, hostId) ->
            """(
                ${row.organizationId},
                $hostId,
                '${escapeSql(row.metricName)}',
                fromUnixTimestamp64Milli(${row.timestampMs}),
                ${row.value},
                '${escapeSql(row.host)}',
                '${escapeSql(row.unit)}',
                '${escapeSql(row.source)}',
                ${mapToSqlMap(row.tags)}
            )"""
        }
        val rollupRows = resolvedRows.joinToString(",\n") { (row, hostId) ->
            """(
                ${row.organizationId},
                $hostId,
                toStartOfMinute(fromUnixTimestamp64Milli(${row.timestampMs})),
                '${escapeSql(row.metricName)}',
                '${escapeSql(row.host)}',
                '${escapeSql(row.unit)}',
                '${escapeSql(row.source)}',
                ${row.value},
                1
            )"""
        }

        executeBestEffort(
            """
            INSERT INTO `$db`.metrics_latest_by_host (
                organization_id, host_id, metric_name, timestamp, value,
                host, unit, source, tags
            ) VALUES
            $latestRows
            """.trimIndent(),
            "infra metrics latest",
        )
        executeBestEffort(
            """
            INSERT INTO `$db`.metrics_rollup_1m (
                organization_id, host_id, bucket_start, metric_name, host,
                unit, source, value_sum, value_count
            ) VALUES
            $rollupRows
            """.trimIndent(),
            "infra metrics 1m rollup",
        )
    }

    suspend fun insertContainerRollups(rows: List<InfraContainerRollupRow>) {
        val resolvedRows = rows.mapNotNull { row ->
            val hostId = resolveHostId(row.tags) ?: return@mapNotNull null
            ResolvedContainerRollupRow(row, hostId)
        }
        if (resolvedRows.isEmpty()) return

        val db = ClickHouseClient.getDatabase()
        val latestRows = resolvedRows.joinToString(",\n") { (row, hostId) ->
            """(
                ${row.organizationId},
                $hostId,
                '${escapeSql(row.host)}',
                '${escapeSql(row.containerId)}',
                '${escapeSql(row.name)}',
                '${escapeSql(row.image)}',
                '${escapeSql(row.state)}',
                ${row.cpuPercent},
                ${row.memUsage},
                ${row.memLimit},
                ${row.netRxBytes},
                ${row.netTxBytes},
                ${mapToSqlMap(row.tags)},
                fromUnixTimestamp64Milli(${row.timestampMs})
            )"""
        }
        val rollupRows = resolvedRows.joinToString(",\n") { (row, hostId) ->
            """(
                ${row.organizationId},
                $hostId,
                toStartOfMinute(fromUnixTimestamp64Milli(${row.timestampMs})),
                '${escapeSql(row.host)}',
                '${escapeSql(row.containerId)}',
                '${escapeSql(row.name)}',
                ${row.cpuPercent},
                1,
                ${row.memUsage},
                ${row.memLimit},
                ${row.netRxBytes},
                ${row.netTxBytes}
            )"""
        }

        executeBestEffort(
            """
            INSERT INTO `$db`.containers_latest_by_host (
                organization_id, host_id, host, container_id, name,
                image, state, cpu_percent, mem_usage, mem_limit,
                net_rx_bytes, net_tx_bytes, tags, timestamp
            ) VALUES
            $latestRows
            """.trimIndent(),
            "infra containers latest",
        )
        executeBestEffort(
            """
            INSERT INTO `$db`.containers_rollup_1m (
                organization_id, host_id, bucket_start, host, container_id,
                name, cpu_sum, cpu_count, mem_usage_sum, mem_limit_sum,
                net_rx_bytes_sum, net_tx_bytes_sum
            ) VALUES
            $rollupRows
            """.trimIndent(),
            "infra containers 1m rollup",
        )
    }

    private suspend fun executeBestEffort(query: String, label: String) {
        suspendRunCatching {
            val response = ClickHouseClient.execute(query)
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                logger.warn { "Failed to insert $label: ${body.take(ERROR_BODY_PREVIEW_CHARS)}" }
            }
        }.getOrElse { e ->
            logger.warn(e) { "Failed to insert $label" }
        }
    }

    private fun resolveHostId(tags: Map<String, String>): Long? =
        (tags["host_id"] ?: tags["system_id"] ?: tags["moneat.host_id"])
            ?.toLongOrNull()
            ?.takeIf { it > 0L }

    private fun mapToSqlMap(map: Map<String, String>): String {
        if (map.isEmpty()) return "map()"
        val entries = map.entries.joinToString(", ") { (key, value) ->
            "'${escapeSql(key)}', '${escapeSql(value)}'"
        }
        return "map($entries)"
    }

    private data class ResolvedMetricRollupRow(
        val row: InfraMetricRollupRow,
        val hostId: Long,
    )

    private data class ResolvedContainerRollupRow(
        val row: InfraContainerRollupRow,
        val hostId: Long,
    )

    private const val ERROR_BODY_PREVIEW_CHARS = 600
}
