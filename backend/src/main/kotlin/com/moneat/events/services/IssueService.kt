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

import com.moneat.events.models.EventResponse
import com.moneat.events.models.IssueDetailResponse
import com.moneat.events.models.IssueResponse
import com.moneat.events.models.IssueTransactionResponse
import com.moneat.events.models.IssueUpdateRequest
import com.moneat.events.repositories.IssueRepository
import com.moneat.utils.ClickHouseQueryUtils
import io.ktor.server.plugins.BadRequestException
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class IssueService(
    private val issueRepository: IssueRepository,
    private val queryHelper: DashboardQueryHelper
) {

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

        val overfetch = if (status != null) (limit + offset) * 5 else limit + offset
        val rows = issueRepository.getIssuesRaw(
            projectId = projectId,
            offset = offset,
            overfetch = overfetch,
            retentionDays = retentionDays,
            retentionClause = retentionClauseForEvents,
            projectIdClause = projectIdClause
        )

        return try {
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
                        projectId = row.projectId,
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
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch issues for project $projectId" }
            emptyList()
        }
    }

    suspend fun getIssue(
        issueId: String,
        demoEpochMs: Long? = null,
        explicitProjectId: Long? = null
    ): IssueDetailResponse? {
        val projectId = explicitProjectId
            ?: issueRepository.getProjectIdForIssue(issueId)
            ?: return null
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

        val latestEvent = getIssueEvents(issueId, 1, demoEpochMs).firstOrNull()

        return IssueDetailResponse(
            id = obj.issueId,
            projectId = obj.projectId,
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
        val projectId = explicitProjectId
            ?: issueRepository.getProjectIdForIssue(issueId) ?: return emptyList()
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

    suspend fun getIssueTransactions(
        issueId: String,
        limit: Int,
        demoEpochMs: Long? = null,
        explicitProjectId: Long? = null
    ): List<IssueTransactionResponse> {
        val projectId = explicitProjectId
            ?: issueRepository.getProjectIdForIssue(issueId) ?: return emptyList()
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
        update: IssueUpdateRequest
    ) {
        val projectId = issueRepository.getProjectIdForIssue(issueId)
            ?: throw IllegalArgumentException("Issue not found")

        if (update.status != null) {
            val validStatuses = setOf("unresolved", "resolved", "archived", "ignored")
            if (update.status !in validStatuses) throw BadRequestException("Invalid status value")
            issueRepository.upsertIssueStatus(issueId, projectId, update.status)
        }
    }
}
