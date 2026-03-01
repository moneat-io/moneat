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
import com.moneat.logs.models.CreateLogIndexRequest
import com.moneat.logs.models.LogIndexResponse
import com.moneat.logs.models.LogIndexTestResponse
import com.moneat.logs.models.UpdateLogIndexRequest
import com.moneat.shared.models.LogIndexes
import com.moneat.shared.services.CacheService
import com.moneat.utils.ClickHouseQueryUtils
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }
private const val CACHE_TTL_SECONDS = 60L
private const val MAX_SAMPLING_RATE = 1.0f
private const val MIN_SAMPLING_RATE = 0.0f
private const val MAX_RETENTION_DAYS = 365

class LogIndexService {

    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val queryParser = LogQueryParser()

    fun create(
        organizationId: Int,
        request: CreateLogIndexRequest
    ): LogIndexResponse {
        val name = request.name.trim()
        if (name.isEmpty()) throw IllegalArgumentException("Index name cannot be empty")
        val now = Clock.System.now()
        val id = transaction {
            LogIndexes.insert {
                it[LogIndexes.organizationId] = organizationId
                it[LogIndexes.name] = name
                it[LogIndexes.filterQuery] = request.filterQuery
                it[LogIndexes.retentionDays] = request.retentionDays
                    .coerceIn(1, MAX_RETENTION_DAYS)
                it[LogIndexes.samplingRate] = request.samplingRate
                    .coerceIn(MIN_SAMPLING_RATE, MAX_SAMPLING_RATE)
                it[LogIndexes.priority] = request.priority
                it[LogIndexes.isActive] = true
                it[LogIndexes.dailyQuotaGb] = request.dailyQuotaGb
                it[LogIndexes.createdAt] = now
                it[LogIndexes.updatedAt] = now
            }[LogIndexes.id]
        }
        invalidateCache(organizationId)
        return getById(organizationId, id)!!
    }

    fun update(
        organizationId: Int,
        indexId: Int,
        request: UpdateLogIndexRequest
    ): LogIndexResponse? {
        val now = Clock.System.now()
        val updated = transaction {
            LogIndexes.update({
                (LogIndexes.id eq indexId) and
                    (LogIndexes.organizationId eq organizationId)
            }) {
                request.name?.let { v ->
                    val trimmed = v.trim()
                    if (trimmed.isEmpty()) throw IllegalArgumentException("Index name cannot be empty")
                    it[LogIndexes.name] = trimmed
                }
                request.filterQuery?.let { v ->
                    it[LogIndexes.filterQuery] = v
                }
                request.retentionDays?.let { v ->
                    it[LogIndexes.retentionDays] =
                        v.coerceIn(1, MAX_RETENTION_DAYS)
                }
                request.samplingRate?.let { v ->
                    it[LogIndexes.samplingRate] =
                        v.coerceIn(MIN_SAMPLING_RATE, MAX_SAMPLING_RATE)
                }
                request.priority?.let { v ->
                    it[LogIndexes.priority] = v
                }
                request.isActive?.let { v ->
                    it[LogIndexes.isActive] = v
                }
                request.dailyQuotaGb?.let { v ->
                    it[LogIndexes.dailyQuotaGb] = v
                }
                it[LogIndexes.updatedAt] = now
            }
        }
        if (updated == 0) return null
        invalidateCache(organizationId)
        return getById(organizationId, indexId)
    }

    fun delete(organizationId: Int, indexId: Int): Boolean {
        val deleted = transaction {
            LogIndexes.deleteWhere {
                (LogIndexes.id eq indexId) and
                    (LogIndexes.organizationId eq organizationId)
            }
        }
        if (deleted > 0) invalidateCache(organizationId)
        return deleted > 0
    }

    fun list(organizationId: Int): List<LogIndexResponse> {
        return transaction {
            LogIndexes
                .selectAll()
                .where { LogIndexes.organizationId eq organizationId }
                .orderBy(LogIndexes.priority)
                .map { toResponse(it) }
        }
    }

    fun getById(
        organizationId: Int,
        indexId: Int
    ): LogIndexResponse? {
        return transaction {
            LogIndexes
                .selectAll()
                .where {
                    (LogIndexes.id eq indexId) and
                        (LogIndexes.organizationId eq organizationId)
                }
                .firstOrNull()
                ?.let { toResponse(it) }
        }
    }

    /**
     * Get active indexes for an org, cached in Redis for fast
     * ingestion-path lookups.
     */
    suspend fun getActiveIndexesCached(
        organizationId: Int
    ): List<LogIndexResponse> {
        return CacheService.cached(
            "logindex:active:$organizationId",
            CACHE_TTL_SECONDS
        ) {
            transaction {
                LogIndexes
                    .selectAll()
                    .where {
                        (LogIndexes.organizationId eq organizationId) and
                            (LogIndexes.isActive eq true)
                    }
                    .orderBy(LogIndexes.priority)
                    .map { toResponse(it) }
            }
        }
    }

    /**
     * Evaluate a log entry against an org's indexes and return the
     * matching index name. Returns empty string if no index matches.
     */
    suspend fun matchIndex(
        organizationId: Int,
        logEntry: Map<String, String>
    ): String {
        val indexes = getActiveIndexesCached(organizationId)
        for (index in indexes) {
            if (index.filterQuery.isBlank()) return index.name
            try {
                if (matchesFilter(index.filterQuery, logEntry)) {
                    return index.name
                }
            } catch (e: Exception) {
                logger.warn(e) {
                    "Filter evaluation failed for index '${index.name}' " +
                        "query='${index.filterQuery}'; skipping"
                }
            }
        }
        return ""
    }

