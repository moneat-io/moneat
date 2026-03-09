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

import com.moneat.config.ClickHouseClient
import com.moneat.events.models.EventResponse
import com.moneat.events.models.IssueDetailResponse
import com.moneat.events.models.IssueTransactionResponse
import com.moneat.events.models.IssueResponse
import com.moneat.shared.models.IssueStatuses
import com.moneat.shared.models.Projects
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.long
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

class IssueService(private val queryHelper: DashboardQueryHelper) {
    private val clickhouseDb: String get() = queryHelper.clickhouseDb
    private val json get() = queryHelper.json

    suspend fun getProjectIdForIssue(issueId: String): Long? {
        val escapedIssueId = escapeSql(issueId)
        val query = """
            SELECT toInt64(project_id) as project_id
            FROM `$clickhouseDb`.issues FINAL
            WHERE issue_id = '$escapedIssueId'
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) return null
            if (body.isBlank()) return null
            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            obj["project_id"]?.jsonPrimitive?.long
        } catch (e: Exception) {
            logger.error(e) { "Failed to get project ID for issue $issueId" }
            null
        }
    }

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
        val retentionClause = queryHelper.timestampRetentionClause("last_seen", retentionDays, demoEpochMs)

        val validStatuses = setOf("unresolved", "resolved", "archived", "ignored")
        val statusFilter = if (status != null && status in validStatuses) {
            "AND status = '${escapeSql(status)}'"
        } else {
            ""
        }

        val retentionClauseForEvents = queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)

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
                AND $retentionClauseForEvents
            GROUP BY issue_id, project_id
            ORDER BY max(timestamp) DESC
            LIMIT $limit OFFSET $offset
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299) return emptyList()

            val pgOverrides = if (projectId > 0) {
                transaction {
                    IssueStatuses
                        .selectAll()
                        .where { IssueStatuses.project_id eq projectId }
                        .associate { it[IssueStatuses.issue_id] to it[IssueStatuses.status] }
                }
            } else {
                emptyMap()
            }

            body.lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    var chStatus = obj["status"]?.jsonPrimitive?.contentOrNull ?: "unresolved"
                    val issueId = obj["issue_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val effectiveStatus = pgOverrides[issueId] ?: chStatus

                    if (status != null && effectiveStatus != status) return@mapNotNull null

                    IssueResponse(
                        id = issueId,
                        projectId = obj["project_id"]?.jsonPrimitive?.long ?: projectId,
                        title = obj["title"]?.jsonPrimitive?.content ?: "",
                        culprit = obj["culprit"]?.jsonPrimitive?.content ?: "",
                        level = obj["level"]?.jsonPrimitive?.content ?: "error",
                        platform = obj["platform"]?.jsonPrimitive?.content ?: "",
                        firstSeen = obj["first_seen"]?.jsonPrimitive?.content ?: "",
                        lastSeen = obj["last_seen"]?.jsonPrimitive?.content ?: "",
                        eventCount = obj["event_count"]?.jsonPrimitive?.long ?: 0,
                        userCount = obj["user_count"]?.jsonPrimitive?.long ?: 0,
                        status = effectiveStatus,
                        substatus = null,
                        statusDetail = null
                    )
                }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch issues for project $projectId" }
            emptyList()
        }
    }

    suspend fun getIssue(
        issueId: String,
        demoEpochMs: Long? = null
    ): IssueDetailResponse? {
        val escapedIssueId = escapeSql(issueId)
        val projectId = getProjectIdForIssue(issueId) ?: return null
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)
        val retentionDays = queryHelper.getProjectRetentionDays(projectId)
        val retentionClause = queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)

        val detailQuery = """
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

        val pgStatus = transaction {
            IssueStatuses
                .selectAll()
                .where { (IssueStatuses.issue_id eq issueId) and (IssueStatuses.project_id eq projectId) }
                .firstOrNull()
                ?.get(IssueStatuses.status)
        }

        val projectName = transaction {
            Projects
                .selectAll()
                .where { Projects.id eq projectId }
                .firstOrNull()
                ?.get(Projects.name)
        } ?: "Unknown"

        return try {
            val response = ClickHouseClient.execute(detailQuery)
            val body = response.bodyAsText()
            val line = body.lines().firstOrNull { it.isNotBlank() } ?: return null
            val obj = json.parseToJsonElement(line).jsonObject

            val fingerprintArr =
                obj["fingerprint"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull ?: "" } ?: emptyList()
            val effectiveStatus = pgStatus ?: (obj["status"]?.jsonPrimitive?.contentOrNull ?: "unresolved")

            val latestEvent = getIssueEvents(issueId, 1, demoEpochMs).firstOrNull()

            IssueDetailResponse(
                id = issueId,
                projectId = projectId,
                projectName = projectName,
                title = obj["title"]?.jsonPrimitive?.content ?: "",
                culprit = obj["culprit"]?.jsonPrimitive?.content ?: "",
                level = obj["level"]?.jsonPrimitive?.content ?: "error",
                platform = obj["platform"]?.jsonPrimitive?.content ?: "",
                firstSeen = obj["first_seen"]?.jsonPrimitive?.content ?: "",
                lastSeen = obj["last_seen"]?.jsonPrimitive?.content ?: "",
                eventCount = obj["event_count"]?.jsonPrimitive?.long ?: 0,
                userCount = obj["user_count"]?.jsonPrimitive?.long ?: 0,
                status = effectiveStatus,
                substatus = null,
                statusDetail = null,
                fingerprint = fingerprintArr,
                latestEvent = latestEvent
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch issue $issueId" }
            null
        }
    }

    suspend fun getIssueEvents(
        issueId: String,
        limit: Int,
        demoEpochMs: Long? = null
    ): List<EventResponse> {
        val projectId = getProjectIdForIssue(issueId) ?: return emptyList()
        val escapedIssueId = escapeSql(issueId)
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)
        val retentionDays = queryHelper.getProjectRetentionDays(projectId)
        val retentionClause = queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)

