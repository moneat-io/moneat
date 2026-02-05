package com.moneat.services

import com.moneat.models.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.util.*

private val logger = KotlinLogging.logger {}

class EventService {
    private val config = ApplicationConfig("application.conf")
    private val clickhouseUrl = config.property("database.clickhouse.url").getString()
    private val clickhouseDb = config.property("database.clickhouse.database").getString()
    private val clickhouseUser = config.property("database.clickhouse.user").getString()
    private val clickhousePassword = config.property("database.clickhouse.password").getString()
    
    private val httpClient = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }
    
    fun verifyProjectKey(projectId: Long, publicKey: String): Boolean {
        return transaction {
            ProjectKeys.selectAll().where {
                (ProjectKeys.project_id eq projectId) and
                        (ProjectKeys.public_key eq publicKey) and
                        (ProjectKeys.is_active eq true)
            }.count() > 0
        }
    }
    
    suspend fun processEnvelope(projectId: Long, envelope: SentryEnvelope) {
        for (item in envelope.items) {
            when (item.type) {
                "event" -> {
                    val event = json.decodeFromString<SentryEvent>(item.payload)
                    storeEvent(projectId, event)
                }
                "transaction" -> {
                    // TODO: Handle transactions
                    logger.debug { "Received transaction (not yet implemented)" }
                }
                "session" -> {
                    // TODO: Handle sessions
                    logger.debug { "Received session (not yet implemented)" }
                }
                else -> {
                    logger.debug { "Unknown item type: ${item.type}" }
                }
            }
        }
    }
    
    suspend fun processStoreEvent(projectId: Long, body: String) {
        val event = json.decodeFromString<SentryEvent>(body)
        storeEvent(projectId, event)
    }
    
    private suspend fun storeEvent(projectId: Long, event: SentryEvent) {
        val eventId = event.event_id ?: UUID.randomUUID().toString()
        val timestamp = event.timestamp?.toLong()?.let { it * 1000 } ?: System.currentTimeMillis()
        
        // Generate issue ID from fingerprint
        val fingerprint = event.fingerprint ?: generateFingerprint(event)
        val issueId = generateIssueId(fingerprint)
        
        // Extract exception info
        val exceptionType = event.exception?.values?.firstOrNull()?.type ?: ""
        val exceptionValue = event.exception?.values?.firstOrNull()?.value ?: event.message ?: ""
        val stackTrace = event.exception?.values?.firstOrNull()?.stacktrace?.let { 
            Json.encodeToString(StackTrace.serializer(), it) 
        } ?: ""
        
        // Extract contexts
        val contexts = event.contexts?.toString() ?: "{}"
        val breadcrumbs = event.breadcrumbs?.toString() ?: "[]"
        val request = event.request?.toString() ?: "{}"
        
        // Build ClickHouse insert query
        val query = """
            INSERT INTO $clickhouseDb.events (
                event_id, project_id, timestamp, event_type, level,
                message, platform, environment, release, dist, server_name,
                user_id, user_email, user_username, user_ip_address,
                exception_type, exception_value, stack_trace,
                fingerprint, issue_id, tags, contexts, breadcrumbs, request,
                sdk_name, sdk_version
            ) VALUES (
                '${eventId}',
                $projectId,
                fromUnixTimestamp64Milli($timestamp),
                'error',
                '${event.level ?: "error"}',
                '${escapeSql(exceptionValue)}',
                '${event.platform ?: "unknown"}',
                '${event.environment ?: "production"}',
                '${event.release ?: ""}',
                '${event.dist ?: ""}',
                '${event.server_name ?: ""}',
                '${event.user?.id ?: ""}',
                '${event.user?.email ?: ""}',
                '${event.user?.username ?: ""}',
                '${event.user?.ip_address ?: ""}',
                '${escapeSql(exceptionType)}',
                '${escapeSql(exceptionValue)}',
                '${escapeSql(stackTrace)}',
                ${fingerprintToArray(fingerprint)},
                '$issueId',
                ${tagsToMap(event.tags)},
                '${escapeSql(contexts)}',
                '${escapeSql(breadcrumbs)}',
                '${escapeSql(request)}',
                '${event.sdk?.name ?: ""}',
                '${event.sdk?.version ?: ""}'
            )
        """.trimIndent()
        
        try {
            val response = httpClient.post("$clickhouseUrl") {
                parameter("database", clickhouseDb)
                parameter("user", clickhouseUser)
                parameter("password", clickhousePassword)
                contentType(ContentType.Text.Plain)
                setBody(query)
            }
            
            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                logger.error { "Failed to insert event: $errorBody" }
            } else {
                logger.info { "Event stored: $eventId for project $projectId" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error storing event in ClickHouse" }
        }
    }
    
    private fun generateFingerprint(event: SentryEvent): List<String> {
        val type = event.exception?.values?.firstOrNull()?.type
        val value = event.exception?.values?.firstOrNull()?.value
        val function = event.exception?.values?.firstOrNull()?.stacktrace?.frames?.lastOrNull()?.function
        
        return listOfNotNull(type, value, function).ifEmpty { listOf("{{ default }}") }
    }
    
    private fun generateIssueId(fingerprint: List<String>): String {
        val combined = fingerprint.joinToString("::")
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(combined.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }.take(16)
    }
    
    private fun escapeSql(str: String): String {
        return str.replace("'", "''").replace("\\", "\\\\")
    }
    
    private fun fingerprintToArray(fingerprint: List<String>): String {
        return "[${fingerprint.joinToString(",") { "'${escapeSql(it)}'" }}]"
    }
    
    private fun tagsToMap(tags: Map<String, String>?): String {
        if (tags.isNullOrEmpty()) return "{}"
        return "{${tags.entries.joinToString(",") { "'${escapeSql(it.key)}':'${escapeSql(it.value)}'" }}}"
    }
}
