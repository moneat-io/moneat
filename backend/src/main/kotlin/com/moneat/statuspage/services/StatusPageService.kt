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

package com.moneat.statuspage.services

import com.moneat.billing.services.BillingQuotaService
import com.moneat.config.ClickHouseClient
import com.moneat.statuspage.models.AddCustomDomainRequest
import com.moneat.statuspage.models.AddMonitorsRequest
import com.moneat.statuspage.models.CreateIncidentRequest
import com.moneat.statuspage.models.CreateIncidentUpdateRequest
import com.moneat.statuspage.models.CreateStatusPageRequest
import com.moneat.statuspage.models.CustomDomainResponse
import com.moneat.statuspage.models.IncidentResponse
import com.moneat.statuspage.models.IncidentUpdateResponse
import com.moneat.statuspage.models.PublicMonitorStatus
import com.moneat.statuspage.models.PublicStatusPageResponse
import com.moneat.statuspage.models.StatusPageCustomDomains
import com.moneat.statuspage.models.StatusPageDetailResponse
import com.moneat.statuspage.models.StatusPageIncidentUpdates
import com.moneat.statuspage.models.StatusPageIncidents
import com.moneat.statuspage.models.StatusPageMonitorResponse
import com.moneat.statuspage.models.StatusPageMonitors
import com.moneat.statuspage.models.StatusPageResponse
import com.moneat.statuspage.models.StatusPages
import com.moneat.statuspage.models.UpdateIncidentRequest
import com.moneat.statuspage.models.UpdateStatusPageRequest
import com.moneat.statuspage.models.UptimeDataPoint
import com.moneat.uptime.models.UptimeMonitors
import com.moneat.uptime.repositories.UptimeMonitorRepositoryImpl
import com.moneat.uptime.services.UptimeService
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import java.util.UUID
import javax.naming.NamingException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}

private const val HOURS_PER_DAY = 24
private const val PERCENT_MULTIPLIER = 100.0
private const val TOKEN_BYTES_SIZE = 32

