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

import kotlinx.serialization.SerializationException
import java.io.IOException

import com.moneat.config.ClickHouseClient
import com.moneat.events.repositories.models.ErrorEventInsertData
import com.moneat.events.repositories.models.FeedbackInsertData
import com.moneat.events.repositories.models.LlmGenerationInsertData
import com.moneat.events.repositories.models.ProfileInsertData
import com.moneat.events.repositories.models.ProjectKeyVerification
import com.moneat.events.repositories.models.ReplayEventInsertData
import com.moneat.events.repositories.models.ReplayRecordingInsertData
import com.moneat.events.repositories.models.SpanInsertData
import com.moneat.events.repositories.models.TransactionEventInsertData
import com.moneat.shared.models.ProjectKeys
import com.moneat.shared.models.Projects
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

class EventRepositoryImpl : EventRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val db: String get() = ClickHouseClient.getDatabase()

    override fun verifyProjectKey(projectId: Long, publicKey: String): ProjectKeyVerification =
        transaction {
            ProjectKeys
                .selectAll()
                .where {
                    (ProjectKeys.project_id eq projectId) and
                        (ProjectKeys.public_key eq publicKey) and
                        (ProjectKeys.is_active eq true)
                }.firstOrNull()
                ?.let { row ->
                    ProjectKeyVerification(true, row[ProjectKeys.platform_target])
                } ?: ProjectKeyVerification(false, null)
        }

    override fun getOrganizationIdForProject(projectId: Long): Int? =
        transaction {
            Projects
                .selectAll()
                .where { Projects.id eq projectId }
                .firstOrNull()
                ?.get(Projects.organization_id)
        }

    override suspend fun getEventCountForIssue(projectId: Long, issueId: String): Long {
        val escapedIssueId = escapeSql(issueId)
        val query = """
            SELECT count() as cnt
            FROM `$db`.events
            WHERE project_id = $projectId
              AND issue_id = '$escapedIssueId'
            FORMAT JSON
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            val jsonResponse = json.parseToJsonElement(response.bodyAsText()).jsonObject
            jsonResponse["data"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("cnt")
                ?.jsonPrimitive
                ?.longOrNull ?: 0
        } catch (e: SerializationException) {
            logger.error(e) { "Error checking event count for issue $issueId" }
            0
        } catch (e: IOException) {
            logger.error(e) { "Error checking event count for issue $issueId" }
            0
        } catch (e: IllegalStateException) {
            logger.error(e) { "Error checking event count for issue $issueId" }
            0
        } catch (e: IllegalArgumentException) {
            logger.error(e) { "Error checking event count for issue $issueId" }
            0
        }
    }

    override suspend fun insertErrorEvent(data: ErrorEventInsertData): Boolean {
        val fingerprintArray = "[${data.fingerprint.joinToString(",") { "'${escapeSql(it)}'" }}]"
        val tagsMap = tagsToMap(data.tags)
        val sql = """
            INSERT INTO `$db`.events (
                event_id, project_id, timestamp, event_type, level,
                message, platform, environment, release, dist, server_name,
                user_id, user_email, user_username, user_ip_address,
                exception_type, exception_value, stack_trace,
                fingerprint, issue_id, tags, contexts, breadcrumbs, request,
                sdk_name, sdk_version
            ) VALUES (
                toUUID('${escapeSql(data.eventId)}'),
                ${data.projectId},
                fromUnixTimestamp64Milli(${data.timestampMs}),
                'error',
                '${escapeSql(data.level)}',
                '${escapeSql(data.message)}',
                '${escapeSql(data.platform)}',
                '${escapeSql(data.environment)}',
                '${escapeSql(data.release)}',
                '${escapeSql(data.dist)}',
                '${escapeSql(data.serverName)}',
                '${escapeSql(data.userId)}',
                '${escapeSql(data.userEmail)}',
                '${escapeSql(data.userUsername)}',
                '${escapeSql(data.userIpAddress)}',
                '${escapeSql(data.exceptionType)}',
                '${escapeSql(data.exceptionValue)}',
                '${escapeSql(data.stackTrace)}',
                $fingerprintArray,
                '${escapeSql(data.issueId)}',
                $tagsMap,
                '${escapeSql(data.contexts)}',
                '${escapeSql(data.breadcrumbs)}',
                '${escapeSql(data.request)}',
                '${escapeSql(data.sdkName)}',
                '${escapeSql(data.sdkVersion)}'
            )
        """.trimIndent()
        return executeInsert(sql)
    }

    override suspend fun insertTransaction(data: TransactionEventInsertData): Boolean {
        val tagsMap = tagsToMap(data.tags)
        val sql = """
            INSERT INTO `$db`.events (
                event_id, project_id, timestamp, event_type, level,
                message, platform, environment, release, dist, server_name,
                user_id, user_email, user_username, user_ip_address,
                exception_type, exception_value, stack_trace,
                transaction_name, transaction_op, duration_ms,
                fingerprint, issue_id, tags, contexts, breadcrumbs, request,
                sdk_name, sdk_version
            ) VALUES (
                toUUID('${escapeSql(data.eventId)}'),
                ${data.projectId},
                fromUnixTimestamp64Milli(${data.timestampMs}),
                'transaction',
                '${escapeSql(data.level)}',
                '${escapeSql(data.message)}',
                '${escapeSql(data.platform)}',
                '${escapeSql(data.environment)}',
                '${escapeSql(data.release)}',
                '${escapeSql(data.dist)}',
                '${escapeSql(data.serverName)}',
                '${escapeSql(data.userId)}',
                '${escapeSql(data.userEmail)}',
                '${escapeSql(data.userUsername)}',
                '${escapeSql(data.userIpAddress)}',
                '',
                '',
                '',
                '${escapeSql(data.transactionName)}',
                '${escapeSql(data.transactionOp)}',
                ${data.durationMs},
                [],
                '',
                $tagsMap,
                '${escapeSql(data.contexts)}',
                '${escapeSql(data.breadcrumbs)}',
                '${escapeSql(data.request)}',
                '${escapeSql(data.sdkName)}',
                '${escapeSql(data.sdkVersion)}'
            )
        """.trimIndent()
        return executeInsert(sql)
    }

    override suspend fun insertSpans(rows: List<SpanInsertData>) {
        if (rows.isEmpty()) return
        val valueRows = rows.joinToString(",\n") { span ->
            """(
                '${escapeSql(span.spanId)}',
                '${escapeSql(span.parentSpanId)}',
                '${escapeSql(span.traceId)}',
                toUUID('${escapeSql(span.transactionId)}'),
                ${span.projectId},
                '${escapeSql(span.op)}',
                '${escapeSql(span.description)}',
                fromUnixTimestamp64Milli(${span.startTimestampMs}),
                fromUnixTimestamp64Milli(${span.endTimestampMs}),
                ${span.durationMs},
                '${escapeSql(span.status)}',
                ${tagsToMap(span.tags)},
                '${escapeSql(span.data)}'
            )"""
        }
        val sql = """
            INSERT INTO `$db`.spans (
                span_id, parent_span_id, trace_id, transaction_id, project_id,
                op, description, start_timestamp, end_timestamp, duration_ms, status, tags, data
            ) VALUES
            $valueRows
        """.trimIndent()
        executeInsertNoResult(sql)
    }

    override suspend fun insertFeedback(data: FeedbackInsertData): Boolean {
        val tagsMap = tagsToMap(data.tags)
        val sql = """
            INSERT INTO `$db`.user_feedback (
                feedback_id, project_id, timestamp, message, contact_email, name, url,
                associated_event_id, replay_id, environment, release, platform,
                user_id, user_email, user_username, user_ip_address,
                sdk_name, sdk_version, tags, status
            ) VALUES (
                toUUID('${escapeSql(data.feedbackId)}'),
                ${data.projectId},
                fromUnixTimestamp64Milli(${data.timestampMs}),
                '${escapeSql(data.message)}',
                '${escapeSql(data.contactEmail)}',
                '${escapeSql(data.name)}',
                '${escapeSql(data.url)}',
                '${escapeSql(data.associatedEventId)}',
                '${escapeSql(data.replayId)}',
                '${escapeSql(data.environment)}',
                '${escapeSql(data.release)}',
                '${escapeSql(data.platform)}',
                '${escapeSql(data.userId)}',
                '${escapeSql(data.userEmail)}',
                '${escapeSql(data.userUsername)}',
                '${escapeSql(data.userIpAddress)}',
                '${escapeSql(data.sdkName)}',
                '${escapeSql(data.sdkVersion)}',
                $tagsMap,
                'unresolved'
            )
        """.trimIndent()
        return executeInsert(sql)
    }

    override suspend fun insertReplayEvent(data: ReplayEventInsertData): Boolean {
        val urlsArray = "[${data.urls.joinToString(",") { "'${escapeSql(it)}'" }}]"
        val errorIdsArray = "[${data.errorIds.joinToString(",") { "'${escapeSql(it)}'" }}]"
        val traceIdsArray = "[${data.traceIds.joinToString(",") { "'${escapeSql(it)}'" }}]"
        val sql = """
            INSERT INTO `$db`.replay_events (
                replay_id, project_id, segment_id, timestamp, replay_start_timestamp,
                urls, error_ids, trace_ids, environment, release, platform,
                user_id, user_email, user_username, user_ip_address,
                sdk_name, sdk_version, browser_name, browser_version,
                os_name, os_version, device_name, device_family, activity, tags
            ) VALUES (
                toUUID('${escapeSql(data.replayId)}'),
                ${data.projectId},
                ${data.segmentId},
                fromUnixTimestamp64Milli(${data.timestampMs}),
                fromUnixTimestamp64Milli(${data.replayStartTimestampMs}),
                $urlsArray,
                $errorIdsArray,
                $traceIdsArray,
                '${escapeSql(data.environment)}',
                '${escapeSql(data.release)}',
                '${escapeSql(data.platform)}',
                '${escapeSql(data.userId)}',
                '${escapeSql(data.userEmail)}',
                '${escapeSql(data.userUsername)}',
                '${escapeSql(data.userIpAddress)}',
                '${escapeSql(data.sdkName)}',
                '${escapeSql(data.sdkVersion)}',
                '${escapeSql(data.browserName)}',
                '${escapeSql(data.browserVersion)}',
                '${escapeSql(data.osName)}',
                '${escapeSql(data.osVersion)}',
                '${escapeSql(data.deviceName)}',
                '${escapeSql(data.deviceFamily)}',
                ${data.activity},
                '${escapeSql(data.tags)}'
            )
        """.trimIndent()
        return executeInsert(sql)
    }

    override suspend fun insertReplayRecording(data: ReplayRecordingInsertData) {
        val sql = """
            INSERT INTO `$db`.replay_segments (
                replay_id, project_id, segment_id, timestamp, recording_data
            ) VALUES (
                toUUID('${escapeSql(data.replayId)}'),
                ${data.projectId},
                ${data.segmentId},
                fromUnixTimestamp64Milli(${data.timestampMs}),
                '${escapeSql(data.recordingData)}'
            )
        """.trimIndent()
        executeInsertNoResult(sql)
    }

    override suspend fun insertLlmGenerations(rows: List<LlmGenerationInsertData>): Boolean {
        if (rows.isEmpty()) return true
        val valueRows = rows.joinToString(",\n") { g ->
            """(
                toUUID('${escapeSql(g.generationId)}'),
                ${g.projectId},
                '${escapeSql(g.traceId)}',
                '${escapeSql(g.spanId)}',
                '${escapeSql(g.parentSpanId)}',
                fromUnixTimestamp64Milli(${g.timestampMs}),
                ${g.durationMs},
                '${escapeSql(g.name)}',
                '${escapeSql(g.model)}',
                '${escapeSql(g.provider)}',
                '${escapeSql(g.type)}',
                '${escapeSql(g.input)}',
                '${escapeSql(g.output)}',
                ${g.inputTokens},
                ${g.outputTokens},
                ${g.totalTokens},
                0.0,
                0, 0, 0,
                '${escapeSql(g.status)}',
                '', 0,
                '${escapeSql(g.userId)}',
                '',
                '${escapeSql(g.environment)}',
                '${escapeSql(g.release)}',
                ${tagsToMap(g.tags)},
                '{}'
            )"""
        }
        val sql = """
            INSERT INTO `$db`.llm_generations (
                generation_id, project_id, trace_id, span_id, parent_span_id,
                timestamp, duration_ms, name, model, provider, type,
                input, output, input_tokens, output_tokens, total_tokens, cost_usd,
                temperature, max_tokens, top_p,
                status, error_message, status_code,
                user_id, session_id, environment, release, tags, metadata
            ) VALUES
            $valueRows
        """.trimIndent()
        return executeInsert(sql)
    }

    override suspend fun insertProfile(data: ProfileInsertData): Boolean {
        val sql = """
            INSERT INTO `$db`.profiles (
                profile_id, organization_id,
                host, service, env, version,
                runtime, language, profile_type,
                start_time, end_time, duration_ns,
                storage_key, tags, size_bytes, source
            ) VALUES (
                generateUUIDv4(),
                ${data.organizationId},
                '',
                '${escapeSql(data.service)}',
                '${escapeSql(data.environment)}',
                '${escapeSql(data.release)}',
                '${escapeSql(data.runtime)}',
                '${escapeSql(data.platform)}',
                'cpu',
                fromUnixTimestamp64Milli(${data.startTimeMs}),
                fromUnixTimestamp64Milli(${data.endTimeMs}),
                ${data.durationNs},
                '${escapeSql(data.storageKey)}',
                map(),
                ${data.payloadSizeBytes},
                'sentry'
            )
        """.trimIndent()
        return executeInsert(sql)
    }

    private suspend fun executeInsert(sql: String): Boolean {
        return try {
            val response = ClickHouseClient.execute(sql)
            response.status.isSuccess()
        } catch (e: SerializationException) {
            logger.error(e) { "ClickHouse insert failed" }
            false
        } catch (e: IOException) {
            logger.error(e) { "ClickHouse insert failed" }
            false
        } catch (e: IllegalStateException) {
            logger.error(e) { "ClickHouse insert failed" }
            false
        } catch (e: IllegalArgumentException) {
            logger.error(e) { "ClickHouse insert failed" }
            false
        }
    }

    private suspend fun executeInsertNoResult(sql: String) {
        try {
            val response = ClickHouseClient.execute(sql)
            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                logger.error { "ClickHouse insert failed: $errorBody" }
            }
        } catch (e: SerializationException) {
            logger.error(e) { "ClickHouse insert failed" }
        } catch (e: IOException) {
            logger.error(e) { "ClickHouse insert failed" }
        } catch (e: IllegalStateException) {
            logger.error(e) { "ClickHouse insert failed" }
        } catch (e: IllegalArgumentException) {
            logger.error(e) { "ClickHouse insert failed" }
        }
    }

    private fun tagsToMap(tags: Map<String, String>?): String {
        if (tags.isNullOrEmpty()) return "{}"
        return "{${tags.entries.joinToString(",") { "'${escapeSql(it.key)}':'${escapeSql(it.value)}'" }}}"
    }
}
