package com.moneat.services

import com.moneat.models.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.*
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

private val logger = KotlinLogging.logger {}

class MonitorService {
    private val config = ApplicationConfig("application.conf")
    private val clickhouseUrl = config.property("database.clickhouse.url").getString()
    private val clickhouseDb = config.property("database.clickhouse.database").getString()
    private val clickhouseUser = config.property("database.clickhouse.user").getString()
    private val clickhousePassword = config.property("database.clickhouse.password").getString()
    private val httpClient = HttpClient(CIO)
    
    /**
     * Create a new system and generate an agent key.
     */
    fun createSystem(organizationId: Int, name: String): Pair<SystemData, String> {
        val agentKey = generateAgentKey()
        val agentKeyHash = hashAgentKey(agentKey)
        val systemId = UUID.randomUUID()
        val now = Clock.System.now()
        
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
        }
        
        val system = getSystemById(systemId)!!
        return Pair(system, agentKey)
    }
    
    /**
     * Validate agent key and return system ID + organization ID.
     */
    fun validateAgentKey(agentKey: String): Pair<UUID, Int>? {
        val keyHash = hashAgentKey(agentKey)
        return transaction {
            Systems.select { Systems.agent_key_hash eq keyHash }
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
        val tier = getPricingTier(organizationId)
        val currentCount = transaction {
            Systems.select { Systems.organization_id eq organizationId }.count()
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
        
        val response = httpClient.post("$clickhouseUrl") {
            parameter("database", clickhouseDb)
            parameter("user", clickhouseUser)
            parameter("password", clickhousePassword)
            contentType(ContentType.Text.Plain)
            setBody(query)
        }
        
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
            
            val containerResponse = httpClient.post("$clickhouseUrl") {
                parameter("database", clickhouseDb)
                parameter("user", clickhouseUser)
                parameter("password", clickhousePassword)
                contentType(ContentType.Text.Plain)
                setBody(containerQuery)
            }
            
            if (!containerResponse.status.isSuccess()) {
                val errorBody = containerResponse.bodyAsText()
                logger.warn { "Failed to insert container metrics: $errorBody" }
            }
        }
        
        // Return the poll interval for this organization's tier
        val tier = getPricingTier(organizationId)
        return tier.monitorIntervalSeconds
    }
    
    /**
     * List all systems for an organization.
     */
    fun listSystems(organizationId: Int): List<SystemData> {
        return transaction {
            Systems.select { Systems.organization_id eq organizationId }
                .orderBy(Systems.created_at to SortOrder.DESC)
                .map { rowToSystemData(it) }
        }
    }
    
    /**
     * Get a single system by ID.
     */
    fun getSystemById(systemId: UUID): SystemData? {
        return transaction {
            Systems.select { Systems.id eq systemId }
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
        val query = """
            SELECT 
                cpu_percent, mem_total, mem_used, disk_total, disk_used,
                net_recv_bytes, net_sent_bytes, load_1, temp_max, gpu_percent, battery_percent
            FROM $clickhouseDb.system_metrics
            WHERE system_id = toUUID('$systemId')
            ORDER BY timestamp DESC
            LIMIT 1
            FORMAT JSONCompact
        """.trimIndent()
        
        val response = httpClient.post("$clickhouseUrl") {
            parameter("database", clickhouseDb)
            parameter("user", clickhouseUser)
            parameter("password", clickhousePassword)
            contentType(ContentType.Text.Plain)
            setBody(query)
        }
        
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
            val load1 = data.getOrNull(7)?.toString()?.toFloatOrNull() ?: 0f
            val tempMax = data.getOrNull(8)?.toString()?.toFloatOrNull()
            val gpuPercent = data.getOrNull(9)?.toString()?.toFloatOrNull()
            val batteryPercent = data.getOrNull(10)?.toString()?.toFloatOrNull()
            
            return LatestMetrics(
                cpu_percent = cpuPercent,
                mem_percent = if (memTotal > 0) (memUsed.toFloat() / memTotal * 100) else 0f,
                disk_percent = if (diskTotal > 0) (diskUsed.toFloat() / diskTotal * 100) else 0f,
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
    ): HistoricalMetricsResponse {
        // Auto-calculate interval if not provided
        val timeRange = toTimestamp - fromTimestamp
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
              AND timestamp >= fromUnixTimestamp($fromTimestamp)
              AND timestamp <= fromUnixTimestamp($toTimestamp)
            GROUP BY ts
            ORDER BY ts
            FORMAT JSONCompact
        """.trimIndent()
        
        val response = httpClient.post("$clickhouseUrl") {
            parameter("database", clickhouseDb)
            parameter("user", clickhouseUser)
            parameter("password", clickhousePassword)
            contentType(ContentType.Text.Plain)
            setBody(query)
        }
        
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error { "Failed to fetch historical metrics: $errorBody" }
            return HistoricalMetricsResponse(
                system_id = systemId.toString(),
                from = fromTimestamp,
                to = toTimestamp,
                interval_seconds = calculatedInterval,
                data_points = emptyList()
            )
        }
        
        val body = response.bodyAsText()
        val dataPoints = try {
            val json = Json { ignoreUnknownKeys = true }
            val result = json.parseToJsonElement(body).jsonObject
            val data = result["data"]?.jsonArray ?: return HistoricalMetricsResponse(
                system_id = systemId.toString(),
                from = fromTimestamp,
                to = toTimestamp,
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
        
        return HistoricalMetricsResponse(
            system_id = systemId.toString(),
            from = fromTimestamp,
            to = toTimestamp,
            interval_seconds = calculatedInterval,
            data_points = dataPoints
        )
    }
    
    /**
     * Get latest container stats from ClickHouse.
     */
    suspend fun getLatestContainers(systemId: UUID): List<ContainerStats> {
        val query = """
            SELECT 
                container_name, container_id, image, status,
                cpu_percent, mem_used, mem_limit
            FROM (
                SELECT *,
                    ROW_NUMBER() OVER (PARTITION BY container_name ORDER BY timestamp DESC) as rn
                FROM $clickhouseDb.container_metrics
                WHERE system_id = toUUID('$systemId')
                  AND timestamp >= now() - INTERVAL 5 MINUTE
            ) WHERE rn = 1
            FORMAT JSONCompact
        """.trimIndent()
        
        val response = httpClient.post("$clickhouseUrl") {
            parameter("database", clickhouseDb)
            parameter("user", clickhouseUser)
            parameter("password", clickhousePassword)
            contentType(ContentType.Text.Plain)
            setBody(query)
        }
        
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
        val timeRange = toTimestamp - fromTimestamp
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
              AND timestamp >= fromUnixTimestamp($fromTimestamp)
              AND timestamp <= fromUnixTimestamp($toTimestamp)
            GROUP BY ts
            ORDER BY ts
            FORMAT JSONCompact
        """.trimIndent()
        
        val response = httpClient.post("$clickhouseUrl") {
            parameter("database", clickhouseDb)
            parameter("user", clickhouseUser)
            parameter("password", clickhousePassword)
            contentType(ContentType.Text.Plain)
            setBody(query)
        }
        
        if (!response.status.isSuccess()) {
            return ContainerMetricsResponse(
                container_name = containerName,
                from = fromTimestamp,
                to = toTimestamp,
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
                from = fromTimestamp,
                to = toTimestamp,
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
            from = fromTimestamp,
            to = toTimestamp,
            interval_seconds = calculatedInterval,
            data_points = dataPoints
        )
    }
    
    /**
     * List all alerts for a system.
     */
    fun listAlerts(systemId: UUID): List<AlertResponse> {
        return transaction {
            SystemAlerts.select { SystemAlerts.system_id eq systemId }
                .orderBy(SystemAlerts.created_at to SortOrder.DESC)
                .map { row ->
                    AlertResponse(
                        id = row[SystemAlerts.id],
                        systemId = row[SystemAlerts.system_id].toString(),
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
    
    /**
     * Create an alert for a system.
     */
    fun createAlert(systemId: UUID, organizationId: Int, request: CreateAlertRequest): AlertResponse {
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
        request: UpdateAlertRequest
    ): Boolean {
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
    fun deleteAlert(alertId: Int, systemId: UUID, organizationId: Int): Boolean {
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
    
    private fun getPricingTier(organizationId: Int): PricingTier {
        val plan = transaction {
            Subscriptions.select { 
                (Subscriptions.organization_id eq organizationId) and 
                (Subscriptions.status eq "active") 
            }
                .orderBy(Subscriptions.id to SortOrder.DESC)
                .firstOrNull()
                ?.get(Subscriptions.plan)
                ?.lowercase() ?: "free"
        }
        return PricingTier.entries.find { it.name.equals(plan, ignoreCase = true) } ?: PricingTier.FREE
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
