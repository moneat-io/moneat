package com.moneat.services

import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.models.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.time.Instant
import java.util.*

private val logger = KotlinLogging.logger {}

private data class LogWithCursor(
    val log: LogEntryResponse,
    val timestampMs: Long
)

class LogService {
    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val json = Json { ignoreUnknownKeys = true }
    private val usageTracking = UsageTrackingService.instance

    fun liveChannel(projectId: Long): String = "log:live:$projectId"

    suspend fun enqueueSdkLogs(projectId: Long, entries: List<LogIngestEntry>, queueKey: String): Int {
        val normalized = entries.mapNotNull { normalizeSdkEntry(it) }
        return enqueueNormalized(projectId, null, "sdk", normalized, queueKey)
    }

    suspend fun enqueueAgentLogs(projectId: Long, systemId: String?, entries: List<AgentLogEntry>, queueKey: String): Int {
        val normalized = entries.mapNotNull { normalizeAgentEntry(it, systemId) }
        return enqueueNormalized(projectId, systemId, "agent", normalized, queueKey)
    }

    suspend fun enqueueOtlpLogs(projectId: Long, body: String, queueKey: String): Int {
        val parsed = parseOtlpJson(body)
        val normalized = parsed.mapNotNull { normalizeOtlpEntry(it) }
        return enqueueNormalized(projectId, null, "otlp", normalized, queueKey)
    }

    fun estimateBillableBytes(entries: List<LogIngestEntry>): Long {
        return entries
            .mapNotNull { normalizeSdkEntry(it) }
            .sumOf { (it.message.length + it.body.length).toLong() }
    }

    fun estimateBillableBytes(entries: List<AgentLogEntry>, systemId: String?): Long {
        return entries
            .mapNotNull { normalizeAgentEntry(it, systemId) }
            .sumOf { (it.message.length + it.body.length).toLong() }
    }

    fun decodeQueueMessage(encoded: String): QueuedLogBatch {
        return json.decodeFromString(encoded)
    }

    fun encodeQueueMessage(batch: QueuedLogBatch): String {
        return json.encodeToString(batch)
    }

    suspend fun insertBatch(batch: QueuedLogBatch): List<LogEntryResponse> {
        if (batch.logs.isEmpty()) return emptyList()

        val systemIdValue = batch.systemId ?: "00000000-0000-0000-0000-000000000000"

        val rows = batch.logs.joinToString(",\n") { entry ->
            """
            (
                toUUID('${escapeSql(entry.logId)}'),
                ${batch.projectId},
                toUUID('${escapeSql(systemIdValue)}'),
                fromUnixTimestamp64Milli(${entry.timestampMs}),
                '${escapeSql(entry.level)}',
                '${escapeSql(entry.message)}',
                '${escapeSql(entry.body)}',
                '${escapeSql(entry.service)}',
                '${escapeSql(entry.environment)}',
                '${escapeSql(entry.host)}',
                '${escapeSql(entry.source)}',
                '${escapeSql(entry.containerName)}',
                '${escapeSql(entry.containerId)}',
                '${escapeSql(entry.containerImage)}',
                '${escapeSql(entry.traceId)}',
                '${escapeSql(entry.spanId)}',
                ${mapToSqlMap(entry.tags)},
                ${mapToSqlMap(entry.resourceAttributes)}
            )
            """.trimIndent()
        }

        val insert = """
            INSERT INTO $clickhouseDb.logs (
                log_id,
                project_id,
                system_id,
                timestamp,
                level,
                message,
                body,
                service,
                environment,
                host,
                source,
                container_name,
                container_id,
                container_image,
                trace_id,
                span_id,
                tags,
                resource_attributes
            ) VALUES
            $rows
        """.trimIndent()

        val response = ClickHouseClient.execute(insert)
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw IllegalStateException("Failed to insert logs into ClickHouse: ${errorBody.take(600)}")
        }

        val totalBytes = batch.logs.sumOf { it.message.length + it.body.length }
        usageTracking.recordUsage(batch.projectId, "log", totalBytes)

