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

package com.moneat.datadog.security

import com.moneat.billing.services.BillingQuotaService
import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.datadog.services.DatadogService
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.header
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecurityIngestRoutesTest {

    companion object {
        private const val DD_API_KEY_HEADER = "DD-API-KEY"
        private const val VALID_KEY = "security-ingest-test-key"
    }

    private val quotaService = mockk<BillingQuotaService> {
        every { isEnforcementEnabled() } returns false
    }

    @BeforeTest
    fun setupKoin() {
        startTestKoin()
        DatadogAuthMiddleware.clearCache()
        mockkObject(DatadogService)
        mockkObject(SecurityIngestionService)
        every { SecurityIngestionService.enqueueSecurityEvents(any(), any()) } returns 1
        every { SecurityIngestionService.enqueueActivityDumps(any(), any()) } returns 1
        every { SecurityIngestionService.enqueueCompliance(any(), any()) } returns 1
    }

    @AfterTest
    fun teardownKoin() {
        unmockkObject(SecurityIngestionService)
        unmockkObject(DatadogService)
        DatadogAuthMiddleware.clearCache()
        stopTestKoin()
    }

    @Test
    fun `security events ingest returns forbidden when api key is missing`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { securityIngestRoutes(quotaService) }
        }
        val response = client.post("/dd/api/v2/security") {
            contentType(ContentType.Application.Json)
            setBody("""{"events":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("API key"))
    }

    @Test
    fun `security events ingest returns bad request for invalid json`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns 1
        application {
            install(ContentNegotiation) { json() }
            routing { securityIngestRoutes(quotaService) }
        }
        val response = client.post("/dd/api/v2/security") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid payload"))
    }

    @Test
    fun `security events ingest returns forbidden when api key is invalid`() = testApplication {
        every { DatadogService.validateApiKey("bad-key") } returns null
        application {
            install(ContentNegotiation) { json() }
            routing { securityIngestRoutes(quotaService) }
        }
        val response = client.post("/dd/api/v2/security") {
            header(DD_API_KEY_HEADER, "bad-key")
            contentType(ContentType.Application.Json)
            setBody("""{"events":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `security events ingest returns bad request when body is empty with valid api key`() =
        testApplication {
            every { DatadogService.validateApiKey(VALID_KEY) } returns 1
            application {
                install(ContentNegotiation) { json() }
                routing { securityIngestRoutes(quotaService) }
            }
            val response = client.post("/dd/api/v2/security") {
                header(DD_API_KEY_HEADER, VALID_KEY)
                contentType(ContentType.Application.Json)
                setBody(ByteArray(0))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid payload"))
        }

    @Test
    fun `activity dump ingest returns forbidden when api key is missing`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { securityIngestRoutes(quotaService) }
        }
        val response = client.post("/dd/api/v2/activity-dump") {
            contentType(ContentType.Application.Json)
            setBody("""{"dumps":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `compliance ingest returns forbidden when api key is missing`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { securityIngestRoutes(quotaService) }
        }
        val response = client.post("/dd/api/v2/compliance") {
            contentType(ContentType.Application.Json)
            setBody("""{"findings":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `security events ingest accepts empty events with valid api key`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns 7
        application {
            install(ContentNegotiation) { json() }
            routing { securityIngestRoutes(quotaService) }
        }
        val response = client.post("/dd/api/v2/security") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"events":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }
}
