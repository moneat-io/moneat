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
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Subscriptions
import com.moneat.uptime.models.CheckResult
import com.moneat.uptime.models.CreateUptimeMonitorRequest
import com.moneat.uptime.models.UpdateUptimeMonitorRequest
import com.moneat.uptime.models.UptimeHeartbeatResponse
import com.moneat.uptime.models.UptimeMonitorData
import com.moneat.uptime.models.UptimeMonitorResponse
import com.moneat.uptime.models.UptimeMonitors
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import java.util.*
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

class UptimeService(
    private val billingQuotaService: BillingQuotaService = BillingQuotaService()
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

        val monitorId = UUID.randomUUID()
        val now = Clock.System.now()

        // Generate push token for push monitors
        val pushToken =
            if (request.type.lowercase() == "push") {
                generatePushToken()
            } else {
                null
            }

        transaction {
            UptimeMonitors.insert {
                it[id] = monitorId
                it[UptimeMonitors.organizationId] = organizationId
                it[name] = request.name
                it[type] = request.type
                it[active] = true

                // Connection
                it[url] = request.url
                it[hostname] = request.hostname
                it[port] = request.port

                // HTTP
                it[method] = request.method
                it[headers] = request.headers?.let { h -> Json.encodeToString(h) }
                it[body] = request.body
                it[authMethod] = request.authMethod
                it[authUser] = request.authUser
                it[authPass] = request.authPass
                it[expectedStatusCodes] = request.expectedStatusCodes
                it[maxRedirects] = request.maxRedirects
                it[ignoreTls] = request.ignoreTls

                // Keyword
                it[keyword] = request.keyword
                it[keywordInverse] = request.keywordInverse

                // JSON Query
                it[jsonPath] = request.jsonPath
                it[jsonExpectedValue] = request.jsonExpectedValue

                // DNS
                it[dnsRecordType] = request.dnsRecordType
                it[dnsExpectedValue] = request.dnsExpectedValue
                it[dnsServer] = request.dnsServer

                // SSL
                it[sslExpiryWarnDays] = request.sslExpiryWarnDays

                // Database
                it[dbConnectionString] = request.dbConnectionString
                it[dbQuery] = request.dbQuery

                // Docker
                it[dockerContainerName] = request.dockerContainerName
                it[dockerHost] = request.dockerHost

                // Check config
                it[intervalSeconds] = request.intervalSeconds
                it[timeoutSeconds] = request.timeoutSeconds
                it[retries] = request.retries
                it[retryIntervalSeconds] = request.retryIntervalSeconds

                // Status
                it[status] = "pending"
                it[lastCheckAt] = null
                it[lastStatusChangeAt] = now
                it[consecutiveFailures] = 0

                it[UptimeMonitors.pushToken] = pushToken
                it[createdAt] = now
                it[updatedAt] = now
            }
        }

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
        val updated =
            transaction {
                UptimeMonitors
                    .selectAll()
                    .where { (UptimeMonitors.id eq monitorId) and (UptimeMonitors.organizationId eq organizationId) }
                    .firstOrNull() ?: return@transaction false

                UptimeMonitors.update({ (UptimeMonitors.id eq monitorId) and (UptimeMonitors.organizationId eq organizationId) }) {
                    request.name?.let { v -> it[name] = v }
                    request.active?.let { v -> it[active] = v }

                    request.url?.let { v -> it[url] = v }
                    request.hostname?.let { v -> it[hostname] = v }
                    request.port?.let { v -> it[port] = v }

                    request.method?.let { v -> it[method] = v }
                    request.headers?.let { v -> it[headers] = Json.encodeToString(v) }
                    request.body?.let { v -> it[body] = v }
                    request.authMethod?.let { v -> it[authMethod] = v }
                    request.authUser?.let { v -> it[authUser] = v }
                    request.authPass?.let { v -> it[authPass] = v }
                    request.expectedStatusCodes?.let { v -> it[expectedStatusCodes] = v }
                    request.maxRedirects?.let { v -> it[maxRedirects] = v }
                    request.ignoreTls?.let { v -> it[ignoreTls] = v }

                    request.keyword?.let { v -> it[keyword] = v }
                    request.keywordInverse?.let { v -> it[keywordInverse] = v }

                    request.jsonPath?.let { v -> it[jsonPath] = v }
                    request.jsonExpectedValue?.let { v -> it[jsonExpectedValue] = v }

                    request.dnsRecordType?.let { v -> it[dnsRecordType] = v }
                    request.dnsExpectedValue?.let { v -> it[dnsExpectedValue] = v }
                    request.dnsServer?.let { v -> it[dnsServer] = v }

                    request.sslExpiryWarnDays?.let { v -> it[sslExpiryWarnDays] = v }

                    request.dbConnectionString?.let { v -> it[dbConnectionString] = v }
                    request.dbQuery?.let { v -> it[dbQuery] = v }

                    request.dockerContainerName?.let { v -> it[dockerContainerName] = v }
                    request.dockerHost?.let { v -> it[dockerHost] = v }

                    request.intervalSeconds?.let { v -> it[intervalSeconds] = v }
                    request.timeoutSeconds?.let { v -> it[timeoutSeconds] = v }
                    request.retries?.let { v -> it[retries] = v }
                    request.retryIntervalSeconds?.let { v -> it[retryIntervalSeconds] = v }

                    it[updatedAt] = Clock.System.now()
                } > 0
            }

        return if (updated) getMonitor(monitorId, organizationId) else null
    }

    /**
     * Delete a monitor.
     */
    fun deleteMonitor(
        monitorId: UUID,
        organizationId: Int
    ): Boolean {
        return transaction {
            UptimeMonitors.deleteWhere {
                (id eq monitorId) and (UptimeMonitors.organizationId eq organizationId)
            } > 0
        }
    }

    /**
     * List all monitors for an organization.
     */
    fun listMonitors(organizationId: Int): List<UptimeMonitorResponse> {
        val monitors =
            transaction {
                UptimeMonitors
                    .selectAll()
                    .where { UptimeMonitors.organizationId eq organizationId }
                    .map { rowToMonitorData(it) }
            }

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
        val monitor =
            transaction {
                UptimeMonitors
                    .selectAll()
                    .where { (UptimeMonitors.id eq monitorId) and (UptimeMonitors.organizationId eq organizationId) }
                    .firstOrNull()
                    ?.let { rowToMonitorData(it) }
            } ?: return null

        return toMonitorResponse(monitor, includeStats = true)
    }

    /**
     * Pause a monitor.
     */
    fun pauseMonitor(
        monitorId: UUID,
        organizationId: Int
    ): Boolean {
        return transaction {
            UptimeMonitors.update({ (UptimeMonitors.id eq monitorId) and (UptimeMonitors.organizationId eq organizationId) }) {
                it[status] = "paused"
                it[active] = false
                it[updatedAt] = Clock.System.now()
            } > 0
        }
    }

    /**
     * Resume a monitor.
     */
    fun resumeMonitor(
        monitorId: UUID,
        organizationId: Int
    ): Boolean {
        return transaction {
            UptimeMonitors.update({ (UptimeMonitors.id eq monitorId) and (UptimeMonitors.organizationId eq organizationId) }) {
                it[active] = true
                it[updatedAt] = Clock.System.now()
            } > 0
        }
    }

    /**
     * Get monitors that need checking.
     */
    fun getMonitorsDueForCheck(): List<UptimeMonitorData> {
        return transaction {
            val now = Clock.System.now()

            UptimeMonitors
                .selectAll()
                .where { UptimeMonitors.active eq true }
                .filter { row ->
                    val lastCheck = row[UptimeMonitors.lastCheckAt]
                    val interval = row[UptimeMonitors.intervalSeconds]

                    if (lastCheck == null) {
                        true // Never checked
                    } else {
                        val nextCheck = lastCheck.plus(interval.toLong().seconds)
                        nextCheck <= now
                    }
                }.map { rowToMonitorData(it) }
        }
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
            ClickHouseClient.execute(sql)
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
        transaction {
            val monitor =
                UptimeMonitors
                    .selectAll()
                    .where { UptimeMonitors.id eq monitorId }
                    .firstOrNull() ?: return@transaction

            val oldStatus = monitor[UptimeMonitors.status]
            val newStatus =
                when (result.status) {
                    1 -> "up"
                    0 -> "down"
                    else -> "pending"
                }

            val statusChanged = oldStatus != newStatus
            val now = Clock.System.now()

            val consecutiveFailures =
                if (result.status == 0) {
                    monitor[UptimeMonitors.consecutiveFailures] + 1
                } else {
                    0
                }

            UptimeMonitors.update({ UptimeMonitors.id eq monitorId }) {
                it[status] = newStatus
                it[lastCheckAt] = now
                it[UptimeMonitors.consecutiveFailures] = consecutiveFailures
                if (statusChanged) {
                    it[lastStatusChangeAt] = now
                }
                it[updatedAt] = now
            }
        }
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
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()

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
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()

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
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()

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
        val currentCount =
            transaction {
                UptimeMonitors
                    .selectAll()
                    .where { UptimeMonitors.organizationId eq organizationId }
                    .count()
            }

        val tier =
            transaction {
                val org =
                    Organizations
                        .selectAll()
                        .where { Organizations.id eq organizationId }
                        .firstOrNull()

                org?.let {
                    val subQuery =
                        Subscriptions
                            .selectAll()
                            .where { Subscriptions.organization_id eq organizationId }
                            .limit(1)
                            .firstOrNull()

                    subQuery?.get(Subscriptions.plan) ?: "FREE"
                } ?: "FREE"
            }

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
        return transaction {
            UptimeMonitors
                .selectAll()
                .where { UptimeMonitors.pushToken eq token }
                .firstOrNull()
                ?.let { rowToMonitorData(it) }
        }
    }

    // Helper methods

    private fun rowToMonitorData(row: ResultRow): UptimeMonitorData {
        return UptimeMonitorData(
            id = row[UptimeMonitors.id],
            organizationId = row[UptimeMonitors.organizationId],
            name = row[UptimeMonitors.name],
            type = row[UptimeMonitors.type],
            active = row[UptimeMonitors.active],
            url = row[UptimeMonitors.url],
            hostname = row[UptimeMonitors.hostname],
            port = row[UptimeMonitors.port],
            method = row[UptimeMonitors.method],
            headers = row[UptimeMonitors.headers],
            body = row[UptimeMonitors.body],
            authMethod = row[UptimeMonitors.authMethod],
            authUser = row[UptimeMonitors.authUser],
            authPass = row[UptimeMonitors.authPass],
            expectedStatusCodes = row[UptimeMonitors.expectedStatusCodes],
            maxRedirects = row[UptimeMonitors.maxRedirects],
            ignoreTls = row[UptimeMonitors.ignoreTls],
            keyword = row[UptimeMonitors.keyword],
            keywordInverse = row[UptimeMonitors.keywordInverse],
            jsonPath = row[UptimeMonitors.jsonPath],
            jsonExpectedValue = row[UptimeMonitors.jsonExpectedValue],
            dnsRecordType = row[UptimeMonitors.dnsRecordType],
            dnsExpectedValue = row[UptimeMonitors.dnsExpectedValue],
            dnsServer = row[UptimeMonitors.dnsServer],
            sslExpiryWarnDays = row[UptimeMonitors.sslExpiryWarnDays],
            dbConnectionString = row[UptimeMonitors.dbConnectionString],
            dbQuery = row[UptimeMonitors.dbQuery],
            dockerContainerName = row[UptimeMonitors.dockerContainerName],
            dockerHost = row[UptimeMonitors.dockerHost],
            intervalSeconds = row[UptimeMonitors.intervalSeconds],
            timeoutSeconds = row[UptimeMonitors.timeoutSeconds],
            retries = row[UptimeMonitors.retries],
            retryIntervalSeconds = row[UptimeMonitors.retryIntervalSeconds],
            status = row[UptimeMonitors.status],
            lastCheckAt = row[UptimeMonitors.lastCheckAt],
            lastStatusChangeAt = row[UptimeMonitors.lastStatusChangeAt],
            consecutiveFailures = row[UptimeMonitors.consecutiveFailures],
            pushToken = row[UptimeMonitors.pushToken],
            createdAt = row[UptimeMonitors.createdAt],
            updatedAt = row[UptimeMonitors.updatedAt]
        )
    }

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
