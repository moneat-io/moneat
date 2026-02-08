package com.moneat.services

import com.moneat.config.ClickHouseClient
import com.moneat.models.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import kotlin.math.max
import kotlin.math.min

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
    }

    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val pricingTierService = PricingTierService()
    private val retentionPolicyService = RetentionPolicyService()
    private val defaultAlertTemplates = listOf(
        DefaultAlertTemplate(metric = "cpu_percent", condition = ">", threshold = 80.0),
        DefaultAlertTemplate(metric = "mem_percent", condition = ">", threshold = 80.0),
        DefaultAlertTemplate(metric = "disk_percent", condition = ">", threshold = 80.0),
        DefaultAlertTemplate(metric = "load_1", condition = ">", threshold = 4.0),
        DefaultAlertTemplate(metric = "temp_max", condition = ">", threshold = 85.0),
        DefaultAlertTemplate(metric = "gpu_percent", condition = ">", threshold = 85.0),
        DefaultAlertTemplate(metric = "battery_percent", condition = "<=", threshold = 20.0)
    )
    
    /**
     * Create a new system and generate an agent key.
     */
    fun createSystem(organizationId: Int, name: String): Pair<SystemData, String> {
        val agentKey = generateAgentKey()
        val agentKeyHash = hashAgentKey(agentKey)
        val systemId = UUID.randomUUID()
        val now = Clock.System.now()

        ensureOrganizationAlertTemplates(organizationId)
        
        transaction {
            Systems.insert {
                it[id] = systemId
                it[Systems.organization_id] = organizationId
                it[Systems.name] = name
                it[host] = null
                it[agent_key_hash] = agentKeyHash
                it[status] = "pending"
                it[last_seen_at] = null
                it[agent_version] = null
                it[os] = null
                it[arch] = null
                it[created_at] = now
                it[updated_at] = now
            }

            SystemAlertSettings.insert {
                it[SystemAlertSettings.system_id] = systemId
                it[SystemAlertSettings.organization_id] = organizationId
                it[SystemAlertSettings.scope] = ALERT_SCOPE_GLOBAL
                it[updated_at] = now
            }
        }

        ensureSystemAlertsSeeded(systemId, organizationId)
        
        val system = getSystemById(systemId)!!
        return Pair(system, agentKey)
    }
    
    /**
     * Validate agent key and return system ID + organization ID.
     */
    fun validateAgentKey(agentKey: String): Pair<UUID, Int>? {
        val keyHash = hashAgentKey(agentKey)
        return transaction {
            Systems.selectAll().where { Systems.agent_key_hash eq keyHash }
                .firstOrNull()
                ?.let {
                    Pair(
                        it[Systems.id],
                        it[Systems.organization_id]
                    )
                }
        }
    }
    
    /**
     * Check if organization can add more systems.
     */
    fun checkSystemQuota(organizationId: Int): Boolean {
        val tier = getTierConfig(organizationId)
        val currentCount = transaction {
            Systems.selectAll().where { Systems.organization_id eq organizationId }.count()
        }
        return currentCount < tier.maxSystems
    }
    
    /**
     * Ingest metrics from agent and store in ClickHouse.
     */
    suspend fun ingestMetrics(systemId: UUID, organizationId: Int, payload: SystemMetricsPayload): Int {
        val now = Clock.System.now()
        
        // Update system metadata and last_seen_at
        transaction {
            Systems.update({ Systems.id eq systemId }) {
                it[last_seen_at] = now
                it[status] = "up"
                it[updated_at] = now
                
                payload.agent_version?.let { version -> it[agent_version] = version }
                payload.os?.let { osName -> it[os] = osName }
                payload.arch?.let { architecture -> it[arch] = architecture }
                payload.host?.let { hostname -> it[host] = hostname }
            }
        }
        
        // Insert system metrics to ClickHouse
        val timestamp = payload.timestamp
        val query = """
            INSERT INTO $clickhouseDb.system_metrics (
                system_id, org_id, timestamp,
                cpu_percent, mem_total, mem_used, mem_available,
                swap_total, swap_used, disk_total, disk_used,
                disk_read_bytes, disk_write_bytes, net_recv_bytes, net_sent_bytes,
                load_1, load_5, load_15, temp_max,
                gpu_percent, gpu_mem_percent, gpu_power, battery_percent
            ) VALUES (
                toUUID('$systemId'),
                $organizationId,
                fromUnixTimestamp($timestamp),
                ${payload.cpu_percent},
                ${payload.mem_total},
                ${payload.mem_used},
                ${payload.mem_available},
                ${payload.swap_total},
                ${payload.swap_used},
                ${payload.disk_total},
                ${payload.disk_used},
                ${payload.disk_read_bytes},
                ${payload.disk_write_bytes},
                ${payload.net_recv_bytes},
                ${payload.net_sent_bytes},
                ${payload.load_1},
                ${payload.load_5},
                ${payload.load_15},
                ${payload.temp_max ?: 0f},
                ${payload.gpu_percent ?: 0f},
                ${payload.gpu_mem_percent ?: 0f},
                ${payload.gpu_power ?: 0f},
                ${payload.battery_percent ?: 0f}
            )
        """.trimIndent()
        
        val response = ClickHouseClient.execute(query)
        
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error { "Failed to insert system metrics: $errorBody" }
            throw Exception("Failed to insert metrics: $errorBody")
        }
        
        // Insert container metrics if present
        payload.containers?.forEach { container ->
            val containerQuery = """
                INSERT INTO $clickhouseDb.container_metrics (
                    system_id, org_id, timestamp,
                    container_name, container_id, image, status,
                    cpu_percent, mem_used, mem_limit,
                    net_recv_bytes, net_sent_bytes
                ) VALUES (
                    toUUID('$systemId'),
                    $organizationId,
                    fromUnixTimestamp($timestamp),
                    '${escapeSql(container.name)}',
                    '${escapeSql(container.id)}',
                    '${escapeSql(container.image)}',
                    '${escapeSql(container.status)}',
                    ${container.cpu_percent},
                    ${container.mem_used},
                    ${container.mem_limit},
                    ${container.net_recv_bytes},
                    ${container.net_sent_bytes}
                )
            """.trimIndent()
            
            val containerResponse = ClickHouseClient.execute(containerQuery)
            
            if (!containerResponse.status.isSuccess()) {
                val errorBody = containerResponse.bodyAsText()
                logger.warn { "Failed to insert container metrics: $errorBody" }
            }
        }
        
        // Return the poll interval for this organization's tier
        val tier = getTierConfig(organizationId)
        return tier.monitorIntervalSeconds
    }
    
    /**
     * List all systems for an organization.
     */
    fun listSystems(organizationId: Int): List<SystemData> {
        return transaction {
            Systems.selectAll().where { Systems.organization_id eq organizationId }
                .orderBy(Systems.created_at to SortOrder.DESC)
                .map { rowToSystemData(it) }
        }
    }
    
    /**
     * Get a single system by ID.
     */
    fun getSystemById(systemId: UUID): SystemData? {
        return transaction {
            Systems.selectAll().where { Systems.id eq systemId }
                .firstOrNull()
                ?.let { rowToSystemData(it) }
        }
    }
    
    /**
     * Delete a system and all its metrics.
     */
    fun deleteSystem(systemId: UUID, organizationId: Int): Boolean {
        return transaction {
            val deleted = Systems.deleteWhere {
                (Systems.id eq systemId) and (Systems.organization_id eq organizationId)
            }
            deleted > 0
        }
    }
    
    /**
     * Get latest metrics for a system from ClickHouse.
     */
    suspend fun getLatestMetrics(systemId: UUID): LatestMetrics? {
        val retentionDays = retentionPolicyService.getRetentionDaysForSystem(systemId) ?: PricingTier.FREE.retentionDays
        val query = """
            SELECT 
                cpu_percent, mem_total, mem_used, disk_total, disk_used,
                net_recv_bytes, net_sent_bytes, load_1, temp_max, gpu_percent, battery_percent
            FROM $clickhouseDb.system_metrics
            WHERE system_id = toUUID('$systemId')
              AND timestamp >= now() - INTERVAL $retentionDays DAY
            ORDER BY timestamp DESC
            LIMIT 1
            FORMAT JSONCompact
        """.trimIndent()
        
        val response = ClickHouseClient.execute(query)
        
        if (!response.status.isSuccess()) {
            logger.warn { "Failed to fetch latest metrics for system $systemId" }
            return null
        }
        
        val body = response.bodyAsText()
        if (body.isBlank()) return null
        
        try {
            val json = Json { ignoreUnknownKeys = true }
            val result = json.parseToJsonElement(body).jsonObject
            val data = result["data"]?.jsonArray?.firstOrNull()?.jsonArray ?: return null
            
            val cpuPercent = data.getOrNull(0)?.toString()?.toFloatOrNull() ?: 0f
            val memTotal = data.getOrNull(1)?.toString()?.replace("\"", "")?.toLongOrNull() ?: 0
            val memUsed = data.getOrNull(2)?.toString()?.replace("\"", "")?.toLongOrNull() ?: 0
            val diskTotal = data.getOrNull(3)?.toString()?.replace("\"", "")?.toLongOrNull() ?: 0
            val diskUsed = data.getOrNull(4)?.toString()?.replace("\"", "")?.toLongOrNull() ?: 0
            val netRecvBytes = data.getOrNull(5)?.toString()?.replace("\"", "")?.toLongOrNull() ?: 0
            val netSentBytes = data.getOrNull(6)?.toString()?.replace("\"", "")?.toLongOrNull() ?: 0
            val load1 = data.getOrNull(7)?.toString()?.toFloatOrNull() ?: 0f
            val tempMax = data.getOrNull(8)?.toString()?.toFloatOrNull()
            val gpuPercent = data.getOrNull(9)?.toString()?.toFloatOrNull()
            val batteryPercent = data.getOrNull(10)?.toString()?.toFloatOrNull()
            
            return LatestMetrics(
                cpu_percent = cpuPercent,
                mem_total = memTotal,
                mem_used = memUsed,
                mem_percent = if (memTotal > 0) (memUsed.toFloat() / memTotal * 100) else 0f,
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
        systemId: UUID,
        fromTimestamp: Long,
        toTimestamp: Long,
        intervalSeconds: Int?
    ): HistoricalMetricsResponse =
        CacheService.cached("cache:monitor_hist:$systemId:$fromTimestamp:$toTimestamp:$intervalSeconds", 30) {
        val clampedWindow = clampRangeToRetention(systemId, fromTimestamp, toTimestamp)
        if (clampedWindow == null) {
            return@cached HistoricalMetricsResponse(
                system_id = systemId.toString(),
                from = fromTimestamp,
                to = toTimestamp,
                interval_seconds = intervalSeconds ?: 3600,
                data_points = emptyList()
            )
        }
        val (effectiveFrom, effectiveTo) = clampedWindow

        // Auto-calculate interval if not provided
        val timeRange = effectiveTo - effectiveFrom
        val calculatedInterval = intervalSeconds ?: when {
            timeRange <= 3600 -> 10 // 1 hour: 10s interval
            timeRange <= 21600 -> 60 // 6 hours: 1 min interval
            timeRange <= 86400 -> 300 // 24 hours: 5 min interval
            timeRange <= 604800 -> 1800 // 7 days: 30 min interval
            else -> 3600 // 30+ days: 1 hour interval
        }
        
        val query = """
            SELECT 
                toUnixTimestamp(toStartOfInterval(timestamp, INTERVAL $calculatedInterval second)) as ts,
                avg(cpu_percent) as cpu,
                avg(mem_used / mem_total * 100) as mem,
                avg(disk_used / disk_total * 100) as disk,
                sum(net_recv_bytes) as net_recv,
                sum(net_sent_bytes) as net_sent,
                avg(load_1) as load1,
                avg(load_5) as load5,
                avg(load_15) as load15,
                max(temp_max) as temp,
                avg(gpu_percent) as gpu,
                avg(battery_percent) as battery
            FROM $clickhouseDb.system_metrics
            WHERE system_id = toUUID('$systemId')
              AND timestamp >= fromUnixTimestamp($effectiveFrom)
              AND timestamp <= fromUnixTimestamp($effectiveTo)
            GROUP BY ts
            ORDER BY ts
            FORMAT JSONCompact
        """.trimIndent()
        
        val response = ClickHouseClient.execute(query)
        
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error { "Failed to fetch historical metrics: $errorBody" }
            return@cached HistoricalMetricsResponse(
                system_id = systemId.toString(),
                from = effectiveFrom,
                to = effectiveTo,
                interval_seconds = calculatedInterval,
                data_points = emptyList()
            )
        }
        
        val body = response.bodyAsText()
        val dataPoints = try {
            val json = Json { ignoreUnknownKeys = true }
            val result = json.parseToJsonElement(body).jsonObject
            val data = result["data"]?.jsonArray ?: return@cached HistoricalMetricsResponse(
                system_id = systemId.toString(),
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
                    net_recv_bytes = arr.getOrNull(4)?.toString()?.replace("\"", "")?.toLongOrNull(),
                    net_sent_bytes = arr.getOrNull(5)?.toString()?.replace("\"", "")?.toLongOrNull(),
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
            system_id = systemId.toString(),
            from = effectiveFrom,
            to = effectiveTo,
            interval_seconds = calculatedInterval,
            data_points = dataPoints
        )
        }
    
    /**
     * Get latest container stats from ClickHouse.
     */
    suspend fun getLatestContainers(systemId: UUID): List<ContainerStats> {
        val retentionDays = retentionPolicyService.getRetentionDaysForSystem(systemId) ?: PricingTier.FREE.retentionDays
        val query = """
            SELECT 
                container_name, container_id, image, status,
                cpu_percent, mem_used, mem_limit
            FROM (
                SELECT *,
                    ROW_NUMBER() OVER (PARTITION BY container_name ORDER BY timestamp DESC) as rn
                FROM $clickhouseDb.container_metrics
                WHERE system_id = toUUID('$systemId')
                  AND timestamp >= now() - INTERVAL $retentionDays DAY
                  AND timestamp >= now() - INTERVAL 5 MINUTE
            ) WHERE rn = 1
            FORMAT JSONCompact
        """.trimIndent()
        
        val response = ClickHouseClient.execute(query)
        
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.warn { "Failed to fetch containers: $errorBody" }
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
                
                ContainerStats(
                    name = arr[0].toString().replace("\"", ""),
                    id = arr[1].toString().replace("\"", ""),
                    image = arr[2].toString().replace("\"", ""),
                    status = arr[3].toString().replace("\"", ""),
                    cpu_percent = arr[4].toString().toFloatOrNull() ?: 0f,
                    mem_used = memUsed,
                    mem_limit = memLimit,
                    mem_percent = if (memLimit > 0) (memUsed.toFloat() / memLimit * 100) else 0f
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse container stats" }
            emptyList()
        }
    }
    
    /**
     * Get historical metrics for a specific container.
     */
    suspend fun getContainerHistoricalMetrics(
        systemId: UUID,
        containerName: String,
        fromTimestamp: Long,
        toTimestamp: Long,
        intervalSeconds: Int?
    ): ContainerMetricsResponse {
        val clampedWindow = clampRangeToRetention(systemId, fromTimestamp, toTimestamp)
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
        val calculatedInterval = intervalSeconds ?: when {
            timeRange <= 3600 -> 10
            timeRange <= 21600 -> 60
            timeRange <= 86400 -> 300
            timeRange <= 604800 -> 1800
            else -> 3600
        }
        
        val query = """
            SELECT 
                toUnixTimestamp(toStartOfInterval(timestamp, INTERVAL $calculatedInterval second)) as ts,
                avg(cpu_percent) as cpu,
                avg(mem_used) as mem_used,
                avg(mem_limit) as mem_limit,
                sum(net_recv_bytes) as net_recv,
                sum(net_sent_bytes) as net_sent
            FROM $clickhouseDb.container_metrics
            WHERE system_id = toUUID('$systemId')
              AND container_name = '${escapeSql(containerName)}'
              AND timestamp >= fromUnixTimestamp($effectiveFrom)
              AND timestamp <= fromUnixTimestamp($effectiveTo)
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
        val dataPoints = try {
            val json = Json { ignoreUnknownKeys = true }
            val result = json.parseToJsonElement(body).jsonObject
            val data = result["data"]?.jsonArray ?: return ContainerMetricsResponse(
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
                    mem_used = arr.getOrNull(2)?.toString()?.replace("\"", "")?.toLongOrNull(),
                    mem_limit = arr.getOrNull(3)?.toString()?.replace("\"", "")?.toLongOrNull(),
                    net_recv_bytes = arr.getOrNull(4)?.toString()?.replace("\"", "")?.toLongOrNull(),
                    net_sent_bytes = arr.getOrNull(5)?.toString()?.replace("\"", "")?.toLongOrNull()
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
     * List all alerts for a system.
     */
    fun listAlerts(systemId: UUID): List<AlertResponse> {
        return listSystemAlerts(systemId)
    }

    fun getAlertConfig(systemId: UUID, organizationId: Int): AlertConfigResponse {
        ensureOrganizationAlertTemplates(organizationId)
        ensureSystemAlertsSeeded(systemId, organizationId)

        val scope = getSystemAlertScope(systemId, organizationId)
        val globalAlerts = listGlobalAlerts(systemId, organizationId)
        val systemAlerts = listSystemAlerts(systemId)
        val effectiveAlerts = if (scope == ALERT_SCOPE_GLOBAL) globalAlerts else systemAlerts

        return AlertConfigResponse(
            scope = scope,
            globalAlerts = globalAlerts,
            systemAlerts = systemAlerts,
            effectiveAlerts = effectiveAlerts
        )
    }

    fun updateAlertScope(systemId: UUID, organizationId: Int, scope: String): Boolean {
        if (!isValidAlertScope(scope)) {
            return false
        }
        ensureOrganizationAlertTemplates(organizationId)
        ensureSystemAlertsSeeded(systemId, organizationId)

        val now = Clock.System.now()
        transaction {
            val existing = SystemAlertSettings.selectAll().where {
                (SystemAlertSettings.system_id eq systemId) and
                (SystemAlertSettings.organization_id eq organizationId)
            }.firstOrNull()

            if (existing != null) {
                SystemAlertSettings.update({ SystemAlertSettings.system_id eq systemId }) {
                    it[SystemAlertSettings.scope] = scope
                    it[updated_at] = now
                }
            } else {
                SystemAlertSettings.insert {
                    it[SystemAlertSettings.system_id] = systemId
                    it[SystemAlertSettings.organization_id] = organizationId
                    it[SystemAlertSettings.scope] = scope
                    it[updated_at] = now
                }
            }
        }
        return true
    }
    
    /**
     * Create an alert for a system.
     */
    fun createAlert(
        systemId: UUID,
        organizationId: Int,
        request: CreateAlertRequest,
        scope: String = ALERT_SCOPE_SYSTEM
    ): AlertResponse {
        if (scope == ALERT_SCOPE_GLOBAL) {
            ensureOrganizationAlertTemplates(organizationId)
            val now = Clock.System.now()
            val alertId = transaction {
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
                systemId = systemId.toString(),
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

        ensureSystemAlertsSeeded(systemId, organizationId)
        val now = Clock.System.now()
        
        val alertId = transaction {
            SystemAlerts.insert {
                it[system_id] = systemId
                it[SystemAlerts.organization_id] = organizationId
                it[metric] = request.metric
                it[condition] = request.condition
                it[threshold] = request.threshold
                it[duration_seconds] = request.durationSeconds
                it[enabled] = request.enabled
                it[last_triggered_at] = null
                it[created_at] = now
            } get SystemAlerts.id
        }
        
        return AlertResponse(
            id = alertId,
            systemId = systemId.toString(),
            scope = ALERT_SCOPE_SYSTEM,
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
        systemId: UUID,
        organizationId: Int,
        request: UpdateAlertRequest,
        scope: String = ALERT_SCOPE_SYSTEM
    ): Boolean {
        if (scope == ALERT_SCOPE_GLOBAL) {
            val now = Clock.System.now()
            return transaction {
                val count = OrganizationAlertTemplates.update({
                    (OrganizationAlertTemplates.id eq alertId) and
                    (OrganizationAlertTemplates.organization_id eq organizationId)
                }) {
                    request.metric?.let { metric -> it[OrganizationAlertTemplates.metric] = metric }
                    request.condition?.let { cond -> it[condition] = cond }
                    request.threshold?.let { thresh -> it[threshold] = thresh }
                    request.durationSeconds?.let { dur -> it[duration_seconds] = dur }
                    request.enabled?.let { en -> it[enabled] = en }
                    it[updated_at] = now
                }
                count > 0
            }
        }

        return transaction {
            val count = SystemAlerts.update({
                (SystemAlerts.id eq alertId) and
                (SystemAlerts.system_id eq systemId) and
                (SystemAlerts.organization_id eq organizationId)
            }) {
                request.metric?.let { metric -> it[SystemAlerts.metric] = metric }
                request.condition?.let { cond -> it[condition] = cond }
                request.threshold?.let { thresh -> it[threshold] = thresh }
                request.durationSeconds?.let { dur -> it[duration_seconds] = dur }
                request.enabled?.let { en -> it[enabled] = en }
            }
            count > 0
        }
    }
    
    /**
     * Delete an alert.
     */
    fun deleteAlert(
        alertId: Int,
        systemId: UUID,
        organizationId: Int,
        scope: String = ALERT_SCOPE_SYSTEM
    ): Boolean {
        if (scope == ALERT_SCOPE_GLOBAL) {
            return transaction {
                val deleted = OrganizationAlertTemplates.deleteWhere {
                    (OrganizationAlertTemplates.id eq alertId) and
                    (OrganizationAlertTemplates.organization_id eq organizationId)
                }
                deleted > 0
            }
        }

        return transaction {
            val deleted = SystemAlerts.deleteWhere {
                (SystemAlerts.id eq alertId) and
                (SystemAlerts.system_id eq systemId) and
                (SystemAlerts.organization_id eq organizationId)
            }
            deleted > 0
        }
    }
    
    // Helper functions

    private fun isValidAlertScope(scope: String): Boolean {
        return scope == ALERT_SCOPE_GLOBAL || scope == ALERT_SCOPE_SYSTEM
    }

    private fun ensureOrganizationAlertTemplates(organizationId: Int) {
        transaction {
            val existingCount = OrganizationAlertTemplates.selectAll().where {
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

    private fun ensureSystemAlertsSeeded(systemId: UUID, organizationId: Int) {
        transaction {
            val existingCount = SystemAlerts.selectAll().where {
                (SystemAlerts.system_id eq systemId) and
                (SystemAlerts.organization_id eq organizationId)
            }.count()
            if (existingCount > 0) {
                return@transaction
            }

            val now = Clock.System.now()
            val templates = OrganizationAlertTemplates.selectAll().where {
                OrganizationAlertTemplates.organization_id eq organizationId
            }.toList()

            if (templates.isEmpty()) {
                defaultAlertTemplates.forEach { template ->
                    SystemAlerts.insert {
                        it[SystemAlerts.system_id] = systemId
                        it[SystemAlerts.organization_id] = organizationId
                        it[metric] = template.metric
                        it[condition] = template.condition
                        it[threshold] = template.threshold
                        it[duration_seconds] = template.durationSeconds
                        it[enabled] = template.enabled
                        it[last_triggered_at] = null
                        it[created_at] = now
                    }
                }
                return@transaction
            }

            templates.forEach { template ->
                SystemAlerts.insert {
                    it[SystemAlerts.system_id] = systemId
                    it[SystemAlerts.organization_id] = organizationId
                    it[metric] = template[OrganizationAlertTemplates.metric]
                    it[condition] = template[OrganizationAlertTemplates.condition]
                    it[threshold] = template[OrganizationAlertTemplates.threshold]
                    it[duration_seconds] = template[OrganizationAlertTemplates.duration_seconds]
                    it[enabled] = template[OrganizationAlertTemplates.enabled]
                    it[last_triggered_at] = null
                    it[created_at] = now
                }
            }
        }
    }

    private fun getSystemAlertScope(systemId: UUID, organizationId: Int): String {
        return transaction {
            val existing = SystemAlertSettings.selectAll().where {
                (SystemAlertSettings.system_id eq systemId) and
                (SystemAlertSettings.organization_id eq organizationId)
            }.firstOrNull()

            if (existing != null) {
                return@transaction existing[SystemAlertSettings.scope]
            }

            val now = Clock.System.now()
            SystemAlertSettings.insert {
                it[SystemAlertSettings.system_id] = systemId
                it[SystemAlertSettings.organization_id] = organizationId
                it[SystemAlertSettings.scope] = ALERT_SCOPE_SYSTEM
                it[updated_at] = now
            }
            ALERT_SCOPE_SYSTEM
        }
    }

    private fun listSystemAlerts(systemId: UUID): List<AlertResponse> {
        return transaction {
            SystemAlerts.selectAll().where { SystemAlerts.system_id eq systemId }
                .orderBy(SystemAlerts.created_at to SortOrder.DESC)
                .map { row ->
                    AlertResponse(
                        id = row[SystemAlerts.id],
                        systemId = row[SystemAlerts.system_id].toString(),
                        scope = ALERT_SCOPE_SYSTEM,
                        metric = row[SystemAlerts.metric],
                        condition = row[SystemAlerts.condition],
                        threshold = row[SystemAlerts.threshold],
                        durationSeconds = row[SystemAlerts.duration_seconds],
                        enabled = row[SystemAlerts.enabled],
                        lastTriggeredAt = row[SystemAlerts.last_triggered_at]?.toEpochMilliseconds(),
                        createdAt = row[SystemAlerts.created_at].toEpochMilliseconds()
                    )
                }
        }
    }

    private fun listGlobalAlerts(systemId: UUID, organizationId: Int): List<AlertResponse> {
        return transaction {
            val templateStates = SystemAlertTemplateStates.selectAll().where {
                SystemAlertTemplateStates.system_id eq systemId
            }.associateBy(
                keySelector = { it[SystemAlertTemplateStates.template_alert_id] },
                valueTransform = { it[SystemAlertTemplateStates.last_triggered_at] }
            )

            OrganizationAlertTemplates.selectAll().where {
                OrganizationAlertTemplates.organization_id eq organizationId
            }
                .orderBy(OrganizationAlertTemplates.created_at to SortOrder.DESC)
                .map { row ->
                    AlertResponse(
                        id = row[OrganizationAlertTemplates.id],
                        systemId = systemId.toString(),
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
        return value.replace("\\", "\\\\").replace("'", "\\'")
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

    private suspend fun clampRangeToRetention(systemId: UUID, fromTimestamp: Long, toTimestamp: Long): Pair<Long, Long>? {
        val retentionDays = retentionPolicyService.getRetentionDaysForSystem(systemId) ?: PricingTier.FREE.retentionDays
        val nowEpochSeconds = Clock.System.now().epochSeconds
        val oldestAllowed = nowEpochSeconds - (retentionDays * 86_400L)
        val clampedFrom = max(fromTimestamp, oldestAllowed)
        val clampedTo = min(toTimestamp, nowEpochSeconds)
        if (clampedFrom > clampedTo) return null
        return clampedFrom to clampedTo
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
