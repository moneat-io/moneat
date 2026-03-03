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
import com.moneat.monitor.models.ContainerMetricsPayload
import com.moneat.monitor.models.ContainerMetricsResponse
import com.moneat.monitor.models.ContainerStats
import com.moneat.monitor.models.ContainerWithSystem
import com.moneat.monitor.models.CreateAlertRequest
import com.moneat.monitor.models.HostData
import com.moneat.monitor.models.HistoricalMetricsResponse
import com.moneat.monitor.models.LatestMetrics
import com.moneat.monitor.models.MetricDataPoint
import com.moneat.monitor.models.SystemData
import com.moneat.monitor.models.SystemMetricsPayload
import com.moneat.monitor.models.UpdateAlertRequest
import com.moneat.shared.models.HostAlertSettings
import com.moneat.shared.models.HostAlertTemplateStates
import com.moneat.shared.models.HostAlerts
import com.moneat.shared.models.Hosts
import com.moneat.shared.models.OrganizationAlertTemplates
import com.moneat.shared.models.SystemAlertSettings
import com.moneat.shared.models.SystemAlertTemplateStates
import com.moneat.shared.models.SystemAlerts
import com.moneat.shared.models.Systems
import com.moneat.shared.services.CacheService
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.utils.ClickHouseSqlUtils
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

