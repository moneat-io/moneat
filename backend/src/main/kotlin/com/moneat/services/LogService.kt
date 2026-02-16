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
import com.moneat.config.RedisConfig
import com.moneat.models.*
import com.moneat.utils.ClickHouseSqlUtils
import com.moneat.utils.ClickHouseQueryUtils
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.time.Instant
import java.util.*

private val logger = KotlinLogging.logger {}

// Top-level fields that should not be searched in tags map
private val topLevelFields = setOf(
    "service", "environment", "host", "source", "level", "message", "body",
    "container_name", "container_id", "container_image", "trace_id", "span_id", "status"
)

private data class LogWithCursor(
    val log: LogEntryResponse,
    val timestampMs: Long
)

class LogService {
    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val json = Json { ignoreUnknownKeys = true }
    private val usageTracking = UsageTrackingService.instance
    private val queryParser = LogQueryParser()

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
            // Use Datadog-compatible query parser
            try {
                val parsed = queryParser.parse(request.query)
                if (parsed.rootNode != null) {
                    val queryCondition = queryParser.toClickHouseSql(parsed.rootNode, ::escapeSql)
                    if (queryCondition.isNotBlank() && queryCondition != "1=1") {
                        logger.info { "Generated query condition from '${request.query}': $queryCondition" }
                        conditions += "($queryCondition)"
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to parse query '${request.query}', falling back to simple search" }
                // Fallback: treat as simple full-text search
                conditions += buildSimpleSearchCondition(request.query)
            }
        }

        request.tags.forEach { (key, value) ->
            val condition = buildTagCondition(key, value)
            if (condition.isNotBlank()) {
                conditions += condition
            }
        }

        // Add exclude filters
        if (!request.excludeService.isNullOrBlank()) {
            conditions += "service != '${escapeSql(request.excludeService)}'"
        }

        if (!request.excludeEnvironment.isNullOrBlank()) {
            conditions += "environment != '${escapeSql(request.excludeEnvironment)}'"
        }
        
        if (!request.excludeContainerName.isNullOrBlank()) {
            conditions += "container_name != '${escapeSql(request.excludeContainerName)}'"
        }

        request.excludeTags.forEach { (key, value) ->
            val condition = buildTagCondition(key, value)
            if (condition.isNotBlank()) {
                // Negate the condition by wrapping in NOT
                conditions += "NOT ($condition)"
            }
        }

        decodeCursor(request.cursor)?.let { (cursorTs, cursorLogId) ->
            conditions += "(timestamp < fromUnixTimestamp64Milli($cursorTs) OR (timestamp = fromUnixTimestamp64Milli($cursorTs) AND log_id < toUUID('${escapeSql(cursorLogId)}')))"
        }

        val whereClause = conditions.joinToString(" AND ")
        
        // Log the complete WHERE clause for debugging (at DEBUG level to avoid logging user data in production)
        logger.debug { "Executing log query with WHERE clause: $whereClause" }
        
        val query = """
            SELECT
                toString(log_id) AS log_id,
                formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S.%fZ') AS timestamp_formatted,
                toString(level) AS level_text,
                message,
                body,
                service,
                environment,
                host,
                toString(source) AS source_text,
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
            logger.error("ClickHouse query failed. WHERE clause: $whereClause")
            logger.error("Full query: $query")
            throw IllegalStateException("Failed to query logs: ${body.take(1000)}")
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

    fun autoInterval(fromMs: Long?, toMs: Long?): String {
        if (fromMs == null || toMs == null) return "1h"
        val rangeMs = toMs - fromMs
        return when {
            rangeMs <= 3_600_000L -> "1m"           // ≤1h → 1m
            rangeMs <= 21_600_000L -> "5m"          // ≤6h → 5m
            rangeMs <= 86_400_000L -> "15m"         // ≤24h → 15m
            rangeMs <= 604_800_000L -> "1h"         // ≤7d → 1h
            else -> "1d"                            // >7d → 1d
        }
    }

    private fun intervalToClickHouse(interval: String): String {
        return when (interval) {
            "1m" -> "1 MINUTE"
            "5m" -> "5 MINUTE"
            "15m" -> "15 MINUTE"
            "1h" -> "1 HOUR"
            "1d" -> "1 DAY"
            else -> "1 HOUR"
        }
    }

    suspend fun aggregateLogs(
        projectId: Long,
        from: String?,
        to: String?,
        interval: String?,
        query: String?,
        levels: List<String>,
        service: String?,
        environment: String?,
        tags: Map<String, String>,
        groupBy: String?
    ): LogAggregateResponse {
        val fromMs = parseTimeToMillis(from)
        val toMs = parseTimeToMillis(to) ?: System.currentTimeMillis()
        val resolvedInterval = if (interval.isNullOrBlank() || interval == "auto") {
            autoInterval(fromMs, toMs)
        } else interval
        val chInterval = intervalToClickHouse(resolvedInterval)

        val conditions = mutableListOf("project_id = $projectId")
        if (fromMs != null) conditions += "timestamp >= fromUnixTimestamp64Milli($fromMs)"
        conditions += "timestamp <= fromUnixTimestamp64Milli($toMs)"

        if (!service.isNullOrBlank()) conditions += "service = '${escapeSql(service)}'"
        if (!environment.isNullOrBlank()) conditions += "environment = '${escapeSql(environment)}'"
        val normalizedLevels = levels.map { normalizeLevel(it) }.filter { it.isNotBlank() }.distinct()
        if (normalizedLevels.isNotEmpty()) {
            val inClause = normalizedLevels.joinToString(",") { "'${escapeSql(it)}'" }
            conditions += "level IN ($inClause)"
        }
        if (!query.isNullOrBlank()) {
            // Use Datadog-compatible query parser
            try {
                val parsed = queryParser.parse(query)
                if (parsed.rootNode != null) {
                    val queryCondition = queryParser.toClickHouseSql(parsed.rootNode, ::escapeSql)
                    if (queryCondition.isNotBlank() && queryCondition != "1=1") {
                        conditions += "($queryCondition)"
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to parse query '$query', falling back to simple search" }
                // Fallback: treat as simple full-text search
                conditions += buildSimpleSearchCondition(query)
            }
        }
        tags.forEach { (key, value) ->
            val condition = buildTagCondition(key, value)
            if (condition.isNotBlank()) {
                conditions += condition
            }
        }

        val whereClause = conditions.joinToString(" AND ")

        val validGroupBy = groupBy?.takeIf { it in setOf("level", "service", "environment") }

        val sql = if (validGroupBy != null) {
            """
            SELECT toStartOfInterval(timestamp, INTERVAL $chInterval) AS bucket,
                   $validGroupBy AS group_value,
                   count() AS cnt
            FROM $clickhouseDb.logs
            WHERE $whereClause
            GROUP BY bucket, group_value
            ORDER BY bucket
            FORMAT JSONEachRow
            """.trimIndent()
        } else {
            """
            SELECT toStartOfInterval(timestamp, INTERVAL $chInterval) AS bucket,
                   count() AS cnt
            FROM $clickhouseDb.logs
            WHERE $whereClause
            GROUP BY bucket
            ORDER BY bucket
            FORMAT JSONEachRow
            """.trimIndent()
        }

        val response = ClickHouseClient.execute(sql)
        val body = response.bodyAsText()
        if (!response.status.isSuccess() || body.trimStart().startsWith("Code:")) {
            logger.warn { "Failed to aggregate logs: ${body.take(600)}" }
            return LogAggregateResponse(buckets = emptyList(), totalCount = 0, interval = resolvedInterval)
        }

        val bucketMap = LinkedHashMap<String, MutableMap<String, Long>>()
        var totalCount = 0L

        body.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
            try {
                val obj = json.parseToJsonElement(line).jsonObject
                val bucketTs = obj["bucket"]?.jsonPrimitive?.content ?: return@forEach
                val cnt = obj["cnt"]?.jsonPrimitive?.longOrNull ?: 0L
                totalCount += cnt

                val groups = bucketMap.getOrPut(bucketTs) { mutableMapOf() }
                if (validGroupBy != null) {
                    val groupValue = obj["group_value"]?.jsonPrimitive?.content ?: "unknown"
                    groups[groupValue] = (groups[groupValue] ?: 0L) + cnt
                } else {
                    groups["_total"] = (groups["_total"] ?: 0L) + cnt
                }
            } catch (_: Exception) {}
        }

        val buckets = bucketMap.map { (ts, groups) ->
            val count = groups.values.sum()
            LogAggregateBucket(
                timestamp = ts,
                count = count,
                groups = if (validGroupBy != null) groups else emptyMap()
            )
        }

        return LogAggregateResponse(buckets = buckets, totalCount = totalCount, interval = resolvedInterval)
    }

    suspend fun topValues(
        projectId: Long,
        field: String,
        limit: Int,
        from: String?,
        to: String?,
        query: String?,
        levels: List<String>,
        service: String?,
        environment: String?,
        tags: Map<String, String>
    ): LogTopResponse {
        val conditions = mutableListOf("project_id = $projectId")
        val fromMs = parseTimeToMillis(from)
        if (fromMs != null) conditions += "timestamp >= fromUnixTimestamp64Milli($fromMs)"
        val toMs = parseTimeToMillis(to)
        if (toMs != null) conditions += "timestamp <= fromUnixTimestamp64Milli($toMs)"
        if (!service.isNullOrBlank()) conditions += "service = '${escapeSql(service)}'"
        if (!environment.isNullOrBlank()) conditions += "environment = '${escapeSql(environment)}'"
        val normalizedLevels = levels.map { normalizeLevel(it) }.filter { it.isNotBlank() }.distinct()
        if (normalizedLevels.isNotEmpty()) {
            val inClause = normalizedLevels.joinToString(",") { "'${escapeSql(it)}'" }
            conditions += "level IN ($inClause)"
        }
        if (!query.isNullOrBlank()) {
            // Use Datadog-compatible query parser
            try {
                val parsed = queryParser.parse(query)
                if (parsed.rootNode != null) {
                    val queryCondition = queryParser.toClickHouseSql(parsed.rootNode, ::escapeSql)
                    if (queryCondition.isNotBlank() && queryCondition != "1=1") {
                        conditions += "($queryCondition)"
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to parse query '$query', falling back to simple search" }
                // Fallback: treat as simple full-text search
                conditions += buildSimpleSearchCondition(query)
            }
        }
        tags.forEach { (key, value) ->
            val condition = buildTagCondition(key, value)
            if (condition.isNotBlank()) {
                conditions += condition
            }
        }

        val whereClause = conditions.joinToString(" AND ")
        val safeLimit = limit.coerceIn(1, 100)

        // Determine the SQL column expression for the field
        val columnExpr = when (field) {
            "service", "level", "environment", "host", "container_name" -> field
            else -> {
                // Treat as tag key
                val escapedKey = escapeSql(field)
                "tags['$escapedKey']"
            }
        }

        val sql = """
            SELECT $columnExpr AS field_value, count() AS cnt
            FROM $clickhouseDb.logs
            WHERE $whereClause AND $columnExpr != ''
            GROUP BY field_value
            ORDER BY cnt DESC
            LIMIT $safeLimit
            FORMAT JSONEachRow
        """.trimIndent()

        val response = ClickHouseClient.execute(sql)
        val body = response.bodyAsText()
        if (!response.status.isSuccess() || body.trimStart().startsWith("Code:")) {
            logger.warn { "Failed to query top values: ${body.take(600)}" }
            return LogTopResponse(field = field, values = emptyList(), totalCount = 0)
        }

        val values = mutableListOf<LogTopValue>()
        body.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
            try {
                val obj = json.parseToJsonElement(line).jsonObject
                val value = obj["field_value"]?.jsonPrimitive?.content ?: return@forEach
                val cnt = obj["cnt"]?.jsonPrimitive?.longOrNull ?: 0L
                values += LogTopValue(value = value, count = cnt)
            } catch (_: Exception) {}
        }

        // Get total count for percentage calculation
        val totalSql = """
            SELECT count() AS cnt FROM $clickhouseDb.logs WHERE $whereClause
            FORMAT JSONEachRow
        """.trimIndent()
        val totalResponse = ClickHouseClient.execute(totalSql)
        val totalBody = totalResponse.bodyAsText()
        val totalCount = try {
            json.parseToJsonElement(totalBody.trim()).jsonObject["cnt"]?.jsonPrimitive?.longOrNull ?: 0L
        } catch (_: Exception) { 0L }

        return LogTopResponse(field = field, values = values, totalCount = totalCount)
    }

    suspend fun exportCsv(
        projectId: Long,
        from: String?,
        to: String?,
        query: String?,
        levels: List<String>,
        service: String?,
        environment: String?,
        tags: Map<String, String>,
        limit: Int
    ): String {
        val conditions = mutableListOf("project_id = $projectId")
        val fromMs = parseTimeToMillis(from)
        if (fromMs != null) conditions += "timestamp >= fromUnixTimestamp64Milli($fromMs)"
        val toMs = parseTimeToMillis(to)
        if (toMs != null) conditions += "timestamp <= fromUnixTimestamp64Milli($toMs)"
        if (!service.isNullOrBlank()) conditions += "service = '${escapeSql(service)}'"
        if (!environment.isNullOrBlank()) conditions += "environment = '${escapeSql(environment)}'"
        val normalizedLevels = levels.map { normalizeLevel(it) }.filter { it.isNotBlank() }.distinct()
        if (normalizedLevels.isNotEmpty()) {
            val inClause = normalizedLevels.joinToString(",") { "'${escapeSql(it)}'" }
            conditions += "level IN ($inClause)"
        }
        if (!query.isNullOrBlank()) {
            // Use Datadog-compatible query parser
            try {
                val parsed = queryParser.parse(query)
                if (parsed.rootNode != null) {
                    val queryCondition = queryParser.toClickHouseSql(parsed.rootNode, ::escapeSql)
                    if (queryCondition.isNotBlank() && queryCondition != "1=1") {
                        conditions += "($queryCondition)"
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to parse query '$query', falling back to simple search" }
                // Fallback: treat as simple full-text search
                conditions += buildSimpleSearchCondition(query)
            }
        }
        tags.forEach { (key, value) ->
            val condition = buildTagCondition(key, value)
            if (condition.isNotBlank()) {
                conditions += condition
            }
        }

        val whereClause = conditions.joinToString(" AND ")
        val safeLimit = limit.coerceIn(1, 10_000)

        val sql = """
            SELECT
                formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S.%fZ') AS timestamp,
                level, service, environment, host, message, body,
                container_name, trace_id, span_id,
                toJSONString(tags) AS tags
            FROM $clickhouseDb.logs
            WHERE $whereClause
            ORDER BY timestamp DESC
            LIMIT $safeLimit
            FORMAT JSONEachRow
        """.trimIndent()

        val response = ClickHouseClient.execute(sql)
        val body = response.bodyAsText()
        if (!response.status.isSuccess() || body.trimStart().startsWith("Code:")) {
            throw IllegalStateException("Failed to export logs: ${body.take(600)}")
        }

        val sb = StringBuilder()
        sb.appendLine("timestamp,level,service,environment,host,message,container_name,trace_id,span_id,tags")

        body.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
            try {
                val obj = json.parseToJsonElement(line).jsonObject
                val csvRow = listOf(
                    obj["timestamp"]?.jsonPrimitive?.content ?: "",
                    obj["level"]?.jsonPrimitive?.content ?: "",
                    obj["service"]?.jsonPrimitive?.content ?: "",
                    obj["environment"]?.jsonPrimitive?.content ?: "",
                    obj["host"]?.jsonPrimitive?.content ?: "",
                    obj["message"]?.jsonPrimitive?.content ?: "",
                    obj["container_name"]?.jsonPrimitive?.content ?: "",
                    obj["trace_id"]?.jsonPrimitive?.content ?: "",
                    obj["span_id"]?.jsonPrimitive?.content ?: "",
                    obj["tags"]?.jsonPrimitive?.content ?: "{}"
                ).joinToString(",") { csvEscape(it) }
                sb.appendLine(csvRow)
            } catch (_: Exception) {}
        }

        return sb.toString()
    }

    private fun csvEscape(value: String): String {
        if (value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')) {
            return "\"${value.replace("\"", "\"\"")}\""
        }
        return value
    }

    suspend fun getFilterOptionsWithCounts(projectId: Long, from: String?, to: String?): LogFilterOptionsWithCountsResponse {
        val conditions = mutableListOf("project_id = $projectId")
        val fromMs = parseTimeToMillis(from)
        if (fromMs != null) conditions += "timestamp >= fromUnixTimestamp64Milli($fromMs)"
        val toMs = parseTimeToMillis(to)
        if (toMs != null) conditions += "timestamp <= fromUnixTimestamp64Milli($toMs)"
        val whereClause = conditions.joinToString(" AND ")

        val services = queryValueCounts(
            """
            SELECT service AS val, count() AS cnt
            FROM $clickhouseDb.logs
            WHERE $whereClause AND service != ''
            GROUP BY val ORDER BY cnt DESC LIMIT 200
            FORMAT JSONEachRow
            """.trimIndent()
        )

        val environments = queryValueCounts(
            """
            SELECT environment AS val, count() AS cnt
            FROM $clickhouseDb.logs
            WHERE $whereClause AND environment != ''
            GROUP BY val ORDER BY cnt DESC LIMIT 200
            FORMAT JSONEachRow
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

        return LogFilterOptionsWithCountsResponse(
            services = services,
            environments = environments,
            levels = listOf("trace", "debug", "info", "warn", "error", "fatal"),
            tagKeys = tagKeys
        )
    }

