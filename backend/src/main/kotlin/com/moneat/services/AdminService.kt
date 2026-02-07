package com.moneat.services

import com.moneat.models.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

private val logger = KotlinLogging.logger {}

@Serializable
data class AdminOverviewStats(
    val totalOrganizations: Int,
    val totalUsers: Int,
    val totalEventsAllTime: Long,
    val totalEventsLast30Days: Long,
    val mrr: Double,
    val subscriptionsByPlan: Map<String, Int>,
    val eventsLast30Days: List<AdminTimelinePoint>
)

@Serializable
data class AdminTimelinePoint(
    val date: String,
    val count: Long
)

@Serializable
data class AdminOrgSummary(
    val id: Int,
    val name: String,
    val slug: String,
    val plan: String,
    val eventCountThisMonth: Long,
    val projectCount: Int,
    val memberCount: Int,
    val quotaUsedPercent: Double?
)

@Serializable
data class AdminOrgDetail(
    val id: Int,
    val name: String,
    val slug: String,
    val companySize: String?,
    val plan: String,
    val subscriptionStatus: String?,
    val memberCount: Int,
    val projectCount: Int,
    val eventCountThisMonth: Long,
    val bytesIngestedThisMonth: Long,
    val quotaUsedPercent: Double?,
    val members: List<AdminOrgMember>,
    val projects: List<AdminOrgProject>
)

@Serializable
data class AdminOrgMember(
    val userId: Int,
    val email: String,
    val name: String?,
    val role: String
)

@Serializable
data class AdminOrgProject(
    val id: Long,
    val name: String,
    val slug: String,
    val platform: String?
)

@Serializable
data class AdminUsageBreakdown(
    val daily: List<DailyUsageByType>,
    val totalBytes: Long
)

@Serializable
data class DailyUsageByType(
    val date: String,
    val error: Long,
    val transaction: Long,
    val replay: Long,
    val feedback: Long,
    val total: Long
)

@Serializable
data class AdminRevenueMetrics(
    val mrr: Double,
    val subscriptionsByPlan: Map<String, Int>,
    val estimatedCostPerOrg: Map<String, Double>,
    val churnLast30Days: Int
)

@Serializable
data class AdminInfrastructureHealth(
    val clickhouseTables: List<TableSize>,
    val totalDiskBytes: Long,
    val totalRows: Long,
    val storageUsedPercent: Double,
    val scalingTriggerAlerts: List<String>
)

@Serializable
data class TableSize(
    val table: String,
    val rows: Long,
    val bytesOnDisk: Long,
    val bytesOnDiskFormatted: String
)

@Serializable
data class AdminTopConsumer(
    val orgId: Int,
    val orgName: String,
    val orgSlug: String,
    val plan: String,
    val eventCount: Long,
    val bytesIngested: Long
)

