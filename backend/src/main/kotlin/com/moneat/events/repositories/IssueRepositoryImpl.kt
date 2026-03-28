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
import com.moneat.events.services.DashboardQueryHelper
import com.moneat.shared.models.IssueStatuses
import com.moneat.shared.models.Projects
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}

class IssueRepositoryImpl(
    private val queryHelper: DashboardQueryHelper
) : IssueRepository {

    private val clickhouseDb: String get() = queryHelper.clickhouseDb
    private val json get() = queryHelper.json

    override suspend fun getProjectIdForIssue(issueId: String): Long? {
        val escapedIssueId = escapeSql(issueId)
        val query = """
            SELECT toInt64(project_id) as project_id
            FROM `$clickhouseDb`.issues FINAL
            WHERE issue_id = '$escapedIssueId'
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()
        return queryHelper.executeProjectIdQuery(query, "Issue", issueId)
    }

    override fun getIssueStatusOverrides(projectId: Long): Map<String, String> =
        if (projectId > 0) {
            transaction {
                IssueStatuses
                    .selectAll()
                    .where { IssueStatuses.project_id eq projectId }
                    .associate { it[IssueStatuses.issue_id] to it[IssueStatuses.status] }
            }
        } else {
            emptyMap()
        }

    override suspend fun getIssuesRaw(
        projectId: Long,
        offset: Int,
        overfetch: Int,
        retentionDays: Int,
        retentionClause: String,
        projectIdClause: String
    ): List<IssueRow> {
        val query = """
            SELECT
                issue_id,
                toInt64(project_id) as project_id,
                any(message) as title,
                any(exception_type) as culprit,
                any(level) as level,
                any(platform) as platform,
                formatDateTime(min(timestamp), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as first_seen,
                formatDateTime(max(timestamp), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as last_seen,
                count() as event_count,
                uniq(user_id) as user_count,
                'unresolved' as status
            FROM `$clickhouseDb`.events e
            WHERE $projectIdClause
                AND event_type = 'error'
                AND issue_id != ''
                AND $retentionClause
            GROUP BY issue_id, project_id
            ORDER BY max(timestamp) DESC
            LIMIT $overfetch
            FORMAT JSONEachRow
        """.trimIndent()

        val rows = queryHelper.executeJsonEachRowQuery(query, "Issues") ?: return emptyList()
        return rows.mapNotNull { obj ->
            suspendRunCatching {
                IssueRow(
                    issueId = obj["issue_id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    projectId = obj["project_id"]?.jsonPrimitive?.long ?: projectId,
                    title = obj["title"]?.jsonPrimitive?.content ?: "",
                    culprit = obj["culprit"]?.jsonPrimitive?.content ?: "",
                    level = obj["level"]?.jsonPrimitive?.content ?: "error",
                    platform = obj["platform"]?.jsonPrimitive?.content ?: "",
                    firstSeen = obj["first_seen"]?.jsonPrimitive?.content ?: "",
                    lastSeen = obj["last_seen"]?.jsonPrimitive?.content ?: "",
                    eventCount = obj["event_count"]?.jsonPrimitive?.long ?: 0,
                    userCount = obj["user_count"]?.jsonPrimitive?.long ?: 0,
                    status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "unresolved",
                    fingerprint = null
                )
            }.getOrElse { e ->
                logger.error(e) { "Failed to parse issue row" }
                null
            }
        }
    }

    override suspend fun getIssueDetailRaw(
        issueId: String,
        projectId: Long,
        retentionDays: Int,
        retentionClause: String,
        projectIdClause: String
    ): IssueDetailRow? {
        val escapedIssueId = escapeSql(issueId)
        val query = """
            SELECT
                issue_id,
                toInt64(project_id) as project_id,
                any(message) as title,
                any(exception_type) as culprit,
                any(level) as level,
                any(platform) as platform,
                formatDateTime(min(timestamp), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as first_seen,
                formatDateTime(max(timestamp), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as last_seen,
                count() as event_count,
                uniq(user_id) as user_count,
                'unresolved' as status,
                any(fingerprint) as fingerprint
            FROM `$clickhouseDb`.events e
            WHERE issue_id = '$escapedIssueId' AND $projectIdClause AND $retentionClause
            GROUP BY issue_id, project_id
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()

        val obj = queryHelper.executeJsonEachRowQuery(query, "Issue detail")?.firstOrNull() ?: return null
        val fingerprintArr =
            obj["fingerprint"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull ?: "" } ?: emptyList()
        return IssueDetailRow(
            issueId = obj["issue_id"]?.jsonPrimitive?.content ?: issueId,
            projectId = obj["project_id"]?.jsonPrimitive?.long ?: projectId,
            title = obj["title"]?.jsonPrimitive?.content ?: "",
            culprit = obj["culprit"]?.jsonPrimitive?.content ?: "",
            level = obj["level"]?.jsonPrimitive?.content ?: "error",
            platform = obj["platform"]?.jsonPrimitive?.content ?: "",
            firstSeen = obj["first_seen"]?.jsonPrimitive?.content ?: "",
            lastSeen = obj["last_seen"]?.jsonPrimitive?.content ?: "",
            eventCount = obj["event_count"]?.jsonPrimitive?.long ?: 0,
            userCount = obj["user_count"]?.jsonPrimitive?.long ?: 0,
            status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "unresolved",
            fingerprint = fingerprintArr
        )
    }

    override fun getIssueStatus(issueId: String, projectId: Long): String? =
        transaction {
            IssueStatuses
                .selectAll()
                .where { (IssueStatuses.issue_id eq issueId) and (IssueStatuses.project_id eq projectId) }
                .firstOrNull()
                ?.get(IssueStatuses.status)
        }

    override fun getProjectName(projectId: Long): String? =
        transaction {
            Projects
                .selectAll()
                .where { Projects.id eq projectId }
                .firstOrNull()
                ?.get(Projects.name)
        } ?: "Unknown"

    override suspend fun getIssueEvents(
        issueId: String,
        projectId: Long,
        limit: Int,
        retentionClause: String,
        projectIdClause: String
    ): List<EventResponse> {
        val escapedIssueId = escapeSql(issueId)
        val query = """
            SELECT
                toString(event_id) as event_id,
                formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as event_ts,
                message,
                platform,
                level,
                environment,
                release,
                user_id,
                user_email,
                user_username,
                tags,
                contexts,
                exception_value as exception,
                breadcrumbs
            FROM `$clickhouseDb`.events
            WHERE $projectIdClause AND issue_id = '$escapedIssueId' AND event_type = 'error'
                AND $retentionClause
            ORDER BY timestamp DESC
            LIMIT $limit
            FORMAT JSONEachRow
        """.trimIndent()

        val rows = queryHelper.executeJsonEachRowQuery(query, "Issue events") ?: return emptyList()
        return rows.map { queryHelper.mapEventRow(it, "event_ts") }
    }

    override suspend fun getIssueTransactions(
        issueId: String,
        projectId: Long,
        limit: Int,
        retentionClause: String,
        projectIdClause: String
    ): List<IssueTransactionResponse> {
        val escapedIssueId = escapeSql(issueId)
        val query = """
            SELECT
                toString(event_id) as event_id,
                transaction_name as name,
                transaction_op as op,
                duration_ms as duration,
                formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as event_ts,
                JSONExtractString(contexts, 'trace', 'status') as status,
                JSONExtractString(contexts, 'trace', 'trace_id') as trace_id
            FROM `$clickhouseDb`.events
            WHERE $projectIdClause AND issue_id = '$escapedIssueId'
                AND event_type = 'transaction'
                AND $retentionClause
            ORDER BY timestamp DESC
            LIMIT $limit
            FORMAT JSONEachRow
        """.trimIndent()

        val rows = queryHelper.executeJsonEachRowQuery(query, "Issue transactions") ?: return emptyList()
        return rows.map { obj ->
            IssueTransactionResponse(
                eventId = obj["event_id"]?.jsonPrimitive?.content ?: "",
                name = obj["name"]?.jsonPrimitive?.content ?: "",
                op = obj["op"]?.jsonPrimitive?.content ?: "",
                duration = obj["duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                timestamp = obj["event_ts"]?.jsonPrimitive?.content ?: "",
                status = obj["status"]?.jsonPrimitive?.contentOrNull,
                traceId = obj["trace_id"]?.jsonPrimitive?.contentOrNull
            )
        }
    }

    override fun upsertIssueStatus(issueId: String, projectId: Long, status: String) {
        transaction {
            val existing = IssueStatuses
                .selectAll()
                .where { (IssueStatuses.issue_id eq issueId) and (IssueStatuses.project_id eq projectId) }
                .firstOrNull()

            if (existing != null) {
                IssueStatuses.update(
                    where = { (IssueStatuses.issue_id eq issueId) and (IssueStatuses.project_id eq projectId) }
                ) {
                    it[IssueStatuses.status] = status
                    it[IssueStatuses.updated_at] = Clock.System.now()
                }
            } else {
                IssueStatuses.insert {
                    it[IssueStatuses.issue_id] = issueId
                    it[IssueStatuses.project_id] = projectId
                    it[IssueStatuses.status] = status
                    it[IssueStatuses.updated_at] = Clock.System.now()
                }
            }
        }
    }
}
