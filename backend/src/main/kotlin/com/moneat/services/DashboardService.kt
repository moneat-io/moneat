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

package com.moneat.services

import com.moneat.config.ClickHouseClient
import com.moneat.config.EnvConfig
import com.moneat.models.*
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.ClickHouseSqlUtils
import io.ktor.client.statement.*
import io.ktor.http.HttpStatusCode
import io.sentry.ISpan
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker
import org.msgpack.value.ValueType
import java.time.Instant
import java.util.*

private val logger = KotlinLogging.logger {}

class DashboardService {
    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val backendUrl = EnvConfig.get("BACKEND_URL", "https://api.moneat.io")
    private val json = Json { ignoreUnknownKeys = true }
    private val retentionPolicyService = RetentionPolicyService()
    private val pricingTierService = PricingTierService()

    /**
     * Safely extracts body text from ClickHouse response, checking for error messages.
     * Returns null if the response contains a ClickHouse error instead of valid data.
     */
    private suspend fun extractClickHouseBody(response: HttpResponse): String? {
        if (response.status != HttpStatusCode.OK) {
            return null
        }
        val body = response.bodyAsText()
        // ClickHouse returns error messages as plain text starting with "Code:"
        if (body.startsWith("Code:") && body.contains("DB::Exception")) {
            logger.warn { "ClickHouse error: ${body.take(200)}" }
            return null
        }
        return body
    }

    fun hasProjectAccess(userId: Int, projectId: Long): Boolean {
        return transaction {
            val orgIds = Memberships.selectAll().where { Memberships.user_id eq userId }
                .map { it[Memberships.organization_id] }

            logger.debug { "hasProjectAccess: userId=$userId, projectId=$projectId, orgIds=$orgIds" }

            val hasAccess = Projects.selectAll().where {
                (Projects.id eq projectId) and (Projects.organization_id inList orgIds)
            }.count() > 0
            logger.debug { "hasProjectAccess result: $hasAccess" }
            hasAccess
        }
    }

    suspend fun hasIssueAccess(userId: Int, issueId: String): Boolean {
        val projectId = getProjectIdForIssue(issueId)
        logger.debug { "hasIssueAccess: issueId=$issueId, projectId=$projectId" }
        if (projectId == null) return false
        return hasProjectAccess(userId, projectId)
    }

    suspend fun hasTransactionAccess(userId: Int, eventId: String): Boolean {
        val projectId = getProjectIdForTransaction(eventId) ?: return false
        return hasProjectAccess(userId, projectId)
    }

    suspend fun hasTraceAccess(userId: Int, projectId: Long): Boolean {
        return hasProjectAccess(userId, projectId)
    }

    suspend fun hasSpanAccess(userId: Int, projectId: Long): Boolean {
        return hasProjectAccess(userId, projectId)
    }

    suspend fun hasReplayAccess(userId: Int, replayId: String): Boolean {
        val projectId = getProjectIdForReplay(replayId) ?: return false
        return hasProjectAccess(userId, projectId)
    }

    suspend fun hasFeedbackAccess(userId: Int, feedbackId: String): Boolean {
        val projectId = getProjectIdForFeedback(feedbackId) ?: return false
        return hasProjectAccess(userId, projectId)
    }

