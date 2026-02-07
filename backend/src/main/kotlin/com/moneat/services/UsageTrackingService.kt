package com.moneat.services

import com.moneat.models.PricingTier
import com.moneat.models.Projects
import com.moneat.models.Subscriptions
import com.moneat.models.UsageRecords
import kotlinx.datetime.*
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
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
            Projects.slice(Projects.organization_id)
                .select { Projects.id eq projectId }
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
        val existing = UsageRecords.select {
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
            UsageRecords.select {
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

    fun checkQuota(orgId: Int): QuotaStatus {
        flushBuffer()
        val plan = getPlanForOrg(orgId)
        val tier = PricingTier.entries.find { it.name.equals(plan, ignoreCase = true) } ?: PricingTier.FREE
        val (periodStart, periodEnd) = getBillingPeriod(orgId)

        val used = transaction {
            UsageRecords.select {
                (UsageRecords.organization_id eq orgId) and
                    (UsageRecords.recordDate greaterEq periodStart) and
                    (UsageRecords.recordDate lessEq periodEnd) and
                    (UsageRecords.event_type eq "error")
            }.sumOf { it[UsageRecords.event_count].toLong() }
        }

        val limit = tier.monthlyErrorLimit
        return QuotaStatus(
            withinQuota = used <= limit,
            used = used,
            limit = limit,
            plan = plan
        )
    }

    private fun getPlanForOrg(orgId: Int): String {
        return transaction {
            Subscriptions.select { (Subscriptions.organization_id eq orgId) and (Subscriptions.status eq "active") }
                .orderBy(Subscriptions.id to SortOrder.DESC)
                .firstOrNull()
                ?.get(Subscriptions.plan)
                ?.lowercase() ?: "free"
        }
    }

    private fun getBillingPeriod(orgId: Int): Pair<kotlinx.datetime.LocalDate, kotlinx.datetime.LocalDate> {
        return transaction {
            val sub = Subscriptions.select { (Subscriptions.organization_id eq orgId) and (Subscriptions.status eq "active") }
                .orderBy(Subscriptions.id to SortOrder.DESC)
                .firstOrNull()
            val startTs = sub?.get(Subscriptions.current_period_start)
            val endTs = sub?.get(Subscriptions.current_period_end)
            if (startTs != null && endTs != null) {
                val start = (startTs as Instant).toLocalDateTime(TimeZone.UTC).date
                val end = (endTs as Instant).toLocalDateTime(TimeZone.UTC).date
                Pair(start, end)
            } else {
                val today = Clock.System.todayIn(TimeZone.UTC)
                val monthStart = kotlinx.datetime.LocalDate(today.year, today.month, 1)
                Pair(monthStart, today)
            }
        }
    }
}
