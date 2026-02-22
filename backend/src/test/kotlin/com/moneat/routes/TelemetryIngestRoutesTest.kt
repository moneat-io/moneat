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

package com.moneat.routes

import com.moneat.events.routes.telemetryIngestRoutes
import com.moneat.shared.services.PulsePayload
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetryIngestRoutesTest {

    private val json = Json { encodeDefaults = true }

    private fun samplePayload(deploymentId: String = "test-deploy-abc123") = PulsePayload(
        deploymentId = deploymentId,
        cpuCount = 8,
        memTotalBytes = 8_589_934_592L,
        memUsedBytes = 2_147_483_648L,
        osName = "Linux",
        osArch = "amd64",
        jvmVersion = "21.0.2",
        projectCount = 5,
        userCount = 12,
        eventCount = 10_000,
        issueCount = 42,
        selfHost = true,
        sslEnabled = false,
    )

    @Test
    fun `returns 202 and captures pulse payload`() = testApplication {
        val captured = mutableListOf<PulsePayload>()

        application {
            install(ContentNegotiation) { json() }
            routing { telemetryIngestRoutes(insertPulse = { captured.add(it) }) }
        }

        val response = client.post("/v1/pulse") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(samplePayload()))
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals(1, captured.size)
        val pulse = captured.first()
        assertEquals("test-deploy-abc123", pulse.deploymentId)
        assertEquals(8, pulse.cpuCount)
        assertEquals(8_589_934_592L, pulse.memTotalBytes)
        assertEquals(2_147_483_648L, pulse.memUsedBytes)
        assertEquals("Linux", pulse.osName)
        assertEquals("amd64", pulse.osArch)
        assertEquals("21.0.2", pulse.jvmVersion)
        assertEquals(5L, pulse.projectCount)
        assertEquals(12L, pulse.userCount)
        assertEquals(10_000L, pulse.eventCount)
        assertEquals(42L, pulse.issueCount)
        assertFalse(pulse.sslEnabled)
    }

    @Test
    fun `returns 202 for ssl-enabled deployment`() = testApplication {
        val captured = mutableListOf<PulsePayload>()

        application {
            install(ContentNegotiation) { json() }
            routing { telemetryIngestRoutes(insertPulse = { captured.add(it) }) }
        }

        val response = client.post("/v1/pulse") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(samplePayload().copy(sslEnabled = true)))
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertTrue(captured.first().sslEnabled)
    }

    @Test
    fun `returns 400 for invalid JSON`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { telemetryIngestRoutes(insertPulse = { }) }
        }

        val response = client.post("/v1/pulse") {
            contentType(ContentType.Application.Json)
            setBody("not json at all")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `returns 400 when deploymentId is blank`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { telemetryIngestRoutes(insertPulse = { }) }
        }

        val response = client.post("/v1/pulse") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(samplePayload(deploymentId = "  ")))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("deploymentId"))
    }

    @Test
    fun `returns 500 when insertPulse throws`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { telemetryIngestRoutes(insertPulse = { throw RuntimeException("ClickHouse unavailable") }) }
        }

        val response = client.post("/v1/pulse") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(samplePayload()))
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertTrue(response.bodyAsText().contains("Failed to store pulse"))
    }

    @Test
    fun `ignores unknown extra fields in payload`() = testApplication {
        val captured = mutableListOf<PulsePayload>()

        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing { telemetryIngestRoutes(insertPulse = { captured.add(it) }) }
        }

        val response = client.post("/v1/pulse") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "deploymentId": "deploy-xyz",
                  "cpuCount": 4,
                  "memTotalBytes": 4294967296,
                  "memUsedBytes": 1073741824,
                  "osName": "Mac OS X",
                  "osArch": "aarch64",
                  "jvmVersion": "21.0.1",
                  "projectCount": 1,
                  "userCount": 3,
                  "eventCount": 500,
                  "issueCount": 10,
                  "selfHost": true,
                  "sslEnabled": false,
                  "futureField": "ignored"
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("deploy-xyz", captured.first().deploymentId)
    }
}
