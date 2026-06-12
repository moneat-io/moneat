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

import kotlinx.serialization.SerializationException
import java.io.IOException

import com.moneat.utils.UrlValidator
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import com.moneat.utils.suspendRunCatching
import com.moneat.utils.TimeConstants.MILLIS_PER_SECOND_LONG

private val logger = KotlinLogging.logger {}

private const val SSL_DEFAULT_PORT = 443
private const val DNS_TIMEOUT_MS = 5000L
private const val TCP_TIMEOUT_MS = 10000
private const val UDP_TIMEOUT_MS = 5000
private const val DAYS_PER_MS = 86_400_000L
private const val SECRET_MASK_SHORT_LENGTH = 4
private const val SECRET_VISIBLE_SUFFIX_LENGTH = 3

private data class SyntheticHostValidation(
    val addresses: List<InetAddress> = emptyList(),
    val failure: SyntheticCheckResult? = null,
)

data class SyntheticCheckResult(
    val status: String, // "passed" or "failed"
    val durationMs: Long,
    val errorMessage: String = "",
    val timings: Map<String, Double> = emptyMap(),
    val statusCode: Int = 0,
    val assertionResults: List<AssertionResult> = emptyList(),
    val request: CapturedRequest? = null,
    val response: CapturedResponse? = null,
    val resolvedIp: String = "",
    val browser: BrowserRunDetail? = null
)

private val assertionOperatorLabels = mapOf(
    "equals" to "is",
    "not_equals" to "is not",
    "contains" to "contains",
    "less_than" to "<",
    "greater_than" to ">"
)

private val assertionLabelBuilders = mapOf<String, (SyntheticAssertion, String) -> String>(
    "status_code" to { a, op -> "Status code $op ${a.value}" },
    "response_time" to { a, op -> "Response time $op ${a.value} ms" },
    "body_contains" to { a, _ -> "Body contains \"${a.value}\"" },
    "body_json_path" to { a, op -> "${a.target} $op \"${a.value}\"" },
    "header" to { a, op -> "${a.target} $op ${a.value}" },
    "resolved_ip" to { a, op -> "Resolved IP $op ${a.value}" },
    "resolution_time" to { a, op -> "Resolution time $op ${a.value} ms" },
    "connection_time" to { a, op -> "Connection time $op ${a.value} ms" },
    "port_open" to { _, _ -> "Port is open" },
    "certificate_valid" to { _, _ -> "Certificate is valid" },
    "certificate_expiry_days" to { a, op -> "Cert expiry $op ${a.value} days" },
    "certificate_issuer" to { a, op -> "Issuer $op ${a.value}" }
)

open class SyntheticsCheckExecutor {

    private fun extractHostname(url: String): String? {
        fun extractFromAuthority(authority: String): String? =
            if (authority.startsWith("[")) {
                authority.substringAfter("[").substringBefore("]").takeIf { it.isNotBlank() }
            } else {
                authority.split(":").firstOrNull()?.takeIf { it.isNotBlank() }
            }

        return suspendRunCatching {
            java.net.URI(url).host?.takeIf { it.isNotBlank() }
                ?: extractFromAuthority(
                    url.removePrefix("https://").removePrefix("http://")
                        .split("/").firstOrNull() ?: ""
                )
        }.getOrElse { _ ->
            extractFromAuthority(
                url.removePrefix("https://").removePrefix("http://")
                    .split("/").firstOrNull() ?: ""
            )
        }
    }

