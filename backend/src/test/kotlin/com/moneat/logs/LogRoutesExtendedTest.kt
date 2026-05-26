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
import com.moneat.logs.models.LogIndexResponse
import com.moneat.logs.models.LogIndexTestResponse
import com.moneat.logs.services.LogIndexService
import com.moneat.logs.services.LogService
import com.moneat.otlp.models.CreateOtlpApiKeyResponse
import com.moneat.otlp.models.OtlpApiKeyResponse
import com.moneat.otlp.models.OtlpObservedServiceResponse
import com.moneat.otlp.models.OtlpServiceMappingResponse
import com.moneat.otlp.services.OtlpApiKeyService
import com.moneat.otlp.services.OtlpServiceRoutingService
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
import io.mockk.coEvery
import io.mockk.every
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
    private val mockOtlpServiceRoutingService = mockk<OtlpServiceRoutingService>(relaxed = true)

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

    // ──── OTLP API keys ────

    @Test
    fun `POST logs api-keys creates key`() =
        testApplication {
            every { mockOtlpApiKeyService.createKey(1, "main key", 1) } returns CreateOtlpApiKeyResponse(
                id = 10,
                name = "main key",
                keyPrefix = "motlp_abc123",
                key = "motlp_abc123secret",
                createdAt = "2026-01-01T00:00:00Z"
            )
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.post("/v1/logs/api-keys") {
                withAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"name":" main key "}""")
            }

            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(response.bodyAsText().contains("motlp_abc123secret"))
        }

    @Test
    fun `POST logs api-keys rejects blank name`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.post("/v1/logs/api-keys") {
                withAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"   "}""")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Name is required"))
        }

    @Test
    fun `GET logs api-keys returns keys`() =
        testApplication {
            every { mockOtlpApiKeyService.listKeys(1) } returns listOf(
                OtlpApiKeyResponse(
                    id = 10,
                    name = "main key",
                    keyPrefix = "motlp_abc123",
                    createdAt = "2026-01-01T00:00:00Z",
                    lastUsedAt = "2026-01-01T01:00:00Z"
                )
            )
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.get("/v1/logs/api-keys") {
                withAuth(token)
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("main key"))
        }

    @Test
    fun `DELETE logs api-keys returns no content when deleted`() =
        testApplication {
            every { mockOtlpApiKeyService.deleteKey(1, 10) } returns true
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.delete("/v1/logs/api-keys/10") {
                withAuth(token)
            }

            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    @Test
    fun `DELETE logs api-keys returns 404 when missing`() =
        testApplication {
            every { mockOtlpApiKeyService.deleteKey(1, 10) } returns false
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.delete("/v1/logs/api-keys/10") {
                withAuth(token)
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("Key not found"))
        }

    // ──── Log indexes ────

    @Test
    fun `GET logs indexes returns indexes`() =
        testApplication {
            every { mockLogIndexService.list(1) } returns listOf(logIndexResponse())
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.get("/v1/logs/indexes") {
                withAuth(token)
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("errors"))
        }

    @Test
    fun `POST logs indexes creates index`() =
        testApplication {
            every { mockLogIndexService.create(1, any()) } returns logIndexResponse()
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.post("/v1/logs/indexes") {
                withAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"errors","filter_query":"level:error","retention_days":30}""")
            }

            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(response.bodyAsText().contains("level:error"))
        }

    @Test
    fun `POST logs indexes rejects blank name`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.post("/v1/logs/indexes") {
                withAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"name":" ","filter_query":"level:error"}""")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Name is required"))
        }

    @Test
    fun `PUT logs indexes returns updated index`() =
        testApplication {
            every { mockLogIndexService.update(1, 10, any()) } returns logIndexResponse(name = "warnings")
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.put("/v1/logs/indexes/10") {
                withAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"warnings"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("warnings"))
        }

    @Test
    fun `PUT logs indexes returns 404 when missing`() =
        testApplication {
            every { mockLogIndexService.update(1, 10, any()) } returns null
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.put("/v1/logs/indexes/10") {
                withAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"warnings"}""")
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("Index not found"))
        }

    @Test
    fun `DELETE logs indexes returns no content when deleted`() =
        testApplication {
            every { mockLogIndexService.delete(1, 10) } returns true
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.delete("/v1/logs/indexes/10") {
                withAuth(token)
            }

            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    @Test
    fun `DELETE logs indexes returns 404 when missing`() =
        testApplication {
            every { mockLogIndexService.delete(1, 10) } returns false
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.delete("/v1/logs/indexes/10") {
                withAuth(token)
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("Index not found"))
        }

    @Test
    fun `POST logs indexes test returns match counts`() =
        testApplication {
            coEvery { mockLogIndexService.testFilter(1, "level:error") } returns LogIndexTestResponse(
                matchCount = 3,
                totalCount = 10
            )
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.post("/v1/logs/indexes/test") {
                withAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"filter_query":"level:error"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"match_count\":3"))
        }

    @Test
    fun `GET logs export returns csv`() =
        testApplication {
            coEvery {
                mockLogService.exportCsv(
                    organizationId = 1,
                    from = null,
                    to = null,
                    query = null,
                    levels = emptyList(),
                    service = null,
                    environment = null,
                    tags = emptyMap(),
                    excludeService = null,
                    excludeEnvironment = null,
                    excludeContainerName = null,
                    excludeTags = emptyMap(),
                    limit = 5000
                )
            } returns "timestamp,message\n2026-01-01T00:00:00Z,hello\n"
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.get("/v1/logs/export") {
                withAuth(token)
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("timestamp,message"))
        }

    @Test
    fun `GET logs tail returns 401 without tail auth`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val response = client.get("/v1/logs/tail")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("Unauthorized"))
        }

    // ──── OTLP service routing ────

    @Test
    fun `GET otlp services returns observed services`() =
        testApplication {
            every { mockOtlpServiceRoutingService.listObservedServices(1) } returns listOf(
                OtlpObservedServiceResponse(
                    id = 10,
                    mappingId = 20,
                    serviceNamespace = "checkout",
                    serviceName = "api",
                    projectId = 30,
                    projectName = "Backend",
                    seenLogs = true,
                    seenTraces = false,
                    seenMetrics = true,
                    lastEnvironment = "production",
                    firstSeenAt = "2026-01-01T00:00:00Z",
                    lastSeenAt = "2026-01-01T01:00:00Z"
                )
            )
            application {
                installJwtAuth()
                routing {
                    logRoutes(
                        mockLogService,
                        mockOtlpApiKeyService,
                        mockLogIndexService,
                        mockOtlpServiceRoutingService
                    )
                }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.get("/v1/otlp/services") {
                withAuth(token)
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("checkout"))
            assertTrue(response.bodyAsText().contains("Backend"))
        }

    @Test
    fun `POST otlp service mapping returns mapping response`() =
        testApplication {
            every { mockOtlpServiceRoutingService.upsertMapping(1, any()) } returns OtlpServiceMappingResponse(
                id = 20,
                serviceNamespace = "checkout",
                serviceName = "api",
                projectId = 30,
                projectName = "Backend",
                updatedAt = "2026-01-01T00:00:00Z"
            )
            application {
                installJwtAuth()
                routing {
                    logRoutes(
                        mockLogService,
                        mockOtlpApiKeyService,
                        mockLogIndexService,
                        mockOtlpServiceRoutingService
                    )
                }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.post("/v1/otlp/service-mappings") {
                withAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"service_name":"api","service_namespace":"checkout","project_id":30}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Backend"))
        }

    @Test
    fun `POST otlp service mapping returns 400 when service is invalid`() =
        testApplication {
            every { mockOtlpServiceRoutingService.upsertMapping(1, any()) } returns null
            application {
                installJwtAuth()
                routing {
                    logRoutes(
                        mockLogService,
                        mockOtlpApiKeyService,
                        mockLogIndexService,
                        mockOtlpServiceRoutingService
                    )
                }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.post("/v1/otlp/service-mappings") {
                withAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"service_name":"unknown_service:java","project_id":30}""")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Valid service name"))
        }

    @Test
    fun `DELETE otlp service mapping returns no content when deleted`() =
        testApplication {
            every { mockOtlpServiceRoutingService.deleteMapping(1, 20) } returns true
            application {
                installJwtAuth()
                routing {
                    logRoutes(
                        mockLogService,
                        mockOtlpApiKeyService,
                        mockLogIndexService,
                        mockOtlpServiceRoutingService
                    )
                }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.delete("/v1/otlp/service-mappings/20") {
                withAuth(token)
            }

            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    @Test
    fun `DELETE otlp service mapping returns 404 when missing`() =
        testApplication {
            every { mockOtlpServiceRoutingService.deleteMapping(1, 20) } returns false
            application {
                installJwtAuth()
                routing {
                    logRoutes(
                        mockLogService,
                        mockOtlpApiKeyService,
                        mockLogIndexService,
                        mockOtlpServiceRoutingService
                    )
                }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.delete("/v1/otlp/service-mappings/20") {
                withAuth(token)
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("Mapping not found"))
        }

    @Test
    fun `DELETE otlp service mapping returns 400 for non-numeric id`() =
        testApplication {
            application {
                installJwtAuth()
                routing {
                    logRoutes(
                        mockLogService,
                        mockOtlpApiKeyService,
                        mockLogIndexService,
                        mockOtlpServiceRoutingService
                    )
                }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.delete("/v1/otlp/service-mappings/not-a-number") {
                withAuth(token)
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid mapping ID"))
        }

    private fun logIndexResponse(name: String = "errors"): LogIndexResponse =
        LogIndexResponse(
            id = 10,
            name = name,
            filterQuery = "level:error",
            retentionDays = 30,
            samplingRate = 1.0f,
            priority = 0,
            isActive = true,
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z"
        )
}
