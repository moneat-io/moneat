package com.moneat.services

import com.moneat.config.ClickHouseClient
import com.moneat.models.*
import com.moneat.models.SsoConfigurations
import com.moneat.ai.AiConversations
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.deleteWhere

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
    val bytesIngestedThisMonth: Long,
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
    val framework: String?
)

@Serializable
data class AdminOrgUsagePoint(
    val date: String,
    val eventType: String,
    val eventCount: Int,
    val bytesIngested: Long
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
    val log: Long,
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

@Serializable
data class AdminEmailStats(
    val totalSent: Long,
    val byType: Map<String, Long>,
    val last7Days: List<EmailTimelinePoint>,
    val last30Days: List<EmailTimelinePoint>,
    val estimatedCost: Double
)

@Serializable
data class EmailTimelinePoint(
    val date: String,
    val count: Long
)

@Serializable
data class AdminUserSummary(
    val id: Int,
    val email: String,
    val name: String?,
    val emailVerified: Boolean,
    val isAdmin: Boolean,
    val onboardingCompleted: Boolean,
    val oauthProvider: String?,
    val organizationCount: Int,
    val createdAt: String?
)

@Serializable
data class UpdateUserRequest(
    val isAdmin: Boolean? = null,
    val emailVerified: Boolean? = null
)

@Serializable
data class DeleteUsersRequest(
    val userIds: List<Int>
)

@Serializable
data class DeleteUsersResponse(
    val success: Boolean,
    val deletedCount: Int,
    val errors: List<String> = emptyList()
)

class AdminService {
    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val usageTracker = UsageTrackingService.instance

    private fun applyUserSearchFilter(query: Query, search: String?): Query {
        if (search.isNullOrBlank()) return query
        val searchPattern = "%${search.trim().lowercase()}%"
        return query.where {
            (Users.email.lowerCase() like searchPattern) or
                ((Users.name.isNotNull()) and (Users.name.lowerCase() like searchPattern))
        }
    }
    private val pricingTierService = PricingTierService()
    private val json = Json { ignoreUnknownKeys = true }

    private val usableStorageBytes = 35L * 1024 * 1024 * 1024 // 35GB from MONETIZATION.md

    suspend fun getOverviewStats(): AdminOverviewStats {
        usageTracker.flushBuffer()
        val today = Clock.System.todayIn(TimeZone.UTC)

        val (totalOrgs, totalUsers, subsByPlan) = transaction {
            val orgs = Organizations.selectAll().count().toInt()
            val users = Users.selectAll().count().toInt()
            val subs = Subscriptions.selectAll().where { Subscriptions.status eq "active" }
                .map { it[Subscriptions.plan].lowercase() }
                .groupingBy { it }
                .eachCount()
            Triple(orgs, users, subs)
        }

        val (allTimeEvents, last30Events, eventsTimeline) = queryClickHouseEvents(today.minus(365, DateTimeUnit.DAY), today)

        val mrr = transaction {
            Subscriptions.selectAll().where { Subscriptions.status eq "active" }
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
                val plan = Subscriptions.selectAll()
                    .where { (Subscriptions.organization_id eq orgId) and (Subscriptions.status eq "active") }
                    .orderBy(Subscriptions.id to SortOrder.DESC)
                    .firstOrNull()
                    ?.get(Subscriptions.plan)
                    ?.lowercase() ?: "free"
                val projectCount = Projects.selectAll().where { Projects.organization_id eq orgId }.count().toInt()
                val memberCount = Memberships.selectAll().where { Memberships.organization_id eq orgId }.count().toInt()
                val usageRows = UsageRecords.selectAll().where {
                    (UsageRecords.organization_id eq orgId) and
                        (UsageRecords.recordDate greaterEq monthStart) and
                        (UsageRecords.recordDate lessEq today)
                }.toList()
                val usage = usageRows.sumOf { it[UsageRecords.event_count].toLong() }
                val bytesCount = usageRows.sumOf { it[UsageRecords.bytes_ingested] }
                val tier = pricingTierService.getEffectiveTierForOrganization(orgId).tier
                val quotaPct = when {
                    tier.monthlyGbLimit > 0 -> (bytesCount.toDouble() / tier.monthlyGbLimit * 100).coerceAtMost(100.0)
                    tier.monthlyUnitLimit > 0 -> (usage.toDouble() / tier.monthlyUnitLimit * 100).coerceAtMost(100.0)
                    else -> null
                }

                AdminOrgSummary(
                    id = orgId,
                    name = row[Organizations.name],
                    slug = row[Organizations.slug],
                    plan = plan,
                    eventCountThisMonth = usage,
                    bytesIngestedThisMonth = bytesCount,
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
            val org = Organizations.selectAll().where { Organizations.id eq orgId }.firstOrNull() ?: return@transaction null
            val sub = Subscriptions.selectAll().where { (Subscriptions.organization_id eq orgId) and (Subscriptions.status eq "active") }
                .orderBy(Subscriptions.id to SortOrder.DESC)
                .firstOrNull()
            val plan = sub?.get(Subscriptions.plan)?.lowercase() ?: "free"
            val subStatus = sub?.get(Subscriptions.status)
            val memberCount = Memberships.selectAll().where { Memberships.organization_id eq orgId }.count().toInt()
            val projects = Projects.selectAll().where { Projects.organization_id eq orgId }.toList()
            val usageRows = UsageRecords.selectAll().where {
                (UsageRecords.organization_id eq orgId) and
                    (UsageRecords.recordDate greaterEq monthStart) and
                    (UsageRecords.recordDate lessEq today)
            }.toList()
            val eventCount = usageRows.sumOf { it[UsageRecords.event_count].toLong() }
            val bytesCount = usageRows.sumOf { it[UsageRecords.bytes_ingested] }
            val tier = pricingTierService.getEffectiveTierForOrganization(orgId).tier
            val quotaPct = when {
                tier.monthlyGbLimit > 0 -> (bytesCount.toDouble() / tier.monthlyGbLimit * 100).coerceAtMost(100.0)
                tier.monthlyUnitLimit > 0 -> (eventCount.toDouble() / tier.monthlyUnitLimit * 100).coerceAtMost(100.0)
                else -> null
            }

            val membersList = Memberships.selectAll().where { Memberships.organization_id eq orgId }
                .mapNotNull { mRow ->
                    val u = Users.selectAll().where { Users.id eq mRow[Memberships.user_id] }.firstOrNull() ?: return@mapNotNull null
                    AdminOrgMember(
                        userId = u[Users.id],
                        email = u[Users.email],
                        name = u[Users.name],
                        role = mRow[Memberships.role]
                    )
                }
                .let { listOf(*it.toTypedArray()) }

            val projectsList = projects.map { p ->
                AdminOrgProject(
                    id = p[Projects.id],
                    name = p[Projects.name],
                    slug = p[Projects.slug],
                    framework = p[Projects.framework]
                )
            }.let { listOf(*it.toTypedArray()) }

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
                members = membersList,
                projects = projectsList
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
            val rows = UsageRecords.selectAll().where {
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
                        log = (byType["log"]?.sumOf { r -> r[UsageRecords.event_count].toLong() } ?: 0L) +
                            (byType["logs"]?.sumOf { r -> r[UsageRecords.event_count].toLong() } ?: 0L),
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
            Subscriptions.selectAll().where { Subscriptions.status eq "active" }
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
            Subscriptions.selectAll().where { Subscriptions.status inList listOf("canceled", "past_due") }.count().toInt()
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
            val response = ClickHouseClient.execute(query)
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
            val usageByOrg = UsageRecords.selectAll().where {
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
                    val org = Organizations.selectAll().where { Organizations.id eq orgId }.firstOrNull() ?: return@mapNotNull null
                    val plan = Subscriptions.selectAll().where { (Subscriptions.organization_id eq orgId) and (Subscriptions.status eq "active") }
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
    
    fun getEmailStats(period: String = "30d"): AdminEmailStats {
        val today = Clock.System.todayIn(TimeZone.UTC)
        val daysBack = when (period) {
            "7d" -> 7
            "30d" -> 30
            else -> 30
        }
        val startDate = today.minus(daysBack, DateTimeUnit.DAY)
        
        return transaction {
            // Total sent all time (only successful)
            val totalSent = EmailsSent.selectAll()
                .where { EmailsSent.success eq true }
                .count()
            
            // By type
            val byType = EmailsSent.selectAll()
                .where { EmailsSent.success eq true }
                .toList()
                .groupBy { it[EmailsSent.email_type] }
                .mapValues { it.value.size.toLong() }
            
            // Timeline for last 7 days
            val last7DaysStart = today.minus(7, DateTimeUnit.DAY).atStartOfDayIn(TimeZone.UTC)
            val last7Days = EmailsSent.selectAll()
                .where { 
                    (EmailsSent.success eq true) and 
                    (EmailsSent.sent_at greaterEq last7DaysStart)
                }
                .toList()
                .groupBy { row -> 
                    row[EmailsSent.sent_at].toLocalDateTime(TimeZone.UTC).date.toString()
                }
                .map { (date, emails) -> 
                    EmailTimelinePoint(date = date, count = emails.size.toLong())
                }
                .sortedBy { it.date }
            
            // Timeline for last 30 days
            val last30DaysStart = startDate.atStartOfDayIn(TimeZone.UTC)
            val last30Days = EmailsSent.selectAll()
                .where { 
                    (EmailsSent.success eq true) and 
                    (EmailsSent.sent_at greaterEq last30DaysStart)
                }
                .toList()
                .groupBy { row -> 
                    row[EmailsSent.sent_at].toLocalDateTime(TimeZone.UTC).date.toString()
                }
                .map { (date, emails) -> 
                    EmailTimelinePoint(date = date, count = emails.size.toLong())
                }
                .sortedBy { it.date }
            
            // Estimate cost - AWS SES costs $0.10 per 1,000 emails
            val estimatedCost = totalSent * 0.0001
            
            AdminEmailStats(
                totalSent = totalSent,
                byType = byType,
                last7Days = last7Days,
                last30Days = last30Days,
                estimatedCost = estimatedCost
            )
        }
    }

    private suspend fun queryClickHouseEvents(
        @Suppress("UNUSED_PARAMETER") startDate: kotlinx.datetime.LocalDate,
        @Suppress("UNUSED_PARAMETER") endDate: kotlinx.datetime.LocalDate
    ): Triple<Long, Long, List<AdminTimelinePoint>> {
        return try {
            val totalQuery = "SELECT count() as c FROM $clickhouseDb.events"
            val totalResp = ClickHouseClient.execute(totalQuery)
            val allTime = totalResp.bodyAsText().trim().toLongOrNull() ?: 0L

            val last30Query = "SELECT count() as c FROM $clickhouseDb.events WHERE timestamp >= now() - INTERVAL 30 DAY"
            val last30Resp = ClickHouseClient.execute(last30Query)
            val last30Count = last30Resp.bodyAsText().trim().toLongOrNull() ?: 0L

            val timelineQuery = """
                SELECT toDate(timestamp) as d, count() as cnt
                FROM $clickhouseDb.events
                WHERE timestamp >= now() - INTERVAL 30 DAY
                GROUP BY d
                ORDER BY d
            """.trimIndent()
            val timelineResp = ClickHouseClient.execute(timelineQuery)
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

    fun getAllUsers(page: Int, limit: Int, search: String? = null): List<AdminUserSummary> {
        return transaction {
            val userRows = applyUserSearchFilter(Users.selectAll(), search)
                .orderBy(Users.id to SortOrder.DESC)
                .limit(limit, offset = (page - 1) * limit.toLong())
                .toList()

            val userIds = userRows.map { row -> row[Users.id] }
            val organizationCountsByUser = if (userIds.isEmpty()) {
                emptyMap()
            } else {
                Memberships
                    .select(Memberships.user_id)
                    .where { Memberships.user_id inList userIds }
                    .map { row -> row[Memberships.user_id] }
                    .groupingBy { userId -> userId }
                    .eachCount()
            }

            userRows.map { row ->
                val userId = row[Users.id]
                AdminUserSummary(
                    id = userId,
                    email = row[Users.email],
                    name = row[Users.name],
                    emailVerified = row[Users.email_verified],
                    isAdmin = row[Users.is_admin],
                    onboardingCompleted = row[Users.onboarding_completed],
                    oauthProvider = row[Users.oauth_provider],
                    organizationCount = organizationCountsByUser[userId] ?: 0,
                    createdAt = null // Will add created_at field later if needed
                )
            }
        }
    }

    fun getTotalUserCount(search: String? = null): Int {
        return transaction {
            applyUserSearchFilter(Users.selectAll(), search).count().toInt()
        }
    }

    fun updateUser(userId: Int, updates: UpdateUserRequest): Boolean {
        return transaction {
            Users.selectAll().where { Users.id eq userId }.firstOrNull()
                ?: return@transaction false
            
            Users.update({ Users.id eq userId }) {
                updates.isAdmin?.let { isAdmin -> it[Users.is_admin] = isAdmin }
                updates.emailVerified?.let { verified -> it[Users.email_verified] = verified }
            }
            
            true
        }
    }

    fun deleteUsers(userIds: List<Int>): DeleteUsersResponse {
        if (userIds.isEmpty()) {
            return DeleteUsersResponse(success = false, deletedCount = 0, errors = listOf("No user IDs provided"))
        }

        val errors = mutableListOf<String>()
        var deletedCount = 0

        transaction {
            userIds.forEach { userId ->
                try {
                    val user = Users.selectAll().where { Users.id eq userId }.firstOrNull()
                    if (user == null) {
                        errors.add("User $userId not found")
                        return@forEach
                    }

                    // Find all organizations owned by this user (where they are the only member)
                    val userOrgs = Memberships.selectAll().where { Memberships.user_id eq userId }
                        .map { it[Memberships.organization_id] }
                        .toSet()

                    userOrgs.forEach { orgId ->
                        val memberCount = Memberships.selectAll()
                            .where { Memberships.organization_id eq orgId }
                            .count()
                        
                        // If user is the only member, delete the entire organization and related data
                        if (memberCount.toInt() == 1) {
                            deleteOrganizationData(orgId)
                        }
                    }

                    // Delete user's memberships
                    Memberships.deleteWhere { Memberships.user_id eq userId }

                    // Delete user's auth tokens
                    AuthTokens.deleteWhere { AuthTokens.user_id eq userId }

                    // Delete user's legal acceptances
                    UserLegalAcceptances.deleteWhere { UserLegalAcceptances.user_id eq userId }

                    // Delete user's notification preferences
                    NotificationPreferences.deleteWhere { NotificationPreferences.user_id eq userId }

                    // Delete alert notification preferences
                    AlertNotificationPreferences.deleteWhere { AlertNotificationPreferences.user_id eq userId }

                    // Delete the user
                    Users.deleteWhere { Users.id eq userId }

                    deletedCount++
                } catch (e: Exception) {
                    logger.error(e) { "Failed to delete user $userId" }
                    errors.add("Failed to delete user $userId: ${e.message}")
                }
            }
        }

        return DeleteUsersResponse(
            success = errors.isEmpty(),
            deletedCount = deletedCount,
            errors = errors
        )
    }

    private fun deleteOrganizationData(orgId: Int) {
        try {
            // Get all projects for this organization
            val projectIds = Projects.selectAll().where { Projects.organization_id eq orgId }
                .map { it[Projects.id] }

            // Delete project keys
            projectIds.forEach { projectId ->
                ProjectKeys.deleteWhere { ProjectKeys.project_id eq projectId }
            }

            // Get all release IDs for these projects
            val releaseIds = Releases.selectAll().where { Releases.project_id inList projectIds }
                .map { it[Releases.id] }

            // Delete release files
            releaseIds.forEach { releaseId ->
                ReleaseFiles.deleteWhere { ReleaseFiles.release_id eq releaseId }
            }

            // Delete releases
            projectIds.forEach { projectId ->
                Releases.deleteWhere { Releases.project_id eq projectId }
            }

            // Delete projects
            Projects.deleteWhere { Projects.organization_id eq orgId }

            // Delete organization integrations
            OrganizationIntegrations.deleteWhere { OrganizationIntegrations.organization_id eq orgId }

            // Delete organization invitations
            OrgInvitations.deleteWhere { OrgInvitations.organization_id eq orgId }

            // Delete subscriptions
            Subscriptions.deleteWhere { Subscriptions.organization_id eq orgId }

            // Delete usage records
            UsageRecords.deleteWhere { UsageRecords.organization_id eq orgId }

            // Delete promotional credit grants
            PromotionalCreditGrants.deleteWhere { PromotionalCreditGrants.organization_id eq orgId }

            // Delete email tracking records
            EmailsSent.deleteWhere { EmailsSent.organization_id eq orgId }

            // Delete alert silence periods
            AlertSilencePeriods.deleteWhere { AlertSilencePeriods.organization_id eq orgId }

            // Delete SSO configurations (cascades to user_sso_links)
            SsoConfigurations.deleteWhere { SsoConfigurations.organizationId eq orgId }

            // Delete AI conversations (cascades to ai_messages)
            AiConversations.deleteWhere { AiConversations.organization_id eq orgId }

            // Delete alert templates
            OrganizationAlertTemplates.deleteWhere { OrganizationAlertTemplates.organization_id eq orgId }

            // Delete systems
            val systemIds = Systems.selectAll().where { Systems.organization_id eq orgId }
                .map { it[Systems.id] }
            
            // Delete system alerts (through system relationship)
            systemIds.forEach { systemId ->
                SystemAlerts.deleteWhere { SystemAlerts.system_id eq systemId }
                SystemAlertSettings.deleteWhere { SystemAlertSettings.system_id eq systemId }
            }
            
            // Now delete systems
            Systems.deleteWhere { Systems.organization_id eq orgId }

            // Delete the organization itself
            Organizations.deleteWhere { Organizations.id eq orgId }

            logger.info { "Deleted organization $orgId and all related data" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete organization data for org $orgId" }
            throw e
        }
    }
}
