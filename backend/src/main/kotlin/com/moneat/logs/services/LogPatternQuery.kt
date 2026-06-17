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

import com.moneat.config.ClickHouseClient
import com.moneat.config.isClickHouseError
import com.moneat.logs.models.LogPatternBreakdown
import com.moneat.logs.models.LogPatternRequest
import com.moneat.logs.models.LogPatternResponse
import com.moneat.logs.repositories.LogRepository
import com.moneat.utils.ClickHouseQueryUtils
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

private val patternLogger = KotlinLogging.logger {}

private const val DEFAULT_PATTERN_WINDOW_MS = 24L * 60 * 60 * 1000
private const val MILLIS_PER_MINUTE = 60L * 1000
private const val MILLIS_PER_HOUR = 60L * MILLIS_PER_MINUTE
private const val MILLIS_PER_DAY = 24L * MILLIS_PER_HOUR
private const val MS_PER_SECOND = 1000
private const val UNIX_EPOCH_SECONDS_MAX_DIGITS = 10
private const val PATTERN_SPARKLINE_BUCKETS = 3
private const val TOP_PATTERN_BREAKDOWN_LIMIT = 5
private const val TREND_PERCENT_MULTIPLIER = 100.0
private const val CLICKHOUSE_ERROR_PREVIEW_CHARS = 600
private const val LOG_ID_PARAMETER = "logId"
private const val SERVICE_PARAMETER = "service"
internal const val LOG_MESSAGE_PATTERN_PARAMETER = "messagePattern"
private const val UUID_LENGTH = 36
private const val UUID_DASH_OFFSET_1 = 8
private const val UUID_DASH_OFFSET_2 = 13
private const val UUID_DASH_OFFSET_3 = 18
private const val UUID_DASH_OFFSET_4 = 23
private const val MIN_HEX_TOKEN_CHARS = 12
private const val MIN_PREFIXED_ID_SUFFIX_CHARS = 3
private const val HEX_PREFIX_LENGTH = 2
private const val NO_MATCH = -1
private const val UUID_REPLACEMENT = "<uuid>"
private const val ID_REPLACEMENT = "<id>"
private const val HEX_REPLACEMENT = "<hex>"
private const val STRING_REPLACEMENT = "<str>"
private const val FLOAT_REPLACEMENT = "<float>"
private const val INT_REPLACEMENT = "<int>"
private const val LIKE_WILDCARD = '%'
private const val LIKE_ESCAPE = '\\'

private val uuidDashOffsets =
    setOf(UUID_DASH_OFFSET_1, UUID_DASH_OFFSET_2, UUID_DASH_OFFSET_3, UUID_DASH_OFFSET_4)

private val patternPlaceholderTokens =
    listOf(UUID_REPLACEMENT, ID_REPLACEMENT, HEX_REPLACEMENT, STRING_REPLACEMENT, FLOAT_REPLACEMENT, INT_REPLACEMENT)

internal class LogPatternQuery(private val logRepository: LogRepository) {
    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getLogPattern(
        organizationId: Long,
        request: LogPatternRequest
    ): LogPatternResponse {
        val message = resolvePatternMessage(organizationId, request)
        val pattern = derivePatternString(message)
        if (pattern.isBlank()) return emptyResponse(pattern, resolvedWindow(request))

        val window = resolvedWindow(request)
        val conditions =
            patternConditions(
                PatternConditionScope(
                    organizationId = organizationId,
                    service = request.service,
                    fromMs = window.fromMs,
                    toMs = window.toMs,
                    pattern = pattern
                )
            )
        val stats = queryPatternStats(conditions)
        val previousCount =
            queryPreviousPatternCount(
                organizationId = organizationId,
                service = request.service,
                window = window,
                pattern = pattern
            )

        return LogPatternResponse(
            pattern = pattern,
            level = stats.level.ifBlank { "info" },
            count = stats.count,
            windowLabel = windowLabel(window),
            firstSeen = stats.firstSeenMs?.let { Instant.ofEpochMilli(it).toString() }.orEmpty(),
            lastSeen = stats.lastSeenMs?.let { Instant.ofEpochMilli(it).toString() }.orEmpty(),
            trendPct = trendPercent(stats.count, previousCount),
            sparkline = queryPatternSparkline(conditions, window),
            topServices = queryPatternBreakdown(PatternBreakdownColumn.SERVICE, conditions),
            topHosts = queryPatternBreakdown(PatternBreakdownColumn.HOST, conditions)
        )
    }