        return batch.logs.map { toResponse(it, batch.systemId) }
    }

    suspend fun publishLiveLogs(projectId: Long, logs: List<LogEntryResponse>) {
        if (logs.isEmpty()) return
        val channel = liveChannel(projectId)
        val redis = RedisConfig.sync()
        logs.forEach { log ->
            redis.publish(channel, json.encodeToString(log))
        }
    }

    fun parseLiveLog(payload: String): LogEntryResponse? {
        return try {
            json.decodeFromString<LogEntryResponse>(payload)
        } catch (_: Exception) {
            null
        }
    }

    fun matchesTailFilters(log: LogEntryResponse, filters: LogTailFilters): Boolean {
        if (filters.levels.isNotEmpty() && log.level.lowercase() !in filters.levels) {
            return false
        }
        if (!filters.service.isNullOrBlank() && !log.service.equals(filters.service, ignoreCase = true)) {
            return false
        }
        if (!filters.environment.isNullOrBlank() && !log.environment.equals(filters.environment, ignoreCase = true)) {
            return false
        }
        if (!filters.query.isNullOrBlank()) {
            val query = filters.query.lowercase()
            val haystack = "${log.message}\n${log.body}".lowercase()
            if (!haystack.contains(query)) {
                return false
            }
        }
        return true
    }

    suspend fun queryLogs(projectId: Long, request: LogQueryRequest): LogQueryResponse {
        val limit = request.limit.coerceIn(1, 500)
        val conditions = mutableListOf<String>()

        val totalCountFilter = buildScopeFilter(projectId, request.systemId) ?: return LogQueryResponse(
            logs = emptyList(),
            nextCursor = null,
            hasMore = false,
            totalCount = 0L
        )

        // Support filtering by either system_id or project_id
        conditions += totalCountFilter

        val fromMs = parseTimeToMillis(request.from)
        if (fromMs != null) {
            conditions += "timestamp >= fromUnixTimestamp64Milli($fromMs)"
        }

        val toMs = parseTimeToMillis(request.to)
        if (toMs != null) {
            conditions += "timestamp <= fromUnixTimestamp64Milli($toMs)"
        }

        if (!request.service.isNullOrBlank()) {
            conditions += "service = '${escapeSql(request.service)}'"
        }

        if (!request.environment.isNullOrBlank()) {
            conditions += "environment = '${escapeSql(request.environment)}'"
        }
        
        if (!request.containerName.isNullOrBlank()) {
            conditions += "container_name = '${escapeSql(request.containerName)}'"
        }

        val normalizedLevels = request.levels.map { normalizeLevel(it) }.filter { it.isNotBlank() }.distinct()
        if (normalizedLevels.isNotEmpty()) {
            val inClause = normalizedLevels.joinToString(",") { "'${escapeSql(it)}'" }
            conditions += "level IN ($inClause)"
        }

        if (!request.query.isNullOrBlank()) {
            val tokens = request.query
                .trim()
                .split(Regex("\\s+"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .take(8)

            if (tokens.isNotEmpty()) {
                val tokenConditions = tokens.joinToString(" AND ") { token ->
                    val escaped = escapeSql(token)
                    "(hasTokenCaseInsensitive(message, '$escaped') OR hasTokenCaseInsensitive(body, '$escaped'))"
                }
                conditions += tokenConditions
            }
        }

        request.tags.forEach { (key, value) ->
            if (key.isNotBlank()) {
                val escapedKey = escapeSql(key)
                val escapedValue = escapeSql(value)
                conditions += "mapContains(tags, '$escapedKey') AND tags['$escapedKey'] = '$escapedValue'"
            }
        }

        decodeCursor(request.cursor)?.let { (cursorTs, cursorLogId) ->
            conditions += "(timestamp < fromUnixTimestamp64Milli($cursorTs) OR (timestamp = fromUnixTimestamp64Milli($cursorTs) AND log_id < toUUID('${escapeSql(cursorLogId)}')))"
        }

        val whereClause = conditions.joinToString(" AND ")
        val query = """
            SELECT
                toString(log_id) AS log_id,
                formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S.%fZ') AS timestamp_formatted,
                toString(level) AS level,
                message,
                body,
                service,
                environment,
                host,
                toString(source) AS source,
                container_name,
                container_id,
                container_image,
                trace_id,
                span_id,
                toJSONString(tags) AS tags,
                toJSONString(resource_attributes) AS resource_attributes,
                toUnixTimestamp64Milli(timestamp) AS timestamp_ms,
                toString(system_id) AS system_id_text
            FROM $clickhouseDb.logs
            WHERE $whereClause
            ORDER BY timestamp DESC, log_id DESC
            LIMIT ${limit + 1}
            FORMAT JSONEachRow
        """.trimIndent()

        val response = ClickHouseClient.execute(query)
        val body = response.bodyAsText()
        if (!response.status.isSuccess() || body.trimStart().startsWith("Code:")) {
            throw IllegalStateException("Failed to query logs: ${body.take(600)}")
        }

        val parsed = parseQueryRows(body)
        val hasMore = parsed.size > limit
        val pageRows = if (hasMore) parsed.take(limit) else parsed
        val nextCursor = pageRows.lastOrNull()?.let { row ->
            if (hasMore) encodeCursor(row.timestampMs, row.log.logId) else null
        }

        // Query total count - use scope filter
        val totalCountQuery = """
            SELECT count() as count
            FROM $clickhouseDb.logs
            WHERE $totalCountFilter
            FORMAT JSONEachRow
        """.trimIndent()
        
        val totalCountResponse = ClickHouseClient.execute(totalCountQuery)
        val totalCountBody = totalCountResponse.bodyAsText()
        val totalCount = if (totalCountResponse.status.isSuccess() && !totalCountBody.trimStart().startsWith("Code:")) {
            try {
                val jsonElement = Json.parseToJsonElement(totalCountBody.trim())
                jsonElement.jsonObject["count"]?.jsonPrimitive?.longOrNull ?: 0L
            } catch (_: Exception) {
                0L
            }
        } else {
            0L
        }

        return LogQueryResponse(
            logs = pageRows.map { it.log },
            nextCursor = nextCursor,
            hasMore = hasMore,
            totalCount = totalCount
        )
    }

    suspend fun getFilterOptions(projectId: Long, from: String?, to: String?): LogFilterOptionsResponse {
        val conditions = mutableListOf("project_id = $projectId")
        val fromMs = parseTimeToMillis(from)
        if (fromMs != null) {
            conditions += "timestamp >= fromUnixTimestamp64Milli($fromMs)"
        }
        val toMs = parseTimeToMillis(to)
        if (toMs != null) {
            conditions += "timestamp <= fromUnixTimestamp64Milli($toMs)"
        }
        val whereClause = conditions.joinToString(" AND ")

        val services = queryDistinctLines(
            """
            SELECT DISTINCT service
            FROM $clickhouseDb.logs
            WHERE $whereClause AND service != ''
            ORDER BY service
            LIMIT 200
            FORMAT TSV
            """.trimIndent()
        )

        val environments = queryDistinctLines(
            """
            SELECT DISTINCT environment
            FROM $clickhouseDb.logs
            WHERE $whereClause AND environment != ''
            ORDER BY environment
            LIMIT 200
            FORMAT TSV
            """.trimIndent()
        )

        val tagKeys = queryDistinctLines(
            """
            SELECT DISTINCT tag_key
            FROM (
                SELECT arrayJoin(mapKeys(tags)) AS tag_key
                FROM $clickhouseDb.logs
                WHERE $whereClause
            )
            WHERE tag_key != ''
            ORDER BY tag_key
            LIMIT 200
            FORMAT TSV
            """.trimIndent()
        )

        return LogFilterOptionsResponse(
            services = services,
            environments = environments,
            levels = listOf("trace", "debug", "info", "warn", "error", "fatal"),
            tagKeys = tagKeys
        )
    }

    suspend fun getTagValues(projectId: Long, key: String, from: String?, to: String?, limit: Int = 50): LogTagValuesResponse {
        val escapedKey = escapeSql(key.trim())
        if (escapedKey.isBlank()) return LogTagValuesResponse(key = key, values = emptyList())

        val conditions = mutableListOf("project_id = $projectId", "mapContains(tags, '$escapedKey')")
        val fromMs = parseTimeToMillis(from)
        if (fromMs != null) {
            conditions += "timestamp >= fromUnixTimestamp64Milli($fromMs)"
        }
        val toMs = parseTimeToMillis(to)
        if (toMs != null) {
            conditions += "timestamp <= fromUnixTimestamp64Milli($toMs)"
        }
        val whereClause = conditions.joinToString(" AND ")

        val values = queryDistinctLines(
            """
            SELECT DISTINCT tags['$escapedKey'] AS tag_value
            FROM $clickhouseDb.logs
            WHERE $whereClause AND tags['$escapedKey'] != ''
            ORDER BY tag_value
            LIMIT ${limit.coerceIn(1, 200)}
            FORMAT TSV
            """.trimIndent()
        )

        return LogTagValuesResponse(key = key, values = values)
    }

    private suspend fun queryDistinctLines(query: String): List<String> {
        val response = ClickHouseClient.execute(query)
        val body = response.bodyAsText()
        if (!response.status.isSuccess() || body.trimStart().startsWith("Code:")) {
            logger.warn { "Failed to query log filter values: ${body.take(600)}" }
            return emptyList()
        }
        return body
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun parseQueryRows(raw: String): List<LogWithCursor> {
        if (raw.isBlank()) return emptyList()

        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    val obj = json.parseToJsonElement(line).jsonObject
                    val timestampMs = obj["timestamp_ms"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                    val systemId = obj["system_id_text"]?.jsonPrimitive?.contentOrNull
                        ?: obj["system_id"]?.jsonPrimitive?.contentOrNull
                    val log = LogEntryResponse(
                        logId = obj["log_id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        timestamp = obj["timestamp_formatted"]?.jsonPrimitive?.content ?: Instant.ofEpochMilli(timestampMs).toString(),
                        level = normalizeLevel(obj["level"]?.jsonPrimitive?.content),
                        message = obj["message"]?.jsonPrimitive?.content ?: "",
                        body = obj["body"]?.jsonPrimitive?.content ?: "",
                        service = obj["service"]?.jsonPrimitive?.content ?: "",
                        environment = obj["environment"]?.jsonPrimitive?.content ?: "",
                        host = obj["host"]?.jsonPrimitive?.content ?: "",
                        source = normalizeSource(obj["source"]?.jsonPrimitive?.content ?: "sdk"),
                        containerName = obj["container_name"]?.jsonPrimitive?.content ?: "",
                        containerId = obj["container_id"]?.jsonPrimitive?.content ?: "",
                        containerImage = obj["container_image"]?.jsonPrimitive?.content ?: "",
                        traceId = obj["trace_id"]?.jsonPrimitive?.content ?: "",
                        spanId = obj["span_id"]?.jsonPrimitive?.content ?: "",
                        tags = parseMapField(obj["tags"]),
                        resourceAttributes = parseMapField(obj["resource_attributes"]),
                        systemId = if (systemId == "00000000-0000-0000-0000-000000000000") null else systemId
                    )
                    LogWithCursor(log = log, timestampMs = timestampMs)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse log row" }
                    null
                }
            }
            .toList()
    }

    private fun parseMapField(element: JsonElement?): Map<String, String> {
        if (element == null) return emptyMap()

        return try {
            when (element) {
                is JsonObject -> element.mapValues { (_, value) -> value.jsonPrimitive.content }
                else -> {
                    val text = element.jsonPrimitive.contentOrNull ?: return emptyMap()
                    if (text.isBlank()) {
                        emptyMap()
                    } else {
                        val obj = json.parseToJsonElement(text).jsonObject
                        obj.mapValues { (_, value) -> value.jsonPrimitive.content }
                    }
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private suspend fun enqueueNormalized(
        projectId: Long,
        systemId: String?,
        source: String,
        logs: List<QueuedLogEntry>,
        queueKey: String
    ): Int {
        if (logs.isEmpty()) return 0

        val message = encodeQueueMessage(
            QueuedLogBatch(
                projectId = projectId,
                systemId = systemId,
                source = source,
                logs = logs
            )
        )

        RedisConfig.sync().lpush(queueKey, message)
        return logs.size
    }

    private fun normalizeSdkEntry(entry: LogIngestEntry): QueuedLogEntry? {
        val message = entry.message?.trim().orEmpty()
        if (message.isBlank()) return null

        return QueuedLogEntry(
            logId = UUID.randomUUID().toString(),
            timestampMs = resolveTimestampMs(entry.timestamp, entry.timestampMs),
            level = normalizeLevel(entry.level),
            message = trimTo(entry.message.orEmpty(), 8192),
            body = trimTo(entry.body ?: entry.message.orEmpty(), 32768),
            service = trimTo(entry.service.orEmpty(), 256),
            environment = trimTo(entry.environment.orEmpty(), 128),
            host = trimTo(entry.host.orEmpty(), 256),
            source = normalizeSource(entry.source ?: "sdk"),
            containerName = trimTo(entry.containerName.orEmpty(), 256),
            containerId = trimTo(entry.containerId.orEmpty(), 128),
            containerImage = trimTo(entry.containerImage.orEmpty(), 512),
            traceId = trimTo(entry.traceId.orEmpty(), 128),
            spanId = trimTo(entry.spanId.orEmpty(), 128),
            tags = sanitizeMap(entry.tags),
            resourceAttributes = sanitizeMap(entry.resourceAttributes)
        )
    }

    private fun normalizeAgentEntry(entry: AgentLogEntry, systemId: String?): QueuedLogEntry? {
        val message = entry.message?.trim().orEmpty()
        if (message.isBlank()) return null

        val source = when (entry.stream?.lowercase()) {
            "stderr" -> "agent_stderr"
            else -> "agent_stdout"
        }

        return QueuedLogEntry(
            logId = UUID.randomUUID().toString(),
            timestampMs = resolveTimestampMs(entry.timestamp, entry.timestampMs),
            level = normalizeLevel(entry.level),
            message = trimTo(entry.message.orEmpty(), 8192),
            body = trimTo(entry.body ?: entry.message.orEmpty(), 32768),
            service = trimTo(entry.service ?: entry.containerName.orEmpty(), 256),
            environment = trimTo(entry.environment.orEmpty(), 128),
            host = trimTo(entry.host.orEmpty(), 256),
            source = source,
            containerName = trimTo(entry.containerName.orEmpty(), 256),
            containerId = trimTo(entry.containerId.orEmpty(), 128),
            containerImage = trimTo(entry.containerImage.orEmpty(), 512),
            traceId = trimTo(entry.traceId.orEmpty(), 128),
            spanId = trimTo(entry.spanId.orEmpty(), 128),
            tags = sanitizeMap(entry.tags),
            resourceAttributes = sanitizeMap(entry.resourceAttributes),
            systemId = systemId
        )
    }

    private fun normalizeOtlpEntry(entry: LogIngestEntry): QueuedLogEntry? {
        val base = normalizeSdkEntry(entry) ?: return null
        return base.copy(source = "otlp")
    }

    private fun toResponse(entry: QueuedLogEntry, systemId: String?): LogEntryResponse {
        return LogEntryResponse(
            logId = entry.logId,
            timestamp = Instant.ofEpochMilli(entry.timestampMs).toString(),
            level = entry.level,
            message = entry.message,
            body = entry.body,
            service = entry.service,
            environment = entry.environment,
            host = entry.host,
            source = entry.source,
            containerName = entry.containerName,
            containerId = entry.containerId,
            containerImage = entry.containerImage,
            traceId = entry.traceId,
            spanId = entry.spanId,
            tags = entry.tags,
            resourceAttributes = entry.resourceAttributes,
            systemId = systemId
        )
    }

    fun parseOtlpJson(payload: String): List<LogIngestEntry> {
        val parsed = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            logger.warn(e) { "Invalid OTLP JSON payload" }
            return emptyList()
        }

        val resourceLogs = parsed["resourceLogs"]?.jsonArray ?: return emptyList()
        val entries = mutableListOf<LogIngestEntry>()

        resourceLogs.forEach { resourceLogElement ->
            val resourceLog = resourceLogElement.jsonObject
            val resourceAttrs = attributesToMap(resourceLog["resource"]?.jsonObject?.get("attributes"))
            val scopeLogs = resourceLog["scopeLogs"]?.jsonArray
                ?: resourceLog["instrumentationLibraryLogs"]?.jsonArray
                ?: JsonArray(emptyList())

            scopeLogs.forEach { scopeElement ->
                val scopeLog = scopeElement.jsonObject
                val logRecords = scopeLog["logRecords"]?.jsonArray ?: JsonArray(emptyList())

                logRecords.forEach { recordElement ->
                    val record = recordElement.jsonObject
                    val attributes = attributesToMap(record["attributes"])
                    val mergedAttributes = resourceAttrs + attributes
                    val bodyText = extractAnyValue(record["body"]) ?: ""
                    val message = if (bodyText.isBlank()) "OTLP log record" else bodyText
                    val severityText = record["severityText"]?.jsonPrimitive?.contentOrNull
                    val timestampNs = record["timeUnixNano"]?.jsonPrimitive?.longOrNull
                        ?: record["observedTimeUnixNano"]?.jsonPrimitive?.longOrNull
                    val timestampMs = timestampNs?.div(1_000_000)

                    val entry = LogIngestEntry(
                        timestampMs = timestampMs,
                        level = severityText,
                        message = message,
                        body = bodyText,
                        service = mergedAttributes["service.name"],
                        environment = mergedAttributes["deployment.environment"] ?: mergedAttributes["service.environment"],
                        host = mergedAttributes["host.name"],
                        source = "otlp",
                        traceId = record["traceId"]?.jsonPrimitive?.contentOrNull,
                        spanId = record["spanId"]?.jsonPrimitive?.contentOrNull,
                        tags = HashMap(attributes),
                        resourceAttributes = HashMap(resourceAttrs)
                    )
                    entries += entry
                }
            }
        }

        return entries
    }

    private fun attributesToMap(attributes: JsonElement?): Map<String, String> {
        val array = attributes as? JsonArray ?: return emptyMap()
        return array.mapNotNull { attributeElement ->
            val attribute = attributeElement.jsonObject
            val key = attribute["key"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val value = extractAnyValue(attribute["value"]) ?: return@mapNotNull null
            key to value
        }.toMap()
    }

    private fun extractAnyValue(anyValue: JsonElement?): String? {
        val obj = anyValue as? JsonObject ?: return anyValue?.jsonPrimitive?.contentOrNull
        return when {
            obj.containsKey("stringValue") -> obj["stringValue"]?.jsonPrimitive?.contentOrNull
            obj.containsKey("intValue") -> obj["intValue"]?.jsonPrimitive?.contentOrNull
            obj.containsKey("doubleValue") -> obj["doubleValue"]?.jsonPrimitive?.contentOrNull
            obj.containsKey("boolValue") -> obj["boolValue"]?.jsonPrimitive?.contentOrNull
            obj.containsKey("bytesValue") -> obj["bytesValue"]?.jsonPrimitive?.contentOrNull
            obj.containsKey("arrayValue") -> obj["arrayValue"]?.toString()
            obj.containsKey("kvlistValue") -> obj["kvlistValue"]?.toString()
            else -> null
        }
    }

    private fun parseTimeToMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()

        trimmed.toLongOrNull()?.let { numeric ->
            return if (numeric > 1_000_000_000_000L) numeric else numeric * 1000
        }

        return try {
            Instant.parse(trimmed).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    private fun buildScopeFilter(projectId: Long, systemId: String?): String? {
        val rawSystemId = systemId?.trim()
        if (rawSystemId.isNullOrEmpty()) {
            return "project_id = $projectId"
        }

        val parsed = try {
            UUID.fromString(rawSystemId)
        } catch (_: Exception) {
            return null
        }

        return "system_id = toUUID('${parsed}')"
    }

    private fun resolveTimestampMs(timestamp: String?, timestampMs: Long?): Long {
        return timestampMs
            ?: parseTimeToMillis(timestamp)
            ?: System.currentTimeMillis()
    }

    private fun normalizeLevel(level: String?): String {
        return when (level?.trim()?.lowercase()) {
            "trace" -> "trace"
            "debug" -> "debug"
            "warn", "warning" -> "warn"
            "error" -> "error"
            "fatal", "critical", "panic" -> "fatal"
            else -> "info"
        }
    }

    private fun normalizeSource(source: String): String {
        return when (source.trim().lowercase()) {
            "sdk" -> "sdk"
            "agent_stdout" -> "agent_stdout"
            "agent_stderr" -> "agent_stderr"
            "otlp" -> "otlp"
            else -> "sdk"
        }
    }

    private fun sanitizeMap(input: Map<String, String>?): Map<String, String> {
        if (input == null || input.isEmpty()) return emptyMap()
        return input
            .mapNotNull { (rawKey, rawValue) ->
                val key = rawKey.trim().take(128)
                if (key.isBlank()) return@mapNotNull null
                key to rawValue.trim().take(1024)
            }
            .toMap()
    }

    private fun trimTo(value: String, maxLength: Int): String {
        return if (value.length <= maxLength) value else value.take(maxLength)
    }

    private fun mapToSqlMap(value: Map<String, String>?): String {
        if (value == null || value.isEmpty()) return "map()"
        val pairs = value.entries
            .sortedBy { it.key }
            .joinToString(", ") { (key, mapValue) ->
                "'${escapeSql(key)}', '${escapeSql(mapValue)}'"
            }
        return "map($pairs)"
    }

    private fun escapeSql(value: String?): String {
        if (value == null) return ""
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
    }

    private fun encodeCursor(timestampMs: Long, logId: String): String {
        val raw = "$timestampMs|$logId"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())
    }

    private fun decodeCursor(cursor: String?): Pair<Long, String>? {
        if (cursor.isNullOrBlank()) return null
        return try {
            val decoded = String(Base64.getUrlDecoder().decode(cursor))
            val parts = decoded.split("|", limit = 2)
            if (parts.size != 2) return null
            val ts = parts[0].toLongOrNull() ?: return null
            val logId = parts[1]
            ts to logId
        } catch (_: Exception) {
            null
        }
    }
}
