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

package com.moneat.datadog.routes

import com.moneat.datadog.models.DebuggerProbe
import com.moneat.datadog.services.DebuggerProbeService
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DebuggerProbeRoutesTest {

    private val probeId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @BeforeTest
    fun setup() {
        mockkObject(DebuggerProbeService)
    }

    @AfterTest
    fun teardown() {
        unmockkObject(DebuggerProbeService)
    }

    @Test
    fun `GET debugger probes uses JWT org`() = testApplication {
        every { DebuggerProbeService.listProbes(listOf(42)) } returns listOf(sampleProbe())

        installRoutes()
        val response =
            client.get("/v1/infra/debugger/probes") {
                withAuth(RouteTestSupport.createToken(userId = 7, orgId = 42))
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("probe-service"))
        verify { DebuggerProbeService.listProbes(listOf(42)) }
    }

    @Test
    fun `POST debugger probes uses JWT org and user`() = testApplication {
        every {
            DebuggerProbeService.createProbe(organizationId = 42, createdBy = 7, request = any())
        } returns sampleProbe()

        installRoutes()
        val response =
            client.post("/v1/infra/debugger/probes") {
                withAuth(RouteTestSupport.createToken(userId = 7, orgId = 42))
                contentType(ContentType.Application.Json)
                setBody(createProbeBody())
            }

        assertEquals(HttpStatusCode.Created, response.status)
        assertTrue(response.bodyAsText().contains("probe-service"))
        verify { DebuggerProbeService.createProbe(organizationId = 42, createdBy = 7, request = any()) }
    }

    @Test
    fun `PUT debugger probe uses JWT org`() = testApplication {
        every { DebuggerProbeService.updateProbe(probeId, listOf(42), any()) } returns sampleProbe()

        installRoutes()
        val response =
            client.put("/v1/infra/debugger/probes/$probeId") {
                withAuth(RouteTestSupport.createToken(userId = 7, orgId = 42))
                contentType(ContentType.Application.Json)
                setBody("""{"active":false}""")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        verify { DebuggerProbeService.updateProbe(probeId, listOf(42), any()) }
    }

    @Test
    fun `DELETE debugger probe uses JWT org`() = testApplication {
        every { DebuggerProbeService.deleteProbe(probeId, listOf(42)) } returns true

        installRoutes()
        val response =
            client.delete("/v1/infra/debugger/probes/$probeId") {
                withAuth(RouteTestSupport.createToken(userId = 7, orgId = 42))
            }

        assertEquals(HttpStatusCode.NoContent, response.status)
        verify { DebuggerProbeService.deleteProbe(probeId, listOf(42)) }
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.installRoutes() {
        application {
            installJwtAuth()
            routing { debuggerProbeRoutes() }
        }
    }

    private fun createProbeBody(): String =
        """
        {
          "service":"probe-service",
          "whereType":"method",
          "typeName":"com.example.Worker",
          "methodName":"run"
        }
        """.trimIndent()

    private fun sampleProbe(): DebuggerProbe =
        DebuggerProbe(
            id = probeId.toString(),
            organizationId = 42,
            probeType = "log_probe",
            service = "probe-service",
            environment = "*",
            language = "java",
            active = true,
            whereType = "method",
            typeName = "com.example.Worker",
            methodName = "run",
            createdBy = 7,
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z"
        )
}
