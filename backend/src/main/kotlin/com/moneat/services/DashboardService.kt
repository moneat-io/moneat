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
    
    fun getProjects(userId: Int): List<ProjectResponse> {
        return transaction {
            val orgIds = Memberships.selectAll().where { Memberships.user_id eq userId }
                .map { it[Memberships.organization_id] }
            
            Projects.select { Projects.organization_id inList orgIds }
                .map { row ->
                    val projectId = row[Projects.id]
                    val publicKey = ProjectKeys.select { ProjectKeys.project_id eq projectId }
                        .firstOrNull()
                        ?.get(ProjectKeys.public_key) ?: ""
                    
                    ProjectResponse(
                        id = projectId,
                        name = row[Projects.name],
                        slug = row[Projects.slug],
                        platform = row[Projects.platform],
                        dsn = "http://$publicKey@localhost:8080/$projectId"
                    )
                }
        }
    }
    
    fun getProject(projectId: Long): ProjectResponse? {
        return transaction {
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
                        dsn = "http://$publicKey@localhost:8080/$projectId"
                    )
                }
                .firstOrNull()
        }
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
                dsn = "http://$publicKey@localhost:8080/$projectId"
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
        
        val query = """
            SELECT 
                issue_id,
                title,
                culprit,
                level,
                platform,
                first_seen,
                last_seen,
                event_count,
                user_count,
                status
            FROM $clickhouseDb.issues
            WHERE project_id = $projectId $statusFilter
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
        val query = """
            SELECT 
                issue_id,
                title,
                culprit,
                level,
                platform,
                first_seen,
                last_seen,
                event_count,
                user_count,
                status,
                fingerprint
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
                exception_value,
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
                        exception = obj["exception_value"]?.jsonPrimitive?.contentOrNull,
                        breadcrumbs = obj["breadcrumbs"]?.jsonPrimitive?.contentOrNull
                    )
                }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch issue events" }
            emptyList()
        }
    }
    
    suspend fun getProjectStats(projectId: Long): ProjectStatsResponse {
        // TODO: Implement real stats
        return ProjectStatsResponse(
            totalEvents = 0,
            totalIssues = 0,
            eventsToday = 0,
            timeline = emptyList()
        )
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