class AdminService {
    private val config = ApplicationConfig("application.conf")
    private val clickhouseUrl = config.property("database.clickhouse.url").getString()
    private val clickhouseDb = config.property("database.clickhouse.database").getString()
    private val clickhouseUser = config.property("database.clickhouse.user").getString()
    private val clickhousePassword = config.property("database.clickhouse.password").getString()
    private val usageTracker = UsageTrackingService.instance
    private val httpClient = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }

    private val usableStorageBytes = 35L * 1024 * 1024 * 1024 // 35GB from MONETIZATION.md

    suspend fun getOverviewStats(): AdminOverviewStats {
        usageTracker.flushBuffer()
        val today = Clock.System.todayIn(TimeZone.UTC)
        val thirtyDaysAgo = today.minus(30, DateTimeUnit.DAY)

        val (totalOrgs, totalUsers, subsByPlan) = transaction {
            val orgs = Organizations.selectAll().count().toInt()
            val users = Users.selectAll().count().toInt()
            val subs = Subscriptions.select { Subscriptions.status eq "active" }
                .map { it[Subscriptions.plan].lowercase() }
                .groupingBy { it }
                .eachCount()
            Triple(orgs, users, subs)
        }

        val (allTimeEvents, last30Events, eventsTimeline) = queryClickHouseEvents(today.minus(365, DateTimeUnit.DAY), today)

        val mrr = transaction {
            Subscriptions.select { Subscriptions.status eq "active" }
                .mapNotNull { row ->
                    when (row[Subscriptions.plan].lowercase()) {
                        "pro" -> 19.0
                        "team" -> 49.0
                        else -> null
                    }
                }
                .sum()
        }

        return AdminOverviewStats(
            totalOrganizations = totalOrgs,
            totalUsers = totalUsers,
            totalEventsAllTime = allTimeEvents,
            totalEventsLast30Days = last30Events,
            mrr = mrr,
            subscriptionsByPlan = subsByPlan,
            eventsLast30Days = eventsTimeline
        )
    }

    fun getAllOrganizations(page: Int, limit: Int): List<AdminOrgSummary> {
        usageTracker.flushBuffer()
        val today = Clock.System.todayIn(TimeZone.UTC)
        val monthStart = kotlinx.datetime.LocalDate(today.year, today.month, 1)

        return transaction {
            val orgs = Organizations
                .selectAll()
                .limit(limit, offset = (page - 1) * limit.toLong())
                .toList()

            orgs.map { row ->
                val orgId = row[Organizations.id]
                val plan = Subscriptions.select { (Subscriptions.organization_id eq orgId) and (Subscriptions.status eq "active") }
                    .orderBy(Subscriptions.id to SortOrder.DESC)
                    .firstOrNull()
                    ?.get(Subscriptions.plan)
                    ?.lowercase() ?: "free"
                val projectCount = Projects.select { Projects.organization_id eq orgId }.count().toInt()
                val memberCount = Memberships.select { Memberships.organization_id eq orgId }.count().toInt()
                val usage = UsageRecords.select {
                    (UsageRecords.organization_id eq orgId) and
                        (UsageRecords.recordDate greaterEq monthStart) and
                        (UsageRecords.recordDate lessEq today) and
                        (UsageRecords.event_type eq "error")
                }.sumOf { it[UsageRecords.event_count].toLong() }
                val tier = PricingTier.entries.find { it.name.equals(plan, ignoreCase = true) } ?: PricingTier.FREE
                val quotaPct = if (tier.monthlyErrorLimit > 0) (usage.toDouble() / tier.monthlyErrorLimit * 100).coerceAtMost(100.0) else null

                AdminOrgSummary(
                    id = orgId,
                    name = row[Organizations.name],
                    slug = row[Organizations.slug],
                    plan = plan,
                    eventCountThisMonth = usage,
                    projectCount = projectCount,
                    memberCount = memberCount,
                    quotaUsedPercent = quotaPct
                )
            }
        }
    }

    fun getOrgDetail(orgId: Int): AdminOrgDetail? {
        usageTracker.flushBuffer()
        val today = Clock.System.todayIn(TimeZone.UTC)
        val monthStart = kotlinx.datetime.LocalDate(today.year, today.month, 1)

        return transaction {
            val org = Organizations.select { Organizations.id eq orgId }.firstOrNull() ?: return@transaction null
            val sub = Subscriptions.select { (Subscriptions.organization_id eq orgId) and (Subscriptions.status eq "active") }
                .orderBy(Subscriptions.id to SortOrder.DESC)
                .firstOrNull()
            val plan = sub?.get(Subscriptions.plan)?.lowercase() ?: "free"
            val subStatus = sub?.get(Subscriptions.status)
            val memberCount = Memberships.select { Memberships.organization_id eq orgId }.count().toInt()
            val projects = Projects.select { Projects.organization_id eq orgId }.toList()
            val usageRows = UsageRecords.select {
                (UsageRecords.organization_id eq orgId) and
                    (UsageRecords.recordDate greaterEq monthStart) and
                    (UsageRecords.recordDate lessEq today)
            }.toList()
            val errorCount = UsageRecords.select {
                (UsageRecords.organization_id eq orgId) and
                    (UsageRecords.recordDate greaterEq monthStart) and
                    (UsageRecords.recordDate lessEq today) and
                    (UsageRecords.event_type eq "error")
            }.sumOf { it[UsageRecords.event_count].toLong() }
            val eventCount = usageRows.sumOf { it[UsageRecords.event_count].toLong() }
            val bytesCount = usageRows.sumOf { it[UsageRecords.bytes_ingested] }
            val tier = PricingTier.entries.find { it.name.equals(plan, ignoreCase = true) } ?: PricingTier.FREE
            val quotaPct = if (tier.monthlyErrorLimit > 0) (errorCount.toDouble() / tier.monthlyErrorLimit * 100).coerceAtMost(100.0) else null

            val members = Memberships.select { Memberships.organization_id eq orgId }
                .mapNotNull { mRow ->
                    val u = Users.select { Users.id eq mRow[Memberships.user_id] }.firstOrNull() ?: return@mapNotNull null
                    AdminOrgMember(
                        userId = u[Users.id],
                        email = u[Users.email],
                        name = u[Users.name],
                        role = mRow[Memberships.role]
                    )
                }

            AdminOrgDetail(
                id = orgId,
                name = org[Organizations.name],
                slug = org[Organizations.slug],
                companySize = org[Organizations.company_size],
                plan = plan,
                subscriptionStatus = subStatus,
                memberCount = memberCount,
                projectCount = projects.size,
                eventCountThisMonth = eventCount,
                bytesIngestedThisMonth = bytesCount,
                quotaUsedPercent = quotaPct,
                members = members,
                projects = projects.map { p ->
                    AdminOrgProject(
                        id = p[Projects.id],
                        name = p[Projects.name],
                        slug = p[Projects.slug],
                        platform = p[Projects.platform]
                    )
                }
            )
        }
    }

    fun getUsageBreakdown(period: String): AdminUsageBreakdown {
        usageTracker.flushBuffer()
        val today = Clock.System.todayIn(TimeZone.UTC)
        val daysBack = when (period) {
            "24h" -> 0
            "7d" -> 6
            "30d" -> 29
            else -> 6
        }
        val startDate = today.minus(daysBack, DateTimeUnit.DAY)

        return transaction {
            val rows = UsageRecords.select {
                (UsageRecords.recordDate greaterEq startDate) and
                    (UsageRecords.recordDate lessEq today)
            }.toList()

            val byDate = rows.groupBy { it[UsageRecords.recordDate].toString() }
                .mapValues { (dateStr, recs) ->
                    val byType = recs.groupBy { it[UsageRecords.event_type] }
                    DailyUsageByType(
                        date = dateStr,
                        error = byType["error"]?.sumOf { r -> r[UsageRecords.event_count].toLong() } ?: 0L,
                        transaction = byType["transaction"]?.sumOf { r -> r[UsageRecords.event_count].toLong() } ?: 0L,
                        replay = byType["replay"]?.sumOf { r -> r[UsageRecords.event_count].toLong() } ?: 0L,
                        feedback = byType["feedback"]?.sumOf { r -> r[UsageRecords.event_count].toLong() } ?: 0L,
                        total = recs.sumOf { it[UsageRecords.event_count].toLong() }
                    )
                }
                .toSortedMap()

            val totalBytes = rows.sumOf { it[UsageRecords.bytes_ingested] }
            AdminUsageBreakdown(
                daily = byDate.values.toList(),
                totalBytes = totalBytes
            )
        }
    }

    fun getRevenueMetrics(): AdminRevenueMetrics {
        usageTracker.flushBuffer()
        val subsByPlan = transaction {
            Subscriptions.select { Subscriptions.status eq "active" }
                .map { it[Subscriptions.plan].lowercase() }
                .groupingBy { it }
                .eachCount()
        }
        val mrr = subsByPlan.entries.sumOf { (plan, count) ->
            when (plan) {
                "pro" -> count * 19.0
                "team" -> count * 49.0
                else -> 0.0
            }
        }
        val churn = transaction {
            Subscriptions.select { Subscriptions.status inList listOf("canceled", "past_due") }.count().toInt()
        }
        val costPerPlan = mapOf(
            "free" to 0.0,
            "pro" to 2.0,
            "team" to 6.0
        )
        return AdminRevenueMetrics(
            mrr = mrr,
            subscriptionsByPlan = subsByPlan,
            estimatedCostPerOrg = costPerPlan,
            churnLast30Days = churn
        )
    }

    suspend fun getInfrastructureHealth(): AdminInfrastructureHealth {
        val tables = mutableListOf<TableSize>()
        var totalBytes = 0L
        var totalRows = 0L

        try {
            val query = """
                SELECT table, sum(rows) as rows, sum(bytes_on_disk) as bytes
                FROM system.parts
                WHERE database = '$clickhouseDb' AND active
                GROUP BY table
            """.trimIndent()
            val response = httpClient.post("$clickhouseUrl") {
                parameter("database", clickhouseDb)
                parameter("user", clickhouseUser)
                parameter("password", clickhousePassword)
                setBody(query)
            }
            if (response.status.isSuccess()) {
                val text = response.bodyAsText()
                val lines = text.trim().split("\n").filter { it.isNotBlank() }
                for (line in lines) {
                    val parts = line.split("\t")
                    if (parts.size >= 3) {
                        val tableName = parts[0]
                        val rows = parts[1].toLongOrNull() ?: 0L
                        val bytes = parts[2].toLongOrNull() ?: 0L
                        tables.add(
                            TableSize(
                                table = tableName,
                                rows = rows,
                                bytesOnDisk = bytes,
                                bytesOnDiskFormatted = formatBytes(bytes)
                            )
                        )
                        totalBytes += bytes
                        totalRows += rows
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to query ClickHouse system.parts" }
        }

        val storageUsedPercent = if (usableStorageBytes > 0) (totalBytes.toDouble() / usableStorageBytes * 100) else 0.0
        val alerts = mutableListOf<String>()
        if (storageUsedPercent > 70) alerts.add("Storage > 70% (consider adding block storage)")
        if (storageUsedPercent > 80) alerts.add("Storage > 80% (scaling trigger)")

        return AdminInfrastructureHealth(
            clickhouseTables = tables.sortedByDescending { it.bytesOnDisk },
            totalDiskBytes = totalBytes,
            totalRows = totalRows,
            storageUsedPercent = storageUsedPercent,
            scalingTriggerAlerts = alerts
        )
    }

    fun getTopConsumers(limit: Int): List<AdminTopConsumer> {
        usageTracker.flushBuffer()
        val today = Clock.System.todayIn(TimeZone.UTC)
        val monthStart = kotlinx.datetime.LocalDate(today.year, today.month, 1)

        return transaction {
            val usageByOrg = UsageRecords.select {
                (UsageRecords.recordDate greaterEq monthStart) and
                    (UsageRecords.recordDate lessEq today)
            }.toList()
                .groupBy { it[UsageRecords.organization_id] }
                .mapValues { (_, recs) ->
                    recs.sumOf { it[UsageRecords.event_count].toLong() } to recs.sumOf { it[UsageRecords.bytes_ingested] }
                }

            usageByOrg.entries
                .sortedByDescending { it.value.first }
                .take(limit)
                .mapNotNull { (orgId, pair) ->
                    val (events, bytes) = pair
                    val org = Organizations.select { Organizations.id eq orgId }.firstOrNull() ?: return@mapNotNull null
                    val plan = Subscriptions.select { (Subscriptions.organization_id eq orgId) and (Subscriptions.status eq "active") }
                        .orderBy(Subscriptions.id to SortOrder.DESC)
                        .firstOrNull()
                        ?.get(Subscriptions.plan)
                        ?.lowercase() ?: "free"
                    AdminTopConsumer(
                        orgId = orgId,
                        orgName = org[Organizations.name],
                        orgSlug = org[Organizations.slug],
                        plan = plan,
                        eventCount = events,
                        bytesIngested = bytes
                    )
                }
        }
    }

    fun getOrgUsage(orgId: Int, period: String): List<OrgUsageSummary> {
        val today = Clock.System.todayIn(TimeZone.UTC)
        val daysBack = when (period) {
            "24h" -> 1
            "7d" -> 7
            "30d" -> 30
            else -> 7
        }
        val startDate = today.minus(daysBack, DateTimeUnit.DAY)
        return usageTracker.getUsageForOrg(orgId, startDate, today)
    }

    private suspend fun queryClickHouseEvents(startDate: kotlinx.datetime.LocalDate, endDate: kotlinx.datetime.LocalDate): Triple<Long, Long, List<AdminTimelinePoint>> {
        return try {
            val totalQuery = "SELECT count() as c FROM $clickhouseDb.events"
            val totalResp = httpClient.post("$clickhouseUrl") {
                parameter("database", clickhouseDb)
                parameter("user", clickhouseUser)
                parameter("password", clickhousePassword)
                setBody(totalQuery)
            }
            val allTime = totalResp.bodyAsText().trim().toLongOrNull() ?: 0L

            val last30Query = "SELECT count() as c FROM $clickhouseDb.events WHERE timestamp >= now() - INTERVAL 30 DAY"
            val last30Resp = httpClient.post("$clickhouseUrl") {
                parameter("database", clickhouseDb)
                parameter("user", clickhouseUser)
                parameter("password", clickhousePassword)
                setBody(last30Query)
            }
            val last30Count = last30Resp.bodyAsText().trim().toLongOrNull() ?: 0L

            val timelineQuery = """
                SELECT toDate(timestamp) as d, count() as cnt
                FROM $clickhouseDb.events
                WHERE timestamp >= now() - INTERVAL 30 DAY
                GROUP BY d
                ORDER BY d
            """.trimIndent()
            val timelineResp = httpClient.post("$clickhouseUrl") {
                parameter("database", clickhouseDb)
                parameter("user", clickhouseUser)
                parameter("password", clickhousePassword)
                setBody(timelineQuery)
            }
            val timeline = if (timelineResp.status.isSuccess()) {
                timelineResp.bodyAsText().trim().split("\n").filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        val parts = line.split("\t")
                        if (parts.size >= 2) AdminTimelinePoint(parts[0], parts[1].toLongOrNull() ?: 0L)
                        else null
                    }
            } else emptyList()

            Triple(allTime, last30Count, timeline)
        } catch (e: Exception) {
            logger.error(e) { "Failed to query ClickHouse for events" }
            Triple(0L, 0L, emptyList<AdminTimelinePoint>())
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }
}
