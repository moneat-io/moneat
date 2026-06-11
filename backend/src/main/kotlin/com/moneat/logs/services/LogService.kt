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

package com.moneat.logs.services

import kotlinx.serialization.SerializationException
import java.io.IOException

import com.google.protobuf.InvalidProtocolBufferException
import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.config.isClickHouseError
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueClient
import com.moneat.logs.models.AgentLogEntry
import com.moneat.logs.models.LogAggregateBucket
import com.moneat.logs.models.LogAggregateResponse
import com.moneat.logs.models.LogAnalyticsFilters
import com.moneat.logs.models.LogEntryResponse
import com.moneat.logs.models.LogFilterOptionWithCount
import com.moneat.logs.models.LogFilterOptionsResponse
import com.moneat.logs.models.LogFilterOptionsWithCountsResponse
import com.moneat.logs.models.LogIngestEntry
import com.moneat.logs.models.LogPatternRequest
import com.moneat.logs.models.LogPatternResponse
import com.moneat.logs.models.LogQueryRequest
import com.moneat.logs.models.LogQueryResponse
import com.moneat.logs.models.LogTagValuesResponse
import com.moneat.logs.models.LogTailFilters
import com.moneat.logs.models.LogTopResponse
import com.moneat.logs.models.LogTopValue
import com.moneat.logs.models.QueuedLogBatch
import com.moneat.logs.models.QueuedLogEntry
import com.moneat.logs.repositories.LogRepository
import com.moneat.otlp.OtlpParsingUtils
import com.moneat.otlp.OtlpProtobufParser
import com.moneat.otlp.ResourceContext
import com.moneat.shared.services.UsageTrackingService
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.ClickHouseSqlUtils
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.logs.v1.LogRecord
import io.opentelemetry.proto.logs.v1.ResourceLogs
import io.opentelemetry.proto.logs.v1.ScopeLogs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import java.security.MessageDigest
import java.time.Instant
import com.moneat.utils.suspendRunCatching
import java.util.Base64
import java.util.UUID
import kotlin.text.Charsets

private val logger = KotlinLogging.logger {}

// Top-level fields that should not be searched in tags map
private val topLevelFields =
    setOf(
        "service", "environment", "host", "source", "level", "message", "body",
        "container_name", "container_id", "container_image", "trace_id", "span_id", "status"
    )

private data class LogWithCursor(
    val log: LogEntryResponse,
    val timestampMs: Long
)

private data class LogQueryWhere(
    val conditions: List<String>,
    val parameters: Map<String, String>
)

private data class LogAnalyticsWhere(
    val conditions: List<String>,
    val parameters: Map<String, String>,
    val fromMs: Long?,
    val toMs: Long?
)

private data class AggregateParseResult(
    val buckets: List<LogAggregateBucket>,
    val totalCount: Long
)

private const val MAX_LOG_QUERY_LIMIT = 500
private const val MAX_TOP_VALUES_LIMIT = 100
private const val MAX_FILTER_VALUES_LIMIT = 200
private const val MAX_EXPORT_LIMIT = 10_000
private const val ERROR_BODY_PREVIEW_CHARS = 600
private const val WARN_BODY_PREVIEW_CHARS = 500
private const val MILLIS_PER_HOUR = 3_600_000L
private const val MILLIS_PER_6_HOURS = 21_600_000L
private const val MILLIS_PER_DAY = 86_400_000L
private const val MILLIS_PER_10_DAYS = 10 * MILLIS_PER_DAY
private const val MILLIS_PER_21_DAYS = 21 * MILLIS_PER_DAY
private const val MILLIS_PER_45_DAYS = 45 * MILLIS_PER_DAY
private const val MILLIS_PER_90_DAYS = 90 * MILLIS_PER_DAY
private const val MAX_LOG_MESSAGE_CHARS = 8192
private const val MAX_LOG_BODY_CHARS = 32768
private const val MAX_LOG_SERVICE_CHARS = 256
private const val MAX_LOG_ENVIRONMENT_CHARS = 128
private const val MAX_LOG_HOST_CHARS = 256
private const val MAX_LOG_CONTAINER_ID_CHARS = 128
private const val MAX_LOG_CONTAINER_IMAGE_CHARS = 512
private const val MAX_LOG_TRACE_ID_CHARS = 128
private const val MAX_LOG_SPAN_ID_CHARS = 128
private const val INGEST_KEY_MAX_CHARS = 128
private const val INGEST_VALUE_MAX_CHARS = 1024
private const val ERROR_BODY_LONG_CHARS = 1000
private const val MAX_LOG_CONTAINER_NAME_CHARS = 256
private const val MS_PER_SECOND = 1000
private const val UNIX_EPOCH_SECONDS_MAX_DIGITS = 10
private const val SQL_LOG_FINGERPRINT_HEX_CHARS = 16

private fun utf8Fingerprint(text: String, hexChars: Int = SQL_LOG_FINGERPRINT_HEX_CHARS): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { b -> "%02x".format(b) }.take(hexChars)
}

class LogService(private val logRepository: LogRepository) {
    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val json = Json { ignoreUnknownKeys = true }
    private val usageTracking = UsageTrackingService.instance
    private val queryParser = LogQueryParser()
    private val patternQuery = LogPatternQuery(logRepository)

    fun liveChannel(organizationId: Long): String = "log:live:$organizationId"

    suspend fun enqueueSdkLogs(
        organizationId: Long,
        entries: List<LogIngestEntry>,
        queueKey: String
    ): Int {
        val normalized = entries.mapNotNull { normalizeSdkEntry(it) }
        return enqueueNormalized(organizationId, null, null, "sdk", normalized, queueKey)
    }

    suspend fun enqueueAgentLogs(
        organizationId: Long,
        systemId: String?,
        entries: List<AgentLogEntry>,
        queueKey: String
    ): Int {
        val normalized = entries.mapNotNull { normalizeAgentEntry(it, systemId) }
        return enqueueNormalized(organizationId, systemId, null, "agent", normalized, queueKey)
    }

    suspend fun enqueueAgentLogs(
        organizationId: Long,
        hostId: Int,
        entries: List<AgentLogEntry>,
        queueKey: String
    ): Int {
        val normalized = entries.mapNotNull { normalizeAgentEntry(it, null) }
        return enqueueNormalized(organizationId, null, hostId, "agent", normalized, queueKey)
    }

    suspend fun enqueueOtlpLogs(
        organizationId: Long,
        body: String,
        queueKey: String
    ): Int {
        val parsed = parseOtlpJson(body)
        val normalized = parsed.mapNotNull { normalizeOtlpEntry(it) }
        return enqueueNormalized(organizationId, null, null, "otlp", normalized, queueKey)
    }

