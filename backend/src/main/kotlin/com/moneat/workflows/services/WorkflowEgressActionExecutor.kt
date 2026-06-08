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

package com.moneat.workflows.services

import com.moneat.config.EnvConfig
import com.moneat.workflows.engine.temporal.HTTP_REQUEST_ACTION
import com.moneat.workflows.engine.temporal.TRANSFORM_GRAALJS_ACTION
import com.moneat.workflows.engine.temporal.workflowEgressActionsEnabled
import com.moneat.workflows.models.workflowJson
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess

private const val DEFAULT_HTTP_TIMEOUT_SECONDS = 15L
private const val DEFAULT_TRANSFORM_TIMEOUT_SECONDS = 5L
private const val DEFAULT_EGRESS_PROXY_PORT = 3128
private const val MAX_HTTP_BODY_CHARS = 64_000
private const val MAX_TRANSFORM_OUTPUT_CHARS = 64_000
private const val IPV4_ZERO_PREFIX = 0
private const val IPV6_ULA_MASK = 0xFE
private const val IPV6_ULA_PREFIX = 0xFC
private const val OCTET_MASK = 0xFF
private const val HTTP_STATUS_KEY = "status_code"
private const val HTTP_BODY_KEY = "body"
private const val HTTP_URL_KEY = "url"
private const val TRANSFORM_RESULT_KEY = "result"
private val SAFE_HTTP_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
private val BLOCKED_HOST_SUFFIXES = listOf(".local", ".localhost", ".internal")

/**
 * Executes workflow actions that are intentionally not trusted with database or
 * internal-service access. Production runs this executor on `moneat-workflows-egress`
 * behind an egress proxy and on an outbound-only Docker network.
 */
