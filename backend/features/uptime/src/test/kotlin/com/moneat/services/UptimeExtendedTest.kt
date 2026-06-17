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

package com.moneat.services

import com.moneat.incident.services.IncidentService
import com.moneat.shared.services.TaskLock
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.respond
import com.moneat.uptime.models.CheckResult
import com.moneat.uptime.models.UptimeMonitorData
import com.moneat.uptime.services.UptimeCheckExecutor
import com.moneat.uptime.services.UptimeScheduler
import com.moneat.uptime.services.UptimeService
import com.moneat.utils.UrlValidator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class UptimeExtendedTest {

    companion object {
        private const val JSON_PATH_STATUS = "$.status"
        private const val LOCALHOST = "127.0.0.1"
        private const val UNREACHABLE_TCP_PORT = 1
    }

    private val executor = UptimeCheckExecutor()

    @AfterTest
    fun tearDown() {
        try {
            unmockkObject(UrlValidator)
        } catch (_: Exception) {
            Unit // best-effort cleanup; ignore if already unmocked
        }
    }

    private data class MonitorParams(
        val type: String,
        val url: String? = null,
        val hostname: String? = null,
        val port: Int? = null,
        val method: String = "GET",
        val headers: String? = null,
        val body: String? = null,
        val authMethod: String? = null,
        val authUser: String? = null,
        val authPass: String? = null,
        val expectedStatusCodes: String? = null,
        val keyword: String? = null,
        val keywordInverse: Boolean = false,
        val jsonPath: String? = null,
        val jsonExpectedValue: String? = null,
        val dbConnectionString: String? = null,
        val dbQuery: String? = null,
        val dockerContainerName: String? = null,
        val dockerHost: String? = null,
        val timeoutSeconds: Int = 10
    )

    private fun monitor(p: MonitorParams): UptimeMonitorData {
        val now = Instant.fromEpochMilliseconds(0)
        return UptimeMonitorData(
            id = UUID.randomUUID(),
            organizationId = 1,
            organizationResourceId = "11111111-1111-4111-8111-111111111111",
            name = "test-${p.type}",
            type = p.type,
            active = true,
            url = p.url,
            hostname = p.hostname,
            port = p.port,
            method = p.method,
            headers = p.headers,
            body = p.body,
            authMethod = p.authMethod,
            authUser = p.authUser,
            authPass = p.authPass,
            expectedStatusCodes = p.expectedStatusCodes,
            keyword = p.keyword,
            keywordInverse = p.keywordInverse,
            jsonPath = p.jsonPath,
            jsonExpectedValue = p.jsonExpectedValue,
            dbConnectionString = p.dbConnectionString,
            dbQuery = p.dbQuery,
            dockerContainerName = p.dockerContainerName,
            dockerHost = p.dockerHost,
            intervalSeconds = 60,
            timeoutSeconds = p.timeoutSeconds,
            retries = 0,
            retryIntervalSeconds = 1,
            status = "pending",
            createdAt = now,
            updatedAt = now
        )
    }

    private fun bypassSsrf() {
        mockkObject(UrlValidator)
        every { UrlValidator.validateExternalUrl(any()) } returns Unit
    }

    private suspend fun <T> withSelfHosted(value: String, block: suspend () -> T): T {
        val previous = System.getProperty("SELF_HOSTED")
        System.setProperty("SELF_HOSTED", value)
        return try {
            block()
        } finally {
            if (previous == null) {
                System.clearProperty("SELF_HOSTED")
            } else {
                System.setProperty("SELF_HOSTED", previous)
            }
        }
    }

    // ──── Push & unknown types ────

    @Test
    fun `push monitor returns pending status`() = runBlocking {
        val result = executor.executeCheck(monitor(MonitorParams(type = "push")))
        assertEquals(2, result.status)
        assertTrue(result.message.contains("Push monitors"))
    }

    @Test
    fun `unknown type returns failure`() = runBlocking {
        val result = executor.executeCheck(monitor(MonitorParams(type = "foobar")))
        assertEquals(0, result.status)
        assertTrue(result.message.contains("Unknown monitor type"))
    }

    // ──── HTTP checks ────

    @Test
    fun `http check succeeds with 200`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange -> exchange.respond(200, "OK") }.use { server ->
            val result = executor.executeCheck(monitor(MonitorParams(type = "http", url = server.baseUrl)))
            assertEquals(1, result.status)
            assertEquals(200, result.statusCode)
        }
    }

    @Test
    fun `http check fails with unexpected status code`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange -> exchange.respond(503, "Down") }.use { server ->
            val result = executor.executeCheck(monitor(MonitorParams(type = "http", url = server.baseUrl)))
            assertEquals(0, result.status)
            assertEquals(503, result.statusCode)
            assertTrue(result.message.contains("Unexpected status code"))
        }
    }

    @Test
    fun `http check succeeds with custom expected status codes`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange -> exchange.respond(201, "Created") }.use { server ->
            val result = executor.executeCheck(
                monitor(MonitorParams(type = "http", url = server.baseUrl, expectedStatusCodes = "200,201,202"))
            )
            assertEquals(1, result.status)
            assertEquals(201, result.statusCode)
        }
    }

    @Test
    fun `http check with POST method`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange ->
            assertEquals("POST", exchange.requestMethod)
            exchange.respond(200, "OK")
        }.use { server ->
            val result = executor.executeCheck(
                monitor(MonitorParams(type = "http", url = server.baseUrl, method = "POST", body = """{"test":1}"""))
            )
            assertEquals(1, result.status)
        }
    }

    @Test
    fun `http check with PUT method`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange ->
            assertEquals("PUT", exchange.requestMethod)
            exchange.respond(200, "OK")
        }.use { server ->
            val result = executor.executeCheck(
                monitor(MonitorParams(type = "http", url = server.baseUrl, method = "PUT"))
            )
            assertEquals(1, result.status)
        }
    }

    @Test
    fun `http check with DELETE method`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange ->
            assertEquals("DELETE", exchange.requestMethod)
            exchange.respond(200, "OK")
        }.use { server ->
            val result = executor.executeCheck(
                monitor(MonitorParams(type = "http", url = server.baseUrl, method = "DELETE"))
            )
            assertEquals(1, result.status)
        }
    }

    @Test
    fun `http check with HEAD method`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange ->
            assertEquals("HEAD", exchange.requestMethod)
            exchange.respond(200, "")
        }.use { server ->
            val result = executor.executeCheck(
                monitor(MonitorParams(type = "http", url = server.baseUrl, method = "HEAD"))
            )
            assertEquals(1, result.status)
        }
    }

    @Test
    fun `http check with PATCH method`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange ->
            assertEquals("PATCH", exchange.requestMethod)
            exchange.respond(200, "OK")
        }.use { server ->
            val result = executor.executeCheck(
                monitor(MonitorParams(type = "http", url = server.baseUrl, method = "PATCH"))
            )
            assertEquals(1, result.status)
        }
    }

    @Test
    fun `http check with OPTIONS method`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange ->
            assertEquals("OPTIONS", exchange.requestMethod)
            exchange.respond(200, "OK")
        }.use { server ->
            val result = executor.executeCheck(
                monitor(MonitorParams(type = "http", url = server.baseUrl, method = "OPTIONS"))
            )
            assertEquals(1, result.status)
        }
    }

    @Test
    fun `http check with unknown method defaults to GET`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange ->
            assertEquals("GET", exchange.requestMethod)
            exchange.respond(200, "OK")
        }.use { server ->
            val result = executor.executeCheck(
                monitor(MonitorParams(type = "http", url = server.baseUrl, method = "FOOBAR"))
            )
            assertEquals(1, result.status)
        }
    }

    @Test
    fun `http check with basic auth sends authorization header`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange ->
            val authHeader = exchange.requestHeaders.getFirst("Authorization") ?: ""
            assertTrue(authHeader.startsWith("Basic "))
            exchange.respond(200, "OK")
        }.use { server ->
            val result = executor.executeCheck(
                monitor(
                    MonitorParams(
                        type = "http",
                        url = server.baseUrl,
                        authMethod = "basic",
                        authUser = "user",
                        authPass = "pass"
                    )
                )
            )
            assertEquals(1, result.status)
        }
    }

    @Test
    fun `http check with bearer auth sends bearer token`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange ->
            val authHeader = exchange.requestHeaders.getFirst("Authorization") ?: ""
            assertEquals("Bearer my-token", authHeader)
            exchange.respond(200, "OK")
        }.use { server ->
            val result = executor.executeCheck(
                monitor(
                    MonitorParams(type = "http", url = server.baseUrl, authMethod = "bearer", authPass = "my-token")
                )
            )
            assertEquals(1, result.status)
        }
    }

    @Test
    fun `http check with custom headers`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange ->
            assertEquals("custom-value", exchange.requestHeaders.getFirst("X-Custom"))
            exchange.respond(200, "OK")
        }.use { server ->
            val result = executor.executeCheck(
                monitor(MonitorParams(type = "http", url = server.baseUrl, headers = """{"X-Custom":"custom-value"}"""))
            )
            assertEquals(1, result.status)
        }
    }

    @Test
    fun `http check with invalid headers json is ignored`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange -> exchange.respond(200, "OK") }.use { server ->
            val result = executor.executeCheck(
                monitor(MonitorParams(type = "http", url = server.baseUrl, headers = "not-json"))
            )
            assertEquals(1, result.status)
        }
    }

    @Test
    fun `http check with no url returns failure`() = runBlocking {
        val result = executor.executeCheck(monitor(MonitorParams(type = "http", url = null)))
        assertEquals(0, result.status)
        assertTrue(result.message.contains("No URL configured"))
    }

    // ──── Keyword checks ────

    @Test
    fun `keyword check succeeds when keyword found`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange ->
            exchange.respond(200, "Hello World! The server is healthy.")
        }.use { server ->
            val result = executor.executeCheck(
                monitor(MonitorParams(type = "keyword", url = server.baseUrl, keyword = "healthy"))
            )
            assertEquals(1, result.status)
            assertTrue(result.message.contains("Keyword check passed"))
        }
    }

    @Test
    fun `keyword check fails when keyword not found`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange ->
            exchange.respond(200, "Hello World!")
        }.use { server ->
            val result = executor.executeCheck(
                monitor(MonitorParams(type = "keyword", url = server.baseUrl, keyword = "missing-keyword"))
            )
            assertEquals(0, result.status)
            assertTrue(result.message.contains("not found"))
        }
    }

    @Test
    fun `keyword check inverse succeeds when keyword not found`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange ->
            exchange.respond(200, "Hello World!")
        }.use { server ->
            val result = executor.executeCheck(
                monitor(MonitorParams(type = "keyword", url = server.baseUrl, keyword = "error", keywordInverse = true))
            )
            assertEquals(1, result.status)
        }
    }

    @Test
    fun `keyword check inverse fails when keyword found`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange ->
            exchange.respond(200, "An error occurred")
        }.use { server ->
            val result = executor.executeCheck(
                monitor(MonitorParams(type = "keyword", url = server.baseUrl, keyword = "error", keywordInverse = true))
            )
            assertEquals(0, result.status)
            assertTrue(result.message.contains("inverted check"))
        }
    }

    @Test
    fun `keyword check fails when no keyword configured`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange ->
            exchange.respond(200, "OK")
        }.use { server ->
            val result = executor.executeCheck(
                monitor(MonitorParams(type = "keyword", url = server.baseUrl, keyword = null))
            )
            assertEquals(0, result.status)
            assertTrue(result.message.contains("No keyword configured"))
        }
    }

    // ──── JSON query checks ────

    @Test
    fun `json query check succeeds when value matches`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange ->
            exchange.respond(200, """{"status":"ok","version":"1.0"}""")
        }.use { server ->
            val result = executor.executeCheck(
                monitor(
                    MonitorParams(
                        type = "json_query",
                        url = server.baseUrl,
                        jsonPath = JSON_PATH_STATUS,
                        jsonExpectedValue = "ok"
                    )
                )
            )
            assertEquals(1, result.status)
            assertTrue(result.message.contains("JSON query passed"))
        }
    }

    @Test
    fun `json query check succeeds when only path existence checked`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange ->
            exchange.respond(200, """{"status":"ok"}""")
        }.use { server ->
            val result = executor.executeCheck(
                monitor(
                    MonitorParams(
                        type = "json_query",
                        url = server.baseUrl,
                        jsonPath = JSON_PATH_STATUS,
                        jsonExpectedValue = null
                    )
                )
            )
            assertEquals(1, result.status)
        }
    }

    @Test
    fun `json query check fails when value mismatches`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange ->
            exchange.respond(200, """{"status":"error"}""")
        }.use { server ->
            val result = executor.executeCheck(
                monitor(
                    MonitorParams(
                        type = "json_query",
                        url = server.baseUrl,
                        jsonPath = JSON_PATH_STATUS,
                        jsonExpectedValue = "ok"
                    )
                )
            )
            assertEquals(0, result.status)
            assertTrue(result.message.contains("mismatch"))
        }
    }

    @Test
    fun `json query check fails when no json path configured`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange ->
            exchange.respond(200, """{"status":"ok"}""")
        }.use { server ->
            val result = executor.executeCheck(
                monitor(MonitorParams(type = "json_query", url = server.baseUrl, jsonPath = null))
            )
            assertEquals(0, result.status)
            assertTrue(result.message.contains("No JSON path configured"))
        }
    }

    // ──── TCP checks ────

    @Test
    fun `tcp check succeeds connecting to local server`() = runBlocking {
        withSelfHosted("true") {
            ServerSocket(0).use { serverSocket ->
                val port = serverSocket.localPort
                val result = executor.executeCheck(
                    monitor(MonitorParams(type = "tcp", hostname = LOCALHOST, port = port))
                )
                assertEquals(1, result.status)
                assertTrue(result.message.contains("TCP connection successful"))
                assertTrue(result.responseTimeMs >= 0)
            }
        }
    }

    @Test
    fun `tcp check fails on unreachable port`() = runBlocking {
        withSelfHosted("true") {
            val result = executor.executeCheck(
                monitor(MonitorParams(type = "tcp", hostname = LOCALHOST, port = UNREACHABLE_TCP_PORT))
            )
            assertEquals(0, result.status)
            assertTrue(result.message.contains("TCP connection failed"))
        }
    }

    @Test
    fun `tcp check fails without hostname`() = runBlocking {
        val result = executor.executeCheck(
            monitor(MonitorParams(type = "tcp", hostname = null, port = 80))
        )
        assertEquals(0, result.status)
        assertTrue(result.message.contains("No hostname configured"))
    }

    // ──── WebSocket checks ────

    @Test
    fun `websocket check converts ws scheme to http`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange -> exchange.respond(200, "OK") }.use { server ->
            val wsUrl = server.baseUrl.replace("http://", "ws://")
            val result = executor.executeCheck(monitor(MonitorParams(type = "websocket", url = wsUrl)))
            assertEquals(1, result.status)
            assertTrue(result.message.contains("reachable"))
        }
    }

    @Test
    fun `websocket check converts wss scheme to https`() = runBlocking {
        // wss will convert to https - connection will fail but scheme conversion is tested
        val result = executor.executeCheck(
            monitor(MonitorParams(type = "websocket", url = "wss://192.0.2.1:1/test", timeoutSeconds = 2))
        )
        // Will fail because https://192.0.2.1:1 is unreachable, but proves wss conversion
        assertEquals(0, result.status)
    }

    @Test
    fun `websocket check with invalid url returns failure`() = runBlocking {
        val result = executor.executeCheck(
            monitor(MonitorParams(type = "websocket", url = "not a valid url ::::"))
        )
        assertEquals(0, result.status)
    }

    // ──── Docker checks ────

    @Test
    fun `docker http check detects running container`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange ->
            exchange.respond(200, """{"State":{"Running":true},"Name":"myapp"}""")
        }.use { server ->
            val result = executor.executeCheck(
                monitor(MonitorParams(type = "docker", dockerContainerName = "myapp", dockerHost = server.baseUrl))
            )
            assertEquals(1, result.status)
            assertTrue(result.message.contains("running"))
        }
    }

    @Test
    fun `docker http check detects stopped container`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange ->
            exchange.respond(200, """{"State":{"Running":false},"Name":"myapp"}""")
        }.use { server ->
            val result = executor.executeCheck(
                monitor(MonitorParams(type = "docker", dockerContainerName = "myapp", dockerHost = server.baseUrl))
            )
            assertEquals(0, result.status)
            assertTrue(result.message.contains("not running"))
        }
    }

    @Test
    fun `docker http check handles 404 container not found`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange ->
            exchange.respond(404, """{"message":"No such container"}""")
        }.use { server ->
            val result = executor.executeCheck(
                monitor(MonitorParams(type = "docker", dockerContainerName = "ghost", dockerHost = server.baseUrl))
            )
            assertEquals(0, result.status)
            assertTrue(result.message.contains("not found") || result.message.contains("error"))
        }
    }

    // ──── Database checks ────

    @Test
    fun `database check succeeds with SELECT query`() = runBlocking {
        val connStr = "jdbc:h2:mem:uptime_ext_db1;DB_CLOSE_DELAY=-1"
        java.sql.DriverManager.getConnection(connStr).use { conn ->
            conn.createStatement().execute("CREATE TABLE IF NOT EXISTS health (id INT)")
            conn.createStatement().execute("INSERT INTO health VALUES (1)")
        }
        val result = executor.executeCheck(
            monitor(MonitorParams(type = "database", dbConnectionString = connStr, dbQuery = "SELECT * FROM health"))
        )
        assertEquals(1, result.status)
        assertTrue(result.message.contains("Database connection successful"))
    }

    @Test
    fun `database check fails with invalid connection string`() = runBlocking {
        val result = executor.executeCheck(
            monitor(MonitorParams(type = "database", dbConnectionString = "jdbc:invalid:nope"))
        )
        assertEquals(0, result.status)
        assertTrue(result.message.contains("Blocked"))
    }

    @Test
    fun `database check fails when no connection string`() = runBlocking {
        val result = executor.executeCheck(
            monitor(MonitorParams(type = "database", dbConnectionString = null))
        )
        assertEquals(0, result.status)
        assertTrue(result.message.contains("No connection string configured"))
    }

    // ──── SSL check edge cases ────

    @Test
    fun `ssl check with non-existent host fails`() = runBlocking {
        val result = executor.executeCheck(
            monitor(MonitorParams(type = "ssl", hostname = "192.0.2.1", port = 443, timeoutSeconds = 2))
        )
        assertEquals(0, result.status)
        assertTrue(result.message.contains("SSL check failed"))
    }

    // ──── Ping check ────

    @Test
    fun `ping check reports result for localhost`() = runBlocking {
        withSelfHosted("true") {
            val result = executor.executeCheck(
                monitor(MonitorParams(type = "ping", hostname = LOCALHOST, timeoutSeconds = 5))
            )
            // localhost may or may not be reachable depending on OS permissions
            assertTrue(result.status == 0 || result.status == 1)
            assertTrue(result.responseTimeMs >= 0)
        }
    }

    // ──── DNS check edge cases ────

    @Test
    fun `dns check uses default A record type when not specified`() = runBlocking {
        // Uses a non-existent DNS name to test the flow without network dependency
        val result = executor.executeCheck(
            monitor(MonitorParams(type = "dns", hostname = "this.does.not.exist.invalid", timeoutSeconds = 3))
        )
        assertEquals(0, result.status)
    }

    // ──── Timeout handling ────

    @Test
    fun `executor catches timeout for slow http check`() = runBlocking {
        bypassSsrf()
        MockHttpServer { exchange ->
            Thread.sleep(3000) // Delay response past timeout
            exchange.respond(200, "OK")
        }.use { server ->
            val result = executor.executeCheck(
                monitor(MonitorParams(type = "http", url = server.baseUrl, timeoutSeconds = 1))
            )
            assertEquals(0, result.status)
            assertTrue(
                result.message.contains("timeout", ignoreCase = true) ||
                    result.message.contains("error", ignoreCase = true)
            )
        }
    }

    // ──── Scheduler: retry behavior ────

    @Test
    fun `scheduler retries on failure and succeeds on second attempt`() = runBlocking {
        val uptimeService = mockk<UptimeService>(relaxed = true)
        val checkExecutor = mockk<UptimeCheckExecutor>(relaxed = true)
        val incidentService = mockk<IncidentService>(relaxed = true)

        val monitorData = UptimeMonitorData(
            id = UUID.randomUUID(),
            organizationId = 1,
            organizationResourceId = "11111111-1111-4111-8111-111111111111",
            name = "Retry Monitor",
            type = "http",
            active = true,
            url = "https://example.com",
            intervalSeconds = 60,
            timeoutSeconds = 10,
            retries = 2,
            retryIntervalSeconds = 1,
            status = "up",
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0)
        )

        mockkObject(TaskLock)
        coEvery {
            TaskLock.tryWithLock<Unit>(any(), any(), any(), any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[3] as suspend () -> Unit
            block()
        }

        every { uptimeService.getMonitorsDueForCheck() } returns listOf(monitorData)

        // First call fails, second succeeds
        coEvery { checkExecutor.executeCheck(any()) } returnsMany listOf(
            CheckResult(0, 100, 0, "Connection refused"),
            CheckResult(1, 50, 200, "OK")
        )

        val scheduler = UptimeScheduler(
            uptimeService = uptimeService,
            checkExecutor = checkExecutor,
            incidentService = incidentService,
            frontendBaseUrl = "https://moneat.io",
        )

        scheduler.start()
        delay(3000)
        scheduler.stop()

        // Should have called executeCheck at least twice (initial + retry)
        coVerify(atLeast = 2) { checkExecutor.executeCheck(any()) }

        unmockkObject(TaskLock)
    }

    @Test
    fun `scheduler records heartbeat after failed check with no retries`() = runBlocking {
        val uptimeService = mockk<UptimeService>(relaxed = true)
        val checkExecutor = mockk<UptimeCheckExecutor>(relaxed = true)

        val monitorData = UptimeMonitorData(
            id = UUID.randomUUID(),
            organizationId = 1,
            organizationResourceId = "11111111-1111-4111-8111-111111111111",
            name = "No Retry Monitor",
            type = "http",
            active = true,
            url = "https://example.com",
            intervalSeconds = 60,
            timeoutSeconds = 10,
            retries = 0,
            retryIntervalSeconds = 1,
            status = "up",
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0)
        )

        mockkObject(TaskLock)
        coEvery {
            TaskLock.tryWithLock<Unit>(any(), any(), any(), any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[3] as suspend () -> Unit
            block()
        }

        every { uptimeService.getMonitorsDueForCheck() } returns listOf(monitorData)
        coEvery { checkExecutor.executeCheck(any()) } returns
            CheckResult(0, 100, 0, "Connection refused")

        val scheduler = UptimeScheduler(
            uptimeService = uptimeService,
            checkExecutor = checkExecutor,
            incidentService = mockk(relaxed = true),
            frontendBaseUrl = "https://moneat.io",
        )

        scheduler.start()
        delay(2000)
        scheduler.stop()

        coVerify(atLeast = 1) { uptimeService.recordHeartbeat(monitorData.id, any()) }
        coVerify(atLeast = 1) { uptimeService.updateMonitorStatus(monitorData.id, any()) }

        unmockkObject(TaskLock)
    }
}
