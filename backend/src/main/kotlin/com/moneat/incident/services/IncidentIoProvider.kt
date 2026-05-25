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

package com.moneat.incident.services

import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.alerts.models.AlertStatus
import com.moneat.incident.models.ProviderConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import com.moneat.utils.suspendRunCatching
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import com.moneat.utils.HttpConstants.HTTP_SUCCESS_MAX
import com.moneat.utils.HttpConstants.HTTP_SUCCESS_MIN

/**
 * incident.io provider implementation using Alert Events V2 API.
 * https://api-docs.incident.io/#tag/Alert-Events-V2
 */
class IncidentIoProvider : IncidentProvider {
    override val providerType = "incident_io"

    private val logger = LoggerFactory.getLogger(IncidentIoProvider::class.java)
    private val client =
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        prettyPrint = false
                    }
                )
            }
        }

    init {
        // Register this provider with the registry
        IncidentProviderRegistry.register(this)
    }

    override suspend fun sendAlert(
        event: AlertLifecycleEvent,
        config: ProviderConfig
    ): Result<String> {
        return suspendRunCatching {
            val alertSourceConfigId =
                config.configJson["alert_source_config_id"]?.jsonPrimitive?.content
                    ?: return Result.failure(Exception("Missing alert_source_config_id in provider config"))

            // Convert metadata JsonElement values to strings for incident.io API
            val baseMetadata =
                mapOf(
                    "severity" to event.severity.name.lowercase(),
                    "source" to event.source.name,
                    "moneat_url" to event.moneatUrl
                )
            val convertedMetadata =
                event.metadata.mapValues { (_, value) ->
                    when (value) {
                        is JsonPrimitive -> value.content
                        is JsonArray -> value.toString()
                        is JsonObject -> value.toString()
                    }
                }

            val payload =
                AlertEventPayload(
                    deduplicationKey = event.deduplicationKey,
                    status =
                    when (event.status) {
                        AlertStatus.FIRING -> "firing"
                        AlertStatus.RESOLVED -> "resolved"
                    },
                    title = event.title,
                    description = event.description,
                    metadata = baseMetadata + convertedMetadata
                )

            val response =
                client.post("https://api.incident.io/v2/alert_events/http/$alertSourceConfigId") {
                    header("Authorization", "Bearer ${config.apiKey}")
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }

            if (response.status.value in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
                val responseBody = response.body<AlertEventResponse>()
                Result.success(responseBody.deduplicationKey)
            } else {
                val errorBody = response.bodyAsText()
                Result.failure(Exception("incident.io API error (${response.status}): $errorBody"))
            }
        }.getOrElse { e ->
            logger.error("Error sending alert to incident.io", e)
            Result.failure(e)
        }
    }

    override suspend fun resolveAlert(
        deduplicationKey: String,
        config: ProviderConfig
    ): Result<String> {
        return suspendRunCatching {
            val alertSourceConfigId =
                config.configJson["alert_source_config_id"]?.jsonPrimitive?.content
                    ?: return Result.failure(Exception("Missing alert_source_config_id in provider config"))

            val payload =
                AlertEventPayload(
                    deduplicationKey = deduplicationKey,
                    status = "resolved",
                    title = "Alert Resolved",
                    description = "This alert has been automatically resolved by Moneat",
                    metadata = emptyMap()
                )

            val response =
                client.post("https://api.incident.io/v2/alert_events/http/$alertSourceConfigId") {
                    header("Authorization", "Bearer ${config.apiKey}")
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }

            if (response.status.value in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
                val responseBody = response.body<AlertEventResponse>()
                Result.success(responseBody.deduplicationKey)
            } else {
                val errorBody = response.bodyAsText()
                Result.failure(Exception("incident.io API error (${response.status}): $errorBody"))
            }
        }.getOrElse { e ->
            logger.error("Error resolving alert with incident.io", e)
            Result.failure(e)
        }
    }

    override suspend fun testConnection(config: ProviderConfig): Result<Boolean> {
        return suspendRunCatching {
            val alertSourceConfigId =
                config.configJson["alert_source_config_id"]?.jsonPrimitive?.content
                    ?: return Result.failure(Exception("Missing alert_source_config_id in provider config"))

            // Send a test alert and immediately resolve it
            val testDedup = "moneat-test-${System.currentTimeMillis()}"
            val payload =
                AlertEventPayload(
                    deduplicationKey = testDedup,
                    status = "firing",
                    title = "Moneat Test Alert",
                    description = "This is a test alert from Moneat to verify the integration",
                    metadata = mapOf("test" to "true")
                )

            val response =
                client.post("https://api.incident.io/v2/alert_events/http/$alertSourceConfigId") {
                    header("Authorization", "Bearer ${config.apiKey}")
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }

            if (response.status.value in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
                // Immediately resolve the test alert
                resolveAlert(testDedup, config)
                Result.success(true)
            } else {
                val errorBody = response.bodyAsText()
                Result.failure(Exception("incident.io API error (${response.status}): $errorBody"))
            }
        }.getOrElse { e ->
            logger.error("Error testing incident.io connection", e)
            Result.failure(e)
        }
    }

    @Serializable
    private data class AlertEventPayload(
        @SerialName("deduplication_key") val deduplicationKey: String,
        val status: String,
        val title: String,
        val description: String,
        val metadata: Map<String, String>
    )

    @Serializable
    private data class AlertEventResponse(
        @SerialName("deduplication_key") val deduplicationKey: String
    )
}

// Initialize provider on class load
private val incidentIoProvider = IncidentIoProvider()