    private suspend fun resolvePatternMessage(
        organizationId: Long,
        request: LogPatternRequest
    ): String {
        request.message?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val logId = request.logId?.trim()?.takeIf { it.isNotBlank() } ?: return ""
        val parsedId =
            try {
                UUID.fromString(logId)
            } catch (_: IllegalArgumentException) {
                return ""
            }

        val query =
            """
            SELECT message
            FROM `$clickhouseDb`.logs
            WHERE ${ClickHouseQueryUtils.orgIdClause(organizationId)} AND log_id = toUUID({$LOG_ID_PARAMETER:String})
            LIMIT 1
            FORMAT JSONEachRow
            """.trimIndent()

        val body =
            logRepository.executeClickHouseQuery(
                query,
                mapOf(LOG_ID_PARAMETER to parsedId.toString())
            )
        if (body.isClickHouseError()) {
            patternLogger.warn {
                "Failed to resolve log pattern anchor message: ${body.take(CLICKHOUSE_ERROR_PREVIEW_CHARS)}"
            }
            return ""
        }
        return firstObject(body)?.stringField("message").orEmpty()
    }

    private fun emptyResponse(
        pattern: String,
        window: PatternWindow
    ): LogPatternResponse {
        return LogPatternResponse(
            pattern = pattern,
            level = "info",
            count = 0,
            windowLabel = windowLabel(window),
            firstSeen = "",
            lastSeen = "",
            trendPct = null,
            sparkline = List(PATTERN_SPARKLINE_BUCKETS) { 0L },
            topServices = emptyList(),
            topHosts = emptyList()
        )
    }

    private suspend fun queryPatternStats(conditions: PatternConditions): PatternStats {
        val query =
            """
            SELECT
                count() AS cnt,
                argMax(toString(level), timestamp) AS level_value,
                toUnixTimestamp64Milli(min(timestamp)) AS first_seen_ms,
                toUnixTimestamp64Milli(max(timestamp)) AS last_seen_ms
            FROM `$clickhouseDb`.logs
            WHERE ${conditions.sql}
            FORMAT JSONEachRow
            """.trimIndent()

        val body = logRepository.executeClickHouseQuery(query, conditions.parameters)
        if (body.isClickHouseError()) {
            patternLogger.warn { "Failed to query log pattern stats: ${body.take(CLICKHOUSE_ERROR_PREVIEW_CHARS)}" }
            return PatternStats()
        }
        val obj = firstObject(body) ?: return PatternStats()
        val count = obj.longField("cnt")
        return PatternStats(
            count = count,
            level = obj.stringField("level_value"),
            firstSeenMs = obj.longField("first_seen_ms").takeIf { count > 0 },
            lastSeenMs = obj.longField("last_seen_ms").takeIf { count > 0 }
        )
    }

    private suspend fun queryPreviousPatternCount(
        organizationId: Long,
        service: String?,
        window: PatternWindow,
        pattern: String
    ): Long {
        val durationMs = window.durationMs
        val previousToMs = window.fromMs
        val previousFromMs = previousToMs - durationMs
        val conditions =
            patternConditions(
                PatternConditionScope(
                    organizationId = organizationId,
                    service = service,
                    fromMs = previousFromMs,
                    toMs = previousToMs,
                    pattern = pattern
                )
            )
        val query =
            """
            SELECT count() AS previous_count
            FROM `$clickhouseDb`.logs
            WHERE ${conditions.sql}
            FORMAT JSONEachRow
            """.trimIndent()

        val body = logRepository.executeClickHouseQuery(query, conditions.parameters)
        if (body.isClickHouseError()) return 0
        return firstObject(body)?.longField("previous_count") ?: 0
    }