    fun estimateBillableBytes(entries: List<LogIngestEntry>): Long {
        return entries
            .mapNotNull { normalizeSdkEntry(it) }
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

        val rows =
            batch.logs.joinToString(",\n") { entry ->
                val tagsWithHost = batch.hostId?.let { id ->
                    (entry.tags + ("host_id" to id.toString()))
                } ?: entry.tags
                """
            (
                toUUID('${escapeSql(entry.logId)}'),
                ${batch.effectiveOrganizationId},
                ${entry.projectId ?: 0L},
                ${entry.projectId ?: 0L},
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
                ${mapToSqlMap(tagsWithHost)},
                ${mapToSqlMap(entry.resourceAttributes)},
                '${escapeSql(entry.indexName)}'
            )
                """.trimIndent()
            }

        val insert =
            """
            INSERT INTO `$clickhouseDb`.logs (
                log_id,
                organization_id,
                service_id,
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
                resource_attributes,
                index_name
            ) VALUES
            $rows
            """.trimIndent()

        val insertSuccess = logRepository.executeClickHouseInsert(insert)
        check(insertSuccess) {
            "Failed to insert logs into ClickHouse"
        }

        val totalBytes = batch.logs.sumOf { it.message.length + it.body.length }
        val orgIdLong = batch.effectiveOrganizationId
        if (orgIdLong in Int.MIN_VALUE..Int.MAX_VALUE) {
            usageTracking.recordOrgUsage(orgIdLong.toInt(), "log", totalBytes)
        } else {
            logger.error { "organizationId $orgIdLong out of Int range, skipping usage recording" }
        }

        return batch.logs.map { toResponse(it, batch.systemId, batch.hostId) }
    }

    suspend fun publishLiveLogs(
        organizationId: Long,
        logs: List<LogEntryResponse>
    ) {
        if (logs.isEmpty()) return
        val channel = liveChannel(organizationId)
        val redis = RedisConfig.sync()
        logs.forEach { log ->
            redis.publish(channel, json.encodeToString(log))
        }
    }

    fun parseLiveLog(payload: String): LogEntryResponse? {
        return suspendRunCatching {
            json.decodeFromString<LogEntryResponse>(payload)
        }.getOrElse { _ ->
            null
        }
    }

    fun matchesTailFilters(
        log: LogEntryResponse,
        filters: LogTailFilters
    ): Boolean =
        matchesIncludedTailFilters(log, filters) &&
            matchesExcludedTailFilters(log, filters) &&
            matchesTailTagFilters(log, filters) &&
            matchesTailQueryFilter(log, filters)

    private fun matchesIncludedTailFilters(
        log: LogEntryResponse,
        filters: LogTailFilters
    ): Boolean {
        if (filters.levels.isNotEmpty() && log.level.lowercase() !in filters.levels) {
            return false
        }
        if (!matchesOptionalIgnoreCase(log.service, filters.service)) {
            return false
        }
        if (!matchesOptionalIgnoreCase(log.environment, filters.environment)) {
            return false
        }
        if (!matchesOptionalIgnoreCase(log.containerName, filters.containerName)) {
            return false
        }
        return true
    }

    private fun matchesExcludedTailFilters(
        log: LogEntryResponse,
        filters: LogTailFilters
    ): Boolean {
        if (matchesExcludedIgnoreCase(log.service, filters.excludeService)) {
            return false
        }
        if (matchesExcludedIgnoreCase(log.environment, filters.excludeEnvironment)) {
            return false
        }
        if (matchesExcludedIgnoreCase(log.containerName, filters.excludeContainerName)) {
            return false
        }
        return true
    }

    private fun matchesTailTagFilters(
        log: LogEntryResponse,
        filters: LogTailFilters
    ): Boolean {
        if (filters.tags.any { (key, value) -> log.tags[key] != value }) {
            return false
        }
        if (filters.excludeTags.any { (key, value) -> log.tags[key] == value }) {
            return false
        }
        return true
    }

    private fun matchesTailQueryFilter(
        log: LogEntryResponse,
        filters: LogTailFilters
    ): Boolean {
        if (filters.query.isNullOrBlank()) return true
        val query = filters.query.lowercase()
        val haystack = "${log.message}\n${log.body}".lowercase()
        return haystack.contains(query)
    }

    private fun matchesOptionalIgnoreCase(actual: String, expected: String?): Boolean =
        expected.isNullOrBlank() || actual.lowercase() == expected.lowercase()

    private fun matchesExcludedIgnoreCase(actual: String, excluded: String?): Boolean =
        !excluded.isNullOrBlank() && actual.lowercase() == excluded.lowercase()

    suspend fun queryLogs(
        organizationId: Long,
        request: LogQueryRequest
    ): LogQueryResponse {
        val limit = request.limit.coerceIn(1, MAX_LOG_QUERY_LIMIT)
        val where = buildLogQueryConditions(organizationId, request) ?: return LogQueryResponse(
            logs = emptyList(),
            nextCursor = null,
            hasMore = false,
            totalCount = 0L
        )

        val whereClause = where.conditions.joinToString(" AND ")

        logger.debug {
            "Executing log query: where_fp=${utf8Fingerprint(whereClause)} " +
                "(where_len=${whereClause.length})"
        }

        val query =
            """
            SELECT
                toString(log_id) AS log_id_str,
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
            FROM `$clickhouseDb`.logs
            WHERE $whereClause
            ORDER BY timestamp DESC, toString(log_id) DESC
            LIMIT ${limit + 1}
            FORMAT JSONEachRow
            """.trimIndent()

        val body = logRepository.executeClickHouseQuery(query, where.parameters)
        if (body.isClickHouseError()) {
            logger.error(
                "ClickHouse query failed. where_fp=${utf8Fingerprint(whereClause)} " +
                    "query_fp=${utf8Fingerprint(query)}"
            )
            throw IllegalStateException("Failed to query logs: ${body.take(ERROR_BODY_LONG_CHARS)}")
        }

        val parsed = parseQueryRows(body)
        val hasMore = parsed.size > limit
        val pageRows = if (hasMore) parsed.take(limit) else parsed
        val nextCursor =
            pageRows.lastOrNull()?.let { row ->
                if (hasMore) encodeCursor(row.timestampMs, row.log.logId) else null
            }

        return LogQueryResponse(
            logs = pageRows.map { it.log },
            nextCursor = nextCursor,
            hasMore = hasMore,
            totalCount = null
        )
    }

    private suspend fun buildLogQueryConditions(
        organizationId: Long,
        request: LogQueryRequest
    ): LogQueryWhere? {
        val scopeFilter = buildScopeFilter(organizationId, request.systemId, request.hostId) ?: return null
        val parameters = mutableMapOf<String, String>()
        val conditions =
            buildList {
                add(scopeFilter)
                addLogTimeFilters(request)
                addLogIncludeFilters(request)
                addLogQueryFilter(request.query)
                addTagFilters(request.tags)
                addLogMessagePatternFilter(request.messagePattern, parameters)
                addLogTraceFilter(request.traceId)
                addLogExcludeFilters(request)
                addTagFilters(request.excludeTags, exclude = true)
                addLogCursorFilter(request.cursor)
            }
        return LogQueryWhere(conditions = conditions, parameters = parameters)
    }

