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
import com.moneat.logs.models.CreateLogIndexRequest
import com.moneat.logs.models.LogIndexResponse
import com.moneat.logs.models.LogIndexTestResponse
import com.moneat.logs.models.LogIndexUsageResponse
import com.moneat.logs.models.QueuedLogEntry
import com.moneat.logs.models.UpdateLogIndexRequest
import com.moneat.shared.models.LogIndexes
import com.moneat.shared.services.CacheService
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import com.moneat.utils.suspendRunCatching
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.text.Charsets
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }
private const val CACHE_TTL_SECONDS = 60L
private const val QUOTA_USAGE_CACHE_TTL_MILLIS = 30_000L
private const val MAX_SAMPLING_RATE = 1.0f
private const val MIN_SAMPLING_RATE = 0.0f
private const val MAX_RETENTION_DAYS = 365
private const val BYTES_PER_GB = 1024L * 1024L * 1024L
private const val ERROR_BODY_SHORT_CHARS = 500
private const val POSTGRES_UNIQUE_VIOLATION_SQLSTATE = "23505"

class LogIndexService {

    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val queryParser = LogQueryParser()
    private val filterEvaluator = LogEntryFilterEvaluator(queryParser)
    private val quotaUsageCache = ConcurrentHashMap<Int, QuotaUsageCacheEntry>()

    fun create(
        organizationId: Int,
        request: CreateLogIndexRequest
    ): LogIndexResponse {
        val name = request.name.trim()
        if (name.isEmpty()) throw IllegalArgumentException("Index name cannot be empty")
        ensureIndexNameAvailable(organizationId, name)
        val now = Clock.System.now()
        val id = mapDuplicateIndexName {
            transaction {
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
        }
        invalidateCache(organizationId)
        return getById(organizationId, id)!!
    }

    fun update(
        organizationId: Int,
        indexId: Int,
        request: UpdateLogIndexRequest
    ): LogIndexResponse? {
        request.name?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
            ensureIndexNameAvailable(organizationId, name, indexId)
        }
        val now = Clock.System.now()
        val updated = mapDuplicateIndexName {
            transaction {
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
            suspendRunCatching {
                if (matchesFilter(index.filterQuery, logEntry)) {
                    return index.name
                }
            }.getOrElse { e ->
                logger.warn(e) {
                    "Filter evaluation failed for index '${index.name}' " +
                        "query='${index.filterQuery}'; skipping"
                }
            }
        }
        return ""
    }

    suspend fun usageStats(organizationId: Int): List<LogIndexUsageResponse> {
        val indexes = list(organizationId).associateBy { it.name }
        val orgClause = ClickHouseQueryUtils.orgIdClause(organizationId.toLong())
        val sql = """
            SELECT
                index_name,
                count() AS cnt,
                sum(length(message) + length(body)) AS bytes
            FROM `$clickhouseDb`.logs
            WHERE $orgClause
              AND timestamp >= toStartOfDay(now())
              AND index_name != ''
            GROUP BY index_name
            FORMAT JSONEachRow
        """.trimIndent()

        val rows = executeUsageQuery(sql)
        return indexes.values.map { index ->
            val row = rows[index.name]
            LogIndexUsageResponse(
                indexName = index.name,
                bytesToday = row?.bytes ?: 0L,
                countToday = row?.count ?: 0L,
                quotaGb = index.dailyQuotaGb,
                retentionDays = index.retentionDays
            )
        }
    }

    suspend fun enforceRetention(organizationId: Int): Int {
        var applied = 0
        for (index in list(organizationId)) {
            val sql = """
                ALTER TABLE `$clickhouseDb`.logs
                DELETE WHERE organization_id = ${organizationId.toLong()}
                  AND index_name = '${escapeSql(index.name)}'
                  AND timestamp < now() - INTERVAL ${index.retentionDays} DAY
            """.trimIndent()
            val response = ClickHouseClient.execute(sql)
            val body = response.bodyAsText()
            if (response.isClickHouseError(body)) {
                logger.warn {
                    "Retention delete failed for log index '${index.name}': " +
                        body.take(ERROR_BODY_SHORT_CHARS)
                }
            } else {
                applied += 1
            }
        }
        return applied
    }

    suspend fun filterWithinDailyQuota(
        organizationId: Int,
        entries: List<QueuedLogEntry>,
        indexes: List<LogIndexResponse>
    ): List<QueuedLogEntry> {
        if (entries.isEmpty()) return entries
        val quotaIndexes = indexes
            .filter { it.dailyQuotaGb != null && it.dailyQuotaGb > 0f }
            .associateBy { it.name }
        if (quotaIndexes.isEmpty()) return entries

        val usedBytes = cachedQuotaUsageBytes(organizationId)

        return synchronized(usedBytes) {
            entries.filter { entry ->
                val index = quotaIndexes[entry.indexName] ?: return@filter true
                val quotaBytes = ((index.dailyQuotaGb ?: return@filter true) * BYTES_PER_GB).toLong()
                val entryBytes = entry.quotaBytes()
                val current = usedBytes[entry.indexName] ?: 0L
                if (current + entryBytes > quotaBytes) {
                    false
                } else {
                    usedBytes[entry.indexName] = current + entryBytes
                    true
                }
            }
        }
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
            FROM `$clickhouseDb`.logs
            WHERE $orgClause AND $timeClause
            FORMAT JSONEachRow
        """.trimIndent()

        val totalCount = executeCountQuery(totalSql)

        val matchCount = if (filterQuery.isBlank()) {
            totalCount
        } else {
            suspendRunCatching {
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
                        FROM `$clickhouseDb`.logs
                        WHERE $orgClause AND $timeClause
                          AND ($filterSql)
                        FORMAT JSONEachRow
                    """.trimIndent()
                    executeCountQuery(matchSql)
                }
            }.getOrElse { e ->
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
        return filterEvaluator.matches(filterQuery, logEntry)
    }