class WorkflowEgressActionExecutor(
    private val proxySelector: ProxySelector? = proxySelectorFromEnv()
) {
    private val httpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(DEFAULT_HTTP_TIMEOUT_SECONDS))
            .apply { proxySelector?.let { proxy(it) } }
            .build()

    fun supports(stepName: String): Boolean =
        stepName == HTTP_REQUEST_ACTION || stepName == TRANSFORM_GRAALJS_ACTION

    fun execute(
        stepName: String,
        params: Map<String, String>,
        scope: Map<String, String>
    ): Map<String, JsonElement> {
        require(workflowEgressActionsEnabled()) {
            "Egress workflow actions are disabled; set WORKFLOWS_EGRESS_ENABLED=true on the isolated egress worker"
        }
        return when (stepName) {
            HTTP_REQUEST_ACTION -> executeHttpRequest(params)
            TRANSFORM_GRAALJS_ACTION -> executeTransform(params, scope)
            else -> throw IllegalArgumentException("Unknown egress workflow step $stepName")
        }
    }

    private fun executeHttpRequest(params: Map<String, String>): Map<String, JsonElement> {
        val uri = params.requiredUri("url")
        requirePublicHttpTarget(uri)
        require(proxySelector != null) {
            "HTTP workflow egress requires WORKFLOWS_EGRESS_PROXY_URL so DNS resolution is enforced by the proxy"
        }
        val method = params.optional("method")?.uppercase() ?: "GET"
        require(method in SAFE_HTTP_METHODS) { "Unsupported HTTP method $method" }
        val timeout = params.optionalLong("timeout_seconds")
            ?.coerceIn(1L, DEFAULT_HTTP_TIMEOUT_SECONDS)
            ?: DEFAULT_HTTP_TIMEOUT_SECONDS
        val body = params.optional("body").orEmpty()
        val requestBuilder =
            HttpRequest
                .newBuilder(uri)
                .timeout(Duration.ofSeconds(timeout))
                .method(method, bodyPublisher(method, body))
        params.headerMap().forEach { (name, value) ->
            requireSafeHeader(name)
            requestBuilder.header(name, value)
        }
        val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        return mapOf(
            HTTP_STATUS_KEY to JsonPrimitive(response.statusCode()),
            HTTP_URL_KEY to JsonPrimitive(uri.toString()),
            HTTP_BODY_KEY to JsonPrimitive(response.body().take(MAX_HTTP_BODY_CHARS))
        )
    }

    private fun executeTransform(
        params: Map<String, String>,
        scope: Map<String, String>
    ): Map<String, JsonElement> {
        val script = params.required("script")
        val timeoutSeconds =
            params.optionalLong("timeout_seconds")
                ?.coerceIn(1L, DEFAULT_TRANSFORM_TIMEOUT_SECONDS)
                ?: DEFAULT_TRANSFORM_TIMEOUT_SECONDS
        val result = evaluateJavaScript(script, scope, Duration.ofSeconds(timeoutSeconds))
        val serialized = workflowJson.encodeToString(JsonElement.serializer(), result)
        require(serialized.length <= MAX_TRANSFORM_OUTPUT_CHARS) {
            "Transform output exceeds $MAX_TRANSFORM_OUTPUT_CHARS characters"
        }
        return mapOf(TRANSFORM_RESULT_KEY to result)
    }

    private fun evaluateJavaScript(
        script: String,
        scope: Map<String, String>,
        timeout: Duration
    ): JsonElement {
        val context =
            Context
                .newBuilder("js")
                .allowAllAccess(false)
                .allowHostAccess(HostAccess.NONE)
                .allowCreateThread(false)
                .allowIO(IOAccess.NONE)
                .build()
        return context.use { js ->
            val scopeJson = workflowJson.encodeToString(scope)
            val source =
                """
                const scope = JSON.parse(${scopeJson.javaScriptStringLiteral()});
                const input = scope;
                (function () {
                $script
                })();
                """.trimIndent()
            val completed = AtomicBoolean(false)
            val interrupter =
                Thread {
                    Thread.sleep(timeout.toMillis())
                    if (!completed.get()) {
                        js.close(true)
                    }
                }
            interrupter.isDaemon = true
            interrupter.start()
            try {
                js.eval("js", source).toJsonElement()
            } finally {
                completed.set(true)
            }
        }
    }

    private fun Value.toJsonElement(): JsonElement =
        when {
            isNull -> JsonPrimitive("")
            isBoolean -> JsonPrimitive(asBoolean())
            fitsInInt() -> JsonPrimitive(asInt())
            fitsInLong() -> JsonPrimitive(asLong())
            fitsInDouble() -> JsonPrimitive(asDouble())
            isString -> parseJsonPrimitive(asString())
            else -> JsonPrimitive(toString())
        }

    private fun parseJsonPrimitive(raw: String): JsonElement =
        runCatching { workflowJson.parseToJsonElement(raw) }.getOrElse { JsonPrimitive(raw) }

    private fun bodyPublisher(
        method: String,
        body: String
    ): HttpRequest.BodyPublisher =
        if (method == "GET" || method == "DELETE") {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofString(body)
        }

    private fun Map<String, String>.headerMap(): Map<String, String> =
        optional("headers")?.let { raw ->
            try {
                workflowJson.decodeFromString<Map<String, String>>(raw)
            } catch (error: SerializationException) {
                throw IllegalArgumentException("headers must be a JSON object of string values", error)
            }
        } ?: emptyMap()

    private fun Map<String, String>.requiredUri(name: String): URI =
        URI.create(required(name)).normalize()

    private fun requirePublicHttpTarget(uri: URI) {
        require(uri.scheme == "https" || uri.scheme == "http") { "Only http and https URLs are allowed" }
        require(uri.userInfo == null) { "URL user info is not allowed" }
        val host = uri.host?.lowercase() ?: throw IllegalArgumentException("URL host is required")
        require(host != "localhost" && BLOCKED_HOST_SUFFIXES.none { host.endsWith(it) }) {
            "Internal hostnames are not allowed"
        }
        val addresses = InetAddress.getAllByName(host).toList()
        require(addresses.isNotEmpty()) { "URL host did not resolve" }
        require(addresses.none { it.isBlockedForEgress() }) {
            "Private, local and link-local addresses are not allowed"
        }
    }

    private fun InetAddress.isBlockedForEgress(): Boolean =
        isAnyLocalAddress ||
            isLoopbackAddress ||
            isLinkLocalAddress ||
            isSiteLocalAddress ||
            isMulticastAddress ||
            isReservedRange()

    // Covers ranges that the JDK helpers above miss: IPv4 0.0.0.0/8 and IPv6 unique-local fc00::/7.
    // RFC1918 (isSiteLocalAddress) and metadata 169.254.0.0/16 (isLinkLocalAddress) are already blocked.
    private fun InetAddress.isReservedRange(): Boolean =
        when (this) {
            is Inet4Address -> (address[0].toInt() and OCTET_MASK) == IPV4_ZERO_PREFIX
            is Inet6Address -> (address[0].toInt() and IPV6_ULA_MASK) == IPV6_ULA_PREFIX
            else -> false
        }

    private fun requireSafeHeader(name: String) {
        val lowerName = name.lowercase()
        require(!lowerName.startsWith("x-moneat-")) { "Internal Moneat headers are not allowed" }
        require(lowerName !in setOf("host", "connection", "authorization", "cookie")) {
            "Header $name is managed by the egress proxy or connection resolver"
        }
    }

    private fun Map<String, String>.required(name: String): String =
        optional(name) ?: throw IllegalArgumentException("Missing required parameter $name")

    private fun Map<String, String>.optional(name: String): String? =
        this[name]?.trim()?.takeIf { it.isNotEmpty() }

    private fun Map<String, String>.optionalLong(name: String): Long? =
        optional(name)?.let {
            it.toLongOrNull() ?: throw IllegalArgumentException("Parameter $name must be a number")
        }

    private fun String.javaScriptStringLiteral(): String =
        workflowJson.encodeToString(this)

    companion object {
        private fun proxySelectorFromEnv(): ProxySelector? {
            val proxyUri = EnvConfig.get("WORKFLOWS_EGRESS_PROXY_URL")?.takeIf { it.isNotBlank() } ?: return null
            val uri = URI.create(proxyUri)
            val port = if (uri.port > 0) uri.port else DEFAULT_EGRESS_PROXY_PORT
            return ProxySelector.of(InetSocketAddress(uri.host, port))
        }
    }
}
