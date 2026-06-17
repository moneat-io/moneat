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

package com.moneat.events.repositories

import com.moneat.events.models.EventResponse
import com.moneat.events.models.IssueTransactionResponse
import com.moneat.events.repositories.models.IssueDetailRow
import com.moneat.events.repositories.models.IssueRow
import com.moneat.events.repositories.models.IssueStatusKey

/**
 * Repository for issue data access.
 * Abstracts PostgreSQL (IssueStatuses, Projects) and ClickHouse queries.
 */
interface IssueRepository {
    suspend fun getProjectIdForIssue(issueId: String): Long?
    suspend fun getProjectIdForIssue(issueId: String, projectId: Long): Long?
    fun getIssueStatusOverrides(projectId: Long): Map<String, String>
    fun getIssueStatusOverridesForOrganization(
        organizationId: Int,
        serviceIds: List<Long>
    ): Map<IssueStatusKey, String>
    suspend fun getIssuesRaw(
        projectId: Long,
        offset: Int,
        overfetch: Int,
        retentionDays: Int,
        retentionClause: String,
        projectIdClause: String
    ): List<IssueRow>
    suspend fun getOrganizationIssuesRaw(
        organizationId: Int,
        serviceIds: List<Long>,
        overfetch: Int,
        retentionClause: String
    ): List<IssueRow>
    suspend fun getIssueDetailRaw(
        issueId: String,
        projectId: Long,
        retentionDays: Int,
        retentionClause: String,
        projectIdClause: String
    ): IssueDetailRow?
    fun getIssueStatus(issueId: String, projectId: Long): String?
    fun getProjectName(projectId: Long): String?
    suspend fun getIssueEvents(
        issueId: String,
        projectId: Long,
        limit: Int,
        retentionClause: String,
        projectIdClause: String
    ): List<EventResponse>
    suspend fun getIssueTransactions(
        issueId: String,
        projectId: Long,
        limit: Int,
        retentionClause: String,
        projectIdClause: String
    ): List<IssueTransactionResponse>
    fun upsertIssueStatus(issueId: String, projectId: Long, status: String)
}
