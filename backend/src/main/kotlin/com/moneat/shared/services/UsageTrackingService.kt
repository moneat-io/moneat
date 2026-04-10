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

package com.moneat.shared.services

import com.moneat.billing.services.BillingQuotaService
import com.moneat.shared.models.Projects
import com.moneat.shared.models.UsageRecords
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock

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

        /** Sentinel project ID for org-level usage (logs, etc.) when no specific project applies. */
        const val ORG_PROJECT_ID_SENTINEL = 0L
        private const val USAGE_RECORD_PARTS_COUNT = 4
    }

    private val buffer = ConcurrentHashMap<String, Pair<AtomicInteger, java.util.concurrent.atomic.AtomicLong>>()
    private val flushThreshold = 100
    private val flushIntervalMs = 10_000L
    private val scheduler =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "usage-tracking-flush").apply { isDaemon = true }
        }
    private val billingQuotaService = BillingQuotaService()
    private val orgIdCache = ConcurrentHashMap<Long, Int>()

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
    fun recordUsage(
        projectId: Long,
        eventType: String,
        byteSize: Int = 0
    ) {
        val orgId = getOrganizationId(projectId) ?: return
        recordUsageInternal(orgId, projectId, eventType, 1, byteSize)
    }

    /**
     * Record org-scoped usage (e.g. logs). Uses ORG_PROJECT_ID_SENTINEL for org-level events.
     */
    fun recordOrgUsage(
        organizationId: Int,
        eventType: String,
        byteSize: Int = 0
    ) {
        recordUsageInternal(organizationId, ORG_PROJECT_ID_SENTINEL, eventType, 1, byteSize)
    }

    /**
     * Record org-scoped usage with explicit count (e.g. APM spans, custom metrics).
     */
    fun recordOrgUsage(
        organizationId: Int,
        eventType: String,
        count: Int,
        byteSize: Int = 0
    ) {
        if (count <= 0) return
        recordUsageInternal(organizationId, ORG_PROJECT_ID_SENTINEL, eventType, count, byteSize)
    }

    private fun recordUsageInternal(
        orgId: Int,
        projectId: Long,
        eventType: String,
        count: Int,
        byteSize: Int
    ) {
        if (count <= 0) return
        val today = Clock.System.todayIn(TimeZone.UTC)
        val key = "$orgId|$projectId|$eventType|$today"

        buffer.compute(key) { _, pair ->
            if (pair == null) {
                Pair(
                    AtomicInteger(count),
                    java.util.concurrent.atomic.AtomicLong(byteSize.toLong())
                )
            } else {
                pair.first.addAndGet(count)
                pair.second.addAndGet(byteSize.toLong())
                pair
            }
        }

        if (buffer.size >= flushThreshold) {
            flushBuffer()
        }
    }

    private fun getOrganizationId(projectId: Long): Int? {
        orgIdCache[projectId]?.let { return it }

        val orgId =
            transaction {
                Projects
                    .select(Projects.organization_id)
                    .where { Projects.id eq projectId }
                    .firstOrNull()
                    ?.get(Projects.organization_id)
            }

        if (orgId != null) {
            orgIdCache.putIfAbsent(projectId, orgId)
        }

        return orgId
    }

    fun flushBuffer() {
        if (buffer.isEmpty()) return

        val toFlush = buffer.keys.toList()
        val records =
            toFlush.mapNotNull { key ->
                val pair = buffer.remove(key) ?: return@mapNotNull null
                val parts = key.split("|")
                if (parts.size != USAGE_RECORD_PARTS_COUNT) return@mapNotNull null
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

        var flushed = 0
        for (rec in records) {
            try {
                transaction {
                    upsertUsage(rec)
                }
                flushed++
            } catch (e: org.jetbrains.exposed.v1.exceptions.ExposedSQLException) {
                logger.warn { "Skipping usage record for org ${rec.organizationId}: ${e.message}" }
            }
        }

        logger.debug { "Flushed $flushed/${records.size} usage records to PostgreSQL" }
    }

    private fun upsertUsage(rec: UsageRecord) {
        // Use null for org-level records (sentinel value) to satisfy the FK constraint on project_id
        val projectIdInt: Int? = if (rec.projectId == ORG_PROJECT_ID_SENTINEL) null else rec.projectId.toInt()
        val existing =
            UsageRecords
                .selectAll()
                .where {
                    val baseFilter =
                        (UsageRecords.organization_id eq rec.organizationId) and
                            (UsageRecords.recordDate eq rec.recordDate) and
                            (UsageRecords.event_type eq rec.eventType)
                    if (projectIdInt != null) {
                        baseFilter and (UsageRecords.project_id eq projectIdInt)
                    } else {
                        baseFilter and UsageRecords.project_id.isNull()
                    }
                }.firstOrNull()

        if (existing != null) {
            UsageRecords.update({
                val baseFilter =
                    (UsageRecords.organization_id eq rec.organizationId) and
                        (UsageRecords.recordDate eq rec.recordDate) and
                        (UsageRecords.event_type eq rec.eventType)
                if (projectIdInt != null) {
                    baseFilter and (UsageRecords.project_id eq projectIdInt)
                } else {
                    baseFilter and UsageRecords.project_id.isNull()
                }
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

    fun getUsageForOrg(
        orgId: Int,
        startDate: kotlinx.datetime.LocalDate,
        endDate: kotlinx.datetime.LocalDate
    ): List<OrgUsageSummary> {
        flushBuffer()
        return transaction {
            UsageRecords
                .selectAll()
                .where {
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
                }.sortedWith(compareBy<OrgUsageSummary> { it.date }.thenBy { it.eventType })
        }
    }

    fun getTotalBytesForOrg(
        orgId: Int,
        startDate: kotlinx.datetime.LocalDate,
        endDate: kotlinx.datetime.LocalDate
    ): Long {
        flushBuffer()
        return transaction {
            UsageRecords
                .selectAll()
                .where {
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
            UsageRecords
                .selectAll()
                .where {
                    val baseFilter =
                        (UsageRecords.organization_id eq orgId) and
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
}
