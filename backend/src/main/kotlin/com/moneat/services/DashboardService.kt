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
    
    fun getProjects(userId: Int): List<ProjectResponse> {
        return transaction {
            val orgIds = Memberships.select { Memberships.user_id eq userId }
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
    
    suspend fun getIssues(projectId: Long, page: Int, limit: Int, status: String?): List<IssueResponse> {
        val offset = (page - 1) * limit
        val statusFilter = if (status != null) "AND status = '$status'" else ""
        
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
            WHERE issue_id = '$issueId'
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
            WHERE issue_id = '$issueId'
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
}

object Projects : Table("projects") {
    val id = long("id").autoIncrement()
    val organization_id = integer("organization_id")
    val name = varchar("name", 255)
    val slug = varchar("slug", 255)
    val platform = varchar("platform", 50).nullable()
    override val primaryKey = PrimaryKey(id)
}
