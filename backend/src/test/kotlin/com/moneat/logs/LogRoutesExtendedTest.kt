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

package com.moneat.logs

import com.moneat.logs.routes.logRoutes
import com.moneat.logs.services.LogIndexService
import com.moneat.logs.services.LogService
import com.moneat.otlp.services.OtlpApiKeyService
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
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
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogRoutesExtendedTest {

    private val mockLogService = mockk<LogService>(relaxed = true)
    private val mockOtlpApiKeyService = mockk<OtlpApiKeyService>(relaxed = true)
    private val mockLogIndexService = mockk<LogIndexService>(relaxed = true)

    @BeforeTest
    fun setup() {
        startTestKoin()
    }

    @AfterTest
    fun teardown() {
        stopTestKoin()
    }

    // ──── Auth checks (401 without JWT) ────

    @Test
    fun `GET logs returns 401 without JWT`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val response = client.get("/v1/logs")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `GET logs filters returns 401 without JWT`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val response = client.get("/v1/logs/filters")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `GET logs aggregate returns 401 without JWT`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val response = client.get("/v1/logs/aggregate")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `GET logs top returns 401 without JWT`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val response = client.get("/v1/logs/top")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `GET logs export returns 401 without JWT`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val response = client.get("/v1/logs/export")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `GET logs tag-values returns 401 without JWT`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val response = client.get("/v1/logs/tag-values")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `POST logs api-keys returns 401 without JWT`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val response = client.post("/v1/logs/api-keys")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `GET logs api-keys returns 401 without JWT`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val response = client.get("/v1/logs/api-keys")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `DELETE logs api-keys returns 401 without JWT`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val response = client.delete("/v1/logs/api-keys/1")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `GET logs indexes returns 401 without JWT`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val response = client.get("/v1/logs/indexes")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `POST logs indexes returns 401 without JWT`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val response = client.post("/v1/logs/indexes")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `PUT logs indexes returns 401 without JWT`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val response = client.put("/v1/logs/indexes/1")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `DELETE logs indexes returns 401 without JWT`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val response = client.delete("/v1/logs/indexes/1")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    // ──── Parameter validation ────

    @Test
    fun `GET logs tag-values returns 400 when key is missing`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.get("/v1/logs/tag-values") {
                withAuth(token)
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Missing tag key"))
        }

    @Test
    fun `GET logs top returns 400 when field is missing`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.get("/v1/logs/top") {
                withAuth(token)
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Missing field"))
        }

    @Test
    fun `DELETE logs api-keys returns 400 for non-numeric id`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.delete("/v1/logs/api-keys/abc") {
                withAuth(token)
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid key ID"))
        }

    @Test
    fun `PUT logs indexes returns 400 for non-numeric id`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.put("/v1/logs/indexes/abc") {
                withAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"test","filter_query":"*","retention_days":30,"description":"d"}""")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid index ID"))
        }

    @Test
    fun `DELETE logs indexes returns 400 for non-numeric id`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.delete("/v1/logs/indexes/xyz") {
                withAuth(token)
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid index ID"))
        }

    @Test
    fun `POST logs indexes test returns 401 without JWT`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val response = client.post("/v1/logs/indexes/test")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}
