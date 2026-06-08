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
import com.moneat.events.models.EventTraceResponse
import com.moneat.events.models.FeedbackDetailResponse
import com.moneat.events.models.FeedbackListItem
import com.moneat.events.models.IssueDetailResponse
import com.moneat.events.models.IssueResponse
import com.moneat.events.models.IssueTransactionResponse
import com.moneat.events.models.PerformanceStatsResponse
import com.moneat.events.models.ProjectKeyResponse
import com.moneat.events.models.ProjectResponse
import com.moneat.events.models.ProjectStatsResponse
import com.moneat.events.models.ReleaseDetailStats
import com.moneat.events.models.ReleaseListResponse
import com.moneat.events.models.ReplayDetailResponse
import com.moneat.events.models.ReplayListItem
import com.moneat.events.models.ReplayRecordingResponse
import com.moneat.events.models.ReplayTimelineResponse
import com.moneat.events.models.SpanDetailResponse
import com.moneat.events.models.TraceDetailResponse
import com.moneat.events.models.TransactionDetailResponse
import com.moneat.events.models.TransactionSummaryResponse
import com.moneat.events.models.TransactionWithSpansResponse
import com.moneat.events.repositories.IssueRepository
import com.moneat.events.repositories.IssueRepositoryImpl
import com.moneat.events.repositories.ProjectRepository
import com.moneat.events.repositories.ProjectRepositoryImpl
import io.sentry.ISpan