    open suspend fun executeTest(test: SyntheticTestData): SyntheticCheckResult {
        return when (test.testType.lowercase()) {
            "browser" -> BrowserCheckExecutor.execute(test)
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

        try {
            UrlValidator.validateExternalUrl(url)
        } catch (e: UrlValidator.SsrfException) {
            return SyntheticCheckResult(
                status = "failed",
                durationMs = 0,
                errorMessage = "Blocked: ${e.message}"
            )
        }

        val timeoutMs = test.timeoutSeconds * MILLIS_PER_SECOND_LONG
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

            val assertionResults = assertionList.map { assertion ->
                evaluateAssertion(
                    assertion,
                    statusCode,
                    body,
                    durationMs,
                    responseHeaders
                )
            }
            val allPassed = assertionResults.all { it.passed }
            val capturedRequest = CapturedRequest(
                method = test.method.uppercase(),
                url = url,
                headers = redactHeaders(headersMap),
                body = ""
            )
            val capturedResponse = CapturedResponse(
                statusCode = statusCode,
                headers = redactHeaders(responseHeaders),
                body = ""
            )

            SyntheticCheckResult(
                status = if (allPassed) "passed" else "failed",
                durationMs = durationMs,
                errorMessage = if (allPassed) "" else "One or more assertions failed",
                timings = timings,
                statusCode = statusCode,
                assertionResults = assertionResults,
                request = capturedRequest,
                response = capturedResponse
            )
        } catch (e: SerializationException) {
            val durationMs = (System.nanoTime() - totalStart) / NS_PER_MS
            logger.warn { "API test failed for ${test.id}: ${e.message}" }
            SyntheticCheckResult(
                status = "failed",
                durationMs = durationMs,
                errorMessage = e.message ?: REQUEST_FAILED
            )
        } catch (e: IOException) {
            val durationMs = (System.nanoTime() - totalStart) / NS_PER_MS
            logger.warn { "API test failed for ${test.id}: ${e.message}" }
            SyntheticCheckResult(
                status = "failed",
                durationMs = durationMs,
                errorMessage = e.message ?: REQUEST_FAILED
            )
        } catch (e: IllegalStateException) {
            val durationMs = (System.nanoTime() - totalStart) / NS_PER_MS
            logger.warn { "API test failed for ${test.id}: ${e.message}" }
            SyntheticCheckResult(
                status = "failed",
                durationMs = durationMs,
                errorMessage = e.message ?: REQUEST_FAILED
            )
        } catch (e: IllegalArgumentException) {
            val durationMs = (System.nanoTime() - totalStart) / NS_PER_MS
            logger.warn { "API test failed for ${test.id}: ${e.message}" }
            SyntheticCheckResult(
                status = "failed",
                durationMs = durationMs,
                errorMessage = e.message ?: REQUEST_FAILED
            )
        } finally {
            client.close()
        }
    }