    /**
     * Test a filter query against the last hour of logs and return
     * match/total counts.
     */
    suspend fun testFilter(
        organizationId: Int,
        filterQuery: String
    ): LogIndexTestResponse {
        val orgClause = ClickHouseQueryUtils.orgIdClause(
            organizationId.toLong()
        )
        val timeClause =
            "timestamp >= now() - INTERVAL 1 HOUR"

        val totalSql = """
            SELECT count() AS cnt
            FROM $clickhouseDb.logs
            WHERE $orgClause AND $timeClause
            FORMAT JSONEachRow
        """.trimIndent()

        val totalCount = executeCountQuery(totalSql)

        val matchCount = if (filterQuery.isBlank()) {
            totalCount
        } else {
            try {
                val parsed = queryParser.parse(filterQuery)
                if (parsed.rootNode == null) {
                    totalCount
                } else {
                    val filterSql = queryParser.toClickHouseSql(
                        parsed.rootNode,
                        ::escapeSql
                    )
                    val matchSql = """
                        SELECT count() AS cnt
                        FROM $clickhouseDb.logs
                        WHERE $orgClause AND $timeClause
                          AND ($filterSql)
                        FORMAT JSONEachRow
                    """.trimIndent()
                    executeCountQuery(matchSql)
                }
            } catch (e: Exception) {
                logger.warn(e) {
                    "Failed to test filter query: $filterQuery"
                }
                0L
            }
        }

        return LogIndexTestResponse(
            matchCount = matchCount,
            totalCount = totalCount
        )
    }

    private fun matchesFilter(
        filterQuery: String,
        logEntry: Map<String, String>
    ): Boolean {
        val parsed = queryParser.parse(filterQuery)
        if (parsed.rootNode == null) return true
        return evaluateNode(parsed.rootNode, logEntry)
    }

    private fun evaluateNode(
        node: LogQueryParser.QueryNode,
        entry: Map<String, String>
    ): Boolean {
        return when (node) {
            is LogQueryParser.QueryNode.FieldNode -> {
                val value = entry[node.field] ?: return false
                if (node.isWildcard) {
                    val escaped = Regex.escape(node.value)
                    val pattern = escaped
                        .replace("\\*", ".*")
                        .replace("\\?", ".")
                    try {
                        value.matches(Regex(pattern, RegexOption.IGNORE_CASE))
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    value.equals(node.value, ignoreCase = true)
                }
            }
            is LogQueryParser.QueryNode.FullTextNode -> {
                val searchFields = listOf(
                    "message",
                    "body",
                    "service",
                    "environment",
                    "host",
                    "container_name"
                )
                searchFields.any { field ->
                    val v = entry[field] ?: return@any false
                    v.contains(node.term, ignoreCase = true)
                }
            }
            is LogQueryParser.QueryNode.AndNode ->
                evaluateNode(node.left, entry) &&
                    evaluateNode(node.right, entry)
            is LogQueryParser.QueryNode.OrNode ->
                evaluateNode(node.left, entry) ||
                    evaluateNode(node.right, entry)
            is LogQueryParser.QueryNode.NotNode ->
                !evaluateNode(node.node, entry)
            is LogQueryParser.QueryNode.ExistsNode -> {
                val v = entry[node.field]
                v != null && v.isNotEmpty()
            }
            is LogQueryParser.QueryNode.TagExistsNode -> {
                entry.containsKey(node.tagKey)
            }
            is LogQueryParser.QueryNode.TermNode -> {
                entry.values.any {
                    it.contains(node.term, ignoreCase = true)
                }
            }
            is LogQueryParser.QueryNode.RangeNode -> {
                val v = entry[node.field]?.toDoubleOrNull()
                    ?: return false
                val min = node.min.toDoubleOrNull() ?: return false
                val max = node.max.toDoubleOrNull() ?: return false
                v in min..max
            }
            is LogQueryParser.QueryNode.ComparisonNode -> {
                val v = entry[node.field]?.toDoubleOrNull()
                    ?: return false
                val target = node.value.toDoubleOrNull()
                    ?: return false
                when (node.operator) {
                    ">" -> v > target
                    ">=" -> v >= target
                    "<" -> v < target
                    "<=" -> v <= target
                    else -> false
                }
            }
        }
    }

    private suspend fun executeCountQuery(sql: String): Long {
        return try {
            val response = ClickHouseClient.execute(sql)
            val body = response.bodyAsText()
            if (!response.status.isSuccess() ||
                body.trimStart().startsWith("Code:")
            ) {
                0L
            } else {
                json.parseToJsonElement(body.trim())
                    .jsonObject["cnt"]
                    ?.jsonPrimitive
                    ?.longOrNull ?: 0L
            }
        } catch (e: Exception) {
            logger.warn(e) { "Count query failed: $sql" }
            0L
        }
    }

    private fun toResponse(
        row: org.jetbrains.exposed.v1.core.ResultRow
    ): LogIndexResponse {
        return LogIndexResponse(
            id = row[LogIndexes.id],
            name = row[LogIndexes.name],
            filterQuery = row[LogIndexes.filterQuery],
            retentionDays = row[LogIndexes.retentionDays],
            samplingRate = row[LogIndexes.samplingRate],
            priority = row[LogIndexes.priority],
            isActive = row[LogIndexes.isActive],
            dailyQuotaGb = row[LogIndexes.dailyQuotaGb],
            createdAt = row[LogIndexes.createdAt].toString(),
            updatedAt = row[LogIndexes.updatedAt].toString()
        )
    }

    private fun invalidateCache(organizationId: Int) {
        CacheService.invalidate("logindex:active:$organizationId")
    }

    private fun escapeSql(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("'", "\\'")
    }
}
