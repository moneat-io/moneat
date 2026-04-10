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
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}

/**
 * Graphite handler using Render API.
 * Query format: target=metric.path.* or comma-separated targets.
 * Default port 80 (or 8080 for Graphite-web).
 */
class GraphiteHandler : HttpApiHandler() {

    companion object {
        private const val GRAPHITE_DEFAULT_PORT = 80
        private const val GRAPHITE_SCHEMA_LIMIT = 100
        private const val MILLIS_PER_SECOND = 1_000
    }

    override suspend fun testConnection(request: TestConnectionRequest): TestConnectionResult {
        return suspendRunCatching {
            val baseUrl = buildUrl(request.host, request.port ?: GRAPHITE_DEFAULT_PORT)
            val response = httpClient.get("$baseUrl/render") {
                parameter("target", "constantLine(1)")
                parameter("format", "json")
                parameter("from", "-1min")
                request.apiKey?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status.isSuccess()) {
                TestConnectionResult(true, "Connected successfully")
            } else {
                TestConnectionResult(false, "Graphite returned ${response.status}")
            }
        }.getOrElse { e ->
            logger.warn(e) { "Graphite connection test failed" }
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
        val baseUrl = buildUrl(host, port ?: GRAPHITE_DEFAULT_PORT)
        val from = timeRange?.from ?: "-24h"
        val until = timeRange?.to ?: "now"
        val targets = query.split(",").map { it.trim() }.filter { it.isNotBlank() }
            .ifEmpty { listOf("*") }

        return suspendRunCatching {
            val response = httpClient.get("$baseUrl/render") {
                targets.forEach { parameter("target", it) }
                parameter("format", "json")
                parameter("from", from)
                parameter("until", until)
                credentials.apiKey?.let { header("Authorization", "Bearer $it") }
            }
            if (!response.status.isSuccess()) {
                logger.error { "Graphite query failed: ${response.status}" }
                return emptyList()
            }
            parseGraphiteResponse(response.bodyAsText(), limit)
        }.getOrElse { e ->
            logger.error(e) { "Graphite query failed" }
            emptyList()
        }
    }

    override suspend fun getSchema(
        host: String,
        port: Int?,
        databaseName: String?,
        credentials: DataSourceCredentials,
    ): List<DataSourceField> {
        val baseUrl = buildUrl(host, port ?: GRAPHITE_DEFAULT_PORT)
        return suspendRunCatching {
            val response = httpClient.get("$baseUrl/metrics/index.json") {
                credentials.apiKey?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status.isSuccess()) {
                val arr = json.parseToJsonElement(response.bodyAsText()).jsonArray
                arr.map {
                    DataSourceField(it.jsonPrimitive.content, "metric", "Graphite metric")
                }.take(GRAPHITE_SCHEMA_LIMIT)
            } else {
                emptyList()
            }
        }.getOrElse { e ->
            logger.error(e) { "Graphite schema fetch failed" }
            emptyList()
        }
    }

    private fun parseGraphiteResponse(body: String, limit: Int): List<Map<String, JsonElement>> {
        val arr = json.parseToJsonElement(body).jsonArray
        val rows = mutableListOf<Map<String, JsonElement>>()
        for (series in arr) {
            val target = series.jsonObject["target"]?.jsonPrimitive?.content ?: "value"
            val datapoints = series.jsonObject["datapoints"]?.jsonArray ?: continue
            for (dp in datapoints) {
                if (rows.size >= limit) return rows
                val pair = dp.jsonArray
                val ts = pair.getOrNull(1)?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                val value = pair.getOrNull(0)?.jsonPrimitive?.content?.toDoubleOrNull()
                rows.add(
                    mapOf(
                        "time_bucket" to JsonPrimitive(ts * MILLIS_PER_SECOND),
                        target to JsonPrimitive(value ?: 0.0)
                    )
                )
            }
        }
        return rows
    }
}
