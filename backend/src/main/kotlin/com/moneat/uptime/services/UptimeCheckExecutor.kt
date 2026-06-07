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

package com.moneat.uptime.services

import com.jayway.jsonpath.JsonPath
import com.moneat.uptime.models.CheckResult
import com.moneat.uptime.models.UptimeMonitorData
import com.moneat.utils.UrlValidator
import com.moneat.utils.suspendRunCatching
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.basicAuth
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import mu.KotlinLogging
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.sql.DriverManager
import javax.naming.NamingException
import javax.naming.directory.InitialDirContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private val logger = KotlinLogging.logger {}

private data class HostTargetValidation(
    val addresses: List<InetAddress> = emptyList(),
    val failure: CheckResult? = null,
)

private data class JdbcTargetValidation(
    val connectionString: String,
    val failure: CheckResult? = null,
)

/**
 * Executor for uptime monitor checks.
 * Each monitor type has a specific check strategy.
 */
class UptimeCheckExecutor {

    companion object {
        private const val MILLIS_PER_SECOND = 1000
        private const val HTTP_OK = 200
        private const val DEFAULT_HTTPS_PORT = 443
    }

    private val httpClient =
        HttpClient(CIO) {
            install(HttpTimeout) {
                socketTimeoutMillis = 60_000
                connectTimeoutMillis = 30_000
                requestTimeoutMillis = 60_000
            }
            engine {
                requestTimeout = 60_000
            }
        }

    private val sslCertificateEvaluator = SslCertificateEvaluator()

    /**
     * Execute a check for the given monitor.
     */
    suspend fun executeCheck(monitor: UptimeMonitorData): CheckResult {
        return try {
            withTimeout(monitor.timeoutSeconds * MILLIS_PER_SECOND.toLong()) {
                when (monitor.type.lowercase()) {
                    "http" -> checkHttp(monitor)
                    "keyword" -> checkKeyword(monitor)
                    "json_query" -> checkJsonQuery(monitor)
                    "tcp" -> checkTcp(monitor)
                    "ping" -> checkPing(monitor)
                    "dns" -> checkDns(monitor)
                    "websocket" -> checkWebSocket(monitor)
                    "push" -> CheckResult(2, -1, 0, "Push monitors don't perform active checks")
                    "docker" -> checkDocker(monitor)
                    "database" -> checkDatabase(monitor)
                    "ssl" -> checkSsl(monitor)
                    else -> CheckResult(0, -1, 0, "Unknown monitor type: ${monitor.type}")
                }
            }
        } catch (e: TimeoutCancellationException) {
            logger.error(e) { "Check failed for monitor ${monitor.id}: ${e.message}" }
            CheckResult(0, -1, 0, "Check timeout or error: ${e.message}")
        }
    }