class MonitorService {
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
    }

    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val pricingTierService = PricingTierService()
    private val retentionPolicyService = RetentionPolicyService()
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
     * Create a new host (Moneat Agent) and generate an agent key.
     */
    fun createHost(
        organizationId: Int,
        name: String
    ): Pair<HostData, String> {
        val agentKey = generateAgentKey()
        val agentKeyHash = hashAgentKey(agentKey)
        val now = Clock.System.now()
        val placeholderHostname = "pending-${UUID.randomUUID()}"

        ensureOrganizationAlertTemplates(organizationId)

        val hostId =
            transaction {
                Hosts.insert {
                    it[Hosts.organization_id] = organizationId
                    it[Hosts.hostname] = placeholderHostname
                    it[Hosts.display_name] = name
                    it[Hosts.agent_key_hash] = agentKeyHash
                    it[Hosts.status] = "pending"
                    it[Hosts.os] = ""
                    it[Hosts.platform] = ""
                    it[Hosts.arch] = null
                    it[Hosts.agent_version] = ""
                    it[Hosts.gohai] = ""
                    it[Hosts.tags] = "{}"
                    it[Hosts.first_seen_at] = now
                    it[Hosts.last_seen_at] = now
                } get Hosts.id
            }

        transaction {
            HostAlertSettings.insert {
                it[HostAlertSettings.host_id] = hostId
                it[HostAlertSettings.organization_id] = organizationId
                it[HostAlertSettings.scope] = ALERT_SCOPE_GLOBAL
                it[HostAlertSettings.updated_at] = now
            }
        }

        ensureHostAlertsSeeded(hostId, organizationId)

        val host = getHostById(hostId)!!
        return Pair(host, agentKey)
    }

    /**
     * Validate agent key and return host ID + organization ID (for Moneat Agent).
     */
    fun validateAgentKey(agentKey: String): Pair<Int, Int>? {
        val keyHash = hashAgentKey(agentKey)
        return transaction {
            Hosts
                .selectAll()
                .where { Hosts.agent_key_hash eq keyHash }
                .firstOrNull()
                ?.let {
                    Pair(
                        it[Hosts.id],
                        it[Hosts.organization_id]
                    )
                }
        }
    }

    /**
     * Check if organization can add more hosts.
     */
    fun checkHostQuota(organizationId: Int): Boolean {
        if (EnvConfig.SelfHost.enabled) return true
        val tier = getTierConfig(organizationId)
        val maxHosts = tier.maxHosts ?: Int.MAX_VALUE
        val currentCount =
            transaction {
                Hosts.selectAll().where { Hosts.organization_id eq organizationId }.count()
            }
        return currentCount < maxHosts
    }

    /**
     * Ingest metrics from agent and store in ClickHouse.
     */
    suspend fun ingestMetrics(
        hostId: Int,
        organizationId: Int,
        payload: SystemMetricsPayload
    ): Int {
        val now = Clock.System.now()
        val hostnameFromPayload = payload.host ?: hostId.toString()

        // Update host metadata and last_seen_at
        transaction {
            Hosts.update({ Hosts.id eq hostId }) {
                it[Hosts.last_seen_at] = now
                it[Hosts.status] = "up"
                it[Hosts.agent_version] = payload.agent_version ?: ""
                it[Hosts.os] = payload.os ?: ""
                it[Hosts.arch] = payload.arch
                it[Hosts.hostname] = hostnameFromPayload
                payload.platform?.takeIf { p -> p.isNotBlank() }?.let { p -> it[Hosts.platform] = p }
                payload.processor?.takeIf { p -> p.isNotBlank() }?.let { p -> it[Hosts.processor] = p }
                payload.cpu_cores?.takeIf { c -> c > 0 }?.let { c -> it[Hosts.cpu_cores] = c }
                payload.memory_total_kb?.takeIf { m -> m > 0 }?.let { m -> it[Hosts.memory_total_kb] = m }
            }
        }

        // Insert metrics into ClickHouse with host_id tag
        val timestamp = payload.timestamp
        val host = hostnameFromPayload
        val escapedHost = escapeSql(host)
        val tagsMap = "map('host_id','$hostId')"
        val ts = "fromUnixTimestamp64Milli(${timestamp * 1000})"

        val metricRows: List<Triple<String, Double, String>> =
            buildList {
                add(Triple("system.cpu.percent", payload.cpu_percent.toDouble(), ts))
                add(Triple("system.mem.total", payload.mem_total.toDouble(), ts))
                add(Triple("system.mem.used", payload.mem_used.toDouble(), ts))
                add(Triple("system.mem.available", payload.mem_available.toDouble(), ts))
                add(Triple("system.swap.total", payload.swap_total.toDouble(), ts))
                add(Triple("system.swap.used", payload.swap_used.toDouble(), ts))
                add(Triple("system.disk.total", payload.disk_total.toDouble(), ts))
                add(Triple("system.disk.used", payload.disk_used.toDouble(), ts))
                add(Triple("system.disk.read_bytes", payload.disk_read_bytes.toDouble(), ts))
                add(Triple("system.disk.write_bytes", payload.disk_write_bytes.toDouble(), ts))
                add(Triple("system.net.recv_bytes", payload.net_recv_bytes.toDouble(), ts))
                add(Triple("system.net.sent_bytes", payload.net_sent_bytes.toDouble(), ts))
                add(Triple("system.load.1", payload.load_1.toDouble(), ts))
                add(Triple("system.load.5", payload.load_5.toDouble(), ts))
                add(Triple("system.load.15", payload.load_15.toDouble(), ts))
                payload.temp_max?.let { add(Triple("system.temp.max", it.toDouble(), ts)) }
                payload.gpu_percent?.let { add(Triple("system.gpu.percent", it.toDouble(), ts)) }
                payload.gpu_mem_percent?.let { add(Triple("system.gpu.mem_percent", it.toDouble(), ts)) }
                payload.gpu_power?.let { add(Triple("system.gpu.power", it.toDouble(), ts)) }
                payload.battery_percent?.let { add(Triple("system.battery.percent", it.toDouble(), ts)) }
            }

        val values =
            metricRows
                .joinToString(",") { (name, value, tsVal) ->
                    "($organizationId,'${escapeSql(name)}',1,$tsVal,$value,'$escapedHost',$tagsMap,'','')"
                }

        val query =
            """
            INSERT INTO $clickhouseDb.metrics (
                organization_id, metric_name, metric_type, timestamp, value, host, tags, unit, source_type_name
            ) VALUES $values
            """.trimIndent()

        val response = ClickHouseClient.execute(query)

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error { "Failed to insert system metrics: $errorBody" }
            throw Exception("Failed to insert metrics: $errorBody")
        }

        // Insert container metrics if present
        payload.containers?.forEach { container ->
            insertContainerMetric(hostId, organizationId, timestamp, container, host)
        }

        // Return the poll interval for this organization's tier
        if (EnvConfig.SelfHost.enabled) return 10
        val tier = getTierConfig(organizationId)
        return tier.monitorIntervalSeconds
    }

    /**
     * Count metric rows that would be inserted into the metrics table (for usage tracking).
     * System metrics only; container data goes to the containers table.
     */
    fun countMetricsInPayload(payload: SystemMetricsPayload): Int {
        var count = 15
        if (payload.temp_max != null) count++
        if (payload.gpu_percent != null) count++
        if (payload.gpu_mem_percent != null) count++
        if (payload.gpu_power != null) count++
        if (payload.battery_percent != null) count++
        return count
    }

    private suspend fun insertContainerMetric(
        hostId: Int,
        organizationId: Int,
        timestamp: Long,
        container: ContainerMetricsPayload,
        host: String
    ) {
        val fullQuery =
            buildContainerInsertQuery(
                hostId = hostId,
                organizationId = organizationId,
                timestamp = timestamp,
                container = container,
                host = host
            )
        val response = ClickHouseClient.execute(fullQuery)
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.warn {
                "Failed to insert container metrics for host=$hostId container=${container.name}: $errorBody"
            }
        }
    }

    private fun buildContainerInsertQuery(
        hostId: Int,
        organizationId: Int,
        timestamp: Long,
        container: ContainerMetricsPayload,
        host: String
    ): String {
        val ts = "fromUnixTimestamp64Milli(${timestamp * 1000})"
        val tagsMap = "map('host_id','$hostId')"
        return """
            INSERT INTO $clickhouseDb.containers (
                organization_id, host, container_id, name, image, state,
                cpu_percent, mem_usage, mem_limit, net_rx_bytes, net_tx_bytes, tags, timestamp
            ) VALUES (
                $organizationId,
                '${escapeSql(host)}',
                '${escapeSql(container.id)}',
                '${escapeSql(container.name)}',
                '${escapeSql(container.image)}',
                '${escapeSql(container.status)}',
                ${container.cpu_percent},
                ${container.mem_used},
                ${container.mem_limit},
                ${container.net_recv_bytes},
                ${container.net_sent_bytes},
                $tagsMap,
                $ts
            )
        """.trimIndent()
    }

    /**
     * List all hosts for an organization.
     */
    fun listHosts(organizationId: Int): List<HostData> {
        return transaction {
            Hosts
                .selectAll()
                .where { Hosts.organization_id eq organizationId }
                .orderBy(Hosts.first_seen_at to SortOrder.DESC)
                .map { rowToHostData(it) }
        }
    }

    /**
     * Get a single host by ID.
     */
    fun getHostById(hostId: Int): HostData? {
        return transaction {
            Hosts
                .selectAll()
                .where { Hosts.id eq hostId }
                .firstOrNull()
                ?.let { rowToHostData(it) }
        }
    }

    /**
     * Delete a host and all its metrics.
     */
    fun deleteHost(
        hostId: Int,
        organizationId: Int
    ): Boolean {
        return transaction {
            val deleted =
                Hosts.deleteWhere {
                    (Hosts.id eq hostId) and (Hosts.organization_id eq organizationId)
                }
            deleted > 0
        }
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
            FROM $clickhouseDb.metrics
            WHERE organization_id = ${host.organizationId}
              AND tags['host_id'] = '$hostId'
              AND timestamp >= now64(3) - INTERVAL $retentionDays DAY
            FORMAT JSONCompact
            """.trimIndent()

        val response = ClickHouseClient.execute(query)

        if (!response.status.isSuccess()) {
            logger.warn { "Failed to fetch latest metrics for host $hostId" }
            return null
        }

        val body = response.bodyAsText()
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
     * Get historical metrics with optional downsampling.
     */
    suspend fun getHistoricalMetrics(
        hostId: Int,
        fromTimestamp: Long,
        toTimestamp: Long,
        intervalSeconds: Int?
    ): HistoricalMetricsResponse =
        CacheService.cached("cache:monitor_hist:$hostId:$fromTimestamp:$toTimestamp:$intervalSeconds", 30) {
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
            FROM $clickhouseDb.metrics
            WHERE organization_id = ${host.organizationId}
              AND tags['host_id'] = '$hostId'
              AND timestamp >= fromUnixTimestamp64Milli(${effectiveFrom * 1000})
              AND timestamp <= fromUnixTimestamp64Milli(${effectiveTo * 1000})
            GROUP BY ts
            ORDER BY ts
            FORMAT JSONCompact
                """.trimIndent()

            val response = ClickHouseClient.execute(query)

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                logger.error { "Failed to fetch historical metrics: $errorBody" }
                return@cached HistoricalMetricsResponse(
                    system_id = "",
                    host_id = hostId,
                    from = effectiveFrom,
                    to = effectiveTo,
                    interval_seconds = calculatedInterval,
                    data_points = emptyList()
                )
            }

            val body = response.bodyAsText()
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
                FROM $clickhouseDb.containers
                WHERE organization_id = ${host.organizationId}
                  AND tags['host_id'] = '$hostId'
                  AND timestamp >= now64(3) - INTERVAL $retentionDays DAY
            ) WHERE rn = 1
              AND timestamp >= now64(3) - INTERVAL $freshnessWindowSeconds SECOND
            FORMAT JSONCompact
            """.trimIndent()

        val response = ClickHouseClient.execute(query)
        if (!response.status.isSuccess()) {
            val errBody = response.bodyAsText()
            logger.warn { "Failed to fetch containers: $errBody" }
            return emptyList()
        }

        val body = response.bodyAsText()
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
                FROM $clickhouseDb.containers
                WHERE organization_id IN ($orgList)
                  AND timestamp >= now64(3) - INTERVAL $INFRA_LOOKBACK_DAYS DAY
                  $hostClause
            ) WHERE rn = 1
            ORDER BY host, name
            LIMIT $limit
            FORMAT JSONCompact
            """.trimIndent()

        val response = ClickHouseClient.execute(query)
        if (!response.status.isSuccess()) {
            val errBody = response.bodyAsText()
            logger.warn { "Failed to fetch infra containers: $errBody" }
            return emptyList()
        }

        val body = response.bodyAsText()
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
            FROM $clickhouseDb.containers
            WHERE organization_id = ${host.organizationId}
              AND tags['host_id'] = '$hostId'
              AND name = '$escapedName'
              AND timestamp >= fromUnixTimestamp64Milli(${effectiveFrom * 1000})
              AND timestamp <= fromUnixTimestamp64Milli(${effectiveTo * 1000})
            GROUP BY ts
            ORDER BY ts
            FORMAT JSONCompact
            """.trimIndent()

        val response = ClickHouseClient.execute(query)

        if (!response.status.isSuccess()) {
            return ContainerMetricsResponse(
                container_name = containerName,
                from = effectiveFrom,
                to = effectiveTo,
                interval_seconds = calculatedInterval,
                data_points = emptyList()
            )
        }

        val body = response.bodyAsText()
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
    fun listAlerts(hostId: Int): List<AlertResponse> {
        return listHostAlerts(hostId)
    }

    fun getAlertConfig(
        hostId: Int,
        organizationId: Int
    ): AlertConfigResponse {
        ensureOrganizationAlertTemplates(organizationId)
        ensureHostAlertsSeeded(hostId, organizationId)

        val scope = getHostAlertScope(hostId, organizationId)
        val globalAlerts = listGlobalAlertsForHost(hostId, organizationId)
        val hostAlerts = listHostAlerts(hostId)
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
        ensureOrganizationAlertTemplates(organizationId)
        ensureHostAlertsSeeded(hostId, organizationId)

        val now = Clock.System.now()
        transaction {
            val existing =
                HostAlertSettings
                    .selectAll()
                    .where {
                        (HostAlertSettings.host_id eq hostId) and
                            (HostAlertSettings.organization_id eq organizationId)
                    }.firstOrNull()

            if (existing != null) {
                HostAlertSettings.update({ HostAlertSettings.host_id eq hostId }) {
                    it[HostAlertSettings.scope] = scope
                    it[HostAlertSettings.updated_at] = now
                }
            } else {
                HostAlertSettings.insert {
                    it[HostAlertSettings.host_id] = hostId
                    it[HostAlertSettings.organization_id] = organizationId
                    it[HostAlertSettings.scope] = scope
                    it[HostAlertSettings.updated_at] = now
                }
            }
        }
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
            val alertId =
                transaction {
                    OrganizationAlertTemplates.insert {
                        it[OrganizationAlertTemplates.organization_id] = organizationId
                        it[metric] = request.metric
                        it[condition] = request.condition
                        it[threshold] = request.threshold
                        it[duration_seconds] = request.durationSeconds
                        it[enabled] = request.enabled
                        it[created_at] = now
                        it[updated_at] = now
                    } get OrganizationAlertTemplates.id
                }

            return AlertResponse(
                id = alertId,
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

        val alertId =
            transaction {
                HostAlerts.insert {
                    it[HostAlerts.host_id] = hostId
                    it[HostAlerts.organization_id] = organizationId
                    it[HostAlerts.metric] = request.metric
                    it[HostAlerts.condition] = request.condition
                    it[HostAlerts.threshold] = request.threshold
                    it[HostAlerts.duration_seconds] = request.durationSeconds
                    it[HostAlerts.enabled] = request.enabled
                    it[HostAlerts.last_triggered_at] = null
                    it[HostAlerts.created_at] = now
                } get HostAlerts.id
            }

        return AlertResponse(
            id = alertId,
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
    ): Boolean {
        if (scope == ALERT_SCOPE_GLOBAL) {
            val now = Clock.System.now()
            return transaction {
                val count =
                    OrganizationAlertTemplates.update({
                        (OrganizationAlertTemplates.id eq alertId) and
                            (OrganizationAlertTemplates.organization_id eq organizationId)
                    }) {
                        request.metric?.let { metric -> it[OrganizationAlertTemplates.metric] = metric }
                        request.condition?.let { cond -> it[OrganizationAlertTemplates.condition] = cond }
                        request.threshold?.let { thresh -> it[OrganizationAlertTemplates.threshold] = thresh }
                        request.durationSeconds?.let { dur -> it[OrganizationAlertTemplates.duration_seconds] = dur }
                        request.enabled?.let { en -> it[OrganizationAlertTemplates.enabled] = en }
                        it[OrganizationAlertTemplates.updated_at] = now
                    }
                count > 0
            }
        }

        return transaction {
            val count =
                HostAlerts.update({
                    (HostAlerts.id eq alertId) and
                        (HostAlerts.host_id eq hostId) and
                        (HostAlerts.organization_id eq organizationId)
                }) {
                    request.metric?.let { metric -> it[HostAlerts.metric] = metric }
                    request.condition?.let { cond -> it[HostAlerts.condition] = cond }
                    request.threshold?.let { thresh -> it[HostAlerts.threshold] = thresh }
                    request.durationSeconds?.let { dur -> it[HostAlerts.duration_seconds] = dur }
                    request.enabled?.let { en -> it[HostAlerts.enabled] = en }
                }
            count > 0
        }
    }

    /**
     * Delete an alert.
     */
    fun deleteAlert(
        alertId: Int,
        hostId: Int,
        organizationId: Int,
        scope: String = ALERT_SCOPE_HOST
    ): Boolean {
        if (scope == ALERT_SCOPE_GLOBAL) {
            return transaction {
                val deleted =
                    OrganizationAlertTemplates.deleteWhere {
                        (OrganizationAlertTemplates.id eq alertId) and
                            (OrganizationAlertTemplates.organization_id eq organizationId)
                    }
                deleted > 0
            }
        }

        return transaction {
            val deleted =
                HostAlerts.deleteWhere {
                    (HostAlerts.id eq alertId) and
                        (HostAlerts.host_id eq hostId) and
                        (HostAlerts.organization_id eq organizationId)
                }
            deleted > 0
        }
    }

    // Helper functions

    private fun isValidAlertScope(scope: String): Boolean {
        return scope == ALERT_SCOPE_GLOBAL || scope == ALERT_SCOPE_SYSTEM || scope == ALERT_SCOPE_HOST
    }

    private fun ensureOrganizationAlertTemplates(organizationId: Int) {
        transaction {
            val existingCount =
                OrganizationAlertTemplates
                    .selectAll()
                    .where {
                        OrganizationAlertTemplates.organization_id eq organizationId
                    }.count()
            if (existingCount > 0) {
                return@transaction
            }

            val now = Clock.System.now()
            defaultAlertTemplates.forEach { template ->
                OrganizationAlertTemplates.insert {
                    it[OrganizationAlertTemplates.organization_id] = organizationId
                    it[metric] = template.metric
                    it[condition] = template.condition
                    it[threshold] = template.threshold
                    it[duration_seconds] = template.durationSeconds
                    it[enabled] = template.enabled
                    it[created_at] = now
                    it[updated_at] = now
                }
            }
        }
    }

    private fun ensureHostAlertsSeeded(
        hostId: Int,
        organizationId: Int
    ) {
        transaction {
            val existingCount =
                HostAlerts
                    .selectAll()
                    .where {
                        (HostAlerts.host_id eq hostId) and
                            (HostAlerts.organization_id eq organizationId)
                    }.count()
            if (existingCount > 0) {
                return@transaction
            }

            val now = Clock.System.now()
            val templates =
                OrganizationAlertTemplates
                    .selectAll()
                    .where {
                        OrganizationAlertTemplates.organization_id eq organizationId
                    }.toList()

            if (templates.isEmpty()) {
                defaultAlertTemplates.forEach { template ->
                    HostAlerts.insert {
                        it[HostAlerts.host_id] = hostId
                        it[HostAlerts.organization_id] = organizationId
                        it[HostAlerts.metric] = template.metric
                        it[HostAlerts.condition] = template.condition
                        it[HostAlerts.threshold] = template.threshold
                        it[HostAlerts.duration_seconds] = template.durationSeconds
                        it[HostAlerts.enabled] = template.enabled
                        it[HostAlerts.last_triggered_at] = null
                        it[HostAlerts.created_at] = now
                    }
                }
                return@transaction
            }

            templates.forEach { template ->
                HostAlerts.insert {
                    it[HostAlerts.host_id] = hostId
                    it[HostAlerts.organization_id] = organizationId
                    it[HostAlerts.metric] = template[OrganizationAlertTemplates.metric]
                    it[HostAlerts.condition] = template[OrganizationAlertTemplates.condition]
                    it[HostAlerts.threshold] = template[OrganizationAlertTemplates.threshold]
                    it[HostAlerts.duration_seconds] = template[OrganizationAlertTemplates.duration_seconds]
                    it[HostAlerts.enabled] = template[OrganizationAlertTemplates.enabled]
                    it[HostAlerts.last_triggered_at] = null
                    it[HostAlerts.created_at] = now
                }
            }
        }
    }

    private fun getHostAlertScope(
        hostId: Int,
        organizationId: Int
    ): String {
        return transaction {
            val existing =
                HostAlertSettings
                    .selectAll()
                    .where {
                        (HostAlertSettings.host_id eq hostId) and
                            (HostAlertSettings.organization_id eq organizationId)
                    }.firstOrNull()

            if (existing != null) {
                return@transaction existing[HostAlertSettings.scope]
            }

            val now = Clock.System.now()
            HostAlertSettings.insert {
                it[HostAlertSettings.host_id] = hostId
                it[HostAlertSettings.organization_id] = organizationId
                it[HostAlertSettings.scope] = ALERT_SCOPE_HOST
                it[HostAlertSettings.updated_at] = now
            }
            ALERT_SCOPE_HOST
        }
    }

    private fun listHostAlerts(hostId: Int): List<AlertResponse> {
        return transaction {
            HostAlerts
                .selectAll()
                .where { HostAlerts.host_id eq hostId }
                .orderBy(HostAlerts.created_at to SortOrder.DESC)
                .map { row ->
                    AlertResponse(
                        id = row[HostAlerts.id],
                        hostId = hostId,
                        scope = ALERT_SCOPE_HOST,
                        metric = row[HostAlerts.metric],
                        condition = row[HostAlerts.condition],
                        threshold = row[HostAlerts.threshold],
                        durationSeconds = row[HostAlerts.duration_seconds],
                        enabled = row[HostAlerts.enabled],
                        lastTriggeredAt = row[HostAlerts.last_triggered_at]?.toEpochMilliseconds(),
                        createdAt = row[HostAlerts.created_at].toEpochMilliseconds()
                    )
                }
        }
    }

    private fun listGlobalAlertsForHost(
        hostId: Int,
        organizationId: Int
    ): List<AlertResponse> {
        return transaction {
            val templateStates =
                HostAlertTemplateStates
                    .selectAll()
                    .where {
                        HostAlertTemplateStates.host_id eq hostId
                    }.associateBy(
                        keySelector = { it[HostAlertTemplateStates.template_alert_id] },
                        valueTransform = { it[HostAlertTemplateStates.last_triggered_at] }
                    )

            OrganizationAlertTemplates
                .selectAll()
                .where {
                    OrganizationAlertTemplates.organization_id eq organizationId
                }.orderBy(OrganizationAlertTemplates.created_at to SortOrder.DESC)
                .map { row ->
                    AlertResponse(
                        id = row[OrganizationAlertTemplates.id],
                        hostId = hostId,
                        scope = ALERT_SCOPE_GLOBAL,
                        metric = row[OrganizationAlertTemplates.metric],
                        condition = row[OrganizationAlertTemplates.condition],
                        threshold = row[OrganizationAlertTemplates.threshold],
                        durationSeconds = row[OrganizationAlertTemplates.duration_seconds],
                        enabled = row[OrganizationAlertTemplates.enabled],
                        lastTriggeredAt = templateStates[row[OrganizationAlertTemplates.id]]?.toEpochMilliseconds(),
                        createdAt = row[OrganizationAlertTemplates.created_at].toEpochMilliseconds()
                    )
                }
        }
    }

    private fun escapeSql(value: String): String {
        return ClickHouseSqlUtils.escapeSql(value)
    }

    private fun generateAgentKey(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return "mk_" + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hashAgentKey(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(key.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
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

    private fun rowToHostData(row: ResultRow): HostData {
        return HostData(
            id = row[Hosts.id],
            organizationId = row[Hosts.organization_id],
            hostname = row[Hosts.hostname],
            displayName = row[Hosts.display_name],
            agentKeyHash = row[Hosts.agent_key_hash],
            status = row[Hosts.status],
            lastSeenAt = row[Hosts.last_seen_at],
            agentVersion = row[Hosts.agent_version].takeIf { it.isNotBlank() },
            os = row[Hosts.os].takeIf { it.isNotBlank() },
            arch = row[Hosts.arch],
            platform = row[Hosts.platform].takeIf { it.isNotBlank() },
            processor = row[Hosts.processor].takeIf { it.isNotBlank() },
            cpuCores = row[Hosts.cpu_cores].takeIf { it > 0 },
            memoryTotalKb = row[Hosts.memory_total_kb].takeIf { it > 0 },
            firstSeenAt = row[Hosts.first_seen_at],
            createdAt = row[Hosts.first_seen_at]
        )
    }

    private fun rowToSystemData(row: ResultRow): SystemData {
        return SystemData(
            id = row[Systems.id],
            organizationId = row[Systems.organization_id],
            name = row[Systems.name],
            host = row[Systems.host],
            agentKeyHash = row[Systems.agent_key_hash],
            status = row[Systems.status],
            lastSeenAt = row[Systems.last_seen_at],
            agentVersion = row[Systems.agent_version],
            os = row[Systems.os],
            arch = row[Systems.arch],
            createdAt = row[Systems.created_at],
            updatedAt = row[Systems.updated_at]
        )
    }
}
