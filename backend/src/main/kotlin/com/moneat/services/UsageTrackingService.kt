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

package com.moneat.services

import com.moneat.models.Projects
import com.moneat.models.Subscriptions
import com.moneat.models.UsageRecords
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.and
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

data class UsageRecord(
    val organizationId: Int,
    val projectId: Long,
    val eventType: String,
    val recordDate: kotlinx.datetime.LocalDate,
    val eventCount: Int,
    val bytesIngested: Long
)

data class OrgUsageSummary(
    val date: kotlinx.datetime.LocalDate,
    val eventType: String,
    val eventCount: Int,
    val bytesIngested: Long
)

data class QuotaStatus(
    val withinQuota: Boolean,
    val used: Long,
    val limit: Long,
    val plan: String
)

class UsageTrackingService {
    companion object {
        val instance = UsageTrackingService()
    }
    
    private val buffer = ConcurrentHashMap<String, Pair<AtomicInteger, java.util.concurrent.atomic.AtomicLong>>()
    private val flushThreshold = 100
    private val flushIntervalMs = 10_000L
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "usage-tracking-flush").apply { isDaemon = true }
    }
    private val billingQuotaService = BillingQuotaService()

    init {
        scheduler.scheduleAtFixedRate(
            { flushBuffer() },
            flushIntervalMs,
            flushIntervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    /**
     * Record usage. Buffered and flushed periodically or when threshold is reached.
     * projectId must be the project that received the event.
     */
    fun recordUsage(projectId: Long, eventType: String, byteSize: Int = 0) {
        val orgId = getOrganizationId(projectId) ?: return
        val today = Clock.System.todayIn(TimeZone.UTC)
        val key = "$orgId|$projectId|$eventType|$today"

        buffer.compute(key) { _, pair ->
            if (pair == null) {
                Pair(AtomicInteger(1), java.util.concurrent.atomic.AtomicLong(byteSize.toLong()))
            } else {
                pair.first.incrementAndGet()
                pair.second.addAndGet(byteSize.toLong())
                pair
            }
        }

        if (buffer.size >= flushThreshold) {
            flushBuffer()
        }
    }

    private fun getOrganizationId(projectId: Long): Int? {
        return transaction {
            Projects.select(Projects.organization_id)
                .where { Projects.id eq projectId }
                .firstOrNull()
                ?.get(Projects.organization_id)
        }
    }

    fun flushBuffer() {
        if (buffer.isEmpty()) return

        val toFlush = buffer.keys.toList()
        val records = toFlush.mapNotNull { key ->
            val pair = buffer.remove(key) ?: return@mapNotNull null
            val parts = key.split("|")
            if (parts.size != 4) return@mapNotNull null
            val (orgId, projectId, eventType, dateStr) = parts
            UsageRecord(
                organizationId = orgId.toIntOrNull() ?: return@mapNotNull null,
                projectId = projectId.toLongOrNull() ?: return@mapNotNull null,
                eventType = eventType,
                recordDate = kotlinx.datetime.LocalDate.parse(dateStr),
                eventCount = pair.first.get(),
                bytesIngested = pair.second.get()
            )
        }

        if (records.isEmpty()) return

        transaction {
            for (rec in records) {
                upsertUsage(rec)
            }
        }

        logger.debug { "Flushed ${records.size} usage records to PostgreSQL" }
    }

    private fun upsertUsage(rec: UsageRecord) {
        val projectIdInt = rec.projectId.toInt()
        val existing = UsageRecords.selectAll().where {
            (UsageRecords.organization_id eq rec.organizationId) and
                (UsageRecords.project_id eq projectIdInt) and
                (UsageRecords.recordDate eq rec.recordDate) and
                (UsageRecords.event_type eq rec.eventType)
        }.firstOrNull()

        if (existing != null) {
            UsageRecords.update({
                (UsageRecords.organization_id eq rec.organizationId) and
                    (UsageRecords.project_id eq projectIdInt) and
                    (UsageRecords.recordDate eq rec.recordDate) and
                    (UsageRecords.event_type eq rec.eventType)
            }) {
                it[event_count] = existing[UsageRecords.event_count] + rec.eventCount
                it[bytes_ingested] = existing[UsageRecords.bytes_ingested] + rec.bytesIngested
            }
        } else {
            UsageRecords.insert {
                it[organization_id] = rec.organizationId
                it[project_id] = projectIdInt
                it[event_type] = rec.eventType
                it[event_count] = rec.eventCount
                it[bytes_ingested] = rec.bytesIngested
                it[recordDate] = rec.recordDate
            }
        }
    }

    fun getUsageForOrg(orgId: Int, startDate: kotlinx.datetime.LocalDate, endDate: kotlinx.datetime.LocalDate): List<OrgUsageSummary> {
        flushBuffer()
        return transaction {
            UsageRecords.selectAll().where {
                (UsageRecords.organization_id eq orgId) and
                    (UsageRecords.recordDate greaterEq startDate) and
                    (UsageRecords.recordDate lessEq endDate)
            }.groupBy { it[UsageRecords.recordDate] to it[UsageRecords.event_type] }
                .map { (key, rows) ->
                    OrgUsageSummary(
                        date = key.first,
                        eventType = key.second,
                        eventCount = rows.sumOf { it[UsageRecords.event_count] },
                        bytesIngested = rows.sumOf { it[UsageRecords.bytes_ingested] }
                    )
                }
                .sortedWith(compareBy<OrgUsageSummary> { it.date }.thenBy { it.eventType })
        }
    }

    fun getTotalBytesForOrg(orgId: Int, startDate: kotlinx.datetime.LocalDate, endDate: kotlinx.datetime.LocalDate): Long {
        flushBuffer()
        return transaction {
            UsageRecords.selectAll().where {
                (UsageRecords.organization_id eq orgId) and
                    (UsageRecords.recordDate greaterEq startDate) and
                    (UsageRecords.recordDate lessEq endDate)
            }.sumOf { it[UsageRecords.bytes_ingested] }
        }
    }

    fun getEventCountForOrg(
        orgId: Int,
        startDate: kotlinx.datetime.LocalDate,
        endDate: kotlinx.datetime.LocalDate,
        eventTypes: List<String> = emptyList()
    ): Long {
        flushBuffer()
        return transaction {
            UsageRecords.selectAll().where {
                val baseFilter = (UsageRecords.organization_id eq orgId) and
                    (UsageRecords.recordDate greaterEq startDate) and
                    (UsageRecords.recordDate lessEq endDate)
                if (eventTypes.isNotEmpty()) {
                    baseFilter and (UsageRecords.event_type inList eventTypes)
                } else {
                    baseFilter
                }
            }.sumOf { it[UsageRecords.event_count].toLong() }
        }
    }

    fun checkQuota(orgId: Int): QuotaStatus {
        val usage = billingQuotaService.getUsageForOrganization(orgId)
        return QuotaStatus(
            withinQuota = usage.withinQuota,
            used = usage.usedUnits,
            limit = usage.totalLimitUnits,
            plan = usage.plan
        )
    }

    private fun getPlanForOrg(orgId: Int): String {
        return transaction {
            Subscriptions.selectAll().where { (Subscriptions.organization_id eq orgId) and (Subscriptions.status eq "active") }
                .orderBy(Subscriptions.id to SortOrder.DESC)
                .firstOrNull()
                ?.get(Subscriptions.plan)
                ?.lowercase() ?: "free"
        }
    }

    private fun getBillingPeriod(orgId: Int): Pair<kotlinx.datetime.LocalDate, kotlinx.datetime.LocalDate> {
        return transaction {
            val sub = Subscriptions.selectAll().where { (Subscriptions.organization_id eq orgId) and (Subscriptions.status eq "active") }
                .orderBy(Subscriptions.id to SortOrder.DESC)
                .firstOrNull()
            val startTs = sub?.get(Subscriptions.current_period_start)
            val endTs = sub?.get(Subscriptions.current_period_end)
            if (startTs != null && endTs != null) {
                val start = startTs.toLocalDateTime(TimeZone.UTC).date
                val end = endTs.toLocalDateTime(TimeZone.UTC).date
                Pair(start, end)
            } else {
                val today = Clock.System.todayIn(TimeZone.UTC)
                val monthStart = kotlinx.datetime.LocalDate(today.year, today.month, 1)
                Pair(monthStart, today)
            }
        }
    }
}
