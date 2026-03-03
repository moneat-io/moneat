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
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Elasticsearch/OpenSearch handler using REST API.
 * Query is sent as JSON body to POST /_search or index/_search.
 * Default port 9200.
 */
class ElasticsearchHandler : HttpApiHandler() {

    override suspend fun testConnection(request: TestConnectionRequest): TestConnectionResult {
        return try {
            val baseUrl = buildUrl(request.host, request.port ?: 9200)
            val response = httpClient.get("$baseUrl/_cluster/health") {
                request.apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                request.username?.let { u ->
                    request.password?.let { p ->
                        val encoded = java.util.Base64.getEncoder().encodeToString("$u:$p".toByteArray())
                        header(HttpHeaders.Authorization, "Basic $encoded")
                    }
                }
            }
            if (response.status.isSuccess()) {
                TestConnectionResult(true, "Connected successfully")
            } else {
                TestConnectionResult(false, "Elasticsearch returned ${response.status}")
            }
        } catch (e: Exception) {
            logger.warn(e) { "Elasticsearch connection test failed" }
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
        val baseUrl = buildUrl(host, port ?: 9200)
        val index = databaseName?.ifBlank { null } ?: "_all"
        val path = if (index == "_all") "/_search" else "/$index/_search"

        val queryBody = try {
            json.parseToJsonElement(query) as? JsonObject
                ?: JsonObject(mapOf("query" to (json.parseToJsonElement(query) as JsonObject)))
        } catch (_: Exception) {
            JsonObject(
                mapOf(
                    "query" to JsonObject(
                        mapOf(
                            "query_string" to JsonObject(mapOf("query" to JsonPrimitive(query)))
                        )
                    ),
                    "size" to JsonPrimitive(limit.coerceIn(1, 10000))
                )
            )
        }

        val bodyObj = queryBody.toMutableMap()
        if (!bodyObj.containsKey("size")) bodyObj["size"] = JsonPrimitive(limit.coerceIn(1, 10000))

        return try {
            val response = httpClient.post("$baseUrl$path") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(JsonObject(bodyObj)))
                credentials.apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                credentials.username?.let { u ->
                    credentials.password?.let { p ->
                        val encoded = java.util.Base64.getEncoder().encodeToString("$u:$p".toByteArray())
                        header(HttpHeaders.Authorization, "Basic $encoded")
                    }
                }
            }
            if (!response.status.isSuccess()) {
                logger.error { "Elasticsearch query failed: ${response.status}" }
                return emptyList()
            }
            parseSearchResponse(response.bodyAsText(), limit)
        } catch (e: Exception) {
            logger.error(e) { "Elasticsearch query failed" }
            emptyList()
        }
    }

    override suspend fun getSchema(
        host: String,
        port: Int?,
        databaseName: String?,
        credentials: DataSourceCredentials,
    ): List<DataSourceField> {
        val baseUrl = buildUrl(host, port ?: 9200)
        return try {
            val response = httpClient.get("$baseUrl/_cat/indices?v&format=json") {
                credentials.apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
            if (response.status.isSuccess()) {
                val arr = json.parseToJsonElement(response.bodyAsText()).jsonArray
                arr.mapNotNull {
                    val obj = it.jsonObject
                    val index = obj["index"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    DataSourceField(index, "index", "Elasticsearch index")
                }.take(100)
            } else {
                emptyList()
            }
            emptyList()
        }
    }

    private fun parseSearchResponse(body: String, limit: Int): List<Map<String, JsonElement>> {
        val root = json.parseToJsonElement(body).jsonObject
        val hits = root["hits"]?.jsonObject?.get("hits")?.jsonArray ?: return emptyList()
        val rows = mutableListOf<Map<String, JsonElement>>()
        for (hit in hits) {
            if (rows.size >= limit) break
            val source = hit.jsonObject["_source"]?.jsonObject ?: continue
            val map = source.mapValues { (_, v) -> v }.toMutableMap<String, JsonElement>()
            map["_id"] = hit.jsonObject["_id"]?.jsonPrimitive ?: JsonPrimitive("")
            map["_index"] = hit.jsonObject["_index"]?.jsonPrimitive ?: JsonPrimitive("")
            rows.add(map)
        }
        return rows
    }
}
