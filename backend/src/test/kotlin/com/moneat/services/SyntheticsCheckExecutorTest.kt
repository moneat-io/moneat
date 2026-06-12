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

import com.moneat.synthetics.routes.SyntheticAssertion
import com.moneat.synthetics.routes.SyntheticStep
import com.moneat.synthetics.routes.SyntheticTestData
import com.moneat.synthetics.routes.SyntheticsCheckExecutor
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.respond
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.concurrent.thread

class SyntheticsCheckExecutorTest {
    private val executor = SyntheticsCheckExecutor()

    private var previousSelfHosted: String? = null

    @org.junit.jupiter.api.BeforeEach
    fun setup() {
        // Tests use MockHttpServer on localhost; allow loopback
        previousSelfHosted = System.getProperty("SELF_HOSTED")
        System.setProperty("SELF_HOSTED", "true")
    }

    @org.junit.jupiter.api.AfterEach
    fun teardown() {
        if (previousSelfHosted != null) {
            System.setProperty("SELF_HOSTED", previousSelfHosted!!)
        } else {
            System.clearProperty("SELF_HOSTED")
        }
    }

    private fun makeTestData(
        testType: String = "api",
        url: String? = null,
        method: String = "GET",
        assertions: String = "[]",
        headers: String? = null,
        body: String? = null,
        config: String? = null,
        steps: String? = null,
        timeoutSeconds: Int = 10
    ): SyntheticTestData {
        val now = Clock.System.now()
        return SyntheticTestData(
            id = java.util.UUID.randomUUID(),
            organizationId = 1,
            name = "Test",
            testType = testType,
            active = true,
            intervalSeconds = 300,
            timeoutSeconds = timeoutSeconds,
            url = url,
            method = method,
            headers = headers,
            body = body,
            authMethod = null,
            authUser = null,
            authPass = null,
            assertions = assertions,
            steps = steps,
            status = "pending",
            createdAt = now,
            updatedAt = now,
            config = config
        )
    }

    // ──── API test basics ────

    @Test
    fun `executeTest returns failed when no URL configured`() = runBlocking {
        val test = makeTestData(url = null)
        val result = executor.executeTest(test)
        assertEquals("failed", result.status)
        assertEquals("No URL configured", result.errorMessage)
    }

