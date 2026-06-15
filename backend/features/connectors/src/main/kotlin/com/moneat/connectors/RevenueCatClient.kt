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

package com.moneat.connectors

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val REVENUECAT_BASE_URL = "https://api.revenuecat.com"
private const val REVENUECAT_CONNECT_TIMEOUT_SECONDS = 5L
private const val REVENUECAT_REQUEST_TIMEOUT_SECONDS = 10L
private const val PROVIDER_REVENUECAT = "revenuecat"

data class RevenueCatProject(
    val id: String,
    val name: String?,
)

data class RevenueCatApp(
    val id: String,
    val name: String?,
    val platform: String?,
)

class RevenueCatClientException(
    message: String,
    val code: String,
    val retryable: Boolean = false,
) : RuntimeException(message)

interface RevenueCatProviderClient {
    fun resolveProject(apiKey: String, projectId: String): RevenueCatProject
    fun listApps(apiKey: String, projectId: String): List<RevenueCatApp>
    fun listWebhookIntegrations(
        apiKey: String,
        projectId: String,
        expectedUrl: String,
    ): Pair<List<ConnectorObservedWebhookIntegration>, List<String>>
}

class RevenueCatClient(
    private val baseUrl: String = REVENUECAT_BASE_URL,
    private val httpClient: HttpClient = HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofSeconds(REVENUECAT_CONNECT_TIMEOUT_SECONDS))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) : RevenueCatProviderClient {
    private val json = Json { ignoreUnknownKeys = true }

    override fun resolveProject(
        apiKey: String,
        projectId: String,
    ): RevenueCatProject {
        val normalizedProjectId = projectId.trim()
        if (normalizedProjectId.isBlank()) {
            throw RevenueCatClientException("RevenueCat project ID is required", "missing_project_id")
        }
        val response = get(apiKey, "/v2/projects")
        if (response.statusCode() == HTTP_NOT_FOUND) {
            return getProjectFallback(apiKey, normalizedProjectId)
        }
        ensureSuccess(response, "validate RevenueCat project")
        val projects = parseProjects(response.body())
        return projects.firstOrNull { it.id == normalizedProjectId }
            ?: throw RevenueCatClientException(
                "RevenueCat project was not visible to this API key",
                "project_not_found",
            )
    }

    override fun listApps(
        apiKey: String,
        projectId: String,
    ): List<RevenueCatApp> {
        val response = get(apiKey, "/v2/projects/${pathSegment(projectId)}/apps")
        ensureSuccess(response, "list RevenueCat apps")
        return parseApps(response.body())
    }

    override fun listWebhookIntegrations(
        apiKey: String,
        projectId: String,
        expectedUrl: String,
    ): Pair<List<ConnectorObservedWebhookIntegration>, List<String>> {
        val response = get(apiKey, "/v2/projects/${pathSegment(projectId)}/integrations/webhooks")
        if (response.statusCode() == HTTP_FORBIDDEN || response.statusCode() == HTTP_NOT_FOUND) {
            return emptyList<ConnectorObservedWebhookIntegration>() to
                listOf("Moneat could not inspect RevenueCat webhook integrations with this API key.")
        }
        ensureSuccess(response, "list RevenueCat webhook integrations")
        return parseWebhookIntegrations(response.body(), expectedUrl) to emptyList()
    }

    private fun getProjectFallback(
        apiKey: String,
        projectId: String,
    ): RevenueCatProject {
        val response = get(apiKey, "/v2/projects/${pathSegment(projectId)}")
        ensureSuccess(response, "validate RevenueCat project")
        val obj = json.parseToJsonElement(response.body()).jsonObject
        return RevenueCatProject(
            id = obj.string("id") ?: projectId,
            name = obj.string("name") ?: obj.string("display_name"),
        )
    }

    private fun get(
        apiKey: String,
        path: String,
    ): HttpResponse<String> {
        val uri = URI.create(baseUrl.trimEnd('/') + path)
        if (uri.scheme != "https" || uri.host != "api.revenuecat.com") {
            throw RevenueCatClientException("Invalid RevenueCat API host", "invalid_provider_host")
        }
        val request = HttpRequest
            .newBuilder(uri)
            .timeout(Duration.ofSeconds(REVENUECAT_REQUEST_TIMEOUT_SECONDS))
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .GET()
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun ensureSuccess(
        response: HttpResponse<String>,
        operation: String,
    ) {
        when (response.statusCode()) {
            in HTTP_SUCCESS_RANGE -> return
            HTTP_UNAUTHORIZED -> throw RevenueCatClientException(
                "RevenueCat rejected the API key while trying to $operation",
                "revenuecat_unauthorized",
            )
            HTTP_FORBIDDEN -> throw RevenueCatClientException(
                "RevenueCat API key does not have permission to $operation",
                "revenuecat_forbidden",
            )
            HTTP_NOT_FOUND -> throw RevenueCatClientException(
                "RevenueCat resource was not found while trying to $operation",
                "revenuecat_not_found",
            )
            HTTP_TOO_MANY_REQUESTS -> throw RevenueCatClientException(
                "RevenueCat rate limited the request to $operation",
                "revenuecat_rate_limited",
                retryable = true,
            )
            else -> throw RevenueCatClientException(
                "RevenueCat API request failed while trying to $operation",
                "revenuecat_api_error",
                retryable = response.statusCode() >= HTTP_SERVER_ERROR_MIN,
            )
        }
    }

    private fun parseProjects(body: String): List<RevenueCatProject> =
        parseCollection(body).mapNotNull { element ->
            val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
            val id = obj.string("id") ?: obj.string("project_id") ?: return@mapNotNull null
            RevenueCatProject(id = id, name = obj.string("name") ?: obj.string("display_name"))
        }

    private fun parseApps(body: String): List<RevenueCatApp> =
        parseCollection(body).mapNotNull { element ->
            val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
            val id = obj.string("id") ?: obj.string("app_id") ?: return@mapNotNull null
            RevenueCatApp(
                id = id,
                name = obj.string("name") ?: obj.string("display_name"),
                platform = obj.string("platform") ?: obj.string("type"),
            )
        }

    private fun parseWebhookIntegrations(
        body: String,
        expectedUrl: String,
    ): List<ConnectorObservedWebhookIntegration> =
        parseCollection(body).mapNotNull { element ->
            val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
            val url = obj.string("url") ?: obj.string("webhook_url")
            ConnectorObservedWebhookIntegration(
                id = obj.string("id") ?: return@mapNotNull null,
                name = obj.string("name") ?: obj.string("display_name"),
                enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull,
                appIds = obj.arrayStrings("app_ids") + obj.arrayStrings("app_id_filter"),
                environments = obj.arrayStrings("environments") + obj.arrayStrings("environment_filter"),
                eventTypes = obj.arrayStrings("event_types") + obj.arrayStrings("event_type_filter"),
                matchesExpectedUrl = url == expectedUrl,
            )
        }

    private fun parseCollection(body: String): List<JsonElement> {
        val root = json.parseToJsonElement(body)
        if (root is JsonArray) return root.toList()
        val obj = root.jsonObjectOrNull() ?: return emptyList()
        val arrays = listOf("items", "data", "projects", "apps", "webhooks", "integrations")
        arrays.forEach { key ->
            val array = obj[key] as? JsonArray
            if (array != null) return array.toList()
        }
        return listOf(root)
    }

    private fun pathSegment(value: String): String =
        value.trim().replace("/", "%2F")

    companion object {
        const val PROVIDER_ID: String = PROVIDER_REVENUECAT
        const val AUTH_PROFILE_PROJECT_API_KEY: String = "project_api_key"
        const val RESOURCE_TYPE_APP: String = "revenuecat_app"
        const val LOCAL_RESOURCE_PROJECT: String = "project"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR_MIN = 500
        private val HTTP_SUCCESS_RANGE = 200..299
    }
}

private fun JsonElement.jsonObjectOrNull(): JsonObject? =
    this as? JsonObject

private fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.arrayStrings(key: String): List<String> {
    val element = this[key] ?: return emptyList()
    return when (element) {
        is JsonArray -> element.jsonArray.mapNotNull { item -> item.jsonPrimitive.contentOrNull }
        else -> element.jsonPrimitive.contentOrNull?.let(::listOf).orEmpty()
    }
}