        val query = """
            SELECT
                toString(event_id) as event_id,
                formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as timestamp,
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

        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299) return emptyList()
            body.lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    queryHelper.mapEventRow(obj)
                }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch events for issue $issueId" }
            emptyList()
        }
    }

    suspend fun getIssueTransactions(
        issueId: String,
        limit: Int,
        demoEpochMs: Long? = null
    ): List<IssueTransactionResponse> {
        val projectId = getProjectIdForIssue(issueId) ?: return emptyList()
        val escapedIssueId = escapeSql(issueId)
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)
        val retentionDays = queryHelper.getProjectRetentionDays(projectId)
        val retentionClause = queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)

        val query = """
            SELECT
                toString(event_id) as event_id,
                transaction_name as name,
                transaction_op as op,
                duration_ms as duration,
                formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as timestamp,
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

        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299) return emptyList()
            body.lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    IssueTransactionResponse(
                        eventId = obj["event_id"]?.jsonPrimitive?.content ?: "",
                        name = obj["name"]?.jsonPrimitive?.content ?: "",
                        op = obj["op"]?.jsonPrimitive?.content ?: "",
                        duration = obj["duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                        timestamp = obj["timestamp"]?.jsonPrimitive?.content ?: "",
                        status = obj["status"]?.jsonPrimitive?.contentOrNull,
                        traceId = obj["trace_id"]?.jsonPrimitive?.contentOrNull
                    )
                }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch transactions for issue $issueId" }
            emptyList()
        }
    }

    suspend fun updateIssue(
        issueId: String,
        update: com.moneat.events.models.IssueUpdateRequest
    ) {
        val projectId = getProjectIdForIssue(issueId)
            ?: throw IllegalArgumentException("Issue not found")

        if (update.status != null) {
            val validStatuses = setOf("unresolved", "resolved", "archived", "ignored")
            if (update.status !in validStatuses) {
                throw IllegalArgumentException("Invalid status value")
            }
            transaction {
                val existing = IssueStatuses
                    .selectAll()
                    .where { (IssueStatuses.issue_id eq issueId) and (IssueStatuses.project_id eq projectId) }
                    .firstOrNull()

                if (existing != null) {
                    IssueStatuses.update(
                        where = { (IssueStatuses.issue_id eq issueId) and (IssueStatuses.project_id eq projectId) }
                    ) {
                        it[IssueStatuses.status] = update.status
                        it[IssueStatuses.updated_at] = kotlin.time.Clock.System.now()
                    }
                } else {
                    IssueStatuses.insert {
                        it[IssueStatuses.issue_id] = issueId
                        it[IssueStatuses.project_id] = projectId
                        it[IssueStatuses.status] = update.status
                        it[IssueStatuses.updated_at] = kotlin.time.Clock.System.now()
                    }
                }
            }
        }
    }
}