    @Test
    fun `executeTest passes with 200 and status_code assertion`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(200, """{"ok":true}""")
        }.use { server ->
            val assertions = Json.encodeToString(
                listOf(
                    mapOf(
                        "type" to "status_code",
                        "operator" to "equals",
                        "value" to "200"
                    )
                )
            )
            val test = makeTestData(url = server.baseUrl, assertions = assertions)
            val result = executor.executeTest(test)
            assertEquals("passed", result.status)
            assertTrue(result.durationMs >= 0)
            assertTrue(result.timings.containsKey("ttfb"))
            assertTrue(result.timings.containsKey("total"))
        }
    }

    @Test
    fun `executeTest fails when status_code assertion not met`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(500, """{"error":"oops"}""")
        }.use { server ->
            val assertions = Json.encodeToString(
                listOf(
                    mapOf(
                        "type" to "status_code",
                        "operator" to "equals",
                        "value" to "200"
                    )
                )
            )
            val test = makeTestData(url = server.baseUrl, assertions = assertions)
            val result = executor.executeTest(test)
            assertEquals("failed", result.status)
            assertEquals("One or more assertions failed", result.errorMessage)
        }
    }

    @Test
    fun `body_contains assertion passes when body contains value`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(200, """{"message":"hello world"}""")
        }.use { server ->
            val assertions = Json.encodeToString(
                listOf(
                    mapOf(
                        "type" to "body_contains",
                        "value" to "hello world"
                    )
                )
            )
            val test = makeTestData(url = server.baseUrl, assertions = assertions)
            val result = executor.executeTest(test)
            assertEquals("passed", result.status)
        }
    }

    @Test
    fun `body_contains assertion fails when body missing value`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(200, """{"message":"goodbye"}""")
        }.use { server ->
            val assertions = Json.encodeToString(
                listOf(
                    mapOf(
                        "type" to "body_contains",
                        "value" to "hello world"
                    )
                )
            )
            val test = makeTestData(url = server.baseUrl, assertions = assertions)
            val result = executor.executeTest(test)
            assertEquals("failed", result.status)
        }
    }

    @Test
    fun `body_json_path assertion extracts and compares value`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(200, """{"data":{"status":"healthy"}}""")
        }.use { server ->
            val assertions = Json.encodeToString(
                listOf(
                    mapOf(
                        "type" to "body_json_path",
                        "target" to "$.data.status",
                        "operator" to "equals",
                        "value" to "healthy"
                    )
                )
            )
            val test = makeTestData(url = server.baseUrl, assertions = assertions)
            val result = executor.executeTest(test)
            assertEquals("passed", result.status)
        }
    }

    @Test
    fun `header assertion checks response headers`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(200, "{}", "application/json")
        }.use { server ->
            val assertions = Json.encodeToString(
                listOf(
                    mapOf(
                        "type" to "header",
                        "target" to "Content-type",
                        "operator" to "contains",
                        "value" to "application/json"
                    )
                )
            )
            val test = makeTestData(url = server.baseUrl, assertions = assertions)
            val result = executor.executeTest(test)
            assertEquals("passed", result.status)
        }
    }

    @Test
    fun `executeTest captures request and response metadata without raw bodies`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(200, """{"token":"server-secret"}""", "application/json")
        }.use { server ->
            val test = makeTestData(
                url = server.baseUrl,
                method = "POST",
                body = """{"token":"client-secret"}"""
            )

            val result = executor.executeTest(test)

            assertEquals("passed", result.status)
            val request = assertNotNull(result.request)
            val response = assertNotNull(result.response)
            assertEquals("POST", request.method)
            assertEquals("", request.body)
            assertEquals(200, response.statusCode)
            assertEquals("", response.body)
        }
    }

    @Test
    fun `status_code less_than operator works`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(200, "{}")
        }.use { server ->
            val assertions = Json.encodeToString(
                listOf(
                    mapOf(
                        "type" to "status_code",
                        "operator" to "less_than",
                        "value" to "300"
                    )
                )
            )
            val test = makeTestData(url = server.baseUrl, assertions = assertions)
            val result = executor.executeTest(test)
            assertEquals("passed", result.status)
        }
    }

    @Test
    fun `status_code greater_than operator works`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(201, "{}")
        }.use { server ->
            val assertions = Json.encodeToString(
                listOf(
                    mapOf(
                        "type" to "status_code",
                        "operator" to "greater_than",
                        "value" to "100"
                    )
                )
            )
            val test = makeTestData(url = server.baseUrl, assertions = assertions)
            val result = executor.executeTest(test)
            assertEquals("passed", result.status)
        }
    }

    @Test
    fun `multiple assertions all must pass`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(200, """{"status":"ok"}""")
        }.use { server ->
            val assertions = Json.encodeToString(
                listOf(
                    mapOf(
                        "type" to "status_code",
                        "operator" to "equals",
                        "value" to "200"
                    ),
                    mapOf(
                        "type" to "body_contains",
                        "value" to "ok"
                    )
                )
            )
            val test = makeTestData(url = server.baseUrl, assertions = assertions)
            val result = executor.executeTest(test)
            assertEquals("passed", result.status)
        }
    }

    @Test
    fun `multiple assertions fail if any one fails`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(200, """{"status":"ok"}""")
        }.use { server ->
            val assertions = Json.encodeToString(
                listOf(
                    mapOf(
                        "type" to "status_code",
                        "operator" to "equals",
                        "value" to "200"
                    ),
                    mapOf(
                        "type" to "body_contains",
                        "value" to "not-present"
                    )
                )
            )
            val test = makeTestData(url = server.baseUrl, assertions = assertions)
            val result = executor.executeTest(test)
            assertEquals("failed", result.status)
        }
    }

    @Test
    fun `executeTest with no assertions passes`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(200, "{}")
        }.use { server ->
            val test = makeTestData(url = server.baseUrl, assertions = "[]")
            val result = executor.executeTest(test)
            assertEquals("passed", result.status)
        }
    }

    @Test
    fun `executeTest with malformed assertions json passes`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(200, "{}")
        }.use { server ->
            val test = makeTestData(url = server.baseUrl, assertions = "invalid")
            val result = executor.executeTest(test)
            assertEquals("passed", result.status)
        }
    }

    @Test
    fun `executeTest returns timings for API test`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(200, "{}")
        }.use { server ->
            val test = makeTestData(url = server.baseUrl)
            val result = executor.executeTest(test)
            assertEquals("passed", result.status)
            assertTrue(result.timings.isNotEmpty())
            assertTrue(result.timings.containsKey("total"))
        }
    }

    @Test
    fun `POST method with body works`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(201, """{"created":true}""")
        }.use { server ->
            val assertions = Json.encodeToString(
                listOf(
                    mapOf(
                        "type" to "status_code",
                        "operator" to "equals",
                        "value" to "201"
                    )
                )
            )
            val test = makeTestData(
                url = server.baseUrl,
                method = "POST",
                body = """{"name":"test"}""",
                assertions = assertions,
                headers = """{"Content-Type":"application/json"}"""
            )
            val result = executor.executeTest(test)
            assertEquals("passed", result.status)
        }
    }

    @Test
    fun `API test sends configured authentication headers`() = runBlocking {
        val expectedBasic = "Basic " + Base64.getEncoder().encodeToString("alice:secret".toByteArray())
        MockHttpServer { exchange ->
            val expected = if (exchange.requestURI.path == "/basic") {
                expectedBasic
            } else {
                "Bearer token-123"
            }
            exchange.respond(
                if (exchange.requestHeaders.getFirst("Authorization") == expected) 200 else 401,
                "{}"
            )
        }.use { server ->
            val basic = makeTestData(url = "${server.baseUrl}/basic").copy(
                authMethod = "basic",
                authUser = "alice",
                authPass = "secret"
            )
            val bearer = makeTestData(url = "${server.baseUrl}/bearer").copy(
                authMethod = "bearer",
                authPass = "token-123"
            )

            assertEquals("passed", executor.executeTest(basic).status)
            assertEquals("passed", executor.executeTest(bearer).status)
        }
    }

    // ──── Test type dispatch ────

    @Test
    fun `executeTest dispatches DNS type`() = runBlocking {
        val test = makeTestData(
            testType = "dns",
            url = "localhost"
        )
        val result = executor.executeTest(test)
        // DNS resolution of localhost should succeed
        assertEquals("passed", result.status)
        assertTrue(result.timings.containsKey("dns"))
    }

    @Test
    fun `DNS test fails for unresolvable hostname`() = runBlocking {
        val test = makeTestData(
            testType = "dns",
            url = "this-host-definitely-does-not-exist-moneat-test.invalid"
        )
        val result = executor.executeTest(test)
        assertEquals("failed", result.status)
        assertTrue(result.errorMessage.contains("DNS"))
    }

    @Test
    fun `DNS test with resolved_ip equals assertion`() = runBlocking {
        val assertions = Json.encodeToString(
            listOf(
                mapOf(
                    "type" to "resolved_ip",
                    "operator" to "contains",
                    "value" to "127"
                )
            )
        )
        val test = makeTestData(
            testType = "dns",
            url = "localhost",
            assertions = assertions
        )
        val result = executor.executeTest(test)
        assertEquals("passed", result.status)
    }

    @Test
    fun `DNS test blocks internal hostname when not self-hosted`() = runBlocking {
        withSelfHosted("false") {
            val test = makeTestData(testType = "dns", url = "localhost")
            val result = executor.executeTest(test)
            assertEquals("failed", result.status)
            assertTrue(result.errorMessage.contains("Blocked"), result.errorMessage)
        }
    }

    @Test
    fun `TCP test fails when no port configured`() = runBlocking {
        val test = makeTestData(
            testType = "tcp",
            url = "localhost",
            config = null
        )
        val result = executor.executeTest(test)
        assertEquals("failed", result.status)
        assertEquals("No port configured", result.errorMessage)
    }

    @Test
    fun `TCP test fails for closed port`() = runBlocking {
        val config = """{"hostname":"127.0.0.1","port":19999}"""
        val test = makeTestData(
            testType = "tcp",
            url = "127.0.0.1",
            config = config
        )
        val result = executor.executeTest(test)
        assertEquals("failed", result.status)
        assertTrue(result.errorMessage.contains("TCP"))
    }

    @Test
    fun `TCP test blocks internal hostname when not self-hosted`() = runBlocking {
        withSelfHosted("false") {
            val config = """{"hostname":"127.0.0.1","port":443}"""
            val test = makeTestData(testType = "tcp", url = "127.0.0.1", config = config)
            val result = executor.executeTest(test)
            assertEquals("failed", result.status)
            assertTrue(result.errorMessage.contains("Blocked"), result.errorMessage)
        }
    }

    @Test
    fun `UDP test blocks internal hostname when not self-hosted`() = runBlocking {
        withSelfHosted("false") {
            val config = """{"hostname":"127.0.0.1","port":53}"""
            val test = makeTestData(testType = "udp", url = "127.0.0.1", config = config)
            val result = executor.executeTest(test)
            assertEquals("failed", result.status)
            assertTrue(result.errorMessage.contains("Blocked"), result.errorMessage)
        }
    }

    @Test
    fun `TCP test passes for open port`() = runBlocking {
        // Use MockHttpServer as a TCP endpoint
        MockHttpServer { exchange ->
            exchange.respond(200, "{}")
        }.use { server ->
            val port = server.baseUrl.substringAfterLast(":").toInt()
            val config = """{"hostname":"127.0.0.1","port":$port}"""
            val test = makeTestData(
                testType = "tcp",
                url = "127.0.0.1",
                config = config
            )
            val result = executor.executeTest(test)
            assertEquals("passed", result.status)
            assertTrue(result.timings.containsKey("tcp"))
        }
    }

    @Test
    fun `TCP port_open assertion works`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(200, "{}")
        }.use { server ->
            val port = server.baseUrl.substringAfterLast(":").toInt()
            val config = """{"hostname":"127.0.0.1","port":$port}"""
            val assertions = Json.encodeToString(
                listOf(
                    mapOf(
                        "type" to "port_open",
                        "operator" to "equals",
                        "value" to "true"
                    )
                )
            )
            val test = makeTestData(
                testType = "tcp",
                url = "127.0.0.1",
                config = config,
                assertions = assertions
            )
            val result = executor.executeTest(test)
            assertEquals("passed", result.status)
        }
    }

    @Test
    fun `SSL test fails when no hostname configured`() = runBlocking {
        val test = makeTestData(
            testType = "ssl",
            url = null,
            config = null
        )
        val result = executor.executeTest(test)
        assertEquals("failed", result.status)
        assertEquals("No hostname configured", result.errorMessage)
    }

    @Test
    fun `SSL test blocks internal hostname when not self-hosted`() = runBlocking {
        withSelfHosted("false") {
            val config = """{"hostname":"127.0.0.1","port":443}"""
            val test = makeTestData(testType = "ssl", url = "127.0.0.1", config = config)
            val result = executor.executeTest(test)
            assertEquals("failed", result.status)
            assertTrue(result.errorMessage.contains("Blocked"), result.errorMessage)
        }
    }

    @Test
    fun `SSL test reports connection failure for allowed host`() = runBlocking {
        val config = """{"hostname":"127.0.0.1","port":1}"""
        val test = makeTestData(testType = "ssl", url = "127.0.0.1", config = config)
        val result = executor.executeTest(test)
        assertEquals("failed", result.status)
        assertTrue(result.errorMessage.contains("SSL check failed"), result.errorMessage)
    }

    @Test
    fun `UDP test passes when datagram response is received`() = runBlocking {
        DatagramSocket(0).use { udpServer ->
            val responder = thread(start = true) {
                val receiveBuffer = ByteArray(1)
                val request = DatagramPacket(receiveBuffer, receiveBuffer.size)
                udpServer.receive(request)
                val response = DatagramPacket(byteArrayOf(1), 1, request.address, request.port)
                udpServer.send(response)
            }
            val config = """{"hostname":"127.0.0.1","port":${udpServer.localPort}}"""
            val test = makeTestData(testType = "udp", url = "127.0.0.1", config = config)

            val result = executor.executeTest(test)

            assertEquals("passed", result.status)
            assertTrue(result.timings.containsKey("udp"))
            responder.join(1000)
        }
    }

    // ──── Multistep tests ────

    @Test
    fun `multistep test fails when no steps configured`() = runBlocking {
        val test = makeTestData(
            testType = "multistep",
            steps = null
        )
        val result = executor.executeTest(test)
        assertEquals("failed", result.status)
        assertEquals("No steps configured", result.errorMessage)
    }

    @Test
    fun `multistep test fails with empty steps array`() = runBlocking {
        val test = makeTestData(
            testType = "multistep",
            steps = "[]"
        )
        val result = executor.executeTest(test)
        assertEquals("failed", result.status)
        assertEquals("No steps configured", result.errorMessage)
    }

    @Test
    fun `multistep test passes with single step`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(200, """{"token":"abc123"}""")
        }.use { server ->
            val steps = Json.encodeToString(
                listOf(
                    SyntheticStep(
                        name = "Get token",
                        url = server.baseUrl,
                        method = "GET",
                        assertions = listOf(
                            SyntheticAssertion(
                                type = "status_code",
                                operator = "equals",
                                value = "200"
                            )
                        )
                    )
                )
            )
            val test = makeTestData(
                testType = "multistep",
                url = server.baseUrl,
                steps = steps
            )
            val result = executor.executeTest(test)
            assertEquals("passed", result.status)
        }
    }

    @Test
    fun `multistep test fails when step assertion fails`() = runBlocking {
        MockHttpServer { exchange ->
            exchange.respond(500, """{"error":"fail"}""")
        }.use { server ->
            val steps = Json.encodeToString(
                listOf(
                    SyntheticStep(
                        name = "Failing step",
                        url = server.baseUrl,
                        method = "GET",
                        assertions = listOf(
                            SyntheticAssertion(
                                type = "status_code",
                                operator = "equals",
                                value = "200"
                            )
                        )
                    )
                )
            )
            val test = makeTestData(
                testType = "multistep",
                url = server.baseUrl,
                steps = steps
            )
            val result = executor.executeTest(test)
            assertEquals("failed", result.status)
            assertTrue(result.errorMessage.contains("Failing step"))
        }
    }

    // ──── Connection error handling ────

    @Test
    fun `executeTest handles connection refused gracefully`() = runBlocking {
        val test = makeTestData(url = "http://127.0.0.1:19998")
        val result = executor.executeTest(test)
        assertEquals("failed", result.status)
        assertTrue(result.errorMessage.isNotBlank())
    }

    @Test
    fun `executeTest handles invalid URL gracefully`() = runBlocking {
        val test = makeTestData(url = "not-a-url")
        val result = executor.executeTest(test)
        assertEquals("failed", result.status)
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
}
