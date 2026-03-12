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
import com.moneat.monitor.models.HostData
import com.moneat.monitor.models.HistoricalMetricsResponse
import com.moneat.monitor.models.LatestMetrics
import com.moneat.monitor.models.MetricDataPoint
import com.moneat.monitor.models.UpdateAlertData
import com.moneat.monitor.models.UpdateAlertRequest
import com.moneat.monitor.repositories.HostAlertRepository
import com.moneat.monitor.repositories.HostRepository
import com.moneat.shared.services.CacheService
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

class MonitorService(
    private val hostRepository: HostRepository,
    private val hostAlertRepository: HostAlertRepository,
    private val pricingTierService: PricingTierService = PricingTierService(),
    private val retentionPolicyService: RetentionPolicyService = RetentionPolicyService(),
) {
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

    /**
     * Delete a host and all its metrics.
     */
    suspend fun deleteHost(
        hostId: Int,
        organizationId: Int
    ): Boolean {
        // Delete telemetry from ClickHouse before removing the host row
        val metricsDelete =
            "ALTER TABLE `$clickhouseDb`.metrics DELETE WHERE organization_id = $organizationId" +
                " AND tags['host_id'] = '$hostId'"
        val containersDelete =
            "ALTER TABLE `$clickhouseDb`.containers DELETE WHERE organization_id = $organizationId" +
                " AND tags['host_id'] = '$hostId'"

        if (!hostRepository.deleteClickHouseData(metricsDelete)) {
            logger.error { "Failed to delete ClickHouse metrics for hostId=$hostId" }
            throw Exception("Failed to delete host telemetry (metrics)")
        }
        if (!hostRepository.deleteClickHouseData(containersDelete)) {
            logger.error { "Failed to delete ClickHouse containers for hostId=$hostId" }
            throw Exception("Failed to delete host telemetry (containers)")
        }

        return hostRepository.delete(hostId, organizationId)
    }

    /**
     * Get latest metrics for a host from ClickHouse metrics table.
     */
    suspend fun getLatestMetrics(hostId: Int): LatestMetrics? {
        val host = getHostById(hostId) ?: return null
        val retentionDays = retentionPolicyService.getRetentionDaysForHost(hostId) ?: PricingTier.FREE.retentionDays
        val query =
            """
            SELECT
                argMax(CASE WHEN metric_name='system.cpu.percent' THEN value END, timestamp) as cpu_percent,
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
                argMax(CASE WHEN metric_name='system.battery.percent' THEN value END, timestamp) as battery_percent
            FROM `$clickhouseDb`.metrics
            WHERE organization_id = ${host.organizationId}
              AND tags['host_id'] = '$hostId'
              AND timestamp >= now64(3) - INTERVAL $retentionDays DAY
            FORMAT JSONCompact
            """.trimIndent()

        val body = hostRepository.executeClickHouseQuery(query)
        if (body.isBlank()) return null

        try {
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
                    .getOrNull(3)
                    ?.toString()
                    ?.replace("\"", "")
                    ?.toLongOrNull() ?: 0
            val diskTotal =
                data
                    .getOrNull(4)
                    ?.toString()
                    ?.replace("\"", "")
                    ?.toLongOrNull() ?: 0
            val diskUsed =
                data
                    .getOrNull(5)
                    ?.toString()
                    ?.replace("\"", "")
                    ?.toLongOrNull() ?: 0
            val netRecvBytes =
                data
                    .getOrNull(6)
                    ?.toString()
                    ?.replace("\"", "")
                    ?.toLongOrNull() ?: 0
            val netSentBytes =
                data
                    .getOrNull(7)
                    ?.toString()
                    ?.replace("\"", "")
                    ?.toLongOrNull() ?: 0
            val load1 = data.getOrNull(8)?.toString()?.toFloatOrNull() ?: 0f
            val tempMax = data.getOrNull(9)?.toString()?.toFloatOrNull()
            val gpuPercent = data.getOrNull(10)?.toString()?.toFloatOrNull()
            val batteryPercent = data.getOrNull(11)?.toString()?.toFloatOrNull()

            val effectiveMemUsed = if (memAvailable > 0) memTotal - memAvailable else memUsed
            return LatestMetrics(
                cpu_percent = cpuPercent,
                mem_total = memTotal,
                mem_used = effectiveMemUsed,
                mem_percent = if (memTotal > 0) (effectiveMemUsed.toFloat() / memTotal * 100) else 0f,
                disk_total = diskTotal,
                disk_used = diskUsed,
                disk_percent = if (diskTotal > 0) (diskUsed.toFloat() / diskTotal * 100) else 0f,
                net_recv_bytes = netRecvBytes,
                net_sent_bytes = netSentBytes,
                net_recv_mbps = null,
                net_sent_mbps = null,
                load_1 = load1,
                temp_max = tempMax,
                gpu_percent = gpuPercent,
                battery_percent = batteryPercent
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse latest metrics response" }
            return null
        }
    }

    /**
     * Get latest metrics for multiple hosts in a single ClickHouse query (avoids N+1).
     * Returns a map from hostId to LatestMetrics (null if no data for that host).
     */
    suspend fun getLatestMetricsForHosts(hostIds: List<Int>, organizationId: Int): Map<Int, LatestMetrics?> {
        if (hostIds.isEmpty()) return emptyMap()
        val retentionDays = retentionPolicyService.getRetentionDaysForOrganization(organizationId)
        val hostIdList = hostIds.joinToString(",")
        val query =
            """
            SELECT
                toInt32OrZero(tags['host_id']) as host_id,
                argMax(CASE WHEN metric_name='system.cpu.percent' THEN value END, timestamp) as cpu_percent,
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
                argMax(CASE WHEN metric_name='system.battery.percent' THEN value END, timestamp) as battery_percent
            FROM `$clickhouseDb`.metrics
            WHERE organization_id = $organizationId
              AND toInt32OrZero(tags['host_id']) IN ($hostIdList)
              AND timestamp >= now64(3) - INTERVAL $retentionDays DAY
            GROUP BY host_id
            FORMAT JSONCompact
            """.trimIndent()

        val body = hostRepository.executeClickHouseQuery(query)
        if (body.isBlank()) return hostIds.associateWith { null }

        return try {
            val json = Json { ignoreUnknownKeys = true }
            val rows = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return hostIds.associateWith { null }
            val result = mutableMapOf<Int, LatestMetrics?>()
            for (row in rows) {
                val arr = row.jsonArray
                val rowHostId = arr.getOrNull(0)?.toString()?.replace("\"", "")?.toIntOrNull() ?: continue
                val cpuPercent = arr.getOrNull(1)?.toString()?.toFloatOrNull() ?: 0f
                val memTotal = arr.getOrNull(2)?.toString()?.replace("\"", "")?.toLongOrNull() ?: 0L
                val memUsed = arr.getOrNull(3)?.toString()?.replace("\"", "")?.toLongOrNull() ?: 0L
                val memAvailable = arr.getOrNull(4)?.toString()?.replace("\"", "")?.toLongOrNull() ?: 0L
                val diskTotal = arr.getOrNull(5)?.toString()?.replace("\"", "")?.toLongOrNull() ?: 0L
                val diskUsed = arr.getOrNull(6)?.toString()?.replace("\"", "")?.toLongOrNull() ?: 0L
                val netRecvBytes = arr.getOrNull(7)?.toString()?.replace("\"", "")?.toLongOrNull() ?: 0L
                val netSentBytes = arr.getOrNull(8)?.toString()?.replace("\"", "")?.toLongOrNull() ?: 0L
                val load1 = arr.getOrNull(9)?.toString()?.toFloatOrNull() ?: 0f
                val tempMax = arr.getOrNull(10)?.toString()?.toFloatOrNull()
                val gpuPercent = arr.getOrNull(11)?.toString()?.toFloatOrNull()
                val batteryPercent = arr.getOrNull(12)?.toString()?.toFloatOrNull()
                val effectiveMemUsed = if (memAvailable > 0) memTotal - memAvailable else memUsed
                result[rowHostId] = LatestMetrics(
                    cpu_percent = cpuPercent,
                    mem_total = memTotal,
                    mem_used = effectiveMemUsed,
                    mem_percent = if (memTotal > 0) (effectiveMemUsed.toFloat() / memTotal * 100) else 0f,
                    disk_total = diskTotal,
                    disk_used = diskUsed,
                    disk_percent = if (diskTotal > 0) (diskUsed.toFloat() / diskTotal * 100) else 0f,
                    net_recv_bytes = netRecvBytes,
                    net_sent_bytes = netSentBytes,
                    net_recv_mbps = null,
                    net_sent_mbps = null,
                    load_1 = load1,
                    temp_max = tempMax,
                    gpu_percent = gpuPercent,
                    battery_percent = batteryPercent
                )
            }
            hostIds.associateWith { result[it] }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse batch latest metrics response" }
            hostIds.associateWith { null }
        }
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
                system_id = "",
                host_id = hostId,
                from = fromTimestamp,
                to = toTimestamp,
                interval_seconds = intervalSeconds ?: 3600,
                data_points = emptyList()
            )
            val clampedWindow = clampRangeToRetention(hostId, fromTimestamp, toTimestamp)
            if (clampedWindow == null) {
                return@cached HistoricalMetricsResponse(
                    system_id = "",
                    host_id = hostId,
                    from = fromTimestamp,
                    to = toTimestamp,
                    interval_seconds = intervalSeconds ?: 3600,
                    data_points = emptyList()
                )
            }
            val (effectiveFrom, effectiveTo) = clampedWindow

            // Auto-calculate interval if not provided
            val timeRange = effectiveTo - effectiveFrom
            val calculatedInterval =
                intervalSeconds ?: when {
                    timeRange <= 3600 -> 10
                    timeRange <= 21600 -> 60
                    timeRange <= 86400 -> 300
                    timeRange <= 604800 -> 1800
                    else -> 3600
                }

            val query =
                """
            SELECT
                toUnixTimestamp(toStartOfInterval(timestamp, INTERVAL $calculatedInterval second)) as ts,
                avg(CASE WHEN metric_name='system.cpu.percent' THEN value END) as cpu,
                (1 - avg(CASE WHEN metric_name='system.mem.available' THEN value END) /
                    nullIf(avg(CASE WHEN metric_name='system.mem.total' THEN value END), 0)) * 100 as mem,
                avg(CASE WHEN metric_name='system.disk.used' THEN value END) /
                    nullIf(avg(CASE WHEN metric_name='system.disk.total' THEN value END), 0) * 100 as disk,
                sum(CASE WHEN metric_name='system.net.recv_bytes' THEN value ELSE 0 END) as net_recv,
                sum(CASE WHEN metric_name='system.net.sent_bytes' THEN value ELSE 0 END) as net_sent,
                avg(CASE WHEN metric_name='system.load.1' THEN value END) as load1,
                avg(CASE WHEN metric_name='system.load.5' THEN value END) as load5,
                avg(CASE WHEN metric_name='system.load.15' THEN value END) as load15,
                max(CASE WHEN metric_name='system.temp.max' THEN value END) as temp,
                avg(CASE WHEN metric_name='system.gpu.percent' THEN value END) as gpu,
                avg(CASE WHEN metric_name='system.battery.percent' THEN value END) as battery
            FROM `$clickhouseDb`.metrics
            WHERE organization_id = ${host.organizationId}
              AND tags['host_id'] = '$hostId'
              AND timestamp >= fromUnixTimestamp64Milli(${effectiveFrom * 1000})
              AND timestamp <= fromUnixTimestamp64Milli(${effectiveTo * 1000})
            GROUP BY ts
            ORDER BY ts
            FORMAT JSONCompact
                """.trimIndent()

            val body = hostRepository.executeClickHouseQuery(query)
            val dataPoints =
                try {
                    val json = Json { ignoreUnknownKeys = true }
                    val result = json.parseToJsonElement(body).jsonObject
                    val data =
                        result["data"]?.jsonArray ?: return@cached HistoricalMetricsResponse(
                            system_id = "",
                            host_id = hostId,
                            from = effectiveFrom,
                            to = effectiveTo,
                            interval_seconds = calculatedInterval,
                            data_points = emptyList()
                        )

                    data.map { row ->
                        val arr = row.jsonArray
                        MetricDataPoint(
                            timestamp = arr[0].toString().replace("\"", "").toLong(),
                            cpu_percent = arr.getOrNull(1)?.toString()?.toFloatOrNull(),
                            mem_percent = arr.getOrNull(2)?.toString()?.toFloatOrNull(),
                            disk_percent = arr.getOrNull(3)?.toString()?.toFloatOrNull(),
                            net_recv_bytes =
                            arr
                                .getOrNull(4)
                                ?.toString()
                                ?.replace("\"", "")
                                ?.toLongOrNull(),
                            net_sent_bytes =
                            arr
                                .getOrNull(5)
                                ?.toString()
                                ?.replace("\"", "")
                                ?.toLongOrNull(),
                            load_1 = arr.getOrNull(6)?.toString()?.toFloatOrNull(),
                            load_5 = arr.getOrNull(7)?.toString()?.toFloatOrNull(),
                            load_15 = arr.getOrNull(8)?.toString()?.toFloatOrNull(),
                            temp_max = arr.getOrNull(9)?.toString()?.toFloatOrNull(),
                            gpu_percent = arr.getOrNull(10)?.toString()?.toFloatOrNull(),
                            battery_percent = arr.getOrNull(11)?.toString()?.toFloatOrNull()
                        )
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to parse historical metrics" }
                    emptyList()
                }

            HistoricalMetricsResponse(
                system_id = "",
                host_id = hostId,
                from = effectiveFrom,
                to = effectiveTo,
                interval_seconds = calculatedInterval,
                data_points = dataPoints
            )
        }

    /**
     * Get latest container stats from ClickHouse containers table.
     */
    suspend fun getLatestContainers(hostId: Int): List<ContainerStats> {
        val host = getHostById(hostId) ?: return emptyList()
        val retentionDays = retentionPolicyService.getRetentionDaysForHost(hostId) ?: PricingTier.FREE.retentionDays
        val monitorIntervalSeconds = getTierConfig(host.organizationId).monitorIntervalSeconds
        val freshnessWindowSeconds = max(monitorIntervalSeconds * 3, 300)

        val query =
            """
            SELECT name, container_id, image, state, cpu_percent, mem_usage, mem_limit, net_rx_bytes, net_tx_bytes
            FROM (
                SELECT *,
                    ROW_NUMBER() OVER (PARTITION BY host, container_id ORDER BY timestamp DESC) as rn
                FROM `$clickhouseDb`.containers
                WHERE organization_id = ${host.organizationId}
                  AND tags['host_id'] = '$hostId'
                  AND timestamp >= now64(3) - INTERVAL $retentionDays DAY
            ) WHERE rn = 1
              AND timestamp >= now64(3) - INTERVAL $freshnessWindowSeconds SECOND
            FORMAT JSONCompact
            """.trimIndent()

        val body = hostRepository.executeClickHouseQuery(query)
        if (body.isBlank()) return emptyList()

        return try {
            val json = Json { ignoreUnknownKeys = true }
            val result = json.parseToJsonElement(body).jsonObject
            val data = result["data"]?.jsonArray ?: return emptyList()

            data.map { row ->
                val arr = row.jsonArray
                val memUsed = arr[5].toString().replace("\"", "").toLongOrNull() ?: 0
                val memLimit = arr[6].toString().replace("\"", "").toLongOrNull() ?: 1
                val netRecvBytes = arr.getOrNull(7)?.toString()?.replace("\"", "")?.toLongOrNull() ?: 0
                val netSentBytes = arr.getOrNull(8)?.toString()?.replace("\"", "")?.toLongOrNull() ?: 0

                ContainerStats(
                    name = arr[0].toString().replace("\"", ""),
                    id = arr[1].toString().replace("\"", ""),
                    image = arr[2].toString().replace("\"", ""),
                    status = arr[3].toString().replace("\"", ""),
                    cpu_percent = arr[4].toString().toFloatOrNull() ?: 0f,
                    mem_used = memUsed,
                    mem_limit = memLimit,
                    net_recv_bytes = netRecvBytes,
                    net_sent_bytes = netSentBytes,
                    mem_percent = if (memLimit > 0) (memUsed.toFloat() / memLimit * 100) else 0f
                )
            }
        } catch (e: Exception) {
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
                            systemId = host.id.toString(),
                            hostId = host.id,
                            systemName = host.displayName ?: host.hostname,
                            name = c.name,
                            id = c.id,
                            image = c.image,
                            status = c.status,
                            cpuPercent = c.cpu_percent,
                            memUsed = c.mem_used,
                            memLimit = c.mem_limit,
                            netRecvBytes = c.net_recv_bytes,
                            netSentBytes = c.net_sent_bytes,
                            memPercent = c.mem_percent
                        )
                    )
                }
            }
        }
        return allContainers
    }

    /**
     * Get latest container stats per host+container_id from the containers table.
     * Deduplicates time-series rows so each container appears once (fixes inflated
     * counts when raw rows are returned to MCP/API consumers).
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
            SELECT host, container_id, name, image, state, cpu_percent, mem_usage, mem_limit,
                   net_rx_bytes, net_tx_bytes, tags, timestamp
            FROM (
                SELECT *,
                    ROW_NUMBER() OVER (PARTITION BY organization_id, host, container_id ORDER BY timestamp DESC) as rn
                FROM `$clickhouseDb`.containers
                WHERE organization_id IN ($orgList)
                  AND timestamp >= now64(3) - INTERVAL $INFRA_LOOKBACK_DAYS DAY
                  $hostClause
            ) WHERE rn = 1
            ORDER BY host, name
            LIMIT $limit
            FORMAT JSONCompact
            """.trimIndent()

        val body = hostRepository.executeClickHouseQuery(query)
        if (body.isBlank()) return emptyList()

        return try {
            val json = Json { ignoreUnknownKeys = true }
            val result = json.parseToJsonElement(body).jsonObject
            val data = result["data"]?.jsonArray ?: return emptyList()

            data.map { row ->
                val arr = row.jsonArray
                val tagsObj = try {
                    arr.getOrNull(10)?.toString()?.let { t ->
                        json.parseToJsonElement(t.replace("\\\"", "\""))
                            .jsonObject
                            .entries
                            .associate { (k, v) -> k to v.toString().trim('"') }
                    } ?: emptyMap<String, String>()
                } catch (_: Exception) {
                    emptyMap<String, String>()
                }
                val ts = arr.getOrNull(11)?.toString()?.replace("\"", "") ?: ""

                mapOf(
                    "host" to arr.getOrNull(0)?.toString()?.replace("\"", ""),
                    "container_id" to arr.getOrNull(1)?.toString()?.replace("\"", ""),
                    "containerId" to arr.getOrNull(1)?.toString()?.replace("\"", ""),
                    "name" to arr.getOrNull(2)?.toString()?.replace("\"", ""),
                    "image" to arr.getOrNull(3)?.toString()?.replace("\"", ""),
                    "state" to arr.getOrNull(4)?.toString()?.replace("\"", ""),
                    "cpu_percent" to (arr.getOrNull(5)?.toString()?.toFloatOrNull() ?: 0f),
                    "cpuPercent" to (arr.getOrNull(5)?.toString()?.toFloatOrNull() ?: 0f),
                    "mem_usage" to (arr.getOrNull(6)?.toString()?.toLongOrNull() ?: 0L),
                    "memUsage" to (arr.getOrNull(6)?.toString()?.toLongOrNull() ?: 0L),
                    "mem_limit" to (arr.getOrNull(7)?.toString()?.toLongOrNull() ?: 0L),
                    "memLimit" to (arr.getOrNull(7)?.toString()?.toLongOrNull() ?: 0L),
                    "net_rx_bytes" to (arr.getOrNull(8)?.toString()?.toLongOrNull() ?: 0L),
                    "netRxBytes" to (arr.getOrNull(8)?.toString()?.toLongOrNull() ?: 0L),
                    "net_tx_bytes" to (arr.getOrNull(9)?.toString()?.toLongOrNull() ?: 0L),
                    "netTxBytes" to (arr.getOrNull(9)?.toString()?.toLongOrNull() ?: 0L),
                    "tags" to tagsObj,
                    "timestamp" to ts,
                    "id" to arr.getOrNull(1)?.toString()?.replace("\"", "")
                )
            }
        } catch (e: Exception) {
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
            container_name = containerName,
            from = fromTimestamp,
            to = toTimestamp,
            interval_seconds = intervalSeconds ?: 3600,
            data_points = emptyList()
        )
        val clampedWindow = clampRangeToRetention(hostId, fromTimestamp, toTimestamp)
        if (clampedWindow == null) {
            return ContainerMetricsResponse(
                container_name = containerName,
                from = fromTimestamp,
                to = toTimestamp,
                interval_seconds = intervalSeconds ?: 3600,
                data_points = emptyList()
            )
        }
        val (effectiveFrom, effectiveTo) = clampedWindow

        val timeRange = effectiveTo - effectiveFrom
        val calculatedInterval =
            intervalSeconds ?: when {
                timeRange <= 3600 -> 10
                timeRange <= 21600 -> 60
                timeRange <= 86400 -> 300
                timeRange <= 604800 -> 1800
                else -> 3600
            }

        val escapedName = escapeSql(containerName)
        val query =
            """
            SELECT
                toUnixTimestamp(toStartOfInterval(timestamp, INTERVAL $calculatedInterval second)) as ts,
                avg(cpu_percent) as cpu,
                avg(mem_usage) as mem_used,
                avg(mem_limit) as mem_limit,
                sum(net_rx_bytes) as net_recv,
                sum(net_tx_bytes) as net_sent
            FROM `$clickhouseDb`.containers
            WHERE organization_id = ${host.organizationId}
              AND tags['host_id'] = '$hostId'
              AND name = '$escapedName'
              AND timestamp >= fromUnixTimestamp64Milli(${effectiveFrom * 1000})
              AND timestamp <= fromUnixTimestamp64Milli(${effectiveTo * 1000})
            GROUP BY ts
            ORDER BY ts
            FORMAT JSONCompact
            """.trimIndent()

        val body = hostRepository.executeClickHouseQuery(query)
        val dataPoints =
            try {
                val json = Json { ignoreUnknownKeys = true }
                val result = json.parseToJsonElement(body).jsonObject
                val data =
                    result["data"]?.jsonArray ?: return ContainerMetricsResponse(
                        container_name = containerName,
                        from = effectiveFrom,
                        to = effectiveTo,
                        interval_seconds = calculatedInterval,
                        data_points = emptyList()
                    )

                data.map { row ->
                    val arr = row.jsonArray
                    ContainerMetricDataPoint(
                        timestamp = arr[0].toString().replace("\"", "").toLong(),
                        cpu_percent = arr.getOrNull(1)?.toString()?.toFloatOrNull(),
                        mem_used =
                        arr
                            .getOrNull(2)
                            ?.toString()
                            ?.replace("\"", "")
                            ?.toLongOrNull(),
                        mem_limit =
                        arr
                            .getOrNull(3)
                            ?.toString()
                            ?.replace("\"", "")
                            ?.toLongOrNull(),
                        net_recv_bytes =
                        arr
                            .getOrNull(4)
                            ?.toString()
                            ?.replace("\"", "")
                            ?.toLongOrNull(),
                        net_sent_bytes =
                        arr
                            .getOrNull(5)
                            ?.toString()
                            ?.replace("\"", "")
                            ?.toLongOrNull()
                    )
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to parse container historical metrics" }
                emptyList()
            }

        return ContainerMetricsResponse(
            container_name = containerName,
            from = effectiveFrom,
            to = effectiveTo,
            interval_seconds = calculatedInterval,
            data_points = dataPoints
        )
    }

    /**
     * List all alerts for a host.
     */
    fun listAlerts(hostId: Int, organizationId: Int): List<AlertResponse> {
        return listHostAlerts(hostId, organizationId)
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
            val alertId = hostAlertRepository.createAlert(
                CreateAlertData(
                    hostId = hostId,
                    organizationId = organizationId,
                    metric = request.metric,
                    condition = request.condition,
                    threshold = request.threshold,
                    durationSeconds = request.durationSeconds,
                    enabled = request.enabled,
                    scope = ALERT_SCOPE_GLOBAL
                )
            )
            return AlertResponse(
                id = alertId.toInt(),
                hostId = hostId,
                scope = ALERT_SCOPE_GLOBAL,
                metric = request.metric,
                condition = request.condition,
                threshold = request.threshold,
                durationSeconds = request.durationSeconds,
                enabled = request.enabled,
                lastTriggeredAt = null,
                createdAt = now.toEpochMilliseconds()
            )
        }

        ensureHostAlertsSeeded(hostId, organizationId)
        val now = Clock.System.now()
        val alertId = hostAlertRepository.createAlert(
            CreateAlertData(
                hostId = hostId,
                organizationId = organizationId,
                metric = request.metric,
                condition = request.condition,
                threshold = request.threshold,
                durationSeconds = request.durationSeconds,
                enabled = request.enabled,
                scope = ALERT_SCOPE_HOST
            )
        )
        return AlertResponse(
            id = alertId.toInt(),
            hostId = hostId,
            scope = ALERT_SCOPE_HOST,
            metric = request.metric,
            condition = request.condition,
            threshold = request.threshold,
            durationSeconds = request.durationSeconds,
            enabled = request.enabled,
            lastTriggeredAt = null,
            createdAt = now.toEpochMilliseconds()
        )
    }

    /**
     * Update an alert.
     */
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
                enabled = request.enabled
            ),
            scope
        )

    /**
     * Delete an alert.
     */
    fun deleteAlert(
        alertId: Int,
        hostId: Int,
        organizationId: Int,
        scope: String = ALERT_SCOPE_HOST
    ): Boolean =
        hostAlertRepository.deleteAlert(alertId.toLong(), hostId, organizationId, scope)

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
        hostAlertRepository.listByHostAndOrg(hostId, organizationId).map { row ->
            AlertResponse(
                id = row.id,
                hostId = hostId,
                scope = ALERT_SCOPE_HOST,
                metric = row.metric,
                condition = row.condition,
                threshold = row.threshold,
                durationSeconds = row.durationSeconds,
                enabled = row.enabled,
                lastTriggeredAt = row.lastTriggeredAt?.toEpochMilliseconds(),
                createdAt = row.createdAt.toEpochMilliseconds()
            )
        }

    private fun listGlobalAlertsForHost(
        hostId: Int,
        organizationId: Int
    ): List<AlertResponse> =
        hostAlertRepository.listGlobalAlertsForHost(organizationId, hostId).map { row ->
            AlertResponse(
                id = row.id,
                hostId = hostId,
                scope = ALERT_SCOPE_GLOBAL,
                metric = row.metric,
                condition = row.condition,
                threshold = row.threshold,
                durationSeconds = row.durationSeconds,
                enabled = row.enabled,
                lastTriggeredAt = row.lastTriggeredAt?.toEpochMilliseconds(),
                createdAt = row.createdAt.toEpochMilliseconds()
            )
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
        val oldestAllowed = nowEpochSeconds - (retentionDays * 86_400L)
        val clampedFrom = max(fromTimestamp, oldestAllowed)
        val clampedTo = min(toTimestamp, nowEpochSeconds)
        if (clampedFrom > clampedTo) return null
        return clampedFrom to clampedTo
    }
}