    private suspend fun queryPatternSparkline(
        conditions: PatternConditions,
        window: PatternWindow
    ): List<Long> {
        val bucketMs = roundUp(window.durationMs, PATTERN_SPARKLINE_BUCKETS.toLong()).coerceAtLeast(1)
        val query =
            """
            SELECT intDiv(toUnixTimestamp64Milli(timestamp) - ${window.fromMs}, $bucketMs) AS bucket_index,
                   count() AS cnt
            FROM `$clickhouseDb`.logs
            WHERE ${conditions.sql}
            GROUP BY bucket_index
            ORDER BY bucket_index
            FORMAT JSONEachRow
            """.trimIndent()

        val body = logRepository.executeClickHouseQuery(query, conditions.parameters)
        if (body.isClickHouseError()) return List(PATTERN_SPARKLINE_BUCKETS) { 0L }

        val values = MutableList(PATTERN_SPARKLINE_BUCKETS) { 0L }
        jsonObjects(body).forEach { obj ->
            val idx = obj.intField("bucket_index").coerceIn(values.indices)
            values[idx] += obj.longField("cnt")
        }
        return values
    }

    private suspend fun queryPatternBreakdown(
        column: PatternBreakdownColumn,
        conditions: PatternConditions
    ): List<LogPatternBreakdown> {
        val columnName = column.sqlName
        val query =
            """
            SELECT $columnName AS value, count() AS cnt
            FROM `$clickhouseDb`.logs
            WHERE ${conditions.sql} AND $columnName != ''
            GROUP BY value
            ORDER BY cnt DESC
            LIMIT $TOP_PATTERN_BREAKDOWN_LIMIT
            FORMAT JSONEachRow
            """.trimIndent()

        val body = logRepository.executeClickHouseQuery(query, conditions.parameters)
        if (body.isClickHouseError()) return emptyList()
        return jsonObjects(body).mapNotNull { obj ->
            val value = obj.stringField("value").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            LogPatternBreakdown(value = value, count = obj.longField("cnt"))
        }
    }
}

private data class PatternWindow(
    val fromMs: Long,
    val toMs: Long
) {
    val durationMs: Long = (toMs - fromMs).coerceAtLeast(1)
}

private data class PatternStats(
    val count: Long = 0,
    val level: String = "info",
    val firstSeenMs: Long? = null,
    val lastSeenMs: Long? = null
)

private data class PatternConditionScope(
    val organizationId: Long,
    val service: String?,
    val fromMs: Long,
    val toMs: Long,
    val pattern: String
)

private data class PatternConditions(
    val sql: String,
    val parameters: Map<String, String>
)

private enum class PatternBreakdownColumn(val sqlName: String) {
    SERVICE("service"),
    HOST("host")
}

private fun patternConditions(scope: PatternConditionScope): PatternConditions {
    val parameters =
        mutableMapOf(LOG_MESSAGE_PATTERN_PARAMETER to messagePatternLikeParameter(scope.pattern))
    val conditions =
        mutableListOf(
            ClickHouseQueryUtils.orgIdClause(scope.organizationId),
            "timestamp >= fromUnixTimestamp64Milli(${scope.fromMs})",
            "timestamp <= fromUnixTimestamp64Milli(${scope.toMs})",
            logMessagePatternCondition(scope.pattern)
        )
    if (!scope.service.isNullOrBlank()) {
        conditions += "service = {$SERVICE_PARAMETER:String}"
        parameters[SERVICE_PARAMETER] = scope.service
    }
    return PatternConditions(sql = conditions.joinToString(" AND "), parameters = parameters)
}

internal fun logMessagePatternCondition(pattern: String?): String {
    val trimmed = normalizeLogMessagePattern(pattern)
    if (trimmed.isBlank()) return ""
    return "message LIKE {$LOG_MESSAGE_PATTERN_PARAMETER:String}"
}

internal fun normalizeLogMessagePattern(pattern: String?): String = pattern?.trim().orEmpty()

internal fun messagePatternLikeParameter(pattern: String?): String {
    val trimmed = normalizeLogMessagePattern(pattern)
    if (trimmed.isBlank()) return ""

    val output = StringBuilder(trimmed.length)
    var index = 0
    while (index < trimmed.length) {
        val placeholder = patternPlaceholderTokens.firstOrNull { trimmed.startsWith(it, index) }
        if (placeholder != null) {
            appendWildcard(output)
            index += placeholder.length
        } else {
            appendLikeLiteral(output, trimmed[index])
            index += 1
        }
    }
    return output.toString()
}