class DashboardService(
    private val projectRepository: ProjectRepository,
    private val issueRepository: IssueRepository,
    private val queryHelper: DashboardQueryHelper = DashboardQueryHelper(),
) {
    companion object {
        fun create(): DashboardService {
            val queryHelper = DashboardQueryHelper()
            return DashboardService(
                projectRepository = ProjectRepositoryImpl { col, days, demo ->
                    queryHelper.timestampRetentionClause(col, days, demo)
                },
                issueRepository = IssueRepositoryImpl(queryHelper),
                queryHelper = queryHelper,
            )
        }
    }
    private val feedbackService = FeedbackService(queryHelper)
    private val releaseStatsService = ReleaseStatsService(queryHelper)
    private val replayService = ReplayService(queryHelper)
    private val projectService = ProjectService(projectRepository, queryHelper)
    private val projectStatsService = ProjectStatsService(queryHelper)
    private val transactionService = TransactionService(queryHelper)
    private val issueService = IssueService(issueRepository, queryHelper)
    private val accessService = AccessService(
        queryHelper = queryHelper,
        issueService = issueService,
        transactionService = transactionService,
        feedbackService = feedbackService,
        replayService = replayService
    )

    fun hasProjectAccess(userId: Int, projectId: Long): Boolean =
        accessService.hasProjectAccess(userId, projectId)

    suspend fun hasIssueAccess(userId: Int, issueId: String): Boolean =
        accessService.hasIssueAccess(userId, issueId)

    suspend fun hasTransactionAccess(userId: Int, eventId: String): Boolean =
        accessService.hasTransactionAccess(userId, eventId)

    suspend fun hasTraceAccess(userId: Int, projectId: Long): Boolean =
        accessService.hasTraceAccess(userId, projectId)

    suspend fun hasSpanAccess(userId: Int, projectId: Long): Boolean =
        accessService.hasSpanAccess(userId, projectId)

    suspend fun hasReplayAccess(userId: Int, replayId: String): Boolean =
        accessService.hasReplayAccess(userId, replayId)

    suspend fun hasFeedbackAccess(userId: Int, feedbackId: String): Boolean =
        accessService.hasFeedbackAccess(userId, feedbackId)

    suspend fun getProjectIdForEvent(eventId: String): Long? =
        accessService.getProjectIdForEvent(eventId)

    suspend fun getIssueIdForEvent(eventId: String): String? =
        accessService.getIssueIdForEvent(eventId)

    suspend fun getProjects(
        orgId: Int,
        demoEpochMs: Long? = null
    ): List<ProjectResponse> = projectService.getProjects(orgId, demoEpochMs)

    fun getServiceIdsForOrganization(orgId: Int): List<Long> =
        projectRepository.getProjectsForOrganizations(listOf(orgId)).map { it.projectId }

    fun resolveServiceId(orgId: Int, serviceName: String): Long? =
        projectRepository.resolveServiceId(orgId, serviceName)

    suspend fun getProject(projectId: Long): ProjectResponse? =
        projectService.getProject(projectId)

    suspend fun createProject(
        orgId: Int,
        request: com.moneat.events.models.CreateProjectRequest
    ): ProjectResponse = projectService.createProject(orgId, request)

    fun addProjectTarget(
        projectId: Long,
        target: String
    ): ProjectKeyResponse = projectService.addProjectTarget(projectId, target)

    fun updateProject(
        projectId: Long,
        request: com.moneat.events.models.UpdateProjectRequest
    ) = projectService.updateProject(projectId, request)

    fun deleteProject(projectId: Long) = projectService.deleteProject(projectId)

    suspend fun getIssues(
        projectId: Long,
        page: Int,
        limit: Int,
        status: String?,
        demoEpochMs: Long? = null
    ): List<IssueResponse> =
        issueService.getIssues(projectId, page, limit, status, demoEpochMs)

    suspend fun getIssues(query: IssueListQuery): List<IssueResponse> =
        issueService.getIssues(query)

    suspend fun getIssue(
        issueId: String,
        demoEpochMs: Long? = null,
        projectId: Long? = null
    ): IssueDetailResponse? =
        issueService.getIssue(issueId, demoEpochMs, projectId)

    suspend fun getIssueEvents(
        issueId: String,
        limit: Int,
        demoEpochMs: Long? = null,
        projectId: Long? = null
    ): List<EventResponse> =
        issueService.getIssueEvents(issueId, limit, demoEpochMs, projectId)

    suspend fun getIssueTransactions(
        issueId: String,
        limit: Int,
        demoEpochMs: Long? = null,
        projectId: Long? = null
    ): List<IssueTransactionResponse> =
        issueService.getIssueTransactions(issueId, limit, demoEpochMs, projectId)

    suspend fun getTransactions(
        projectId: Long,
        period: String = "7d",
        environment: String? = null,
        operation: String? = null,
        demoEpochMs: Long? = null
    ): List<TransactionSummaryResponse> =
        transactionService.getTransactions(projectId, period, environment, operation, demoEpochMs)

    suspend fun getPerformanceStats(
        projectId: Long,
        period: String = "7d",
        environment: String? = null,
        operation: String? = null,
        demoEpochMs: Long? = null
    ): PerformanceStatsResponse =
        transactionService.getPerformanceStats(projectId, period, environment, operation, demoEpochMs)

    suspend fun getTransaction(eventId: String): TransactionDetailResponse? =
        transactionService.getTransaction(eventId)

    suspend fun getTransactionSpans(eventId: String): TransactionWithSpansResponse? =
        transactionService.getTransactionSpans(eventId)

    suspend fun getTraceForEvent(eventId: String): EventTraceResponse? =
        transactionService.getTraceForEvent(eventId)

    suspend fun getTraceForTraceId(projectId: Long, traceId: String): EventTraceResponse? =
        transactionService.getTraceForTraceId(projectId, traceId)

    suspend fun getTraceDetails(
        projectId: Long,
        traceId: String
    ): TraceDetailResponse? =
        transactionService.getTraceDetails(projectId, traceId)

    suspend fun getSpanDetails(
        projectId: Long,
        spanId: String
    ): SpanDetailResponse? =
        transactionService.getSpanDetails(projectId, spanId)

    suspend fun getRelatedErrorsForTransaction(
        eventId: String,
        limit: Int = 20
    ): List<EventResponse> =
        transactionService.getRelatedErrorsForTransaction(eventId, limit)

    suspend fun getProjectStats(
        projectId: Long,
        period: String = "7d",
        parentSpan: ISpan? = null,
        demoEpochMs: Long? = null
    ): ProjectStatsResponse = projectStatsService.getProjectStats(projectId, period, parentSpan, demoEpochMs)

    suspend fun getReleases(
        projectId: Long,
        parentSpan: ISpan? = null
    ): List<ReleaseListResponse> = releaseStatsService.getReleases(projectId, parentSpan)

    suspend fun getReleasesForServices(
        organizationId: Int,
        serviceIds: List<Long>,
        parentSpan: ISpan? = null
    ): List<ReleaseListResponse> =
        releaseStatsService.getReleasesForServices(organizationId, serviceIds, parentSpan)

    suspend fun getReleaseStats(
        projectId: Long,
        version: String
    ): ReleaseDetailStats? = releaseStatsService.getReleaseStats(projectId, version)

    suspend fun getReleaseStatsForServices(
        organizationId: Int,
        serviceIds: List<Long>,
        version: String
    ): ReleaseDetailStats? =
        releaseStatsService.getReleaseStatsForServices(organizationId, serviceIds, version)

    suspend fun getReplays(
        projectId: Long,
        page: Int = 1,
        limit: Int = 25,
        environment: String? = null,
        period: String = "7d",
        demoEpochMs: Long? = null
    ): List<ReplayListItem> = replayService.getReplays(projectId, page, limit, environment, period, demoEpochMs)

    suspend fun getReplaysForServices(
        organizationId: Int,
        serviceIds: List<Long>,
        page: Int = 1,
        limit: Int = 25,
        environment: String? = null,
        period: String = "7d",
        demoEpochMs: Long? = null
    ): List<ReplayListItem> =
        replayService.getReplaysForServices(organizationId, serviceIds, page, limit, environment, period, demoEpochMs)

    suspend fun getReplay(
        replayId: String,
        demoEpochMs: Long? = null
    ): ReplayDetailResponse? = replayService.getReplay(replayId, demoEpochMs)

    suspend fun getReplayTimeline(
        replayId: String,
        demoEpochMs: Long? = null
    ): ReplayTimelineResponse = replayService.getReplayTimeline(replayId, demoEpochMs)

    suspend fun getReplayRecording(replayId: String): ReplayRecordingResponse? =
        replayService.getReplayRecording(replayId)

    suspend fun getReplaysForIssue(
        issueId: String,
        limit: Int = 10
    ): List<ReplayListItem> = replayService.getReplaysForIssue(issueId, limit)

    suspend fun getFeedback(
        projectId: Long,
        page: Int = 1,
        limit: Int = 25,
        status: String? = null,
        demoEpochMs: Long? = null
    ): List<FeedbackListItem> = feedbackService.getFeedback(projectId, page, limit, status, demoEpochMs)

    suspend fun getFeedbackForServices(
        organizationId: Int,
        serviceIds: List<Long>,
        page: Int = 1,
        limit: Int = 25,
        status: String? = null,
        demoEpochMs: Long? = null
    ): List<FeedbackListItem> =
        feedbackService.getFeedbackForServices(organizationId, serviceIds, page, limit, status, demoEpochMs)

    suspend fun getFeedbackDetail(feedbackId: String): FeedbackDetailResponse? =
        feedbackService.getFeedbackDetail(feedbackId)

    suspend fun updateFeedback(
        feedbackId: String,
        update: com.moneat.events.models.FeedbackUpdateRequest
    ) = feedbackService.updateFeedback(feedbackId, update)

    suspend fun updateIssue(
        issueId: String,
        update: com.moneat.events.models.IssueUpdateRequest,
        projectId: Long? = null
    ) = issueService.updateIssue(issueId, update, projectId)
}
