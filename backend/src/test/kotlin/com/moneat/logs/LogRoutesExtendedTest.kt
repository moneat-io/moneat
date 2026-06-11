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

import com.moneat.logs.models.LogIndexResponse
import com.moneat.logs.models.LogIndexTestResponse
import com.moneat.logs.routes.LogManagementRouteDependencies
import com.moneat.logs.routes.LogRouteDependencies
import com.moneat.logs.routes.logRoutes as installLogRoutes
import com.moneat.logs.services.LogIndexService
import com.moneat.logs.services.LogService
import com.moneat.org.services.OrgMembershipService
import com.moneat.otlp.models.CreateOtlpApiKeyResponse
import com.moneat.otlp.models.OtlpApiKeyResponse
import com.moneat.otlp.models.OtlpObservedServiceResponse
import com.moneat.otlp.models.OtlpServiceMappingResponse
import com.moneat.otlp.services.OtlpApiKeyService
import com.moneat.otlp.services.OtlpServiceRoutingService
import com.moneat.shared.models.LogIndexes
import com.moneat.shared.models.Organizations
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.TestDatabaseHelper
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
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val LOG_INDEX_RESOURCE_ID = "11111111-1111-1111-1111-111111111111"
private const val MISSING_LOG_INDEX_RESOURCE_ID = "99999999-9999-9999-9999-999999999999"
private const val OTLP_KEY_RESOURCE_ID = "22222222-2222-2222-2222-222222222222"
private const val OTLP_OBSERVED_SERVICE_RESOURCE_ID = "33333333-3333-3333-3333-333333333333"
private const val OTLP_MAPPING_RESOURCE_ID = "44444444-4444-4444-4444-444444444444"
private const val PROJECT_RESOURCE_ID = "55555555-5555-5555-5555-555555555555"

class LogRoutesExtendedTest {

    private val mockLogService = mockk<LogService>(relaxed = true)
    private val mockOtlpApiKeyService = mockk<OtlpApiKeyService>(relaxed = true)
    private val mockLogIndexService = mockk<LogIndexService>(relaxed = true)
    private val mockMembershipService = mockk<OrgMembershipService>(relaxed = true)
    private val mockOtlpServiceRoutingService = mockk<OtlpServiceRoutingService>(relaxed = true)

    @BeforeTest
    fun setup() {
        startTestKoin()
        Database.connect(
            "jdbc:h2:mem:log_routes_extended;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver"
        )
        TestDatabaseHelper.resetSchema(Organizations, LogIndexes)
        seedLogIndexResolverRow()
    }

    @AfterTest
    fun teardown() {
        stopTestKoin()
    }

    private fun Route.logRoutes(
        logService: LogService,
        otlpApiKeyService: OtlpApiKeyService,
        logIndexService: LogIndexService
    ) {
        installLogRoutes(
            LogRouteDependencies(
                logService = logService,
                otlpApiKeyService = otlpApiKeyService,
                logIndexService = logIndexService,
                logManagement = LogManagementRouteDependencies(
                    logIndexService = logIndexService,
                    logService = logService,
                    membershipService = mockMembershipService,
                ),
                membershipService = mockMembershipService,
            )
        )
    }

