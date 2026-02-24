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

package com.moneat.dashboards.services

import com.moneat.dashboards.models.CustomDataSourceType
import com.moneat.dashboards.models.DataSourceField
import com.moneat.dashboards.models.TestConnectionRequest
import com.moneat.dashboards.models.TestConnectionResult
import com.moneat.dashboards.models.TimeRangeDef
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.sql.ResultSet
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

class CustomDataSourceExecutor {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 30_000
            endpoint { connectTimeout = 10_000 }
        }
    }

    // Short-lived connection pools for PostgreSQL sources, keyed by data source ID
    private val pgPools = ConcurrentHashMap<Long, HikariDataSource>()

    /**
     * Test connectivity to a data source without persisting it.
     */
    suspend fun testConnection(request: TestConnectionRequest): TestConnectionResult {
        val sourceType = CustomDataSourceType.fromString(request.sourceType)
            ?: return TestConnectionResult(false, "Unsupported source type: ${request.sourceType}")

        return when (sourceType) {
            CustomDataSourceType.POSTGRESQL -> testPostgresConnection(
                host = request.host,
                port = request.port ?: 5432,
                database = request.databaseName ?: "postgres",
                username = request.username,
                password = request.password,
            )
            CustomDataSourceType.PROMETHEUS -> testPrometheusConnection(
                host = request.host,
                port = request.port,
                apiKey = request.apiKey,
            )
        }
    }

    /**
     * Execute a query against a custom data source.
     */
    suspend fun executeQuery(
        sourceId: Long,
        sourceType: CustomDataSourceType,
        host: String,
        port: Int?,
        databaseName: String?,
        credentials: DataSourceCredentials,
        query: String,
        limit: Int = 100,
        timeRange: TimeRangeDef? = null,
    ): List<Map<String, JsonElement>> {
        return when (sourceType) {
            CustomDataSourceType.POSTGRESQL -> executePostgresQuery(
                sourceId,
                host,
                port ?: 5432,
                databaseName ?: "postgres",
                credentials,
                query,
                limit
            )
            CustomDataSourceType.PROMETHEUS -> {
                val promLimit = if (timeRange != null) limit.coerceAtLeast(5000) else limit
                executePrometheusQuery(host, port, credentials, query, timeRange, promLimit)
            }
        }
    }

    /**
     * Get schema info (tables/metrics) for a custom data source.
     */
    suspend fun getSchema(
        sourceType: CustomDataSourceType,
        host: String,
        port: Int?,
        databaseName: String?,
        credentials: DataSourceCredentials,
    ): List<DataSourceField> {
        return when (sourceType) {
            CustomDataSourceType.POSTGRESQL -> getPostgresSchema(
                host,
                port ?: 5432,
                databaseName ?: "postgres",
                credentials
            )
            CustomDataSourceType.PROMETHEUS -> getPrometheusMetrics(host, port, credentials)
        }
    }

    // --- PostgreSQL ---

    private fun testPostgresConnection(
        host: String,
        port: Int,
        database: String,
        username: String?,
        password: String?,
    ): TestConnectionResult {
        return try {
            val ds = createTempPgDataSource(host, port, database, username, password)
            try {
                ds.connection.use { conn ->
                    val tables = mutableListOf<String>()
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery(
                            """SELECT table_name FROM information_schema.tables 
                               WHERE table_schema = 'public' ORDER BY table_name LIMIT 50"""
                        ).use { rs ->
                            while (rs.next()) tables.add(rs.getString(1))
                        }
                    }
                    TestConnectionResult(true, "Connected successfully", tables = tables)
                }
            } finally {
                ds.close()
            }
        } catch (e: Exception) {
            logger.warn(e) { "PostgreSQL connection test failed" }
            TestConnectionResult(false, "Connection failed: ${e.message}")
        }
    }

    private fun executePostgresQuery(
        sourceId: Long,
        host: String,
        port: Int,
        database: String,
        credentials: DataSourceCredentials,
        query: String,
        limit: Int,
    ): List<Map<String, JsonElement>> {
        validateSqlQuery(query)
        val ds = getOrCreatePgPool(sourceId, host, port, database, credentials)
        return ds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.maxRows = limit.coerceIn(1, 10000)
                stmt.queryTimeout = 30
                stmt.executeQuery(query).use { rs -> resultSetToMaps(rs) }
            }
        }
    }

    private fun getPostgresSchema(
        host: String,
        port: Int,
        database: String,
        credentials: DataSourceCredentials,
    ): List<DataSourceField> {
        val ds = createTempPgDataSource(host, port, database, credentials.username, credentials.password)
        return try {
            ds.connection.use { conn ->
                val fields = mutableListOf<DataSourceField>()
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        """
                        SELECT table_name || '.' || column_name, data_type, '' 
                        FROM information_schema.columns 
                        WHERE table_schema = 'public' 
                        ORDER BY table_name, ordinal_position 
                        LIMIT 500
                        """.trimIndent()
                    ).use { rs ->
                        while (rs.next()) {
                            fields.add(DataSourceField(rs.getString(1), rs.getString(2), rs.getString(3)))
                        }
                    }
                }
                fields
            }
        } finally {
            ds.close()
        }
    }

    private fun getOrCreatePgPool(
        sourceId: Long,
        host: String,
        port: Int,
        database: String,
        credentials: DataSourceCredentials,
    ): HikariDataSource {
        return pgPools.computeIfAbsent(sourceId) {
            createPgPool(host, port, database, credentials.username, credentials.password)
        }
    }

    private fun createPgPool(
        host: String,
        port: Int,
        database: String,
        username: String?,
        password: String?,
    ): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:postgresql://$host:$port/$database"
            this.username = username ?: ""
            this.password = password ?: ""
            maximumPoolSize = 3
            minimumIdle = 0
            idleTimeout = 60_000
            maxLifetime = 300_000
            connectionTimeout = 10_000
            isReadOnly = true // Safety: only allow reads
            addDataSourceProperty("ApplicationName", "moneat-custom-datasource")
        }
        return HikariDataSource(config)
    }

    private fun createTempPgDataSource(
        host: String,
        port: Int,
        database: String,
        username: String?,
        password: String?,
    ): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:postgresql://$host:$port/$database"
            this.username = username ?: ""
            this.password = password ?: ""
            maximumPoolSize = 1
            connectionTimeout = 10_000
            isReadOnly = true
        }
        return HikariDataSource(config)
    }

    private fun resultSetToMaps(rs: ResultSet): List<Map<String, JsonElement>> {
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

    // --- Prometheus ---

    private suspend fun testPrometheusConnection(
        host: String,
        port: Int?,
        apiKey: String?,
    ): TestConnectionResult {
        return try {
            val baseUrl = buildPrometheusUrl(host, port)
            val response = httpClient.get("$baseUrl/api/v1/label/__name__/values") {
                apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                parameter("limit", 20)
            }
            if (response.status.isSuccess()) {
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                val metrics = body["data"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                TestConnectionResult(true, "Connected successfully", metrics = metrics.take(20))
            } else {
                TestConnectionResult(false, "Prometheus returned ${response.status}")
            }
        } catch (e: Exception) {
            logger.warn(e) { "Prometheus connection test failed" }
            TestConnectionResult(false, "Connection failed: ${e.message}")
        }
    }

    private suspend fun executePrometheusQuery(
        host: String,
        port: Int?,
        credentials: DataSourceCredentials,
        query: String,
        timeRange: TimeRangeDef?,
        limit: Int,
    ): List<Map<String, JsonElement>> {
        val baseUrl = buildPrometheusUrl(host, port)

        return try {
            // Use range query if time range is provided, otherwise instant query
            val response = if (timeRange != null) {
                val nowSec = System.currentTimeMillis() / 1000
                val fromSec = resolveRelativeTimeSec(timeRange.from, nowSec)
                val toSec = resolveRelativeTimeSec(timeRange.to, nowSec)
                val step = resolvePrometheusStep(toSec - fromSec)

                httpClient.get("$baseUrl/api/v1/query_range") {
                    credentials.apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                    parameter("query", query)
                    parameter("start", fromSec)
                    parameter("end", toSec)
                    parameter("step", step)
                }
            } else {
                httpClient.get("$baseUrl/api/v1/query") {
                    credentials.apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                    parameter("query", query)
                }
            }

            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                logger.error { "Prometheus query failed: ${response.status} | query=$query | response=$body" }
                return emptyList()
            }

            parsePrometheusResponse(response.bodyAsText(), limit)
        } catch (e: Exception) {
            logger.error(e) { "Failed to execute Prometheus query" }
            emptyList()
        }
    }

    /**
     * Execute a Grafana-style label_values() query against Prometheus.
     * Parses: label_values(metric{filters}, labelName)
     */
    suspend fun executeLabelValuesQuery(
        host: String,
        port: Int?,
        credentials: DataSourceCredentials,
        query: String,
    ): List<String> {
        // label_values(metric{filters}, label) or label_values(label)
        val twoArgMatch = Regex("""label_values\((.+),\s*(\w+)\)""").find(query)
        val oneArgMatch = if (twoArgMatch == null) Regex("""label_values\((\w+)\)""").find(query) else null

        val matcher: String?
        val labelName: String
        if (twoArgMatch != null) {
            matcher = twoArgMatch.groupValues[1].trim()
            labelName = twoArgMatch.groupValues[2].trim()
        } else if (oneArgMatch != null) {
            matcher = null
            labelName = oneArgMatch.groupValues[1].trim()
        } else {
            return emptyList()
        }

        val baseUrl = buildPrometheusUrl(host, port)

        return try {
            val response = httpClient.get("$baseUrl/api/v1/label/$labelName/values") {
                credentials.apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                if (!matcher.isNullOrEmpty()) {
                    parameter("match[]", matcher)
                }
            }
            if (response.status.isSuccess()) {
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["data"]?.jsonArray?.map { it.jsonPrimitive.content }?.sorted() ?: emptyList()
            } else {
                logger.warn { "Prometheus label_values query failed: ${response.status}" }
                emptyList()
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to execute label_values query" }
            emptyList()
        }
    }

    private suspend fun getPrometheusMetrics(
        host: String,
        port: Int?,
        credentials: DataSourceCredentials,
    ): List<DataSourceField> {
        val baseUrl = buildPrometheusUrl(host, port)
        return try {
            val response = httpClient.get("$baseUrl/api/v1/label/__name__/values") {
                credentials.apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
            if (response.status.isSuccess()) {
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["data"]?.jsonArray?.map {
                    DataSourceField(it.jsonPrimitive.content, "gauge", "Prometheus metric")
                } ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch Prometheus metrics" }
            emptyList()
        }
    }

    private fun parsePrometheusResponse(body: String, limit: Int): List<Map<String, JsonElement>> {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonObject ?: return emptyList()
        val resultType = data["resultType"]?.jsonPrimitive?.content
        val results = data["result"]?.jsonArray ?: return emptyList()

        val rows = mutableListOf<Map<String, JsonElement>>()
        for (result in results) {
            val metric = result.jsonObject["metric"]?.jsonObject ?: JsonObject(emptyMap())
            val metricName = metric["__name__"]?.jsonPrimitive?.content ?: "value"

            when (resultType) {
                "matrix" -> {
                    val values = result.jsonObject["values"]?.jsonArray ?: continue
                    for (point in values) {
                        val arr = point.jsonArray
                        val row = mutableMapOf<String, JsonElement>()
                        row["time_bucket"] = promTimestampToMs(arr[0])
                        row[metricName] = promValueToNumber(arr[1])
                        for ((k, v) in metric) {
                            if (k != "__name__") row[k] = v
                        }
                        rows.add(row)
                        if (rows.size >= limit) return rows
                    }
                }
                "vector" -> {
                    val value = result.jsonObject["value"]?.jsonArray
                    if (value != null) {
                        val row = mutableMapOf<String, JsonElement>()
                        row["time_bucket"] = promTimestampToMs(value[0])
                        row[metricName] = promValueToNumber(value[1])
                        for ((k, v) in metric) {
                            if (k != "__name__") row[k] = v
                        }
                        rows.add(row)
                    }
                }
            }
            if (rows.size >= limit) break
        }
        return rows
    }

    private fun promTimestampToMs(element: JsonElement): JsonElement {
        val sec = element.jsonPrimitive.doubleOrNull ?: return element
        return JsonPrimitive((sec * 1000).toLong())
    }

    private fun promValueToNumber(element: JsonElement): JsonElement {
        val str = element.jsonPrimitive.contentOrNull ?: return element
        if (str == "NaN" || str == "+Inf" || str == "-Inf") return JsonNull
        return str.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: element
    }

    private fun buildPrometheusUrl(host: String, port: Int?): String {
        val scheme = when {
            host.startsWith("https://") -> "https://"
            host.startsWith("http://") -> "http://"
            else -> "http://"
        }
        val cleanHost = host.removePrefix("https://").removePrefix("http://").trimEnd('/')
        // Don't append port if: none specified, host already has one, or it's the default for the scheme
        val hostHasPort = cleanHost.contains(":")
        if (port == null || hostHasPort) return "${scheme}$cleanHost"
        val isDefaultPort = (scheme == "http://" && port == 80) || (scheme == "https://" && port == 443)
        return if (isDefaultPort) {
            "${scheme}$cleanHost"
        } else {
            "${scheme}$cleanHost:$port"
        }
    }

    private fun resolveRelativeTimeSec(expr: String, nowSec: Long): Long {
        if (expr == "now") return nowSec
        val match = Regex("""^now-(\d+)([smhdwMy])$""").matchEntire(expr) ?: return nowSec
        val amount = match.groupValues[1].toLong()
        val offsetSec = when (match.groupValues[2]) {
            "s" -> amount
            "m" -> amount * 60
            "h" -> amount * 3600
            "d" -> amount * 86400
            "w" -> amount * 604800
            "M" -> amount * 2592000
            "y" -> amount * 31536000
            else -> 0
        }
        return nowSec - offsetSec
    }

    private fun resolvePrometheusStep(rangeSec: Long): String = when {
        rangeSec <= 3600 -> "15s"
        rangeSec <= 21600 -> "1m"
        rangeSec <= 86400 -> "5m"
        rangeSec <= 604800 -> "1h"
        else -> "1d"
    }

    /**
     * Basic SQL injection protection — reject dangerous statements for custom PostgreSQL queries.
     * Only SELECT queries are allowed.
     */
    private fun validateSqlQuery(query: String) {
        val trimmed = query.trim().uppercase()
        require(trimmed.startsWith("SELECT")) { "Only SELECT queries are allowed" }
        val forbidden =
            listOf("INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE", "GRANT", "REVOKE", "EXEC")
        for (keyword in forbidden) {
            require(!Regex("""\b$keyword\b""").containsMatchIn(trimmed)) {
                "$keyword statements are not allowed"
            }
        }
    }

    fun closePool(sourceId: Long) {
        pgPools.remove(sourceId)?.close()
    }

    fun closeAllPools() {
        pgPools.values.forEach { it.close() }
        pgPools.clear()
    }
}
