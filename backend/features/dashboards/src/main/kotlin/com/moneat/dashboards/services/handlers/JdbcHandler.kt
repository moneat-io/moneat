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

package com.moneat.dashboards.services.handlers

import com.moneat.dashboards.models.DataSourceField
import com.moneat.dashboards.models.TestConnectionRequest
import com.moneat.dashboards.models.TestConnectionResult
import com.moneat.dashboards.models.TimeRangeDef
import com.moneat.dashboards.services.DataSourceCredentials
import com.moneat.utils.TimeConstants.MILLIS_PER_SECOND_LONG
import com.moneat.utils.suspendRunCatching
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import mu.KotlinLogging
import java.sql.Connection
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

abstract class JdbcHandler(
    private val driverClass: String,
    private val pools: ConcurrentHashMap<Long, HikariDataSource>,
) : DataSourceHandler {

    companion object {
        private const val QUERY_MAX_ROWS = 10_000
        private const val QUERY_TIMEOUT_SECONDS = 30
        private const val SCHEMA_COMMENT_COLUMN = 3
        private const val POOL_MAX_SIZE = 3
        private const val POOL_MIN_IDLE = 0
        private const val POOL_IDLE_TIMEOUT_MS = 60_000L
        private const val POOL_MAX_LIFETIME_MS = 300_000L
        private const val POOL_CONNECTION_TIMEOUT_MS = 10_000L
        private const val TEMP_POOL_MAX_SIZE = 1
        private const val DEFAULT_MACRO_INTERVAL_SECONDS = 60L
        private const val SECONDS_PER_MINUTE = 60L
        private const val SECONDS_PER_HOUR = 3_600L
        private const val SECONDS_PER_DAY = 86_400L
        private const val SECONDS_PER_WEEK = 604_800L
        private const val DAYS_PER_WEEK = 7L
        private const val DAYS_PER_MONTH = 30L
        private const val DAYS_PER_YEAR = 365L
        private const val EPOCH_MILLIS_DIVISOR = 1_000L

        private val TIMESTAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
        private val RELATIVE_TIME_REGEX = Regex("""^now-(\d+)([smhdwMy])$""")
        private val INTERVAL_REGEX = Regex("""^(\d+)\s*([smhdwMy])$""")
        private val TIME_GROUP_ALIAS_REGEX =
            Regex("""\${'$'}__timeGroupAlias\(([^,]+),\s*['"]?([^'",)]+)['"]?(?:,\s*[^)]*)?\)""")
        private val TIME_GROUP_REGEX =
            Regex("""\${'$'}__timeGroup\(([^,]+),\s*['"]?([^'",)]+)['"]?(?:,\s*[^)]*)?\)""")
        private val TIME_FILTER_REGEX = Regex("""\${'$'}__timeFilter\(([^)]+)\)""")
        private val TIME_MACRO_REGEX = Regex("""\${'$'}__time\(([^)]+)\)""")
        private val UNIX_EPOCH_FILTER_REGEX = Regex("""\${'$'}__unixEpochFilter\(([^)]+)\)""")
        private val UNIX_EPOCH_FROM_REGEX = Regex("""\${'$'}__unixEpochFrom\(\)""")
        private val UNIX_EPOCH_TO_REGEX = Regex("""\${'$'}__unixEpochTo\(\)""")
        private val TIME_FROM_REGEX = Regex("""\${'$'}__timeFrom\(\)""")
        private val TIME_TO_REGEX = Regex("""\${'$'}__timeTo\(\)""")
    }

    internal abstract fun buildJdbcUrl(host: String, port: Int, database: String, options: ConnectionOptions): String
    protected abstract fun defaultPort(): Int
    protected abstract fun schemaIntrospectionQuery(): String
    protected abstract fun testConnectionQuery(): String
    protected abstract fun forbiddenKeywords(): List<Pair<Regex, String>>
    protected open fun forbiddenFunctionPatterns(): List<Pair<Regex, String>> = emptyList()
    protected open fun schemaFieldsQuery(): String? = null // Override for column-level schema

    override suspend fun testConnection(request: TestConnectionRequest): TestConnectionResult {
        val port = request.port ?: defaultPort()
        val database = request.databaseName ?: defaultDatabase()
        val credentials = request.toCredentials()
        return suspendRunCatching {
            val ds = createTempDataSource(request.host, port, database, credentials)
            try {
                ds.connection.use { conn ->
                    val tables = mutableListOf<String>()
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery(testConnectionQuery()).use { rs ->
                            while (rs.next()) tables.add(rs.getString(1))
                        }
                    }
                    TestConnectionResult(true, "Connected successfully", tables = tables)
                }
            } finally {
                ds.close()
            }
        }.getOrElse { e ->
            logger.warn(e) { "JDBC connection test failed" }
            TestConnectionResult(false, "Connection failed: ${e.message}")
        }
    }

    override suspend fun executeQuery(
        sourceId: Long,
        host: String,
        port: Int?,
        databaseName: String?,
        credentials: DataSourceCredentials,
        query: String,
        limit: Int,
        timeRange: TimeRangeDef?,
    ): List<Map<String, JsonElement>> {
        val preparedQuery = prepareSqlQuery(query, timeRange)
        validateSqlQuery(preparedQuery)
        val p = port ?: defaultPort()
        val db = databaseName ?: defaultDatabase()
        val ds = getOrCreatePool(sourceId, host, p, db, credentials)
        return ds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.maxRows = limit.coerceIn(1, QUERY_MAX_ROWS)
                stmt.queryTimeout = credentials.options.timeoutSeconds ?: QUERY_TIMEOUT_SECONDS
                stmt.executeQuery(preparedQuery).use { rs -> resultSetToMaps(rs) }
            }
        }
    }

    override suspend fun getSchema(
        host: String,
        port: Int?,
        databaseName: String?,
        credentials: DataSourceCredentials,
    ): List<DataSourceField> {
        val p = port ?: defaultPort()
        val db = databaseName ?: defaultDatabase()
        val ds = createTempDataSource(host, p, db, credentials)
        return try {
            ds.connection.use { conn -> readJdbcSchemaFields(conn) }
        } finally {
            ds.close()
        }
    }

    private fun readJdbcSchemaFields(conn: Connection): List<DataSourceField> {
        val fields = mutableListOf<DataSourceField>()
        val query = schemaFieldsQuery() ?: schemaIntrospectionQuery()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(query).use { rs ->
                while (rs.next()) {
                    fields.add(
                        DataSourceField(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getString(SCHEMA_COMMENT_COLUMN).orEmpty(),
                        )
                    )
                }
            }
        }
        return fields
    }

    protected open fun defaultDatabase(): String = ""

    internal fun prepareSqlQuery(query: String, timeRange: TimeRangeDef?): String {
        val range = resolvedTimeRange(timeRange)
        val fromLiteral = timestampLiteral(range.from)
        val toLiteral = timestampLiteral(range.to)
        val fromEpochSeconds = range.from.toEpochMilli() / EPOCH_MILLIS_DIVISOR
        val toEpochSeconds = range.to.toEpochMilli() / EPOCH_MILLIS_DIVISOR

        var expanded = query
        expanded = TIME_GROUP_ALIAS_REGEX.replace(expanded) { match ->
            val column = match.groupValues[1].trim()
            val intervalSeconds = intervalSeconds(match.groupValues[2])
            "${timeBucketExpression(column, intervalSeconds)} AS time"
        }
        expanded = TIME_GROUP_REGEX.replace(expanded) { match ->
            val column = match.groupValues[1].trim()
            val intervalSeconds = intervalSeconds(match.groupValues[2])
            timeBucketExpression(column, intervalSeconds)
        }
        expanded = TIME_FILTER_REGEX.replace(expanded) { match ->
            val column = match.groupValues[1].trim()
            "$column BETWEEN $fromLiteral AND $toLiteral"
        }
        expanded = UNIX_EPOCH_FILTER_REGEX.replace(expanded) { match ->
            val column = match.groupValues[1].trim()
            "$column BETWEEN $fromEpochSeconds AND $toEpochSeconds"
        }
        expanded = TIME_MACRO_REGEX.replace(expanded) { match ->
            "${match.groupValues[1].trim()} AS time"
        }
        expanded = TIME_FROM_REGEX.replace(expanded, fromLiteral)
        expanded = TIME_TO_REGEX.replace(expanded, toLiteral)
        expanded = UNIX_EPOCH_FROM_REGEX.replace(expanded, fromEpochSeconds.toString())
        expanded = UNIX_EPOCH_TO_REGEX.replace(expanded, toEpochSeconds.toString())
        return expanded
    }

    protected open fun timestampLiteral(instant: Instant): String =
        "'${TIMESTAMP_FORMATTER.format(instant)}'"

    protected open fun timeBucketExpression(column: String, intervalSeconds: Long): String = column

    private fun getOrCreatePool(
        sourceId: Long,
        host: String,
        port: Int,
        database: String,
        credentials: DataSourceCredentials,
    ): HikariDataSource = pools.computeIfAbsent(sourceId) {
        createPool(host, port, database, credentials)
    }

    protected open fun createPool(
        host: String,
        port: Int,
        database: String,
        credentials: DataSourceCredentials,
    ): HikariDataSource {
        val config = HikariConfig().apply {
            setDriverClassName(driverClass)
            jdbcUrl = buildJdbcUrl(host, port, database, credentials.options)
            this.username = credentials.username ?: ""
            this.password = credentials.password ?: ""
            maximumPoolSize = POOL_MAX_SIZE
            minimumIdle = POOL_MIN_IDLE
            idleTimeout = POOL_IDLE_TIMEOUT_MS
            maxLifetime = POOL_MAX_LIFETIME_MS
            connectionTimeout = connectionTimeoutMs(credentials.options)
            isReadOnly = true
            addDataSourceProperty("ApplicationName", "moneat-custom-datasource")
        }
        return HikariDataSource(config)
    }

    private fun createTempDataSource(
        host: String,
        port: Int,
        database: String,
        credentials: DataSourceCredentials,
    ): HikariDataSource {
        val config = HikariConfig().apply {
            setDriverClassName(driverClass)
            jdbcUrl = buildJdbcUrl(host, port, database, credentials.options)
            this.username = credentials.username ?: ""
            this.password = credentials.password ?: ""
            maximumPoolSize = 1
            connectionTimeout = connectionTimeoutMs(credentials.options)
            isReadOnly = true
        }
        return HikariDataSource(config)
    }

    private fun connectionTimeoutMs(options: ConnectionOptions): Long =
        options.timeoutSeconds?.let { it * MILLIS_PER_SECOND_LONG } ?: POOL_CONNECTION_TIMEOUT_MS

    protected fun resultSetToMaps(rs: ResultSet): List<Map<String, JsonElement>> {
        val meta = rs.metaData
        val cols = (1..meta.columnCount).map { meta.getColumnLabel(it) }
        val rows = mutableListOf<Map<String, JsonElement>>()
        while (rs.next()) {
            val row = mutableMapOf<String, JsonElement>()
            for ((i, col) in cols.withIndex()) {
                val value = rs.getObject(i + 1)
                row[col] = when (value) {
                    null -> JsonNull
                    is Number -> JsonPrimitive(value)
                    is Boolean -> JsonPrimitive(value)
                    else -> JsonPrimitive(value.toString())
                }
            }
            rows.add(row)
        }
        return rows
    }

    private fun hasNonTrailingSemicolonOutsideQuotes(query: String): Boolean {
        var i = 0
        var inSingle = false
        var inDouble = false
        while (i < query.length) {
            val c = query[i]
            when {
                inSingle -> {
                    if (c == '\'' && i + 1 < query.length && query[i + 1] == '\'') {
                        i++
                    } else if (c == '\'') {
                        inSingle = false
                    }
                }
                inDouble -> {
                    if (c == '"') inDouble = false
                }
                c == '\'' -> inSingle = true
                c == '"' -> inDouble = true
                c == ';' -> return query.drop(i + 1).any { !it.isWhitespace() }
            }
            i++
        }
        return false
    }

    private fun stripSqlComments(query: String): String {
        // MySQL conditional comments: keep the SQL content, strip only the markers
        val conditionalStripped = Regex("""/\*!([\s\S]*?)\*/""").replace(query) { it.groupValues[1] }
        // Regular block comments: collapse to "" so INS/**/ERT becomes INSERT
        val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(conditionalStripped, "")
        // Line comments (-- and MySQL #)
        val noLine = Regex("""(--|#)[^\n]*""").replace(noBlock, "")
        return noLine
    }

    internal fun validateSqlQuery(query: String) {
        val normalized = stripSqlComments(
            java.text.Normalizer.normalize(query, java.text.Normalizer.Form.NFKC)
        )
        val trimmed = normalized.trim().uppercase()
        require(trimmed.startsWith("SELECT") || trimmed.startsWith("WITH")) {
            "Only SELECT or WITH queries are allowed"
        }
        require(!hasNonTrailingSemicolonOutsideQuotes(normalized)) { "Multiple statements are not allowed" }
        for ((pattern, name) in forbiddenKeywords()) {
            require(!pattern.containsMatchIn(normalized)) { "$name statements are not allowed" }
        }
        for ((pattern, fn) in forbiddenFunctionPatterns()) {
            require(!pattern.containsMatchIn(normalized)) { "Function or table $fn is not allowed" }
        }
    }

    fun closePool(sourceId: Long) {
        pools.remove(sourceId)?.close()
    }

    private data class ResolvedTimeRange(
        val from: Instant,
        val to: Instant,
    )

    private fun resolvedTimeRange(timeRange: TimeRangeDef?): ResolvedTimeRange {
        val now = Instant.now()
        val range = timeRange ?: TimeRangeDef()
        return ResolvedTimeRange(
            from = resolveTimeExpression(range.from, now),
            to = resolveTimeExpression(range.to, now),
        )
    }

    private fun resolveTimeExpression(expr: String, now: Instant): Instant {
        if (expr == "now") return now
        val match = RELATIVE_TIME_REGEX.matchEntire(expr)
        if (match != null) {
            val amount = match.groupValues[1].toLong()
            val duration = when (match.groupValues[2]) {
                "s" -> Duration.ofSeconds(amount)
                "m" -> Duration.ofMinutes(amount)
                "h" -> Duration.ofHours(amount)
                "d" -> Duration.ofDays(amount)
                "w" -> Duration.ofDays(amount * DAYS_PER_WEEK)
                "M" -> Duration.ofDays(amount * DAYS_PER_MONTH)
                "y" -> Duration.ofDays(amount * DAYS_PER_YEAR)
                else -> Duration.ZERO
            }
            return now.minus(duration)
        }
        return parseAbsoluteInstant(expr)
    }

    private fun parseAbsoluteInstant(expr: String): Instant {
        val normalized = expr.trim().replace(' ', 'T')
        return runCatching { Instant.parse(normalized) }
            .getOrElse {
                runCatching { OffsetDateTime.parse(normalized).toInstant() }
                    .getOrElse { LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC) }
            }
    }

    private fun intervalSeconds(raw: String): Long {
        val normalized = raw.trim().removeSurrounding("'").removeSurrounding("\"")
        if (normalized == "\${'$'}__interval") return DEFAULT_MACRO_INTERVAL_SECONDS
        val match = INTERVAL_REGEX.matchEntire(normalized) ?: return DEFAULT_MACRO_INTERVAL_SECONDS
        val amount = match.groupValues[1].toLong()
        return when (match.groupValues[2]) {
            "s" -> amount
            "m" -> amount * SECONDS_PER_MINUTE
            "h" -> amount * SECONDS_PER_HOUR
            "d" -> amount * SECONDS_PER_DAY
            "w" -> amount * SECONDS_PER_WEEK
            "M" -> amount * DAYS_PER_MONTH * SECONDS_PER_DAY
            "y" -> amount * DAYS_PER_YEAR * SECONDS_PER_DAY
            else -> DEFAULT_MACRO_INTERVAL_SECONDS
        }
    }
}
