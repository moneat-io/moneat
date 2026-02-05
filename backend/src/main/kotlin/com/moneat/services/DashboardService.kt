package com.moneat.services

import com.moneat.models.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.config.*
import kotlinx.serialization.json.*
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

private val logger = KotlinLogging.logger {}

class DashboardService {
    private val config = ApplicationConfig("application.conf")
    private val clickhouseUrl = config.property("database.clickhouse.url").getString()
    private val clickhouseDb = config.property("database.clickhouse.database").getString()
    private val clickhouseUser = config.property("database.clickhouse.user").getString()
    private val clickhousePassword = config.property("database.clickhouse.password").getString()
    
    private val httpClient = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }
    
    fun hasProjectAccess(userId: Int, projectId: Long): Boolean {
        return transaction {
            val orgIds = Memberships.selectAll().where { Memberships.user_id eq userId }
                .map { it[Memberships.organization_id] }

            Projects.selectAll().where { (Projects.id eq projectId) and (Projects.organization_id inList orgIds) }.count() > 0
        }
    }
    
    suspend fun hasIssueAccess(userId: Int, issueId: String): Boolean {
        val projectId = getProjectIdForIssue(issueId) ?: return false
        return hasProjectAccess(userId, projectId)
    }
    
    private suspend fun getProjectIdForIssue(issueId: String): Long? {
        val escapedIssueId = issueId.replace("'", "''")
        val query = """
            SELECT project_id 
            FROM $clickhouseDb.issues 
            WHERE issue_id = '$escapedIssueId'
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()
        
        return try {
            val response = httpClient.post("$clickhouseUrl") {
                parameter("database", clickhouseDb)
                parameter("user", clickhouseUser)
                parameter("password", clickhousePassword)
                setBody(query)
            }
            
            val body = response.bodyAsText()
            if (body.isBlank()) return null
            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            obj["project_id"]?.jsonPrimitive?.long
        } catch (e: Exception) {
            logger.error(e) { "Failed to get project ID for issue" }
            null
        }
    }
    
    private suspend fun getIssueCount(projectId: Long): Long {
        val query = """
            SELECT count(DISTINCT issue_id) as count
            FROM $clickhouseDb.issues
            WHERE project_id = $projectId
            FORMAT JSONEachRow
        """.trimIndent()
        
        return try {
            val response = httpClient.post("$clickhouseUrl") {
                parameter("database", clickhouseDb)
                parameter("user", clickhouseUser)
                parameter("password", clickhousePassword)
                setBody(query)
            }
            
            val body = response.bodyAsText()
            if (body.isBlank()) return 0
            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            obj["count"]?.jsonPrimitive?.long ?: 0
        } catch (e: Exception) {
            logger.error(e) { "Failed to get issue count for project $projectId" }
            0
        }
    }
    
    suspend fun getProjects(userId: Int): List<ProjectResponse> {
        val projectsData = transaction {
            val orgIds = Memberships.selectAll().where { Memberships.user_id eq userId }
                .map { it[Memberships.organization_id] }
            
            Projects.select { Projects.organization_id inList orgIds }
                .map { row ->
                    val projectId = row[Projects.id]
                    val publicKey = ProjectKeys.select { ProjectKeys.project_id eq projectId }
                        .firstOrNull()
                        ?.get(ProjectKeys.public_key) ?: ""
                    
                    Triple(
                        projectId,
                        ProjectResponse(
                            id = projectId,
                            name = row[Projects.name],
                            slug = row[Projects.slug],
                            platform = row[Projects.platform],
                            dsn = "http://$publicKey@localhost:8080/$projectId",
                            issueCount = 0
                        ),
                        publicKey
                    )
                }
        }
        
        // Get issue counts for all projects
        return projectsData.map { (projectId, projectResponse, _) ->
            projectResponse.copy(issueCount = getIssueCount(projectId))
        }
    }
    
    suspend fun getProject(projectId: Long): ProjectResponse? {
        val projectData = transaction {
            Projects.select { Projects.id eq projectId }
                .map { row ->
                    val publicKey = ProjectKeys.select { ProjectKeys.project_id eq projectId }
                        .firstOrNull()
                        ?.get(ProjectKeys.public_key) ?: ""
                    
                    ProjectResponse(
                        id = projectId,
                        name = row[Projects.name],
                        slug = row[Projects.slug],
                        platform = row[Projects.platform],
                        dsn = "http://$publicKey@localhost:8080/$projectId",
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
            val orgId = Memberships.select { Memberships.user_id eq userId }
                .firstOrNull()
                ?.get(Memberships.organization_id)
                ?: throw IllegalStateException("User has no organization")
            
            // Create slug from name
            val slug = request.name.lowercase().replace(Regex("[^a-z0-9]+"), "-")
            
            // Insert project
            val projectId = Projects.insert {
                it[organization_id] = orgId
                it[name] = request.name
                it[Projects.slug] = slug
                it[platform] = request.platform
            } get Projects.id
            
            // Generate project key
            val publicKey = java.util.UUID.randomUUID().toString().replace("-", "")
            val secretKey = java.util.UUID.randomUUID().toString().replace("-", "")
            
            ProjectKeys.insert {
                it[project_id] = projectId
                it[ProjectKeys.public_key] = publicKey
                it[secret_key] = secretKey
                it[is_active] = true
            }
            
            ProjectResponse(
                id = projectId,
                name = request.name,
                slug = slug,
                platform = request.platform,
                dsn = "http://$publicKey@localhost:8080/$projectId",
                issueCount = 0 // New projects always have 0 issues
            )
        }
    }
    
    fun updateProject(projectId: Long, request: com.moneat.models.UpdateProjectRequest) {
        transaction {
            Projects.update({ Projects.id eq projectId }) {
                if (request.name != null) {
                    it[name] = request.name
                    it[slug] = request.name.lowercase().replace(Regex("[^a-z0-9]+"), "-")
                }
                if (request.platform != null) {
                    it[platform] = request.platform
                }
            }
        }
    }
    
    fun deleteProject(projectId: Long) {
        transaction {
            Projects.deleteWhere { Projects.id eq projectId }
        }
    }
    
    suspend fun getIssues(projectId: Long, page: Int, limit: Int, status: String?): List<IssueResponse> {
        val offset = (page - 1) * limit
        val validStatuses = setOf("unresolved", "resolved", "ignored")
        val statusFilter = if (status != null && status in validStatuses) {
            "AND status = '${status.replace("'", "''")}'"
        } else ""
        
        // Query events table directly and aggregate
        val query = """
            SELECT 
                issue_id,
                any(message) as title,
                any(exception_type) as culprit,
                any(level) as level,
                any(platform) as platform,
                min(timestamp) as first_seen,
                max(timestamp) as last_seen,
                count() as event_count,
                uniq(user_id) as user_count,
                any(i.status) as status
            FROM $clickhouseDb.events e
            LEFT JOIN (
                SELECT issue_id, status 
                FROM $clickhouseDb.issues FINAL
            ) i USING issue_id
            WHERE e.project_id = $projectId 
                AND e.event_type = 'error'
                $statusFilter
            GROUP BY issue_id
            ORDER BY last_seen DESC
            LIMIT $limit OFFSET $offset
            FORMAT JSONEachRow
        """.trimIndent()
        
        return try {
            val response = httpClient.post("$clickhouseUrl") {
                parameter("database", clickhouseDb)
                parameter("user", clickhouseUser)
                parameter("password", clickhousePassword)
                setBody(query)
            }
            
            val body = response.bodyAsText()
            body.lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    IssueResponse(
                        id = obj["issue_id"]?.jsonPrimitive?.content ?: "",
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
    
    suspend fun getIssue(issueId: String): IssueDetailResponse? {
        val escapedIssueId = issueId.replace("'", "''")
        
        // Query events table directly and aggregate
        val query = """
            SELECT 
                issue_id,
                any(message) as title,
                any(exception_type) as culprit,
                any(level) as level,
                any(platform) as platform,
                min(timestamp) as first_seen,
                max(timestamp) as last_seen,
                count() as event_count,
                uniq(user_id) as user_count,
                any(i.status) as status,
                any(fingerprint) as fingerprint
            FROM $clickhouseDb.events e
            LEFT JOIN (
                SELECT issue_id, status 
                FROM $clickhouseDb.issues FINAL
            ) i USING issue_id
            WHERE e.issue_id = '$escapedIssueId'
            GROUP BY issue_id
            FORMAT JSONEachRow
        """.trimIndent()
        
        return try {
            val response = httpClient.post("$clickhouseUrl") {
                parameter("database", clickhouseDb)
                parameter("user", clickhouseUser)
                parameter("password", clickhousePassword)
                setBody(query)
            }
            
            val body = response.bodyAsText()
            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            
            IssueDetailResponse(
                id = obj["issue_id"]?.jsonPrimitive?.content ?: "",
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
                latestEvent = null // TODO: Fetch latest event
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch issue detail" }
            null
        }
    }
    
    suspend fun getIssueEvents(issueId: String, limit: Int): List<EventResponse> {
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
            ORDER BY timestamp DESC
            LIMIT $limit
            FORMAT JSONEachRow
        """.trimIndent()
        
        return try {
            val response = httpClient.post("$clickhouseUrl") {
                parameter("database", clickhouseDb)
                parameter("user", clickhouseUser)
                parameter("password", clickhousePassword)
                setBody(query)
            }
            
            val body = response.bodyAsText()
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
                        tags = obj["tags"]?.jsonObject?.entries?.associate { 
                            it.key to it.value.jsonPrimitive.content 
                        } ?: emptyMap(),
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
    
    suspend fun getProjectStats(projectId: Long, period: String = "7d"): ProjectStatsResponse {
        val hoursBack = when (period) {
            "24h" -> 24
            "7d" -> 168
            "30d" -> 720
            else -> 168
        }
        
        val intervalMinutes = when (period) {
            "24h" -> 60  // 1 hour buckets
            "7d" -> 360  // 6 hour buckets
            "30d" -> 1440 // 1 day buckets
            else -> 360
        }
        
        // Total events in period
        val totalEventsQuery = """
            SELECT count() as total
            FROM $clickhouseDb.events
            WHERE project_id = $projectId
                AND timestamp >= now() - INTERVAL $hoursBack HOUR
            FORMAT JSONEachRow
        """.trimIndent()
        
        // Total issues
        val totalIssuesQuery = """
            SELECT count(DISTINCT issue_id) as total
            FROM $clickhouseDb.events
            WHERE project_id = $projectId
                AND event_type = 'error'
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
            )
            FORMAT JSONEachRow
        """.trimIndent()
        
        // Affected users in period
        val affectedUsersQuery = """
            SELECT uniq(user_id) as total
            FROM $clickhouseDb.events
            WHERE project_id = $projectId
                AND timestamp >= now() - INTERVAL $hoursBack HOUR
                AND user_id != ''
            FORMAT JSONEachRow
        """.trimIndent()
        
        // Events timeline
        val eventsTimelineQuery = """
            SELECT 
                toStartOfInterval(timestamp, INTERVAL $intervalMinutes MINUTE) as time,
                count() as count
            FROM $clickhouseDb.events
            WHERE project_id = $projectId
                AND timestamp >= now() - INTERVAL $hoursBack HOUR
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
                AND timestamp >= now() - INTERVAL $hoursBack HOUR
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
                AND timestamp >= now() - INTERVAL $hoursBack HOUR
                AND platform != ''
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
                AND timestamp >= now() - INTERVAL $hoursBack HOUR
                AND browser_name != ''
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
                AND timestamp >= now() - INTERVAL $hoursBack HOUR
                AND environment != ''
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
                AND timestamp >= now() - INTERVAL $hoursBack HOUR
                AND event_type = 'error'
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
            GROUP BY time
            ORDER BY time
            FORMAT JSONEachRow
        """.trimIndent()
        
        return try {
            val totalEvents = executeScalarQuery(totalEventsQuery)
            val totalIssues = executeScalarQuery(totalIssuesQuery)
            val unresolvedIssues = executeScalarQuery(unresolvedIssuesQuery)
            val affectedUsers = executeScalarQuery(affectedUsersQuery)
            val eventsTimeline = executeTimelineQuery(eventsTimelineQuery)
            val eventsByLevel = executeMapQuery(eventsByLevelQuery, "level")
            val eventsByPlatform = executeMapQuery(eventsByPlatformQuery, "platform")
            val eventsByBrowser = executeMapQuery(eventsByBrowserQuery, "browser_name")
            val eventsByEnvironment = executeMapQuery(eventsByEnvironmentQuery, "environment")
            val issuesByStatus = executeMapQuery(issuesByStatusQuery, "status")
            val topIssues = executeTopIssuesQuery(topIssuesQuery)
            val usersTimeline = executeTimelineQuery(usersTimelineQuery)
            
            val releaseMarkers = executeReleaseMarkersQuery(projectId, hoursBack)

            ProjectStatsResponse(
                totalEvents = totalEvents,
                totalIssues = totalIssues,
                unresolvedIssues = unresolvedIssues,
                affectedUsers = affectedUsers,
                eventsTimeline = eventsTimeline,
                eventsByLevel = eventsByLevel,
                eventsByPlatform = eventsByPlatform,
                eventsByBrowser = eventsByBrowser,
                eventsByEnvironment = eventsByEnvironment,
                issuesByStatus = issuesByStatus,
                topIssues = topIssues,
                usersTimeline = usersTimeline,
                releaseMarkers = releaseMarkers
            )
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

    suspend fun getReleases(projectId: Long): List<ReleaseListResponse> {
        val escapedProjectId = projectId
        val releasesQuery = """
            SELECT
                release as version,
                formatDateTime(min(timestamp), '%Y-%c-%dT%H:%i:%S.000Z') as first_seen,
                formatDateTime(max(timestamp), '%Y-%c-%dT%H:%i:%S.000Z') as last_seen,
                count() as event_count,
                uniq(user_id) as user_count
            FROM $clickhouseDb.events
            WHERE project_id = $escapedProjectId AND release != ''
            GROUP BY release
            ORDER BY first_seen DESC
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val releases = executeReleasesListQuery(releasesQuery)
            val result = mutableListOf<ReleaseListResponse>()
            for (r in releases) {
                val newIssueCount = getNewIssueCountForRelease(projectId, r.version)
                val crashFreeRate = getCrashFreeRateForRelease(projectId, r.version)
                result.add(ReleaseListResponse(
                    version = r.version,
                    firstSeen = r.firstSeen,
                    lastSeen = r.lastSeen,
                    eventCount = r.eventCount,
                    newIssueCount = newIssueCount,
                    crashFreeRate = crashFreeRate,
                    userCount = r.userCount
                ))
            }
            result
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch releases for project $projectId" }
            emptyList()
        }
    }

    suspend fun getReleaseStats(projectId: Long, version: String): ReleaseDetailStats? {
        val escapedVersion = version.replace("'", "''")
        val releasesQuery = """
            SELECT
                formatDateTime(min(timestamp), '%Y-%c-%dT%H:%i:%S.000Z') as first_seen,
                formatDateTime(max(timestamp), '%Y-%c-%dT%H:%i:%S.000Z') as last_seen,
                count() as total_events,
                uniq(user_id) as user_count
            FROM $clickhouseDb.events
            WHERE project_id = $projectId AND release = '$escapedVersion'
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = httpClient.post("$clickhouseUrl") {
                parameter("database", clickhouseDb)
                parameter("user", clickhouseUser)
                parameter("password", clickhousePassword)
                setBody(releasesQuery)
            }
            val body = response.bodyAsText()
            if (body.isBlank()) return null

            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            val firstSeen = obj["first_seen"]?.jsonPrimitive?.content ?: return null
            val lastSeen = obj["last_seen"]?.jsonPrimitive?.content ?: return null
            val totalEvents = obj["total_events"]?.jsonPrimitive?.long ?: 0
            val userCount = obj["user_count"]?.jsonPrimitive?.long ?: 0

            val newIssues = getNewIssueCountForRelease(projectId, version)
            val resolvedIssues = 0L
            val crashFreeSessionRate = getCrashFreeRateForRelease(projectId, version)
            val crashFreeUserRate = crashFreeSessionRate

            val intervalMinutes = 360
            val eventsTimelineQuery = """
                SELECT
                    formatDateTime(toStartOfInterval(timestamp, INTERVAL $intervalMinutes MINUTE), '%Y-%c-%dT%H:%i:%S.000Z') as time,
                    count() as count
                FROM $clickhouseDb.events
                WHERE project_id = $projectId AND release = '$escapedVersion'
                GROUP BY time
                ORDER BY time
                FORMAT JSONEachRow
            """.trimIndent()

            val eventsByLevelQuery = """
                SELECT level, count() as count
                FROM $clickhouseDb.events
                WHERE project_id = $projectId AND release = '$escapedVersion'
                GROUP BY level
                FORMAT JSONEachRow
            """.trimIndent()

            val topIssuesQuery = """
                SELECT issue_id, any(message) as title, count() as count
                FROM $clickhouseDb.events
                WHERE project_id = $projectId AND release = '$escapedVersion' AND event_type = 'error'
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

    private suspend fun executeReleaseMarkersQuery(projectId: Long, hoursBack: Int): List<ReleaseMarker> {
        val query = """
            SELECT version, formatDateTime(first_seen, '%Y-%c-%dT%H:%i:%S.000Z') as timestamp
            FROM (
                SELECT release as version, min(timestamp) as first_seen
                FROM $clickhouseDb.events
                WHERE project_id = $projectId AND release != ''
                GROUP BY release
            )
            WHERE first_seen >= now() - INTERVAL $hoursBack HOUR
            ORDER BY first_seen
            FORMAT JSONEachRow
        """.trimIndent()

        return try {
            val response = httpClient.post("$clickhouseUrl") {
                parameter("database", clickhouseDb)
                parameter("user", clickhouseUser)
                parameter("password", clickhousePassword)
                setBody(query)
            }
            val body = response.bodyAsText()
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

    private suspend fun executeReleasesListQuery(query: String): List<ReleaseListRow> {
        val response = httpClient.post("$clickhouseUrl") {
            parameter("database", clickhouseDb)
            parameter("user", clickhouseUser)
            parameter("password", clickhousePassword)
            setBody(query)
        }
        val body = response.bodyAsText()
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

    private suspend fun getNewIssueCountForRelease(projectId: Long, version: String): Long {
        val escapedVersion = version.replace("'", "''")
        val query = """
            SELECT count() as total FROM (
                SELECT issue_id, argMin(release, timestamp) as first_release
                FROM $clickhouseDb.events
                WHERE project_id = $projectId AND event_type = 'error' AND issue_id != ''
                GROUP BY issue_id
                HAVING first_release = '$escapedVersion'
            )
            FORMAT JSONEachRow
        """.trimIndent()
        return executeScalarQuery(query)
    }

    private suspend fun getCrashFreeRateForRelease(projectId: Long, version: String): Double? {
        val escapedVersion = version.replace("'", "''")
        val query = """
            SELECT countIf(errors = 0) * 100.0 / count() as rate
            FROM $clickhouseDb.sessions
            WHERE project_id = $projectId AND release = '$escapedVersion'
            FORMAT JSONEachRow
        """.trimIndent()
        return try {
            val response = httpClient.post("$clickhouseUrl") {
                parameter("database", clickhouseDb)
                parameter("user", clickhouseUser)
                parameter("password", clickhousePassword)
                setBody(query)
            }
            val body = response.bodyAsText()
            if (body.isBlank()) return null
            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            val rate = obj["rate"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
            if (rate.isNaN() || rate.isInfinite()) null else rate
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun executeScalarQuery(query: String): Long {
        val response = httpClient.post("$clickhouseUrl") {
            parameter("database", clickhouseDb)
            parameter("user", clickhouseUser)
            parameter("password", clickhousePassword)
            setBody(query)
        }
        val body = response.bodyAsText()
        if (body.isBlank()) return 0
        val obj = json.parseToJsonElement(body.lines().first()).jsonObject
        return obj["total"]?.jsonPrimitive?.long ?: 0
    }
    
    private suspend fun executeTimelineQuery(query: String): List<TimelinePoint> {
        val response = httpClient.post("$clickhouseUrl") {
            parameter("database", clickhouseDb)
            parameter("user", clickhouseUser)
            parameter("password", clickhousePassword)
            setBody(query)
        }
        val body = response.bodyAsText()
        return body.lines()
            .filter { it.isNotBlank() }
            .map { line ->
                val obj = json.parseToJsonElement(line).jsonObject
                TimelinePoint(
                    timestamp = obj["time"]?.jsonPrimitive?.content ?: "",
                    count = obj["count"]?.jsonPrimitive?.long ?: 0
                )
            }
    }
    
    private suspend fun executeMapQuery(query: String, keyField: String): Map<String, Long> {
        val response = httpClient.post("$clickhouseUrl") {
            parameter("database", clickhouseDb)
            parameter("user", clickhouseUser)
            parameter("password", clickhousePassword)
            setBody(query)
        }
        val body = response.bodyAsText()
        return body.lines()
            .filter { it.isNotBlank() }
            .associate { line ->
                val obj = json.parseToJsonElement(line).jsonObject
                val key = obj[keyField]?.jsonPrimitive?.content ?: "unknown"
                val count = obj["count"]?.jsonPrimitive?.long ?: 0
                key to count
            }
    }
    
    private suspend fun executeTopIssuesQuery(query: String): List<TopIssue> {
        val response = httpClient.post("$clickhouseUrl") {
            parameter("database", clickhouseDb)
            parameter("user", clickhouseUser)
            parameter("password", clickhousePassword)
            setBody(query)
        }
        val body = response.bodyAsText()
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
                httpClient.post("$clickhouseUrl") {
                    parameter("database", clickhouseDb)
                    parameter("user", clickhouseUser)
                    parameter("password", clickhousePassword)
                    setBody(query)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to update issue" }
                throw e
            }
        }
    }
}