    private fun MutableList<String>.addLogTimeFilters(request: LogQueryRequest) {
        parseTimeToMillis(request.from)?.let { fromMs ->
            add("timestamp >= fromUnixTimestamp64Milli($fromMs)")
        }
        parseTimeToMillis(request.to)?.let { toMs ->
            add("timestamp <= fromUnixTimestamp64Milli($toMs)")
        }
    }

    private fun MutableList<String>.addLogIncludeFilters(request: LogQueryRequest) {
        addIfPresent(request.service) { "service = '${escapeSql(it)}'" }
        addIfPresent(request.environment) { "environment = '${escapeSql(it)}'" }
        addIfPresent(request.host) { "host = '${escapeSql(it)}'" }
        addIfPresent(request.containerName) { "container_name = '${escapeSql(it)}'" }
        addLevelFilter(request.levels)
    }

    private suspend fun buildLogAnalyticsWhere(
        organizationId: Long,
        filters: LogAnalyticsFilters,
        defaultToNow: Boolean = false
    ): LogAnalyticsWhere {
        val fromMs = parseTimeToMillis(filters.from)
        val toMs = parseTimeToMillis(filters.to) ?: if (defaultToNow) System.currentTimeMillis() else null
        val parameters = mutableMapOf<String, String>()
        val conditions =
            buildList {
                add(ClickHouseQueryUtils.orgIdClause(organizationId))
                fromMs?.let { add("timestamp >= fromUnixTimestamp64Milli($it)") }
                toMs?.let { add("timestamp <= fromUnixTimestamp64Milli($it)") }
                addLogIncludeFilters(filters)
                addLogQueryFilter(filters.query)
                addTagFilters(filters.tags)
                addLogMessagePatternFilter(filters.messagePattern, parameters)
                addLogTraceFilter(filters.traceId)
                addLogExcludeFilters(filters)
                addTagFilters(filters.excludeTags, exclude = true)
            }
        return LogAnalyticsWhere(conditions = conditions, parameters = parameters, fromMs = fromMs, toMs = toMs)
    }

    private fun MutableList<String>.addLogIncludeFilters(filters: LogAnalyticsFilters) {
        addIfPresent(filters.service) { "service = '${escapeSql(it)}'" }
        addIfPresent(filters.environment) { "environment = '${escapeSql(it)}'" }
        addIfPresent(filters.host) { "host = '${escapeSql(it)}'" }
        addLevelFilter(filters.levels)
    }

    private fun MutableList<String>.addLevelFilter(levels: List<String>) {
        val normalizedLevels = levels.map { normalizeLevel(it) }.filter { it.isNotBlank() }.distinct()
        if (normalizedLevels.isEmpty()) return

        val inClause = normalizedLevels.joinToString(",") { "'${escapeSql(it)}'" }
        add("level IN ($inClause)")
    }

    private suspend fun MutableList<String>.addLogQueryFilter(query: String?) {
        if (query.isNullOrBlank()) return

        suspendRunCatching {
            queryParser.parse(query).rootNode?.let { rootNode ->
                val queryCondition = queryParser.toClickHouseSql(rootNode, ::escapeSql)
                if (queryCondition.isNotBlank() && queryCondition != "1=1") {
                    logger.info { "Generated query condition from '$query': $queryCondition" }
                    add("($queryCondition)")
                }
            }
        }.getOrElse { e ->
            logger.error(e) {
                "Failed to parse query (query_fp=${utf8Fingerprint(query)}), falling back to simple search"
            }
            add(buildSimpleSearchCondition(query))
        }
    }

    private fun MutableList<String>.addTagFilters(tags: Map<String, String>, exclude: Boolean = false) {
        tags.forEach { (key, value) ->
            val condition = buildTagCondition(key, value, exclude = exclude)
            if (condition.isNotBlank()) {
                add(condition)
            }
        }
    }

    private fun MutableList<String>.addLogMessagePatternFilter(
        messagePattern: String?,
        parameters: MutableMap<String, String>
    ) {
        val normalizedPattern = normalizeLogMessagePattern(messagePattern)
        if (normalizedPattern.isBlank()) return

        add(logMessagePatternCondition(normalizedPattern))
        parameters[LOG_MESSAGE_PATTERN_PARAMETER] = messagePatternLikeParameter(normalizedPattern)
    }

    private fun MutableList<String>.addLogTraceFilter(traceId: String?) {
        addIfPresent(traceId) { "trace_id = '${escapeSql(it)}'" }
    }

    private fun MutableList<String>.addLogExcludeFilters(request: LogQueryRequest) {
        addIfPresent(request.excludeService) { "service != '${escapeSql(it)}'" }
        addIfPresent(request.excludeEnvironment) { "environment != '${escapeSql(it)}'" }
        addIfPresent(request.excludeContainerName) { "container_name != '${escapeSql(it)}'" }
    }

    private fun MutableList<String>.addLogExcludeFilters(filters: LogAnalyticsFilters) {
        addIfPresent(filters.excludeService) { "service != '${escapeSql(it)}'" }
        addIfPresent(filters.excludeEnvironment) { "environment != '${escapeSql(it)}'" }
        addIfPresent(filters.excludeContainerName) { "container_name != '${escapeSql(it)}'" }
    }

    private fun MutableList<String>.addLogCursorFilter(cursor: String?) {
        decodeCursor(cursor)?.let { (cursorTs, cursorLogId) ->
            add(
                "(timestamp < fromUnixTimestamp64Milli($cursorTs) OR " +
                    "(timestamp = fromUnixTimestamp64Milli($cursorTs) AND " +
                    "toString(log_id) < '${escapeSql(cursorLogId)}'))"
            )
        }
    }

    private fun MutableList<String>.addIfPresent(value: String?, condition: (String) -> String) {
        if (!value.isNullOrBlank()) {
            add(condition(value))
        }
    }

    suspend fun getLogPattern(
        organizationId: Long,
        request: LogPatternRequest
    ): LogPatternResponse = patternQuery.getLogPattern(organizationId, request)

    fun autoInterval(
        fromMs: Long?,
        toMs: Long?
    ): String {
        if (fromMs == null || toMs == null) return "1h"
        val rangeMs = toMs - fromMs
        return when {
            rangeMs <= MILLIS_PER_HOUR -> "1m"
            rangeMs <= MILLIS_PER_6_HOURS -> "5m"
            rangeMs <= MILLIS_PER_DAY -> "15m"
            rangeMs <= MILLIS_PER_10_DAYS -> "1h"
            rangeMs <= MILLIS_PER_21_DAYS -> "2h"
            rangeMs <= MILLIS_PER_45_DAYS -> "4h"
            rangeMs <= MILLIS_PER_90_DAYS -> "12h"
            else -> "1d"
        }
    }

