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
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}

/**
 * InfluxDB handler using Flux API (v2) or InfluxQL (v1).
 * Default port 8086. Uses /api/v2/query for Flux.
 */
class InfluxDBHandler : HttpApiHandler() {

    companion object {
        private const val INFLUXDB_DEFAULT_PORT = 8086
        private const val FIELDS_PAGE_SIZE = 100
    }

    override val httpAuthDefault = HttpAuthDefault.TOKEN

    override suspend fun testConnection(request: TestConnectionRequest): TestConnectionResult {
        return suspendRunCatching {
            val baseUrl = buildUrl(request.host, request.port ?: INFLUXDB_DEFAULT_PORT)
            val options = ConnectionOptions.from(request.extraConfig)
            val credentials = request.toCredentials()
            if (options.influxVersion == "1") {
                val responseBody = executeInfluxQl(
                    baseUrl,
                    request.databaseName ?: "moneat",
                    credentials,
                    "SHOW MEASUREMENTS LIMIT 5"
                )
                return@suspendRunCatching TestConnectionResult(
                    responseBody != null,
                    if (responseBody != null) "Connected successfully" else "InfluxDB query failed"
                )
            }
            val org = options.org ?: request.databaseName ?: "moneat"
            val query = "buckets()"
            val body = "org=$org&query=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val response = httpClient.post("$baseUrl/api/v2/query") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(body)
                applyHttpAuth(credentials)
            }
            if (response.status.isSuccess()) {
                TestConnectionResult(true, "Connected successfully")
            } else {
                TestConnectionResult(false, "InfluxDB returned ${response.status}")
            }
        }.getOrElse { e ->
            logger.warn(e) { "InfluxDB connection test failed" }
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
        val baseUrl = buildUrl(host, port ?: INFLUXDB_DEFAULT_PORT)
        val options = credentials.options
        if (options.influxVersion == "1") {
            return suspendRunCatching {
                executeInfluxQl(baseUrl, databaseName ?: "moneat", credentials, query)
                    ?.let { parseInfluxQlJson(it, limit) }
                    ?: emptyList()
            }.getOrElse { e ->
                logger.error(e) { "InfluxDB v1 query failed" }
                emptyList()
            }
        }
        val org = options.org ?: databaseName ?: "moneat"
        val fluxQuery = if (timeRange != null) {
            val from = timeRange.from
            val to = timeRange.to
            val bucket = options.bucket ?: databaseName ?: "moneat"
            "from(bucket: \"$bucket\") |> range(start: $from, stop: $to) |> $query |> limit(n: $limit)"
        } else {
            "$query |> limit(n: $limit)"
        }

        return suspendRunCatching {
            val body = "org=$org&query=${java.net.URLEncoder.encode(fluxQuery, "UTF-8")}"
            val response = httpClient.post("$baseUrl/api/v2/query") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(body)
                applyHttpAuth(credentials)
            }
            if (!response.status.isSuccess()) {
                logger.error { "InfluxDB query failed: ${response.status}" }
                return emptyList()
            }
            parseFluxCsv(response.bodyAsText(), limit)
        }.getOrElse { e ->
            logger.error(e) { "InfluxDB query failed" }
            emptyList()
        }
    }

    override suspend fun getSchema(
        host: String,
        port: Int?,
        databaseName: String?,
        credentials: DataSourceCredentials,
    ): List<DataSourceField> {
        val baseUrl = buildUrl(host, port ?: INFLUXDB_DEFAULT_PORT)
        val options = credentials.options
        if (options.influxVersion == "1") {
            return suspendRunCatching {
                executeInfluxQl(
                    baseUrl,
                    databaseName ?: "moneat",
                    credentials,
                    "SHOW MEASUREMENTS LIMIT $FIELDS_PAGE_SIZE"
                )?.let(::parseInfluxQlMeasurements).orEmpty()
            }.getOrElse { e ->
                logger.error(e) { "InfluxDB v1 schema fetch failed" }
                emptyList()
            }
        }
        val org = options.org ?: databaseName ?: "moneat"
        return suspendRunCatching {
            val bucket = options.bucket ?: databaseName ?: "moneat"
            val query = "import \"influxdata/influxdb/schema\" schema.measurements(bucket: \"$bucket\")"
            val body = "org=$org&query=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val response = httpClient.post("$baseUrl/api/v2/query") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(body)
                applyHttpAuth(credentials)
            }
            if (response.status.isSuccess()) {
                val csv = response.bodyAsText()
                val lines = csv.lines().filter { it.isNotBlank() }
                if (lines.size >= 2) {
                    lines.drop(1).take(FIELDS_PAGE_SIZE).mapNotNull { line ->
                        val vals = line.split(",").map { it.trim() }
                        val name = vals.getOrNull(1) ?: return@mapNotNull null
                        DataSourceField(name, "measurement", "InfluxDB measurement")
                    }
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
        }.getOrElse { e ->
            logger.error(e) { "InfluxDB schema fetch failed" }
            emptyList()
        }
    }

    private fun parseFluxCsv(csv: String, limit: Int): List<Map<String, JsonElement>> {
        val lines = csv.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        if (lines.isEmpty()) return emptyList()
        val headers = lines[0].split(",").map { it.trim() }
        val rows = mutableListOf<Map<String, JsonElement>>()
        for (line in lines.drop(1)) {
            if (rows.size >= limit) break
            val vals = line.split(",").map { it.trim() }
            val row = mutableMapOf<String, JsonElement>()
            for ((i, h) in headers.withIndex()) {
                val v = vals.getOrNull(i) ?: ""
                row[h] = when {
                    v == "" -> JsonNull
                    v.toDoubleOrNull() != null -> JsonPrimitive(v.toDouble())
                    v.toLongOrNull() != null -> JsonPrimitive(v.toLong())
                    else -> JsonPrimitive(v)
                }
            }
            rows.add(row)
        }
        return rows
    }

    private suspend fun executeInfluxQl(
        baseUrl: String,
        databaseName: String,
        credentials: DataSourceCredentials,
        query: String,
    ): String? {
        val body = influxQlBody(databaseName, credentials, query)
        val response = httpClient.post("$baseUrl/query") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(body)
            applyHttpAuth(credentials)
        }
        if (!response.status.isSuccess()) {
            logger.error { "InfluxDB v1 query failed: ${response.status}" }
            return null
        }
        return response.bodyAsText()
    }

    private fun influxQlBody(
        databaseName: String,
        credentials: DataSourceCredentials,
        query: String,
    ): String {
        val params = buildList {
            add("db" to databaseName)
            add("q" to query)
            credentials.username?.takeIf { it.isNotBlank() }?.let { add("u" to it) }
            credentials.password?.takeIf { it.isNotBlank() }?.let { add("p" to it) }
        }
        return params.joinToString("&") { (key, value) -> "$key=${encodeFormValue(value)}" }
    }

    private fun encodeFormValue(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    private fun parseInfluxQlMeasurements(body: String): List<DataSourceField> =
        parseInfluxQlJson(body, FIELDS_PAGE_SIZE).mapNotNull { row ->
            val name = row["name"]?.jsonPrimitive?.content
                ?: row.values.firstOrNull { it !is JsonNull }?.jsonPrimitive?.content
                ?: return@mapNotNull null
            DataSourceField(name, "measurement", "InfluxDB measurement")
        }

    private fun parseInfluxQlJson(body: String, limit: Int): List<Map<String, JsonElement>> {
        val root = json.parseToJsonElement(body).jsonObject
        val series = root["results"]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("series")?.jsonArray
            ?: return emptyList()
        val rows = mutableListOf<Map<String, JsonElement>>()
        for (serie in series) {
            if (rows.size >= limit) break
            val obj = serie.jsonObject
            val measurement = obj["name"]?.jsonPrimitive?.content
            val columns = obj["columns"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val values = obj["values"]?.jsonArray.orEmpty()
            for (valueRow in values) {
                if (rows.size >= limit) break
                val valuesArray = valueRow.jsonArray
                val row = mutableMapOf<String, JsonElement>()
                measurement?.let { row["measurement"] = JsonPrimitive(it) }
                columns.forEachIndexed { index, column ->
                    row[column] = valuesArray.getOrNull(index) ?: JsonNull
                }
                rows.add(row)
            }
        }
        return rows
    }
}
