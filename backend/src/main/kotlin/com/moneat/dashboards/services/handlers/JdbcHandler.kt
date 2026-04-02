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

import com.moneat.utils.suspendRunCatching
import com.moneat.dashboards.models.DataSourceField
import com.moneat.dashboards.models.TestConnectionRequest
import com.moneat.dashboards.models.TestConnectionResult
import com.moneat.dashboards.models.TimeRangeDef
import com.moneat.dashboards.services.DataSourceCredentials
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import mu.KotlinLogging
import java.sql.ResultSet
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
    }

    protected abstract fun buildJdbcUrl(host: String, port: Int, database: String): String
    protected abstract fun defaultPort(): Int
    protected abstract fun schemaIntrospectionQuery(): String
    protected abstract fun testConnectionQuery(): String
    protected abstract fun forbiddenKeywords(): List<Pair<Regex, String>>
    protected open fun forbiddenFunctionPatterns(): List<Pair<Regex, String>> = emptyList()
    protected open fun schemaFieldsQuery(): String? = null // Override for column-level schema

    override suspend fun testConnection(request: TestConnectionRequest): TestConnectionResult {
        val port = request.port ?: defaultPort()
        val database = request.databaseName ?: defaultDatabase()
        return suspendRunCatching {
            val ds = createTempDataSource(request.host, port, database, request.username, request.password)
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
        validateSqlQuery(query)
        val p = port ?: defaultPort()
        val db = databaseName ?: defaultDatabase()
        val ds = getOrCreatePool(sourceId, host, p, db, credentials)
        return ds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.maxRows = limit.coerceIn(1, QUERY_MAX_ROWS)
                stmt.queryTimeout = QUERY_TIMEOUT_SECONDS
                stmt.executeQuery(query).use { rs -> resultSetToMaps(rs) }
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
        val ds = createTempDataSource(host, p, db, credentials.username, credentials.password)
        return try {
            ds.connection.use { conn ->
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
                fields
            }
        } finally {
            ds.close()
        }
    }

    protected open fun defaultDatabase(): String = ""

    private fun getOrCreatePool(
        sourceId: Long,
        host: String,
        port: Int,
        database: String,
        credentials: DataSourceCredentials,
    ): HikariDataSource = pools.computeIfAbsent(sourceId) {
        createPool(host, port, database, credentials.username, credentials.password)
    }

    protected open fun createPool(
        host: String,
        port: Int,
        database: String,
        username: String?,
        password: String?,
    ): HikariDataSource {
        val config = HikariConfig().apply {
            setDriverClassName(driverClass)
            jdbcUrl = buildJdbcUrl(host, port, database)
            this.username = username ?: ""
            this.password = password ?: ""
            maximumPoolSize = POOL_MAX_SIZE
            minimumIdle = 0
            idleTimeout = POOL_IDLE_TIMEOUT_MS
            maxLifetime = POOL_MAX_LIFETIME_MS
            connectionTimeout = POOL_CONNECTION_TIMEOUT_MS
            isReadOnly = true
            addDataSourceProperty("ApplicationName", "moneat-custom-datasource")
        }
        return HikariDataSource(config)
    }

    private fun createTempDataSource(
        host: String,
        port: Int,
        database: String,
        username: String?,
        password: String?,
    ): HikariDataSource {
        val config = HikariConfig().apply {
            setDriverClassName(driverClass)
            jdbcUrl = buildJdbcUrl(host, port, database)
            this.username = username ?: ""
            this.password = password ?: ""
            maximumPoolSize = 1
            connectionTimeout = POOL_CONNECTION_TIMEOUT_MS
            isReadOnly = true
        }
        return HikariDataSource(config)
    }

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

    private fun hasSemicolonOutsideQuotes(query: String): Boolean {
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
                c == ';' -> return true
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
        require(trimmed.startsWith("SELECT")) { "Only SELECT queries are allowed" }
        require(!hasSemicolonOutsideQuotes(normalized)) { "Multiple statements are not allowed" }
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
}