    private fun intervalToClickHouse(interval: String): String {
        return when (interval) {
            "1m" -> "1 MINUTE"
            "5m" -> "5 MINUTE"
            "15m" -> "15 MINUTE"
            "1h" -> "1 HOUR"
            "2h" -> "2 HOUR"
            "4h" -> "4 HOUR"
            "12h" -> "12 HOUR"
            "1d" -> "1 DAY"
            else -> "1 HOUR"
        }
    }

    suspend fun aggregateLogs(
        organizationId: Long,
        filters: LogAnalyticsFilters,
        interval: String?,
        groupBy: String?
    ): LogAggregateResponse {
        val where = buildLogAnalyticsWhere(organizationId, filters, defaultToNow = true)
        val fromMs = where.fromMs
        val toMs = where.toMs ?: System.currentTimeMillis()
        val resolvedInterval =
            if (interval.isNullOrBlank() || interval == "auto") {
                autoInterval(fromMs, toMs)
            } else {
                interval
            }
        val chInterval = intervalToClickHouse(resolvedInterval)

        val whereClause = where.conditions.joinToString(" AND ")

        val validGroupBy = groupBy?.takeIf { it in setOf("level", "service", "environment") }

        val sql =
            if (validGroupBy != null) {
                """
            SELECT formatDateTime(
                       toStartOfInterval(timestamp, INTERVAL $chInterval),
                       '%Y-%m-%dT%H:%i:%SZ',
                       'UTC'
                   ) AS bucket,
                   $validGroupBy AS group_value,
                   count() AS cnt
            FROM `$clickhouseDb`.logs
            WHERE $whereClause
            GROUP BY bucket, group_value
            ORDER BY bucket
            FORMAT JSONEachRow
                """.trimIndent()
            } else {
                """
            SELECT formatDateTime(
                       toStartOfInterval(timestamp, INTERVAL $chInterval),
                       '%Y-%m-%dT%H:%i:%SZ',
                       'UTC'
                   ) AS bucket,
                   count() AS cnt
            FROM `$clickhouseDb`.logs
            WHERE $whereClause
            GROUP BY bucket
            ORDER BY bucket
            FORMAT JSONEachRow
                """.trimIndent()
            }

        logger.debug {
            "Aggregate logs SQL for org $organizationId (fromMs=$fromMs, toMs=$toMs, interval=$chInterval, " +
                "groupBy=$validGroupBy): query_fp=${utf8Fingerprint(sql)}"
        }
        val body = logRepository.executeClickHouseQuery(sql, where.parameters)
        logger.debug { "Aggregate logs response body (first 500 chars): ${body.take(WARN_BODY_PREVIEW_CHARS)}" }
        if (body.isClickHouseError()) {
            logger.warn { "Failed to aggregate logs: ${body.take(ERROR_BODY_PREVIEW_CHARS)}" }
            return LogAggregateResponse(buckets = emptyList(), totalCount = 0, interval = resolvedInterval)
        }

        val parsed = parseAggregateRows(body, validGroupBy)

        logger.debug {
            "Aggregate logs result for org $organizationId: ${parsed.buckets.size} buckets, " +
                "totalCount=${parsed.totalCount}, " +
                "interval=$resolvedInterval"
        }
        return LogAggregateResponse(
            buckets = parsed.buckets,
            totalCount = parsed.totalCount,
            interval = resolvedInterval
        )
    }

    private fun parseAggregateRows(
        body: String,
        validGroupBy: String?
    ): AggregateParseResult {
        val bucketMap = LinkedHashMap<String, MutableMap<String, Long>>()
        var totalCount = 0L

        body.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
            parseAggregateRow(line, validGroupBy, bucketMap)?.let { totalCount += it }
        }