    private fun Route.logRoutes(
        logService: LogService,
        otlpApiKeyService: OtlpApiKeyService,
        logIndexService: LogIndexService,
        otlpServiceRoutingService: OtlpServiceRoutingService
    ) {
        installLogRoutes(
            LogRouteDependencies(
                logService = logService,
                otlpApiKeyService = otlpApiKeyService,
                logIndexService = logIndexService,
                otlpServiceRoutingService = otlpServiceRoutingService,
                logManagement = LogManagementRouteDependencies(
                    logIndexService = logIndexService,
                    logService = logService,
                    membershipService = mockMembershipService,
                ),
                membershipService = mockMembershipService,
            )
        )
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
    fun `GET logs returns 401 when JWT has no org`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val response = client.get("/v1/logs") {
                withAuth(RouteTestSupport.createToken(userId = 1, orgId = null))
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `GET log pipelines returns 401 when JWT has no org`() =
        testApplication {
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val response = client.get("/v1/logs/pipelines") {
                withAuth(RouteTestSupport.createToken(userId = 1, orgId = null))
            }

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
                id = OTLP_KEY_RESOURCE_ID,
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
                    id = OTLP_KEY_RESOURCE_ID,
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
            every { mockOtlpApiKeyService.deleteKey(1, Uuid.parse(OTLP_KEY_RESOURCE_ID)) } returns true
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.delete("/v1/logs/api-keys/$OTLP_KEY_RESOURCE_ID") {
                withAuth(token)
            }

            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    @Test
    fun `DELETE logs api-keys returns 404 when missing`() =
        testApplication {
            every { mockOtlpApiKeyService.deleteKey(1, Uuid.parse(OTLP_KEY_RESOURCE_ID)) } returns false
            application {
                installJwtAuth()
                routing { logRoutes(mockLogService, mockOtlpApiKeyService, mockLogIndexService) }
            }

            val token = RouteTestSupport.createToken(userId = 1, orgId = 1)
            val response = client.delete("/v1/logs/api-keys/$OTLP_KEY_RESOURCE_ID") {
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
            val response = client.put("/v1/logs/indexes/$LOG_INDEX_RESOURCE_ID") {
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
            val response = client.put("/v1/logs/indexes/$MISSING_LOG_INDEX_RESOURCE_ID") {
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
            val response = client.delete("/v1/logs/indexes/$LOG_INDEX_RESOURCE_ID") {
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
            val response = client.delete("/v1/logs/indexes/$MISSING_LOG_INDEX_RESOURCE_ID") {
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
                    filters = any(),
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
        }

    // ──── OTLP service routing ────

    @Test
    fun `GET otlp services returns observed services`() =
        testApplication {
            every { mockOtlpServiceRoutingService.listObservedServices(1) } returns listOf(
                OtlpObservedServiceResponse(
                    id = OTLP_OBSERVED_SERVICE_RESOURCE_ID,
                    mappingId = OTLP_MAPPING_RESOURCE_ID,
                    serviceNamespace = "checkout",
                    serviceName = "api",
                    projectId = PROJECT_RESOURCE_ID,
                    projectResourceId = PROJECT_RESOURCE_ID,
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
            val responseBody = response.bodyAsText()
            assertTrue(responseBody.contains("checkout"))
            assertTrue(responseBody.contains("Backend"))
            assertTrue(
                responseBody.contains(""""project_resource_id":"$PROJECT_RESOURCE_ID"""")
            )
        }

    @Test
    fun `POST otlp service mapping returns mapping response`() =
        testApplication {
            every { mockOtlpServiceRoutingService.upsertMapping(1, any()) } returns OtlpServiceMappingResponse(
                id = OTLP_MAPPING_RESOURCE_ID,
                serviceNamespace = "checkout",
                serviceName = "api",
                projectId = PROJECT_RESOURCE_ID,
                projectResourceId = PROJECT_RESOURCE_ID,
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
                setBody("""{"service_name":"api","service_namespace":"checkout","project_id":"$PROJECT_RESOURCE_ID"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val responseBody = response.bodyAsText()
            assertTrue(responseBody.contains("Backend"))
            assertTrue(
                responseBody.contains(""""project_resource_id":"$PROJECT_RESOURCE_ID"""")
            )
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
            every { mockOtlpServiceRoutingService.deleteMapping(1, Uuid.parse(OTLP_MAPPING_RESOURCE_ID)) } returns true
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
            val response = client.delete("/v1/otlp/service-mappings/$OTLP_MAPPING_RESOURCE_ID") {
                withAuth(token)
            }

            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    @Test
    fun `DELETE otlp service mapping returns 404 when missing`() =
        testApplication {
            every { mockOtlpServiceRoutingService.deleteMapping(1, Uuid.parse(OTLP_MAPPING_RESOURCE_ID)) } returns false
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
            val response = client.delete("/v1/otlp/service-mappings/$OTLP_MAPPING_RESOURCE_ID") {
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
            id = LOG_INDEX_RESOURCE_ID,
            name = name,
            filterQuery = "level:error",
            retentionDays = 30,
            samplingRate = 1.0f,
            priority = 0,
            isActive = true,
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z"
        )

    private fun seedLogIndexResolverRow() {
        val now = Clock.System.now()
        transaction {
            Organizations.insert {
                it[Organizations.id] = 1
                it[Organizations.name] = "Logs Org"
                it[Organizations.slug] = "logs-org"
            }
            LogIndexes.insert {
                it[LogIndexes.id] = 10
                it[LogIndexes.resource_id] = Uuid.parse(LOG_INDEX_RESOURCE_ID)
                it[LogIndexes.organizationId] = 1
                it[LogIndexes.name] = "errors"
                it[LogIndexes.createdAt] = now
                it[LogIndexes.updatedAt] = now
            }
        }
    }
}