private fun appendWildcard(output: StringBuilder) {
    if (output.lastOrNull() != LIKE_WILDCARD) {
        output.append(LIKE_WILDCARD)
    }
}

private fun appendLikeLiteral(
    output: StringBuilder,
    char: Char
) {
    if (char == LIKE_WILDCARD || char == '_' || char == LIKE_ESCAPE) {
        output.append(LIKE_ESCAPE)
    }
    output.append(char)
}

internal fun derivePatternString(message: String): String {
    val output = StringBuilder(message.length)
    var index = 0
    while (index < message.length) {
        val replacement = patternReplacementAt(message, index)
        if (replacement != null) {
            output.append(replacement.value)
            index = replacement.nextIndex
        } else {
            output.append(message[index])
            index += 1
        }
    }
    return output.toString().trim()
}

private data class PatternReplacement(
    val value: String,
    val nextIndex: Int
)

private fun patternReplacementAt(
    input: String,
    index: Int
): PatternReplacement? =
    quotedReplacementAt(input, index)
        ?: uuidReplacementAt(input, index)
        ?: prefixedIdReplacementAt(input, index)
        ?: hexReplacementAt(input, index)
        ?: floatReplacementAt(input, index)
        ?: intReplacementAt(input, index)

private fun quotedReplacementAt(
    input: String,
    index: Int
): PatternReplacement? {
    val quote = input[index]
    if (quote != '"' && quote != '\'') return null
    var end = index + 1
    while (end < input.length && input[end] != quote) {
        end += 1
    }
    return PatternReplacement(STRING_REPLACEMENT, if (end < input.length) end + 1 else input.length)
}

private fun uuidReplacementAt(
    input: String,
    index: Int
): PatternReplacement? {
    val end = index + UUID_LENGTH
    if (end > input.length || !hasWordBoundaries(input, index, end)) return null
    val isUuid =
        (0 until UUID_LENGTH).all { offset ->
            val char = input[index + offset]
            if (offset in uuidDashOffsets) char == '-' else char.isHexDigit()
        }
    return if (isUuid) PatternReplacement(UUID_REPLACEMENT, end) else null
}

private fun prefixedIdReplacementAt(
    input: String,
    index: Int
): PatternReplacement? {
    if (!input[index].isLowercaseAsciiLetter()) return null

    var cursor = index + 1
    while (cursor < input.length && input[cursor].isLowercaseAsciiLetterOrDigit()) {
        cursor += 1
    }
    if (cursor >= input.length || input[cursor] != '_') return null

    val suffixStart = cursor + 1
    cursor = suffixStart
    while (cursor < input.length && input[cursor].isAsciiLetterOrDigit()) {
        cursor += 1
    }

    val suffixLength = cursor - suffixStart
    val isValid = suffixLength >= MIN_PREFIXED_ID_SUFFIX_CHARS && hasWordBoundaries(input, index, cursor)
    return if (isValid) PatternReplacement(ID_REPLACEMENT, cursor) else null
}

private fun hexReplacementAt(
    input: String,
    index: Int
): PatternReplacement? {
    val hexStart =
        if (hasHexPrefix(input, index)) {
            index + HEX_PREFIX_LENGTH
        } else {
            index
        }
    var cursor = hexStart
    while (cursor < input.length && input[cursor].isHexDigit()) {
        cursor += 1
    }

    val hexLength = cursor - hexStart
    val isValid = hexLength >= MIN_HEX_TOKEN_CHARS && hasWordBoundaries(input, index, cursor)
    return if (isValid) PatternReplacement(HEX_REPLACEMENT, cursor) else null
}

private fun floatReplacementAt(
    input: String,
    index: Int
): PatternReplacement? {
    val firstDigitsEnd = consumeDigits(input, index)
    if (firstDigitsEnd == index || firstDigitsEnd >= input.length || input[firstDigitsEnd] != '.') {
        return null
    }
    val end = consumeDigits(input, firstDigitsEnd + 1)
    val isValid = end > firstDigitsEnd + 1 && hasWordBoundaries(input, index, end)
    return if (isValid) PatternReplacement(FLOAT_REPLACEMENT, end) else null
}

