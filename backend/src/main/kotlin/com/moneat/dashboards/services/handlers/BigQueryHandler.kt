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

import com.google.cloud.bigquery.BigQuery
import com.google.cloud.bigquery.BigQueryOptions
import com.google.cloud.bigquery.FieldValueList
import com.google.cloud.bigquery.QueryJobConfiguration
import com.moneat.dashboards.models.DataSourceField
import com.moneat.dashboards.models.TestConnectionRequest
import com.moneat.dashboards.models.TestConnectionResult
import com.moneat.dashboards.models.TimeRangeDef
import com.moneat.dashboards.services.DataSourceCredentials
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import mu.KotlinLogging
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}

/**
 * BigQuery handler using Google Cloud BigQuery Java client.
 * Uses project_id (host or projectId credential) and database_name as dataset.
 * Requires service_account_json for authentication.
 */
class BigQueryHandler : DataSourceHandler {

    override suspend fun testConnection(request: TestConnectionRequest): TestConnectionResult {
        val projectId = request.projectId ?: request.host.ifBlank { null }
            ?: return TestConnectionResult(false, "Project ID is required")
        val serviceAccountJson = request.serviceAccountJson
            ?: return TestConnectionResult(false, "Service account JSON is required")

        return suspendRunCatching {
            val bigQuery = createBigQueryClient(projectId, serviceAccountJson)
            val queryConfig = QueryJobConfiguration.newBuilder("SELECT 1 AS test").build()
            bigQuery.query(queryConfig)
            TestConnectionResult(true, "Connected successfully")
        }.getOrElse { e ->
            logger.warn(e) { "BigQuery connection test failed" }
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
        val projectId = credentials.projectId ?: host.ifBlank { null }
            ?: return emptyList()
        val serviceAccountJson = credentials.serviceAccountJson
            ?: return emptyList()

        validateSqlQuery(query)

        return suspendRunCatching {
            val bigQuery = createBigQueryClient(projectId, serviceAccountJson)
            val config = QueryJobConfiguration.newBuilder(query)
                .setMaxResults(limit.toLong().coerceIn(1, 10000))
                .build()
            val results = bigQuery.query(config)
            val schema = results.schema ?: return emptyList()
            val columns = schema.fields.map { it.name }
            results.values.toList().map { row -> rowToMap(row, columns) }
        }.getOrElse { e ->
            logger.error(e) { "BigQuery query failed" }
            emptyList()
        }
    }

    override suspend fun getSchema(
        host: String,
        port: Int?,
        databaseName: String?,
        credentials: DataSourceCredentials,
    ): List<DataSourceField> {
        val projectId = credentials.projectId ?: host.ifBlank { null }
            ?: return emptyList()
        val serviceAccountJson = credentials.serviceAccountJson
            ?: return emptyList()
        val dataset = databaseName ?: "INFORMATION_SCHEMA"

        return suspendRunCatching {
            val bigQuery = createBigQueryClient(projectId, serviceAccountJson)
            val datasetId = validateBigQueryIdentifier(dataset.ifBlank { "INFORMATION_SCHEMA" })
            val validatedProjectId = validateBigQueryIdentifier(projectId)
            val queryConfig = QueryJobConfiguration.newBuilder(
                """
                SELECT table_name, column_name, data_type
                FROM `$validatedProjectId.$datasetId.INFORMATION_SCHEMA.COLUMNS`
                ORDER BY table_name, ordinal_position
                LIMIT 500
                """.trimIndent()
            ).build()
            val results = bigQuery.query(queryConfig)
            results.values.toList().map { row ->
                DataSourceField(
                    name = "${row.get("table_name").stringValue}.${row.get("column_name").stringValue}",
                    type = row.get("data_type").stringValue,
                    description = ""
                )
            }
        }.getOrElse { e ->
            logger.error(e) { "BigQuery schema fetch failed" }
            emptyList()
        }
    }

    private fun createBigQueryClient(projectId: String, serviceAccountJson: String): BigQuery {
        val credentials = com.google.auth.oauth2.GoogleCredentials.fromStream(
            serviceAccountJson.byteInputStream()
        )
        return BigQueryOptions.newBuilder()
            .setProjectId(projectId)
            .setCredentials(credentials)
            .build()
            .service
    }

    private fun rowToMap(row: FieldValueList, columns: List<String>): Map<String, JsonElement> {
        val map = mutableMapOf<String, JsonElement>()
        for ((i, col) in columns.withIndex()) {
            val fv = row.get(i)
            map[col] = when {
                fv.isNull -> JsonNull
                fv.attribute == com.google.cloud.bigquery.FieldValue.Attribute.PRIMITIVE -> {
                    val str = fv.stringValue
                    when {
                        str?.toLongOrNull() != null -> JsonPrimitive(str.toLong())
                        str?.toDoubleOrNull() != null -> JsonPrimitive(str.toDouble())
                        str == "true" || str == "false" -> JsonPrimitive(str == "true")
                        else -> JsonPrimitive(str ?: "")
                    }
                }
                else -> JsonPrimitive(fv.stringValue ?: "")
            }
        }
        return map
    }

    private fun validateSqlQuery(query: String) {
        val trimmed = query.trim().uppercase()
        require(trimmed.startsWith("SELECT")) { "Only SELECT queries are allowed" }
        require(!query.contains(";")) { "Multiple statements are not allowed" }
        val forbiddenKeywords = listOf(
            Regex("""\bINSERT\b""") to "INSERT",
            Regex("""\bUPDATE\b""") to "UPDATE",
            Regex("""\bDELETE\b""") to "DELETE",
            Regex("""\bDROP\b""") to "DROP",
            Regex("""\bCREATE\b""") to "CREATE",
            Regex("""\bALTER\b""") to "ALTER",
            Regex("""\bTRUNCATE\b""") to "TRUNCATE",
            Regex("""\bEXEC(UTE)?\b""") to "EXEC",
            Regex("""\bMERGE\b""") to "MERGE",
            Regex("""\bCALL\b""") to "CALL",
        )
        for ((pattern, name) in forbiddenKeywords) {
            require(!pattern.containsMatchIn(trimmed)) { "$name statements are not allowed" }
        }
    }

    private fun validateBigQueryIdentifier(identifier: String): String {
        require(identifier.matches(Regex("[A-Za-z0-9_]+"))) {
            "Invalid BigQuery identifier '$identifier': only letters, digits, and underscores are allowed"
        }
        return identifier
    }
}