class StatusPageService(
    private val uptimeService: UptimeService = UptimeService(BillingQuotaService(), UptimeMonitorRepositoryImpl())
) {

    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()

    // ==================== Status Page CRUD ====================

    fun listStatusPages(organizationId: Int): List<StatusPageResponse> {
        return transaction {
            StatusPages
                .selectAll()
                .where { StatusPages.organizationId eq organizationId }
                .map { it.toStatusPageResponse() }
        }
    }

    fun getStatusPage(
        pageId: UUID,
        organizationId: Int
    ): StatusPageDetailResponse? {
        return transaction {
            val page =
                StatusPages
                    .selectAll()
                    .where {
                        (StatusPages.id eq pageId) and (StatusPages.organizationId eq organizationId)
                    }.firstOrNull() ?: return@transaction null

            val monitors =
                StatusPageMonitors
                    .innerJoin(UptimeMonitors, { monitorId }, { UptimeMonitors.id })
                    .selectAll()
                    .where { StatusPageMonitors.statusPageId eq pageId }
                    .orderBy(StatusPageMonitors.sortOrder to SortOrder.ASC)
                    .map {
                        StatusPageMonitorResponse(
                            id = it[StatusPageMonitors.resourceId].toString(),
                            monitorId = it[StatusPageMonitors.monitorId].toString(),
                            monitorName = it[UptimeMonitors.name],
                            displayName = it[StatusPageMonitors.displayName],
                            sortOrder = it[StatusPageMonitors.sortOrder],
                            url = it[UptimeMonitors.url]
                        )
                    }

            val customDomains =
                StatusPageCustomDomains
                    .selectAll()
                    .where { StatusPageCustomDomains.statusPageId eq pageId }
                    .map { it.toCustomDomainResponse() }

            StatusPageDetailResponse(
                id = page[StatusPages.id].toString(),
                organizationId = page[StatusPages.organizationId].toString(),
                name = page[StatusPages.name],
                slug = page[StatusPages.slug],
                description = page[StatusPages.description],
                logoUrl = page[StatusPages.logoUrl],
                faviconUrl = page[StatusPages.faviconUrl],
                primaryColor = page[StatusPages.primaryColor],
                darkMode = page[StatusPages.darkMode],
                showUptimeHistory = page[StatusPages.showUptimeHistory],
                historyDays = page[StatusPages.historyDays],
                isPublic = page[StatusPages.isPublic],
                monitors = monitors,
                customDomains = customDomains,
                createdAt = page[StatusPages.createdAt].toString(),
                updatedAt = page[StatusPages.updatedAt].toString()
            )
        }
    }

    fun createStatusPage(
        organizationId: Int,
        request: CreateStatusPageRequest
    ): StatusPageResponse {
        val pageId = UUID.randomUUID()
        val now = Clock.System.now()

        // Validate slug format
        if (!request.slug.matches(Regex("^[a-z0-9-]+$"))) {
            throw IllegalArgumentException("Slug must contain only lowercase letters, numbers, and hyphens")
        }

        return transaction {
            // Check for unique slug
            val existing = StatusPages.selectAll().where { StatusPages.slug eq request.slug }.firstOrNull()
            if (existing != null) {
                throw IllegalArgumentException("Slug '${request.slug}' is already taken")
            }

            StatusPages.insert {
                it[id] = pageId
                it[StatusPages.organizationId] = organizationId
                it[name] = request.name
                it[slug] = request.slug
                it[description] = request.description
                it[logoUrl] = request.logoUrl
                it[faviconUrl] = request.faviconUrl
                it[primaryColor] = request.primaryColor
                it[darkMode] = request.darkMode
                it[showUptimeHistory] = request.showUptimeHistory
                it[historyDays] = request.historyDays
                it[isPublic] = request.isPublic
                it[createdAt] = now
                it[updatedAt] = now
            }

            StatusPages
                .selectAll()
                .where { StatusPages.id eq pageId }
                .first()
                .toStatusPageResponse()
        }
    }

    fun updateStatusPage(
        pageId: UUID,
        organizationId: Int,
        request: UpdateStatusPageRequest
    ): StatusPageResponse? {
        return transaction {
            val existing =
                StatusPages
                    .selectAll()
                    .where {
                        (StatusPages.id eq pageId) and (StatusPages.organizationId eq organizationId)
                    }.firstOrNull() ?: return@transaction null

            // Validate slug if provided
            if (request.slug != null && !request.slug.matches(Regex("^[a-z0-9-]+$"))) {
                throw IllegalArgumentException("Slug must contain only lowercase letters, numbers, and hyphens")
            }

            // Check slug uniqueness if changing
            if (request.slug != null && request.slug != existing[StatusPages.slug]) {
                val slugTaken = StatusPages.selectAll().where { StatusPages.slug eq request.slug }.firstOrNull()
                if (slugTaken != null) {
                    throw IllegalArgumentException("Slug '${request.slug}' is already taken")
                }
            }

            StatusPages.update({ StatusPages.id eq pageId }) {
                request.name?.let { name -> it[StatusPages.name] = name }
                request.slug?.let { slug -> it[StatusPages.slug] = slug }
                request.description?.let { desc -> it[StatusPages.description] = desc }
                request.logoUrl?.let { url -> it[StatusPages.logoUrl] = url }
                request.faviconUrl?.let { url -> it[StatusPages.faviconUrl] = url }
                request.primaryColor?.let { color -> it[StatusPages.primaryColor] = color }
                request.darkMode?.let { dark -> it[StatusPages.darkMode] = dark }
                request.showUptimeHistory?.let { show -> it[StatusPages.showUptimeHistory] = show }
                request.historyDays?.let { days -> it[StatusPages.historyDays] = days }
                request.isPublic?.let { pub -> it[StatusPages.isPublic] = pub }
                it[StatusPages.updatedAt] = Clock.System.now()
            }

            StatusPages
                .selectAll()
                .where { StatusPages.id eq pageId }
                .first()
                .toStatusPageResponse()
        }
    }

    fun deleteStatusPage(
        pageId: UUID,
        organizationId: Int
    ): Boolean {
        return transaction {
            val deleted =
                StatusPages.deleteWhere {
                    (id eq pageId) and (StatusPages.organizationId eq organizationId)
                }
            deleted > 0
        }
    }

    // ==================== Monitor Management ====================

    fun addMonitors(
        pageId: UUID,
        organizationId: Int,
        request: AddMonitorsRequest
    ): List<StatusPageMonitorResponse> {
        return transaction {
            // Verify page belongs to org
            StatusPages
                .selectAll()
                .where {
                    (StatusPages.id eq pageId) and (StatusPages.organizationId eq organizationId)
                }.firstOrNull() ?: throw IllegalArgumentException("Status page not found")

            // Remove existing monitors and add new ones
            StatusPageMonitors.deleteWhere { statusPageId eq pageId }

            request.monitors.forEach { assignment ->
                val monitorUuid = UUID.fromString(assignment.monitorId)

                // Verify monitor belongs to org
                UptimeMonitors
                    .selectAll()
                    .where {
                        (UptimeMonitors.id eq monitorUuid) and (UptimeMonitors.organizationId eq organizationId)
                    }.firstOrNull() ?: throw IllegalArgumentException("Monitor ${assignment.monitorId} not found")

                StatusPageMonitors.insert {
                    it[statusPageId] = pageId
                    it[monitorId] = monitorUuid
                    it[displayName] = assignment.displayName
                    it[sortOrder] = assignment.sortOrder
                }
            }

            // Return updated list
            StatusPageMonitors
                .innerJoin(UptimeMonitors, { monitorId }, { UptimeMonitors.id })
                .selectAll()
                .where { StatusPageMonitors.statusPageId eq pageId }
                .orderBy(StatusPageMonitors.sortOrder to SortOrder.ASC)
                .map {
                    StatusPageMonitorResponse(
                        id = it[StatusPageMonitors.resourceId].toString(),
                        monitorId = it[StatusPageMonitors.monitorId].toString(),
                        monitorName = it[UptimeMonitors.name],
                        displayName = it[StatusPageMonitors.displayName],
                        sortOrder = it[StatusPageMonitors.sortOrder],
                        url = it[UptimeMonitors.url]
                    )
                }
        }
    }

    fun removeMonitor(
        pageId: UUID,
        organizationId: Int,
        monitorId: UUID
    ): Boolean {
        return transaction {
            // Verify page belongs to org
            StatusPages
                .selectAll()
                .where {
                    (StatusPages.id eq pageId) and (StatusPages.organizationId eq organizationId)
                }.firstOrNull() ?: return@transaction false

            val deleted =
                StatusPageMonitors.deleteWhere {
                    (statusPageId eq pageId) and (StatusPageMonitors.monitorId eq monitorId)
                }
            deleted > 0
        }
    }

    // ==================== Incident Management ====================

    fun listIncidents(
        pageId: UUID,
        organizationId: Int
    ): List<IncidentResponse> {
        return transaction {
            // Verify page belongs to org
            StatusPages
                .selectAll()
                .where {
                    (StatusPages.id eq pageId) and (StatusPages.organizationId eq organizationId)
                }.firstOrNull() ?: throw IllegalArgumentException("Status page not found")

            StatusPageIncidents
                .selectAll()
                .where { StatusPageIncidents.statusPageId eq pageId }
                .orderBy(StatusPageIncidents.createdAt to SortOrder.DESC)
                .map { it.toIncidentResponse() }
        }
    }

    fun createIncident(
        pageId: UUID,
        organizationId: Int,
        request: CreateIncidentRequest
    ): IncidentResponse {
        return createIncidentWithId(pageId, organizationId, UUID.randomUUID(), request)
    }

    fun createIncidentWithId(
        pageId: UUID,
        organizationId: Int,
        incidentId: UUID,
        request: CreateIncidentRequest,
    ): IncidentResponse {
        val now = Clock.System.now()

        return transaction {
            // Verify page belongs to org
            StatusPages
                .selectAll()
                .where {
                    (StatusPages.id eq pageId) and (StatusPages.organizationId eq organizationId)
                }.firstOrNull() ?: throw IllegalArgumentException("Status page not found")

            // Create incident
            StatusPageIncidents.insert {
                it[id] = incidentId
                it[statusPageId] = pageId
                it[title] = request.title
                it[status] = request.status
                it[type] = request.type
                it[impact] = request.impact
                it[scheduledStartAt] = request.scheduledStartAt?.let { ts -> kotlin.time.Instant.parse(ts) }
                it[scheduledEndAt] = request.scheduledEndAt?.let { ts -> kotlin.time.Instant.parse(ts) }
                it[createdAt] = now
                it[updatedAt] = now
            }

            // Create initial update
            StatusPageIncidentUpdates.insert {
                it[id] = UUID.randomUUID()
                it[StatusPageIncidentUpdates.incidentId] = incidentId
                it[StatusPageIncidentUpdates.status] = request.status
                it[message] = request.message
                it[createdAt] = now
            }

            StatusPageIncidents
                .selectAll()
                .where { StatusPageIncidents.id eq incidentId }
                .first()
                .toIncidentResponse()
        }
    }

    fun getIncident(
        pageId: UUID,
        organizationId: Int,
        incidentId: UUID,
    ): IncidentResponse? {
        return transaction {
            StatusPageIncidents
                .innerJoin(StatusPages, { statusPageId }, { StatusPages.id })
                .selectAll()
                .where {
                    (StatusPageIncidents.id eq incidentId) and
                        (StatusPageIncidents.statusPageId eq pageId) and
                        (StatusPages.organizationId eq organizationId)
                }
                .firstOrNull()
                ?.toIncidentResponse()
        }
    }

    fun updateIncident(
        pageId: UUID,
        organizationId: Int,
        incidentId: UUID,
        request: UpdateIncidentRequest
    ): IncidentResponse? {
        return transaction {
            // Verify page belongs to org
            StatusPages
                .selectAll()
                .where {
                    (StatusPages.id eq pageId) and (StatusPages.organizationId eq organizationId)
                }.firstOrNull() ?: return@transaction null

            StatusPageIncidents
                .selectAll()
                .where {
                    (StatusPageIncidents.id eq incidentId) and (StatusPageIncidents.statusPageId eq pageId)
                }.firstOrNull() ?: return@transaction null

            StatusPageIncidents.update({ StatusPageIncidents.id eq incidentId }) {
                request.title?.let { title -> it[StatusPageIncidents.title] = title }
                request.status?.let { status ->
                    it[StatusPageIncidents.status] = status
                    if (status == "resolved" || status == "completed") {
                        it[resolvedAt] = Clock.System.now()
                    }
                }
                request.impact?.let { impact -> it[StatusPageIncidents.impact] = impact }
                request.scheduledStartAt?.let { ts -> it[scheduledStartAt] = kotlin.time.Instant.parse(ts) }
                request.scheduledEndAt?.let { ts -> it[scheduledEndAt] = kotlin.time.Instant.parse(ts) }
                it[updatedAt] = Clock.System.now()
            }

            StatusPageIncidents
                .selectAll()
                .where { StatusPageIncidents.id eq incidentId }
                .first()
                .toIncidentResponse()
        }
    }

    fun createIncidentUpdate(
        pageId: UUID,
        organizationId: Int,
        incidentId: UUID,
        request: CreateIncidentUpdateRequest
    ): IncidentResponse? {
        return transaction {
            // Verify page belongs to org
            StatusPages
                .selectAll()
                .where {
                    (StatusPages.id eq pageId) and (StatusPages.organizationId eq organizationId)
                }.firstOrNull() ?: return@transaction null

            StatusPageIncidents
                .selectAll()
                .where {
                    (StatusPageIncidents.id eq incidentId) and (StatusPageIncidents.statusPageId eq pageId)
                }.firstOrNull() ?: return@transaction null

            val now = Clock.System.now()

            // Create update
            StatusPageIncidentUpdates.insert {
                it[id] = UUID.randomUUID()
                it[StatusPageIncidentUpdates.incidentId] = incidentId
                it[status] = request.status
                it[message] = request.message
                it[createdAt] = now
            }

            // Update incident status and timestamp
            StatusPageIncidents.update({ StatusPageIncidents.id eq incidentId }) {
                it[status] = request.status
                it[updatedAt] = now
                if (request.status == "resolved" || request.status == "completed") {
                    it[resolvedAt] = now
                }
            }

            StatusPageIncidents
                .selectAll()
                .where { StatusPageIncidents.id eq incidentId }
                .first()
                .toIncidentResponse()
        }
    }

    // ==================== Custom Domain Management ====================

    fun addCustomDomain(
        pageId: UUID,
        organizationId: Int,
        request: AddCustomDomainRequest
    ): CustomDomainResponse {
        return transaction {
            // Verify page belongs to org
            StatusPages
                .selectAll()
                .where {
                    (StatusPages.id eq pageId) and (StatusPages.organizationId eq organizationId)
                }.firstOrNull() ?: throw IllegalArgumentException("Status page not found")

            // Validate domain format
            if (!request.domain.matches(Regex("^[a-z0-9][a-z0-9.-]+[a-z0-9]$"))) {
                throw IllegalArgumentException("Invalid domain format")
            }

            // Check uniqueness
            val existing =
                StatusPageCustomDomains
                    .selectAll()
                    .where {
                        StatusPageCustomDomains.domain eq request.domain
                    }.firstOrNull()
            if (existing != null) {
                throw IllegalArgumentException("Domain '${request.domain}' is already in use")
            }

            val verificationToken = generateVerificationToken()
            val now = Clock.System.now()

            val domain =
                StatusPageCustomDomains.insert {
                    it[statusPageId] = pageId
                    it[domain] = request.domain
                    it[StatusPageCustomDomains.verificationToken] = verificationToken
                    it[createdAt] = now
                }

            StatusPageCustomDomains
                .selectAll()
                .where {
                    (StatusPageCustomDomains.resourceId eq domain[StatusPageCustomDomains.resourceId]) and
                        (StatusPageCustomDomains.statusPageId eq pageId)
                }
                .first()
                .toCustomDomainResponse()
        }
    }

    fun verifyCustomDomain(
        pageId: UUID,
        organizationId: Int,
        domainResourceId: Uuid
    ): CustomDomainResponse? {
        return transaction {
            // Verify page belongs to org
            StatusPages
                .selectAll()
                .where {
                    (StatusPages.id eq pageId) and (StatusPages.organizationId eq organizationId)
                }.firstOrNull() ?: return@transaction null

            val domain =
                StatusPageCustomDomains
                    .selectAll()
                    .where {
                        (StatusPageCustomDomains.resourceId eq domainResourceId) and
                            (StatusPageCustomDomains.statusPageId eq pageId)
                    }.firstOrNull() ?: return@transaction null

            val domainName = domain[StatusPageCustomDomains.domain]
            val expectedToken = domain[StatusPageCustomDomains.verificationToken]

            // Perform DNS TXT lookup
            val verified = verifyDnsTxtRecord(domainName, expectedToken)

            if (verified) {
                StatusPageCustomDomains.update(
                    {
                        (StatusPageCustomDomains.resourceId eq domainResourceId) and
                            (StatusPageCustomDomains.statusPageId eq pageId)
                    }
                ) {
                    it[StatusPageCustomDomains.verified] = true
                    it[verifiedAt] = Clock.System.now()
                }
            }

            StatusPageCustomDomains
                .selectAll()
                .where {
                    (StatusPageCustomDomains.resourceId eq domainResourceId) and
                        (StatusPageCustomDomains.statusPageId eq pageId)
                }
                .first()
                .toCustomDomainResponse()
        }
    }

    fun removeCustomDomain(
        pageId: UUID,
        organizationId: Int,
        domainResourceId: Uuid
    ): Boolean {
        return transaction {
            // Verify page belongs to org
            StatusPages
                .selectAll()
                .where {
                    (StatusPages.id eq pageId) and (StatusPages.organizationId eq organizationId)
                }.firstOrNull() ?: return@transaction false

            val deleted =
                StatusPageCustomDomains.deleteWhere {
                    (resourceId eq domainResourceId) and (statusPageId eq pageId)
                }
            deleted > 0
        }
    }

    // ==================== Public Data ====================

    suspend fun getPublicStatusPage(slug: String): PublicStatusPageResponse? {
        val page =
            transaction {
                StatusPages
                    .selectAll()
                    .where {
                        (StatusPages.slug eq slug) and (StatusPages.isPublic eq true)
                    }.firstOrNull()
            } ?: return null

        return getPublicStatusPageData(page)
    }

    suspend fun getPublicStatusPageByDomain(domain: String): PublicStatusPageResponse? {
        val pageRow =
            transaction {
                val customDomain =
                    StatusPageCustomDomains
                        .selectAll()
                        .where {
                            (StatusPageCustomDomains.domain eq domain) and (StatusPageCustomDomains.verified eq true)
                        }.firstOrNull() ?: return@transaction null

                val pageId = customDomain[StatusPageCustomDomains.statusPageId]

                StatusPages
                    .selectAll()
                    .where {
                        (StatusPages.id eq pageId) and (StatusPages.isPublic eq true)
                    }.firstOrNull()
            } ?: return null

        return getPublicStatusPageData(pageRow)
    }

    private suspend fun getPublicStatusPageData(page: ResultRow): PublicStatusPageResponse {
        val pageId = page[StatusPages.id]
        val historyDays = page[StatusPages.historyDays]
        val showHistory = page[StatusPages.showUptimeHistory]

        // Get monitor data
        data class MonitorData(
            val monitorId: UUID,
            val monitorName: String,
            val displayName: String?
        )

        val monitorData =
            transaction {
                StatusPageMonitors
                    .innerJoin(UptimeMonitors, { monitorId }, { UptimeMonitors.id })
                    .selectAll()
                    .where { StatusPageMonitors.statusPageId eq pageId }
                    .orderBy(StatusPageMonitors.sortOrder to SortOrder.ASC)
                    .map { monitorRow ->
                        MonitorData(
                            monitorId = monitorRow[StatusPageMonitors.monitorId],
                            monitorName = monitorRow[UptimeMonitors.name],
                            displayName = monitorRow[StatusPageMonitors.displayName]
                        )
                    }
            }

        // Process monitors with suspend functions
        val monitors =
            monitorData.map { data ->
                // Get current status
                val currentStatus = getCurrentMonitorStatus(data.monitorId)

                // Calculate uptime percentage
                val uptimePercentage = uptimeService.getUptimePercentage(data.monitorId, historyDays * HOURS_PER_DAY)

                // Get uptime history if enabled
                val uptimeHistory =
                    if (showHistory) {
                        getUptimeHistory(data.monitorId, historyDays)
                    } else {
                        null
                    }

                PublicMonitorStatus(
                    name = data.monitorName,
                    displayName = data.displayName,
                    status = currentStatus,
                    uptimePercentage = uptimePercentage.toDouble(),
                    uptimeHistory = uptimeHistory
                )
            }

        // Get active incidents (not resolved)
        val activeIncidents =
            transaction {
                StatusPageIncidents
                    .selectAll()
                    .where {
                        (StatusPageIncidents.statusPageId eq pageId) and
                            (StatusPageIncidents.type eq "incident") and
                            (StatusPageIncidents.status neq "resolved")
                    }.orderBy(StatusPageIncidents.createdAt to SortOrder.DESC)
                    .map { it.toIncidentResponse() }
            }

        // Get scheduled maintenance
        val scheduledMaintenance =
            transaction {
                StatusPageIncidents
                    .selectAll()
                    .where {
                        (StatusPageIncidents.statusPageId eq pageId) and
                            (StatusPageIncidents.type eq "maintenance") and
                            (StatusPageIncidents.status neq "completed")
                    }.orderBy(StatusPageIncidents.scheduledStartAt to SortOrder.ASC)
                    .map { it.toIncidentResponse() }
            }

        return PublicStatusPageResponse(
            name = page[StatusPages.name],
            description = page[StatusPages.description],
            logoUrl = page[StatusPages.logoUrl],
            faviconUrl = page[StatusPages.faviconUrl],
            primaryColor = page[StatusPages.primaryColor],
            darkMode = page[StatusPages.darkMode],
            showUptimeHistory = showHistory,
            historyDays = historyDays,
            monitors = monitors,
            activeIncidents = activeIncidents,
            scheduledMaintenance = scheduledMaintenance
        )
    }

    private suspend fun getCurrentMonitorStatus(monitorId: UUID): String {
        // Get most recent heartbeat
        val now = Clock.System.now()
        val from = now.minus(1.hours)

        val query =
            """
            SELECT status
            FROM `$clickhouseDb`.uptime_heartbeats
            WHERE monitor_id = '$monitorId'
              AND timestamp >= fromUnixTimestamp64Milli(${from.toEpochMilliseconds()})
            ORDER BY timestamp DESC
            LIMIT 1
            FORMAT JSONEachRow
            """.trimIndent()

        return suspendRunCatching {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()

            if (body.isBlank()) return "unknown"

            val json = Json.parseToJsonElement(body.trim().lines().first()).jsonObject
            val status = json["status"]?.jsonPrimitive?.content?.toIntOrNull() ?: return "unknown"

            when (status) {
                1 -> "operational"
                0 -> "down"
                else -> "unknown"
            }
        }.getOrElse { e ->
            logger.error(e) { "Failed to get monitor status for $monitorId" }
            "unknown"
        }
    }

    private suspend fun getUptimeHistory(
        monitorId: UUID,
        days: Int
    ): List<UptimeDataPoint> {
        val now = Clock.System.now()
        val from = now.minus((days * HOURS_PER_DAY).hours)

        // Get daily uptime percentages
        val query =
            """
            SELECT 
                toDate(timestamp) as date,
                countIf(status = 1) as up_count,
                count() as total_count
            FROM `$clickhouseDb`.uptime_heartbeats
            WHERE monitor_id = '$monitorId'
              AND timestamp >= fromUnixTimestamp64Milli(${from.toEpochMilliseconds()})
            GROUP BY date
            ORDER BY date ASC
            FORMAT JSONEachRow
            """.trimIndent()

        return suspendRunCatching {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()

            if (body.isBlank()) return emptyList()

            body.trim().lines().mapNotNull { line ->
                try {
                    val json = Json.parseToJsonElement(line).jsonObject
                    val date = json["date"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val upCount = json["up_count"]?.jsonPrimitive?.long ?: 0L
                    val totalCount = json["total_count"]?.jsonPrimitive?.long ?: 0L

                    val uptime =
                        if (totalCount == 0L) 0.0 else (upCount.toDouble() / totalCount.toDouble() * PERCENT_MULTIPLIER)

                    UptimeDataPoint(date = date, uptime = uptime)
                } catch (e: SerializationException) {
                    logger.error(e) { "Failed to parse uptime history line: $line" }
                    null
                }
            }
        }.getOrElse { e ->
            logger.error(e) { "Failed to get uptime history for monitor $monitorId" }
            emptyList()
        }
    }

    // ==================== Helper Methods ====================

    private fun generateVerificationToken(): String {
        val random = SecureRandom()
        val bytes = ByteArray(TOKEN_BYTES_SIZE)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun verifyDnsTxtRecord(
        domain: String,
        expectedToken: String
    ): Boolean {
        return try {
            val txtRecordName = "_moneat-verify.$domain"
            val expectedValue = "moneat-verify=$expectedToken"

            // Use Java's DNS lookup
            val attrs =
                javax.naming.directory.InitialDirContext().getAttributes(
                    "dns:/$txtRecordName",
                    arrayOf("TXT")
                )
            val txtRecords = attrs.get("TXT")

            if (txtRecords == null) {
                logger.warn { "No TXT records found for $txtRecordName" }
                return false
            }

            val records = (0 until txtRecords.size()).map { txtRecords.get(it).toString().trim('"') }
            val found = records.any { it == expectedValue }

            if (!found) {
                logger.warn { "TXT record found but doesn't match. Expected: $expectedValue, Found: $records" }
            }

            found
        } catch (e: NamingException) {
            logger.error(e) { "Failed to verify DNS TXT record for $domain" }
            false
        }
    }

    // ==================== Extension Functions ====================

    private fun ResultRow.toStatusPageResponse() =
        StatusPageResponse(
            id = this[StatusPages.id].toString(),
            organizationId = this[StatusPages.organizationId].toString(),
            name = this[StatusPages.name],
            slug = this[StatusPages.slug],
            description = this[StatusPages.description],
            logoUrl = this[StatusPages.logoUrl],
            faviconUrl = this[StatusPages.faviconUrl],
            primaryColor = this[StatusPages.primaryColor],
            darkMode = this[StatusPages.darkMode],
            showUptimeHistory = this[StatusPages.showUptimeHistory],
            historyDays = this[StatusPages.historyDays],
            isPublic = this[StatusPages.isPublic],
            createdAt = this[StatusPages.createdAt].toString(),
            updatedAt = this[StatusPages.updatedAt].toString()
        )

    private fun ResultRow.toIncidentResponse(): IncidentResponse {
        val incidentId = this[StatusPageIncidents.id]

        val updates =
            transaction {
                StatusPageIncidentUpdates
                    .selectAll()
                    .where { StatusPageIncidentUpdates.incidentId eq incidentId }
                    .orderBy(StatusPageIncidentUpdates.createdAt to SortOrder.DESC)
                    .map {
                        IncidentUpdateResponse(
                            id = it[StatusPageIncidentUpdates.id].toString(),
                            status = it[StatusPageIncidentUpdates.status],
                            message = it[StatusPageIncidentUpdates.message],
                            createdAt = it[StatusPageIncidentUpdates.createdAt].toString()
                        )
                    }
            }

        return IncidentResponse(
            id = incidentId.toString(),
            statusPageId = this[StatusPageIncidents.statusPageId].toString(),
            title = this[StatusPageIncidents.title],
            status = this[StatusPageIncidents.status],
            type = this[StatusPageIncidents.type],
            impact = this[StatusPageIncidents.impact],
            scheduledStartAt = this[StatusPageIncidents.scheduledStartAt]?.toString(),
            scheduledEndAt = this[StatusPageIncidents.scheduledEndAt]?.toString(),
            resolvedAt = this[StatusPageIncidents.resolvedAt]?.toString(),
            createdAt = this[StatusPageIncidents.createdAt].toString(),
            updatedAt = this[StatusPageIncidents.updatedAt].toString(),
            updates = updates
        )
    }

    private fun ResultRow.toCustomDomainResponse() =
        CustomDomainResponse(
            id = this[StatusPageCustomDomains.resourceId].toString(),
            domain = this[StatusPageCustomDomains.domain],
            verificationToken = this[StatusPageCustomDomains.verificationToken],
            verified = this[StatusPageCustomDomains.verified],
            verifiedAt = this[StatusPageCustomDomains.verifiedAt]?.toString(),
            sslProvisioned = this[StatusPageCustomDomains.sslProvisioned],
            createdAt = this[StatusPageCustomDomains.createdAt].toString()
        )
}
