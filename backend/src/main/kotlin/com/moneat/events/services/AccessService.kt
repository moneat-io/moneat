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
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Projects
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

class AccessService(
    private val queryHelper: DashboardQueryHelper,
    private val issueService: IssueService,
    private val transactionService: TransactionService,
    private val feedbackService: FeedbackService,
    private val replayService: ReplayService
) {
    private val clickhouseDb: String get() = queryHelper.clickhouseDb
    private val json get() = queryHelper.json

    fun hasProjectAccess(userId: Int, projectId: Long): Boolean {
        return transaction {
            val project = Projects
                .selectAll()
                .where { Projects.id eq projectId }
                .firstOrNull()
                ?: return@transaction false

            val orgId = project[Projects.organization_id]
            Memberships
                .selectAll()
                .where { (Memberships.user_id eq userId) and (Memberships.organization_id eq orgId) }
                .count() > 0
        }
    }

    suspend fun hasIssueAccess(userId: Int, issueId: String): Boolean {
        val projectId = issueService.getProjectIdForIssue(issueId) ?: return false
        return hasProjectAccess(userId, projectId)
    }

    suspend fun hasTransactionAccess(userId: Int, eventId: String): Boolean {
        val projectId = transactionService.getProjectIdForTransaction(eventId) ?: return false
        return hasProjectAccess(userId, projectId)
    }

    suspend fun hasTraceAccess(userId: Int, projectId: Long): Boolean =
        hasProjectAccess(userId, projectId)

    suspend fun hasSpanAccess(userId: Int, projectId: Long): Boolean =
        hasProjectAccess(userId, projectId)

    suspend fun hasReplayAccess(userId: Int, replayId: String): Boolean {
        val projectId = replayService.getProjectIdForReplay(replayId) ?: return false
        return hasProjectAccess(userId, projectId)
    }

    suspend fun hasFeedbackAccess(userId: Int, feedbackId: String): Boolean {
        val projectId = feedbackService.getProjectIdForFeedback(feedbackId) ?: return false
        return hasProjectAccess(userId, projectId)
    }

    suspend fun getProjectIdForEvent(eventId: String): Long? {
        val normalizedEventId = queryHelper.normalizeUuid(eventId) ?: return null
        val query = """
            SELECT toInt64(project_id) as project_id
            FROM `$clickhouseDb`.events
            WHERE toString(event_id) = '$normalizedEventId'
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) return null
            if (body.isBlank()) return null
            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            obj["project_id"]?.jsonPrimitive?.longOrNull?.takeIf { it != 0L }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get project ID for event $eventId" }
            null
        }
    }

    suspend fun getIssueIdForEvent(eventId: String): String? {
        val normalizedEventId = queryHelper.normalizeUuid(eventId) ?: return null
        val query = """
            SELECT issue_id
            FROM `$clickhouseDb`.events
            WHERE toString(event_id) = '$normalizedEventId' AND event_type = 'error' AND issue_id != ''
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) return null
            if (body.isBlank()) return null
            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            obj["issue_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get issue ID for event $eventId" }
            null
        }
    }
}
