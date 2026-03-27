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
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.naming.directory.InitialDirContext
import javax.net.ssl.HttpsURLConnection

private val logger = KotlinLogging.logger {}

/**
 * Executor for uptime monitor checks.
 * Each monitor type has a specific check strategy.
 */
class UptimeCheckExecutor {

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

    /**
     * Execute a check for the given monitor.
     */
    suspend fun executeCheck(monitor: UptimeMonitorData): CheckResult {
        return try {
            withTimeout(monitor.timeoutSeconds * 1000L) {
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
        } catch (e: Exception) {
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

        try {
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
                        } catch (_: Exception) {
                            // intentionally ignored
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
                    ?: listOf(200)

            val isSuccess = statusCode in expectedCodes

            return CheckResult(
                status = if (isSuccess) 1 else 0,
                responseTimeMs = responseTime,
                statusCode = statusCode,
                message = if (isSuccess) "OK" else "Unexpected status code: $statusCode"
            )
        } catch (e: Exception) {
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

        val keyword = monitor.keyword ?: return CheckResult(0, httpResult.responseTimeMs, httpResult.statusCode, "No keyword configured")

        try {
            val url = monitor.url ?: return CheckResult(0, -1, 0, "No URL configured")
            val response = httpClient.get(url)
            val body = response.bodyAsText()

            val containsKeyword = body.contains(keyword, ignoreCase = true)
            val shouldContain = !monitor.keywordInverse

            val success = containsKeyword == shouldContain

            return CheckResult(
                status = if (success) 1 else 0,
                responseTimeMs = httpResult.responseTimeMs,
                statusCode = httpResult.statusCode,
                message = if (success) "Keyword check passed" else "Keyword '$keyword' ${if (shouldContain) "not found" else "found (inverted check)"}"
            )
        } catch (e: Exception) {
            return CheckResult(0, httpResult.responseTimeMs, httpResult.statusCode, "Keyword check error: ${e.message}")
        }
    }

    /**
     * JSON Query check - HTTP with JSONPath validation
     */
    private suspend fun checkJsonQuery(monitor: UptimeMonitorData): CheckResult {
        val httpResult = checkHttp(monitor)
        if (httpResult.status == 0) return httpResult

        val jsonPath = monitor.jsonPath ?: return CheckResult(0, httpResult.responseTimeMs, httpResult.statusCode, "No JSON path configured")

        try {
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

            return CheckResult(
                status = if (success) 1 else 0,
                responseTimeMs = httpResult.responseTimeMs,
                statusCode = httpResult.statusCode,
                message = if (success) "JSON query passed" else "JSON value mismatch: got '$value', expected '$expectedValue'"
            )
        } catch (e: Exception) {
            return CheckResult(0, httpResult.responseTimeMs, httpResult.statusCode, "JSON query error: ${e.message}")
        }
    }

    /**
     * TCP port check
     */
    private suspend fun checkTcp(monitor: UptimeMonitorData): CheckResult {
        val hostname = monitor.hostname ?: return CheckResult(0, -1, 0, "No hostname configured")
        val port = monitor.port ?: return CheckResult(0, -1, 0, "No port configured")

        val startTime = System.currentTimeMillis()

        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(hostname, port), monitor.timeoutSeconds * 1000)
                val responseTime = (System.currentTimeMillis() - startTime).toInt()
                CheckResult(1, responseTime, 0, "TCP connection successful")
            }
        } catch (e: Exception) {
            val responseTime = (System.currentTimeMillis() - startTime).toInt()
            CheckResult(0, responseTime, 0, "TCP connection failed: ${e.message}")
        }
    }