    private fun executeSslTest(
        test: SyntheticTestData
    ): SyntheticCheckResult {
        val config: SyntheticTestConfig? = test.config?.let {
            suspendRunCatching {
                Json.decodeFromString<SyntheticTestConfig>(it)
            }.getOrElse { _ ->
                null
            }
        }
        val hostname = config?.hostname
            ?: test.url?.let { extractHostname(it) }
            ?: return SyntheticCheckResult(
                status = "failed", durationMs = 0,
                errorMessage = "No hostname configured"
            )

        val port = config?.port ?: SSL_DEFAULT_PORT
        val hostValidation = validateSyntheticHostAddresses(hostname)
        hostValidation.failure?.let { return it }
        val address = hostValidation.addresses.first()
        val startTime = System.currentTimeMillis()

        return suspendRunCatching {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tlsStart = System.currentTimeMillis()
            Socket().use { rawSocket ->
                rawSocket.connect(InetSocketAddress(address, port), TCP_TIMEOUT_MS)
                rawSocket.soTimeout = TCP_TIMEOUT_MS
                val socket = factory.createSocket(rawSocket, hostname, port, true) as SSLSocket
                socket.use { sslSocket ->
                    sslSocket.soTimeout = TCP_TIMEOUT_MS
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
                    val assertionResults = assertionList.map { assertion ->
                        evaluateSslAssertion(assertion, cert, expiryDays)
                    }
                    val allPassed = assertionResults.all { it.passed }

                    timings["certificate_expiry_days"] =
                        expiryDays.toDouble()

                    SyntheticCheckResult(
                        status = if (allPassed) "passed" else "failed",
                        durationMs = durationMs,
                        errorMessage = if (allPassed) "" else "SSL assertion failed",
                        timings = timings,
                        assertionResults = assertionResults,
                        resolvedIp = address.hostAddress
                    )
                }
            }
        }.getOrElse { e ->
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
        val hostname = test.url?.let { extractHostname(it) }
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
            validateSyntheticAddresses(addresses)?.let { return it }
            val dnsMs = System.currentTimeMillis() - dnsStart
            val durationMs = System.currentTimeMillis() - startTime

            val resolvedIps = addresses.map { it.hostAddress }
            val timings = mapOf(
                "dns" to dnsMs.toDouble(),
                "total" to durationMs.toDouble()
            )

            val assertionList = parseAssertions(test.assertions)
            val assertionResults = assertionList.map { assertion ->
                evaluateDnsAssertion(assertion, resolvedIps, dnsMs)
            }
            val allPassed = assertionResults.all { it.passed }

            SyntheticCheckResult(
                status = if (allPassed) "passed" else "failed",
                durationMs = durationMs,
                errorMessage = if (allPassed) "" else "DNS assertion failed",
                timings = timings,
                assertionResults = assertionResults,
                resolvedIp = resolvedIps.firstOrNull() ?: ""
            )
        } catch (_: TimeoutCancellationException) {
            SyntheticCheckResult(
                status = "failed",
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = "DNS resolution timed out after ${DNS_TIMEOUT_MS}ms"
            )
        } catch (e: SerializationException) {
            SyntheticCheckResult(
                status = "failed",
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = "DNS resolution failed: ${e.message}"
            )
        } catch (e: IOException) {
            SyntheticCheckResult(
                status = "failed",
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = "DNS resolution failed: ${e.message}"
            )
        } catch (e: IllegalStateException) {
            SyntheticCheckResult(
                status = "failed",
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = "DNS resolution failed: ${e.message}"
            )
        } catch (e: IllegalArgumentException) {
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
            suspendRunCatching {
                Json.decodeFromString<SyntheticTestConfig>(it)
            }.getOrElse { _ ->
                null
            }
        }
        val hostname = config?.hostname
            ?: test.url?.let { extractHostname(it) }
            ?: return SyntheticCheckResult(
                status = "failed", durationMs = 0,
                errorMessage = "No hostname configured"
            )
        val port = config?.port ?: return SyntheticCheckResult(
            status = "failed", durationMs = 0,
            errorMessage = "No port configured"
        )
        val hostValidation = validateSyntheticHostAddresses(hostname)
        hostValidation.failure?.let { return it }
        val address = hostValidation.addresses.first()

        val startTime = System.currentTimeMillis()
        return suspendRunCatching {
            val socket = Socket()
            val connectStart = System.currentTimeMillis()
            socket.connect(
                InetSocketAddress(address, port),
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
            val assertionResults = assertionList.map { assertion ->
                evaluateTcpAssertion(assertion, connectMs, true)
            }
            val allPassed = assertionResults.all { it.passed }

            SyntheticCheckResult(
                status = if (allPassed) "passed" else "failed",
                durationMs = durationMs,
                errorMessage = if (allPassed) "" else "TCP assertion failed",
                timings = timings,
                assertionResults = assertionResults,
                resolvedIp = address.hostAddress
            )
        }.getOrElse { e ->
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
            suspendRunCatching {
                Json.decodeFromString<SyntheticTestConfig>(it)
            }.getOrElse { _ ->
                null
            }
        }
        val hostname = config?.hostname
            ?: test.url?.let { extractHostname(it) }
            ?: return SyntheticCheckResult(
                status = "failed", durationMs = 0,
                errorMessage = "No hostname configured"
            )
        val port = config?.port ?: return SyntheticCheckResult(
            status = "failed", durationMs = 0,
            errorMessage = "No port configured"
        )
        val hostValidation = validateSyntheticHostAddresses(hostname)
        hostValidation.failure?.let { return it }
        val address = hostValidation.addresses.first()

        val startTime = System.currentTimeMillis()
        return suspendRunCatching {
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
            val assertionResults = assertionList.map { assertion ->
                evaluateTcpAssertion(assertion, connectMs, portOpen)
            }
            val allPassed = assertionResults.all { it.passed }

            SyntheticCheckResult(
                status = if (allPassed) "passed" else "failed",
                durationMs = durationMs,
                errorMessage = if (allPassed) "" else "UDP assertion failed",
                timings = timings,
                assertionResults = assertionResults,
                resolvedIp = address.hostAddress
            )
        }.getOrElse { e ->
            SyntheticCheckResult(
                status = "failed",
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = "UDP check failed: ${e.message}"
            )
        }
    }

    private suspend fun executeMultistepTest(test: SyntheticTestData): SyntheticCheckResult {
        val steps: List<SyntheticStep> = suspendRunCatching {
            test.steps?.let { Json.decodeFromString<List<SyntheticStep>>(it) } ?: emptyList()
        }.getOrElse { _ ->
            emptyList()
        }

        if (steps.isEmpty()) {
            return SyntheticCheckResult(status = "failed", durationMs = 0, errorMessage = "No steps configured")
        }

        val timeoutMs = test.timeoutSeconds * MILLIS_PER_SECOND_LONG
        val client = buildClient(timeoutMs)
        val variables = mutableMapOf<String, String>()
        val startTime = System.currentTimeMillis()

        return try {
            for (step in steps) {
                val stepOutcome = runSingleMultistepStep(step, client, variables, startTime)
                if (stepOutcome != null) return stepOutcome
            }

            SyntheticCheckResult(status = "passed", durationMs = System.currentTimeMillis() - startTime)
        } finally {
            client.close()
        }
    }

    /**
     * Runs one synthetic multistep step. Returns a terminal [SyntheticCheckResult] on failure, or null to continue.
     */
    private suspend fun runSingleMultistepStep(
        step: SyntheticStep,
        client: HttpClient,
        variables: MutableMap<String, String>,
        startTime: Long,
    ): SyntheticCheckResult? {
        val stepUrl = substituteVariables(step.url, variables)
        try {
            UrlValidator.validateExternalUrl(stepUrl)
        } catch (e: UrlValidator.SsrfException) {
            val durationMs = System.currentTimeMillis() - startTime
            return SyntheticCheckResult(
                status = "failed",
                durationMs = durationMs,
                errorMessage = "Step '${step.name}' blocked: ${e.message}"
            )
        }
        val stepBody = step.body?.let { substituteVariables(it, variables) }
        val stepHeaders = step.headers?.mapValues { (_, v) -> substituteVariables(v, variables) }

        val stepStart = System.currentTimeMillis()
        val response = suspendRunCatching {
            client.request(stepUrl) {
                method = resolveHttpMethod(step.method)
                stepHeaders?.forEach { (k, v) -> header(k, v) }
                stepBody?.let { b -> setBody(b) }
            }
        }.getOrElse { e ->
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

        val allPassed = step.assertions.all { assertion ->
            evaluateAssertion(assertion, statusCode, body, stepDurationMs, responseHeaders).passed
        }
        if (!allPassed) {
            val durationMs = System.currentTimeMillis() - startTime
            return SyntheticCheckResult(
                status = "failed",
                durationMs = durationMs,
                errorMessage = "Step '${step.name}' assertion failed"
            )
        }

        step.extractVariables.forEach { extraction ->
            val extracted = extractVariable(extraction.source, extraction.path, body, responseHeaders)
            if (extracted != null) {
                variables[extraction.name] = extracted
            }
        }
        return null
    }

    private fun validateSyntheticHostAddresses(hostname: String): SyntheticHostValidation =
        try {
            SyntheticHostValidation(addresses = UrlValidator.validateExternalHost(hostname))
        } catch (e: UrlValidator.SsrfException) {
            SyntheticHostValidation(
                failure = SyntheticCheckResult(
                    status = "failed",
                    durationMs = 0,
                    errorMessage = "Blocked: ${e.message}"
                )
            )
        }

    private fun validateSyntheticAddresses(addresses: Array<InetAddress>): SyntheticCheckResult? =
        try {
            addresses.forEach { UrlValidator.validateAddress(it) }
            null
        } catch (e: UrlValidator.SsrfException) {
            SyntheticCheckResult(
                status = "failed",
                durationMs = 0,
                errorMessage = "Blocked: ${e.message}"
            )
        }

    private fun evaluateAssertion(
        assertion: SyntheticAssertion,
        statusCode: Int,
        body: String,
        responseTimeMs: Long,
        headers: Map<String, String>
    ): AssertionResult {
        val (passed, actual) = suspendRunCatching {
            evaluateAssertionValue(assertion, statusCode, body, responseTimeMs, headers)
        }.getOrElse { false to "error" }
        return AssertionResult(
            label = describeAssertion(assertion),
            expected = assertion.value,
            actual = actual,
            passed = passed
        )
    }

    private fun evaluateAssertionValue(
        assertion: SyntheticAssertion,
        statusCode: Int,
        body: String,
        responseTimeMs: Long,
        headers: Map<String, String>
    ): Pair<Boolean, String> =
        when (assertion.type) {
            "status_code" -> evaluateStatusCodeAssertion(assertion, statusCode)
            "body_contains" -> evaluateBodyContainsAssertion(assertion, body)
            "body_json_path" -> evaluateJsonPathAssertion(assertion, body)
            "response_time" -> evaluateResponseTimeAssertion(assertion, responseTimeMs)
            "header" -> evaluateHeaderAssertion(assertion, headers)
            else -> unknownAssertion(assertion)
        }

    private fun evaluateStatusCodeAssertion(
        assertion: SyntheticAssertion,
        statusCode: Int
    ): Pair<Boolean, String> {
        val expected = assertion.value.toIntOrNull()
        val ok = expected != null &&
            compareValues(statusCode.toLong(), expected.toLong(), assertion.operator)
        return ok to statusCode.toString()
    }

    private fun evaluateBodyContainsAssertion(
        assertion: SyntheticAssertion,
        body: String
    ): Pair<Boolean, String> {
        val ok = body.contains(assertion.value)
        return ok to if (ok) "present" else "absent"
    }

    private fun evaluateJsonPathAssertion(
        assertion: SyntheticAssertion,
        body: String
    ): Pair<Boolean, String> {
        val jsonValue = extractJsonPath(body, assertion.target)
        val ok = jsonValue?.let {
            compareStringValue(it, assertion.value, assertion.operator)
        } ?: false
        return ok to (jsonValue ?: "<null>")
    }

    private fun evaluateResponseTimeAssertion(
        assertion: SyntheticAssertion,
        responseTimeMs: Long
    ): Pair<Boolean, String> {
        val threshold = assertion.value.toLongOrNull()
        val ok = threshold != null &&
            compareValues(responseTimeMs, threshold, assertion.operator)
        return ok to responseTimeMs.toString()
    }

    private fun evaluateHeaderAssertion(
        assertion: SyntheticAssertion,
        headers: Map<String, String>
    ): Pair<Boolean, String> {
        val headerValue = headers.entries
            .firstOrNull { (name, _) -> name.equals(assertion.target, ignoreCase = true) }
            ?.value
        val ok = headerValue?.let {
            compareStringValue(it, assertion.value, assertion.operator)
        } ?: false
        return ok to (headerValue ?: "<missing>")
    }

    private fun compareStringValue(actual: String, expected: String, operator: String): Boolean =
        when (operator) {
            "not_equals" -> actual != expected
            "contains" -> actual.contains(expected)
            else -> actual == expected
        }

    private fun unknownAssertion(assertion: SyntheticAssertion): Pair<Boolean, String> {
        logger.warn {
            "Unknown assertion type '${assertion.type}' " +
                "(assertion operator: ${assertion.operator}) - failing assertion"
        }
        return false to "unknown"
    }

    /** Human-readable assertion label for run-detail (mirrors the builder's phrasing). */
    private fun describeAssertion(a: SyntheticAssertion): String {
        val op = assertionOperatorLabels[a.operator] ?: a.operator
        val builder = assertionLabelBuilders[a.type]
        return builder?.invoke(a, op) ?: "${a.type} $op ${a.value}"
    }

    /** Mask sensitive request/response header values before capture. */
    private fun redactHeaders(headers: Map<String, String>): Map<String, String> {
        val sensitive = setOf(
            "authorization",
            "cookie",
            "set-cookie",
            "x-api-key",
            "proxy-authorization",
            "x-auth-token"
        )
        return headers.mapValues { (k, v) ->
            if (k.lowercase() in sensitive) maskSecret(v) else v
        }
    }

    private fun maskSecret(v: String): String =
        if (v.length <= SECRET_MASK_SHORT_LENGTH) {
            "••••"
        } else {
            "••••" + v.takeLast(SECRET_VISIBLE_SUFFIX_LENGTH)
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
        return suspendRunCatching {
            val json = Json.parseToJsonElement(body).jsonObject
            val segments = path.removePrefix("$.").split(".")
            var current = json
            for (i in 0 until segments.size - 1) {
                current = current[segments[i]]?.jsonObject ?: return null
            }
            current[segments.last()]?.jsonPrimitive?.content
        }.getOrElse { _ ->
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
        return suspendRunCatching {
            headersJson?.let { Json.decodeFromString<Map<String, String>>(it) } ?: emptyMap()
        }.getOrElse { _ ->
            emptyMap()
        }
    }

    private fun parseAssertions(assertionsJson: String): List<SyntheticAssertion> {
        return suspendRunCatching {
            Json.decodeFromString<List<SyntheticAssertion>>(assertionsJson)
        }.getOrElse { _ ->
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
    ): AssertionResult {
        val (passed, actual) = when (assertion.type) {
            "certificate_expiry_days" -> {
                val threshold = assertion.value.toLongOrNull()
                val ok = threshold != null &&
                    compareValues(expiryDays, threshold, assertion.operator)
                ok to "$expiryDays"
            }
            "certificate_valid" -> {
                val expected = assertion.value.toBooleanStrictOrNull()
                    ?: true
                val valid = suspendRunCatching {
                    cert.checkValidity()
                    true
                }.getOrElse { _ ->
                    false
                }
                (valid == expected) to valid.toString()
            }
            "certificate_issuer" -> {
                val issuer = cert.issuerX500Principal.name
                issuer.contains(assertion.value, ignoreCase = true) to issuer
            }
            else -> {
                logger.warn { "Unknown SSL assertion type: '${assertion.type}'" }
                false to "unknown"
            }
        }
        return AssertionResult(describeAssertion(assertion), assertion.value, actual, passed)
    }

    private fun evaluateDnsAssertion(
        assertion: SyntheticAssertion,
        resolvedIps: List<String>,
        resolutionTimeMs: Long
    ): AssertionResult {
        val (passed, actual) = when (assertion.type) {
            "resolved_ip" -> {
                val ok = when (assertion.operator) {
                    "contains" -> resolvedIps.any { it.contains(assertion.value) }
                    else -> resolvedIps.contains(assertion.value)
                }
                ok to resolvedIps.joinToString(", ")
            }
            "resolution_time" -> {
                val threshold = assertion.value.toLongOrNull()
                val ok = threshold != null &&
                    compareValues(resolutionTimeMs, threshold, assertion.operator)
                ok to "$resolutionTimeMs"
            }
            else -> {
                logger.warn { "Unknown DNS assertion type: '${assertion.type}'" }
                false to "unknown"
            }
        }
        return AssertionResult(describeAssertion(assertion), assertion.value, actual, passed)
    }

    private fun evaluateTcpAssertion(
        assertion: SyntheticAssertion,
        connectionTimeMs: Long,
        portOpen: Boolean
    ): AssertionResult {
        val (passed, actual) = when (assertion.type) {
            "connection_time" -> {
                val threshold = assertion.value.toLongOrNull()
                val ok = threshold != null &&
                    compareValues(connectionTimeMs, threshold, assertion.operator)
                ok to "$connectionTimeMs"
            }
            "port_open" -> {
                val expected = assertion.value.toBooleanStrictOrNull()
                    ?: true
                (portOpen == expected) to portOpen.toString()
            }
            else -> {
                logger.warn { "Unknown TCP assertion type: '${assertion.type}'" }
                false to "unknown"
            }
        }
        return AssertionResult(describeAssertion(assertion), assertion.value, actual, passed)
    }

    companion object {
        private const val NS_PER_MS = 1_000_000L
        private const val REQUEST_FAILED = "Request failed"
    }
}