    private suspend fun getProjectIdForIssue(issueId: String): Long? {
        val escapedIssueId = ClickHouseSqlUtils.escapeSql(issueId)
        val query = """
            SELECT toInt64(project_id) as project_id
            FROM $clickhouseDb.issues 
            WHERE issue_id = '$escapedIssueId'
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)

            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error { "Failed to get project ID for issue $issueId: ${response.status} ${body.take(400)}" }
                return null
            }
            if (body.isBlank()) return null
            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            obj["project_id"]?.jsonPrimitive?.long
        } catch (e: Exception) {
            logger.error(e) { "Failed to get project ID for issue" }
            null
        }
    }

    suspend fun getProjectIdForEvent(eventId: String): Long? {
        val normalizedEventId = normalizeUuid(eventId) ?: return null
        val query = """
            SELECT toInt64(project_id) as project_id
            FROM $clickhouseDb.events
            WHERE toString(event_id) = '$normalizedEventId'
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error { "Failed to get project ID for event $eventId: ${response.status} ${body.take(400)}" }
                return null
            }
            if (body.isBlank()) return null
            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            obj["project_id"]?.jsonPrimitive?.long
        } catch (e: Exception) {
            logger.error(e) { "Failed to get project ID for event" }
            null
        }
    }

    suspend fun getIssueIdForEvent(eventId: String): String? {
        val normalizedEventId = normalizeUuid(eventId) ?: return null
        val query = """
            SELECT issue_id
            FROM $clickhouseDb.events
            WHERE toString(event_id) = '$normalizedEventId' AND event_type = 'error' AND issue_id != ''
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error { "Failed to get issue ID for event $eventId: ${response.status} ${body.take(400)}" }
                return null
            }
            if (body.isBlank()) return null
            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            obj["issue_id"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            logger.error(e) { "Failed to get issue ID for event $eventId" }
            null
        }
    }

    private suspend fun getProjectIdForReplay(replayId: String): Long? {
        val normalizedReplayId = normalizeUuid(replayId) ?: return null
        val query = """
            SELECT toInt64(project_id) as project_id
            FROM $clickhouseDb.replay_events
            WHERE toString(replay_id) = '$normalizedReplayId'
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)

            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error { "Failed to get project ID for replay $replayId: ${response.status} ${body.take(400)}" }
                return null
            }
            if (body.isBlank()) return null
            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            obj["project_id"]?.jsonPrimitive?.long
        } catch (e: Exception) {
            logger.error(e) { "Failed to get project ID for replay" }
            null
        }
    }

    private suspend fun getProjectIdForFeedback(feedbackId: String): Long? {
        val normalizedFeedbackId = normalizeUuid(feedbackId) ?: return null
        val query = """
            SELECT toInt64(project_id) as project_id
            FROM $clickhouseDb.user_feedback FINAL
            WHERE toString(feedback_id) = '$normalizedFeedbackId'
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error {
                    "Failed to get project ID for feedback $feedbackId: ${response.status} ${
                        body.take(400)}"
                }
                return null
            }
            if (body.isBlank()) return null
            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            val projectId = obj["project_id"]?.jsonPrimitive?.longOrNull

            // Validate project_id is in valid range (reject corrupted data)
            if (projectId == null || projectId <= 0) {
                logger.warn { "Invalid project_id for feedback $feedbackId: $projectId" }
                return null
            }
            projectId
        } catch (e: Exception) {
            logger.error(e) { "Failed to get project ID for feedback" }
            null
        }
    }

    private suspend fun getProjectIdForTransaction(eventId: String): Long? {
        val normalizedEventId = normalizeUuid(eventId) ?: return null
        val query = """
            SELECT toInt64(project_id) as project_id
            FROM $clickhouseDb.events
            WHERE event_id = toUUID('$normalizedEventId')
                AND event_type = 'transaction'
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)

            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error {
                    "Failed to get project ID for transaction $eventId: ${response.status} ${body.take(
                        400
                    )}"
                }
                return null
            }
            if (body.isBlank()) return null
            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            obj["project_id"]?.jsonPrimitive?.long
        } catch (e: Exception) {
            logger.error(e) { "Failed to get project ID for transaction" }
            null
        }
    }

    private suspend fun getIssueCount(projectId: Long, demoEpochMs: Long? = null): Long {
        val retentionDays = getProjectRetentionDays(projectId)
        val projectIdClause = if (projectId < 0) "toInt64(project_id) = $projectId" else "project_id = $projectId"
        val query = """
            SELECT count(DISTINCT issue_id) as count
            FROM $clickhouseDb.issues
            WHERE $projectIdClause
                AND ${timestampRetentionClause("last_seen", retentionDays, demoEpochMs)}
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)

            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error {
                    "Failed to get issue count for project $projectId: ${response.status} ${body.take(
                        400
                    )}"
                }
                return 0
            }
            if (body.isBlank()) return 0
            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            obj["count"]?.jsonPrimitive?.long ?: 0
        } catch (e: Exception) {
            logger.error(e) { "Failed to get issue count for project $projectId" }
            0
        }
    }

    private suspend fun getBatchIssueCounts(projectIds: List<Long>, demoEpochMs: Long? = null): Map<Long, Long> {
        if (projectIds.isEmpty()) return emptyMap()
        // Use a default retention — individual retention per project would require N queries to resolve.
        // For the dashboard overview list this is acceptable.
        val retentionDays = getProjectRetentionDays(projectIds.first())
        val idList = projectIds.joinToString(",")
        val query = """
            SELECT project_id, count(DISTINCT issue_id) as count
            FROM $clickhouseDb.issues
            WHERE project_id IN ($idList)
                AND ${timestampRetentionClause("last_seen", retentionDays, demoEpochMs)}
            GROUP BY project_id
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error { "Failed to batch get issue counts: ${response.status} ${body.take(400)}" }
                return emptyMap()
            }
            if (body.isBlank()) return emptyMap()
            body.lines().filter { it.isNotBlank() }.associate { line ->
                val obj = json.parseToJsonElement(line).jsonObject
                val pid = obj["project_id"]?.jsonPrimitive?.long ?: 0L
                val count = obj["count"]?.jsonPrimitive?.long ?: 0L
                pid to count
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to batch get issue counts" }
            emptyMap()
        }
    }

    suspend fun getProjects(userId: Int, demoEpochMs: Long? = null): List<ProjectResponse> {
        val projectsData = transaction {
            val orgIds = Memberships.selectAll().where { Memberships.user_id eq userId }
                .map { it[Memberships.organization_id] }

            val projects = Projects.selectAll().where { Projects.organization_id inList orgIds }
                .map { row -> row[Projects.id] to row }

            val projectIds = projects.map { it.first }

            // Batch fetch all project keys in a single query
            val keysByProject = ProjectKeys.selectAll()
                .where { ProjectKeys.project_id inList projectIds }
                .groupBy { it[ProjectKeys.project_id] }
                .mapValues { (projectId, rows) ->
                    rows.map { keyRow ->
                        ProjectKeyResponse(
                            platformTarget = keyRow[ProjectKeys.platform_target],
                            dsn = "https://${keyRow[ProjectKeys.public_key]}@${backendUrl.removePrefix(
                                "http://"
                            ).removePrefix("https://")}/$projectId"
                        )
                    }
                }

            projects.map { (projectId, row) ->
                val keys = keysByProject[projectId] ?: emptyList()
                Pair(
                    projectId,
                    ProjectResponse(
                        id = projectId,
                        name = row[Projects.name],
                        slug = row[Projects.slug],
                        framework = row[Projects.framework],
                        keys = keys,
                        dsn = keys.firstOrNull()?.dsn ?: "",
                        issueCount = 0
                    )
                )
            }
        }

        if (projectsData.isEmpty()) return emptyList()

        // Batch fetch all issue counts in a single ClickHouse query
        val issueCounts = getBatchIssueCounts(projectsData.map { it.first }, demoEpochMs)

        return projectsData.map { (projectId, projectResponse) ->
            projectResponse.copy(issueCount = issueCounts[projectId] ?: 0)
        }
    }

    suspend fun getProject(projectId: Long): ProjectResponse? {
        val projectData = transaction {
            Projects.selectAll().where { Projects.id eq projectId }
                .map { row ->
                    val keys = ProjectKeys.selectAll().where { ProjectKeys.project_id eq projectId }
                        .map { keyRow ->
                            ProjectKeyResponse(
                                platformTarget = keyRow[ProjectKeys.platform_target],
                                dsn = "https://${keyRow[ProjectKeys.public_key]}@${backendUrl.removePrefix(
                                    "http://"
                                ).removePrefix("https://")}/$projectId"
                            )
                        }

                    ProjectResponse(
                        id = projectId,
                        name = row[Projects.name],
                        slug = row[Projects.slug],
                        framework = row[Projects.framework],
                        keys = keys,
                        dsn = keys.firstOrNull()?.dsn ?: "",
                        issueCount = 0
                    )
                }
                .firstOrNull()
        }

        return projectData?.copy(issueCount = getIssueCount(projectId))
    }

    fun createProject(userId: Int, request: com.moneat.models.CreateProjectRequest): ProjectResponse {
        return transaction {
            // Get user's first organization
            val orgId = Memberships.selectAll().where { Memberships.user_id eq userId }
                .firstOrNull()
                ?.get(Memberships.organization_id)
                ?: throw IllegalStateException("User has no organization")

            // Check project limit based on tier
            val tierContext = pricingTierService.getEffectiveTierForOrganization(orgId)
            val maxProjects = tierContext.tier.maxProjects
            if (maxProjects != null) {
                val currentProjectCount = Projects.selectAll()
                    .where { Projects.organization_id eq orgId }
                    .count()

                if (currentProjectCount >= maxProjects) {
                    throw IllegalStateException("project_limit_reached")
                }
            }

            // Create slug from name
            val slug = request.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

            // Check if slug already exists in this organization
            val existingProject = Projects.selectAll()
                .where {
                    (Projects.organization_id eq orgId) and (Projects.slug eq slug)
                }
                .firstOrNull()

            if (existingProject != null) {
                throw IllegalStateException("A project with this name already exists")
            }

            // Insert project
            val projectId = Projects.insert {
                it[organization_id] = orgId
                it[name] = request.name
                it[Projects.slug] = slug
                it[framework] = request.framework
            } get Projects.id

            // Generate project keys (one per target or single key if no targets)
            val keys = mutableListOf<ProjectKeyResponse>()
            val targets = request.targets?.takeIf { it.isNotEmpty() } ?: listOf(null)

            for (target in targets) {
                val publicKey = java.util.UUID.randomUUID().toString().replace("-", "")
                val secretKey = java.util.UUID.randomUUID().toString().replace("-", "")

                ProjectKeys.insert {
                    it[project_id] = projectId
                    it[ProjectKeys.public_key] = publicKey
                    it[secret_key] = secretKey
                    it[platform_target] = target
                    it[is_active] = true
                }

                val dsn = "https://$publicKey@${backendUrl.removePrefix("http://").removePrefix("https://")}/$projectId"
                keys.add(ProjectKeyResponse(platformTarget = target, dsn = dsn))
            }

            ProjectResponse(
                id = projectId,
                name = request.name,
                slug = slug,
                framework = request.framework,
                keys = keys,
                dsn = keys.firstOrNull()?.dsn ?: "",
                issueCount = 0 // New projects always have 0 issues
            )
        }
    }

    fun addProjectTarget(projectId: Long, target: String): ProjectKeyResponse {
        return transaction {
            // Check if target already exists (excluding NULL platform_target entries)
            val existing = ProjectKeys.selectAll()
                .where {
                    (ProjectKeys.project_id eq projectId) and
                        (ProjectKeys.platform_target eq target) and
                        (ProjectKeys.platform_target.isNotNull())
                }
                .firstOrNull()

            if (existing != null) {
                throw IllegalStateException("Target platform '$target' already exists for this project")
            }

            val publicKey = java.util.UUID.randomUUID().toString().replace("-", "")
            val secretKey = java.util.UUID.randomUUID().toString().replace("-", "")

            ProjectKeys.insert {
                it[project_id] = projectId
                it[ProjectKeys.public_key] = publicKey
                it[secret_key] = secretKey
                it[platform_target] = target
                it[is_active] = true
            }

            val dsn = "https://$publicKey@${backendUrl.removePrefix("http://").removePrefix("https://")}/$projectId"
            ProjectKeyResponse(platformTarget = target, dsn = dsn)
        }
    }

    fun updateProject(projectId: Long, request: com.moneat.models.UpdateProjectRequest) {
        transaction {
            Projects.update({ Projects.id eq projectId }) {
                if (request.name != null) {
                    it[name] = request.name
                    it[slug] = request.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                }
                if (request.framework != null) {
                    it[framework] = request.framework
                }
            }
        }
    }

    fun deleteProject(projectId: Long) {
        transaction {
            Projects.deleteWhere { Projects.id eq projectId }
        }
    }

    suspend fun getIssues(projectId: Long, page: Int, limit: Int, status: String?, demoEpochMs: Long? = null): List<IssueResponse> =
        CacheService.cached("cache:issues:$projectId:$page:$limit:${status ?: ""}:${demoEpochMs ?: 0}", 30) {
            val offset = (page - 1) * limit
            val retentionDays = getProjectRetentionDays(projectId)
            val validStatuses = setOf("unresolved", "resolved", "ignored")
            val statusFilter = if (status != null && status in validStatuses) {
                "AND status = '${status.replace("'", "''")}'"
            } else {
                ""
            }

            val projectIdClause = if (projectId < 0) "toInt64(e.project_id) = $projectId" else "e.project_id = $projectId"

            // Query events table directly and aggregate
            val query = """
            SELECT 
                issue_id,
                any(message) as title,
                any(exception_type) as culprit,
                any(level) as level,
                any(platform) as platform,
                formatDateTime(min(timestamp), '%Y-%c-%dT%H:%i:%S.000Z') as first_seen,
                formatDateTime(max(timestamp), '%Y-%c-%dT%H:%i:%S.000Z') as last_seen,
                count() as event_count,
                uniq(user_id) as user_count,
                any(i.status) as status
            FROM $clickhouseDb.events e
            LEFT JOIN (
                SELECT issue_id, status 
                FROM $clickhouseDb.issues FINAL
                WHERE ${timestampRetentionClause("last_seen", retentionDays, demoEpochMs)}
            ) i USING issue_id
            WHERE $projectIdClause 
                AND e.event_type = 'error'
                AND ${timestampRetentionClause("e.timestamp", retentionDays, demoEpochMs)}
                $statusFilter
            GROUP BY issue_id
            ORDER BY last_seen DESC
            LIMIT $limit OFFSET $offset
            FORMAT JSONEachRow
            """.trimIndent()

            try {
                val response = ClickHouseClient.execute(query)
                val body = response.bodyAsText()
                if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                    logger.error { "Failed to fetch issues for project $projectId: ${response.status} ${body.take(400)}" }
                    return@cached emptyList<IssueResponse>()
                }
                body.lines()
                    .filter { it.isNotBlank() }
                    .map { line ->
                        val obj = json.parseToJsonElement(line).jsonObject
                        IssueResponse(
                            id = obj["issue_id"]?.jsonPrimitive?.content ?: "",
                            projectId = projectId,
                            title = obj["title"]?.jsonPrimitive?.content ?: "",
                            culprit = obj["culprit"]?.jsonPrimitive?.content ?: "",
                            level = obj["level"]?.jsonPrimitive?.content ?: "error",
                            platform = obj["platform"]?.jsonPrimitive?.content ?: "",
                            firstSeen = obj["first_seen"]?.jsonPrimitive?.content ?: "",
                            lastSeen = obj["last_seen"]?.jsonPrimitive?.content ?: "",
                            eventCount = obj["event_count"]?.jsonPrimitive?.long ?: 0,
                            userCount = obj["user_count"]?.jsonPrimitive?.long ?: 0,
                            status = obj["status"]?.jsonPrimitive?.content ?: "unresolved"
                        )
                    }
            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch issues" }
                emptyList()
            }
        }

    suspend fun getIssue(issueId: String, demoEpochMs: Long? = null): IssueDetailResponse? {
        val projectId = getProjectIdForIssue(issueId) ?: return null
        val retentionDays = getProjectRetentionDays(projectId)
        val escapedIssueId = issueId.replace("'", "''")
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)

        // Query events table directly and aggregate
        val query = """
            SELECT 
                issue_id,
                any(message) as title,
                any(exception_type) as culprit,
                any(level) as level,
                any(platform) as platform,
                formatDateTime(min(timestamp), '%Y-%c-%dT%H:%i:%S.000Z') as first_seen,
                formatDateTime(max(timestamp), '%Y-%c-%dT%H:%i:%S.000Z') as last_seen,
                count() as event_count,
                uniq(user_id) as user_count,
                any(i.status) as status,
                any(fingerprint) as fingerprint
            FROM $clickhouseDb.events e
            LEFT JOIN (
                SELECT issue_id, status 
                FROM $clickhouseDb.issues FINAL
                WHERE ${timestampRetentionClause("last_seen", retentionDays, demoEpochMs)}
            ) i USING issue_id
            WHERE e.issue_id = '$escapedIssueId'
                AND $projectIdClause
                AND ${timestampRetentionClause("e.timestamp", retentionDays, demoEpochMs)}
            GROUP BY issue_id
            FORMAT JSONEachRow
        """.trimIndent()

        // Fetch latest event with full details
        val latestEventQuery = """
            SELECT 
                event_id,
                timestamp,
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
                stack_trace,
                breadcrumbs
            FROM $clickhouseDb.events
            WHERE issue_id = '$escapedIssueId'
                AND $projectIdClause
                AND ${timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            ORDER BY timestamp DESC
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)

            val body = response.bodyAsText()

            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error { "Failed to fetch issue detail for $issueId: ${response.status} ${body.take(400)}" }
                return null
            }
            if (body.isBlank()) return null

            val obj = json.parseToJsonElement(body.lines().first()).jsonObject

            // Fetch latest event
            val latestEventResponse = ClickHouseClient.execute(latestEventQuery)
            val latestEventBody = latestEventResponse.bodyAsText()
            val latestEvent = if (latestEventResponse.status.value in 200..299 && latestEventBody.isNotBlank() && !latestEventBody.trimStart().startsWith("Code:")) {
                val eventObj = json.parseToJsonElement(latestEventBody.lines().first()).jsonObject
                EventResponse(
                    eventId = eventObj["event_id"]?.jsonPrimitive?.content ?: "",
                    timestamp = eventObj["timestamp"]?.jsonPrimitive?.content ?: "",
                    message = eventObj["message"]?.jsonPrimitive?.content ?: "",
                    platform = eventObj["platform"]?.jsonPrimitive?.content ?: "",
                    level = eventObj["level"]?.jsonPrimitive?.content ?: "error",
                    environment = eventObj["environment"]?.jsonPrimitive?.contentOrNull,
                    release = eventObj["release"]?.jsonPrimitive?.contentOrNull,
                    user = eventObj["user_id"]?.jsonPrimitive?.content?.let {
                        UserInfo(
                            id = it,
                            email = eventObj["user_email"]?.jsonPrimitive?.contentOrNull,
                            username = eventObj["user_username"]?.jsonPrimitive?.contentOrNull
                        )
                    },
                    tags = HashMap(
                        eventObj["tags"]?.jsonObject?.mapValues {
                            it.value.jsonPrimitive.content
                        } ?: emptyMap()
                    ),
                    contexts = eventObj["contexts"]?.jsonPrimitive?.content ?: "{}",
                    exception = eventObj["stack_trace"]?.jsonPrimitive?.contentOrNull,
                    breadcrumbs = eventObj["breadcrumbs"]?.jsonPrimitive?.contentOrNull
                )
            } else {
                null
            }

            val projectName = when {
                projectId == -1L -> "Android"
                projectId == -2L -> "iOS"
                projectId == -3L -> "React Native"
                else -> transaction {
                    Projects.selectAll().where { Projects.id eq projectId }
                        .firstOrNull()?.get(Projects.name) ?: ""
                }
            }

            IssueDetailResponse(
                id = obj["issue_id"]?.jsonPrimitive?.content ?: "",
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
                status = obj["status"]?.jsonPrimitive?.content ?: "unresolved",
                fingerprint = obj["fingerprint"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                latestEvent = latestEvent
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch issue detail" }
            null
        }
    }

    suspend fun getIssueEvents(issueId: String, limit: Int, demoEpochMs: Long? = null): List<EventResponse> {
        val projectId = getProjectIdForIssue(issueId) ?: return emptyList()
        val retentionDays = getProjectRetentionDays(projectId)
        val escapedIssueId = issueId.replace("'", "''")
        val query = """
            SELECT 
                event_id,
                timestamp,
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
                stack_trace,
                breadcrumbs
            FROM $clickhouseDb.events
            WHERE issue_id = '$escapedIssueId'
                AND project_id = $projectId
                AND ${timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            ORDER BY timestamp DESC
            LIMIT $limit
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)

            val body = response.bodyAsText()

            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error { "Failed to fetch events for issue $issueId: ${response.status} ${body.take(400)}" }
                return emptyList()
            }

            body.lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    EventResponse(
                        eventId = obj["event_id"]?.jsonPrimitive?.content ?: "",
                        timestamp = obj["timestamp"]?.jsonPrimitive?.content ?: "",
                        message = obj["message"]?.jsonPrimitive?.content ?: "",
                        platform = obj["platform"]?.jsonPrimitive?.content ?: "",
                        level = obj["level"]?.jsonPrimitive?.content ?: "error",
                        environment = obj["environment"]?.jsonPrimitive?.contentOrNull,
                        release = obj["release"]?.jsonPrimitive?.contentOrNull,
                        user = obj["user_id"]?.jsonPrimitive?.content?.let {
                            UserInfo(
                                id = it,
                                email = obj["user_email"]?.jsonPrimitive?.contentOrNull,
                                username = obj["user_username"]?.jsonPrimitive?.contentOrNull
                            )
                        },
                        tags = HashMap(
                            obj["tags"]?.jsonObject?.mapValues {
                                it.value.jsonPrimitive.content
                            } ?: emptyMap()
                        ),
                        contexts = obj["contexts"]?.jsonPrimitive?.content ?: "{}",
                        exception = obj["stack_trace"]?.jsonPrimitive?.contentOrNull,
                        breadcrumbs = obj["breadcrumbs"]?.jsonPrimitive?.contentOrNull
                    )
                }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch issue events" }
            emptyList()
        }
    }

    suspend fun getIssueTransactions(issueId: String, limit: Int, demoEpochMs: Long? = null): List<IssueTransactionResponse> {
        val projectId = getProjectIdForIssue(issueId) ?: return emptyList()
        val retentionDays = getProjectRetentionDays(projectId)
        val escapedIssueId = issueId.replace("'", "''")
        val query = """
            WITH (
                SELECT arrayFilter(
                    trace -> trace != '',
                    arrayDistinct(groupArray(JSONExtractString(contexts, 'trace', 'trace_id')))
                )
                FROM $clickhouseDb.events
                WHERE project_id = $projectId
                    AND issue_id = '$escapedIssueId'
                    AND event_type = 'error'
                    AND ${timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            ) AS issue_trace_ids
            SELECT
                toString(event_id) as event_id,
                transaction_name as name,
                transaction_op as op,
                duration_ms as duration,
                timestamp,
                JSONExtractString(contexts, 'trace', 'status') as status,
                JSONExtractString(contexts, 'trace', 'trace_id') as trace_id
            FROM $clickhouseDb.events
            WHERE project_id = $projectId
                AND event_type = 'transaction'
                AND ${timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
                AND (
                    issue_id = '$escapedIssueId'
                    OR has(issue_trace_ids, JSONExtractString(contexts, 'trace', 'trace_id'))
                )
            ORDER BY timestamp DESC
            LIMIT $limit
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)

            val body = response.bodyAsText()

            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error { "Failed to fetch transactions for issue $issueId: ${response.status} ${body.take(400)}" }
                return emptyList()
            }

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
                        status = obj["status"]?.jsonPrimitive?.contentOrNull?.ifBlank { null },
                        traceId = obj["trace_id"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
                    )
                }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch related transactions for issue $issueId" }
            emptyList()
        }
    }

    suspend fun getTransactions(
        projectId: Long,
        period: String = "7d",
        environment: String? = null,
        operation: String? = null,
        demoEpochMs: Long? = null
    ): List<TransactionSummaryResponse> =
        CacheService.cached(
            "cache:transactions:$projectId:$period:${environment ?: ""}:${operation ?: ""}:${demoEpochMs ?: ""}",
            60
        ) {
            val periodConfig = getPeriodConfig(period)
            val retentionDays = getProjectRetentionDays(projectId)
            val filters = buildTransactionFilterClause(environment, operation)
            val nowSql = demoNowClause(demoEpochMs)
            val query = """
            SELECT
                transaction_name as name,
                transaction_op as op,
                argMax(toString(event_id), timestamp) as latest_event_id,
                count() as count,
                quantileTDigest(0.50)(duration_ms) as p50,
                quantileTDigest(0.75)(duration_ms) as p75,
                quantileTDigest(0.95)(duration_ms) as p95,
                (countIf(level IN ('error', 'fatal')) * 100.0) / if(count() = 0, 1, count()) as failure_rate,
                count() / ${periodConfig.periodMinutes}.0 as tpm
            FROM $clickhouseDb.events
            WHERE project_id = $projectId
                AND event_type = 'transaction'
                AND timestamp >= $nowSql - INTERVAL ${periodConfig.hoursBack} HOUR
                AND ${timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
                $filters
            GROUP BY transaction_name, transaction_op
            ORDER BY p95 DESC
            LIMIT 200
            FORMAT JSONEachRow
            """.trimIndent()

            try {
                val response = ClickHouseClient.execute(query)
                val body = response.bodyAsText()
                if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                    logger.error {
                        "Failed to fetch transactions for project $projectId: ${response.status} ${body.take(
                            400
                        )}"
                    }
                    emptyList<TransactionSummaryResponse>()
                } else {
                    body.lines()
                        .filter { it.isNotBlank() }
                        .map { line ->
                            val obj = json.parseToJsonElement(line).jsonObject
                            TransactionSummaryResponse(
                                name = obj["name"]?.jsonPrimitive?.content ?: "",
                                op = obj["op"]?.jsonPrimitive?.content ?: "",
                                latestEventId = obj["latest_event_id"]?.jsonPrimitive?.contentOrNull
                                    ?: obj["latestEventId"]?.jsonPrimitive?.contentOrNull,
                                count = obj["count"]?.jsonPrimitive?.long ?: 0,
                                p50 = obj["p50"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                                p75 = obj["p75"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                                p95 = obj["p95"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                                failureRate = obj["failure_rate"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                                tpm = obj["tpm"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                            )
                        }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch transactions for project $projectId" }
                emptyList()
            }
        }

    suspend fun getPerformanceStats(
        projectId: Long,
        period: String = "7d",
        environment: String? = null,
        operation: String? = null,
        demoEpochMs: Long? = null
    ): PerformanceStatsResponse =
        CacheService.cached(
            "cache:perf_stats:$projectId:$period:${environment ?: ""}:${operation ?: ""}:${demoEpochMs ?: ""}",
            60
        ) {
            val periodConfig = getPeriodConfig(period)
            val retentionDays = getProjectRetentionDays(projectId)
            val filters = buildTransactionFilterClause(environment, operation)
            val nowSql = demoNowClause(demoEpochMs)

            val aggregateQuery = """
            SELECT
                count() as total,
                avg(duration_ms) as avg_duration,
                (countIf(duration_ms <= 300) + countIf(duration_ms <= 1200)) / (2.0 * if(count() = 0, 1, count())) as apdex
            FROM $clickhouseDb.events e
            WHERE e.project_id = $projectId
                AND e.event_type = 'transaction'
                AND e.timestamp >= $nowSql - INTERVAL ${periodConfig.hoursBack} HOUR
                AND ${timestampRetentionClause("e.timestamp", retentionDays, demoEpochMs)}
                $filters
            FORMAT JSONEachRow
            """.trimIndent()

            val throughputQuery = """
            SELECT
                formatDateTime(
                    toStartOfInterval(e.timestamp, INTERVAL ${periodConfig.intervalMinutes} MINUTE),
                    '%Y-%c-%dT%H:%i:%S.000Z'
                ) as time,
                count() as count
            FROM $clickhouseDb.events e
            WHERE e.project_id = $projectId
                AND e.event_type = 'transaction'
                AND e.timestamp >= $nowSql - INTERVAL ${periodConfig.hoursBack} HOUR
                AND ${timestampRetentionClause("e.timestamp", retentionDays, demoEpochMs)}
                $filters
            GROUP BY time
            ORDER BY time
            FORMAT JSONEachRow
            """.trimIndent()

            val slowestQuery = """
            SELECT
                toString(e.event_id) as event_id,
                e.transaction_name as name,
                e.transaction_op as op,
                e.duration_ms as duration,
                formatDateTime(e.timestamp, '%Y-%c-%dT%H:%i:%S.000Z') as timestamp_iso
            FROM $clickhouseDb.events e
            WHERE e.project_id = $projectId
                AND e.event_type = 'transaction'
                AND e.timestamp >= $nowSql - INTERVAL ${periodConfig.hoursBack} HOUR
                AND ${timestampRetentionClause("e.timestamp", retentionDays, demoEpochMs)}
                $filters
            ORDER BY e.duration_ms DESC
            LIMIT 10
            FORMAT JSONEachRow
            """.trimIndent()

            try {
                val aggregateResponse = ClickHouseClient.execute(aggregateQuery)
                val aggregateBody = aggregateResponse.bodyAsText()
                if (aggregateResponse.status.value !in 200..299 || aggregateBody.trimStart().startsWith("Code:")) {
                    logger.error {
                        "Failed to fetch aggregate performance stats for project $projectId: ${aggregateResponse.status} ${aggregateBody.take(
                            400
                        )}"
                    }
                    return@cached PerformanceStatsResponse(
                        apdex = 0.0,
                        throughput = emptyList(),
                        slowestTransactions = emptyList(),
                        totalTransactions = 0,
                        avgDuration = 0.0
                    )
                }

                val aggregateObj = aggregateBody.lines().firstOrNull { it.isNotBlank() }?.let {
                    json.parseToJsonElement(it).jsonObject
                }

                val throughput = executeTimelineQuery(throughputQuery)
                val slowest = executeSlowestTransactionsQuery(slowestQuery)

                PerformanceStatsResponse(
                    apdex = aggregateObj?.get("apdex")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                    throughput = throughput,
                    slowestTransactions = slowest,
                    totalTransactions = aggregateObj?.get("total")?.jsonPrimitive?.long ?: 0,
                    avgDuration = aggregateObj?.get("avg_duration")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch performance stats for project $projectId" }
                PerformanceStatsResponse(
                    apdex = 0.0,
                    throughput = emptyList(),
                    slowestTransactions = emptyList(),
                    totalTransactions = 0,
                    avgDuration = 0.0
                )
            }
        }

    suspend fun getTransaction(eventId: String): TransactionDetailResponse? {
        val normalizedEventId = normalizeUuid(eventId) ?: return null
        val projectId = getProjectIdForTransaction(normalizedEventId) ?: return null
        val retentionDays = getProjectRetentionDays(projectId)
        val query = """
            SELECT
                toString(e.event_id) as event_id,
                e.transaction_name as name,
                e.transaction_op as op,
                toUnixTimestamp64Milli(
                    ifNull(parseDateTime64BestEffortOrNull(toString(e.timestamp)), now64(3))
                ) as end_ts_ms,
                e.duration_ms,
                formatDateTime(
                    ifNull(parseDateTime64BestEffortOrNull(toString(e.timestamp)), now64(3)),
                    '%Y-%c-%dT%H:%i:%S.000Z'
                ) as timestamp_iso,
                e.environment,
                e.release,
                e.tags,
                e.contexts,
                e.breadcrumbs,
                e.request
            FROM $clickhouseDb.events e
            WHERE toString(e.event_id) = '$normalizedEventId'
                AND e.event_type = 'transaction'
                AND e.project_id = $projectId
                AND ${timestampRetentionClause("e.timestamp", retentionDays)}
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)

            val body = response.bodyAsText()
            if (response.status.value !in 200..299) {
                logger.error { "Failed to fetch transaction $eventId (status=${response.status}): ${body.take(400)}" }
                return null
            }

            val line = body.lines().firstOrNull { it.isNotBlank() } ?: return null
            if (line.startsWith("Code: ")) {
                logger.error { "Failed to fetch transaction $eventId: ${line.take(400)}" }
                return null
            }

            val obj = json.parseToJsonElement(line).jsonObject
            val endTsMs = obj["end_ts_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
            val durationMs = obj["duration_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
            val contexts = obj["contexts"]?.jsonPrimitive?.content ?: "{}"
            val traceContext = parseTraceContext(contexts)
            val traceId = traceContext?.get("trace_id")?.jsonPrimitive?.contentOrNull ?: ""
            val status = traceContext?.get("status")?.jsonPrimitive?.contentOrNull
            val op = obj["op"]?.jsonPrimitive?.content?.ifBlank {
                traceContext?.get("op")?.jsonPrimitive?.contentOrNull ?: ""
            } ?: ""

            TransactionDetailResponse(
                eventId = obj["event_id"]?.jsonPrimitive?.content ?: normalizedEventId,
                name = obj["name"]?.jsonPrimitive?.content ?: "",
                op = op,
                startTimestamp = ((endTsMs - durationMs).coerceAtLeast(0.0)) / 1000.0,
                duration = durationMs,
                traceId = traceId,
                timestamp = obj["timestamp_iso"]?.jsonPrimitive?.content ?: "",
                environment = obj["environment"]?.jsonPrimitive?.contentOrNull,
                release = obj["release"]?.jsonPrimitive?.contentOrNull,
                status = status,
                tags = parseStringMap(obj["tags"]),
                contexts = contexts,
                breadcrumbs = obj["breadcrumbs"]?.jsonPrimitive?.contentOrNull,
                request = obj["request"]?.jsonPrimitive?.contentOrNull
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch transaction $eventId" }
            null
        }
    }

    suspend fun getTransactionSpans(eventId: String): TransactionWithSpansResponse? {
        val transaction = getTransaction(eventId) ?: return null
        val projectId = getProjectIdForTransaction(transaction.eventId) ?: return null
        val retentionDays = getProjectRetentionDays(projectId)
        val normalizedEventId = normalizeUuid(transaction.eventId) ?: return null
        val query = """
            SELECT
                span_id,
                parent_span_id,
                trace_id,
                toString(transaction_id) as transaction_id,
                op,
                description,
                toUnixTimestamp64Milli(start_timestamp) as start_ts_ms,
                toUnixTimestamp64Milli(end_timestamp) as end_ts_ms,
                duration_ms,
                status,
                tags,
                data
            FROM $clickhouseDb.spans
            WHERE toString(transaction_id) = '$normalizedEventId'
                AND project_id = $projectId
                AND ${timestampRetentionClause("start_timestamp", retentionDays)}
            ORDER BY start_timestamp ASC
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)

            val body = response.bodyAsText()

            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error { "Failed to fetch spans for transaction $eventId: ${response.status} ${body.take(400)}" }
                return null
            }

            val spans = body.lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    SpanResponse(
                        spanId = obj["span_id"]?.jsonPrimitive?.content ?: "",
                        parentSpanId = obj["parent_span_id"]?.jsonPrimitive?.contentOrNull?.ifBlank { null },
                        traceId = obj["trace_id"]?.jsonPrimitive?.contentOrNull,
                        transactionId = obj["transaction_id"]?.jsonPrimitive?.contentOrNull,
                        op = obj["op"]?.jsonPrimitive?.content ?: "",
                        description = obj["description"]?.jsonPrimitive?.content ?: "",
                        startTimestamp = (obj["start_ts_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0) / 1000.0,
                        endTimestamp = (obj["end_ts_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0) / 1000.0,
                        duration = obj["duration_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                        status = obj["status"]?.jsonPrimitive?.contentOrNull,
                        tags = parseStringMap(obj["tags"]),
                        data = obj["data"]?.jsonPrimitive?.contentOrNull
                    )
                }

            val traceContext = parseTraceContext(transaction.contexts)
            val rootSpanId = traceContext?.get("span_id")?.jsonPrimitive?.contentOrNull
                ?: "root-${transaction.eventId.take(8)}"

            val rootSpan = SpanResponse(
                spanId = rootSpanId,
                parentSpanId = null,
                traceId = transaction.traceId.ifBlank { null },
                transactionId = transaction.eventId,
                op = transaction.op,
                description = transaction.name,
                startTimestamp = transaction.startTimestamp,
                endTimestamp = transaction.startTimestamp + (transaction.duration / 1000.0),
                duration = transaction.duration,
                status = transaction.status,
                tags = transaction.tags,
                data = null
            )

            val mergedSpans = if (spans.any { it.spanId == rootSpanId }) spans else listOf(rootSpan) + spans
            TransactionWithSpansResponse(transaction = transaction, spans = mergedSpans)
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch spans for transaction $eventId" }
            null
        }
    }

    suspend fun getTraceDetails(projectId: Long, traceId: String): TraceDetailResponse? {
        val retentionDays = getProjectRetentionDays(projectId)
        val escapedTraceId = escapeSql(traceId)

        val query = """
            SELECT
                span_id,
                parent_span_id,
                trace_id,
                toString(transaction_id) as transaction_id,
                op,
                description,
                toUnixTimestamp64Milli(start_timestamp) as start_ts_ms,
                toUnixTimestamp64Milli(end_timestamp) as end_ts_ms,
                duration_ms,
                status,
                tags,
                data
            FROM $clickhouseDb.spans
            WHERE trace_id = '$escapedTraceId'
                AND project_id = $projectId
                AND ${timestampRetentionClause("start_timestamp", retentionDays)}
            ORDER BY start_timestamp ASC
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()

            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error { "Failed to fetch trace $traceId: ${response.status} ${body.take(400)}" }
                return null
            }

            if (body.isBlank()) {
                return null
            }

            val spans = body.lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    SpanResponse(
                        spanId = obj["span_id"]?.jsonPrimitive?.content ?: "",
                        parentSpanId = obj["parent_span_id"]?.jsonPrimitive?.contentOrNull?.ifBlank { null },
                        traceId = obj["trace_id"]?.jsonPrimitive?.contentOrNull,
                        transactionId = obj["transaction_id"]?.jsonPrimitive?.contentOrNull,
                        op = obj["op"]?.jsonPrimitive?.content ?: "",
                        description = obj["description"]?.jsonPrimitive?.content ?: "",
                        startTimestamp = (obj["start_ts_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0) / 1000.0,
                        endTimestamp = (obj["end_ts_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0) / 1000.0,
                        duration = obj["duration_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                        status = obj["status"]?.jsonPrimitive?.contentOrNull,
                        tags = parseStringMap(obj["tags"]),
                        data = obj["data"]?.jsonPrimitive?.contentOrNull
                    )
                }

            val startTimestamp = spans.minOfOrNull { it.startTimestamp } ?: 0.0
            val endTimestamp = spans.maxOfOrNull { it.endTimestamp } ?: 0.0
            val duration = (endTimestamp - startTimestamp) * 1000.0

            TraceDetailResponse(
                traceId = traceId,
                projectId = projectId,
                spans = spans,
                startTimestamp = startTimestamp,
                endTimestamp = endTimestamp,
                duration = duration
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch trace $traceId" }
            null
        }
    }

    suspend fun getSpanDetails(projectId: Long, spanId: String): SpanDetailResponse? {
        val retentionDays = getProjectRetentionDays(projectId)
        val escapedSpanId = escapeSql(spanId)

        val query = """
            SELECT
                span_id,
                parent_span_id,
                trace_id,
                toString(transaction_id) as transaction_id,
                op,
                description,
                toUnixTimestamp64Milli(start_timestamp) as start_ts_ms,
                toUnixTimestamp64Milli(end_timestamp) as end_ts_ms,
                duration_ms,
                status,
                tags,
                data
            FROM $clickhouseDb.spans
            WHERE span_id = '$escapedSpanId'
                AND project_id = $projectId
                AND ${timestampRetentionClause("start_timestamp", retentionDays)}
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()

            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error { "Failed to fetch span $spanId: ${response.status} ${body.take(400)}" }
                return null
            }

            if (body.isBlank()) {
                return null
            }

            val obj = json.parseToJsonElement(body.trim()).jsonObject
            val span = SpanResponse(
                spanId = obj["span_id"]?.jsonPrimitive?.content ?: "",
                parentSpanId = obj["parent_span_id"]?.jsonPrimitive?.contentOrNull?.ifBlank { null },
                traceId = obj["trace_id"]?.jsonPrimitive?.contentOrNull,
                transactionId = obj["transaction_id"]?.jsonPrimitive?.contentOrNull,
                op = obj["op"]?.jsonPrimitive?.content ?: "",
                description = obj["description"]?.jsonPrimitive?.content ?: "",
                startTimestamp = (obj["start_ts_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0) / 1000.0,
                endTimestamp = (obj["end_ts_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0) / 1000.0,
                duration = obj["duration_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                status = obj["status"]?.jsonPrimitive?.contentOrNull,
                tags = parseStringMap(obj["tags"]),
                data = obj["data"]?.jsonPrimitive?.contentOrNull
            )

            val transaction = span.transactionId?.let { getTransaction(it) }

            SpanDetailResponse(
                span = span,
                transaction = transaction
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch span $spanId" }
            null
        }
    }

    suspend fun getRelatedErrorsForTransaction(eventId: String, limit: Int = 20): List<EventResponse> {
        val transaction = getTransaction(eventId) ?: return emptyList()
        if (transaction.traceId.isBlank()) return emptyList()
        val projectId = getProjectIdForTransaction(eventId) ?: return emptyList()
        val retentionDays = getProjectRetentionDays(projectId)
        val traceId = escapeSql(transaction.traceId)

        val query = """
            SELECT
                toString(event_id) as event_id,
                formatDateTime(timestamp, '%Y-%c-%dT%H:%i:%S.000Z') as timestamp,
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
                stack_trace,
                breadcrumbs
            FROM $clickhouseDb.events
            WHERE project_id = $projectId
                AND event_type = 'error'
                AND positionCaseInsensitive(contexts, '"trace_id":"$traceId"') > 0
                AND ${timestampRetentionClause("timestamp", retentionDays)}
            ORDER BY timestamp DESC
            LIMIT $limit
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            val body = extractClickHouseBody(response) ?: return emptyList()

            body.lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    EventResponse(
                        eventId = obj["event_id"]?.jsonPrimitive?.content ?: "",
                        timestamp = obj["timestamp"]?.jsonPrimitive?.content ?: "",
                        message = obj["message"]?.jsonPrimitive?.content ?: "",
                        platform = obj["platform"]?.jsonPrimitive?.content ?: "",
                        level = obj["level"]?.jsonPrimitive?.content ?: "error",
                        environment = obj["environment"]?.jsonPrimitive?.contentOrNull,
                        release = obj["release"]?.jsonPrimitive?.contentOrNull,
                        user = obj["user_id"]?.jsonPrimitive?.content?.let {
                            UserInfo(
                                id = it,
                                email = obj["user_email"]?.jsonPrimitive?.contentOrNull,
                                username = obj["user_username"]?.jsonPrimitive?.contentOrNull
                            )
                        },
                        tags = parseStringMap(obj["tags"]),
                        contexts = obj["contexts"]?.jsonPrimitive?.content ?: "{}",
                        exception = obj["stack_trace"]?.jsonPrimitive?.contentOrNull,
                        breadcrumbs = obj["breadcrumbs"]?.jsonPrimitive?.contentOrNull
                    )
                }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch related errors for transaction $eventId" }
            emptyList()
        }
    }

    suspend fun getProjectStats(projectId: Long, period: String = "7d", parentSpan: ISpan? = null, demoEpochMs: Long? = null): ProjectStatsResponse =
        CacheService.cached("cache:project_stats:$projectId:$period:${demoEpochMs ?: ""}", 60, parentSpan) {
            val retentionDays = getProjectRetentionDays(projectId)
            val hoursBack = when (period) {
                "24h" -> 24
                "7d" -> 168
                "30d" -> 720
                "90d" -> 2160
                else -> 168
            }

            val intervalMinutes = when (period) {
                "24h" -> 60 // 1 hour buckets
                "7d" -> 360 // 6 hour buckets
                "30d" -> 1440 // 1 day buckets
                "90d" -> 4320 // 3 day buckets
                else -> 360
            }

            val nowSql = demoNowClause(demoEpochMs)

            // Total events in period
            val totalEventsQuery = """
            SELECT count() as total
            FROM $clickhouseDb.events
            WHERE project_id = $projectId
                AND timestamp >= $nowSql - INTERVAL $hoursBack HOUR
                AND ${timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            FORMAT JSONEachRow
            """.trimIndent()

            // Total issues
            val totalIssuesQuery = """
            SELECT count(DISTINCT issue_id) as total
            FROM $clickhouseDb.events
            WHERE project_id = $projectId
                AND event_type = 'error'
                AND ${timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            FORMAT JSONEachRow
            """.trimIndent()

            // Unresolved issues
            val unresolvedIssuesQuery = """
            SELECT count() as total
            FROM (
                SELECT issue_id
                FROM $clickhouseDb.issues FINAL
                WHERE project_id = $projectId
                    AND status = 'unresolved'
                    AND ${timestampRetentionClause("last_seen", retentionDays, demoEpochMs)}
            )
            FORMAT JSONEachRow
            """.trimIndent()

            // Affected users in period
            val affectedUsersQuery = """
            SELECT uniq(user_id) as total
            FROM $clickhouseDb.events
            WHERE project_id = $projectId
                AND timestamp >= $nowSql - INTERVAL $hoursBack HOUR
                AND user_id != ''
                AND ${timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            FORMAT JSONEachRow
            """.trimIndent()

            // Events timeline
            val eventsTimelineQuery = """
            SELECT 
                toStartOfInterval(timestamp, INTERVAL $intervalMinutes MINUTE) as time,
                count() as count
            FROM $clickhouseDb.events
            WHERE project_id = $projectId
                AND timestamp >= $nowSql - INTERVAL $hoursBack HOUR
                AND ${timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            GROUP BY time
            ORDER BY time
            FORMAT JSONEachRow
            """.trimIndent()

            // Events by level
            val eventsByLevelQuery = """
            SELECT 
                level,
                count() as count
            FROM $clickhouseDb.events
            WHERE project_id = $projectId
                AND timestamp >= $nowSql - INTERVAL $hoursBack HOUR
                AND ${timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            GROUP BY level
            FORMAT JSONEachRow
            """.trimIndent()

            // Events by platform
            val eventsByPlatformQuery = """
            SELECT 
                platform,
                count() as count
            FROM $clickhouseDb.events
            WHERE project_id = $projectId
                AND timestamp >= $nowSql - INTERVAL $hoursBack HOUR
                AND platform != ''
                AND ${timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            GROUP BY platform
            ORDER BY count DESC
            LIMIT 10
            FORMAT JSONEachRow
            """.trimIndent()

            // Events by browser
            val eventsByBrowserQuery = """
            SELECT 
                browser_name,
                count() as count
            FROM $clickhouseDb.events
            WHERE project_id = $projectId
                AND timestamp >= $nowSql - INTERVAL $hoursBack HOUR
                AND browser_name != ''
                AND ${timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            GROUP BY browser_name
            ORDER BY count DESC
            LIMIT 10
            FORMAT JSONEachRow
            """.trimIndent()

            // Events by environment
            val eventsByEnvironmentQuery = """
            SELECT 
                environment,
                count() as count
            FROM $clickhouseDb.events
            WHERE project_id = $projectId
                AND timestamp >= $nowSql - INTERVAL $hoursBack HOUR
                AND environment != ''
                AND ${timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            GROUP BY environment
            FORMAT JSONEachRow
            """.trimIndent()

            // Issues by status
            val issuesByStatusQuery = """
            SELECT 
                status,
                count() as count
            FROM $clickhouseDb.issues FINAL
            WHERE project_id = $projectId
                AND ${timestampRetentionClause("last_seen", retentionDays, demoEpochMs)}
            GROUP BY status
            FORMAT JSONEachRow
            """.trimIndent()

            // Top issues
            val topIssuesQuery = """
            SELECT 
                issue_id,
                any(message) as title,
                count() as count
            FROM $clickhouseDb.events
            WHERE project_id = $projectId
                AND timestamp >= $nowSql - INTERVAL $hoursBack HOUR
                AND event_type = 'error'
                AND ${timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            GROUP BY issue_id
            ORDER BY count DESC
            LIMIT 10
            FORMAT JSONEachRow
            """.trimIndent()

            // Users timeline
            val usersTimelineQuery = """
            SELECT 
                toStartOfInterval(timestamp, INTERVAL $intervalMinutes MINUTE) as time,
                uniq(user_id) as count
            FROM $clickhouseDb.events
            WHERE project_id = $projectId
                AND timestamp >= now() - INTERVAL $hoursBack HOUR
                AND user_id != ''
                AND ${timestampRetentionClause("timestamp", retentionDays)}
            GROUP BY time
            ORDER BY time
            FORMAT JSONEachRow
            """.trimIndent()

            try {
                // Execute all queries in parallel
                coroutineScope {
                    val totalEventsDeferred = async { executeScalarQuery(totalEventsQuery, parentSpan) }
                    val totalIssuesDeferred = async { executeScalarQuery(totalIssuesQuery, parentSpan) }
                    val unresolvedIssuesDeferred = async { executeScalarQuery(unresolvedIssuesQuery, parentSpan) }
                    val affectedUsersDeferred = async { executeScalarQuery(affectedUsersQuery, parentSpan) }
                    val eventsTimelineDeferred = async { executeTimelineQuery(eventsTimelineQuery, parentSpan) }
                    val eventsByLevelDeferred = async { executeMapQuery(eventsByLevelQuery, "level", parentSpan) }
                    val eventsByPlatformDeferred = async {
                        executeMapQuery(
                            eventsByPlatformQuery,
                            "platform",
                            parentSpan
                        )
                    }
                    val eventsByBrowserDeferred = async {
                        executeMapQuery(
                            eventsByBrowserQuery,
                            "browser_name",
                            parentSpan
                        )
                    }
                    val eventsByEnvironmentDeferred = async {
                        executeMapQuery(
                            eventsByEnvironmentQuery,
                            "environment",
                            parentSpan
                        )
                    }
                    val issuesByStatusDeferred = async { executeMapQuery(issuesByStatusQuery, "status", parentSpan) }
                    val topIssuesDeferred = async { executeTopIssuesQuery(topIssuesQuery, parentSpan) }
                    val usersTimelineDeferred = async { executeTimelineQuery(usersTimelineQuery, parentSpan) }
                    val releaseMarkersDeferred = async {
                        executeReleaseMarkersQuery(
                            projectId,
                            hoursBack,
                            retentionDays,
                            parentSpan
                        )
                    }

                    // Await all results
                    ProjectStatsResponse(
                        totalEvents = totalEventsDeferred.await(),
                        totalIssues = totalIssuesDeferred.await(),
                        unresolvedIssues = unresolvedIssuesDeferred.await(),
                        affectedUsers = affectedUsersDeferred.await(),
                        eventsTimeline = eventsTimelineDeferred.await(),
                        eventsByLevel = eventsByLevelDeferred.await(),
                        eventsByPlatform = eventsByPlatformDeferred.await(),
                        eventsByBrowser = eventsByBrowserDeferred.await(),
                        eventsByEnvironment = eventsByEnvironmentDeferred.await(),
                        issuesByStatus = issuesByStatusDeferred.await(),
                        topIssues = topIssuesDeferred.await(),
                        usersTimeline = usersTimelineDeferred.await(),
                        releaseMarkers = releaseMarkersDeferred.await()
                    )
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch project stats" }
                ProjectStatsResponse(
                    totalEvents = 0,
                    totalIssues = 0,
                    unresolvedIssues = 0,
                    affectedUsers = 0,
                    eventsTimeline = emptyList(),
                    eventsByLevel = emptyMap(),
                    eventsByPlatform = emptyMap(),
                    eventsByBrowser = emptyMap(),
                    eventsByEnvironment = emptyMap(),
                    issuesByStatus = emptyMap(),
                    topIssues = emptyList(),
                    usersTimeline = emptyList(),
                    releaseMarkers = emptyList()
                )
            }
        }

    suspend fun getReleases(projectId: Long, parentSpan: ISpan? = null): List<ReleaseListResponse> =
        CacheService.cached("cache:releases:$projectId", 120, parentSpan) {
            val retentionDays = getProjectRetentionDays(projectId)
            val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)
            val releasesQuery = """
            SELECT
                release as version,
                formatDateTime(min(timestamp), '%Y-%c-%dT%H:%i:%S.000Z') as first_seen,
                formatDateTime(max(timestamp), '%Y-%c-%dT%H:%i:%S.000Z') as last_seen,
                count() as event_count,
                uniq(user_id) as user_count
            FROM $clickhouseDb.events
            WHERE $projectIdClause AND release != ''
                AND ${timestampRetentionClause("timestamp", retentionDays)}
            GROUP BY release
            ORDER BY first_seen DESC
            FORMAT JSONEachRow
            """.trimIndent()

            try {
                val releases = executeReleasesListQuery(releasesQuery, parentSpan)
                val result = mutableListOf<ReleaseListResponse>()
                for (r in releases) {
                    val newIssueCount = getNewIssueCountForRelease(projectId, r.version, retentionDays)
                    val crashFreeRate = getCrashFreeRateForRelease(projectId, r.version, retentionDays)
                    result.add(
                        ReleaseListResponse(
                            version = r.version,
                            firstSeen = r.firstSeen,
                            lastSeen = r.lastSeen,
                            eventCount = r.eventCount,
                            newIssueCount = newIssueCount,
                            crashFreeRate = crashFreeRate,
                            userCount = r.userCount
                        )
                    )
                }
                result
            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch releases for project $projectId" }
                emptyList()
            }
        }

    suspend fun getReleaseStats(projectId: Long, version: String): ReleaseDetailStats? {
        val retentionDays = getProjectRetentionDays(projectId)
        val escapedVersion = ClickHouseSqlUtils.escapeSql(version)
        val releasesQuery = """
            SELECT
                formatDateTime(min(timestamp), '%Y-%c-%dT%H:%i:%S.000Z') as first_seen,
                formatDateTime(max(timestamp), '%Y-%c-%dT%H:%i:%S.000Z') as last_seen,
                count() as total_events,
                uniq(user_id) as user_count
            FROM $clickhouseDb.events
            WHERE project_id = $projectId AND release = '$escapedVersion'
                AND ${timestampRetentionClause("timestamp", retentionDays)}
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(releasesQuery)
            val body = response.bodyAsText()
            if (body.isBlank()) return null

            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            val firstSeen = obj["first_seen"]?.jsonPrimitive?.content ?: return null
            val lastSeen = obj["last_seen"]?.jsonPrimitive?.content ?: return null
            val totalEvents = obj["total_events"]?.jsonPrimitive?.long ?: 0
            val userCount = obj["user_count"]?.jsonPrimitive?.long ?: 0

            val newIssues = getNewIssueCountForRelease(projectId, version, retentionDays)
            val resolvedIssues = 0L
            val crashFreeSessionRate = getCrashFreeRateForRelease(projectId, version, retentionDays)
            val crashFreeUserRate = crashFreeSessionRate

            val intervalMinutes = 360
            val eventsTimelineQuery = """
                SELECT
                    formatDateTime(toStartOfInterval(timestamp, INTERVAL $intervalMinutes MINUTE), '%Y-%c-%dT%H:%i:%S.000Z') as time,
                    count() as count
                FROM $clickhouseDb.events
                WHERE project_id = $projectId AND release = '$escapedVersion'
                    AND ${timestampRetentionClause("timestamp", retentionDays)}
                GROUP BY time
                ORDER BY time
                FORMAT JSONEachRow
            """.trimIndent()

            val eventsByLevelQuery = """
                SELECT level, count() as count
                FROM $clickhouseDb.events
                WHERE project_id = $projectId AND release = '$escapedVersion'
                    AND ${timestampRetentionClause("timestamp", retentionDays)}
                GROUP BY level
                FORMAT JSONEachRow
            """.trimIndent()

            val topIssuesQuery = """
                SELECT issue_id, any(message) as title, count() as count
                FROM $clickhouseDb.events
                WHERE project_id = $projectId AND release = '$escapedVersion' AND event_type = 'error'
                    AND ${timestampRetentionClause("timestamp", retentionDays)}
                GROUP BY issue_id
                ORDER BY count DESC
                LIMIT 10
                FORMAT JSONEachRow
            """.trimIndent()

            ReleaseDetailStats(
                version = version,
                firstSeen = firstSeen,
                lastSeen = lastSeen,
                totalEvents = totalEvents,
                newIssues = newIssues,
                resolvedIssues = resolvedIssues,
                crashFreeSessionRate = crashFreeSessionRate,
                crashFreeUserRate = crashFreeUserRate,
                userCount = userCount,
                eventsTimeline = executeTimelineQuery(eventsTimelineQuery),
                eventsByLevel = executeMapQuery(eventsByLevelQuery, "level"),
                topIssues = executeTopIssuesQuery(topIssuesQuery)
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch release stats for $version" }
            null
        }
    }

    private suspend fun executeReleaseMarkersQuery(projectId: Long, hoursBack: Int, retentionDays: Int, parentSpan: ISpan? = null): List<ReleaseMarker> {
        val query = """
            SELECT version, formatDateTime(first_seen, '%Y-%c-%dT%H:%i:%S.000Z') as timestamp
            FROM (
                SELECT release as version, min(timestamp) as first_seen
                FROM $clickhouseDb.events
                WHERE project_id = $projectId AND release != ''
                    AND ${timestampRetentionClause("timestamp", retentionDays)}
                GROUP BY release
            )
            WHERE first_seen >= now() - INTERVAL $hoursBack HOUR
            ORDER BY first_seen
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query, parentSpan)
            val body = response.bodyAsText()

            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error { "Failed to execute release markers query: ${response.status} ${body.take(400)}" }
                return emptyList()
            }

            body.lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    ReleaseMarker(
                        version = obj["version"]?.jsonPrimitive?.content ?: "",
                        timestamp = obj["timestamp"]?.jsonPrimitive?.content ?: ""
                    )
                }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch release markers" }
            emptyList()
        }
    }

    private data class ReleaseListRow(
        val version: String,
        val firstSeen: String,
        val lastSeen: String,
        val eventCount: Long,
        val userCount: Long
    )

    private suspend fun executeReleasesListQuery(query: String, parentSpan: ISpan? = null): List<ReleaseListRow> {
        val response = ClickHouseClient.execute(query, parentSpan)
        val body = response.bodyAsText()

        if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
            logger.error { "Failed to execute releases list query: ${response.status} ${body.take(400)}" }
            return emptyList()
        }

        return body.lines()
            .filter { it.isNotBlank() }
            .map { line ->
                val obj = json.parseToJsonElement(line).jsonObject
                ReleaseListRow(
                    version = obj["version"]?.jsonPrimitive?.content ?: "",
                    firstSeen = obj["first_seen"]?.jsonPrimitive?.content ?: "",
                    lastSeen = obj["last_seen"]?.jsonPrimitive?.content ?: "",
                    eventCount = obj["event_count"]?.jsonPrimitive?.long ?: 0,
                    userCount = obj["user_count"]?.jsonPrimitive?.long ?: 0
                )
            }
    }

    private suspend fun getNewIssueCountForRelease(projectId: Long, version: String, retentionDays: Int): Long {
        val escapedVersion = ClickHouseSqlUtils.escapeSql(version)
        val query = """
            SELECT count() as total FROM (
                SELECT issue_id, argMin(release, timestamp) as first_release
                FROM $clickhouseDb.events
                WHERE project_id = $projectId AND event_type = 'error' AND issue_id != ''
                    AND ${timestampRetentionClause("timestamp", retentionDays)}
                GROUP BY issue_id
                HAVING first_release = '$escapedVersion'
            )
            FORMAT JSONEachRow
        """.trimIndent()
        return executeScalarQuery(query)
    }

    private suspend fun getCrashFreeRateForRelease(projectId: Long, version: String, retentionDays: Int): Double? {
        val escapedVersion = ClickHouseSqlUtils.escapeSql(version)
        val query = """
            SELECT countIf(errors = 0) * 100.0 / count() as rate
            FROM $clickhouseDb.sessions
            WHERE project_id = $projectId AND release = '$escapedVersion'
                AND ${timestampRetentionClause("started", retentionDays)}
            FORMAT JSONEachRow
        """.trimIndent()
        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (body.isBlank()) return null
            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            val rate = obj["rate"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
            if (rate.isNaN() || rate.isInfinite()) null else rate
        } catch (e: Exception) {
            null
        }
    }

    private data class PeriodConfig(
        val hoursBack: Int,
        val intervalMinutes: Int,
        val periodMinutes: Int
    )

    private fun getPeriodConfig(period: String): PeriodConfig {
        return when (period) {
            "24h" -> PeriodConfig(hoursBack = 24, intervalMinutes = 60, periodMinutes = 24 * 60)
            "30d" -> PeriodConfig(hoursBack = 720, intervalMinutes = 1440, periodMinutes = 30 * 24 * 60)
            "90d" -> PeriodConfig(hoursBack = 2160, intervalMinutes = 4320, periodMinutes = 90 * 24 * 60)
            else -> PeriodConfig(hoursBack = 168, intervalMinutes = 360, periodMinutes = 7 * 24 * 60)
        }
    }

    private fun buildTransactionFilterClause(environment: String?, operation: String?): String {
        val conditions = mutableListOf<String>()
        environment?.takeIf { it.isNotBlank() }?.let {
            conditions.add("environment = '${escapeSql(it)}'")
        }
        operation?.takeIf { it.isNotBlank() }?.let {
            conditions.add("transaction_op = '${escapeSql(it)}'")
        }

        return if (conditions.isEmpty()) {
            ""
        } else {
            conditions.joinToString(
                separator = "\n                ",
                prefix = "AND "
            )
        }
    }

    private fun parseStringMap(element: JsonElement?): HashMap<String, String> {
        val objectValue = element as? JsonObject ?: return hashMapOf()
        return HashMap(
            objectValue.entries.associate { (key, value) ->
                key to (value.jsonPrimitive.contentOrNull ?: "")
            }
        )
    }

    private fun parseTraceContext(contexts: String): JsonObject? {
        return try {
            val contextsJson = json.parseToJsonElement(contexts) as? JsonObject ?: return null
            contextsJson["trace"] as? JsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeUuid(value: String): String? {
        val trimmed = value.trim().lowercase()
        val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        if (uuidRegex.matches(trimmed)) return trimmed

        val hexRegex = Regex("^[0-9a-f]{32}$")
        if (hexRegex.matches(trimmed)) {
            return "${trimmed.substring(
                0,
                8
            )}-${trimmed.substring(
                8,
                12
            )}-${trimmed.substring(12, 16)}-${trimmed.substring(16, 20)}-${trimmed.substring(20)}"
        }

        return null
    }

    private fun escapeSql(value: String): String {
        return ClickHouseSqlUtils.escapeSql(value)
    }

    private suspend fun getProjectRetentionDays(projectId: Long): Int {
        return retentionPolicyService.getRetentionDaysForProject(projectId) ?: PricingTier.FREE.retentionDays
    }

    private fun timestampRetentionClause(column: String, retentionDays: Int, demoEpochMs: Long? = null): String {
        val nowClause = demoNowClause(demoEpochMs)
        return "$column >= $nowClause - INTERVAL $retentionDays DAY"
    }

    private fun demoNowClause(demoEpochMs: Long? = null): String {
        return if (demoEpochMs != null) {
            "toDateTime64(${demoEpochMs / 1000.0}, 3)"
        } else {
            "now()"
        }
    }

    private suspend fun executeScalarQuery(query: String, parentSpan: ISpan? = null): Long {
        val response = ClickHouseClient.execute(query, parentSpan)
        val body = response.bodyAsText()
        if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
            logger.error { "Failed to execute scalar query: ${response.status} ${body.take(400)}" }
            return 0
        }
        if (body.isBlank()) return 0
        val obj = json.parseToJsonElement(body.lines().first()).jsonObject
        return obj["total"]?.jsonPrimitive?.long ?: 0
    }

    private suspend fun executeTimelineQuery(query: String, parentSpan: ISpan? = null): List<TimelinePoint> {
        val response = ClickHouseClient.execute(query, parentSpan)
        val body = response.bodyAsText()

        if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
            logger.error { "ClickHouse query failed: ${body.take(400)}" }
            return emptyList()
        }

        return body.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    val obj = json.parseToJsonElement(line).jsonObject
                    TimelinePoint(
                        timestamp = obj["time"]?.jsonPrimitive?.content ?: "",
                        count = obj["count"]?.jsonPrimitive?.long ?: 0
                    )
                } catch (e: Exception) {
                    logger.error(e) { "Failed to parse line: $line" }
                    null
                }
            }
    }

    private suspend fun executeSlowestTransactionsQuery(query: String, parentSpan: ISpan? = null): List<SlowTransactionResponse> {
        val response = ClickHouseClient.execute(query, parentSpan)
        val body = response.bodyAsText()

        if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
            logger.error { "ClickHouse query failed: ${body.take(400)}" }
            return emptyList()
        }

        return body.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    val obj = json.parseToJsonElement(line).jsonObject
                    SlowTransactionResponse(
                        eventId = obj["event_id"]?.jsonPrimitive?.content ?: "",
                        name = obj["name"]?.jsonPrimitive?.content ?: "",
                        op = obj["op"]?.jsonPrimitive?.content ?: "",
                        duration = obj["duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                        timestamp = obj["timestamp_iso"]?.jsonPrimitive?.content
                            ?: obj["timestamp"]?.jsonPrimitive?.content
                            ?: ""
                    )
                } catch (e: Exception) {
                    logger.error(e) { "Failed to parse line: $line" }
                    null
                }
            }
    }

    private suspend fun executeMapQuery(query: String, keyField: String, parentSpan: ISpan? = null): Map<String, Long> {
        val response = ClickHouseClient.execute(query, parentSpan)
        val body = response.bodyAsText()

        if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
            logger.error { "Failed to execute map query: ${response.status} ${body.take(400)}" }
            return emptyMap()
        }

        return body.lines()
            .filter { it.isNotBlank() }
            .associate { line ->
                val obj = json.parseToJsonElement(line).jsonObject
                val key = obj[keyField]?.jsonPrimitive?.content ?: "unknown"
                val count = obj["count"]?.jsonPrimitive?.long ?: 0
                key to count
            }
    }

    private suspend fun executeTopIssuesQuery(query: String, parentSpan: ISpan? = null): List<TopIssue> {
        val response = ClickHouseClient.execute(query, parentSpan)
        val body = response.bodyAsText()

        if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
            logger.error { "Failed to execute top issues query: ${response.status} ${body.take(400)}" }
            return emptyList()
        }

        return body.lines()
            .filter { it.isNotBlank() }
            .map { line ->
                val obj = json.parseToJsonElement(line).jsonObject
                TopIssue(
                    issueId = obj["issue_id"]?.jsonPrimitive?.content ?: "",
                    title = obj["title"]?.jsonPrimitive?.content ?: "",
                    count = obj["count"]?.jsonPrimitive?.long ?: 0
                )
            }
    }

    suspend fun getReplays(
        projectId: Long,
        page: Int = 1,
        limit: Int = 25,
        environment: String? = null,
        period: String = "7d",
        demoEpochMs: Long? = null
    ): List<ReplayListItem> {
        val offset = (page - 1) * limit
        val retentionDays = getProjectRetentionDays(projectId)
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)

        val nowMs = demoEpochMs ?: System.currentTimeMillis()
        val periodMs = when (period) {
            "24h" -> 24 * 60 * 60 * 1000L
            "30d" -> 30 * 24 * 60 * 60 * 1000L
            "90d" -> 90 * 24 * 60 * 60 * 1000L
            else -> 7 * 24 * 60 * 60 * 1000L
        }
        val periodStartMs = nowMs - periodMs
        val retentionStartMs = nowMs - (retentionDays * 24 * 60 * 60 * 1000L)

        val envClause = if (environment != null && environment.isNotBlank()) {
            "AND environment = '${environment.replace("'", "''")}'"
        } else {
            ""
        }

        val query = """
            SELECT
                toString(replay_id) as replay_id,
                toInt64(project_id) as project_id,
                formatDateTime(min(replay_start_timestamp), '%Y-%m-%dT%H:%i:%S.000Z') as started_at,
                formatDateTime(max(timestamp), '%Y-%m-%dT%H:%i:%S.000Z') as finished_at,
                toUnixTimestamp64Milli(min(replay_start_timestamp)) as started_ms,
                toUnixTimestamp64Milli(max(timestamp)) as finished_ms,
                dateDiff('millisecond', min(replay_start_timestamp), max(timestamp)) as duration_ms,
                arrayFlatten(groupArray(urls)) as urls,
                length(arrayDistinct(arrayFlatten(groupArray(error_ids)))) as error_count,
                argMax(user_id, timestamp) as user_id,
                argMax(user_email, timestamp) as user_email,
                argMax(user_username, timestamp) as user_username,
                argMax(browser_name, timestamp) as browser_name,
                argMax(browser_version, timestamp) as browser_version,
                argMax(os_name, timestamp) as os_name,
                argMax(os_version, timestamp) as os_version,
                argMax(activity, timestamp) as activity
            FROM $clickhouseDb.replay_events
            WHERE $projectIdClause
                AND replay_start_timestamp >= fromUnixTimestamp64Milli($periodStartMs)
                AND timestamp >= fromUnixTimestamp64Milli($retentionStartMs)
                $envClause
            GROUP BY replay_id, project_id
            ORDER BY max(timestamp) DESC
            LIMIT $limit OFFSET $offset
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)

            val body = response.bodyAsText()
            if (response.status.value !in 200..299) return emptyList()

            body.lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try {
                        val obj = json.parseToJsonElement(line).jsonObject
                        val userId = obj["user_id"]?.jsonPrimitive?.contentOrNull
                        val userEmail = obj["user_email"]?.jsonPrimitive?.contentOrNull
                        val userUsername = obj["user_username"]?.jsonPrimitive?.contentOrNull
                        val startedMs = obj["started_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        val finishedMs = obj["finished_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        val rawErrorCount = obj["error_count"]?.jsonPrimitive?.intOrNull ?: 0
                        val fallbackErrorCount = if (rawErrorCount == 0 && startedMs != null && finishedMs != null) {
                            getReplayWindowErrorCount(projectId, startedMs, finishedMs, userId, retentionDays)
                        } else {
                            0
                        }
                        ReplayListItem(
                            replayId = obj["replay_id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                            projectId = obj["project_id"]?.jsonPrimitive?.long ?: projectId,
                            startedAt = obj["started_at"]?.jsonPrimitive?.content ?: "",
                            finishedAt = obj["finished_at"]?.jsonPrimitive?.content ?: "",
                            durationMs = obj["duration_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                            urls = parseStringArray(obj["urls"]),
                            errorCount = maxOf(rawErrorCount, fallbackErrorCount),
                            user = if (userId != null || userEmail != null || userUsername != null) {
                                UserInfo(id = userId, email = userEmail, username = userUsername, ip_address = null)
                            } else {
                                null
                            },
                            browserName = obj["browser_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                            browserVersion = obj["browser_version"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                            osName = obj["os_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                            osVersion = obj["os_version"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                            activity = obj["activity"]?.jsonPrimitive?.intOrNull ?: 0
                        )
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to parse replay list row" }
                        null
                    }
                }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch replays for project $projectId" }
            emptyList()
        }
    }

    private fun parseStringArray(element: JsonElement?): List<String> {
        val arr = element?.jsonArray ?: return emptyList()
        return arr.mapNotNull { it.jsonPrimitive.contentOrNull }
    }

    private suspend fun getReplayWindowErrorCount(
        projectId: Long,
        startMs: Long,
        endMs: Long,
        userId: String?,
        retentionDays: Int
    ): Int {
        if (endMs < startMs) return 0
        val userClause = userId?.takeIf { it.isNotBlank() }?.let { "AND user_id = '${escapeSql(it)}'" } ?: ""
        val query = """
            SELECT countDistinct(event_id) as count
            FROM $clickhouseDb.events
            WHERE project_id = $projectId
                AND event_type = 'error'
                AND timestamp >= fromUnixTimestamp64Milli($startMs)
                AND timestamp <= fromUnixTimestamp64Milli($endMs)
                AND ${timestampRetentionClause("timestamp", retentionDays)}
                $userClause
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            if (response.status.value !in 200..299) return 0
            val line = response.bodyAsText().lines().firstOrNull { it.isNotBlank() } ?: return 0
            val obj = json.parseToJsonElement(line).jsonObject
            obj["count"]?.jsonPrimitive?.intOrNull ?: 0
        } catch (e: Exception) {
            logger.error(e) { "Failed to count replay window errors for project $projectId" }
            0
        }
    }

    private suspend fun getReplayWindowErrorIds(
        projectId: Long,
        startMs: Long,
        endMs: Long,
        userId: String?,
        retentionDays: Int,
        limit: Int = 200
    ): List<String> {
        if (endMs < startMs) return emptyList()
        val userClause = userId?.takeIf { it.isNotBlank() }?.let { "AND user_id = '${escapeSql(it)}'" } ?: ""
        val query = """
            SELECT toString(event_id) as event_id
            FROM $clickhouseDb.events
            WHERE project_id = $projectId
                AND event_type = 'error'
                AND timestamp >= fromUnixTimestamp64Milli($startMs)
                AND timestamp <= fromUnixTimestamp64Milli($endMs)
                AND ${timestampRetentionClause("timestamp", retentionDays)}
                $userClause
            ORDER BY timestamp ASC
            LIMIT $limit
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            if (response.status.value !in 200..299) return emptyList()
            response.bodyAsText()
                .lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching {
                        json.parseToJsonElement(
                            line
                        ).jsonObject["event_id"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull()
                }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch replay window error IDs for project $projectId" }
            emptyList()
        }
    }

    suspend fun getReplay(replayId: String, demoEpochMs: Long? = null): ReplayDetailResponse? {
        val normalizedReplayId = normalizeUuid(replayId) ?: return null
        val projectId = getProjectIdForReplay(normalizedReplayId) ?: return null
        val retentionDays = getProjectRetentionDays(projectId)
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)

        val query = """
            SELECT
                toString(replay_id) as replay_id,
                toInt64(project_id) as project_id,
                formatDateTime(min(replay_start_timestamp), '%Y-%m-%dT%H:%i:%S.000Z') as started_at,
                formatDateTime(max(timestamp), '%Y-%m-%dT%H:%i:%S.000Z') as finished_at,
                toUnixTimestamp64Milli(min(replay_start_timestamp)) as started_ms,
                toUnixTimestamp64Milli(max(timestamp)) as finished_ms,
                dateDiff('millisecond', min(replay_start_timestamp), max(timestamp)) as duration_ms,
                arrayFlatten(groupArray(urls)) as urls,
                arrayFlatten(groupArray(error_ids)) as error_ids,
                arrayFlatten(groupArray(trace_ids)) as trace_ids,
                count() as segment_count,
                argMax(environment, timestamp) as environment,
                argMax(release, timestamp) as release,
                argMax(platform, timestamp) as platform,
                argMax(user_id, timestamp) as user_id,
                argMax(user_email, timestamp) as user_email,
                argMax(user_username, timestamp) as user_username,
                argMax(browser_name, timestamp) as browser_name,
                argMax(browser_version, timestamp) as browser_version,
                argMax(os_name, timestamp) as os_name,
                argMax(os_version, timestamp) as os_version,
                argMax(activity, timestamp) as activity,
                argMax(tags, timestamp) as tags
            FROM $clickhouseDb.replay_events
            WHERE toString(replay_id) = '$normalizedReplayId'
                AND $projectIdClause
                AND ${timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            GROUP BY replay_id, project_id
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)

            val body = response.bodyAsText()
            val line = body.lines().firstOrNull { it.isNotBlank() } ?: return null

            val obj = json.parseToJsonElement(line).jsonObject
            val userId = obj["user_id"]?.jsonPrimitive?.contentOrNull
            val userEmail = obj["user_email"]?.jsonPrimitive?.contentOrNull
            val userUsername = obj["user_username"]?.jsonPrimitive?.contentOrNull
            val startedMs = obj["started_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            val finishedMs = obj["finished_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            val tagsStr = obj["tags"]?.jsonPrimitive?.contentOrNull ?: "{}"
            val tagsMap = try {
                val tagsObj = json.parseToJsonElement(tagsStr) as? JsonObject ?: return null
                tagsObj.mapValues { it.value.jsonPrimitive.content }
            } catch (_: Exception) { emptyMap<String, String>() }
            val replayErrorIds = parseStringArray(obj["error_ids"]).distinct()
            val fallbackErrorIds = if (replayErrorIds.isEmpty() && startedMs != null && finishedMs != null) {
                getReplayWindowErrorIds(
                    projectId = obj["project_id"]?.jsonPrimitive?.long ?: return null,
                    startMs = startedMs,
                    endMs = finishedMs,
                    userId = userId,
                    retentionDays = retentionDays
                )
            } else {
                emptyList()
            }
            val mergedErrorIds = (replayErrorIds + fallbackErrorIds).distinct()
            val fallbackErrorCount = if (replayErrorIds.isEmpty() && startedMs != null && finishedMs != null) {
                getReplayWindowErrorCount(
                    projectId = obj["project_id"]?.jsonPrimitive?.long ?: return null,
                    startMs = startedMs,
                    endMs = finishedMs,
                    userId = userId,
                    retentionDays = retentionDays
                )
            } else {
                0
            }

            ReplayDetailResponse(
                replayId = obj["replay_id"]?.jsonPrimitive?.content ?: return null,
                projectId = obj["project_id"]?.jsonPrimitive?.long ?: return null,
                startedAt = obj["started_at"]?.jsonPrimitive?.content ?: "",
                finishedAt = obj["finished_at"]?.jsonPrimitive?.content ?: "",
                durationMs = obj["duration_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                urls = parseStringArray(obj["urls"]),
                errorCount = maxOf(mergedErrorIds.size, fallbackErrorCount),
                errorIds = mergedErrorIds,
                traceIds = parseStringArray(obj["trace_ids"]),
                segmentCount = obj["segment_count"]?.jsonPrimitive?.intOrNull ?: 0,
                environment = obj["environment"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                release = obj["release"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                platform = obj["platform"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                user = if (userId != null || userEmail != null || userUsername != null) {
                    UserInfo(id = userId, email = userEmail, username = userUsername, ip_address = null)
                } else {
                    null
                },
                browserName = obj["browser_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                browserVersion = obj["browser_version"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                osName = obj["os_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                osVersion = obj["os_version"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                activity = obj["activity"]?.jsonPrimitive?.intOrNull ?: 0,
                tags = tagsMap
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch replay $replayId" }
            null
        }
    }

    suspend fun getReplayTimeline(replayId: String, demoEpochMs: Long? = null): ReplayTimelineResponse {
        val replay = getReplay(replayId, demoEpochMs) ?: return ReplayTimelineResponse(emptyList(), 0L)
        val replayStartMs = try {
            Instant.parse(replay.startedAt).toEpochMilli()
        } catch (_: Exception) {
            return ReplayTimelineResponse(emptyList(), 0L)
        }
        val replayEndMs = try {
            Instant.parse(replay.finishedAt).toEpochMilli()
        } catch (_: Exception) {
            replayStartMs + 86400_000L
        }
        val projectId = replay.projectId
        val retentionDays = getProjectRetentionDays(projectId)
        val items = mutableListOf<ReplayTimelineItem>()
        val addedIds = mutableSetOf<String>()
        val userClause = replay.user?.id?.takeIf { it.isNotBlank() }?.let { "AND user_id = '${escapeSql(it)}'" } ?: ""

        // Fetch errors by errorIds
        if (replay.errorIds.isNotEmpty()) {
            val errorIdList = replay.errorIds.mapNotNull { normalizeUuid(it) }.distinct()
            if (errorIdList.isNotEmpty()) {
                val inClause = errorIdList.joinToString(",") { "'${escapeSql(it)}'" }
                val query = """
                    SELECT
                        toString(e.event_id) as event_id,
                        formatDateTime(e.timestamp, '%Y-%m-%dT%H:%i:%S.000Z') as timestamp,
                        toUnixTimestamp64Milli(e.timestamp) as ts_ms,
                        message,
                        level,
                        issue_id,
                        exception_type,
                        exception_value
                    FROM $clickhouseDb.events e
                    WHERE e.project_id = $projectId
                        AND e.event_type = 'error'
                        AND toString(e.event_id) IN ($inClause)
                        AND ${timestampRetentionClause("e.timestamp", retentionDays, demoEpochMs)}
                    FORMAT JSONEachRow
                """.trimIndent()
                runCatching {
                    val response = ClickHouseClient.execute(query)
                    val body = response.bodyAsText()
                    if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                        if (response.status.value !in 200..299) {
                            logger.error {
                                "Replay timeline errors by IDs failed: ${response.status} ${body.take(
                                    400
                                )}"
                            }
                        } else {
                            logger.error { "Replay timeline errors by IDs (ClickHouse): ${body.take(400)}" }
                        }
                        return@runCatching
                    }
                    body.lines()
                        .filter { it.isNotBlank() }
                        .filter { !it.trimStart().startsWith("Code:") }
                        .forEach { line ->
                            val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
                            val tsMs = obj["ts_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@forEach
                            val exceptionType = obj["exception_type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                            val message = obj["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                            val title = exceptionType ?: message ?: "Error"
                            val eventId = obj["event_id"]?.jsonPrimitive?.content ?: return@forEach
                            if (!addedIds.add(eventId)) return@forEach
                            items.add(
                                ReplayTimelineItem(
                                    id = eventId,
                                    type = "error",
                                    timestamp = obj["timestamp"]?.jsonPrimitive?.content ?: "",
                                    offsetMs = (tsMs - replayStartMs).toDouble(),
                                    title = title,
                                    description = obj["exception_value"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                                        ?: obj["message"]?.jsonPrimitive?.contentOrNull,
                                    durationMs = null,
                                    category = obj["level"]?.jsonPrimitive?.contentOrNull,
                                    eventId = eventId,
                                    issueId = obj["issue_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                                    traceId = null
                                )
                            )
                        }
                }.onFailure { logger.error(it) { "Failed to fetch replay timeline errors" } }
            }
        }

        // Fetch transactions by traceIds
        if (replay.traceIds.isNotEmpty()) {
            val traceConditions = replay.traceIds.distinct().map { traceId ->
                val escaped = escapeSql(traceId)
                "positionCaseInsensitive(e.contexts, '\"trace_id\":\"$escaped\"') > 0"
            }.joinToString(" OR ")
            val query = """
                SELECT
                    toString(e.event_id) as event_id,
                    formatDateTime(e.timestamp, '%Y-%m-%dT%H:%i:%S.000Z') as timestamp,
                    toUnixTimestamp64Milli(e.timestamp) as ts_ms,
                    transaction_name,
                    duration_ms,
                    transaction_op,
                    contexts
                FROM $clickhouseDb.events e
                WHERE e.project_id = $projectId
                    AND e.event_type = 'transaction'
                    AND ($traceConditions)
                    AND ${timestampRetentionClause("e.timestamp", retentionDays, demoEpochMs)}
                FORMAT JSONEachRow
            """.trimIndent()
            runCatching {
                val response = ClickHouseClient.execute(query)
                val body = response.bodyAsText()
                if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                    if (response.status.value !in 200..299) {
                        logger.error {
                            "Replay timeline transactions by trace IDs failed: ${response.status} ${body.take(
                                400
                            )}"
                        }
                    } else {
                        logger.error { "Replay timeline transactions by trace IDs (ClickHouse): ${body.take(400)}" }
                    }
                    return@runCatching
                }
                body.lines()
                    .filter { it.isNotBlank() }
                    .filter { !it.trimStart().startsWith("Code:") }
                    .forEach { line ->
                        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
                        val tsMs = obj["ts_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@forEach
                        val eventId = obj["event_id"]?.jsonPrimitive?.content ?: return@forEach
                        if (!addedIds.add(eventId)) return@forEach
                        val traceId = parseTraceContext(
                            obj["contexts"]?.jsonPrimitive?.content ?: "{}"
                        )?.get("trace_id")?.jsonPrimitive?.contentOrNull
                        items.add(
                            ReplayTimelineItem(
                                id = eventId,
                                type = "transaction",
                                timestamp = obj["timestamp"]?.jsonPrimitive?.content ?: "",
                                offsetMs = (tsMs - replayStartMs).toDouble(),
                                title = obj["transaction_name"]?.jsonPrimitive?.content ?: "Transaction",
                                description = null,
                                durationMs = obj["duration_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                                category = obj["transaction_op"]?.jsonPrimitive?.contentOrNull,
                                eventId = eventId,
                                issueId = null,
                                traceId = traceId
                            )
                        )
                    }
            }.onFailure { logger.error(it) { "Failed to fetch replay timeline transactions" } }
        }

        // Fetch spans by traceIds
        if (replay.traceIds.isNotEmpty()) {
            val traceIdList = replay.traceIds.distinct().map { "'${escapeSql(it)}'" }.joinToString(",")
            val query = """
                SELECT
                    span_id,
                    trace_id,
                    toString(transaction_id) as transaction_id,
                    description,
                    op,
                    toUnixTimestamp64Milli(start_timestamp) as start_ts_ms,
                    duration_ms
                FROM $clickhouseDb.spans
                WHERE project_id = $projectId
                    AND trace_id IN ($traceIdList)
                    AND ${timestampRetentionClause("start_timestamp", retentionDays, demoEpochMs)}
                FORMAT JSONEachRow
            """.trimIndent()
            runCatching {
                val response = ClickHouseClient.execute(query)
                val body = response.bodyAsText()
                if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                    if (response.status.value !in 200..299) {
                        logger.error {
                            "Replay timeline spans by trace IDs failed: ${response.status} ${body.take(
                                400
                            )}"
                        }
                    } else {
                        logger.error { "Replay timeline spans by trace IDs (ClickHouse): ${body.take(400)}" }
                    }
                    return@runCatching
                }
                body.lines()
                    .filter { it.isNotBlank() }
                    .filter { !it.trimStart().startsWith("Code:") }
                    .forEach { line ->
                        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
                        val startTsMs = obj["start_ts_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@forEach
                        val spanId = obj["span_id"]?.jsonPrimitive?.content ?: return@forEach
                        val traceId = obj["trace_id"]?.jsonPrimitive?.contentOrNull
                        val spanItemId = "span-$traceId-$spanId"
                        if (!addedIds.add(spanItemId)) return@forEach
                        val spanTimestampIso = Instant.ofEpochMilli(startTsMs).toString()
                        items.add(
                            ReplayTimelineItem(
                                id = spanItemId,
                                type = "span",
                                timestamp = spanTimestampIso,
                                offsetMs = (startTsMs - replayStartMs).toDouble(),
                                title = obj["description"]?.jsonPrimitive?.content?.takeIf {
                                    it.isNotBlank()
                                } ?: obj["op"]?.jsonPrimitive?.content ?: "Span",
                                description = obj["op"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                                durationMs = obj["duration_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                                category = obj["op"]?.jsonPrimitive?.contentOrNull,
                                eventId = obj["transaction_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                                issueId = null,
                                traceId = traceId
                            )
                        )
                    }
            }.onFailure { logger.error(it) { "Failed to fetch replay timeline spans" } }
        }

        // Link by time range: include errors/transactions/spans that occurred during the replay
        // when client did not send error_ids/trace_ids (or to supplement)
        val errorsInRangeQuery = """
            SELECT
                toString(event_id) as event_id,
                formatDateTime(ts_col, '%Y-%m-%dT%H:%i:%S.000Z') as timestamp,
                toUnixTimestamp64Milli(ts_col) as ts_ms,
                message,
                level,
                issue_id,
                exception_type,
                exception_value
            FROM (SELECT *, timestamp as ts_col FROM $clickhouseDb.events WHERE project_id = $projectId AND event_type = 'error' $userClause)
            WHERE ts_col >= fromUnixTimestamp64Milli($replayStartMs)
                AND ts_col <= fromUnixTimestamp64Milli($replayEndMs)
                AND ${timestampRetentionClause("ts_col", retentionDays)}
            ORDER BY ts_col ASC
            LIMIT 100
            FORMAT JSONEachRow
        """.trimIndent()
        runCatching {
            val response = ClickHouseClient.execute(errorsInRangeQuery)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                if (response.status.value !in 200..299) {
                    logger.error { "Replay timeline errors by time range failed: ${response.status} ${body.take(400)}" }
                } else {
                    logger.error { "Replay timeline errors by time range (ClickHouse): ${body.take(400)}" }
                }
                return@runCatching
            }
            body.lines()
                .filter { it.isNotBlank() }
                .filter { !it.trimStart().startsWith("Code:") }
                .forEach { line ->
                    val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
                    val tsMs = obj["ts_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@forEach
                    val exceptionType = obj["exception_type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    val message = obj["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    val title = exceptionType ?: message ?: "Error"
                    val eventId = obj["event_id"]?.jsonPrimitive?.content ?: return@forEach
                    if (!addedIds.add(eventId)) return@forEach
                    items.add(
                        ReplayTimelineItem(
                            id = eventId,
                            type = "error",
                            timestamp = obj["timestamp"]?.jsonPrimitive?.content ?: "",
                            offsetMs = (tsMs - replayStartMs).toDouble(),
                            title = title,
                            description = obj["exception_value"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                                ?: obj["message"]?.jsonPrimitive?.contentOrNull,
                            durationMs = null,
                            category = obj["level"]?.jsonPrimitive?.contentOrNull,
                            eventId = eventId,
                            issueId = obj["issue_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                            traceId = null
                        )
                    )
                }
        }.onFailure { logger.error(it) { "Failed to fetch replay timeline errors by time range" } }

        val transactionsInRangeQuery = """
            SELECT
                toString(event_id) as event_id,
                formatDateTime(ts_col, '%Y-%m-%dT%H:%i:%S.000Z') as timestamp,
                toUnixTimestamp64Milli(ts_col) as ts_ms,
                transaction_name,
                duration_ms,
                transaction_op,
                contexts
            FROM (SELECT *, timestamp as ts_col FROM $clickhouseDb.events WHERE project_id = $projectId AND event_type = 'transaction' $userClause)
            WHERE ts_col >= fromUnixTimestamp64Milli($replayStartMs)
                AND ts_col <= fromUnixTimestamp64Milli($replayEndMs)
                AND ${timestampRetentionClause("ts_col", retentionDays)}
            ORDER BY ts_col ASC
            LIMIT 100
            FORMAT JSONEachRow
        """.trimIndent()
        runCatching {
            val response = ClickHouseClient.execute(transactionsInRangeQuery)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                if (response.status.value !in 200..299) {
                    logger.error {
                        "Replay timeline transactions by time range failed: ${response.status} ${body.take(
                            400
                        )}"
                    }
                } else {
                    logger.error { "Replay timeline transactions by time range (ClickHouse): ${body.take(400)}" }
                }
                return@runCatching
            }
            body.lines()
                .filter { it.isNotBlank() }
                .filter { !it.trimStart().startsWith("Code:") }
                .forEach { line ->
                    val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
                    val tsMs = obj["ts_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@forEach
                    val eventId = obj["event_id"]?.jsonPrimitive?.content ?: return@forEach
                    if (!addedIds.add(eventId)) return@forEach
                    val traceId = parseTraceContext(
                        obj["contexts"]?.jsonPrimitive?.content ?: "{}"
                    )?.get("trace_id")?.jsonPrimitive?.contentOrNull
                    items.add(
                        ReplayTimelineItem(
                            id = eventId,
                            type = "transaction",
                            timestamp = obj["timestamp"]?.jsonPrimitive?.content ?: "",
                            offsetMs = (tsMs - replayStartMs).toDouble(),
                            title = obj["transaction_name"]?.jsonPrimitive?.content ?: "Transaction",
                            description = null,
                            durationMs = obj["duration_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                            category = obj["transaction_op"]?.jsonPrimitive?.contentOrNull,
                            eventId = eventId,
                            issueId = null,
                            traceId = traceId
                        )
                    )
                }
        }.onFailure { logger.error(it) { "Failed to fetch replay timeline transactions by time range" } }

        val spansInRangeQuery = """
            SELECT
                span_id,
                trace_id,
                toString(transaction_id) as transaction_id,
                description,
                op,
                toUnixTimestamp64Milli(start_timestamp) as start_ts_ms,
                duration_ms
            FROM $clickhouseDb.spans
            WHERE project_id = $projectId
                AND start_timestamp >= fromUnixTimestamp64Milli($replayStartMs)
                AND start_timestamp <= fromUnixTimestamp64Milli($replayEndMs)
                AND ${timestampRetentionClause("start_timestamp", retentionDays)}
            ORDER BY start_timestamp ASC
            LIMIT 200
            FORMAT JSONEachRow
        """.trimIndent()
        runCatching {
            val response = ClickHouseClient.execute(spansInRangeQuery)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                if (response.status.value !in 200..299) {
                    logger.error { "Replay timeline spans by time range failed: ${response.status} ${body.take(400)}" }
                } else {
                    logger.error { "Replay timeline spans by time range (ClickHouse): ${body.take(400)}" }
                }
                return@runCatching
            }
            body.lines()
                .filter { it.isNotBlank() }
                .filter { !it.trimStart().startsWith("Code:") }
                .forEach { line ->
                    val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
                    val startTsMs = obj["start_ts_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@forEach
                    val spanId = obj["span_id"]?.jsonPrimitive?.content ?: return@forEach
                    val traceId = obj["trace_id"]?.jsonPrimitive?.contentOrNull
                    val spanItemId = "span-$traceId-$spanId"
                    if (!addedIds.add(spanItemId)) return@forEach
                    val spanTimestampIso = Instant.ofEpochMilli(startTsMs).toString()
                    items.add(
                        ReplayTimelineItem(
                            id = spanItemId,
                            type = "span",
                            timestamp = spanTimestampIso,
                            offsetMs = (startTsMs - replayStartMs).toDouble(),
                            title = obj["description"]?.jsonPrimitive?.content?.takeIf {
                                it.isNotBlank()
                            } ?: obj["op"]?.jsonPrimitive?.content ?: "Span",
                            description = obj["op"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                            durationMs = obj["duration_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                            category = obj["op"]?.jsonPrimitive?.contentOrNull,
                            eventId = obj["transaction_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                            issueId = null,
                            traceId = traceId
                        )
                    )
                }
        }.onFailure { logger.error(it) { "Failed to fetch replay timeline spans by time range" } }

        val sorted = items.sortedBy { it.offsetMs }
        return ReplayTimelineResponse(items = sorted, replayStartMs = replayStartMs)
    }

    private data class SegmentDecodeResult(
        val events: List<JsonElement>,
        val isMobileReplay: Boolean = false
    )

    private fun parseJsonEvents(payload: String, segmentIdx: Int): List<JsonElement> {
        return try {
            val parsed = json.parseToJsonElement(payload)
            when (parsed) {
                is JsonArray -> parsed.toList()
                else -> listOf(parsed)
            }
        } catch (e: Exception) {
            logger.error(e) { "Segment $segmentIdx: Failed to parse replay payload as JSON" }
            emptyList()
        }
    }

    private fun readMsgpackBinaryOrString(unpacker: MessageUnpacker): ByteArray? {
        return when (unpacker.nextFormat.valueType) {
            ValueType.BINARY -> {
                val size = unpacker.unpackBinaryHeader()
                unpacker.readPayload(size)
            }
            ValueType.STRING -> unpacker.unpackString().toByteArray(Charsets.UTF_8)
            else -> {
                unpacker.skipValue()
                null
            }
        }
    }

    private fun extractSegmentIdFromJsonPayload(payload: String): Int? {
        return try {
            val obj = json.parseToJsonElement(payload).jsonObject
            obj["segment_id"]?.jsonPrimitive?.intOrNull
        } catch (_: Exception) {
            null
        }
    }

    private fun parseReplayRecordingBinary(payloadBytes: ByteArray, segmentIdx: Int): Pair<Int?, List<JsonElement>> {
        val payload = String(payloadBytes, Charsets.UTF_8)
        val arrayStart = payload.indexOf('[')
        if (arrayStart == -1) {
            logger.warn { "Segment $segmentIdx: replay_recording payload does not contain event array" }
            return null to emptyList()
        }

        val header = payload.substring(0, arrayStart).trim()
        val segmentId = extractSegmentIdFromJsonPayload(header)
        val events = parseJsonEvents(payload.substring(arrayStart), segmentIdx)
        return segmentId to events
    }

    private fun annotateEventsWithSegmentId(events: List<JsonElement>, segmentId: Int): List<JsonElement> {
        return events.map { event ->
            val obj = event as? JsonObject ?: return@map event
            if (obj["segment_id"] != null) {
                event
            } else {
                val updated = obj.toMutableMap()
                updated["segment_id"] = JsonPrimitive(segmentId)
                JsonObject(updated)
            }
        }
    }

    private fun isLikelyMp4(payloadBytes: ByteArray): Boolean {
        if (payloadBytes.size < 8) return false
        val boxType = String(payloadBytes.copyOfRange(4, 8), Charsets.US_ASCII)
        return boxType == "ftyp"
    }

    private fun decodeReplaySegment(recordingData: String, segmentIdx: Int): SegmentDecodeResult {
        val rawBytes = try {
            Base64.getDecoder().decode(recordingData)
        } catch (_: IllegalArgumentException) {
            null
        }

        if (rawBytes == null) {
            return SegmentDecodeResult(events = parseJsonEvents(recordingData, segmentIdx))
        }

        val firstNonWhitespace = rawBytes.firstOrNull {
            val code = it.toInt()
            code != ' '.code && code != '\n'.code && code != '\r'.code && code != '\t'.code
        }

        if (firstNonWhitespace == '['.code.toByte() || firstNonWhitespace == '{'.code.toByte()) {
            return SegmentDecodeResult(
                events = parseJsonEvents(String(rawBytes, Charsets.UTF_8), segmentIdx)
            )
        }

        return try {
            val unpacker = MessagePack.newDefaultUnpacker(rawBytes)
            val topMapSize = unpacker.unpackMapHeader()
            val events = mutableListOf<JsonElement>()
            var mobileSegmentId: Int? = null

            repeat(topMapSize) {
                val key = unpacker.unpackString()
                when (key) {
                    "replay_event" -> {
                        val payload = readMsgpackBinaryOrString(unpacker)
                        if (payload != null && mobileSegmentId == null) {
                            mobileSegmentId = extractSegmentIdFromJsonPayload(String(payload, Charsets.UTF_8))
                        }
                    }
                    "replay_recording" -> {
                        val payload = readMsgpackBinaryOrString(unpacker) ?: return@repeat
                        val (segmentIdFromRecording, recordingEvents) = parseReplayRecordingBinary(payload, segmentIdx)
                        val effectiveSegmentId = segmentIdFromRecording ?: mobileSegmentId ?: segmentIdx
                        if (mobileSegmentId == null) {
                            mobileSegmentId = segmentIdFromRecording
                        }
                        events.addAll(annotateEventsWithSegmentId(recordingEvents, effectiveSegmentId))
                    }
                    "replay_video" -> {
                        val payload = readMsgpackBinaryOrString(unpacker) ?: return@repeat
                        events.add(
                            JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("mobile_replay_video"),
                                    "segment_id" to JsonPrimitive(mobileSegmentId ?: segmentIdx),
                                    "mime_type" to JsonPrimitive(if (isLikelyMp4(payload)) "video/mp4" else "application/octet-stream"),
                                    "size" to JsonPrimitive(payload.size),
                                    "data" to JsonPrimitive(Base64.getEncoder().encodeToString(payload))
                                )
                            )
                        )
                    }
                    else -> unpacker.skipValue()
                }
            }

            unpacker.close()
            SegmentDecodeResult(events = events, isMobileReplay = true)
        } catch (e: Exception) {
            logger.error(e) { "Segment $segmentIdx: Failed to parse msgpack replay segment" }
            SegmentDecodeResult(events = emptyList(), isMobileReplay = true)
        }
    }

    suspend fun getReplayRecording(replayId: String): ReplayRecordingResponse? {
        val normalizedReplayId = normalizeUuid(replayId) ?: return null
        val projectId = getProjectIdForReplay(normalizedReplayId) ?: return null
        val retentionDays = getProjectRetentionDays(projectId)
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)

        val query = """
            SELECT recording_data
            FROM $clickhouseDb.replay_segments
            WHERE toString(replay_id) = '$normalizedReplayId'
                AND $projectIdClause
                AND timestamp >= now64(3) - INTERVAL $retentionDays DAY
            ORDER BY segment_id ASC
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)

            val body = response.bodyAsText()
            val allEvents = mutableListOf<JsonElement>()
            var isMobileReplay = false

            logger.debug { "Processing replay recording response, body lines: ${body.lines().filter { it.isNotBlank() }.size}" }

            body.lines()
                .filter { it.isNotBlank() }
                .forEachIndexed { segmentIdx, line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    val recordingData = obj["recording_data"]?.jsonPrimitive?.content ?: return@forEachIndexed
                    val segment = decodeReplaySegment(recordingData, segmentIdx)
                    if (segment.isMobileReplay) {
                        isMobileReplay = true
                    }
                    allEvents.addAll(segment.events)
                }

            logger.info { "Msgpack decoding complete, extracted ${allEvents.size} total events from all segments" }

            // If mobile replay but no events decoded, return placeholder
            if (isMobileReplay && allEvents.isEmpty()) {
                logger.warn { "Mobile replay detected but no events extracted!" }
                ReplayRecordingResponse(
                    events = listOf(
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("mobile_replay_not_supported"),
                                "message" to JsonPrimitive("Mobile session replays are not yet supported in the web viewer")
                            )
                        )
                    )
                )
            } else {
                logger.info { "Returning response with ${allEvents.size} events, isMobileReplay=$isMobileReplay" }
                ReplayRecordingResponse(events = allEvents)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch replay recording $replayId" }
            null
        }
    }

    suspend fun getReplaysForIssue(issueId: String, limit: Int = 10): List<ReplayListItem> {
        val projectId = getProjectIdForIssue(issueId) ?: return emptyList()
        val retentionDays = getProjectRetentionDays(projectId)
        val escapedIssueId = issueId.replace("'", "''")

        val eventIdsQuery = """
            SELECT toString(event_id) as event_id
            FROM $clickhouseDb.events
            WHERE issue_id = '$escapedIssueId'
                AND project_id = $projectId
                AND event_type = 'error'
                AND ${timestampRetentionClause("timestamp", retentionDays)}
            LIMIT 100
            FORMAT JSONEachRow
        """.trimIndent()

        val eventIds = try {
            val response = ClickHouseClient.execute(eventIdsQuery)
            val body = response.bodyAsText()
            body.lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    obj["event_id"]?.jsonPrimitive?.content
                }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get event IDs for issue $issueId" }
            emptyList()
        }

        if (eventIds.isEmpty()) return emptyList()

        val eventIdList = eventIds.joinToString(",") { "'${it.replace("'", "''")}'" }
        val query = """
            SELECT
                toString(r.replay_id) as replay_id,
                r.project_id,
                formatDateTime(min(r.replay_start_timestamp), '%Y-%m-%dT%H:%i:%S.000Z') as started_at,
                formatDateTime(max(r.timestamp), '%Y-%m-%dT%H:%i:%S.000Z') as finished_at,
                dateDiff('millisecond', min(r.replay_start_timestamp), max(r.timestamp)) as duration_ms,
                arrayFlatten(groupArray(r.urls)) as urls,
                length(arrayFlatten(groupArray(r.error_ids))) as error_count,
                argMax(r.user_id, r.timestamp) as user_id,
                argMax(r.user_email, r.timestamp) as user_email,
                argMax(r.user_username, r.timestamp) as user_username,
                argMax(r.browser_name, r.timestamp) as browser_name,
                argMax(r.browser_version, r.timestamp) as browser_version,
                argMax(r.os_name, r.timestamp) as os_name,
                argMax(r.os_version, r.timestamp) as os_version,
                argMax(r.activity, r.timestamp) as activity
            FROM $clickhouseDb.replay_events r
            WHERE r.project_id = $projectId
                AND hasAny(r.error_ids, [$eventIdList])
                AND r.timestamp >= now64(3) - INTERVAL $retentionDays DAY
            GROUP BY r.replay_id, r.project_id
            ORDER BY max(r.timestamp) DESC
            LIMIT $limit
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)

            val body = response.bodyAsText()
            if (response.status.value !in 200..299) return emptyList()

            body.lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try {
                        val obj = json.parseToJsonElement(line).jsonObject
                        val userId = obj["user_id"]?.jsonPrimitive?.contentOrNull
                        val userEmail = obj["user_email"]?.jsonPrimitive?.contentOrNull
                        val userUsername = obj["user_username"]?.jsonPrimitive?.contentOrNull
                        ReplayListItem(
                            replayId = obj["replay_id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                            projectId = obj["project_id"]?.jsonPrimitive?.long ?: projectId,
                            startedAt = obj["started_at"]?.jsonPrimitive?.content ?: "",
                            finishedAt = obj["finished_at"]?.jsonPrimitive?.content ?: "",
                            durationMs = obj["duration_ms"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                            urls = parseStringArray(obj["urls"]),
                            errorCount = obj["error_count"]?.jsonPrimitive?.intOrNull ?: 0,
                            user = if (userId != null || userEmail != null || userUsername != null) {
                                UserInfo(id = userId, email = userEmail, username = userUsername, ip_address = null)
                            } else {
                                null
                            },
                            browserName = obj["browser_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                            browserVersion = obj["browser_version"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                            osName = obj["os_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                            osVersion = obj["os_version"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                            activity = obj["activity"]?.jsonPrimitive?.intOrNull ?: 0
                        )
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to parse replay list row for issue" }
                        null
                    }
                }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch replays for issue $issueId" }
            emptyList()
        }
    }

    suspend fun getFeedback(
        projectId: Long,
        page: Int = 1,
        limit: Int = 25,
        status: String? = null,
        demoEpochMs: Long? = null
    ): List<FeedbackListItem> {
        val offset = (page - 1) * limit
        val retentionDays = getProjectRetentionDays(projectId)
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)
        val validStatuses = setOf("unresolved", "resolved", "archived")
        val statusFilter = if (status != null && status in validStatuses) {
            "AND status = '${status.replace("'", "''")}'"
        } else {
            ""
        }

        val query = """
            SELECT
                toString(feedback_id) as feedback_id,
                message,
                contact_email,
                name,
                url,
                status,
                formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S.000Z') as created_at,
                environment,
                release,
                platform,
                user_id,
                user_email,
                user_username,
                associated_event_id,
                replay_id
            FROM $clickhouseDb.user_feedback FINAL
            WHERE $projectIdClause
                AND ${timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
                $statusFilter
            ORDER BY timestamp DESC
            LIMIT $limit OFFSET $offset
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
                    val userId = obj["user_id"]?.jsonPrimitive?.contentOrNull
                    val userEmail = obj["user_email"]?.jsonPrimitive?.contentOrNull
                    val userUsername = obj["user_username"]?.jsonPrimitive?.contentOrNull
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
                        user = if (userId != null || userEmail != null || userUsername != null) {
                            UserInfo(id = userId, email = userEmail, username = userUsername, ip_address = null)
                        } else {
                            null
                        },
                        associatedEventId = obj["associated_event_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                        replayId = obj["replay_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    )
                }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch feedback for project $projectId" }
            emptyList()
        }
    }

    suspend fun getFeedbackDetail(feedbackId: String): FeedbackDetailResponse? {
        val normalizedFeedbackId = normalizeUuid(feedbackId) ?: return null
        val projectId = getProjectIdForFeedback(normalizedFeedbackId) ?: return null
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)

        // For feedback detail, we don't apply retention filtering since we're looking up a specific ID
        // The feedback item was already shown in the list (which did apply retention), so we know it exists
        val query = """
            SELECT
                toString(feedback_id) as feedback_id,
                message,
                contact_email,
                name,
                url,
                status,
                formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S.000Z') as timestamp,
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
            FROM $clickhouseDb.user_feedback FINAL
            WHERE toString(feedback_id) = '$normalizedFeedbackId'
                AND $projectIdClause
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            val line = body.lines().firstOrNull { it.isNotBlank() } ?: return null
            val obj = json.parseToJsonElement(line).jsonObject
            val userId = obj["user_id"]?.jsonPrimitive?.contentOrNull
            val userEmail = obj["user_email"]?.jsonPrimitive?.contentOrNull
            val userUsername = obj["user_username"]?.jsonPrimitive?.contentOrNull
            val tagsMap = parseTagsMap(obj["tags"])
            FeedbackDetailResponse(
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
                user = if (userId != null || userEmail != null || userUsername != null) {
                    UserInfo(id = userId, email = userEmail, username = userUsername, ip_address = null)
                } else {
                    null
                },
                associatedEventId = obj["associated_event_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                replayId = obj["replay_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                tags = tagsMap,
                sdkName = obj["sdk_name"]?.jsonPrimitive?.content ?: "",
                sdkVersion = obj["sdk_version"]?.jsonPrimitive?.content ?: ""
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch feedback $feedbackId" }
            null
        }
    }

    private fun parseTagsMap(element: JsonElement?): Map<String, String> {
        if (element == null) return emptyMap()
        val obj = element as? JsonObject ?: return emptyMap()
        return obj.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: "" }
    }

    suspend fun updateFeedback(feedbackId: String, update: com.moneat.models.FeedbackUpdateRequest) {
        if (update.status != null) {
            val validStatuses = setOf("unresolved", "resolved", "archived")
            if (update.status !in validStatuses) {
                throw IllegalArgumentException("Invalid status value")
            }
            val normalizedFeedbackId = normalizeUuid(feedbackId) ?: throw IllegalArgumentException("Invalid feedback ID")
            val escapedStatus = update.status.replace("'", "''")
            val query = """
                ALTER TABLE $clickhouseDb.user_feedback
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

    suspend fun updateIssue(issueId: String, update: com.moneat.models.IssueUpdateRequest) {
        if (update.status != null) {
            val validStatuses = setOf("unresolved", "resolved", "ignored")
            if (update.status !in validStatuses) {
                throw IllegalArgumentException("Invalid status value")
            }

            val escapedIssueId = issueId.replace("'", "''")
            val escapedStatus = update.status.replace("'", "''")

            val query = """
                ALTER TABLE $clickhouseDb.issues
                UPDATE status = '$escapedStatus'
                WHERE issue_id = '$escapedIssueId'
            """.trimIndent()

            try {
                ClickHouseClient.execute(query)
            } catch (e: Exception) {
                logger.error(e) { "Failed to update issue" }
                throw e
            }
        }
    }
}
