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

import com.moneat.config.EnvConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.time.Clock

private const val GOOGLE_ADS_BASE_URL = "https://googleads.googleapis.com"
private const val GOOGLE_OAUTH_TOKEN_URL = "https://oauth2.googleapis.com/token"
private const val GOOGLE_ADS_DEFAULT_API_VERSION = "v24"
private const val GOOGLE_ADS_CONNECT_TIMEOUT_SECONDS = 5L
private const val GOOGLE_ADS_REQUEST_TIMEOUT_SECONDS = 15L
private const val PROVIDER_GOOGLE_ADS = "google_ads"
private const val GOOGLE_ADS_REFRESH_TOKEN_GRANT = "refresh_token"
private const val ACCESS_TOKEN_EXPIRY_SKEW_SECONDS = 60L

@Serializable
data class GoogleAdsOAuthCredential(
    val refreshToken: String,
    val accessToken: String? = null,
    val expiresAtEpochSeconds: Long? = null,
    val tokenType: String? = null,
    val scope: String? = null,
    val loginCustomerId: String? = null,
)

data class GoogleAdsCustomerAccount(
    val customerId: String,
    val resourceName: String,
    val descriptiveName: String?,
    val manager: Boolean,
    val testAccount: Boolean?,
    val status: String?,
    val currencyCode: String?,
    val timeZone: String?,
    val level: Int?,
    val loginCustomerId: String?,
)

class GoogleAdsClientException(
    message: String,
    val code: String,
    val retryable: Boolean = false,
) : RuntimeException(message)

interface GoogleAdsProviderClient {
    fun validateCustomer(
        credential: GoogleAdsOAuthCredential,
        customerId: String,
        managerCustomerId: String?,
    ): GoogleAdsCustomerAccount

    fun listAccessibleCustomers(credential: GoogleAdsOAuthCredential): List<GoogleAdsCustomerAccount>

    fun listCustomerClients(
        credential: GoogleAdsOAuthCredential,
        loginCustomerId: String,
    ): List<GoogleAdsCustomerAccount>
}

data class GoogleAdsClientConfig(
    val baseUrl: String = GOOGLE_ADS_BASE_URL,
    val oauthTokenUrl: String = GOOGLE_OAUTH_TOKEN_URL,
    val apiVersion: String = EnvConfig.get("GOOGLE_ADS_API_VERSION", GOOGLE_ADS_DEFAULT_API_VERSION),
    val developerTokenProvider: () -> String? = { EnvConfig.get("GOOGLE_ADS_DEVELOPER_TOKEN") },
    val clientIdProvider: () -> String? = { EnvConfig.get("GOOGLE_ADS_CLIENT_ID") },
    val clientSecretProvider: () -> String? = { EnvConfig.get("GOOGLE_ADS_CLIENT_SECRET") },
)

