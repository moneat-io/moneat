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

package com.moneat.synthetics.routes

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.basicAuth
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

private const val SSL_DEFAULT_PORT = 443
private const val DNS_TIMEOUT_MS = 5000L
private const val TCP_TIMEOUT_MS = 10000
private const val UDP_TIMEOUT_MS = 5000
private const val DAYS_PER_MS = 86_400_000L

data class SyntheticCheckResult(
    val status: String, // "passed" or "failed"
    val durationMs: Long,
    val errorMessage: String = "",
    val timings: Map<String, Double> = emptyMap()
)

open class SyntheticsCheckExecutor {

    open suspend fun executeTest(test: SyntheticTestData): SyntheticCheckResult {
        return when (test.testType.lowercase()) {
            "multistep" -> executeMultistepTest(test)
            "ssl" -> executeSslTest(test)
            "dns" -> executeDnsTest(test)
            "tcp" -> executeTcpTest(test)
            "udp" -> executeUdpTest(test)
            else -> executeApiTest(test)
        }
    }

    private suspend fun executeApiTest(
        test: SyntheticTestData
    ): SyntheticCheckResult {
        val url = test.url ?: return SyntheticCheckResult(
            status = "failed",
            durationMs = 0,
            errorMessage = "No URL configured"
        )

        val timeoutMs = test.timeoutSeconds * 1000L
        val client = buildClient(timeoutMs)

        val totalStart = System.nanoTime()
        return try {
            val headersMap: Map<String, String> = parseHeaders(test.headers)
            val assertionList = parseAssertions(test.assertions)

            val response = client.request(url) {
                method = resolveHttpMethod(test.method)
                headersMap.forEach { (k, v) -> header(k, v) }
                when (test.authMethod?.lowercase()) {
                    "basic" -> basicAuth(
                        test.authUser ?: "",
                        test.authPass ?: ""
                    )
                    "bearer" -> bearerAuth(test.authPass ?: "")
                }
                test.body?.let { b -> setBody(b) }
            }

            val ttfbNs = System.nanoTime() - totalStart
            val statusCode = response.status.value
            val body = response.bodyAsText()
            val totalNs = System.nanoTime() - totalStart
            val responseHeaders = response.headers.entries()
                .associate { (k, v) -> k to v.firstOrNull().orEmpty() }

            val durationMs = totalNs / NS_PER_MS
            val timings = mutableMapOf(
                "ttfb" to (ttfbNs.toDouble() / NS_PER_MS),
                "total" to (totalNs.toDouble() / NS_PER_MS)
            )

            val allPassed = assertionList.all { assertion ->
                evaluateAssertion(
                    assertion,
                    statusCode,
                    body,
                    durationMs,
                    responseHeaders
                )
            }

            if (allPassed) {
                SyntheticCheckResult(
                    status = "passed",
                    durationMs = durationMs,
                    timings = timings
                )
            } else {
                SyntheticCheckResult(
                    status = "failed",
                    durationMs = durationMs,
                    errorMessage = "One or more assertions failed",
                    timings = timings
                )
            }
        } catch (e: Exception) {
            val durationMs = (System.nanoTime() - totalStart) / NS_PER_MS
            logger.warn { "API test failed for ${test.id}: ${e.message}" }
            SyntheticCheckResult(
                status = "failed",
                durationMs = durationMs,
                errorMessage = e.message ?: "Request failed"
            )
        } finally {
            client.close()
        }
    }

