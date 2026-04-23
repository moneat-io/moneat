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

package com.moneat.otlp

import com.moneat.billing.services.BillingQuotaService
import com.moneat.otlp.routes.otlpMetricsRoutes
import com.moneat.otlp.routes.otlpTraceRoutes
import com.moneat.otlp.services.OtlpApiKeyService
import com.moneat.otlp.services.OtlpMetricsService
import com.moneat.otlp.services.OtlpTraceService
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OtlpIngestRoutesTest {

    companion object {
        private const val VALID_KEY = "otlp-test-api-key-abc"
        private const val ORG_ID = 3
    }

    private val otlpApiKeyService = mockk<OtlpApiKeyService>()
    private val traceService = mockk<OtlpTraceService>(relaxed = true)
    private val metricsService = mockk<OtlpMetricsService>(relaxed = true)
    private val quotaService = mockk<BillingQuotaService>(relaxed = true)

    @BeforeTest
    fun setup() {
        startTestKoin()
        every { quotaService.isEnforcementEnabled() } returns false
    }

    @AfterTest
    fun teardown() {
        stopTestKoin()
    }

    private fun installRoutes(): io.ktor.server.testing.ApplicationTestBuilder.() -> Unit = {
        application {
            install(ContentNegotiation) { json() }
            routing {
                otlpTraceRoutes(traceService, quotaService, otlpApiKeyService)
                otlpMetricsRoutes(metricsService, quotaService, otlpApiKeyService)
            }
        }
    }

    // ──── Trace Routes ────

    @Test
    fun `POST traces returns 401 without bearer token`() = testApplication {
        installRoutes()()
        val response = client.post("/v1/traces") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("Missing or invalid"))
    }

    @Test
    fun `POST traces returns 401 with invalid bearer token`() = testApplication {
        every { otlpApiKeyService.validateKey("invalid-key") } returns null
        installRoutes()()
        val response = client.post("/v1/traces") {
            header(HttpHeaders.Authorization, "Bearer invalid-key")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST traces returns 415 for unsupported content type`() = testApplication {
        every { otlpApiKeyService.validateKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/v1/traces") {
            header(HttpHeaders.Authorization, "Bearer $VALID_KEY")
            header(HttpHeaders.ContentType, "text/plain")
            setBody("data")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `POST traces otlp alias returns 401 without bearer token`() = testApplication {
        installRoutes()()
        val response = client.post("/v1/traces/otlp") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST traces otlp alias returns 401 with invalid token`() = testApplication {
        every { otlpApiKeyService.validateKey("bad") } returns null
        installRoutes()()
        val response = client.post("/v1/traces/otlp") {
            header(HttpHeaders.Authorization, "Bearer bad")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST traces otlp alias returns 415 for unsupported content type`() = testApplication {
        every { otlpApiKeyService.validateKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/v1/traces/otlp") {
            header(HttpHeaders.Authorization, "Bearer $VALID_KEY")
            header(HttpHeaders.ContentType, "text/plain")
            setBody("data")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `POST traces accepts valid json with empty resourceSpans`() = testApplication {
        every { otlpApiKeyService.validateKey(VALID_KEY) } returns ORG_ID
        every { traceService.parseOtlpTracesJson(any()) } returns emptyList()
        installRoutes()()
        val response = client.post("/v1/traces") {
            header(HttpHeaders.Authorization, "Bearer $VALID_KEY")
            contentType(ContentType.Application.Json)
            setBody("""{"resourceSpans":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertTrue(response.bodyAsText().contains("0"))
    }

    @Test
    fun `POST traces returns 401 with empty bearer token`() = testApplication {
        installRoutes()()
        val response = client.post("/v1/traces") {
            header(HttpHeaders.Authorization, "Bearer ")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST traces returns 401 with non-bearer auth`() = testApplication {
        installRoutes()()
        val response = client.post("/v1/traces") {
            header(HttpHeaders.Authorization, "Basic dGVzdDp0ZXN0")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ──── Metrics Routes ────

    @Test
    fun `POST metrics returns 401 without bearer token`() = testApplication {
        installRoutes()()
        val response = client.post("/v1/metrics") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST metrics returns 415 for unsupported content type`() = testApplication {
        every { otlpApiKeyService.validateKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/v1/metrics") {
            header(HttpHeaders.Authorization, "Bearer $VALID_KEY")
            header(HttpHeaders.ContentType, "text/plain")
            setBody("data")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `POST metrics otlp alias returns 401 without bearer token`() = testApplication {
        installRoutes()()
        val response = client.post("/v1/metrics/otlp") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST metrics returns 401 with invalid bearer token`() = testApplication {
        every { otlpApiKeyService.validateKey("bad") } returns null
        installRoutes()()
        val response = client.post("/v1/metrics") {
            header(HttpHeaders.Authorization, "Bearer bad")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST metrics otlp alias returns 401 with invalid token`() = testApplication {
        every { otlpApiKeyService.validateKey("wrong") } returns null
        installRoutes()()
        val response = client.post("/v1/metrics/otlp") {
            header(HttpHeaders.Authorization, "Bearer wrong")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST metrics otlp alias returns 415 for unsupported content type`() = testApplication {
        every { otlpApiKeyService.validateKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/v1/metrics/otlp") {
            header(HttpHeaders.Authorization, "Bearer $VALID_KEY")
            header(HttpHeaders.ContentType, "text/plain")
            setBody("data")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `POST metrics accepts valid json with empty resourceMetrics`() = testApplication {
        every { otlpApiKeyService.validateKey(VALID_KEY) } returns ORG_ID
        every { metricsService.parseOtlpMetricsJson(any()) } returns emptyList()
        installRoutes()()
        val response = client.post("/v1/metrics") {
            header(HttpHeaders.Authorization, "Bearer $VALID_KEY")
            contentType(ContentType.Application.Json)
            setBody("""{"resourceMetrics":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertTrue(response.bodyAsText().contains("0"))
    }

    @Test
    fun `POST metrics returns 400 for null parsed result`() = testApplication {
        every { otlpApiKeyService.validateKey(VALID_KEY) } returns ORG_ID
        every { metricsService.parseOtlpMetricsJson(any()) } returns null
        installRoutes()()
        val response = client.post("/v1/metrics") {
            header(HttpHeaders.Authorization, "Bearer $VALID_KEY")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid OTLP metrics payload"))
    }

    @Test
    fun `POST metrics returns 401 with empty bearer token`() = testApplication {
        installRoutes()()
        val response = client.post("/v1/metrics") {
            header(HttpHeaders.Authorization, "Bearer ")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST metrics returns 401 with non-bearer auth`() = testApplication {
        installRoutes()()
        val response = client.post("/v1/metrics") {
            header(HttpHeaders.Authorization, "Basic dGVzdA==")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