class GoogleAdsClient(
    private val config: GoogleAdsClientConfig = GoogleAdsClientConfig(),
    private val httpClient: HttpClient = HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofSeconds(GOOGLE_ADS_CONNECT_TIMEOUT_SECONDS))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) : GoogleAdsProviderClient {
    private val json = Json { ignoreUnknownKeys = true }

    override fun validateCustomer(
        credential: GoogleAdsOAuthCredential,
        customerId: String,
        managerCustomerId: String?,
    ): GoogleAdsCustomerAccount {
        val normalizedCustomerId = normalizeCustomerId(customerId)
            ?: throw GoogleAdsClientException("Google Ads customer ID is required", "missing_customer_id")
        val normalizedManagerId = normalizeCustomerId(managerCustomerId ?: credential.loginCustomerId)
        if (normalizedManagerId != null) {
            val managed = listCustomerClients(credential, normalizedManagerId)
            val match = managed.firstOrNull { account -> account.customerId == normalizedCustomerId }
            if (match != null) return match
        }
        val accessible = listAccessibleCustomers(credential)
        if (accessible.none { account -> account.customerId == normalizedCustomerId }) {
            throw GoogleAdsClientException(
                "Google Ads customer was not visible to this OAuth grant",
                "google_ads_customer_not_found",
            )
        }
        return getCustomer(credential, normalizedCustomerId, normalizedManagerId)
    }

    override fun listAccessibleCustomers(credential: GoogleAdsOAuthCredential): List<GoogleAdsCustomerAccount> {
        val response = sendAdsRequest(
            GoogleAdsRequest(
                credential = credential,
                method = "GET",
                path = "/customers:listAccessibleCustomers",
                loginCustomerId = null,
                body = null,
                operation = "list accessible Google Ads customers",
            )
        )
        val root = json.parseToJsonElement(response.body()).jsonObject
        return root["resourceNames"]
            ?.jsonArray
            ?.mapNotNull { element ->
                val resourceName = element.jsonPrimitive.contentOrNull ?: return@mapNotNull null
                val customerId = normalizeCustomerId(resourceName) ?: return@mapNotNull null
                GoogleAdsCustomerAccount(
                    customerId = customerId,
                    resourceName = resourceName,
                    descriptiveName = null,
                    manager = false,
                    testAccount = null,
                    status = null,
                    currencyCode = null,
                    timeZone = null,
                    level = null,
                    loginCustomerId = null,
                )
            }
            .orEmpty()
    }

    override fun listCustomerClients(
        credential: GoogleAdsOAuthCredential,
        loginCustomerId: String,
    ): List<GoogleAdsCustomerAccount> {
        val normalizedLoginCustomerId = normalizeCustomerId(loginCustomerId)
            ?: throw GoogleAdsClientException("Google Ads manager customer ID is required", "missing_manager_id")
        val response = searchStream(
            credential = credential,
            customerId = normalizedLoginCustomerId,
            loginCustomerId = normalizedLoginCustomerId,
            query = CUSTOMER_CLIENT_QUERY,
            operation = "list Google Ads customer clients",
        )
        return parseSearchResults(response.body(), "customerClient").mapNotNull { obj ->
            val resourceName = obj.string("clientCustomer") ?: obj.string("resourceName") ?: return@mapNotNull null
            val customerId = normalizeCustomerId(resourceName) ?: return@mapNotNull null
            GoogleAdsCustomerAccount(
                customerId = customerId,
                resourceName = resourceName,
                descriptiveName = obj.string("descriptiveName"),
                manager = obj.boolean("manager") == true,
                testAccount = obj.boolean("testAccount"),
                status = obj.string("status"),
                currencyCode = obj.string("currencyCode"),
                timeZone = obj.string("timeZone"),
                level = obj.int("level"),
                loginCustomerId = normalizedLoginCustomerId,
            )
        }
    }

    private fun getCustomer(
        credential: GoogleAdsOAuthCredential,
        customerId: String,
        loginCustomerId: String?,
    ): GoogleAdsCustomerAccount {
        val response = searchStream(
            credential = credential,
            customerId = customerId,
            loginCustomerId = loginCustomerId,
            query = CUSTOMER_QUERY,
            operation = "inspect Google Ads customer",
        )
        val customer = parseSearchResults(response.body(), "customer").firstOrNull()
        return GoogleAdsCustomerAccount(
            customerId = customerId,
            resourceName = customer?.string("resourceName") ?: "customers/$customerId",
            descriptiveName = customer?.string("descriptiveName"),
            manager = customer?.boolean("manager") == true,
            testAccount = customer?.boolean("testAccount"),
            status = customer?.string("status"),
            currencyCode = customer?.string("currencyCode"),
            timeZone = customer?.string("timeZone"),
            level = null,
            loginCustomerId = loginCustomerId,
        )
    }

    private fun searchStream(
        credential: GoogleAdsOAuthCredential,
        customerId: String,
        loginCustomerId: String?,
        query: String,
        operation: String,
    ): HttpResponse<String> {
        val body = json.encodeToString(JsonObject(mapOf("query" to JsonPrimitive(query))))
        return sendAdsRequest(
            GoogleAdsRequest(
                credential = credential,
                method = "POST",
                path = "/customers/${pathSegment(customerId)}/googleAds:searchStream",
                loginCustomerId = loginCustomerId,
                body = body,
                operation = operation,
            )
        )
    }

    private fun sendAdsRequest(requestOptions: GoogleAdsRequest): HttpResponse<String> {
        val uri = googleAdsUri(requestOptions.path)
        val builder = HttpRequest
            .newBuilder(uri)
            .timeout(Duration.ofSeconds(GOOGLE_ADS_REQUEST_TIMEOUT_SECONDS))
            .header("Authorization", "Bearer ${accessToken(requestOptions.credential)}")
            .header("developer-token", developerToken())
            .header("Accept", "application/json")
        if (requestOptions.loginCustomerId != null) {
            builder.header("login-customer-id", requestOptions.loginCustomerId)
        }
        if (requestOptions.method == "POST") {
            builder
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestOptions.body.orEmpty()))
        } else {
            builder.GET()
        }
        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        ensureSuccess(response, requestOptions.operation)
        return response
    }

    private fun accessToken(credential: GoogleAdsOAuthCredential): String {
        val now = Clock.System.now().epochSeconds
        val usableAccessToken =
            credential.accessToken?.takeIf {
                credential.expiresAtEpochSeconds == null ||
                    credential.expiresAtEpochSeconds > now + ACCESS_TOKEN_EXPIRY_SKEW_SECONDS
            }
        if (!usableAccessToken.isNullOrBlank()) return usableAccessToken
        return refreshAccessToken(credential)
    }

    private fun refreshAccessToken(credential: GoogleAdsOAuthCredential): String {
        val refreshToken = credential.refreshToken.trim().takeIf { it.isNotBlank() }
            ?: throw GoogleAdsClientException("Google Ads refresh token is required", "missing_refresh_token")
        val form = formBody(
            mapOf(
                "client_id" to oauthClientId(),
                "client_secret" to oauthClientSecret(),
                "refresh_token" to refreshToken,
                "grant_type" to GOOGLE_ADS_REFRESH_TOKEN_GRANT,
            )
        )
        val uri = URI.create(config.oauthTokenUrl)
        if (uri.scheme != "https" || uri.host != "oauth2.googleapis.com") {
            throw GoogleAdsClientException("Invalid Google OAuth token host", "invalid_provider_host")
        }
        val request = HttpRequest
            .newBuilder(uri)
            .timeout(Duration.ofSeconds(GOOGLE_ADS_REQUEST_TIMEOUT_SECONDS))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        ensureSuccess(response, "refresh Google Ads OAuth token")
        return json
            .parseToJsonElement(response.body())
            .jsonObject
            .string("access_token")
            ?: throw GoogleAdsClientException("Google OAuth token response was missing an access token", "oauth_error")
    }

    private fun googleAdsUri(path: String): URI {
        val version = config.apiVersion.trim().trim('/')
        val normalizedPath = path.trimStart('/')
        val uri = URI.create("${config.baseUrl.trimEnd('/')}/$version/$normalizedPath")
        if (uri.scheme != "https" || uri.host != "googleads.googleapis.com") {
            throw GoogleAdsClientException("Invalid Google Ads API host", "invalid_provider_host")
        }
        return uri
    }

    private fun developerToken(): String =
        config.developerTokenProvider()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw GoogleAdsClientException(
                "Google Ads developer token is not configured",
                "google_ads_not_configured",
            )

    private fun oauthClientId(): String =
        config.clientIdProvider()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw GoogleAdsClientException(
                "Google Ads OAuth client ID is not configured",
                "google_ads_not_configured",
            )

    private fun oauthClientSecret(): String =
        config.clientSecretProvider()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw GoogleAdsClientException(
                "Google Ads OAuth client secret is not configured",
                "google_ads_not_configured",
            )

    private fun ensureSuccess(
        response: HttpResponse<String>,
        operation: String,
    ) {
        when (response.statusCode()) {
            in HTTP_SUCCESS_RANGE -> return
            HTTP_UNAUTHORIZED -> throw GoogleAdsClientException(
                "Google Ads rejected the OAuth credential while trying to $operation",
                "google_ads_unauthorized",
            )
            HTTP_FORBIDDEN -> throw GoogleAdsClientException(
                "Google Ads API permissions were insufficient while trying to $operation",
                "google_ads_forbidden",
            )
            HTTP_NOT_FOUND -> throw GoogleAdsClientException(
                "Google Ads resource was not found while trying to $operation",
                "google_ads_not_found",
            )
            HTTP_TOO_MANY_REQUESTS -> throw GoogleAdsClientException(
                "Google Ads rate limited the request to $operation",
                "google_ads_rate_limited",
                retryable = true,
            )
            else -> throw GoogleAdsClientException(
                "Google Ads API request failed while trying to $operation",
                "google_ads_api_error",
                retryable = response.statusCode() >= HTTP_SERVER_ERROR_MIN,
            )
        }
    }

    private fun parseSearchResults(
        body: String,
        objectKey: String,
    ): List<JsonObject> {
        val root = json.parseToJsonElement(body)
        val batches = if (root is JsonArray) root.toList() else listOf(root)
        return batches.flatMap { batch ->
            batch.jsonObjectOrNull()
                ?.get("results")
                ?.jsonArray
                ?.mapNotNull { result -> result.jsonObjectOrNull()?.get(objectKey)?.jsonObjectOrNull() }
                .orEmpty()
        }
    }

    private fun formBody(values: Map<String, String>): String =
        values.entries.joinToString("&") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }

    private fun pathSegment(value: String): String =
        value.trim().replace("/", "%2F")

    companion object {
        const val PROVIDER_ID: String = PROVIDER_GOOGLE_ADS
        const val AUTH_PROFILE_MANAGER_OAUTH: String = "manager_oauth"
        const val RESOURCE_TYPE_CUSTOMER: String = "google_ads_customer"
        const val RESOURCE_TYPE_MANAGER: String = "google_ads_manager"
        const val OAUTH_SCOPE: String = "https://www.googleapis.com/auth/adwords"

        fun normalizeCustomerId(value: String?): String? =
            value
                ?.filter { char -> char.isDigit() }
                ?.takeIf { it.isNotBlank() }

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR_MIN = 500
        private val HTTP_SUCCESS_RANGE = 200..299
        private val CUSTOMER_QUERY = """
            SELECT
              customer.id,
              customer.resource_name,
              customer.descriptive_name,
              customer.manager,
              customer.test_account,
              customer.status,
              customer.currency_code,
              customer.time_zone
            FROM customer
            LIMIT 1
        """.trimIndent()
        private val CUSTOMER_CLIENT_QUERY = """
            SELECT
              customer_client.client_customer,
              customer_client.descriptive_name,
              customer_client.manager,
              customer_client.test_account,
              customer_client.status,
              customer_client.currency_code,
              customer_client.time_zone,
              customer_client.level
            FROM customer_client
            WHERE customer_client.level <= 1
        """.trimIndent()
    }
}

private fun JsonElement.jsonObjectOrNull(): JsonObject? =
    this as? JsonObject

private fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.boolean(key: String): Boolean? =
    this[key]?.jsonPrimitive?.booleanOrNull

private fun JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull

private fun String.urlEncode(): String =
    URLEncoder.encode(this, Charsets.UTF_8)

private data class GoogleAdsRequest(
    val credential: GoogleAdsOAuthCredential,
    val method: String,
    val path: String,
    val loginCustomerId: String?,
    val body: String?,
    val operation: String,
)
