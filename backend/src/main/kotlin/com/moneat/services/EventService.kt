package com.moneat.services

import com.moneat.models.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import kotlinx.serialization.json.*
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
            logger.debug { "Processing envelope item type: ${item.type}" }
            when (item.type) {
                "event" -> {
                    logger.debug { "Event payload: ${item.payload.take(500)}" }
                    val event = json.decodeFromString<SentryEvent>(item.payload)
                    storeEvent(projectId, event)
                }
                "transaction" -> {
                    logger.debug { "Transaction payload: ${item.payload.take(500)}" }
                    val transaction = json.decodeFromString<SentryTransaction>(item.payload)
                    storeTransaction(projectId, transaction)
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

    private suspend fun storeTransaction(projectId: Long, transaction: SentryTransaction) {
        val rawEventId = transaction.event_id ?: UUID.randomUUID().toString()
        val eventId = normalizeUuid(rawEventId)
        val traceContext = transaction.contexts?.get("trace") as? JsonObject
        val traceId = traceContext?.get("trace_id")?.jsonPrimitive?.contentOrNull ?: ""
        val transactionOp = traceContext?.get("op")?.jsonPrimitive?.contentOrNull ?: ""
        val traceStatus = traceContext?.get("status")?.jsonPrimitive?.contentOrNull
        val transactionLevel = if (traceStatus == null || traceStatus == "ok") "info" else "error"

        val endTimestampMs = unixSecondsToMillis(transaction.timestamp) ?: System.currentTimeMillis()
        val durationMs = durationMs(transaction.start_timestamp, transaction.timestamp)

        val contexts = transaction.contexts?.toString() ?: "{}"
        val breadcrumbs = transaction.breadcrumbs?.toString() ?: "[]"
        val request = transaction.request?.toString() ?: "{}"
        val message = transaction.transaction ?: transactionOp.ifBlank { "transaction" }

        val transactionInsert = """
            INSERT INTO $clickhouseDb.events (
                event_id, project_id, timestamp, event_type, level,
                message, platform, environment, release, dist, server_name,
                user_id, user_email, user_username, user_ip_address,
                exception_type, exception_value, stack_trace,
                transaction_name, transaction_op, duration_ms,
                fingerprint, issue_id, tags, contexts, breadcrumbs, request,
                sdk_name, sdk_version
            ) VALUES (
                toUUID('$eventId'),
                $projectId,
                fromUnixTimestamp64Milli($endTimestampMs),
                'transaction',
                '$transactionLevel',
                '${escapeSql(message)}',
                '${escapeSql(transaction.platform ?: "unknown")}',
                '${escapeSql(transaction.environment ?: "production")}',
                '${escapeSql(transaction.release ?: "")}',
                '${escapeSql(transaction.dist ?: "")}',
                '${escapeSql(transaction.server_name ?: "")}',
                '${escapeSql(transaction.user?.id ?: "")}',
                '${escapeSql(transaction.user?.email ?: "")}',
                '${escapeSql(transaction.user?.username ?: "")}',
                '${escapeSql(transaction.user?.ip_address ?: "")}',
                '',
                '',
                '',
                '${escapeSql(transaction.transaction ?: "")}',
                '${escapeSql(transactionOp)}',
                $durationMs,
                [],
                '',
                ${tagsToMap(transaction.tags)},
                '${escapeSql(contexts)}',
                '${escapeSql(breadcrumbs)}',
                '${escapeSql(request)}',
                '${escapeSql(transaction.sdk?.name ?: "")}',
                '${escapeSql(transaction.sdk?.version ?: "")}'
            )
        """.trimIndent()

        try {
            val transactionResponse = httpClient.post("$clickhouseUrl") {
                parameter("database", clickhouseDb)
                parameter("user", clickhouseUser)
                parameter("password", clickhousePassword)
                contentType(ContentType.Text.Plain)
                setBody(transactionInsert)
            }

            if (!transactionResponse.status.isSuccess()) {
                val errorBody = transactionResponse.bodyAsText()
                logger.error { "Failed to insert transaction: $errorBody" }
                return
            }

            val spans = transaction.spans.orEmpty()
            if (spans.isNotEmpty()) {
                val spanRows = spans.mapNotNull { span ->
                    val spanStart = span.start_timestamp ?: transaction.start_timestamp
                    val spanEnd = span.timestamp ?: transaction.timestamp ?: spanStart
                    if (spanStart == null || spanEnd == null) return@mapNotNull null

                    val spanStartMs = unixSecondsToMillis(spanStart)
                    val spanEndMs = unixSecondsToMillis(spanEnd)
                    val spanDurationMs = durationMs(spanStart, spanEnd)
                    val spanId = span.span_id?.ifBlank { null } ?: UUID.randomUUID().toString().replace("-", "")
                    val parentSpanId = span.parent_span_id ?: ""
                    val spanTraceId = span.trace_id ?: traceId
                    val spanData = span.data?.toString() ?: "{}"

                    """
                        (
                            '${escapeSql(spanId)}',
                            '${escapeSql(parentSpanId)}',
                            '${escapeSql(spanTraceId)}',
                            toUUID('$eventId'),
                            $projectId,
                            '${escapeSql(span.op ?: "")}',
                            '${escapeSql(span.description ?: "")}',
                            fromUnixTimestamp64Milli($spanStartMs),
                            fromUnixTimestamp64Milli($spanEndMs),
                            $spanDurationMs,
                            '${escapeSql(span.status ?: "")}',
                            ${tagsToMap(span.tags)},
                            '${escapeSql(spanData)}'
                        )
                    """.trimIndent()
                }

                if (spanRows.isNotEmpty()) {
                    val spansInsert = """
                        INSERT INTO $clickhouseDb.spans (
                            span_id, parent_span_id, trace_id, transaction_id, project_id,
                            op, description, start_timestamp, end_timestamp, duration_ms, status, tags, data
                        ) VALUES
                        ${spanRows.joinToString(",\n")}
                    """.trimIndent()

                    val spansResponse = httpClient.post("$clickhouseUrl") {
                        parameter("database", clickhouseDb)
                        parameter("user", clickhouseUser)
                        parameter("password", clickhousePassword)
                        contentType(ContentType.Text.Plain)
                        setBody(spansInsert)
                    }

                    if (!spansResponse.status.isSuccess()) {
                        val errorBody = spansResponse.bodyAsText()
                        logger.error { "Failed to insert spans: $errorBody" }
                    }
                }
            }

            logger.info { "Transaction stored: $eventId for project $projectId (spans=${spans.size})" }
            transaction.release?.takeIf { it.isNotBlank() }?.let { releaseVersion ->
                try {
                    ReleaseService().upsertReleaseFromEvent(projectId, releaseVersion, endTimestampMs)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to upsert release $releaseVersion for project $projectId" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error storing transaction in ClickHouse" }
        }
    }
    
    private suspend fun storeEvent(projectId: Long, event: SentryEvent) {
        val eventId = event.event_id ?: UUID.randomUUID().toString()
        
        logger.debug { "Full event structure - exception: ${event.exception}, message: ${event.message}, platform: ${event.platform}" }
        
        // Parse ISO 8601 timestamp or use current time
        val timestamp = event.timestamp?.let {
            try {
                // Parse ISO 8601 to epoch milliseconds
                java.time.Instant.parse(it).toEpochMilli()
            } catch (e: Exception) {
                logger.warn { "Failed to parse timestamp: $it, using current time" }
                System.currentTimeMillis()
            }
        } ?: System.currentTimeMillis()
        
        // Generate issue ID from fingerprint
        val fingerprint = if (event.fingerprint.isNullOrEmpty()) {
            generateFingerprint(event)
        } else {
            event.fingerprint
        }
        logger.debug { "Generated fingerprint: $fingerprint" }
        val issueId = generateIssueId(fingerprint)
        logger.debug { "Generated issue ID: $issueId" }
        
        // Extract exception info
        val firstException = event.exception?.values?.firstOrNull()
        val exceptionType = firstException?.type ?: ""
        val exceptionValue = firstException?.value ?: event.message ?: ""
        
        // Detect if this is a crash (unhandled exception)
        val mechanism = firstException?.mechanism
        val isHandled = mechanism?.get("handled")?.jsonPrimitive?.booleanOrNull ?: true
        val mechanismType = mechanism?.get("type")?.jsonPrimitive?.contentOrNull
        val isCrash = !isHandled || mechanismType == "onerror" || mechanismType == "onunhandledrejection"
        
        // Determine level: fatal for crashes, otherwise use provided level
        val eventLevel = if (isCrash && event.level == null) "fatal" else (event.level ?: "error")
        
        // Encode full exception with stack trace
        val stackTrace = event.exception?.let { 
            Json.encodeToString(ExceptionInfo.serializer(), it) 
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
                toUUID('${eventId}'),
                $projectId,
                fromUnixTimestamp64Milli($timestamp),
                'error',
                '$eventLevel',
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
                event.release?.takeIf { it.isNotBlank() }?.let { releaseVersion ->
                    try {
                        ReleaseService().upsertReleaseFromEvent(projectId, releaseVersion, timestamp)
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to upsert release $releaseVersion for project $projectId" }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error storing event in ClickHouse" }
        }
    }
    
    private fun generateFingerprint(event: SentryEvent): List<String> {
        val firstException = event.exception?.values?.firstOrNull()
        val type = firstException?.type
        
        logger.info { "=== FINGERPRINT GENERATION ===" }
        logger.info { "Exception type: $type" }
        logger.info { "Total frames: ${firstException?.stacktrace?.frames?.size}" }
        
        // Find the last in_app frame (innermost/actual error location), or fall back to the last frame
        val relevantFrame = firstException?.stacktrace?.frames?.findLast { it.in_app == true }
            ?: firstException?.stacktrace?.frames?.lastOrNull()
        
        val function = relevantFrame?.function
        val filename = relevantFrame?.filename
        
        logger.info { "Selected frame: filename=$filename, function=$function, in_app=${relevantFrame?.in_app}" }
        
        val fingerprint = buildList {
            type?.let { add(it) }
            function?.let { add(it) }
            filename?.let { add(it) }
        }
        
        logger.info { "Final fingerprint: $fingerprint" }
        
        return fingerprint.ifEmpty { listOf("{{ default }}") }
    }
    
    private fun generateIssueId(fingerprint: List<String>): String {
        val combined = fingerprint.joinToString("::")
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(combined.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun normalizeUuid(value: String): String {
        val trimmed = value.trim().lowercase()
        val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        if (uuidRegex.matches(trimmed)) return trimmed

        val hexRegex = Regex("^[0-9a-f]{32}$")
        if (hexRegex.matches(trimmed)) {
            return "${trimmed.substring(0, 8)}-${trimmed.substring(8, 12)}-${trimmed.substring(12, 16)}-${trimmed.substring(16, 20)}-${trimmed.substring(20)}"
        }

        return UUID.randomUUID().toString()
    }

    private fun unixSecondsToMillis(value: Double): Long {
        return (value * 1000).toLong()
    }

    private fun unixSecondsToMillis(value: Double?): Long? {
        return value?.let { unixSecondsToMillis(it) }
    }

    private fun durationMs(start: Double?, end: Double?): Double {
        if (start == null || end == null) return 0.0
        return ((end - start) * 1000.0).coerceAtLeast(0.0)
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