    private suspend fun executeCountQuery(sql: String): Long {
        return suspendRunCatching {
            val response = ClickHouseClient.execute(sql)
            val body = response.bodyAsText()
            if (response.isClickHouseError(body)) {
                0L
            } else {
                json.parseToJsonElement(body.trim())
                    .jsonObject["cnt"]
                    ?.jsonPrimitive
                    ?.longOrNull ?: 0L
            }
        }.getOrElse { e ->
            logger.warn(e) { "Count query failed: $sql" }
            0L
        }
    }

    private suspend fun executeUsageQuery(sql: String): Map<String, UsageRow> {
        return suspendRunCatching {
            val response = ClickHouseClient.execute(sql)
            val body = response.bodyAsText()
            if (response.isClickHouseError(body)) {
                logger.warn { "Usage query failed: ${body.take(ERROR_BODY_SHORT_CHARS)}" }
                emptyMap()
            } else {
                body.lineSequence()
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        val obj = json.parseToJsonElement(line).jsonObject
                        val name = obj["index_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val count = obj["cnt"]?.jsonPrimitive?.longOrNull ?: 0L
                        val bytes = obj["bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                        name to UsageRow(count = count, bytes = bytes)
                    }
                    .toMap()
            }
        }.getOrElse { e ->
            logger.warn(e) { "Usage query failed: $sql" }
            emptyMap()
        }
    }

    private data class UsageRow(
        val count: Long,
        val bytes: Long
    )

    private data class QuotaUsageCacheEntry(
        val expiresAtMillis: Long,
        val bytesByIndex: MutableMap<String, Long>
    )

    private suspend fun cachedQuotaUsageBytes(organizationId: Int): MutableMap<String, Long> {
        val nowMillis = Clock.System.now().toEpochMilliseconds()
        val cached = quotaUsageCache[organizationId]
        if (cached != null && cached.expiresAtMillis > nowMillis) {
            return cached.bytesByIndex
        }
        val fresh = usageStats(organizationId).associate { usage ->
            usage.indexName to usage.bytesToday
        }.toMutableMap()
        quotaUsageCache[organizationId] = QuotaUsageCacheEntry(
            expiresAtMillis = nowMillis + QUOTA_USAGE_CACHE_TTL_MILLIS,
            bytesByIndex = fresh
        )
        return fresh
    }

    private fun QueuedLogEntry.quotaBytes(): Long =
        (message.toByteArray(Charsets.UTF_8).size + body.toByteArray(Charsets.UTF_8).size).toLong()

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

    private fun ensureIndexNameAvailable(
        organizationId: Int,
        name: String,
        excludeId: Int? = null
    ) {
        val duplicate = transaction {
            LogIndexes
                .selectAll()
                .where {
                    (LogIndexes.organizationId eq organizationId) and
                        (LogIndexes.name eq name)
                }
                .firstOrNull()
                ?.let { excludeId == null || it[LogIndexes.id] != excludeId }
                ?: false
        }
        require(!duplicate) { "Index name already exists" }
    }

    private fun <T> mapDuplicateIndexName(block: () -> T): T =
        try {
            block()
        } catch (error: ExposedSQLException) {
            require(error.sqlState != POSTGRES_UNIQUE_VIOLATION_SQLSTATE) { "Index name already exists" }
            throw error
        }

    private fun invalidateCache(organizationId: Int) {
        CacheService.invalidate("logindex:active:$organizationId")
        quotaUsageCache.remove(organizationId)
    }
}
