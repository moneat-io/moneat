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

import com.moneat.config.ClickHouseClient
import com.moneat.monitor.routes.infraRoutes
import com.moneat.shared.models.InfrastructureMapSavedViews
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.TestIpConstants
import com.moneat.testsupport.TestOidConstants
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InfraRoutesTest {
    companion object {
        private const val INFRA_EVENTS_PATH = "/v1/infra/events"
        private const val SAVED_VIEWS_PATH = "/v1/infra/map/saved-views"
        private const val SAVED_VIEW_NAME_MAX_LENGTH = 48
        private const val OVERLONG_SAVED_VIEW_NAME_LENGTH = SAVED_VIEW_NAME_MAX_LENGTH + 1
    }

    private data class SavedMapViewRequest(
        val name: String = "Production hosts",
        val resourceKind: String = "hosts",
        val groupBy: String = "tag:env",
        val fillBy: String = "health",
        val sizeBy: String = "memory",
        val searchQuery: String = "prod"
    )

    @BeforeTest
    fun setup() {
        val db = Database.connect(
            url = "jdbc:h2:mem:moneat_infra_routes;" +
                "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchemaForH2WithJsonb(
            Users,
            Organizations,
            Memberships,
            InfrastructureMapSavedViews
        )

        mockkObject(ClickHouseClient)
        mockkStatic(HttpResponse::bodyAsText)
    }

    @AfterTest
    fun teardown() {
        unmockkObject(ClickHouseClient)
        unmockkStatic(HttpResponse::bodyAsText)
    }

    private fun Application.installAuth() {
        installJwtAuth()
    }

    private fun token(userId: Int, orgId: Int? = null): String = RouteTestSupport.createToken(userId, orgId)

    private fun seedUser(): Int = transaction {
        Users.insert {
            it[email] = "infra-${System.nanoTime()}@test.com"
            it[password_hash] = "hash"
            it[email_verified] = true
        } get Users.id
    }

    private fun seedOrg(): Int = transaction {
        Organizations.insert {
            it[name] = "Infra Org"
            it[slug] = "infra-org-${System.nanoTime()}"
        } get Organizations.id
    }

    private fun seedMembership(userId: Int, orgId: Int) {
        transaction {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }
        }
    }

    private fun seedUserAndOrg(): Pair<Int, Int> {
        val orgId = seedOrg()
        val userId = seedUser()
        seedMembership(userId, orgId)
        return Pair(userId, orgId)
    }

    private fun stubClickHouseOk(body: String) {
        val mockResponse = mockk<HttpResponse>()
        every { mockResponse.status } returns HttpStatusCode.OK
        coEvery { mockResponse.bodyAsText(any()) } returns body
        coEvery { ClickHouseClient.execute(any(), any()) } returns mockResponse
    }

    private fun stubClickHouseError() {
        val mockResponse = mockk<HttpResponse>()
        every { mockResponse.status } returns HttpStatusCode.InternalServerError
        coEvery { ClickHouseClient.execute(any(), any()) } returns mockResponse
    }

    private suspend fun createSavedMapView(
        client: HttpClient,
        bearerToken: String,
        request: SavedMapViewRequest = SavedMapViewRequest(),
    ): HttpResponse =
        client.post(SAVED_VIEWS_PATH) {
            withAuth(bearerToken)
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "name": "${request.name}",
                  "resource_kind": "${request.resourceKind}",
                  "group_by": "${request.groupBy}",
                  "fill_by": "${request.fillBy}",
                  "size_by": "${request.sizeBy}",
                  "search_query": "${request.searchQuery}"
                }
                """.trimIndent()
            )
        }

    private fun savedViewId(responseBody: String): String {
        val match = Regex(""""id":"([^"]+)"""").find(responseBody)
        return match?.groupValues?.get(1) ?: error("expected saved view id in response: $responseBody")
    }

    // ──── Unauthenticated requests ────

    @Test
    fun `GET infra events without auth returns 401`() = testApplication {
        application {
            installAuth()
            routing { infraRoutes() }
        }
        val response = client.get(INFRA_EVENTS_PATH)
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ──── Current org with no matching data ────

    @Test
    fun `GET infra events with no data returns empty list`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get(INFRA_EVENTS_PATH) {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"events\":[]"))
        }

    // ──── Infrastructure map saved views ────

    @Test
    fun `saved map views are persisted for current user and organization`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val createResponse = createSavedMapView(client, token(userId, orgId))
            assertEquals(HttpStatusCode.Created, createResponse.status)
            val createBody = createResponse.bodyAsText()
            assertTrue(createBody.contains("\"name\":\"Production hosts\""))
            assertTrue(createBody.contains("\"resource_kind\":\"hosts\""))
            val viewId = savedViewId(createBody)

            val updateResponse = createSavedMapView(
                client,
                token(userId, orgId),
                SavedMapViewRequest(
                    name = "production HOSTS",
                    groupBy = "status",
                    fillBy = "lastSeen",
                    sizeBy = "cpu",
                    searchQuery = "web"
                )
            )
            assertEquals(HttpStatusCode.OK, updateResponse.status)
            assertTrue(updateResponse.bodyAsText().contains("\"search_query\":\"web\""))

            val listResponse = client.get(SAVED_VIEWS_PATH) {
                withAuth(token(userId, orgId))
            }
            val listBody = listResponse.bodyAsText()
            assertEquals(HttpStatusCode.OK, listResponse.status)
            assertTrue(listBody.contains("\"views\":["))
            assertTrue(listBody.contains("\"name\":\"production HOSTS\""))
            assertTrue(listBody.contains("\"group_by\":\"status\""))

            val deleteResponse = client.delete("$SAVED_VIEWS_PATH/$viewId") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

            val emptyListResponse = client.get(SAVED_VIEWS_PATH) {
                withAuth(token(userId, orgId))
            }
            assertTrue(emptyListResponse.bodyAsText().contains("\"views\":[]"))
        }

    @Test
    fun `saved map views are isolated by current user within an organization`() =
        testApplication {
            val orgId = seedOrg()
            val ownerUserId = seedUser()
            val otherUserId = seedUser()
            seedMembership(ownerUserId, orgId)
            seedMembership(otherUserId, orgId)

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val createResponse = createSavedMapView(client, token(ownerUserId, orgId))
            assertEquals(HttpStatusCode.Created, createResponse.status)
            val viewId = savedViewId(createResponse.bodyAsText())

            val otherListResponse = client.get(SAVED_VIEWS_PATH) {
                withAuth(token(otherUserId, orgId))
            }
            assertEquals(HttpStatusCode.OK, otherListResponse.status)
            assertTrue(otherListResponse.bodyAsText().contains("\"views\":[]"))

            val otherDeleteResponse = client.delete("$SAVED_VIEWS_PATH/$viewId") {
                withAuth(token(otherUserId, orgId))
            }
            assertEquals(HttpStatusCode.NotFound, otherDeleteResponse.status)

            val ownerListResponse = client.get(SAVED_VIEWS_PATH) {
                withAuth(token(ownerUserId, orgId))
            }
            assertTrue(ownerListResponse.bodyAsText().contains("\"name\":\"Production hosts\""))
        }

    @Test
    fun `saved map views require current organization claim`() =
        testApplication {
            val userId = seedUser()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get(SAVED_VIEWS_PATH) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `DELETE saved map view validates id and returns 404 when scoped row is missing`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val invalidIdResponse = client.delete("$SAVED_VIEWS_PATH/not-a-number") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.BadRequest, invalidIdResponse.status)

            val missingResponse = client.delete("$SAVED_VIEWS_PATH/11111111-1111-4111-8111-111111111111") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.NotFound, missingResponse.status)
        }

    @Test
    fun `POST saved map view rejects invalid options`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = createSavedMapView(
                client,
                token(userId, orgId),
                SavedMapViewRequest(
                    name = "Bad containers",
                    resourceKind = "containers",
                    groupBy = "agent",
                    fillBy = "health",
                    sizeBy = "cpu",
                    searchQuery = ""
                )
            )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid group option"))
        }

    @Test
    fun `POST saved map view rejects names that exceed the persisted key limit`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = createSavedMapView(
                client,
                token(userId, orgId),
                SavedMapViewRequest(
                    name = "x".repeat(OVERLONG_SAVED_VIEW_NAME_LENGTH)
                )
            )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(
                response.bodyAsText().contains(
                    "Saved view name must be at most $SAVED_VIEW_NAME_MAX_LENGTH characters"
                )
            )
        }

    // ──── GET /infra/events ────

    @Test
    fun `GET infra events returns 200 with data`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        stubClickHouseOk("""{"host":"web-01","alert_type":"cpu_high"}""")

        application {
            installAuth()
            routing { infraRoutes() }
        }

        val response = client.get(INFRA_EVENTS_PATH) {
            withAuth(token(userId, orgId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("web-01"))
    }

    @Test
    fun `GET infra events returns empty when CH errors`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            stubClickHouseError()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get(INFRA_EVENTS_PATH) {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"events\":[]"))
        }

    // ──── GET /infra/service-checks ────

    @Test
    fun `GET infra service-checks returns 200 with data`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            stubClickHouseOk(
                """{"check_name":"http_check","status":"ok"}"""
            )

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/service-checks") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("http_check"))
        }

    @Test
    fun `GET service-checks with no data returns empty`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/service-checks") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(
                response.bodyAsText().contains("\"serviceChecks\":[]")
            )
        }

    // ──── GET /infra/processes ────

    @Test
    fun `GET infra processes returns 200 with data`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            stubClickHouseOk("""{"process_name":"nginx","pid":"1234"}""")

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/processes") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("nginx"))
        }

    @Test
    fun `GET processes with no data returns empty`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/processes") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"processes\":[]"))
        }

    // ──── GET /infra/containers ────

    @Test
    fun `GET infra containers returns 200 with data`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            stubClickHouseOk(
                """{"container_name":"redis","status":"running"}"""
            )

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/containers") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("redis"))
        }

    @Test
    fun `GET containers with no data returns empty`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/containers") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"containers\":[]"))
        }

    // ──── GET /infra/connections ────

    @Test
    fun `GET infra connections returns 200 with data`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            stubClickHouseOk(
                """{"source_host":"web-01","dest_host":"db-01"}"""
            )

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/connections") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("web-01"))
        }

    @Test
    fun `GET connections with no data returns empty`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/connections") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(
                response.bodyAsText().contains("\"connections\":[]")
            )
        }

    // ──── GET /infra/k8s-resources ────

    @Test
    fun `GET infra k8s-resources returns 200 with data`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            stubClickHouseOk(
                """{"resource_type":"pod","name":"api-server"}"""
            )

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/k8s-resources") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("api-server"))
        }

    @Test
    fun `GET k8s-resources with no data returns empty`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/k8s-resources") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(
                response.bodyAsText().contains("\"resources\":[]")
            )
        }

    // ──── GET /infra/dbm/queries ────

    @Test
    fun `GET infra dbm queries returns 200 with data`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            stubClickHouseOk(
                """{"query_text":"SELECT 1","duration_ms":"42"}"""
            )

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/dbm/queries") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("SELECT 1"))
        }

    @Test
    fun `GET dbm queries with no data returns empty`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/dbm/queries") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"queries\":[]"))
        }

    // ──── GET /infra/debugger/logs ────

    @Test
    fun `GET infra debugger logs returns 200 with data`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            stubClickHouseOk(
                """{"probe_id":"probe-1","message":"breakpoint hit"}"""
            )

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/debugger/logs") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("probe-1"))
        }

    @Test
    fun `GET debugger logs with no data returns empty`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/debugger/logs") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"logs\":[]"))
        }

    // ──── GET /infra/debugger/diagnostics ────

    @Test
    fun `GET infra debugger diagnostics returns 200 with data`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            stubClickHouseOk(
                """{"probe_id":"diag-1","status":"ok"}"""
            )

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response =
                client.get("/v1/infra/debugger/diagnostics") {
                    header(
                        HttpHeaders.Authorization,
                        "Bearer ${token(userId, orgId)}"
                    )
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("diag-1"))
        }

    @Test
    fun `GET debugger diagnostics with no data returns empty`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response =
                client.get("/v1/infra/debugger/diagnostics") {
                    header(
                        HttpHeaders.Authorization,
                        "Bearer ${token(userId, orgId)}"
                    )
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(
                response.bodyAsText().contains("\"diagnostics\":[]")
            )
        }

    // ──── GET /infra/sbom ────

    @Test
    fun `GET infra sbom returns 200 with data`() = testApplication {
        val (userId, orgId) = seedUserAndOrg()
        stubClickHouseOk(
            """{"package_name":"openssl","version":"3.0.1"}"""
        )

        application {
            installAuth()
            routing { infraRoutes() }
        }

        val response = client.get("/v1/infra/sbom") {
            withAuth(token(userId, orgId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("openssl"))
    }

    @Test
    fun `GET sbom with no data returns empty`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/infra/sbom") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(
                response.bodyAsText().contains("\"packages\":[]")
            )
        }

    // ──── GET /network-devices ────

    @Test
    fun `GET network-devices returns 200 with data`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            stubClickHouseOk(
                """{"device_ip":"${TestIpConstants.IP_1}","vendor":"cisco"}"""
            )

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/network-devices") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains(TestIpConstants.IP_1))
        }

    @Test
    fun `GET network-devices with no data returns empty`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/network-devices") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"devices\":[]"))
        }

    // ──── GET /network-devices/flows ────

    @Test
    fun `GET network-devices flows returns 200 with data`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            stubClickHouseOk(
                """{"source_ip":"${TestIpConstants.IP_1}","dest_ip":"${TestIpConstants.IP_2}"}"""
            )

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/network-devices/flows") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains(TestIpConstants.IP_1))
        }

    @Test
    fun `GET network-devices flows with no data returns empty`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/network-devices/flows") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"flows\":[]"))
        }

    // ──── GET /network-devices/traps ────

    @Test
    fun `GET network-devices traps returns 200 with data`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            stubClickHouseOk(
                """{"trap_oid":"${TestOidConstants.OID_TRAP_TEST}","source_ip":"${TestIpConstants.IP_5}"}"""
            )

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/network-devices/traps") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains(TestIpConstants.IP_5))
        }

    @Test
    fun `GET network-devices traps with no data returns empty`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/network-devices/traps") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"traps\":[]"))
        }

    // ──── GET /network-devices/paths ────

    @Test
    fun `GET network-devices paths returns 200 with data`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            stubClickHouseOk(
                """{"destination":"${TestIpConstants.IP_PATH_DEST}","hop_count":"5"}"""
            )

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/network-devices/paths") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains(TestIpConstants.IP_PATH_DEST))
        }

    @Test
    fun `GET network-devices paths with no data returns empty`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()

            application {
                installAuth()
                routing { infraRoutes() }
            }

            val response = client.get("/v1/network-devices/paths") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"paths\":[]"))
        }
}