    private fun executeSslTest(
        test: SyntheticTestData
    ): SyntheticCheckResult {
        val config: SyntheticTestConfig? = test.config?.let {
            try {
                Json.decodeFromString(it)
            } catch (_: Exception) {
                null
            }
        }
        val hostname = config?.hostname
            ?: test.url?.removePrefix("https://")
                ?.removePrefix("http://")?.split("/")?.firstOrNull()
            ?: return SyntheticCheckResult(
                status = "failed", durationMs = 0,
                errorMessage = "No hostname configured"
            )

        val port = config?.port ?: SSL_DEFAULT_PORT
        val startTime = System.currentTimeMillis()

        return try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tlsStart = System.currentTimeMillis()
            val socket = factory.createSocket(
                hostname,
                port
            ) as SSLSocket
            socket.use { sslSocket ->
                sslSocket.startHandshake()
                val tlsMs = System.currentTimeMillis() - tlsStart
                val session = sslSocket.session
                val cert = session.peerCertificates.firstOrNull()
                    as? java.security.cert.X509Certificate

                val durationMs = System.currentTimeMillis() - startTime
                val timings = mutableMapOf(
                    "tls" to tlsMs.toDouble(),
                    "total" to durationMs.toDouble()
                )

                if (cert == null) {
                    return SyntheticCheckResult(
                        status = "failed",
                        durationMs = durationMs,
                        errorMessage = "No certificate found",
                        timings = timings
                    )
                }

                val expiryMs = cert.notAfter.time -
                    System.currentTimeMillis()
                val expiryDays = expiryMs / DAYS_PER_MS

                val assertionList = parseAssertions(test.assertions)
                val allPassed = assertionList.all { assertion ->
                    evaluateSslAssertion(
                        assertion,
                        cert,
                        expiryDays
                    )
                }

                timings["certificate_expiry_days"] =
                    expiryDays.toDouble()

                if (allPassed) {
                    SyntheticCheckResult(
                        status = "passed",
                        durationMs = durationMs,
                        timings = timings
                    )
                } else {
                    SyntheticCheckResult(
                        status = "failed",
                        durationMs = durationMs,
                        errorMessage = "SSL assertion failed",
                        timings = timings
                    )
                }
            }
        } catch (e: Exception) {
            SyntheticCheckResult(
                status = "failed",
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = "SSL check failed: ${e.message}"
            )
        }
    }

    private suspend fun executeDnsTest(
        test: SyntheticTestData
    ): SyntheticCheckResult {
        val hostname = test.url
            ?.removePrefix("https://")
            ?.removePrefix("http://")
            ?.split("/")?.firstOrNull()
            ?: return SyntheticCheckResult(
                status = "failed", durationMs = 0,
                errorMessage = "No hostname configured"
            )

        val startTime = System.currentTimeMillis()
        return try {
            val dnsStart = System.currentTimeMillis()
            val addresses = withTimeout(DNS_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    InetAddress.getAllByName(hostname)
                }
            }
            val dnsMs = System.currentTimeMillis() - dnsStart
            val durationMs = System.currentTimeMillis() - startTime

            val resolvedIps = addresses.map { it.hostAddress }
            val timings = mapOf(
                "dns" to dnsMs.toDouble(),
                "total" to durationMs.toDouble()
            )

            val assertionList = parseAssertions(test.assertions)
            val allPassed = assertionList.all { assertion ->
                evaluateDnsAssertion(
                    assertion,
                    resolvedIps,
                    dnsMs
                )
            }

            if (allPassed) {
                SyntheticCheckResult(
                    status = "passed",
                    durationMs = durationMs,
                    timings = timings
                )
            } else {
                SyntheticCheckResult(
                    status = "failed",
                    durationMs = durationMs,
                    errorMessage = "DNS assertion failed",
                    timings = timings
                )
            }
        } catch (_: TimeoutCancellationException) {
            SyntheticCheckResult(
                status = "failed",
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = "DNS resolution timed out after ${DNS_TIMEOUT_MS}ms"
            )
        } catch (e: Exception) {
            SyntheticCheckResult(
                status = "failed",
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = "DNS resolution failed: ${e.message}"
            )
        }
    }

    private fun executeTcpTest(
        test: SyntheticTestData
    ): SyntheticCheckResult {
        val config: SyntheticTestConfig? = test.config?.let {
            try {
                Json.decodeFromString(it)
            } catch (_: Exception) {
                null
            }
        }
        val hostname = config?.hostname
            ?: test.url?.removePrefix("https://")
                ?.removePrefix("http://")?.split("/")?.firstOrNull()
            ?: return SyntheticCheckResult(
                status = "failed", durationMs = 0,
                errorMessage = "No hostname configured"
            )
        val port = config?.port ?: return SyntheticCheckResult(
            status = "failed", durationMs = 0,
            errorMessage = "No port configured"
        )

        val startTime = System.currentTimeMillis()
        return try {
            val socket = Socket()
            val connectStart = System.currentTimeMillis()
            socket.connect(
                InetSocketAddress(hostname, port),
                TCP_TIMEOUT_MS
            )
            val connectMs = System.currentTimeMillis() - connectStart
            socket.close()

            val durationMs = System.currentTimeMillis() - startTime
            val timings = mapOf(
                "tcp" to connectMs.toDouble(),
                "total" to durationMs.toDouble()
            )

            val assertionList = parseAssertions(test.assertions)
            val allPassed = assertionList.all { assertion ->
                evaluateTcpAssertion(assertion, connectMs, true)
            }

            if (allPassed) {
                SyntheticCheckResult(
                    status = "passed",
                    durationMs = durationMs,
                    timings = timings
                )
            } else {
                SyntheticCheckResult(
                    status = "failed",
                    durationMs = durationMs,
                    errorMessage = "TCP assertion failed",
                    timings = timings
                )
            }
        } catch (e: Exception) {
            SyntheticCheckResult(
                status = "failed",
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = "TCP connect failed: ${e.message}"
            )
        }
    }

    private fun executeUdpTest(
        test: SyntheticTestData
    ): SyntheticCheckResult {
        val config: SyntheticTestConfig? = test.config?.let {
            try {
                Json.decodeFromString(it)
            } catch (_: Exception) {
                null
            }
        }
        val hostname = config?.hostname
            ?: test.url?.removePrefix("https://")
                ?.removePrefix("http://")?.split("/")?.firstOrNull()
            ?: return SyntheticCheckResult(
                status = "failed", durationMs = 0,
                errorMessage = "No hostname configured"
            )
        val port = config?.port ?: return SyntheticCheckResult(
            status = "failed", durationMs = 0,
            errorMessage = "No port configured"
        )

        val startTime = System.currentTimeMillis()
        return try {
            val address = InetAddress.getByName(hostname)
            val sendData = ByteArray(1) { 0 }
            val packet = DatagramPacket(
                sendData,
                sendData.size,
                address,
                port
            )
            val socket = DatagramSocket()
            socket.soTimeout = UDP_TIMEOUT_MS

            val connectStart = System.currentTimeMillis()
            socket.send(packet)

            val recvBuf = ByteArray(1)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            val portOpen = try {
                socket.receive(recvPacket)
                true
            } catch (_: java.net.SocketTimeoutException) {
                // No response is normal for many UDP services
                true
            } catch (_: java.net.PortUnreachableException) {
                false
            }
            val connectMs = System.currentTimeMillis() - connectStart
            socket.close()

            val durationMs = System.currentTimeMillis() - startTime
            val timings = mapOf(
                "udp" to connectMs.toDouble(),
                "total" to durationMs.toDouble()
            )

            val assertionList = parseAssertions(test.assertions)
            val allPassed = assertionList.all { assertion ->
                evaluateTcpAssertion(assertion, connectMs, portOpen)
            }

            if (allPassed) {
                SyntheticCheckResult(
                    status = "passed",
                    durationMs = durationMs,
                    timings = timings
                )
            } else {
                SyntheticCheckResult(
                    status = "failed",
                    durationMs = durationMs,
                    errorMessage = "UDP assertion failed",
                    timings = timings
                )
            }
        } catch (e: Exception) {
            SyntheticCheckResult(
                status = "failed",
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = "UDP check failed: ${e.message}"
            )
        }
    }

    private suspend fun executeMultistepTest(test: SyntheticTestData): SyntheticCheckResult {
        val steps: List<SyntheticStep> = try {
            test.steps?.let { Json.decodeFromString(it) } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        if (steps.isEmpty()) {
            return SyntheticCheckResult(status = "failed", durationMs = 0, errorMessage = "No steps configured")
        }

        val timeoutMs = test.timeoutSeconds * 1000L
        val client = buildClient(timeoutMs)
        val variables = mutableMapOf<String, String>()
        val startTime = System.currentTimeMillis()

        return try {
            for (step in steps) {
                val stepUrl = substituteVariables(step.url, variables)
                val stepBody = step.body?.let { substituteVariables(it, variables) }
                val stepHeaders = step.headers?.mapValues { (_, v) -> substituteVariables(v, variables) }

                val stepStart = System.currentTimeMillis()
                val response = try {
                    client.request(stepUrl) {
                        method = resolveHttpMethod(step.method)
                        stepHeaders?.forEach { (k, v) -> header(k, v) }
                        stepBody?.let { b -> setBody(b) }
                    }
                } catch (e: Exception) {
                    val durationMs = System.currentTimeMillis() - startTime
                    return SyntheticCheckResult(
                        status = "failed",
                        durationMs = durationMs,
                        errorMessage = "Step '${step.name}' failed: ${e.message}"
                    )
                }

                val stepDurationMs = System.currentTimeMillis() - stepStart
                val statusCode = response.status.value
                val body = response.bodyAsText()
                val responseHeaders = response.headers.entries()
                    .associate { (k, v) -> k to v.firstOrNull().orEmpty() }

                // Evaluate step assertions
                val allPassed = step.assertions.all { assertion ->
                    evaluateAssertion(assertion, statusCode, body, stepDurationMs, responseHeaders)
                }
                if (!allPassed) {
                    val durationMs = System.currentTimeMillis() - startTime
                    return SyntheticCheckResult(
                        status = "failed",
                        durationMs = durationMs,
                        errorMessage = "Step '${step.name}' assertion failed"
                    )
                }

                // Extract variables for subsequent steps
                step.extractVariables.forEach { extraction ->
                    val extracted = extractVariable(extraction.source, extraction.path, body, responseHeaders)
                    if (extracted != null) {
                        variables[extraction.name] = extracted
                    }
                }
            }

            SyntheticCheckResult(status = "passed", durationMs = System.currentTimeMillis() - startTime)
        } finally {
            client.close()
        }
    }

    private fun evaluateAssertion(
        assertion: SyntheticAssertion,
        statusCode: Int,
        body: String,
        responseTimeMs: Long,
        headers: Map<String, String>
    ): Boolean {
        return try {
            when (assertion.type) {
                "status_code" -> {
                    val expected = assertion.value.toIntOrNull() ?: return false
                    compareValues(statusCode.toLong(), expected.toLong(), assertion.operator)
                }
                "body_contains" -> body.contains(assertion.value)
                "body_json_path" -> {
                    val jsonValue = extractJsonPath(body, assertion.target) ?: return false
                    when (assertion.operator) {
                        "equals" -> jsonValue == assertion.value
                        "not_equals" -> jsonValue != assertion.value
                        "contains" -> jsonValue.contains(assertion.value)
                        else -> jsonValue == assertion.value
                    }
                }
                "response_time" -> {
                    val threshold = assertion.value.toLongOrNull() ?: return false
                    compareValues(responseTimeMs, threshold, assertion.operator)
                }
                "header" -> {
                    val headerValue = headers[assertion.target] ?: return false
                    when (assertion.operator) {
                        "equals" -> headerValue == assertion.value
                        "not_equals" -> headerValue != assertion.value
                        "contains" -> headerValue.contains(assertion.value)
                        else -> headerValue == assertion.value
                    }
                }
                else -> {
                    logger.warn {
                        "Unknown assertion type '${assertion.type}' " +
                            "(assertion operator: ${assertion.operator}) - failing assertion"
                    }
                    false
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun compareValues(actual: Long, expected: Long, operator: String): Boolean {
        return when (operator) {
            "equals" -> actual == expected
            "not_equals" -> actual != expected
            "less_than" -> actual < expected
            "greater_than" -> actual > expected
            else -> actual == expected
        }
    }

    private fun extractJsonPath(body: String, path: String): String? {
        return try {
            val json = Json.parseToJsonElement(body).jsonObject
            val segments = path.removePrefix("$.").split(".")
            var current = json
            for (i in 0 until segments.size - 1) {
                current = current[segments[i]]?.jsonObject ?: return null
            }
            current[segments.last()]?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }
    }

    private fun extractVariable(source: String, path: String, body: String, headers: Map<String, String>): String? {
        return when (source) {
            "body_json_path" -> extractJsonPath(body, path)
            "header" -> headers[path]
            else -> null
        }
    }

    private fun substituteVariables(
        input: String,
        variables: Map<String, String>
    ): String {
        var result = input
        variables.forEach { (name, value) ->
            result = result.replace("{{$name}}", value)
            result = result.replace("{{global.$name}}", value)
        }
        return result
    }

    private fun resolveHttpMethod(method: String): HttpMethod {
        return when (method.uppercase()) {
            "GET" -> HttpMethod.Get
            "POST" -> HttpMethod.Post
            "PUT" -> HttpMethod.Put
            "DELETE" -> HttpMethod.Delete
            "HEAD" -> HttpMethod.Head
            "OPTIONS" -> HttpMethod.Options
            "PATCH" -> HttpMethod.Patch
            else -> HttpMethod.Get
        }
    }

    private fun parseHeaders(headersJson: String?): Map<String, String> {
        return try {
            headersJson?.let { Json.decodeFromString(it) } ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun parseAssertions(assertionsJson: String): List<SyntheticAssertion> {
        return try {
            Json.decodeFromString(assertionsJson)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun buildClient(timeoutMs: Long): HttpClient {
        return HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = timeoutMs
                socketTimeoutMillis = timeoutMs
            }
        }
    }

    private fun evaluateSslAssertion(
        assertion: SyntheticAssertion,
        cert: java.security.cert.X509Certificate,
        expiryDays: Long
    ): Boolean {
        return when (assertion.type) {
            "certificate_expiry_days" -> {
                val threshold = assertion.value.toLongOrNull() ?: return false
                compareValues(expiryDays, threshold, assertion.operator)
            }
            "certificate_valid" -> {
                val expected = assertion.value.toBooleanStrictOrNull()
                    ?: true
                val valid = try {
                    cert.checkValidity()
                    true
                } catch (_: Exception) {
                    false
                }
                valid == expected
            }
            "certificate_issuer" -> {
                val issuer = cert.issuerX500Principal.name
                when (assertion.operator) {
                    "contains" -> issuer.contains(
                        assertion.value,
                        ignoreCase = true
                    )
                    else -> issuer.contains(
                        assertion.value,
                        ignoreCase = true
                    )
                }
            }
            else -> {
                logger.warn { "Unknown SSL assertion type: '${assertion.type}'" }
                false
            }
        }
    }

    private fun evaluateDnsAssertion(
        assertion: SyntheticAssertion,
        resolvedIps: List<String>,
        resolutionTimeMs: Long
    ): Boolean {
        return when (assertion.type) {
            "resolved_ip" -> when (assertion.operator) {
                "contains" -> resolvedIps.any {
                    it.contains(assertion.value)
                }
                "equals" -> resolvedIps.contains(assertion.value)
                else -> resolvedIps.contains(assertion.value)
            }
            "resolution_time" -> {
                val threshold = assertion.value.toLongOrNull()
                    ?: return false
                compareValues(
                    resolutionTimeMs,
                    threshold,
                    assertion.operator
                )
            }
            else -> {
                logger.warn { "Unknown DNS assertion type: '${assertion.type}'" }
                false
            }
        }
    }

    private fun evaluateTcpAssertion(
        assertion: SyntheticAssertion,
        connectionTimeMs: Long,
        portOpen: Boolean
    ): Boolean {
        return when (assertion.type) {
            "connection_time" -> {
                val threshold = assertion.value.toLongOrNull()
                    ?: return false
                compareValues(
                    connectionTimeMs,
                    threshold,
                    assertion.operator
                )
            }
            "port_open" -> {
                val expected = assertion.value.toBooleanStrictOrNull()
                    ?: true
                portOpen == expected
            }
            else -> {
                logger.warn { "Unknown TCP assertion type: '${assertion.type}'" }
                false
            }
        }
    }

    companion object {
        private const val NS_PER_MS = 1_000_000L
    }
}