private fun intReplacementAt(
    input: String,
    index: Int
): PatternReplacement? {
    val end = consumeDigits(input, index)
    val isValid = end > index && hasWordBoundaries(input, index, end)
    return if (isValid) PatternReplacement(INT_REPLACEMENT, end) else null
}

private fun consumeDigits(
    input: String,
    index: Int
): Int {
    var cursor = index
    while (cursor < input.length && input[cursor].isDigit()) {
        cursor += 1
    }
    return cursor
}

private fun hasHexPrefix(
    input: String,
    index: Int
): Boolean {
    return index + HEX_PREFIX_LENGTH < input.length && input[index] == '0' &&
        (input[index + 1] == 'x' || input[index + 1] == 'X')
}

private fun hasWordBoundaries(
    input: String,
    start: Int,
    end: Int
): Boolean {
    val previousIsWord = start > 0 && input[start - 1].isRegexWordChar()
    val nextIsWord = end < input.length && input[end].isRegexWordChar()
    return !previousIsWord && !nextIsWord
}

private fun Char.isRegexWordChar(): Boolean = isAsciiLetterOrDigit() || this == '_'

private fun Char.isAsciiLetterOrDigit(): Boolean = isLowercaseAsciiLetterOrDigit() || this in 'A'..'Z'

private fun Char.isLowercaseAsciiLetterOrDigit(): Boolean = isLowercaseAsciiLetter() || this in '0'..'9'

private fun Char.isLowercaseAsciiLetter(): Boolean = this in 'a'..'z'

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun resolvedWindow(request: LogPatternRequest): PatternWindow {
    val requestedToMs = parseTimeToMillis(request.to) ?: System.currentTimeMillis()
    val requestedFromMs = parseTimeToMillis(request.from) ?: (requestedToMs - DEFAULT_PATTERN_WINDOW_MS)
    val fromMs = if (requestedFromMs < requestedToMs) requestedFromMs else requestedToMs - DEFAULT_PATTERN_WINDOW_MS
    return PatternWindow(fromMs = fromMs, toMs = requestedToMs)
}

private fun parseTimeToMillis(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    val trimmed = value.trim()
    trimmed.toLongOrNull()?.let { numeric ->
        val digits = trimmed.trimStart('-')
        return if (digits.length <= UNIX_EPOCH_SECONDS_MAX_DIGITS) numeric * MS_PER_SECOND else numeric
    }
    return runCatching { Instant.parse(trimmed).toEpochMilli() }.getOrNull()
}

private fun trendPercent(
    current: Long,
    previous: Long
): Int? {
    if (previous <= 0) return null
    return (((current - previous).toDouble() / previous.toDouble()) * TREND_PERCENT_MULTIPLIER).roundToInt()
}

private fun windowLabel(window: PatternWindow): String {
    val durationMs = window.durationMs
    return when {
        durationMs < MILLIS_PER_HOUR -> "${roundUp(durationMs, MILLIS_PER_MINUTE)}m"
        durationMs <= MILLIS_PER_DAY -> "${roundUp(durationMs, MILLIS_PER_HOUR)}h"
        else -> "${roundUp(durationMs, MILLIS_PER_DAY)}d"
    }
}

private fun roundUp(
    value: Long,
    divisor: Long
): Long {
    return (value + divisor - 1) / divisor
}

private fun firstObject(body: String): JsonObject? = jsonObjects(body).firstOrNull()

private fun jsonObjects(body: String): List<JsonObject> {
    return body
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            try {
                Json.parseToJsonElement(line).jsonObject
            } catch (_: SerializationException) {
                null
            } catch (_: IOException) {
                null
            } catch (_: IllegalStateException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }.toList()
}

private fun JsonObject.longField(key: String): Long {
    return this[key]?.jsonPrimitive?.longOrNull ?: 0L
}

private fun JsonObject.intField(key: String): Int {
    return this[key]?.jsonPrimitive?.intOrNull ?: 0
}

private fun JsonObject.stringField(key: String): String {
    return this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
}