        val buckets =
            bucketMap.map { (ts, groups) ->
                val count = groups.values.sum()
                LogAggregateBucket(
                    timestamp = ts,
                    count = count,
                    groups = if (validGroupBy != null) groups else emptyMap()
                )
            }
        return AggregateParseResult(buckets = buckets, totalCount = totalCount)
    }

    private fun parseAggregateRow(
        line: String,
        validGroupBy: String?,
        bucketMap: LinkedHashMap<String, MutableMap<String, Long>>
    ): Long? =
        suspendRunCatching {
            val obj = json.parseToJsonElement(line).jsonObject
            val bucketTs = obj["bucket"]?.jsonPrimitive?.content ?: return@suspendRunCatching null
            val cnt = obj["cnt"]?.jsonPrimitive?.longOrNull ?: 0L
            val groups = bucketMap.getOrPut(bucketTs) { mutableMapOf() }
            if (validGroupBy != null) {
                val groupValue = obj["group_value"]?.jsonPrimitive?.content ?: "unknown"
                groups[groupValue] = (groups[groupValue] ?: 0L) + cnt
            } else {
                groups["_total"] = (groups["_total"] ?: 0L) + cnt
            }
            cnt
        }.getOrElse { null }

    suspend fun topValues(
        organizationId: Long,
        field: String,
        limit: Int,
        filters: LogAnalyticsFilters
    ): LogTopResponse {
        val where = buildLogAnalyticsWhere(organizationId, filters)
        val whereClause = where.conditions.joinToString(" AND ")
        val safeLimit = limit.coerceIn(1, MAX_TOP_VALUES_LIMIT)

        val columnExpr = topValueColumnExpression(field)

        val sql =
            """
            SELECT $columnExpr AS field_value, count() AS cnt
            FROM `$clickhouseDb`.logs
            WHERE $whereClause AND $columnExpr != ''
            GROUP BY field_value
            ORDER BY cnt DESC
            LIMIT $safeLimit
            FORMAT JSONEachRow
            """.trimIndent()

        val body = logRepository.executeClickHouseQuery(sql, where.parameters)
        if (body.isClickHouseError()) {
            logger.warn { "Failed to query top values: ${body.take(ERROR_BODY_PREVIEW_CHARS)}" }
            return LogTopResponse(field = field, values = emptyList(), totalCount = 0)
        }

        val values = parseTopValueRows(body)

        // Get total count for percentage calculation
        val totalSql =
            """
            SELECT count() AS cnt FROM `$clickhouseDb`.logs WHERE $whereClause
            FORMAT JSONEachRow
            """.trimIndent()
        val totalCount = parseCountResponse(logRepository.executeClickHouseQuery(totalSql, where.parameters))

        return LogTopResponse(field = field, values = values, totalCount = totalCount)
    }

    private fun topValueColumnExpression(field: String): String =
        when (field) {
            "service", "service_name" -> "service"
            "message" -> "message"
            "level", "environment", "host", "container_name" -> field
            else -> {
                val escapedKey = escapeSql(field)
                "tags['$escapedKey']"
            }
        }

    private fun parseTopValueRows(body: String): List<LogTopValue> =
        body
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                suspendRunCatching {
                    val obj = json.parseToJsonElement(line).jsonObject
                    val value = obj["field_value"]?.jsonPrimitive?.content ?: return@suspendRunCatching null
                    LogTopValue(value = value, count = obj["cnt"]?.jsonPrimitive?.longOrNull ?: 0L)
                }.getOrElse { null }
            }.toList()

    private fun parseCountResponse(body: String): Long =
        suspendRunCatching {
            json
                .parseToJsonElement(body.trim())
                .jsonObject["cnt"]
                ?.jsonPrimitive
                ?.longOrNull ?: 0L
        }.getOrElse { 0L }

    suspend fun exportCsv(
        organizationId: Long,
        filters: LogAnalyticsFilters,
        limit: Int
    ): String {
        val where = buildLogAnalyticsWhere(organizationId, filters)
        val whereClause = where.conditions.joinToString(" AND ")
        val safeLimit = limit.coerceIn(1, MAX_EXPORT_LIMIT)

        val sql =
            """
            SELECT
                timestamp,
                level, service, environment, host, message, body,
                container_name, trace_id, span_id,
                toJSONString(tags) AS tags
            FROM `$clickhouseDb`.logs
            WHERE $whereClause
            ORDER BY timestamp DESC
            LIMIT $safeLimit
            FORMAT JSONEachRow
            """.trimIndent()

        logger.debug { "Export CSV SQL: query_fp=${utf8Fingerprint(sql)}" }
        val body = logRepository.executeClickHouseQuery(sql, where.parameters)
        if (body.isClickHouseError()) {
            logger.error {
                "ClickHouse export error. query_fp=${utf8Fingerprint(sql)}\n" +
                    "Error: ${body.take(ERROR_BODY_PREVIEW_CHARS)}"
            }
            throw IllegalStateException("Failed to export logs: ${body.take(ERROR_BODY_PREVIEW_CHARS)}")
        }

        val sb = StringBuilder()
        sb.appendLine("timestamp,level,service,environment,host,message,container_name,trace_id,span_id,tags")

        body.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
            suspendRunCatching {
                val obj = json.parseToJsonElement(line).jsonObject
                val timestampStr = obj["timestamp"]?.jsonPrimitive?.content ?: ""
                val csvRow =
                    listOf(
                        timestampStr,
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
            }.getOrElse { e ->
                logger.warn(e) { "Failed to parse log line for CSV: $line" }
            }
        }

        return sb.toString()
    }

    private fun csvEscape(value: String): String {
        if (value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')) {
            return "\"${value.replace("\"", "\"\"")}\""
        }
        return value
    }

    suspend fun getFilterOptionsWithCounts(
        organizationId: Long,
        from: String?,
        to: String?
    ): LogFilterOptionsWithCountsResponse {
        val conditions = mutableListOf(ClickHouseQueryUtils.orgIdClause(organizationId))
        val fromMs = parseTimeToMillis(from)
        if (fromMs != null) conditions += "timestamp >= fromUnixTimestamp64Milli($fromMs)"
        val toMs = parseTimeToMillis(to)
        if (toMs != null) conditions += "timestamp <= fromUnixTimestamp64Milli($toMs)"
        val whereClause = conditions.joinToString(" AND ")

        val services =
            queryValueCounts(
                """
            SELECT service AS val, count() AS cnt
            FROM `$clickhouseDb`.logs
            WHERE $whereClause AND service != ''
            GROUP BY val ORDER BY cnt DESC LIMIT 200
            FORMAT JSONEachRow
                """.trimIndent()
            )

        val environments =
            queryValueCounts(
                """
            SELECT environment AS val, count() AS cnt
            FROM `$clickhouseDb`.logs
            WHERE $whereClause AND environment != ''
            GROUP BY val ORDER BY cnt DESC LIMIT 200
            FORMAT JSONEachRow
                """.trimIndent()
            )

        val tagKeys =
            queryDistinctLines(
                """
            SELECT DISTINCT tag_key
            FROM (
                SELECT arrayJoin(mapKeys(tags)) AS tag_key
                FROM `$clickhouseDb`.logs
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
        val body = logRepository.executeClickHouseQuery(query)
        if (body.isClickHouseError()) {
            logger.warn { "Failed to query value counts: ${body.take(ERROR_BODY_PREVIEW_CHARS)}" }
            return emptyList()
        }
        val results = mutableListOf<LogFilterOptionWithCount>()
        body.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
            try {
                val obj = json.parseToJsonElement(line).jsonObject
                val value = obj["val"]?.jsonPrimitive?.content ?: return@forEach
                val count = obj["cnt"]?.jsonPrimitive?.longOrNull ?: 0L
                results += LogFilterOptionWithCount(value = value, count = count)
            } catch (_: SerializationException) {
                // Ignored: skip malformed JSON line
            } catch (_: IOException) {
                // Ignored: skip malformed JSON line
            } catch (_: IllegalStateException) {
                // Ignored: skip malformed JSON line
            } catch (_: IllegalArgumentException) {
                // Ignored: skip malformed JSON line
            }
        }
        return results
    }

    suspend fun getFilterOptions(
        organizationId: Long,
        from: String?,
        to: String?
    ): LogFilterOptionsResponse {
        val conditions = mutableListOf(ClickHouseQueryUtils.orgIdClause(organizationId))
        val fromMs = parseTimeToMillis(from)
        if (fromMs != null) {
            conditions += "timestamp >= fromUnixTimestamp64Milli($fromMs)"
        }
        val toMs = parseTimeToMillis(to)
        if (toMs != null) {
            conditions += "timestamp <= fromUnixTimestamp64Milli($toMs)"
        }
        val whereClause = conditions.joinToString(" AND ")

        val services =
            queryDistinctLines(
                """
            SELECT DISTINCT service
            FROM `$clickhouseDb`.logs
            WHERE $whereClause AND service != ''
            ORDER BY service
            LIMIT 200
            FORMAT TSV
                """.trimIndent()
            )

        val environments =
            queryDistinctLines(
                """
            SELECT DISTINCT environment
            FROM `$clickhouseDb`.logs
            WHERE $whereClause AND environment != ''
            ORDER BY environment
            LIMIT 200
            FORMAT TSV
                """.trimIndent()
            )

        val tagKeys =
            queryDistinctLines(
                """
            SELECT DISTINCT tag_key
            FROM (
                SELECT arrayJoin(mapKeys(tags)) AS tag_key
                FROM `$clickhouseDb`.logs
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

    suspend fun getTagValues(
        organizationId: Long,
        key: String,
        from: String?,
        to: String?,
        limit: Int = 50
    ): LogTagValuesResponse {
        val escapedKey = escapeSql(key.trim())
        if (escapedKey.isBlank()) return LogTagValuesResponse(key = key, values = emptyList())

        // Map status to level
        val actualField = if (key == "status") "level" else key

        // Check if this is a top-level field or a tag
        val isTopLevelField = actualField in topLevelFields

        val conditions = mutableListOf(ClickHouseQueryUtils.orgIdClause(organizationId))

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
        val query =
            if (isTopLevelField) {
                // For top-level fields, select directly from the column
                val enumFields = setOf("level", "source")
                val fieldRef = if (actualField in enumFields) "toString($actualField)" else actualField
                """
            SELECT DISTINCT $fieldRef AS tag_value
            FROM `$clickhouseDb`.logs
            WHERE $whereClause AND $fieldRef != ''
            ORDER BY tag_value
            LIMIT ${limit.coerceIn(1, MAX_FILTER_VALUES_LIMIT)}
            FORMAT TSV
                """.trimIndent()
            } else {
                // For tags, access the tags map
                """
            SELECT DISTINCT tags['$escapedKey'] AS tag_value
            FROM `$clickhouseDb`.logs
            WHERE $whereClause AND tags['$escapedKey'] != ''
            ORDER BY tag_value
            LIMIT ${limit.coerceIn(1, MAX_FILTER_VALUES_LIMIT)}
            FORMAT TSV
                """.trimIndent()
            }

        val values = queryDistinctLines(query)

        return LogTagValuesResponse(key = key, values = values)
    }

    private suspend fun queryDistinctLines(query: String): List<String> {
        val body = logRepository.executeClickHouseQuery(query)
        if (body.isClickHouseError()) {
            logger.warn { "Failed to query log filter values: ${body.take(ERROR_BODY_PREVIEW_CHARS)}" }
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

        return raw
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                suspendRunCatching {
                    val obj = json.parseToJsonElement(line).jsonObject
                    val timestampMs = obj["timestamp_ms"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                    val systemId =
                        obj["system_id_text"]?.jsonPrimitive?.contentOrNull
                            ?: obj["system_id"]?.jsonPrimitive?.contentOrNull
                    val tagsMap = parseMapField(obj["tags"])
                    val hostIdFromTags = tagsMap["host_id"]?.toIntOrNull()
                    val log =
                        LogEntryResponse(
                            logId = obj["log_id_str"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                            timestamp = Instant.ofEpochMilli(timestampMs).toString(),
                            level =
                            normalizeLevel(
                                obj["level_text"]?.jsonPrimitive?.content ?: obj["level"]?.jsonPrimitive?.content
                            ),
                            message = obj["message"]?.jsonPrimitive?.content ?: "",
                            body = obj["body"]?.jsonPrimitive?.content ?: "",
                            service = obj["service"]?.jsonPrimitive?.content ?: "",
                            environment = obj["environment"]?.jsonPrimitive?.content ?: "",
                            host = obj["host"]?.jsonPrimitive?.content ?: "",
                            source =
                            normalizeSource(
                                obj["source_text"]?.jsonPrimitive?.content
                                    ?: obj["source"]?.jsonPrimitive?.content ?: "sdk"
                            ),
                            containerName = obj["container_name"]?.jsonPrimitive?.content ?: "",
                            containerId = obj["container_id"]?.jsonPrimitive?.content ?: "",
                            containerImage = obj["container_image"]?.jsonPrimitive?.content ?: "",
                            traceId = obj["trace_id"]?.jsonPrimitive?.content ?: "",
                            spanId = obj["span_id"]?.jsonPrimitive?.content ?: "",
                            tags = tagsMap,
                            resourceAttributes = parseMapField(obj["resource_attributes"]),
                            systemId = if (systemId == "00000000-0000-0000-0000-000000000000") null else systemId,
                            hostId = hostIdFromTags
                        )
                    LogWithCursor(log = log, timestampMs = timestampMs)
                }.getOrElse { e ->
                    logger.warn(e) { "Failed to parse log row" }
                    null
                }
            }.toList()
    }

    private fun parseMapField(element: JsonElement?): Map<String, String> {
        if (element == null) return emptyMap()

        return suspendRunCatching {
            when (element) {
                is JsonObject -> {
                    element.mapValues { (_, value) -> value.jsonPrimitive.content }
                }

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
        }.getOrElse { _ ->
            emptyMap()
        }
    }

    private suspend fun enqueueNormalized(
        organizationId: Long,
        systemId: String?,
        hostId: Int? = null,
        source: String,
        logs: List<QueuedLogEntry>,
        queueKey: String
    ): Int {
        if (logs.isEmpty()) return 0

        val message =
            encodeQueueMessage(
                QueuedLogBatch(
                    organizationId = organizationId,
                    legacyProjectId = null,
                    systemId = systemId,
                    hostId = hostId,
                    source = source,
                    logs = logs
                )
            )

        IngestionQueueClient.enqueue(IngestionPipeline.LOGS, queueKey, message)
        return logs.size
    }

    private fun normalizeSdkEntry(entry: LogIngestEntry): QueuedLogEntry? {
        val message = entry.message?.trim().orEmpty()
        if (message.isBlank()) return null

        return QueuedLogEntry(
            logId = UUID.randomUUID().toString(),
            timestampMs = resolveTimestampMs(entry.timestamp, entry.timestampMs),
            level = normalizeLevel(entry.level),
            message = trimTo(entry.message.orEmpty(), MAX_LOG_MESSAGE_CHARS),
            body = trimTo(entry.body ?: entry.message.orEmpty(), MAX_LOG_BODY_CHARS),
            service = trimTo(entry.service.orEmpty(), MAX_LOG_SERVICE_CHARS),
            serviceNamespace = trimTo(entry.serviceNamespace.orEmpty(), MAX_LOG_SERVICE_CHARS),
            environment = trimTo(entry.environment.orEmpty(), MAX_LOG_ENVIRONMENT_CHARS),
            host = trimTo(entry.host.orEmpty(), MAX_LOG_HOST_CHARS),
            source = normalizeSource(entry.source ?: "sdk"),
            projectId = entry.projectId,
            containerName = trimTo(entry.containerName.orEmpty(), MAX_LOG_CONTAINER_NAME_CHARS),
            containerId = trimTo(entry.containerId.orEmpty(), MAX_LOG_CONTAINER_ID_CHARS),
            containerImage = trimTo(entry.containerImage.orEmpty(), MAX_LOG_CONTAINER_IMAGE_CHARS),
            traceId = trimTo(entry.traceId.orEmpty(), MAX_LOG_TRACE_ID_CHARS),
            spanId = trimTo(entry.spanId.orEmpty(), MAX_LOG_SPAN_ID_CHARS),
            tags = sanitizeMap(entry.tags),
            resourceAttributes = sanitizeMap(entry.resourceAttributes)
        )
    }

    private fun normalizeAgentEntry(
        entry: AgentLogEntry,
        systemId: String?
    ): QueuedLogEntry? {
        val message = entry.message?.trim().orEmpty()
        if (message.isBlank()) return null

        val source =
            when (entry.stream?.lowercase()) {
                "stderr" -> "agent_stderr"
                else -> "agent_stdout"
            }

        return QueuedLogEntry(
            logId = UUID.randomUUID().toString(),
            timestampMs = resolveTimestampMs(entry.timestamp, entry.timestampMs),
            level = normalizeLevel(entry.level),
            message = trimTo(entry.message.orEmpty(), MAX_LOG_MESSAGE_CHARS),
            body = trimTo(entry.body ?: entry.message.orEmpty(), MAX_LOG_BODY_CHARS),
            service = trimTo(entry.service ?: entry.containerName.orEmpty(), MAX_LOG_SERVICE_CHARS),
            serviceNamespace = "",
            environment = trimTo(entry.environment.orEmpty(), MAX_LOG_ENVIRONMENT_CHARS),
            host = trimTo(entry.host.orEmpty(), MAX_LOG_HOST_CHARS),
            source = source,
            containerName = trimTo(entry.containerName.orEmpty(), MAX_LOG_CONTAINER_NAME_CHARS),
            containerId = trimTo(entry.containerId.orEmpty(), MAX_LOG_CONTAINER_ID_CHARS),
            containerImage = trimTo(entry.containerImage.orEmpty(), MAX_LOG_CONTAINER_IMAGE_CHARS),
            traceId = trimTo(entry.traceId.orEmpty(), MAX_LOG_TRACE_ID_CHARS),
            spanId = trimTo(entry.spanId.orEmpty(), MAX_LOG_SPAN_ID_CHARS),
            tags = sanitizeMap(entry.tags),
            resourceAttributes = sanitizeMap(entry.resourceAttributes),
            systemId = systemId
        )
    }

    private fun normalizeOtlpEntry(entry: LogIngestEntry): QueuedLogEntry? {
        val base = normalizeSdkEntry(entry) ?: return null
        return base.copy(source = "otlp")
    }

    private fun toResponse(
        entry: QueuedLogEntry,
        systemId: String?,
        hostId: Int? = null
    ): LogEntryResponse {
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
            systemId = systemId,
            hostId = hostId
        )
    }

    fun parseOtlpProtobuf(bytes: ByteArray): List<LogIngestEntry> {
        val request =
            try {
                ExportLogsServiceRequest.parseFrom(bytes)
            } catch (e: InvalidProtocolBufferException) {
                logger.warn { "Invalid OTLP protobuf logs payload: ${e.message?.take(WARN_BODY_PREVIEW_CHARS)}" }
                return emptyList()
            }

        val entries = mutableListOf<LogIngestEntry>()
        request.resourceLogsList.forEach { resourceLogs ->
            appendOtlpProtobufResourceLogs(resourceLogs, entries)
        }
        return entries
    }

    private fun appendOtlpProtobufResourceLogs(
        resourceLogs: ResourceLogs,
        entries: MutableList<LogIngestEntry>,
    ) {
        val resourceCtx = OtlpProtobufParser.extractResourceContext(resourceLogs.resource)
        resourceLogs.scopeLogsList.forEach { scopeLogs ->
            appendOtlpProtobufScopeLogs(scopeLogs, resourceCtx, entries)
        }
    }

    private fun appendOtlpProtobufScopeLogs(
        scopeLogs: ScopeLogs,
        resourceCtx: ResourceContext,
        entries: MutableList<LogIngestEntry>,
    ) {
        scopeLogs.logRecordsList.forEach { record ->
            entries += logIngestEntryFromOtlpProtobuf(record, resourceCtx)
        }
    }

    private fun logIngestEntryFromOtlpProtobuf(
        record: LogRecord,
        resourceCtx: ResourceContext,
    ): LogIngestEntry {
        val attributes = OtlpProtobufParser.attributesToMap(record.attributesList)
        val bodyText = OtlpProtobufParser.extractAnyValue(record.body) ?: ""
        val message = if (bodyText.isBlank()) "OTLP log record" else bodyText
        val severityText = record.severityText.ifEmpty { null }
        val timestampNs = record.timeUnixNano.takeIf { it != 0L }
            ?: record.observedTimeUnixNano.takeIf { it != 0L }
        val timestampMs = timestampNs?.let { OtlpProtobufParser.nanoToEpochMs(it) }

        return LogIngestEntry(
            timestampMs = timestampMs,
            level = severityText,
            message = message,
            body = bodyText,
            service = resourceCtx.serviceName.ifEmpty { attributes["service.name"] },
            serviceNamespace = resourceCtx.serviceNamespace.ifEmpty { attributes["service.namespace"] },
            environment = resourceCtx.environment.ifEmpty {
                OtlpParsingUtils.extractDeploymentEnvironment(attributes)
            },
            host = resourceCtx.hostName.ifEmpty { attributes["host.name"] },
            source = "otlp",
            traceId = OtlpProtobufParser.bytesToHex(record.traceId).ifEmpty { null },
            spanId = OtlpProtobufParser.bytesToHex(record.spanId).ifEmpty { null },
            tags = HashMap(attributes),
            resourceAttributes = HashMap(resourceCtx.attributes),
        )
    }

    fun parseOtlpJson(payload: String): List<LogIngestEntry> {
        val parsed =
            suspendRunCatching {
                json.parseToJsonElement(payload).jsonObject
            }.getOrElse { e ->
                logger.warn(e) { "Invalid OTLP JSON payload" }
                return emptyList()
            }

        val resourceLogs = parsed["resourceLogs"]?.jsonArray ?: return emptyList()
        val entries = mutableListOf<LogIngestEntry>()
        appendOtlpJsonResourceLogs(resourceLogs, entries)
        return entries
    }

    private fun appendOtlpJsonResourceLogs(
        resourceLogs: JsonArray,
        entries: MutableList<LogIngestEntry>,
    ) {
        resourceLogs.forEach { resourceLogElement ->
            val resourceLog = resourceLogElement.jsonObject
            val resourceCtx = OtlpParsingUtils.extractResourceContext(resourceLog["resource"]?.jsonObject)
            val scopeLogs =
                resourceLog["scopeLogs"]?.jsonArray
                    ?: resourceLog["instrumentationLibraryLogs"]?.jsonArray
                    ?: JsonArray(emptyList())
            appendOtlpJsonScopeLogs(scopeLogs, resourceCtx, entries)
        }
    }

    private fun appendOtlpJsonScopeLogs(
        scopeLogs: JsonArray,
        resourceCtx: ResourceContext,
        entries: MutableList<LogIngestEntry>,
    ) {
        scopeLogs.forEach { scopeElement ->
            val scopeLog = scopeElement.jsonObject
            val logRecords = OtlpParsingUtils.safeJsonArray(scopeLog["logRecords"])
            logRecords.forEach { recordElement ->
                entries += logIngestEntryFromOtlpJson(recordElement.jsonObject, resourceCtx)
            }
        }
    }

    private fun logIngestEntryFromOtlpJson(
        record: JsonObject,
        resourceCtx: ResourceContext,
    ): LogIngestEntry {
        val attributes = OtlpParsingUtils.attributesToMap(record["attributes"])
        val bodyText = OtlpParsingUtils.extractAnyValue(record["body"]) ?: ""
        val message = if (bodyText.isBlank()) "OTLP log record" else bodyText
        val severityText = record["severityText"]?.jsonPrimitive?.contentOrNull
        val timestampNs = OtlpParsingUtils.extractTimestampNanos(
            record,
            "timeUnixNano",
            "observedTimeUnixNano"
        )
        val timestampMs = OtlpParsingUtils.nanoToEpochMs(timestampNs)

        return LogIngestEntry(
            timestampMs = timestampMs,
            level = severityText,
            message = message,
            body = bodyText,
            service = resourceCtx.serviceName.ifEmpty { attributes["service.name"] },
            serviceNamespace = resourceCtx.serviceNamespace.ifEmpty { attributes["service.namespace"] },
            environment = resourceCtx.environment.ifEmpty {
                OtlpParsingUtils.extractDeploymentEnvironment(attributes)
            },
            host = resourceCtx.hostName.ifEmpty { attributes["host.name"] },
            source = "otlp",
            traceId = record["traceId"]?.jsonPrimitive?.contentOrNull,
            spanId = record["spanId"]?.jsonPrimitive?.contentOrNull,
            tags = HashMap(attributes),
            resourceAttributes = HashMap(resourceCtx.attributes)
        )
    }

    private fun parseTimeToMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()

        trimmed.toLongOrNull()?.let { numeric ->
            // Use digit count to distinguish seconds (≤10 digits) from milliseconds (13 digits).
            // A numeric threshold would misclassify valid pre-2001 millisecond timestamps
            // (e.g. 946684800000 for 2000-01-01).
            val digits = trimmed.trimStart('-')
            return if (digits.length <= UNIX_EPOCH_SECONDS_MAX_DIGITS) numeric * MS_PER_SECOND else numeric
        }

        return suspendRunCatching {
            Instant.parse(trimmed).toEpochMilli()
        }.getOrElse { _ ->
            null
        }
    }

    private fun buildScopeFilter(
        organizationId: Long,
        systemId: String?,
        hostId: Int? = null
    ): String? {
        hostId?.let { id ->
            return "${ClickHouseQueryUtils.orgIdClause(organizationId)} AND tags['host_id'] = '$id'"
        }

        val rawSystemId = systemId?.trim()
        if (rawSystemId.isNullOrEmpty()) {
            return ClickHouseQueryUtils.orgIdClause(organizationId)
        }

        val parsed =
            suspendRunCatching {
                UUID.fromString(rawSystemId)
            }.getOrElse { _ ->
                return null
            }

        return "${ClickHouseQueryUtils.orgIdClause(organizationId)} AND system_id = toUUID('$parsed')"
    }

    private fun resolveTimestampMs(
        timestamp: String?,
        timestampMs: Long?
    ): Long {
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
                val key = rawKey.trim().take(INGEST_KEY_MAX_CHARS)
                if (key.isBlank()) return@mapNotNull null
                key to rawValue.trim().take(INGEST_VALUE_MAX_CHARS)
            }.toMap()
    }

    private fun trimTo(
        value: String,
        maxLength: Int
    ): String {
        return if (value.length <= maxLength) value else value.take(maxLength)
    }

    private fun mapToSqlMap(value: Map<String, String>?): String {
        if (value == null || value.isEmpty()) return "map()"
        val pairs =
            value.entries
                .sortedBy { it.key }
                .joinToString(", ") { (key, mapValue) ->
                    "'${escapeSql(key)}', '${escapeSql(mapValue)}'"
                }
        return "map($pairs)"
    }

    private fun buildSimpleSearchCondition(term: String): String {
        val escaped = ClickHouseSqlUtils.escapeLikePattern(term)
        val isSimpleToken = term.all { it.isLetterOrDigit() }
        return if (isSimpleToken) {
            val tokenEscaped = ClickHouseSqlUtils.escapeSql(term)
            "(hasTokenCaseInsensitive(message, '$tokenEscaped') OR hasTokenCaseInsensitive(body, '$tokenEscaped'))"
        } else {
            "(message ILIKE '%$escaped%' OR body ILIKE '%$escaped%')"
        }
    }

    private fun encodeCursor(
        timestampMs: Long,
        logId: String
    ): String {
        val raw = "$timestampMs|$logId"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())
    }

    private fun decodeCursor(cursor: String?): Pair<Long, String>? {
        if (cursor.isNullOrBlank()) return null
        return suspendRunCatching {
            val decoded = String(Base64.getUrlDecoder().decode(cursor))
            val parts = decoded.split("|", limit = 2)
            if (parts.size != 2) return null
            val ts = parts[0].toLongOrNull() ?: return null
            val logId = parts[1]
            ts to logId
        }.getOrElse { _ ->
            null
        }
    }

    /**
     * Check if a tag key/value looks like it contains Boolean operators.
     * These should be in the query field instead.
     */
    private fun isTagMalformed(
        key: String,
        value: String
    ): Boolean {
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
    internal fun buildTagCondition(
        key: String,
        value: String,
        exclude: Boolean = false
    ): String {
        if (key.isBlank()) return ""

        // If the tag value contains Boolean operators, it's actually a query that was
        // mistakenly sent as a tag. Route it through the query parser instead of dropping it.
        if (isTagMalformed(key, value)) {
            logger.info { "Tag contains Boolean operators, parsing as query: $key:$value" }
            return suspendRunCatching {
                val parsed = queryParser.parse("$key:$value")
                if (parsed.rootNode != null) {
                    val condition = queryParser.toClickHouseSql(parsed.rootNode, ::escapeSql)
                    if (exclude && condition.isNotBlank()) "NOT ($condition)" else condition
                } else {
                    ""
                }
            }.getOrElse { e ->
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
        val operator = if (exclude) "!=" else "="
        return if (actualField in topLevelFields) {
            // Use toString() for Enum8 fields
            val fieldRef = if (actualField in enumFields) "toString($actualField)" else actualField
            "$fieldRef $operator '$escapedValue'"
        } else {
            // Use has() for actual tags in the tags map
            if (exclude) {
                "NOT (has(tags, '$escapedKey') AND tags['$escapedKey'] = '$escapedValue')"
            } else {
                "has(tags, '$escapedKey') AND tags['$escapedKey'] = '$escapedValue'"
            }
        }
    }
}
