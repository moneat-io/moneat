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

package com.moneat.synthetics.services

import com.moneat.synthetics.models.SyntheticAssertion
import com.moneat.synthetics.models.SyntheticStep
import com.moneat.synthetics.models.SyntheticTestData
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

data class SyntheticCheckResult(
    val status: String, // "passed" or "failed"
    val durationMs: Long,
    val errorMessage: String = "",
    val timings: Map<String, Double> = emptyMap()
)

class SyntheticsCheckExecutor {

    suspend fun executeTest(test: SyntheticTestData): SyntheticCheckResult {
        return when (test.testType.lowercase()) {
            "multistep" -> executeMultistepTest(test)
            else -> executeApiTest(test)
        }
    }

    private suspend fun executeApiTest(test: SyntheticTestData): SyntheticCheckResult {
        val url = test.url ?: return SyntheticCheckResult(
            status = "failed",
            durationMs = 0,
            errorMessage = "No URL configured"
        )

        val timeoutMs = test.timeoutSeconds * 1000L
        val client = buildClient(timeoutMs)

        val startTime = System.currentTimeMillis()
        return try {
            val headersMap: Map<String, String> = parseHeaders(test.headers)
            val assertionList: List<SyntheticAssertion> = parseAssertions(test.assertions)

            val response = client.request(url) {
                method = resolveHttpMethod(test.method)
                headersMap.forEach { (k, v) -> header(k, v) }
                when (test.authMethod?.lowercase()) {
                    "basic" -> basicAuth(test.authUser ?: "", test.authPass ?: "")
                    "bearer" -> bearerAuth(test.authPass ?: "")
                }
                test.body?.let { b -> setBody(b) }
            }

            val durationMs = System.currentTimeMillis() - startTime
            val statusCode = response.status.value
            val body = response.bodyAsText()
            val responseHeaders = response.headers.entries().associate { (k, v) -> k to v.firstOrNull().orEmpty() }

            val allPassed = assertionList.all { assertion ->
                evaluateAssertion(assertion, statusCode, body, durationMs, responseHeaders)
            }

            if (allPassed) {
                SyntheticCheckResult(status = "passed", durationMs = durationMs)
            } else {
                SyntheticCheckResult(
                    status = "failed",
                    durationMs = durationMs,
                    errorMessage = "One or more assertions failed"
                )
            }
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startTime
            logger.warn { "API test failed for ${test.id}: ${e.message}" }
            SyntheticCheckResult(status = "failed", durationMs = durationMs, errorMessage = e.message ?: "Request failed")
        } finally {
            client.close()
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
                        "Unknown assertion type '${assertion.type}' (assertion operator: ${assertion.operator}) - failing assertion"
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

    private fun substituteVariables(input: String, variables: Map<String, String>): String {
        var result = input
        variables.forEach { (name, value) ->
            result = result.replace("{{$name}}", value)
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
}