    /**
     * HTTP/HTTPS check
     */
    private suspend fun checkHttp(monitor: UptimeMonitorData): CheckResult {
        val url = monitor.url ?: return CheckResult(0, -1, 0, "No URL configured")

        try {
            UrlValidator.validateExternalUrl(url)
        } catch (e: UrlValidator.SsrfException) {
            return CheckResult(0, -1, 0, "Blocked: ${e.message}")
        }

        val startTime = System.currentTimeMillis()

        suspendRunCatching {
            val response =
                httpClient.request(url) {
                    method =
                        when (monitor.method.uppercase()) {
                            "GET" -> HttpMethod.Get
                            "POST" -> HttpMethod.Post
                            "PUT" -> HttpMethod.Put
                            "DELETE" -> HttpMethod.Delete
                            "HEAD" -> HttpMethod.Head
                            "OPTIONS" -> HttpMethod.Options
                            "PATCH" -> HttpMethod.Patch
                            else -> HttpMethod.Get
                        }

                    // Headers
                    monitor.headers?.let { headersJson ->
                        try {
                            val headers =
                                kotlinx.serialization.json.Json
                                    .decodeFromString<Map<String, String>>(headersJson)
                            headers.forEach { (key, value) ->
                                header(key, value)
                            }
                        } catch (_: SerializationException) {
                            // Ignored: malformed headers JSON should not block the check
                        }
                    }

                    // Authentication
                    when (monitor.authMethod?.lowercase()) {
                        "basic" -> {
                            val user = monitor.authUser ?: ""
                            val pass = monitor.authPass ?: ""
                            basicAuth(user, pass)
                        }

                        "bearer" -> {
                            val token = monitor.authPass ?: ""
                            bearerAuth(token)
                        }
                    }

                    // Body
                    monitor.body?.let {
                        setBody(it)
                    }

                    // TLS
                    if (monitor.ignoreTls) {
                        // Note: CIO engine doesn't easily support ignoring TLS
                        // This would need custom SSL configuration
                    }
                }

            val responseTime = (System.currentTimeMillis() - startTime).toInt()
            val statusCode = response.status.value

            // Check expected status codes
            val expectedCodes =
                monitor.expectedStatusCodes
                    ?.split(",")
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?: listOf(HTTP_OK)

            val isSuccess = statusCode in expectedCodes

            return CheckResult(
                status = if (isSuccess) 1 else 0,
                responseTimeMs = responseTime,
                statusCode = statusCode,
                message = if (isSuccess) "OK" else "Unexpected status code: $statusCode"
            )
        }.getOrElse { e ->
            val responseTime = (System.currentTimeMillis() - startTime).toInt()
            return CheckResult(0, responseTime, 0, "HTTP error: ${e.message}")
        }
    }

    /**
     * Keyword check - HTTP with keyword search
     */
    private suspend fun checkKeyword(monitor: UptimeMonitorData): CheckResult {
        val httpResult = checkHttp(monitor)
        if (httpResult.status == 0) return httpResult

        val keyword =
            monitor.keyword
                ?: return CheckResult(
                    0,
                    httpResult.responseTimeMs,
                    httpResult.statusCode,
                    "No keyword configured"
                )

        suspendRunCatching {
            val url = monitor.url ?: return CheckResult(0, -1, 0, "No URL configured")
            val response = httpClient.get(url)
            val body = response.bodyAsText()

            val containsKeyword = body.contains(keyword, ignoreCase = true)
            val shouldContain = !monitor.keywordInverse

            val success = containsKeyword == shouldContain

            val keywordMessage =
                if (success) {
                    "Keyword check passed"
                } else {
                    "Keyword '$keyword' " +
                        if (shouldContain) "not found" else "found (inverted check)"
                }
            return CheckResult(
                status = if (success) 1 else 0,
                responseTimeMs = httpResult.responseTimeMs,
                statusCode = httpResult.statusCode,
                message = keywordMessage
            )
        }.getOrElse { e ->
            return CheckResult(
                0,
                httpResult.responseTimeMs,
                httpResult.statusCode,
                "Keyword check error: ${e.message}"
            )
        }
    }

    /**
     * JSON Query check - HTTP with JSONPath validation
     */
    private suspend fun checkJsonQuery(monitor: UptimeMonitorData): CheckResult {
        val httpResult = checkHttp(monitor)
        if (httpResult.status == 0) return httpResult

        val jsonPath =
            monitor.jsonPath
                ?: return CheckResult(
                    0,
                    httpResult.responseTimeMs,
                    httpResult.statusCode,
                    "No JSON path configured"
                )

        suspendRunCatching {
            val url = monitor.url ?: return CheckResult(0, -1, 0, "No URL configured")
            val response = httpClient.get(url)
            val body = response.bodyAsText()

            val value = JsonPath.read<Any>(body, jsonPath)
            val expectedValue = monitor.jsonExpectedValue

            val success =
                if (expectedValue != null) {
                    value.toString() == expectedValue
                } else {
                    true // Just check that path exists
                }

            val jsonMessage =
                if (success) {
                    "JSON query passed"
                } else {
                    "JSON value mismatch: got '$value', expected '$expectedValue'"
                }
            return CheckResult(
                status = if (success) 1 else 0,
                responseTimeMs = httpResult.responseTimeMs,
                statusCode = httpResult.statusCode,
                message = jsonMessage
            )
        }.getOrElse { e ->
            return CheckResult(
                0,
                httpResult.responseTimeMs,
                httpResult.statusCode,
                "JSON query error: ${e.message}"
            )
        }
    }