    private suspend fun queryValueCounts(query: String): List<LogFilterOptionWithCount> {
        val response = ClickHouseClient.execute(query)
        val body = response.bodyAsText()
        if (!response.status.isSuccess() || body.trimStart().startsWith("Code:")) {
            logger.warn { "Failed to query value counts: ${body.take(600)}" }
            return emptyList()
        }
        val results = mutableListOf<LogFilterOptionWithCount>()
        body.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
            try {
                val obj = json.parseToJsonElement(line).jsonObject
                val value = obj["val"]?.jsonPrimitive?.content ?: return@forEach
                val count = obj["cnt"]?.jsonPrimitive?.longOrNull ?: 0L
                results += LogFilterOptionWithCount(value = value, count = count)
            } catch (_: Exception) {}
        }
        return results
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

        // Map status to level
        val actualField = if (key == "status") "level" else key
        
        // Check if this is a top-level field or a tag
        val isTopLevelField = actualField in topLevelFields
        
        val conditions = mutableListOf("project_id = $projectId")
        
        // Only add has() check for actual tags, not top-level fields
        if (!isTopLevelField) {
            conditions += "has(tags, '$escapedKey')"
        }
        
        val fromMs = parseTimeToMillis(from)
        if (fromMs != null) {
            conditions += "timestamp >= fromUnixTimestamp64Milli($fromMs)"
        }
        val toMs = parseTimeToMillis(to)
        if (toMs != null) {
            conditions += "timestamp <= fromUnixTimestamp64Milli($toMs)"
        }
        val whereClause = conditions.joinToString(" AND ")

