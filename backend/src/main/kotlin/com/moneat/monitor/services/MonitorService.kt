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

import com.moneat.alerts.models.AlertPriority
import com.moneat.billing.models.PricingTier
import com.moneat.billing.models.PricingTierConfigResponse
import com.moneat.billing.services.PricingTierService
import com.moneat.config.ClickHouseClient
import com.moneat.config.EnvConfig
import com.moneat.monitor.models.AlertConfigResponse
import com.moneat.monitor.models.AlertResponse
import com.moneat.monitor.models.ContainerMetricDataPoint
import com.moneat.monitor.models.ContainerMetricsResponse
import com.moneat.monitor.models.ContainerStats
import com.moneat.monitor.models.ContainerWithSystem
import com.moneat.monitor.models.CreateAlertData
import com.moneat.monitor.models.CreateAlertRequest
import com.moneat.monitor.models.HistoricalMetricsResponse
import com.moneat.monitor.models.HostData
import com.moneat.monitor.models.LatestMetrics
import com.moneat.monitor.models.MetricDataPoint
import com.moneat.monitor.models.UpdateAlertData
import com.moneat.monitor.models.UpdateAlertRequest
import com.moneat.monitor.repositories.HostAlertRepository
import com.moneat.monitor.repositories.HostRepository
import com.moneat.shared.services.CacheService
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.shared.services.toUuidOrNull
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import com.moneat.utils.TimeConstants.MILLIS_PER_SECOND_LONG
import com.moneat.utils.suspendRunCatching
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

