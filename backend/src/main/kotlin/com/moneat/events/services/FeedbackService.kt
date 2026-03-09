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
import com.moneat.events.models.FeedbackDetailResponse
import com.moneat.events.models.FeedbackListItem
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class FeedbackService(private val queryHelper: DashboardQueryHelper) {
    private val clickhouseDb: String get() = queryHelper.clickhouseDb
    private val json get() = queryHelper.json

    suspend fun getProjectIdForFeedback(feedbackId: String): Long? {
        val normalizedFeedbackId = queryHelper.normalizeUuid(feedbackId) ?: return null
        val query =
            """
            SELECT toInt64(project_id) as project_id
            FROM `$clickhouseDb`.user_feedback FINAL
            WHERE toString(feedback_id) = '$normalizedFeedbackId'
            LIMIT 1
            FORMAT JSONEachRow
            """.trimIndent()
        val projectId = queryHelper.executeProjectIdQuery(query, "Feedback", feedbackId)
        if (projectId == null || projectId <= 0L) {
            logger.warn { "Invalid project_id for feedback $feedbackId: $projectId" }
            return null
        }
        return projectId
    }

    suspend fun getFeedback(
        projectId: Long,
        page: Int = 1,
        limit: Int = 25,
        status: String? = null,
        demoEpochMs: Long? = null
    ): List<FeedbackListItem> {
        val offset = (page - 1) * limit
        val retentionDays = queryHelper.getProjectRetentionDays(projectId)
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)
        val validStatuses = setOf("unresolved", "resolved", "archived")
        val statusFilter =
            if (status != null && status in validStatuses) {
                "AND status = '${escapeSql(status)}'"
            } else {
                ""
            }

        val query =
            """
            SELECT
                toString(feedback_id) as feedback_id,
                message,
                contact_email,
                name,
                url,
                status,
                formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as created_at,
                environment,
                release,
                platform,
                user_id,
                user_email,
                user_username,
                associated_event_id,
                replay_id
            FROM `$clickhouseDb`.user_feedback FINAL
            WHERE $projectIdClause
                AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
                $statusFilter
            ORDER BY timestamp DESC
            LIMIT $limit OFFSET $offset
            FORMAT JSONEachRow
            """.trimIndent()

        val rows = queryHelper.executeJsonEachRowQuery(query, "Feedback list") ?: return emptyList()
        return rows.map { obj ->
            FeedbackListItem(
                feedbackId = obj["feedback_id"]?.jsonPrimitive?.content ?: "",
                message = obj["message"]?.jsonPrimitive?.content ?: "",
                contactEmail = obj["contact_email"]?.jsonPrimitive?.content ?: "",
                name = obj["name"]?.jsonPrimitive?.content ?: "",
                url = obj["url"]?.jsonPrimitive?.content ?: "",
                status = obj["status"]?.jsonPrimitive?.content ?: "unresolved",
                timestamp = obj["created_at"]?.jsonPrimitive?.content ?: "",
                environment = obj["environment"]?.jsonPrimitive?.content ?: "",
                release = obj["release"]?.jsonPrimitive?.content ?: "",
                platform = obj["platform"]?.jsonPrimitive?.content ?: "",
                user = queryHelper.extractUserInfo(obj),
                associatedEventId =
                obj["associated_event_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                replayId = obj["replay_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            )
        }
    }

    suspend fun getFeedbackDetail(feedbackId: String): FeedbackDetailResponse? {
        val normalizedFeedbackId = queryHelper.normalizeUuid(feedbackId) ?: return null
        val projectId = getProjectIdForFeedback(normalizedFeedbackId) ?: return null
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)

        // For feedback detail, we don't apply retention filtering since we're looking up a specific ID
        // The feedback item was already shown in the list (which did apply retention), so we know it exists
        val query =
            """
            SELECT
                toString(feedback_id) as feedback_id,
                message,
                contact_email,
                name,
                url,
                status,
                formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as timestamp,
                environment,
                release,
                platform,
                user_id,
                user_email,
                user_username,
                associated_event_id,
                replay_id,
                tags,
                sdk_name,
                sdk_version
            FROM `$clickhouseDb`.user_feedback FINAL
            WHERE toString(feedback_id) = '$normalizedFeedbackId'
                AND $projectIdClause
            LIMIT 1
            FORMAT JSONEachRow
            """.trimIndent()

        val obj = queryHelper.executeJsonEachRowQuery(query, "Feedback detail")?.firstOrNull() ?: return null
        val tagsMap = parseTagsMap(obj["tags"])
        return FeedbackDetailResponse(
            feedbackId = obj["feedback_id"]?.jsonPrimitive?.content ?: return null,
            message = obj["message"]?.jsonPrimitive?.content ?: "",
            contactEmail = obj["contact_email"]?.jsonPrimitive?.content ?: "",
            name = obj["name"]?.jsonPrimitive?.content ?: "",
            url = obj["url"]?.jsonPrimitive?.content ?: "",
            status = obj["status"]?.jsonPrimitive?.content ?: "unresolved",
            timestamp = obj["timestamp"]?.jsonPrimitive?.content ?: "",
            environment = obj["environment"]?.jsonPrimitive?.content ?: "",
            release = obj["release"]?.jsonPrimitive?.content ?: "",
            platform = obj["platform"]?.jsonPrimitive?.content ?: "",
            user = queryHelper.extractUserInfo(obj),
            associatedEventId = obj["associated_event_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            replayId = obj["replay_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            tags = tagsMap,
            sdkName = obj["sdk_name"]?.jsonPrimitive?.content ?: "",
            sdkVersion = obj["sdk_version"]?.jsonPrimitive?.content ?: ""
        )
    }

    private fun parseTagsMap(element: JsonElement?): Map<String, String> {
        if (element == null) return emptyMap()
        val obj = element as? JsonObject ?: return emptyMap()
        return obj.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: "" }
    }

    suspend fun updateFeedback(
        feedbackId: String,
        update: com.moneat.events.models.FeedbackUpdateRequest
    ) {
        if (update.status != null) {
            val validStatuses = setOf("unresolved", "resolved", "archived")
            require(update.status in validStatuses) { "Invalid status value" }
            val normalizedFeedbackId =
                queryHelper.normalizeUuid(feedbackId) ?: throw IllegalArgumentException("Invalid feedback ID")
            val escapedStatus = escapeSql(update.status)
            val query =
                """
                ALTER TABLE `$clickhouseDb`.user_feedback
                UPDATE status = '$escapedStatus', updated_at = now64(3)
                WHERE toString(feedback_id) = '$normalizedFeedbackId'
                """.trimIndent()
            try {
                ClickHouseClient.execute(query)
            } catch (e: Exception) {
                logger.error(e) { "Failed to update feedback" }
                throw e
            }
        }
    }
}