    /**
     * TCP port check
     */
    private suspend fun checkTcp(monitor: UptimeMonitorData): CheckResult {
        val hostname = monitor.hostname ?: return CheckResult(0, -1, 0, "No hostname configured")
        val port = monitor.port ?: return CheckResult(0, -1, 0, "No port configured")
        val hostValidation = validateHostAddresses(hostname)
        hostValidation.failure?.let { return it }
        val address = hostValidation.addresses.first()

        val startTime = System.currentTimeMillis()

        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, port), monitor.timeoutSeconds * MILLIS_PER_SECOND)
                val responseTime = (System.currentTimeMillis() - startTime).toInt()
                CheckResult(1, responseTime, 0, "TCP connection successful")
            }
        } catch (e: IOException) {
            val responseTime = (System.currentTimeMillis() - startTime).toInt()
            CheckResult(0, responseTime, 0, "TCP connection failed: ${e.message}")
        }
    }

    /**
     * Ping check (ICMP or fallback to TCP)
     */
    private suspend fun checkPing(monitor: UptimeMonitorData): CheckResult {
        val hostname = monitor.hostname ?: return CheckResult(0, -1, 0, "No hostname configured")
        val hostValidation = validateHostAddresses(hostname)
        hostValidation.failure?.let { return it }
        val address = hostValidation.addresses.first()

        val startTime = System.currentTimeMillis()

        return try {
            val reachable = address.isReachable(monitor.timeoutSeconds * MILLIS_PER_SECOND)
            val responseTime = (System.currentTimeMillis() - startTime).toInt()

            if (reachable) {
                CheckResult(1, responseTime, 0, "Host is reachable", responseTime.toFloat())
            } else {
                CheckResult(0, responseTime, 0, "Host is unreachable")
            }
        } catch (e: IOException) {
            val responseTime = (System.currentTimeMillis() - startTime).toInt()
            CheckResult(0, responseTime, 0, "Ping failed: ${e.message}")
        }
    }

    /**
     * DNS check
     */
    private suspend fun checkDns(monitor: UptimeMonitorData): CheckResult {
        val hostname = monitor.hostname ?: return CheckResult(0, -1, 0, "No hostname configured")
        val recordType = monitor.dnsRecordType ?: "A"
        validateHostTarget(hostname)?.let { return it }
        monitor.dnsServer?.let { dnsServer ->
            validateHostTarget(dnsServer)?.let { return it }
        }

        val startTime = System.currentTimeMillis()

        return try {
            val env = hashMapOf<String, String>()
            env["java.naming.factory.initial"] = "com.sun.jndi.dns.DnsContextFactory"
            monitor.dnsServer?.let {
                env["java.naming.provider.url"] = "dns://$it"
            }

            val ctx = InitialDirContext(env.toProperties())
            val attrs = ctx.getAttributes(hostname, arrayOf(recordType))
            val attr = attrs.get(recordType)

            val responseTime = (System.currentTimeMillis() - startTime).toInt()

            if (attr != null && attr.size() > 0) {
                val value = attr.get(0).toString()
                val expectedValue = monitor.dnsExpectedValue

                val success = expectedValue == null || value == expectedValue

                val dnsMessage =
                    if (success) {
                        "DNS record found: $value"
                    } else {
                        "DNS value mismatch: got '$value', expected '$expectedValue'"
                    }
                CheckResult(
                    status = if (success) 1 else 0,
                    responseTimeMs = responseTime,
                    statusCode = 0,
                    message = dnsMessage
                )
            } else {
                CheckResult(0, responseTime, 0, "DNS record not found")
            }
        } catch (e: NamingException) {
            val responseTime = (System.currentTimeMillis() - startTime).toInt()
            CheckResult(0, responseTime, 0, "DNS lookup failed: ${e.message}")
        }
    }

    /**
     * WebSocket check
     */
    private suspend fun checkWebSocket(monitor: UptimeMonitorData): CheckResult {
        val url = monitor.url ?: return CheckResult(0, -1, 0, "No URL configured")

        val httpUrl = suspendRunCatching {
            val uri = java.net.URI(url)
            val httpScheme = when (uri.scheme?.lowercase()) {
                "ws" -> "http"
                "wss" -> "https"
                else -> uri.scheme
            }
            java.net.URI(
                httpScheme,
                uri.userInfo,
                uri.host,
                uri.port,
                uri.path,
                uri.query,
                uri.fragment
            ).toString()
        }.getOrElse { _ ->
            return CheckResult(0, -1, 0, "Invalid URL: $url")
        }
        try {
            UrlValidator.validateExternalUrl(httpUrl)
        } catch (e: UrlValidator.SsrfException) {
            return CheckResult(0, -1, 0, "Blocked: ${e.message}")
        }

        // Basic WebSocket connection test via HTTP upgrade
        return suspendRunCatching {
            val startTime = System.currentTimeMillis()

            val response = httpClient.get(httpUrl)

            val responseTime = (System.currentTimeMillis() - startTime).toInt()

            CheckResult(
                status = if (response.status.value in 100..499) 1 else 0,
                responseTimeMs = responseTime,
                statusCode = response.status.value,
                message = "WebSocket endpoint reachable"
            )
        }.getOrElse { e ->
            CheckResult(0, -1, 0, "WebSocket check failed: ${e.message}")
        }
    }

    /**
     * Docker container check
     */
    private suspend fun checkDocker(monitor: UptimeMonitorData): CheckResult {
        val containerName = monitor.dockerContainerName ?: return CheckResult(0, -1, 0, "No container name configured")
        val dockerHost = monitor.dockerHost ?: "unix:///var/run/docker.sock"

        // Docker API check
        return suspendRunCatching {
            if (dockerHost.startsWith("http")) {
                checkHttpDockerContainer(containerName, dockerHost)
            } else {
                // Unix socket not easily supported here
                CheckResult(0, -1, 0, "Docker Unix socket not supported. Use HTTP Docker API.")
            }
        }.getOrElse { e ->
            CheckResult(0, -1, 0, "Docker check failed: ${e.message}")
        }
    }

    private suspend fun checkHttpDockerContainer(containerName: String, dockerHost: String): CheckResult {
        val dockerUrl = "$dockerHost/containers/$containerName/json"
        try {
            UrlValidator.validateExternalUrl(dockerUrl)
        } catch (e: UrlValidator.SsrfException) {
            return CheckResult(0, -1, 0, "Blocked: ${e.message}")
        }
        val startTime = System.currentTimeMillis()
        val response = httpClient.get(dockerUrl)
        val responseTime = (System.currentTimeMillis() - startTime).toInt()

        return if (response.status.value == HTTP_OK) {
            val body = response.bodyAsText()
            val running = body.contains("\"Running\":true")
            CheckResult(
                status = if (running) 1 else 0,
                responseTimeMs = responseTime,
                statusCode = response.status.value,
                message = if (running) "Container is running" else "Container is not running"
            )
        } else {
            CheckResult(0, responseTime, response.status.value, "Container not found or Docker API error")
        }
    }

    /**
     * Database connection check
     */
    private suspend fun checkDatabase(monitor: UptimeMonitorData): CheckResult {
        val connectionString =
            monitor.dbConnectionString
                ?: return CheckResult(0, -1, 0, "No connection string configured")
        val jdbcValidation = validateJdbcTarget(connectionString)
        jdbcValidation.failure?.let { return it }

        val startTime = System.currentTimeMillis()

        return suspendRunCatching {
            DriverManager.getConnection(jdbcValidation.connectionString).use { conn ->
                val responseTime = (System.currentTimeMillis() - startTime).toInt()

                // Optionally run a read-only query (SELECT only to prevent destructive statements)
                monitor.dbQuery?.let { query ->
                    val trimmed = query.trimStart()
                    require(trimmed.startsWith("SELECT", ignoreCase = true)) {
                        "Only SELECT queries are allowed for database health checks"
                    }
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery(query).use { rs ->
                            rs.next() // Execute query
                        }
                    }
                }

                CheckResult(1, responseTime, 0, "Database connection successful")
            }
        }.getOrElse { e ->
            val responseTime = (System.currentTimeMillis() - startTime).toInt()
            CheckResult(0, responseTime, 0, "Database check failed: ${e.message}")
        }
    }

    /**
     * SSL certificate check
     */
    private suspend fun checkSsl(monitor: UptimeMonitorData): CheckResult {
        val hostname = monitor.hostname ?: return CheckResult(0, -1, 0, "No hostname configured")
        val port = monitor.port ?: DEFAULT_HTTPS_PORT
        val hostValidation = validateHostAddresses(hostname)
        hostValidation.failure?.let { return it }
        val address = hostValidation.addresses.first()

        val startTime = System.currentTimeMillis()
        val timeoutMillis = monitor.timeoutSeconds * MILLIS_PER_SECOND

        return try {
            connectSslSocket(hostname, port, address, timeoutMillis).use { socket ->
                socket.soTimeout = timeoutMillis
                socket.startHandshake()
                val responseTime = (System.currentTimeMillis() - startTime).toInt()
                sslCertificateEvaluator.evaluateSocket(socket, responseTime, monitor.sslExpiryWarnDays.toLong())
            }
        } catch (e: IOException) {
            val responseTime = (System.currentTimeMillis() - startTime).toInt()
            CheckResult(0, responseTime, 0, "SSL check failed: ${e.message}")
        }
    }

    private fun connectSslSocket(
        hostname: String,
        port: Int,
        address: InetAddress,
        timeoutMillis: Int,
    ): SSLSocket {
        val rawSocket = Socket()
        return try {
            rawSocket.connect(InetSocketAddress(address, port), timeoutMillis)
            rawSocket.soTimeout = timeoutMillis
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            factory.createSocket(rawSocket, hostname, port, true) as SSLSocket
        } catch (e: IOException) {
            rawSocket.close()
            throw e
        } catch (e: RuntimeException) {
            rawSocket.close()
            throw e
        }
    }

    private fun java.util.Hashtable<String, String>.toProperties(): java.util.Properties {
        val props = java.util.Properties()
        this.forEach { (k, v) -> props[k] = v }
        return props
    }

    private fun validateHostTarget(hostname: String): CheckResult? =
        validateHostAddresses(hostname).failure

    private fun validateHostAddresses(hostname: String): HostTargetValidation =
        try {
            HostTargetValidation(addresses = UrlValidator.validateExternalHost(hostname))
        } catch (e: UrlValidator.SsrfException) {
            HostTargetValidation(failure = CheckResult(0, -1, 0, "Blocked: ${e.message}"))
        }

    private fun validateJdbcTarget(connectionString: String): JdbcTargetValidation =
        try {
            JdbcTargetValidation(UrlValidator.validatedExternalJdbcUrl(connectionString))
        } catch (e: UrlValidator.SsrfException) {
            JdbcTargetValidation(
                connectionString = connectionString,
                failure = CheckResult(0, -1, 0, "Blocked: ${e.message}"),
            )
        }
}