class MonitorService(
    private val hostRepository: HostRepository,
    private val hostAlertRepository: HostAlertRepository,
    private val pricingTierService: PricingTierService = PricingTierService(),
    private val retentionPolicyService: RetentionPolicyService = RetentionPolicyService(),
) {
    private fun clickHouseOrgClause(organizationId: Int): String =
        ClickHouseQueryUtils.orgIdClause(organizationId.toLong())

    private fun resolvedAlertPriority(request: CreateAlertRequest): String? =
        canonicalAlertPriority(
            request.alertPriority
                ?: request.alertPrioritySnake
                ?: request.incidentSeverity
                ?: request.legacyIncidentSeveritySnake
        )

    private fun resolvedAlertPriority(request: UpdateAlertRequest): String? =
        canonicalAlertPriority(
            request.alertPriority
                ?: request.alertPrioritySnake
                ?: request.incidentSeverity
                ?: request.legacyIncidentSeveritySnake
        )

    private fun canonicalAlertPriority(priority: String?): String? =
        priority?.let { AlertPriority.fromString(it)?.wire }

    private data class DefaultAlertTemplate(
        val metric: String,
        val condition: String,
        val threshold: Double,
        val durationSeconds: Int = 0,
        val enabled: Boolean = false
    )

    companion object {
        const val ALERT_SCOPE_GLOBAL = "global"
        const val ALERT_SCOPE_SYSTEM = "system"
        const val ALERT_SCOPE_HOST = "host"
        const val INFRA_LOOKBACK_DAYS = 7
        const val MONITOR_HISTORY_CACHE_TTL_SECONDS = 30L

        private const val LATEST_METRICS_LOOKBACK_HOURS = 6
        private const val LATEST_METRIC_NAMES_SQL =
            "'system.cpu.percent','system.cpu.user','system.cpu.system','system.cpu.idle'," +
                "'system.mem.total','system.mem.used','system.mem.available'," +
                "'system.disk.percent','system.disk.total','system.disk.used'," +
                "'system.net.recv_bytes','system.net.sent_bytes'," +
                "'system.load.1','system.temp.max','system.gpu.percent','system.battery.percent'"

        // Time-range thresholds for historical downsampling (in seconds)
        private const val ONE_HOUR_SECONDS = 3600L
        private const val SIX_HOURS_SECONDS = 21600L
        private const val ONE_DAY_SECONDS = 86400L
        private const val ONE_WEEK_SECONDS = 604800L

        // Interval step sizes returned by the downsampling selector (in seconds)
        private const val INTERVAL_TEN_SECONDS = 10
        private const val INTERVAL_ONE_MINUTE = 60
        private const val INTERVAL_FIVE_MINUTES = 300
        private const val INTERVAL_THIRTY_MINUTES = 1800
        private const val INTERVAL_ONE_HOUR = 3600

        // Container freshness: keep rows within (monitorInterval * multiplier) seconds,
        // but never less than the minimum window.
        private const val FRESHNESS_MONITOR_MULTIPLIER = 3
        private const val FRESHNESS_MIN_WINDOW_SECONDS = 300

        // JSONCompact column indices — single-host latest metrics query
        // SELECT: cpu_percent(0), mem_total(1), mem_used(2), mem_available(3),
        //         disk_total(4), disk_used(5), net_recv_bytes(6), net_sent_bytes(7),
        //         load_1(8), temp_max(9), gpu_percent(10), battery_percent(11), disk_percent(12)
        private const val LATEST_COL_MEM_AVAILABLE = 3
        private const val LATEST_COL_DISK_TOTAL = 4
        private const val LATEST_COL_DISK_USED = 5
        private const val LATEST_COL_NET_RECV_BYTES = 6
        private const val LATEST_COL_NET_SENT_BYTES = 7
        private const val LATEST_COL_LOAD_1 = 8
        private const val LATEST_COL_TEMP_MAX = 9
        private const val LATEST_COL_GPU_PERCENT = 10
        private const val LATEST_COL_BATTERY_PERCENT = 11
        private const val LATEST_COL_DISK_PERCENT = 12

        // JSONCompact column indices — multi-host batch latest metrics query
        // SELECT: host_id(0), cpu_percent(1), mem_total(2), mem_used(3),
        //         mem_available(4), disk_total(5), disk_used(6), net_recv_bytes(7),
        //         net_sent_bytes(8), load_1(9), temp_max(10), gpu_percent(11),
        //         battery_percent(12), disk_percent(13)
        private const val BATCH_COL_MEM_USED = 3
        private const val BATCH_COL_MEM_AVAILABLE = 4
        private const val BATCH_COL_DISK_TOTAL = 5
        private const val BATCH_COL_DISK_USED = 6
        private const val BATCH_COL_NET_RECV_BYTES = 7
        private const val BATCH_COL_NET_SENT_BYTES = 8
        private const val BATCH_COL_LOAD_1 = 9
        private const val BATCH_COL_TEMP_MAX = 10
        private const val BATCH_COL_GPU_PERCENT = 11
        private const val BATCH_COL_BATTERY_PERCENT = 12
        private const val BATCH_COL_DISK_PERCENT = 13

        // JSONCompact column indices — historical metrics query
        // SELECT: ts(0), cpu(1), mem(2), disk(3), net_recv(4), net_sent(5),
        //         load1(6), load5(7), load15(8), temp(9), gpu(10), battery(11)
        private const val HIST_COL_DISK_PERCENT = 3
        private const val HIST_COL_NET_RECV_BYTES = 4
        private const val HIST_COL_NET_SENT_BYTES = 5
        private const val HIST_COL_LOAD_1 = 6
        private const val HIST_COL_LOAD_5 = 7
        private const val HIST_COL_LOAD_15 = 8
        private const val HIST_COL_TEMP_MAX = 9
        private const val HIST_COL_GPU_PERCENT = 10
        private const val HIST_COL_BATTERY_PERCENT = 11

        // JSONCompact column indices — single-host container stats query
        // SELECT: name(0), container_id(1), image(2), state(3), cpu_percent(4),
        //         mem_usage(5), mem_limit(6), net_rx_bytes(7), net_tx_bytes(8)
        private const val CONTAINER_COL_MEM_USAGE = 5
        private const val CONTAINER_COL_MEM_LIMIT = 6
        private const val CONTAINER_COL_NET_RX_BYTES = 7
        private const val CONTAINER_COL_NET_TX_BYTES = 8

        // JSONCompact column indices — infra containers query
        // SELECT: host(0), container_id(1), name(2), image(3), state(4), cpu_percent(5),
        //         mem_usage(6), mem_limit(7), net_rx_bytes(8), net_tx_bytes(9),
        //         tags(10), timestamp(11)
        private const val INFRA_COL_IMAGE = 3
        private const val INFRA_COL_STATE = 4
        private const val INFRA_COL_CPU_PERCENT = 5
        private const val INFRA_COL_MEM_USAGE = 6
        private const val INFRA_COL_MEM_LIMIT = 7
        private const val INFRA_COL_NET_RX_BYTES = 8
        private const val INFRA_COL_NET_TX_BYTES = 9
        private const val INFRA_COL_TAGS = 10
        private const val INFRA_COL_TIMESTAMP = 11

        // JSONCompact column indices — container historical metrics query
        // SELECT: ts(0), cpu(1), mem_used(2), mem_limit(3), net_recv(4), net_sent(5)
        private const val CONT_HIST_COL_MEM_LIMIT = 3
        private const val CONT_HIST_COL_NET_RECV = 4
        private const val CONT_HIST_COL_NET_SENT = 5

        private const val PERCENT_MULTIPLIER = 100
        private const val DATETIME64_MILLIS_PRECISION = 3
    }

    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val defaultAlertTemplates =
        listOf(
            DefaultAlertTemplate(metric = "cpu_percent", condition = ">", threshold = 80.0),
            DefaultAlertTemplate(metric = "mem_percent", condition = ">", threshold = 80.0),
            DefaultAlertTemplate(metric = "disk_percent", condition = ">", threshold = 80.0),
            DefaultAlertTemplate(metric = "load_1", condition = ">", threshold = 4.0),
            DefaultAlertTemplate(metric = "temp_max", condition = ">", threshold = 85.0),
            DefaultAlertTemplate(metric = "gpu_percent", condition = ">", threshold = 85.0),
            DefaultAlertTemplate(metric = "battery_percent", condition = "<=", threshold = 20.0)
        )

    /**
     * Check if organization can add more hosts.
     */
    fun checkHostQuota(organizationId: Int): Boolean {
        if (EnvConfig.SelfHost.enabled) return true
        val tier = getTierConfig(organizationId)
        val maxHosts = tier.maxHosts ?: Int.MAX_VALUE
        val currentCount = hostRepository.getHostCountForOrganization(organizationId)
        return currentCount < maxHosts
    }

    /**
     * List all hosts for an organization.
     */
    fun listHosts(organizationId: Int): List<HostData> =
        hostRepository.listByOrganizationId(organizationId)

    /**
     * Get a single host by ID.
     */
    fun getHostById(hostId: Int): HostData? =
        hostRepository.getById(hostId)

    fun getHostByResourceId(
        resourceId: Uuid,
        organizationIds: List<Int>
    ): HostData? =
        hostRepository.getByResourceId(resourceId, organizationIds)

    /**
     * Delete a host and all its metrics.
     */
    suspend fun deleteHost(
        hostId: Int,
        organizationId: Int
    ): Boolean {
        // Delete telemetry from ClickHouse before removing the host row
        val orgClause = clickHouseOrgClause(organizationId)
        val metricsDelete =
            "ALTER TABLE `$clickhouseDb`.metrics DELETE WHERE $orgClause" +
                " AND tags['host_id'] = '$hostId'"
        val containersDelete =
            "ALTER TABLE `$clickhouseDb`.containers DELETE WHERE $orgClause" +
                " AND tags['host_id'] = '$hostId'"
        val metricsLatestDelete =
            "ALTER TABLE `$clickhouseDb`.metrics_latest_by_host DELETE WHERE $orgClause" +
                " AND host_id = $hostId"
        val metricsRollupDelete =
            "ALTER TABLE `$clickhouseDb`.metrics_rollup_1m DELETE WHERE $orgClause" +
                " AND host_id = $hostId"
        val containersLatestDelete =
            "ALTER TABLE `$clickhouseDb`.containers_latest_by_host DELETE WHERE $orgClause" +
                " AND host_id = $hostId"
        val containersRollupDelete =
            "ALTER TABLE `$clickhouseDb`.containers_rollup_1m DELETE WHERE $orgClause" +
                " AND host_id = $hostId"

        if (!hostRepository.deleteClickHouseData(metricsDelete)) {
            logger.error { "Failed to delete ClickHouse metrics for hostId=$hostId" }
            throw Exception("Failed to delete host telemetry (metrics)")
        }
        if (!hostRepository.deleteClickHouseData(containersDelete)) {
            logger.error { "Failed to delete ClickHouse containers for hostId=$hostId" }
            throw Exception("Failed to delete host telemetry (containers)")
        }
        if (!hostRepository.deleteClickHouseData(metricsLatestDelete)) {
            logger.error { "Failed to delete ClickHouse latest metrics for hostId=$hostId" }
            throw Exception("Failed to delete host telemetry (metrics latest)")
        }
        if (!hostRepository.deleteClickHouseData(metricsRollupDelete)) {
            logger.error { "Failed to delete ClickHouse metrics rollups for hostId=$hostId" }
            throw Exception("Failed to delete host telemetry (metrics rollup)")
        }
        if (!hostRepository.deleteClickHouseData(containersLatestDelete)) {
            logger.error { "Failed to delete ClickHouse latest containers for hostId=$hostId" }
            throw Exception("Failed to delete host telemetry (containers latest)")
        }
        if (!hostRepository.deleteClickHouseData(containersRollupDelete)) {
            logger.error { "Failed to delete ClickHouse container rollups for hostId=$hostId" }
            throw Exception("Failed to delete host telemetry (containers rollup)")
        }

        return hostRepository.delete(hostId, organizationId)
    }

    /**
     * Get latest metrics for a host from ClickHouse metrics table.
     */
    suspend fun getLatestMetrics(hostId: Int): LatestMetrics? {
        val host = getHostById(hostId) ?: return null
        val query =
            """
            SELECT
                coalesce(
                    argMax(CASE WHEN metric_name='system.cpu.percent' THEN value END, timestamp),
                    if(
                        countIf(metric_name IN ('system.cpu.user', 'system.cpu.system')) > 0,
                        least(
                            coalesce(argMax(CASE WHEN metric_name='system.cpu.user' THEN value END, timestamp), 0) +
                                coalesce(
                                    argMax(CASE WHEN metric_name='system.cpu.system' THEN value END, timestamp),
                                    0
                                ),
                            100
                        ),
                        NULL
                    )
                ) as cpu_percent,
                argMax(CASE WHEN metric_name='system.mem.total' THEN value END, timestamp) as mem_total,
                argMax(CASE WHEN metric_name='system.mem.used' THEN value END, timestamp) as mem_used,
                argMax(CASE WHEN metric_name='system.mem.available' THEN value END, timestamp) as mem_available,
                argMax(CASE WHEN metric_name='system.disk.total' THEN value END, timestamp) as disk_total,
                argMax(CASE WHEN metric_name='system.disk.used' THEN value END, timestamp) as disk_used,
                argMax(CASE WHEN metric_name='system.net.recv_bytes' THEN value END, timestamp) as net_recv_bytes,
                argMax(CASE WHEN metric_name='system.net.sent_bytes' THEN value END, timestamp) as net_sent_bytes,
                argMax(CASE WHEN metric_name='system.load.1' THEN value END, timestamp) as load_1,
                argMax(CASE WHEN metric_name='system.temp.max' THEN value END, timestamp) as temp_max,
                argMax(CASE WHEN metric_name='system.gpu.percent' THEN value END, timestamp) as gpu_percent,
                argMax(CASE WHEN metric_name='system.battery.percent' THEN value END, timestamp) as battery_percent,
                argMax(CASE WHEN metric_name='system.disk.percent' THEN value END, timestamp) as disk_percent
            FROM `$clickhouseDb`.metrics_latest_by_host
            WHERE ${clickHouseOrgClause(host.organizationId)}
              AND host_id = $hostId
              AND metric_name IN ($LATEST_METRIC_NAMES_SQL)
              AND timestamp >= now64(3) - INTERVAL $LATEST_METRICS_LOOKBACK_HOURS HOUR
            FORMAT JSONCompact
            """.trimIndent()

        val body = hostRepository.executeClickHouseQuery(query)
        if (body.isBlank()) return null

        suspendRunCatching {
            val json = Json { ignoreUnknownKeys = true }
            val result = json.parseToJsonElement(body).jsonObject
            val data = result["data"]?.jsonArray?.firstOrNull()?.jsonArray ?: return null

            val cpuPercent = data.getOrNull(0)?.toString()?.toFloatOrNull() ?: 0f
            val memTotal =
                data
                    .getOrNull(1)
                    ?.toString()
                    ?.replace("\"", "")
                    ?.toLongOrNull() ?: 0
            val memUsed =
                data
                    .getOrNull(2)
                    ?.toString()
                    ?.replace("\"", "")
                    ?.toLongOrNull() ?: 0
            val memAvailable =
                data
                    .getOrNull(LATEST_COL_MEM_AVAILABLE)
                    ?.toString()
                    ?.replace("\"", "")
                    ?.toLongOrNull() ?: 0
            val diskTotal =
                data
                    .getOrNull(LATEST_COL_DISK_TOTAL)
                    ?.toString()
                    ?.replace("\"", "")
                    ?.toLongOrNull() ?: 0
            val diskUsed =
                data
                    .getOrNull(LATEST_COL_DISK_USED)
                    ?.toString()
                    ?.replace("\"", "")
                    ?.toLongOrNull() ?: 0
            val netRecvBytes =
                data
                    .getOrNull(LATEST_COL_NET_RECV_BYTES)
                    ?.toString()
                    ?.replace("\"", "")
                    ?.toLongOrNull() ?: 0
            val netSentBytes =
                data
                    .getOrNull(LATEST_COL_NET_SENT_BYTES)
                    ?.toString()
                    ?.replace("\"", "")
                    ?.toLongOrNull() ?: 0
            val load1 = data.getOrNull(LATEST_COL_LOAD_1)?.toString()?.toFloatOrNull() ?: 0f
            val tempMax = data.getOrNull(LATEST_COL_TEMP_MAX)?.toString()?.toFloatOrNull()
            val gpuPercent = data.getOrNull(LATEST_COL_GPU_PERCENT)?.toString()?.toFloatOrNull()
            val batteryPercent = data.getOrNull(LATEST_COL_BATTERY_PERCENT)?.toString()?.toFloatOrNull()
            val diskPercentMetric = data.getOrNull(LATEST_COL_DISK_PERCENT)?.toString()?.toFloatOrNull()

            val effectiveMemUsed = if (memAvailable > 0) memTotal - memAvailable else memUsed
            val memPercent = percent(effectiveMemUsed, memTotal)
            val diskPercent = diskPercent(diskPercentMetric, diskUsed, diskTotal)
            return LatestMetrics(
                cpuPercent = cpuPercent,
                memTotal = memTotal,
                memUsed = effectiveMemUsed,
                memPercent = memPercent,
                diskTotal = diskTotal,
                diskUsed = diskUsed,
                diskPercent = diskPercent,
                netRecvBytes = netRecvBytes,
                netSentBytes = netSentBytes,
                netRecvMbps = null,
                netSentMbps = null,
                load1 = load1,
                tempMax = tempMax,
                gpuPercent = gpuPercent,
                batteryPercent = batteryPercent
            )
        }.getOrElse { e ->
            logger.warn(e) { "Failed to parse latest metrics response" }
            return null
        }
    }

    /**
     * Get latest metrics for multiple hosts in a single ClickHouse query (avoids N+1).
     * Returns a map from hostId to LatestMetrics (null if no data for that host).
     */
    suspend fun getLatestMetricsForHosts(
        hostIds: List<Int>,
        organizationId: Int,
        demoEpochMs: Long? = null,
    ): Map<Int, LatestMetrics?> {
        if (hostIds.isEmpty()) return emptyMap()
        val hostIdList = hostIds.joinToString(",")
        val freshnessNow = latestMetricsNowClause(demoEpochMs)
        val query =
            """
            SELECT
                toInt32(host_id) as host_id,
                coalesce(
                    argMax(CASE WHEN metric_name='system.cpu.percent' THEN value END, timestamp),
                    if(
                        countIf(metric_name IN ('system.cpu.user', 'system.cpu.system')) > 0,
                        least(
                            coalesce(argMax(CASE WHEN metric_name='system.cpu.user' THEN value END, timestamp), 0) +
                                coalesce(
                                    argMax(CASE WHEN metric_name='system.cpu.system' THEN value END, timestamp),
                                    0
                                ),
                            100
                        ),
                        NULL
                    )
                ) as cpu_percent,
                argMax(CASE WHEN metric_name='system.mem.total' THEN value END, timestamp) as mem_total,
                argMax(CASE WHEN metric_name='system.mem.used' THEN value END, timestamp) as mem_used,
                argMax(CASE WHEN metric_name='system.mem.available' THEN value END, timestamp) as mem_available,
                argMax(CASE WHEN metric_name='system.disk.total' THEN value END, timestamp) as disk_total,
                argMax(CASE WHEN metric_name='system.disk.used' THEN value END, timestamp) as disk_used,
                argMax(CASE WHEN metric_name='system.net.recv_bytes' THEN value END, timestamp) as net_recv_bytes,
                argMax(CASE WHEN metric_name='system.net.sent_bytes' THEN value END, timestamp) as net_sent_bytes,
                argMax(CASE WHEN metric_name='system.load.1' THEN value END, timestamp) as load_1,
                argMax(CASE WHEN metric_name='system.temp.max' THEN value END, timestamp) as temp_max,
                argMax(CASE WHEN metric_name='system.gpu.percent' THEN value END, timestamp) as gpu_percent,
                argMax(CASE WHEN metric_name='system.battery.percent' THEN value END, timestamp) as battery_percent,
                argMax(CASE WHEN metric_name='system.disk.percent' THEN value END, timestamp) as disk_percent
            FROM `$clickhouseDb`.metrics_latest_by_host
            WHERE ${clickHouseOrgClause(organizationId)}
              AND host_id IN ($hostIdList)
              AND metric_name IN ($LATEST_METRIC_NAMES_SQL)
              AND timestamp >= $freshnessNow - INTERVAL $LATEST_METRICS_LOOKBACK_HOURS HOUR
            GROUP BY host_id
            FORMAT JSONCompact
            """.trimIndent()

        val body = hostRepository.executeClickHouseQuery(query)
        if (body.isBlank()) return hostIds.associateWith { null }

        return suspendRunCatching {
            val json = Json { ignoreUnknownKeys = true }
            val rows = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                ?: return hostIds.associateWith { null }
            val result = mutableMapOf<Int, LatestMetrics?>()
            for (row in rows) {
                latestMetricsFromBatchRow(row.jsonArray)?.let { (hostId, metrics) ->
                    result[hostId] = metrics
                }
            }
            hostIds.associateWith { result[it] }
        }.getOrElse { e ->
            logger.warn(e) { "Failed to parse batch latest metrics response" }
            hostIds.associateWith { null }
        }
    }

    private fun latestMetricsFromBatchRow(arr: JsonArray): Pair<Int, LatestMetrics>? {
        val rowHostId = arr.stringAt(0).toIntOrNull() ?: return null
        val memTotal = arr.longAt(2)
        val memUsed = arr.longAt(BATCH_COL_MEM_USED)
        val memAvailable = arr.longAt(BATCH_COL_MEM_AVAILABLE)
        val diskTotal = arr.longAt(BATCH_COL_DISK_TOTAL)
        val diskUsed = arr.longAt(BATCH_COL_DISK_USED)
        val diskPercentMetric = arr.floatAt(BATCH_COL_DISK_PERCENT)
        val effectiveMemUsed = if (memAvailable > 0) memTotal - memAvailable else memUsed
        return rowHostId to LatestMetrics(
            cpuPercent = arr.floatAt(1) ?: 0f,
            memTotal = memTotal,
            memUsed = effectiveMemUsed,
            memPercent = percent(effectiveMemUsed, memTotal),
            diskTotal = diskTotal,
            diskUsed = diskUsed,
            diskPercent = diskPercent(diskPercentMetric, diskUsed, diskTotal),
            netRecvBytes = arr.longAt(BATCH_COL_NET_RECV_BYTES),
            netSentBytes = arr.longAt(BATCH_COL_NET_SENT_BYTES),
            netRecvMbps = null,
            netSentMbps = null,
            load1 = arr.floatAt(BATCH_COL_LOAD_1) ?: 0f,
            tempMax = arr.floatAt(BATCH_COL_TEMP_MAX),
            gpuPercent = arr.floatAt(BATCH_COL_GPU_PERCENT),
            batteryPercent = arr.floatAt(BATCH_COL_BATTERY_PERCENT),
        )
    }

    private fun JsonArray.stringAt(index: Int): String =
        getOrNull(index)?.toString()?.replace("\"", "") ?: ""

    private fun JsonArray.longAt(index: Int): Long =
        stringAt(index).toLongOrNull() ?: 0L

    private fun JsonArray.floatAt(index: Int): Float? =
        stringAt(index).toFloatOrNull()

    private fun percent(value: Long, total: Long): Float =
        if (total > 0L) value.toFloat() / total * PERCENT_MULTIPLIER else 0f

    private fun diskPercent(diskPercent: Float?, used: Long, total: Long): Float =
        diskPercent ?: percent(used, total)

    private fun latestMetricsNowClause(demoEpochMs: Long?): String =
        if (demoEpochMs != null) {
            "toDateTime64(${formatEpochSeconds(demoEpochMs)}, $DATETIME64_MILLIS_PRECISION)"
        } else {
            "now64($DATETIME64_MILLIS_PRECISION)"
        }

    private fun formatEpochSeconds(epochMs: Long): String {
        val seconds = epochMs / MILLIS_PER_SECOND_LONG
        val millis = epochMs % MILLIS_PER_SECOND_LONG
        return "$seconds.${millis.toString().padStart(DATETIME64_MILLIS_PRECISION, '0')}"
    }

    /**
     * Get historical metrics with optional downsampling.
     */
    suspend fun getHistoricalMetrics(
        hostId: Int,
        fromTimestamp: Long,
        toTimestamp: Long,
        intervalSeconds: Int?
    ): HistoricalMetricsResponse =
        CacheService.cached(
            "cache:monitor_hist:$hostId:$fromTimestamp:$toTimestamp:$intervalSeconds",
            MONITOR_HISTORY_CACHE_TTL_SECONDS
        ) {
            val host = getHostById(hostId) ?: return@cached HistoricalMetricsResponse(
                systemId = "",
                hostId = null,
                from = fromTimestamp,
                to = toTimestamp,
                intervalSeconds = intervalSeconds ?: 3600,
                dataPoints = emptyList()
            )
            val clampedWindow = clampRangeToRetention(hostId, fromTimestamp, toTimestamp)
            if (clampedWindow == null) {
                return@cached HistoricalMetricsResponse(
                    systemId = "",
                    hostId = host.resourceId.toString(),
                    from = fromTimestamp,
                    to = toTimestamp,
                    intervalSeconds = intervalSeconds ?: 3600,
                    dataPoints = emptyList()
                )
            }
            val (effectiveFrom, effectiveTo) = clampedWindow

            // Auto-calculate interval if not provided
            val timeRange = effectiveTo - effectiveFrom
            val calculatedInterval =
                intervalSeconds ?: when {
                    timeRange <= ONE_HOUR_SECONDS -> INTERVAL_TEN_SECONDS
                    timeRange <= SIX_HOURS_SECONDS -> INTERVAL_ONE_MINUTE
                    timeRange <= ONE_DAY_SECONDS -> INTERVAL_FIVE_MINUTES
                    timeRange <= ONE_WEEK_SECONDS -> INTERVAL_THIRTY_MINUTES
                    else -> INTERVAL_ONE_HOUR
                }
            val rollupInterval = max(calculatedInterval, INTERVAL_ONE_MINUTE)

            val query =
                """
            SELECT
                toUnixTimestamp(toStartOfInterval(bucket_start, INTERVAL $rollupInterval second)) as ts,
                coalesce(
                    sumIf(value_sum, metric_name='system.cpu.percent') /
                        nullIf(sumIf(value_count, metric_name='system.cpu.percent'), 0),
                    if(
                        sumIf(value_count, metric_name IN ('system.cpu.user', 'system.cpu.system')) > 0,
                        least(
                            coalesce(
                                sumIf(value_sum, metric_name='system.cpu.user') /
                                    nullIf(sumIf(value_count, metric_name='system.cpu.user'), 0),
                                0
                            ) +
                                coalesce(
                                    sumIf(value_sum, metric_name='system.cpu.system') /
                                        nullIf(sumIf(value_count, metric_name='system.cpu.system'), 0),
                                    0
                                ),
                            100
                        ),
                        NULL
                    )
                ) as cpu,
                coalesce(
                    (1 - (
                        sumIf(value_sum, metric_name='system.mem.available') /
                        nullIf(sumIf(value_count, metric_name='system.mem.available'), 0)
                    ) / nullIf(
                        sumIf(value_sum, metric_name='system.mem.total') /
                        nullIf(sumIf(value_count, metric_name='system.mem.total'), 0),
                        0
                    )) * 100,
                    (
                        sumIf(value_sum, metric_name='system.mem.used') /
                        nullIf(sumIf(value_count, metric_name='system.mem.used'), 0)
                    ) / nullIf(
                        sumIf(value_sum, metric_name='system.mem.total') /
                        nullIf(sumIf(value_count, metric_name='system.mem.total'), 0),
                        0
                    ) * 100
                ) as mem,
                coalesce(
                    if(
                        sumIf(value_count, metric_name='system.disk.percent') > 0,
                        sumIf(value_sum, metric_name='system.disk.percent') /
                            nullIf(sumIf(value_count, metric_name='system.disk.percent'), 0),
                        NULL
                    ),
                    (
                        sumIf(value_sum, metric_name='system.disk.used') /
                        nullIf(sumIf(value_count, metric_name='system.disk.used'), 0)
                    ) / nullIf(
                        sumIf(value_sum, metric_name='system.disk.total') /
                        nullIf(sumIf(value_count, metric_name='system.disk.total'), 0),
                        0
                    ) * 100
                ) as disk,
                sumIf(value_sum, metric_name='system.net.recv_bytes') as net_recv,
                sumIf(value_sum, metric_name='system.net.sent_bytes') as net_sent,
                sumIf(value_sum, metric_name='system.load.1') /
                    nullIf(sumIf(value_count, metric_name='system.load.1'), 0) as load1,
                sumIf(value_sum, metric_name='system.load.5') /
                    nullIf(sumIf(value_count, metric_name='system.load.5'), 0) as load5,
                sumIf(value_sum, metric_name='system.load.15') /
                    nullIf(sumIf(value_count, metric_name='system.load.15'), 0) as load15,
                maxIf(value_sum / value_count, metric_name='system.temp.max') as temp,
                sumIf(value_sum, metric_name='system.gpu.percent') /
                    nullIf(sumIf(value_count, metric_name='system.gpu.percent'), 0) as gpu,
                sumIf(value_sum, metric_name='system.battery.percent') /
                    nullIf(sumIf(value_count, metric_name='system.battery.percent'), 0) as battery
            FROM `$clickhouseDb`.metrics_rollup_1m
            WHERE ${clickHouseOrgClause(host.organizationId)}
              AND host_id = $hostId
              AND bucket_start >= fromUnixTimestamp64Milli(${effectiveFrom * MILLIS_PER_SECOND_LONG})
              AND bucket_start <= fromUnixTimestamp64Milli(${effectiveTo * MILLIS_PER_SECOND_LONG})
            GROUP BY ts
            ORDER BY ts
            FORMAT JSONCompact
                """.trimIndent()

            val body = hostRepository.executeClickHouseQuery(query)
            val dataPoints =
                suspendRunCatching {
                    val json = Json { ignoreUnknownKeys = true }
                    val result = json.parseToJsonElement(body).jsonObject
                    val data =
                        result["data"]?.jsonArray ?: return@cached HistoricalMetricsResponse(
                            systemId = "",
                            hostId = host.resourceId.toString(),
                            from = effectiveFrom,
                            to = effectiveTo,
                            intervalSeconds = rollupInterval,
                            dataPoints = emptyList()
                        )

                    data.map { row ->
                        val arr = row.jsonArray
                        MetricDataPoint(
                            timestamp = arr[0].toString().replace("\"", "").toLong(),
                            cpuPercent = arr.getOrNull(1)?.toString()?.toFloatOrNull(),
                            memPercent = arr.getOrNull(2)?.toString()?.toFloatOrNull(),
                            diskPercent = arr.getOrNull(HIST_COL_DISK_PERCENT)?.toString()?.toFloatOrNull(),
                            netRecvBytes =
                            arr
                                .getOrNull(HIST_COL_NET_RECV_BYTES)
                                ?.toString()
                                ?.replace("\"", "")
                                ?.toLongOrNull(),
                            netSentBytes =
                            arr
                                .getOrNull(HIST_COL_NET_SENT_BYTES)
                                ?.toString()
                                ?.replace("\"", "")
                                ?.toLongOrNull(),
                            load1 = arr.getOrNull(HIST_COL_LOAD_1)?.toString()?.toFloatOrNull(),
                            load5 = arr.getOrNull(HIST_COL_LOAD_5)?.toString()?.toFloatOrNull(),
                            load15 = arr.getOrNull(HIST_COL_LOAD_15)?.toString()?.toFloatOrNull(),
                            tempMax = arr.getOrNull(HIST_COL_TEMP_MAX)?.toString()?.toFloatOrNull(),
                            gpuPercent = arr.getOrNull(HIST_COL_GPU_PERCENT)?.toString()?.toFloatOrNull(),
                            batteryPercent = arr.getOrNull(HIST_COL_BATTERY_PERCENT)?.toString()?.toFloatOrNull()
                        )
                    }
                }.getOrElse { e ->
                    logger.error(e) { "Failed to parse historical metrics" }
                    emptyList()
                }

            HistoricalMetricsResponse(
                systemId = host.resourceId.toString(),
                hostId = host.resourceId.toString(),
                from = effectiveFrom,
                to = effectiveTo,
                intervalSeconds = rollupInterval,
                dataPoints = dataPoints
            )
        }

    /**
     * Get latest container stats from ClickHouse containers table.
     */
    suspend fun getLatestContainers(hostId: Int): List<ContainerStats> {
        val host = getHostById(hostId) ?: return emptyList()
        val monitorIntervalSeconds = getTierConfig(host.organizationId).monitorIntervalSeconds
        val freshnessWindowSeconds = max(
            monitorIntervalSeconds * FRESHNESS_MONITOR_MULTIPLIER,
            FRESHNESS_MIN_WINDOW_SECONDS,
        )

        val query =
            """
            SELECT
                argMax(name, timestamp) as name,
                container_id,
                argMax(image, timestamp) as image,
                argMax(state, timestamp) as state,
                argMax(cpu_percent, timestamp) as cpu_percent,
                argMax(mem_usage, timestamp) as mem_usage,
                argMax(mem_limit, timestamp) as mem_limit,
                argMax(net_rx_bytes, timestamp) as net_rx_bytes,
                argMax(net_tx_bytes, timestamp) as net_tx_bytes
            FROM `$clickhouseDb`.containers_latest_by_host
            WHERE ${clickHouseOrgClause(host.organizationId)}
              AND host_id = $hostId
              AND timestamp >= now64(3) - INTERVAL $freshnessWindowSeconds SECOND
            GROUP BY container_id
            ORDER BY max(timestamp) DESC
            FORMAT JSONCompact
            """.trimIndent()

        val body = hostRepository.executeClickHouseQuery(query)
        if (body.isBlank()) return emptyList()

        return suspendRunCatching {
            val json = Json { ignoreUnknownKeys = true }
            val result = json.parseToJsonElement(body).jsonObject
            val data = result["data"]?.jsonArray ?: return emptyList()

            data.map { row ->
                val arr = row.jsonArray
                val memUsed = arr[CONTAINER_COL_MEM_USAGE].toString().replace("\"", "").toLongOrNull() ?: 0
                val memLimit = arr[CONTAINER_COL_MEM_LIMIT].toString().replace("\"", "").toLongOrNull() ?: 1
                val netRecvBytes =
                    arr.getOrNull(CONTAINER_COL_NET_RX_BYTES)?.toString()?.replace("\"", "")?.toLongOrNull() ?: 0
                val netSentBytes =
                    arr.getOrNull(CONTAINER_COL_NET_TX_BYTES)?.toString()?.replace("\"", "")?.toLongOrNull() ?: 0

                ContainerStats(
                    name = arr[0].toString().replace("\"", ""),
                    id = arr[1].toString().replace("\"", ""),
                    image = arr[2].toString().replace("\"", ""),
                    status = arr[3].toString().replace("\"", ""),
                    cpuPercent = arr[4].toString().toFloatOrNull() ?: 0f,
                    memUsed = memUsed,
                    memLimit = memLimit,
                    netRecvBytes = netRecvBytes,
                    netSentBytes = netSentBytes,
                    memPercent = if (memLimit > 0) (memUsed.toFloat() / memLimit * PERCENT_MULTIPLIER) else 0f
                )
            }
        }.getOrElse { e ->
            logger.error(e) { "Failed to parse container stats" }
            emptyList()
        }
    }

    /**
     * Get latest container stats from all hosts in the given organizations.
     */
    suspend fun getLatestContainersForOrganizations(organizationIds: List<Int>): List<ContainerWithSystem> {
        val allContainers = mutableListOf<ContainerWithSystem>()
        for (orgId in organizationIds) {
            val hosts = listHosts(orgId)
            for (host in hosts) {
                val containers = getLatestContainers(host.id)
                for (c in containers) {
                    allContainers.add(
                        ContainerWithSystem(
                            systemId = host.resourceId.toString(),
                            hostId = host.resourceId.toString(),
                            systemName = host.displayName ?: host.hostname,
                            name = c.name,
                            id = c.id,
                            image = c.image,
                            status = c.status,
                            cpuPercent = c.cpuPercent,
                            memUsed = c.memUsed,
                            memLimit = c.memLimit,
                            netRecvBytes = c.netRecvBytes,
                            netSentBytes = c.netSentBytes,
                            memPercent = c.memPercent
                        )
                    )
                }
            }
        }
        return allContainers
    }

    /**
     * Get latest container stats per host+container_id from the live latest table.
     */
    suspend fun getLatestInfraContainers(
        organizationIds: List<Int>,
        hostFilter: String?,
        limit: Int
    ): List<Map<String, Any?>> {
        if (organizationIds.isEmpty()) return emptyList()
        val orgList = organizationIds.joinToString(",") { it.toString() }
        val escapedHost = if (hostFilter != null && hostFilter.isNotBlank()) escapeSql(hostFilter) else null
        val hostClause = if (escapedHost != null) "AND host = '$escapedHost'" else ""
        val query =
            """
            SELECT
                host,
                container_id,
                argMax(name, timestamp) as name,
                argMax(image, timestamp) as image,
                argMax(state, timestamp) as state,
                argMax(cpu_percent, timestamp) as cpu_percent,
                argMax(mem_usage, timestamp) as mem_usage,
                argMax(mem_limit, timestamp) as mem_limit,
                argMax(net_rx_bytes, timestamp) as net_rx_bytes,
                argMax(net_tx_bytes, timestamp) as net_tx_bytes,
                argMax(tags, timestamp) as tags,
                max(timestamp) as timestamp
            FROM `$clickhouseDb`.containers_latest_by_host
            WHERE organization_id IN ($orgList)
              AND timestamp >= now64(3) - INTERVAL $INFRA_LOOKBACK_DAYS DAY
              $hostClause
            GROUP BY organization_id, host_id, host, container_id
            ORDER BY host, name
            LIMIT $limit
            FORMAT JSONCompact
            """.trimIndent()

        val body = hostRepository.executeClickHouseQuery(query)
        if (body.isBlank()) return emptyList()

        return suspendRunCatching {
            val json = Json { ignoreUnknownKeys = true }
            val result = json.parseToJsonElement(body).jsonObject
            val data = result["data"]?.jsonArray ?: return emptyList()

            data.map { row ->
                val arr = row.jsonArray
                val tagsObj = suspendRunCatching {
                    arr.getOrNull(INFRA_COL_TAGS)?.toString()?.let { t ->
                        json.parseToJsonElement(t.replace("\\\"", "\""))
                            .jsonObject
                            .entries
                            .associate { (k, v) -> k to v.toString().trim('"') }
                    } ?: emptyMap<String, String>()
                }.getOrElse { _ ->
                    emptyMap<String, String>()
                }
                val ts = arr.getOrNull(INFRA_COL_TIMESTAMP)?.toString()?.replace("\"", "") ?: ""

                mapOf(
                    "host" to arr.getOrNull(0)?.toString()?.replace("\"", ""),
                    "container_id" to arr.getOrNull(1)?.toString()?.replace("\"", ""),
                    "containerId" to arr.getOrNull(1)?.toString()?.replace("\"", ""),
                    "name" to arr.getOrNull(2)?.toString()?.replace("\"", ""),
                    "image" to arr.getOrNull(INFRA_COL_IMAGE)?.toString()?.replace("\"", ""),
                    "state" to arr.getOrNull(INFRA_COL_STATE)?.toString()?.replace("\"", ""),
                    "cpu_percent" to (arr.getOrNull(INFRA_COL_CPU_PERCENT)?.toString()?.toFloatOrNull() ?: 0f),
                    "cpuPercent" to (arr.getOrNull(INFRA_COL_CPU_PERCENT)?.toString()?.toFloatOrNull() ?: 0f),
                    "mem_usage" to (arr.getOrNull(INFRA_COL_MEM_USAGE)?.toString()?.toLongOrNull() ?: 0L),
                    "memUsage" to (arr.getOrNull(INFRA_COL_MEM_USAGE)?.toString()?.toLongOrNull() ?: 0L),
                    "mem_limit" to (arr.getOrNull(INFRA_COL_MEM_LIMIT)?.toString()?.toLongOrNull() ?: 0L),
                    "memLimit" to (arr.getOrNull(INFRA_COL_MEM_LIMIT)?.toString()?.toLongOrNull() ?: 0L),
                    "net_rx_bytes" to (arr.getOrNull(INFRA_COL_NET_RX_BYTES)?.toString()?.toLongOrNull() ?: 0L),
                    "netRxBytes" to (arr.getOrNull(INFRA_COL_NET_RX_BYTES)?.toString()?.toLongOrNull() ?: 0L),
                    "net_tx_bytes" to (arr.getOrNull(INFRA_COL_NET_TX_BYTES)?.toString()?.toLongOrNull() ?: 0L),
                    "netTxBytes" to (arr.getOrNull(INFRA_COL_NET_TX_BYTES)?.toString()?.toLongOrNull() ?: 0L),
                    "tags" to tagsObj,
                    "timestamp" to ts,
                    "id" to arr.getOrNull(1)?.toString()?.replace("\"", "")
                )
            }
        }.getOrElse { e ->
            logger.error(e) { "Failed to parse infra container stats" }
            emptyList()
        }
    }

    /**
     * Get historical metrics for a specific container.
     */
    suspend fun getContainerHistoricalMetrics(
        hostId: Int,
        containerName: String,
        fromTimestamp: Long,
        toTimestamp: Long,
        intervalSeconds: Int?
    ): ContainerMetricsResponse {
        val host = getHostById(hostId) ?: return ContainerMetricsResponse(
            containerName = containerName,
            from = fromTimestamp,
            to = toTimestamp,
            intervalSeconds = intervalSeconds ?: 3600,
            dataPoints = emptyList()
        )
        val clampedWindow = clampRangeToRetention(hostId, fromTimestamp, toTimestamp)
        if (clampedWindow == null) {
            return ContainerMetricsResponse(
                containerName = containerName,
                from = fromTimestamp,
                to = toTimestamp,
                intervalSeconds = intervalSeconds ?: 3600,
                dataPoints = emptyList()
            )
        }
        val (effectiveFrom, effectiveTo) = clampedWindow

        val timeRange = effectiveTo - effectiveFrom
        val calculatedInterval =
            intervalSeconds ?: when {
                timeRange <= ONE_HOUR_SECONDS -> INTERVAL_TEN_SECONDS
                timeRange <= SIX_HOURS_SECONDS -> INTERVAL_ONE_MINUTE
                timeRange <= ONE_DAY_SECONDS -> INTERVAL_FIVE_MINUTES
                timeRange <= ONE_WEEK_SECONDS -> INTERVAL_THIRTY_MINUTES
                else -> INTERVAL_ONE_HOUR
            }
        val rollupInterval = max(calculatedInterval, INTERVAL_ONE_MINUTE)

        val escapedName = escapeSql(containerName)
        val query =
            """
            SELECT
                toUnixTimestamp(toStartOfInterval(bucket_start, INTERVAL $rollupInterval second)) as ts,
                sum(cpu_sum) / nullIf(sum(cpu_count), 0) as cpu,
                sum(mem_usage_sum) / nullIf(sum(cpu_count), 0) as mem_used,
                sum(mem_limit_sum) / nullIf(sum(cpu_count), 0) as mem_limit,
                sum(net_rx_bytes_sum) as net_recv,
                sum(net_tx_bytes_sum) as net_sent
            FROM `$clickhouseDb`.containers_rollup_1m
            WHERE ${clickHouseOrgClause(host.organizationId)}
              AND host_id = $hostId
              AND name = '$escapedName'
              AND bucket_start >= fromUnixTimestamp64Milli(${effectiveFrom * MILLIS_PER_SECOND_LONG})
              AND bucket_start <= fromUnixTimestamp64Milli(${effectiveTo * MILLIS_PER_SECOND_LONG})
            GROUP BY ts
            ORDER BY ts
            FORMAT JSONCompact
            """.trimIndent()

        val body = hostRepository.executeClickHouseQuery(query)
        val dataPoints =
            suspendRunCatching {
                val json = Json { ignoreUnknownKeys = true }
                val result = json.parseToJsonElement(body).jsonObject
                val data =
                    result["data"]?.jsonArray ?: return ContainerMetricsResponse(
                        containerName = containerName,
                        from = effectiveFrom,
                        to = effectiveTo,
                        intervalSeconds = rollupInterval,
                        dataPoints = emptyList()
                    )

                data.map { row ->
                    val arr = row.jsonArray
                    ContainerMetricDataPoint(
                        timestamp = arr[0].toString().replace("\"", "").toLong(),
                        cpuPercent = arr.getOrNull(1)?.toString()?.toFloatOrNull(),
                        memUsed =
                        arr
                            .getOrNull(2)
                            ?.toString()
                            ?.replace("\"", "")
                            ?.toLongOrNull(),
                        memLimit =
                        arr
                            .getOrNull(CONT_HIST_COL_MEM_LIMIT)
                            ?.toString()
                            ?.replace("\"", "")
                            ?.toLongOrNull(),
                        netRecvBytes =
                        arr
                            .getOrNull(CONT_HIST_COL_NET_RECV)
                            ?.toString()
                            ?.replace("\"", "")
                            ?.toLongOrNull(),
                        netSentBytes =
                        arr
                            .getOrNull(CONT_HIST_COL_NET_SENT)
                            ?.toString()
                            ?.replace("\"", "")
                            ?.toLongOrNull()
                    )
                }
            }.getOrElse { e ->
                logger.error(e) { "Failed to parse container historical metrics" }
                emptyList()
            }

        return ContainerMetricsResponse(
            containerName = containerName,
            from = effectiveFrom,
            to = effectiveTo,
            intervalSeconds = rollupInterval,
            dataPoints = dataPoints
        )
    }

    /**
     * List all alerts for a host.
     */
    fun listAlerts(hostId: Int, organizationId: Int): List<AlertResponse> {
        return listHostAlerts(hostId, organizationId)
    }

    fun getAlertByInternalId(alertId: Int): AlertResponse? =
        hostAlertRepository.findAlertById(alertId.toLong())?.let { row ->
            AlertResponse(
                id = row.resourceId.toString(),
                hostId = hostRepository.getById(row.hostId)?.resourceId?.toString(),
                scope = row.scope,
                metric = row.metric,
                condition = row.condition,
                threshold = row.threshold,
                durationSeconds = row.durationSeconds,
                enabled = row.enabled,
                alertPriority = row.alertPriority,
                lastTriggeredAt = row.lastTriggeredAt?.toEpochMilliseconds(),
                createdAt = row.createdAt.toEpochMilliseconds()
            )
        }

    fun getAlertConfig(
        hostId: Int,
        organizationId: Int
    ): AlertConfigResponse {
        ensureOrganizationAlertTemplates(organizationId)
        ensureHostAlertsSeeded(hostId, organizationId)

        val scope = getHostAlertScope(hostId, organizationId)
        val globalAlerts = listGlobalAlertsForHost(hostId, organizationId)
        val hostAlerts = listHostAlerts(hostId, organizationId)
        val effectiveAlerts = if (scope == ALERT_SCOPE_GLOBAL) globalAlerts else hostAlerts

        return AlertConfigResponse(
            scope = scope,
            globalAlerts = globalAlerts,
            systemAlerts = hostAlerts,
            effectiveAlerts = effectiveAlerts
        )
    }

    fun updateAlertScope(
        hostId: Int,
        organizationId: Int,
        scope: String
    ): Boolean {
        if (!isValidAlertScope(scope)) {
            return false
        }
        // Normalize legacy "system" value to "host" before persisting
        val normalizedScope = if (scope == ALERT_SCOPE_SYSTEM) ALERT_SCOPE_HOST else scope
        ensureOrganizationAlertTemplates(organizationId)
        ensureHostAlertsSeeded(hostId, organizationId)
        hostAlertRepository.upsertAlertSettings(hostId, organizationId, normalizedScope)
        return true
    }

    /**
     * Create an alert for a host.
     */
    fun createAlert(
        hostId: Int,
        organizationId: Int,
        request: CreateAlertRequest,
        scope: String = ALERT_SCOPE_HOST
    ): AlertResponse {
        if (scope == ALERT_SCOPE_GLOBAL) {
            ensureOrganizationAlertTemplates(organizationId)
            val now = Clock.System.now()
            val alertPriority = resolvedAlertPriority(request)
            val alertResourceId = hostAlertRepository.createAlert(
                CreateAlertData(
                    hostId = hostId,
                    organizationId = organizationId,
                    metric = request.metric,
                    condition = request.condition,
                    threshold = request.threshold,
                    durationSeconds = request.durationSeconds,
                    enabled = request.enabled,
                    alertPriority = alertPriority,
                    scope = ALERT_SCOPE_GLOBAL
                )
            )
            return AlertResponse(
                id = alertResourceId.toString(),
                hostId = hostRepository.getById(hostId)?.resourceId?.toString(),
                scope = ALERT_SCOPE_GLOBAL,
                metric = request.metric,
                condition = request.condition,
                threshold = request.threshold,
                durationSeconds = request.durationSeconds,
                enabled = request.enabled,
                alertPriority = alertPriority,
                lastTriggeredAt = null,
                createdAt = now.toEpochMilliseconds()
            )
        }

        ensureHostAlertsSeeded(hostId, organizationId)
        val now = Clock.System.now()
        val alertPriority = resolvedAlertPriority(request)
        val alertResourceId = hostAlertRepository.createAlert(
            CreateAlertData(
                hostId = hostId,
                organizationId = organizationId,
                metric = request.metric,
                condition = request.condition,
                threshold = request.threshold,
                durationSeconds = request.durationSeconds,
                enabled = request.enabled,
                alertPriority = alertPriority,
                scope = ALERT_SCOPE_HOST
            )
        )
        return AlertResponse(
            id = alertResourceId.toString(),
            hostId = hostRepository.getById(hostId)?.resourceId?.toString(),
            scope = ALERT_SCOPE_HOST,
            metric = request.metric,
            condition = request.condition,
            threshold = request.threshold,
            durationSeconds = request.durationSeconds,
            enabled = request.enabled,
            alertPriority = alertPriority,
            lastTriggeredAt = null,
            createdAt = now.toEpochMilliseconds()
        )
    }

    /**
     * Update an alert.
     */
    fun updateAlert(
        alertId: String,
        hostId: Int,
        organizationId: Int,
        request: UpdateAlertRequest,
        scope: String = ALERT_SCOPE_HOST
    ): Boolean {
        val alertResourceId = parseResourceId(alertId) ?: return false
        val internalAlertId = resolveAlertId(alertResourceId, hostId, organizationId, scope) ?: return false
        return updateAlert(internalAlertId, hostId, organizationId, request, scope)
    }

    fun updateAlert(
        alertId: Int,
        hostId: Int,
        organizationId: Int,
        request: UpdateAlertRequest,
        scope: String = ALERT_SCOPE_HOST
    ): Boolean =
        hostAlertRepository.updateAlert(
            alertId.toLong(),
            hostId,
            organizationId,
            UpdateAlertData(
                metric = request.metric,
                condition = request.condition,
                threshold = request.threshold,
                durationSeconds = request.durationSeconds,
                enabled = request.enabled,
                alertPriority = resolvedAlertPriority(request)
            ),
            scope
        )

    /**
     * Delete an alert.
     */
    fun deleteAlert(
        alertId: String,
        hostId: Int,
        organizationId: Int,
        scope: String = ALERT_SCOPE_HOST
    ): Boolean {
        val alertResourceId = parseResourceId(alertId) ?: return false
        val internalAlertId = resolveAlertId(alertResourceId, hostId, organizationId, scope) ?: return false
        return deleteAlert(internalAlertId, hostId, organizationId, scope)
    }

    fun deleteAlert(
        alertId: Int,
        hostId: Int,
        organizationId: Int,
        scope: String = ALERT_SCOPE_HOST
    ): Boolean =
        hostAlertRepository.deleteAlert(alertId.toLong(), hostId, organizationId, scope)

    fun resolveAlertId(
        alertResourceId: Uuid,
        hostId: Int,
        organizationId: Int,
        scope: String
    ): Int? =
        hostAlertRepository.resolveAlertId(alertResourceId, hostId, organizationId, scope)

    private fun parseResourceId(value: String): Uuid? =
        value.toUuidOrNull()

    // Helper functions

    private fun isValidAlertScope(scope: String): Boolean {
        return scope == ALERT_SCOPE_GLOBAL || scope == ALERT_SCOPE_SYSTEM || scope == ALERT_SCOPE_HOST
    }

    internal fun ensureOrganizationAlertTemplates(organizationId: Int) {
        val existing = hostAlertRepository.listGlobalAlertsForHost(organizationId, -1)
        if (existing.isNotEmpty()) return

        defaultAlertTemplates.forEach { template ->
            hostAlertRepository.createAlert(
                CreateAlertData(
                    hostId = 0,
                    organizationId = organizationId,
                    metric = template.metric,
                    condition = template.condition,
                    threshold = template.threshold,
                    durationSeconds = template.durationSeconds,
                    enabled = template.enabled,
                    alertPriority = null,
                    scope = ALERT_SCOPE_GLOBAL
                )
            )
        }
    }

    internal fun ensureHostAlertsSeeded(
        hostId: Int,
        organizationId: Int
    ) {
        val existingAlerts = hostAlertRepository.listByHostAndOrg(hostId, organizationId)
        if (existingAlerts.isNotEmpty()) return

        val templates = hostAlertRepository.listGlobalAlertsForHost(organizationId, hostId)
        val sources: List<CreateAlertData> = if (templates.isEmpty()) {
            defaultAlertTemplates.map { template ->
                CreateAlertData(
                    hostId = hostId,
                    organizationId = organizationId,
                    metric = template.metric,
                    condition = template.condition,
                    threshold = template.threshold,
                    durationSeconds = template.durationSeconds,
                    enabled = template.enabled,
                    alertPriority = null,
                    scope = ALERT_SCOPE_HOST
                )
            }
        } else {
            templates.map { template ->
                CreateAlertData(
                    hostId = hostId,
                    organizationId = organizationId,
                    metric = template.metric,
                    condition = template.condition,
                    threshold = template.threshold,
                    durationSeconds = template.durationSeconds,
                    enabled = template.enabled,
                    alertPriority = template.alertPriority,
                    scope = ALERT_SCOPE_HOST
                )
            }
        }
        sources.forEach { hostAlertRepository.createAlert(it) }
    }

    private fun getHostAlertScope(
        hostId: Int,
        organizationId: Int
    ): String {
        val settings = hostAlertRepository.getAlertSettings(hostId)
        val existing = settings.firstOrNull { it.organizationId == organizationId }
        if (existing != null) return existing.scope
        hostAlertRepository.upsertAlertSettings(hostId, organizationId, ALERT_SCOPE_HOST)
        return ALERT_SCOPE_HOST
    }

    private fun listHostAlerts(hostId: Int, organizationId: Int): List<AlertResponse> =
        hostAlertRepository.listByHostAndOrg(hostId, organizationId).let { rows ->
            val hostResourceId = hostRepository.getById(hostId)?.resourceId?.toString()
            rows.map { row ->
                AlertResponse(
                    id = row.resourceId.toString(),
                    hostId = hostResourceId,
                    scope = ALERT_SCOPE_HOST,
                    metric = row.metric,
                    condition = row.condition,
                    threshold = row.threshold,
                    durationSeconds = row.durationSeconds,
                    enabled = row.enabled,
                    alertPriority = row.alertPriority,
                    lastTriggeredAt = row.lastTriggeredAt?.toEpochMilliseconds(),
                    createdAt = row.createdAt.toEpochMilliseconds()
                )
            }
        }

    private fun listGlobalAlertsForHost(
        hostId: Int,
        organizationId: Int
    ): List<AlertResponse> =
        hostAlertRepository.listGlobalAlertsForHost(organizationId, hostId).let { rows ->
            val hostResourceId = hostRepository.getById(hostId)?.resourceId?.toString()
            rows.map { row ->
                AlertResponse(
                    id = row.resourceId.toString(),
                    hostId = hostResourceId,
                    scope = ALERT_SCOPE_GLOBAL,
                    metric = row.metric,
                    condition = row.condition,
                    threshold = row.threshold,
                    durationSeconds = row.durationSeconds,
                    enabled = row.enabled,
                    alertPriority = row.alertPriority,
                    lastTriggeredAt = row.lastTriggeredAt?.toEpochMilliseconds(),
                    createdAt = row.createdAt.toEpochMilliseconds()
                )
            }
        }

    private fun getTierConfig(organizationId: Int): PricingTierConfigResponse {
        return pricingTierService.getEffectiveTierForOrganization(organizationId).tier
    }

    private suspend fun clampRangeToRetention(
        hostId: Int,
        fromTimestamp: Long,
        toTimestamp: Long
    ): Pair<Long, Long>? {
        val retentionDays = retentionPolicyService.getRetentionDaysForHost(hostId) ?: PricingTier.FREE.retentionDays
        val nowEpochSeconds = Clock.System.now().epochSeconds
        val oldestAllowed = nowEpochSeconds - (retentionDays * ONE_DAY_SECONDS)
        val clampedFrom = max(fromTimestamp, oldestAllowed)
        val clampedTo = min(toTimestamp, nowEpochSeconds)
        if (clampedFrom > clampedTo) return null
        return clampedFrom to clampedTo
    }
}
