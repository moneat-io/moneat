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

package com.moneat.events.services

import com.moneat.alerts.models.AlertSource
import com.moneat.alerts.services.AlertEpisodeService
import com.moneat.events.models.EventResponse
import com.moneat.events.models.IssueDetailResponse
import com.moneat.events.models.IssueResponse
import com.moneat.events.models.IssueTransactionResponse
import com.moneat.events.models.IssueUpdateRequest
import com.moneat.events.repositories.IssueRepository
import com.moneat.events.repositories.models.IssueStatusKey
import com.moneat.shared.models.Projects
import com.moneat.shared.services.ProjectIdResolver
import com.moneat.shared.services.ServiceIdentityResolver
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.suspendRunCatching
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

class IssueService(
    private val issueRepository: IssueRepository,
    private val queryHelper: DashboardQueryHelper,
    private val projectIdResolver: ProjectIdResolver = ProjectIdResolver(),
    private val alertEpisodeService: AlertEpisodeService = AlertEpisodeService(),
    private val serviceIdentityResolver: ServiceIdentityResolver = ServiceIdentityResolver(),
) {
    companion object {
        private const val ISSUE_OVERFETCH_MULTIPLIER = 5
        private const val ERROR_ALERT_DEDUP_PREFIX = "moneat-error-"
        private const val STATUS_IGNORED = "ignored"
        private const val STATUS_RESOLVED = "resolved"
        private const val STATUS_RESOLVED_IN_NEXT_RELEASE = "resolvedInNextRelease"
        private const val STATUS_ARCHIVED = "archived"
        private const val STATUS_UNRESOLVED = "unresolved"
    }

    suspend fun getProjectIdForIssue(issueId: String): Long? =
        issueRepository.getProjectIdForIssue(issueId)

    suspend fun getIssues(
        projectId: Long,
        page: Int,
        limit: Int,
        status: String?,
        demoEpochMs: Long? = null
    ): List<IssueResponse> {
        val offset = (page - 1) * limit
        val retentionDays = queryHelper.getProjectRetentionDays(projectId)
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)
        val retentionClauseForEvents =
            queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)

        val pgOverrides = issueRepository.getIssueStatusOverrides(projectId)
        val projectResourceId = projectResourceId(projectId)

        val overfetch = if (status != null) (limit + offset) * ISSUE_OVERFETCH_MULTIPLIER else limit + offset
        val rows = issueRepository.getIssuesRaw(
            projectId = projectId,
            offset = offset,
            overfetch = overfetch,
            retentionDays = retentionDays,
            retentionClause = retentionClauseForEvents,
            projectIdClause = projectIdClause
        )

        return suspendRunCatching {
            var skipped = 0
            val result = mutableListOf<IssueResponse>()
            for (row in rows) {
                val effectiveStatus = pgOverrides[row.issueId] ?: row.status

                if (status != null && effectiveStatus != status) continue

                if (skipped < offset) {
                    skipped++
                    continue
                }
                result.add(
                    IssueResponse(
                        id = row.issueId,
                        projectId = projectResourceId,
                        title = row.title,
                        culprit = row.culprit,
                        level = row.level,
                        platform = row.platform,
                        firstSeen = row.firstSeen,
                        lastSeen = row.lastSeen,
                        eventCount = row.eventCount,
                        userCount = row.userCount,
                        status = effectiveStatus,
                        substatus = null,
                        statusDetail = null
                    )
                )
                if (result.size >= limit) break
            }
            result
        }.getOrElse { e ->
            logger.error(e) { "Failed to fetch issues for project $projectId" }
            emptyList()
        }
    }

    suspend fun getIssues(query: IssueListQuery): List<IssueResponse> {
        val offset = (query.page - 1) * query.limit
        val retentionDays = queryHelper.getOrganizationRetentionDays(query.organizationId)
        val retentionClause =
            queryHelper.timestampRetentionClause("timestamp", retentionDays, query.demoEpochMs)
        val serviceIds = resolveServiceIds(query)
        if (query.hasServiceFilters() && serviceIds.isEmpty()) return emptyList()

        val pgOverrides = issueRepository.getIssueStatusOverridesForOrganization(
            organizationId = query.organizationId,
            serviceIds = serviceIds
        )

        val overfetch =
            if (query.status != null) {
                (query.limit + offset) * ISSUE_OVERFETCH_MULTIPLIER
            } else {
                query.limit + offset
            }
        val rows = issueRepository.getOrganizationIssuesRaw(
            organizationId = query.organizationId,
            serviceIds = serviceIds,
            overfetch = overfetch,
            retentionClause = retentionClause
        )

        return suspendRunCatching {
            paginateIssueRows(
                rows = rows,
                status = query.status,
                offset = offset,
                limit = query.limit,
                statusForRow = { row ->
                    pgOverrides[IssueStatusKey(row.projectId, row.issueId)] ?: row.status
                }
            )
        }.getOrElse { e ->
            logger.error(e) { "Failed to fetch issues for organization ${query.organizationId}" }
            emptyList()
        }
    }

    suspend fun getIssue(
        issueId: String,
        demoEpochMs: Long? = null,
        explicitProjectId: Long? = null
    ): IssueDetailResponse? {
        val projectId = explicitProjectId ?: issueRepository.getProjectIdForIssue(issueId) ?: return null
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)
        val retentionDays = queryHelper.getProjectRetentionDays(projectId)
        val retentionClause =
            queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)

        val obj = issueRepository.getIssueDetailRaw(
            issueId = issueId,
            projectId = projectId,
            retentionDays = retentionDays,
            retentionClause = retentionClause,
            projectIdClause = projectIdClause
        ) ?: return null

        val pgStatus = issueRepository.getIssueStatus(issueId, projectId)
        val projectName = issueRepository.getProjectName(projectId)
        val effectiveStatus = pgStatus ?: obj.status
        val projectResourceId = projectResourceId(projectId)

        val latestEvent = getIssueEvents(issueId, 1, demoEpochMs, projectId).firstOrNull()

        return IssueDetailResponse(
            id = obj.issueId,
            projectId = projectResourceId,
            projectName = projectName ?: "Unknown",
            title = obj.title,
            culprit = obj.culprit,
            level = obj.level,
            platform = obj.platform,
            firstSeen = obj.firstSeen,
            lastSeen = obj.lastSeen,
            eventCount = obj.eventCount,
            userCount = obj.userCount,
            status = effectiveStatus,
            substatus = null,
            statusDetail = null,
            fingerprint = obj.fingerprint ?: emptyList(),
            latestEvent = latestEvent
        )
    }

    suspend fun getIssueEvents(
        issueId: String,
        limit: Int,
        demoEpochMs: Long? = null,
        explicitProjectId: Long? = null
    ): List<EventResponse> {
        val projectId = explicitProjectId ?: issueRepository.getProjectIdForIssue(issueId) ?: return emptyList()
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)
        val retentionDays = queryHelper.getProjectRetentionDays(projectId)
        val retentionClause =
            queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)

        return issueRepository.getIssueEvents(
            issueId = issueId,
            projectId = projectId,
            limit = limit,
            retentionClause = retentionClause,
            projectIdClause = projectIdClause
        )
    }

    private fun projectResourceId(projectId: Long): String =
        projectIdResolver.resourceIdFor(projectId) ?: ""

    suspend fun getIssueTransactions(
        issueId: String,
        limit: Int,
        demoEpochMs: Long? = null,
        explicitProjectId: Long? = null
    ): List<IssueTransactionResponse> {
        val projectId = explicitProjectId ?: issueRepository.getProjectIdForIssue(issueId) ?: return emptyList()
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)
        val retentionDays = queryHelper.getProjectRetentionDays(projectId)
        val retentionClause =
            queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)

        return issueRepository.getIssueTransactions(
            issueId = issueId,
            projectId = projectId,
            limit = limit,
            retentionClause = retentionClause,
            projectIdClause = projectIdClause
        )
    }

    suspend fun updateIssue(
        issueId: String,
        update: IssueUpdateRequest,
        explicitProjectId: Long? = null
    ) {
        val projectId = resolveProjectIdForIssue(issueId, explicitProjectId)
            ?: throw NotFoundException("Issue not found")

        if (update.status != null) {
            val validStatuses = setOf(
                STATUS_UNRESOLVED,
                STATUS_RESOLVED,
                STATUS_RESOLVED_IN_NEXT_RELEASE,
                STATUS_ARCHIVED,
                STATUS_IGNORED
            )
            if (update.status !in validStatuses) throw BadRequestException("Invalid status value")
            issueRepository.upsertIssueStatus(issueId, projectId, update.status)
            updateErrorAlertEpisode(issueId, projectId, update.status)
        }
    }

    private fun resolveServiceIds(query: IssueListQuery): List<Long> =
        (query.serviceIds + resolveServiceNames(query.organizationId, query.serviceNames))
            .distinct()

    private fun resolveServiceNames(organizationId: Int, serviceNames: List<String>): List<Long> =
        serviceNames.mapNotNull { serviceName ->
            serviceIdentityResolver.resolveServiceId(organizationId, serviceName)
        }

    private fun IssueListQuery.hasServiceFilters(): Boolean =
        serviceIds.isNotEmpty() || serviceNames.isNotEmpty()

    private fun paginateIssueRows(
        rows: List<com.moneat.events.repositories.models.IssueRow>,
        status: String?,
        offset: Int,
        limit: Int,
        statusForRow: (com.moneat.events.repositories.models.IssueRow) -> String
    ): List<IssueResponse> {
        var skipped = 0
        val result = mutableListOf<IssueResponse>()
        for (row in rows) {
            val effectiveStatus = statusForRow(row)
            if (status != null && effectiveStatus != status) continue
            if (skipped < offset) {
                skipped++
                continue
            }
            result.add(row.toIssueResponse(effectiveStatus))
            if (result.size >= limit) break
        }
        return result
    }

    private fun com.moneat.events.repositories.models.IssueRow.toIssueResponse(status: String): IssueResponse =
        IssueResponse(
            id = issueId,
            projectId = projectResourceId(projectId),
            title = title,
            culprit = culprit,
            level = level,
            platform = platform,
            firstSeen = firstSeen,
            lastSeen = lastSeen,
            eventCount = eventCount,
            userCount = userCount,
            status = status,
            substatus = null,
            statusDetail = null
        )

    private suspend fun resolveProjectIdForIssue(issueId: String, explicitProjectId: Long?): Long? =
        if (explicitProjectId != null) {
            issueRepository.getProjectIdForIssue(issueId, explicitProjectId)
        } else {
            issueRepository.getProjectIdForIssue(issueId)
        }

    private fun updateErrorAlertEpisode(
        issueId: String,
        projectId: Long,
        status: String
    ) {
        val organizationId = projectOrganizationId(projectId) ?: return
        val deduplicationKey = "$ERROR_ALERT_DEDUP_PREFIX$issueId"
        when (status) {
            STATUS_IGNORED ->
                alertEpisodeService.suppressCurrentEpisode(
                    organizationId = organizationId,
                    source = AlertSource.ERROR_ALERT,
                    deduplicationKey = deduplicationKey,
                    userId = null,
                    reason = "Issue ignored"
                )
            STATUS_RESOLVED,
            STATUS_RESOLVED_IN_NEXT_RELEASE,
            STATUS_ARCHIVED ->
                alertEpisodeService.closeCurrentEpisode(
                    organizationId = organizationId,
                    source = AlertSource.ERROR_ALERT,
                    deduplicationKey = deduplicationKey
                )
            STATUS_UNRESOLVED -> {
                val episode = alertEpisodeService.openCurrentEpisode(
                    organizationId = organizationId,
                    source = AlertSource.ERROR_ALERT,
                    deduplicationKey = deduplicationKey
                )
                if (episode?.suppressedAt != null) {
                    alertEpisodeService.unsuppressCurrentEpisode(
                        organizationId = organizationId,
                        source = AlertSource.ERROR_ALERT,
                        deduplicationKey = deduplicationKey
                    )
                }
            }
        }
    }

    private fun projectOrganizationId(projectId: Long): Int? =
        runCatching {
            transaction {
                Projects
                    .selectAll()
                    .where { Projects.id eq projectId }
                    .firstOrNull()
                    ?.get(Projects.organization_id)
            }
        }.onFailure { error ->
            logger.warn(error) {
                "Skipping error alert lifecycle update because project $projectId could not be loaded"
            }
        }.getOrNull()
}
