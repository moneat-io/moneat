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

package com.moneat.uptime.services

import com.moneat.billing.services.BillingQuotaService
import com.moneat.config.ClickHouseClient
import com.moneat.config.EnvConfig
import com.moneat.uptime.models.CheckResult
import com.moneat.uptime.models.CreateUptimeMonitorRequest
import com.moneat.uptime.models.UpdateUptimeMonitorRequest
import com.moneat.uptime.models.UptimeHeartbeatResponse
import com.moneat.uptime.models.UptimeMonitorData
import com.moneat.uptime.models.UptimeMonitorResponse
import com.moneat.uptime.repositories.UptimeMonitorRepository
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mu.KotlinLogging
import java.security.SecureRandom
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

class UptimeService(
    private val billingQuotaService: BillingQuotaService,
    private val uptimeMonitorRepository: UptimeMonitorRepository
) {

    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()

    companion object {
        private const val FREE_TIER_QUOTA = 3
        private const val PRO_TIER_QUOTA = 10
        private const val TEAM_TIER_QUOTA = 25
        private const val BUSINESS_TIER_QUOTA = Int.MAX_VALUE
        private const val DEFAULT_TIER_QUOTA = 3
    }

    /**
     * Create a new uptime monitor.
     */
    fun createMonitor(
        organizationId: Int,
        request: CreateUptimeMonitorRequest
    ): UptimeMonitorResponse {
        // Check quota
        checkUptimeMonitorQuota(organizationId)

        // Generate push token for push monitors
        val pushToken =
            if (request.type.lowercase() == "push") {
                generatePushToken()
            } else {
                null
            }

        val monitorId = uptimeMonitorRepository.create(organizationId, request, pushToken)

        return getMonitor(monitorId, organizationId)!!
    }

    /**
     * Update an existing monitor.
     */
    fun updateMonitor(
        monitorId: UUID,
        organizationId: Int,
        request: UpdateUptimeMonitorRequest
    ): UptimeMonitorResponse? {
        val updated = uptimeMonitorRepository.update(monitorId, organizationId, request)

        return if (updated) getMonitor(monitorId, organizationId) else null
    }

    /**
     * Delete a monitor.
     */
    fun deleteMonitor(
        monitorId: UUID,
        organizationId: Int
    ): Boolean {
        return uptimeMonitorRepository.delete(monitorId, organizationId)
    }

    /**
     * List all monitors for an organization.
     */
    fun listMonitors(organizationId: Int): List<UptimeMonitorResponse> {
        val monitors = uptimeMonitorRepository.listByOrganizationId(organizationId)

        return monitors.map { monitor ->
            toMonitorResponse(monitor, includeStats = false)
        }
    }

    /**
     * Get a single monitor with stats.
     */
    fun getMonitor(
        monitorId: UUID,
        organizationId: Int
    ): UptimeMonitorResponse? {
        val monitor = uptimeMonitorRepository.getByIdAndOrg(monitorId, organizationId) ?: return null

        return toMonitorResponse(monitor, includeStats = true)
    }

    /**
     * Pause a monitor.
     */
    fun pauseMonitor(
        monitorId: UUID,
        organizationId: Int
    ): Boolean {
        return uptimeMonitorRepository.pause(monitorId, organizationId)
    }

    /**
     * Resume a monitor.
     */
    fun resumeMonitor(
        monitorId: UUID,
        organizationId: Int
    ): Boolean {
        return uptimeMonitorRepository.resume(monitorId, organizationId)
    }

    /**
     * Get monitors that need checking.
     */
    fun getMonitorsDueForCheck(): List<UptimeMonitorData> {
        return uptimeMonitorRepository.getMonitorsDueForCheck()
    }

    /**
     * Record a heartbeat result.
     */
    suspend fun recordHeartbeat(
        monitorId: UUID,
        result: CheckResult
    ) {
        val timestamp = Clock.System.now().toEpochMilliseconds() / 1000.0

        val sql =
            """
            INSERT INTO `$clickhouseDb`.uptime_heartbeats 
            (monitor_id, timestamp, status, response_time_ms, status_code, message, ping_ms)
            VALUES (
                '$monitorId',
                fromUnixTimestamp64Milli(${(timestamp * 1000).toLong()}),
                ${result.status},
                ${result.responseTimeMs},
                ${result.statusCode},
                '${escapeSql(result.message)}',
                ${result.pingMs}
            )
            """.trimIndent()

        try {
            uptimeMonitorRepository.executeClickHouseInsert(sql)
        } catch (e: Exception) {
            logger.error(e) { "Failed to record heartbeat for monitor $monitorId" }
        }
    }

    /**
     * Update monitor status after a check.
     */
    fun updateMonitorStatus(
        monitorId: UUID,
        result: CheckResult
    ) {
        uptimeMonitorRepository.updateStatus(monitorId, result)
    }

    /**
     * Get heartbeats for a monitor.
     */
    suspend fun getHeartbeats(
        monitorId: UUID,
        from: Instant,
        to: Instant
    ): List<UptimeHeartbeatResponse> {
        val query =
            """
            SELECT 
                toUnixTimestamp64Milli(timestamp) as ts,
                status,
                response_time_ms,
                status_code,
                message,
                ping_ms
            FROM `$clickhouseDb`.uptime_heartbeats
            WHERE monitor_id = '$monitorId'
              AND timestamp >= fromUnixTimestamp64Milli(${from.toEpochMilliseconds()})
              AND timestamp <= fromUnixTimestamp64Milli(${to.toEpochMilliseconds()})
            ORDER BY timestamp DESC
            LIMIT 1000
            FORMAT JSONEachRow
            """.trimIndent()

        return try {
            val body = uptimeMonitorRepository.executeClickHouseQuery(query)

            if (body.isBlank()) return emptyList()

            body.trim().lines().mapNotNull { line ->
                try {
                    val json = Json.parseToJsonElement(line).jsonObject
                    UptimeHeartbeatResponse(
                        timestamp = json["ts"]?.jsonPrimitive?.long ?: 0L,
                        status = json["status"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        responseTimeMs = json["response_time_ms"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1,
                        statusCode = json["status_code"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        message = json["message"]?.jsonPrimitive?.content ?: "",
                        pingMs = json["ping_ms"]?.jsonPrimitive?.content?.toFloatOrNull()
                    )
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse heartbeat: $line" }
                    null
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to query heartbeats for monitor $monitorId" }
            emptyList()
        }
    }

    /**
     * Calculate uptime percentage for a period.
     */
    suspend fun getUptimePercentage(
        monitorId: UUID,
        hours: Int
    ): Float {
        val now = Clock.System.now()
        val from = now.minus(hours.hours)

        val query =
            """
            SELECT 
                countIf(status = 1) as up_count,
                countIf(status = 0) as down_count,
                count() as total_count
            FROM `$clickhouseDb`.uptime_heartbeats
            WHERE monitor_id = '$monitorId'
              AND timestamp >= fromUnixTimestamp64Milli(${from.toEpochMilliseconds()})
            FORMAT JSONEachRow
            """.trimIndent()

        return try {
            val body = uptimeMonitorRepository.executeClickHouseQuery(query)

            if (body.isBlank()) return 0f

            val json = Json.parseToJsonElement(body.trim().lines().first()).jsonObject
            val upCount = json["up_count"]?.jsonPrimitive?.long ?: 0L
            val totalCount = json["total_count"]?.jsonPrimitive?.long ?: 0L

            if (totalCount == 0L) 0f else (upCount.toFloat() / totalCount.toFloat() * 100f)
        } catch (e: Exception) {
            logger.error(e) { "Failed to calculate uptime for monitor $monitorId" }
            0f
        }
    }

    /**
     * Get average response time for a period.
     */
    suspend fun getAverageResponseTime(
        monitorId: UUID,
        hours: Int
    ): Int {
        val now = Clock.System.now()
        val from = now.minus(hours.hours)

        val query =
            """
            SELECT avg(response_time_ms) as avg_time
            FROM `$clickhouseDb`.uptime_heartbeats
            WHERE monitor_id = '$monitorId'
              AND timestamp >= fromUnixTimestamp64Milli(${from.toEpochMilliseconds()})
              AND response_time_ms >= 0
            FORMAT JSONEachRow
            """.trimIndent()

        return try {
            val body = uptimeMonitorRepository.executeClickHouseQuery(query)

            if (body.isBlank()) return 0

            val json = Json.parseToJsonElement(body.trim().lines().first()).jsonObject
            json["avg_time"]
                ?.jsonPrimitive
                ?.content
                ?.toDoubleOrNull()
                ?.roundToInt() ?: 0
        } catch (e: Exception) {
            logger.error(e) { "Failed to calculate avg response time for monitor $monitorId" }
            0
        }
    }

    /**
     * Check uptime monitor quota for organization.
     */
    fun checkUptimeMonitorQuota(organizationId: Int) {
        if (EnvConfig.SelfHost.enabled) return
        val currentCount = uptimeMonitorRepository.getMonitorCountForOrganization(organizationId)

        val tier = uptimeMonitorRepository.getOrganizationTier(organizationId)

        val limit =
            when (tier) {
                "FREE" -> FREE_TIER_QUOTA
                "PRO" -> PRO_TIER_QUOTA
                "TEAM" -> TEAM_TIER_QUOTA
                "BUSINESS" -> BUSINESS_TIER_QUOTA
                else -> DEFAULT_TIER_QUOTA
            }

        if (currentCount >= limit) {
            throw IllegalStateException("Uptime monitor limit reached ($limit for $tier tier)")
        }
    }

    /**
     * Get monitor by push token.
     */
    fun getMonitorByPushToken(token: String): UptimeMonitorData? {
        return uptimeMonitorRepository.getByPushToken(token)
    }

    // Helper methods

    private fun toMonitorResponse(
        monitor: UptimeMonitorData,
        includeStats: Boolean
    ): UptimeMonitorResponse {
        val stats =
            if (includeStats) {
                // Run async calls synchronously (in real impl, could be suspended)
                kotlinx.coroutines.runBlocking {
                    Triple(
                        getUptimePercentage(monitor.id, 24),
                        getUptimePercentage(monitor.id, 168),
                        getUptimePercentage(monitor.id, 720)
                    )
                }
            } else {
                Triple(null, null, null)
            }

        val avgResponseTime =
            if (includeStats) {
                kotlinx.coroutines.runBlocking {
                    getAverageResponseTime(monitor.id, 24)
                }
            } else {
                null
            }

        return UptimeMonitorResponse(
            id = monitor.id.toString(),
            organizationId = monitor.organizationId,
            name = monitor.name,
            type = monitor.type,
            active = monitor.active,
            url = monitor.url,
            hostname = monitor.hostname,
            port = monitor.port,
            method = monitor.method,
            headers =
            monitor.headers?.let {
                try { Json.decodeFromString<Map<String, String>>(it) } catch (e: Exception) { null }
            },
            body = monitor.body,
            authMethod = monitor.authMethod,
            authUser = monitor.authUser,
            expectedStatusCodes = monitor.expectedStatusCodes,
            maxRedirects = monitor.maxRedirects,
            ignoreTls = monitor.ignoreTls,
            keyword = monitor.keyword,
            keywordInverse = monitor.keywordInverse,
            jsonPath = monitor.jsonPath,
            jsonExpectedValue = monitor.jsonExpectedValue,
            dnsRecordType = monitor.dnsRecordType,
            dnsExpectedValue = monitor.dnsExpectedValue,
            dnsServer = monitor.dnsServer,
            sslExpiryWarnDays = monitor.sslExpiryWarnDays,
            dbConnectionString = monitor.dbConnectionString,
            dbQuery = monitor.dbQuery,
            dockerContainerName = monitor.dockerContainerName,
            dockerHost = monitor.dockerHost,
            intervalSeconds = monitor.intervalSeconds,
            timeoutSeconds = monitor.timeoutSeconds,
            retries = monitor.retries,
            retryIntervalSeconds = monitor.retryIntervalSeconds,
            status = monitor.status,
            lastCheckAt = monitor.lastCheckAt?.toEpochMilliseconds(),
            lastStatusChangeAt = monitor.lastStatusChangeAt?.toEpochMilliseconds(),
            consecutiveFailures = monitor.consecutiveFailures,
            pushToken = if (monitor.type.lowercase() == "push") monitor.pushToken else null,
            uptime24h = stats.first,
            uptime7d = stats.second,
            uptime30d = stats.third,
            avgResponseTime = avgResponseTime,
            createdAt = monitor.createdAt.toEpochMilliseconds(),
            updatedAt = monitor.updatedAt.toEpochMilliseconds()
        )
    }

    private fun generatePushToken(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