        // Build the SELECT query based on field type
        val query = if (isTopLevelField) {
            // For top-level fields, select directly from the column
            val enumFields = setOf("level", "source")
            val fieldRef = if (actualField in enumFields) "toString($actualField)" else actualField
            """
            SELECT DISTINCT $fieldRef AS tag_value
            FROM $clickhouseDb.logs
            WHERE $whereClause AND $fieldRef != ''
            ORDER BY tag_value
            LIMIT ${limit.coerceIn(1, 200)}
            FORMAT TSV
            """.trimIndent()
        } else {
            // For tags, access the tags map
            """
            SELECT DISTINCT tags['$escapedKey'] AS tag_value
            FROM $clickhouseDb.logs
            WHERE $whereClause AND tags['$escapedKey'] != ''
            ORDER BY tag_value
            LIMIT ${limit.coerceIn(1, 200)}
            FORMAT TSV
            """.trimIndent()
        }

        val values = queryDistinctLines(query)

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
                        level = normalizeLevel(obj["level_text"]?.jsonPrimitive?.content ?: obj["level"]?.jsonPrimitive?.content),
                        message = obj["message"]?.jsonPrimitive?.content ?: "",
                        body = obj["body"]?.jsonPrimitive?.content ?: "",
                        service = obj["service"]?.jsonPrimitive?.content ?: "",
                        environment = obj["environment"]?.jsonPrimitive?.content ?: "",
                        host = obj["host"]?.jsonPrimitive?.content ?: "",
                        source = normalizeSource(obj["source_text"]?.jsonPrimitive?.content ?: obj["source"]?.jsonPrimitive?.content ?: "sdk"),
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
            return ClickHouseQueryUtils.projectIdClause(projectId)
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
        return ClickHouseSqlUtils.escapeSql(value)
    }

    private fun buildSimpleSearchCondition(term: String): String {
        val escaped = ClickHouseSqlUtils.escapeLikePattern(term)
        val hasSeparators = term.any { it == '-' || it == '.' || it == '/' || it == ':' || it == ' ' }
        return if (hasSeparators) {
            "(message ILIKE '%$escaped%' OR body ILIKE '%$escaped%')"
        } else {
            val tokenEscaped = ClickHouseSqlUtils.escapeSql(term)
            "(hasTokenCaseInsensitive(message, '$tokenEscaped') OR hasTokenCaseInsensitive(body, '$tokenEscaped'))"
        }
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
    
    /**
     * Check if a tag key/value looks like it contains Boolean operators.
     * These should be in the query field instead.
     */
    private fun isTagMalformed(key: String, value: String): Boolean {
        // Check for Boolean operators with various spacing
        return key.contains(" OR", ignoreCase = true) ||
               key.contains("OR ", ignoreCase = true) ||
               key.contains(" AND", ignoreCase = true) ||
               key.contains("AND ", ignoreCase = true) ||
               key.startsWith("-") ||
               value.contains(" OR", ignoreCase = true) ||
               value.contains("OR ", ignoreCase = true) ||
               value.contains(" AND", ignoreCase = true) ||
               value.contains("AND ", ignoreCase = true)
    }
    
    /**
     * Build a SQL condition for a tag/field filter.
     * Checks if the key is a top-level field or an actual tag.
     */
    internal fun buildTagCondition(key: String, value: String): String {
        if (key.isBlank()) return ""
        
        // If the tag value contains Boolean operators, it's actually a query that was
        // mistakenly sent as a tag. Route it through the query parser instead of dropping it.
        if (isTagMalformed(key, value)) {
            logger.info { "Tag contains Boolean operators, parsing as query: $key:$value" }
            return try {
                val parsed = queryParser.parse("$key:$value")
                if (parsed.rootNode != null) {
                    queryParser.toClickHouseSql(parsed.rootNode, ::escapeSql)
                } else ""
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse malformed tag as query: $key:$value" }
                ""
            }
        }
        
        val escapedKey = escapeSql(key)
        val escapedValue = escapeSql(value)
        
        // Map status to level
        val actualField = if (key == "status") "level" else key
        
        // Enum8 columns need toString() cast for string comparison
        val enumFields = setOf("level", "source")
        
        // Check if this is a top-level field
        return if (actualField in topLevelFields) {
            // Use toString() for Enum8 fields
            val fieldRef = if (actualField in enumFields) "toString($actualField)" else actualField
            "$fieldRef = '$escapedValue'"
        } else {
            // Use has() for actual tags in the tags map
            "has(tags, '$escapedKey') AND tags['$escapedKey'] = '$escapedValue'"
        }
    }
}
