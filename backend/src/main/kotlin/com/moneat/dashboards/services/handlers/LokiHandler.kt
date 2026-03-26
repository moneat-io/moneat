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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Loki handler using LogQL API.
 * Query is LogQL (e.g. {job="api"} |= "error").
 * Default port 3100.
 */
class LokiHandler : HttpApiHandler() {

    override suspend fun testConnection(request: TestConnectionRequest): TestConnectionResult {
        return try {
            val baseUrl = buildUrl(request.host, request.port ?: 3100)
            val response = httpClient.get("$baseUrl/ready") {
                request.apiKey?.let { header("X-Scope-OrgID", it) }
            }
            if (response.status.isSuccess()) {
                TestConnectionResult(true, "Connected successfully")
            } else {
                TestConnectionResult(false, "Loki returned ${response.status}")
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.warn(e) { "Loki connection test failed" }
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
        val baseUrl = buildUrl(host, port ?: 3100)
        val nowSec = System.currentTimeMillis() / 1000
        val fromSec = timeRange?.from?.let { resolveRelativeTimeSec(it, nowSec) } ?: nowSec - 3600
        val toSec = timeRange?.to?.let { resolveRelativeTimeSec(it, nowSec) } ?: nowSec

        return try {
            val boundedLimit = limit.coerceIn(1, 5000)
            val response = httpClient.get("$baseUrl/loki/api/v1/query_range") {
                parameter("query", query)
                parameter("start", fromSec.toString() + "000000000")
                parameter("end", toSec.toString() + "000000000")
                parameter("limit", boundedLimit)
                credentials.apiKey?.let { header("X-Scope-OrgID", it) }
            }
            if (!response.status.isSuccess()) {
                logger.error { "Loki query failed: ${response.status}" }
                return emptyList()
            }
            parseLokiResponse(response.bodyAsText(), boundedLimit)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error(e) { "Loki query failed" }
            emptyList()
        }
    }

    override suspend fun getSchema(
        host: String,
        port: Int?,
        databaseName: String?,
        credentials: DataSourceCredentials,
    ): List<DataSourceField> {
        val baseUrl = buildUrl(host, port ?: 3100)
        return try {
            val response = httpClient.get("$baseUrl/loki/api/v1/labels") {
                credentials.apiKey?.let { header("X-Scope-OrgID", it) }
            }
            if (response.status.isSuccess()) {
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["data"]?.jsonArray?.map {
                    DataSourceField(it.jsonPrimitive.content, "label", "Loki label")
                } ?: emptyList()
            } else {
                emptyList()
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error(e) { "Loki schema fetch failed" }
            emptyList()
        }
    }

    override suspend fun executeLabelValuesQuery(
        host: String,
        port: Int?,
        credentials: DataSourceCredentials,
        query: String,
    ): List<String> {
        val labelMatch = Regex("""label_values\((.+),\s*(\w+)\)""").find(query)
            ?: return emptyList()
        val matcher = labelMatch.groupValues[1].trim()
        val labelName = labelMatch.groupValues[2].trim()
        val baseUrl = buildUrl(host, port ?: 3100)
        return try {
            val response = httpClient.get("$baseUrl/loki/api/v1/label/$labelName/values") {
                if (matcher.isNotBlank()) parameter("query", matcher)
                credentials.apiKey?.let { header("X-Scope-OrgID", it) }
            }
            if (response.status.isSuccess()) {
                val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["data"]?.jsonArray?.map { it.jsonPrimitive.content }?.sorted() ?: emptyList()
            } else {
                emptyList()
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.warn(e) { "Loki label_values failed" }
            emptyList()
        }
    }

    private fun parseLokiResponse(body: String, limit: Int): List<Map<String, JsonElement>> {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonObject ?: return emptyList()
        val result = data["result"]?.jsonArray ?: return emptyList()
        val rows = mutableListOf<Map<String, JsonElement>>()
        for (stream in result) {
            val metric = stream.jsonObject["stream"]?.jsonObject ?: continue
            val values = stream.jsonObject["values"]?.jsonArray ?: continue
            for (pair in values) {
                if (rows.size >= limit) return rows
                val arr = pair.jsonArray
                val tsNs = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: "0"
                val log = arr.getOrNull(1)?.jsonPrimitive?.content ?: ""
                val row = mutableMapOf<String, JsonElement>()
                row["time_bucket"] = JsonPrimitive(tsNs.take(13).toLongOrNull() ?: 0L)
                row["log"] = JsonPrimitive(log)
                for ((k, v) in metric) row[k] = v
                rows.add(row)
            }
        }
        return rows
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
}