    /**
     * Ping check (ICMP or fallback to TCP)
     */
    private suspend fun checkPing(monitor: UptimeMonitorData): CheckResult {
        val hostname = monitor.hostname ?: return CheckResult(0, -1, 0, "No hostname configured")

        val startTime = System.currentTimeMillis()

        return try {
            val address = InetAddress.getByName(hostname)
            val reachable = address.isReachable(monitor.timeoutSeconds * 1000)
            val responseTime = (System.currentTimeMillis() - startTime).toInt()

            if (reachable) {
                CheckResult(1, responseTime, 0, "Host is reachable", responseTime.toFloat())
            } else {
                CheckResult(0, responseTime, 0, "Host is unreachable")
            }
        } catch (e: Exception) {
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

                CheckResult(
                    status = if (success) 1 else 0,
                    responseTimeMs = responseTime,
                    statusCode = 0,
                    message = if (success) "DNS record found: $value" else "DNS value mismatch: got '$value', expected '$expectedValue'"
                )
            } else {
                CheckResult(0, responseTime, 0, "DNS record not found")
            }
        } catch (e: Exception) {
            val responseTime = (System.currentTimeMillis() - startTime).toInt()
            CheckResult(0, responseTime, 0, "DNS lookup failed: ${e.message}")
        }
    }

    /**
     * WebSocket check
     */
    private suspend fun checkWebSocket(monitor: UptimeMonitorData): CheckResult {
        val url = monitor.url ?: return CheckResult(0, -1, 0, "No URL configured")

        val httpUrl = try {
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
        } catch (_: Exception) {
            return CheckResult(0, -1, 0, "Invalid URL: $url")
        }
        try {
            UrlValidator.validateExternalUrl(httpUrl)
        } catch (e: UrlValidator.SsrfException) {
            return CheckResult(0, -1, 0, "Blocked: ${e.message}")
        }

        // Basic WebSocket connection test via HTTP upgrade
        return try {
            val startTime = System.currentTimeMillis()

            val response = httpClient.get(httpUrl)

            val responseTime = (System.currentTimeMillis() - startTime).toInt()

            CheckResult(
                status = if (response.status.value in 100..499) 1 else 0,
                responseTimeMs = responseTime,
                statusCode = response.status.value,
                message = "WebSocket endpoint reachable"
            )
        } catch (e: Exception) {
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
        return try {
            val startTime = System.currentTimeMillis()

            // For HTTP-based Docker API
            if (dockerHost.startsWith("http")) {
                val dockerUrl = "$dockerHost/containers/$containerName/json"
                try {
                    UrlValidator.validateExternalUrl(dockerUrl)
                } catch (e: UrlValidator.SsrfException) {
                    return CheckResult(0, -1, 0, "Blocked: ${e.message}")
                }
                val response = httpClient.get(dockerUrl)
                val responseTime = (System.currentTimeMillis() - startTime).toInt()

                if (response.status.value == 200) {
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
            } else {
                // Unix socket not easily supported here
                CheckResult(0, -1, 0, "Docker Unix socket not supported. Use HTTP Docker API.")
            }
        } catch (e: Exception) {
            CheckResult(0, -1, 0, "Docker check failed: ${e.message}")
        }
    }

    /**
     * Database connection check
     */
    private suspend fun checkDatabase(monitor: UptimeMonitorData): CheckResult {
        val connectionString = monitor.dbConnectionString ?: return CheckResult(0, -1, 0, "No connection string configured")

        val startTime = System.currentTimeMillis()

        return try {
            DriverManager.getConnection(connectionString).use { conn ->
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
        } catch (e: Exception) {
            val responseTime = (System.currentTimeMillis() - startTime).toInt()
            CheckResult(0, responseTime, 0, "Database check failed: ${e.message}")
        }
    }

    /**
     * SSL certificate check
     */
    private suspend fun checkSsl(monitor: UptimeMonitorData): CheckResult {
        val hostname = monitor.hostname ?: return CheckResult(0, -1, 0, "No hostname configured")
        val port = monitor.port ?: 443

        val startTime = System.currentTimeMillis()

        return try {
            val url = java.net.URI("https://$hostname:$port").toURL()
            val conn = url.openConnection() as HttpsURLConnection
            conn.connectTimeout = monitor.timeoutSeconds * 1000
            conn.connect()

            val responseTime = (System.currentTimeMillis() - startTime).toInt()

            val certs = conn.serverCertificates
            if (certs.isNotEmpty() && certs[0] is X509Certificate) {
                val cert = certs[0] as X509Certificate
                val expiryDate = cert.notAfter.toInstant()
                val now = Instant.now()
                val daysUntilExpiry = ChronoUnit.DAYS.between(now, expiryDate)

                val warnDays = monitor.sslExpiryWarnDays.toLong()

                if (daysUntilExpiry < 0) {
                    CheckResult(0, responseTime, 0, "SSL certificate expired ${-daysUntilExpiry} days ago")
                } else if (daysUntilExpiry < warnDays) {
                    CheckResult(
                        0,
                        responseTime,
                        0,
                        "SSL certificate expires in $daysUntilExpiry days (warning threshold: $warnDays)"
                    )
                } else {
                    CheckResult(1, responseTime, 0, "SSL certificate valid (expires in $daysUntilExpiry days)")
                }
            } else {
                CheckResult(0, responseTime, 0, "No SSL certificate found")
            }
        } catch (e: Exception) {
            val responseTime = (System.currentTimeMillis() - startTime).toInt()
            CheckResult(0, responseTime, 0, "SSL check failed: ${e.message}")
        }
    }

    private fun java.util.Hashtable<String, String>.toProperties(): java.util.Properties {
        val props = java.util.Properties()
        this.forEach { (k, v) -> props[k] = v }
        return props
    }
}
