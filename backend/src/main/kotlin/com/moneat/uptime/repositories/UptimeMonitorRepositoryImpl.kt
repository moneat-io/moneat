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

package com.moneat.uptime.repositories

import com.moneat.config.ClickHouseClient
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Subscriptions
import com.moneat.uptime.models.CheckResult
import com.moneat.uptime.models.CreateUptimeMonitorRequest
import com.moneat.uptime.models.UpdateUptimeMonitorRequest
import com.moneat.uptime.models.UptimeMonitorData
import com.moneat.uptime.models.UptimeMonitors
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class UptimeMonitorRepositoryImpl : UptimeMonitorRepository {

    override fun getMonitorCountForOrganization(organizationId: Int): Int =
        transaction {
            UptimeMonitors
                .selectAll()
                .where { UptimeMonitors.organizationId eq organizationId }
                .count()
                .toInt()
        }

    override fun existsById(monitorId: UUID): Boolean =
        transaction {
            UptimeMonitors
                .selectAll()
                .where { UptimeMonitors.id eq monitorId }
                .firstOrNull() != null
        }

    override fun create(orgId: Int, request: CreateUptimeMonitorRequest, pushToken: String?): UUID {
        val monitorId = UUID.randomUUID()
        val now = Clock.System.now()
        transaction {
            UptimeMonitors.insert {
                it[id] = monitorId
                it[organizationId] = orgId
                it[name] = request.name
                it[type] = request.type
                it[active] = true
                it[url] = request.url
                it[hostname] = request.hostname
                it[port] = request.port
                it[method] = request.method
                it[headers] = request.headers?.let { h -> Json.encodeToString(h) }
                it[body] = request.body
                it[authMethod] = request.authMethod
                it[authUser] = request.authUser
                it[authPass] = request.authPass
                it[expectedStatusCodes] = request.expectedStatusCodes
                it[maxRedirects] = request.maxRedirects
                it[ignoreTls] = request.ignoreTls
                it[keyword] = request.keyword
                it[keywordInverse] = request.keywordInverse
                it[jsonPath] = request.jsonPath
                it[jsonExpectedValue] = request.jsonExpectedValue
                it[dnsRecordType] = request.dnsRecordType
                it[dnsExpectedValue] = request.dnsExpectedValue
                it[dnsServer] = request.dnsServer
                it[sslExpiryWarnDays] = request.sslExpiryWarnDays
                it[dbConnectionString] = request.dbConnectionString
                it[dbQuery] = request.dbQuery
                it[dockerContainerName] = request.dockerContainerName
                it[dockerHost] = request.dockerHost
                it[intervalSeconds] = request.intervalSeconds
                it[timeoutSeconds] = request.timeoutSeconds
                it[retries] = request.retries
                it[retryIntervalSeconds] = request.retryIntervalSeconds
                it[status] = "pending"
                it[lastCheckAt] = null
                it[lastStatusChangeAt] = now
                it[consecutiveFailures] = 0
                it[UptimeMonitors.pushToken] = pushToken
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        return monitorId
    }

    override fun update(monitorId: UUID, orgId: Int, request: UpdateUptimeMonitorRequest): Boolean =
        transaction {
            UptimeMonitors
                .selectAll()
                .where { (UptimeMonitors.id eq monitorId) and (UptimeMonitors.organizationId eq orgId) }
                .firstOrNull() ?: return@transaction false

            UptimeMonitors.update(
                { (UptimeMonitors.id eq monitorId) and (UptimeMonitors.organizationId eq orgId) }
            ) {
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

    override fun delete(monitorId: UUID, orgId: Int): Boolean =
        transaction {
            UptimeMonitors.deleteWhere {
                (UptimeMonitors.id eq monitorId) and (UptimeMonitors.organizationId eq orgId)
            } > 0
        }

    override fun listByOrganizationId(orgId: Int): List<UptimeMonitorData> =
        transaction {
            UptimeMonitors
                .selectAll()
                .where { UptimeMonitors.organizationId eq orgId }
                .map { rowToMonitorData(it) }
        }

    override fun getByIdAndOrg(monitorId: UUID, orgId: Int): UptimeMonitorData? =
        transaction {
            UptimeMonitors
                .selectAll()
                .where { (UptimeMonitors.id eq monitorId) and (UptimeMonitors.organizationId eq orgId) }
                .firstOrNull()
                ?.let { rowToMonitorData(it) }
        }

    override fun pause(monitorId: UUID, orgId: Int): Boolean =
        transaction {
            UptimeMonitors.update(
                { (UptimeMonitors.id eq monitorId) and (UptimeMonitors.organizationId eq orgId) }
            ) {
                it[status] = "paused"
                it[active] = false
                it[updatedAt] = Clock.System.now()
            } > 0
        }

    override fun resume(monitorId: UUID, orgId: Int): Boolean =
        transaction {
            UptimeMonitors.update(
                { (UptimeMonitors.id eq monitorId) and (UptimeMonitors.organizationId eq orgId) }
            ) {
                it[active] = true
                it[updatedAt] = Clock.System.now()
            } > 0
        }

    override fun getMonitorsDueForCheck(): List<UptimeMonitorData> =
        transaction {
            val now = Clock.System.now()
            UptimeMonitors
                .selectAll()
                .where { UptimeMonitors.active eq true }
                .filter { row ->
                    val lastCheck = row[UptimeMonitors.lastCheckAt]
                    val interval = row[UptimeMonitors.intervalSeconds]
                    if (lastCheck == null) {
                        true
                    } else {
                        val nextCheck = lastCheck.plus(interval.toLong().seconds)
                        nextCheck <= now
                    }
                }.map { rowToMonitorData(it) }
        }

    override fun updateStatus(monitorId: UUID, result: CheckResult): Boolean =
        transaction {
            val monitor =
                UptimeMonitors
                    .selectAll()
                    .where { UptimeMonitors.id eq monitorId }
                    .firstOrNull() ?: return@transaction false

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
            } > 0
        }

    override fun getByPushToken(token: String): UptimeMonitorData? =
        transaction {
            UptimeMonitors
                .selectAll()
                .where { UptimeMonitors.pushToken eq token }
                .firstOrNull()
                ?.let { rowToMonitorData(it) }
        }

    override fun getOrganizationTier(orgId: Int): String =
        transaction {
            val org =
                Organizations
                    .selectAll()
                    .where { Organizations.id eq orgId }
                    .firstOrNull()
            org?.let {
                Subscriptions
                    .selectAll()
                    .where { Subscriptions.organization_id eq orgId }
                    .limit(1)
                    .firstOrNull()
                    ?.get(Subscriptions.plan) ?: "FREE"
            } ?: "FREE"
        }

    override suspend fun executeClickHouseQuery(sql: String): String {
        val response = ClickHouseClient.execute(sql)
        return response.bodyAsText()
    }

    override suspend fun executeClickHouseInsert(sql: String): Boolean {
        ClickHouseClient.execute(sql)
        return true
    }

    fun rowToMonitorData(row: ResultRow): UptimeMonitorData =
        UptimeMonitorData(
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
