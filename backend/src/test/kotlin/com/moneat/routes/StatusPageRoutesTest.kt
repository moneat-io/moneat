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

import com.moneat.statuspage.routes.statusPageRoutes
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.statuspage.models.StatusPageCustomDomains
import com.moneat.statuspage.models.StatusPageIncidentUpdates
import com.moneat.statuspage.models.StatusPageIncidents
import com.moneat.statuspage.models.StatusPageMonitors
import com.moneat.statuspage.models.StatusPages
import com.moneat.uptime.models.UptimeMonitors
import com.moneat.shared.models.Users
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll

class StatusPageRoutesTest {
    companion object {
        private const val JWT_SECRET = "status-routes-secret"
        private var dbInitialized = false

        @JvmStatic
        @BeforeAll
        fun setupKoin() {
            startTestKoin()
        }

        @JvmStatic
        @AfterAll
        fun teardownKoin() {
            stopTestKoin()
        }
    }

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_status_pages;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            dbInitialized = true
        }

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(
            Users, Organizations, Memberships, StatusPages, StatusPageIncidents,
            UptimeMonitors, StatusPageMonitors, StatusPageCustomDomains, StatusPageIncidentUpdates
        )
    }

    private fun Application.installAuth() {
        install(ContentNegotiation) { json() }
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(
                    JWT.require(Algorithm.HMAC256(JWT_SECRET))
                        .withIssuer("moneat").withAudience("moneat-users").build()
                )
                validate { JWTPrincipal(it.payload) }
            }
        }
    }

    private fun token(userId: Int): String =
        JWT.create()
            .withIssuer("moneat").withAudience("moneat-users")
            .withClaim("userId", userId)
            .sign(Algorithm.HMAC256(JWT_SECRET))

    private fun seedUser(): Int =
        transaction {
            Users.insert {
                it[email] = "sp-test-${System.nanoTime()}@test.com"
                it[password_hash] = "hash"
                it[email_verified] = true
            } get Users.id
        }

    private fun seedUserAndOrg(): Pair<Int, Int> {
        val orgId = transaction {
            Organizations.insert {
                it[name] = "SP Test Org"
                it[slug] = "sp-test-org-${System.nanoTime()}"
            } get Organizations.id
        }
        val userId = seedUser()
        transaction {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }
        }
        return Pair(userId, orgId)
    }

    private fun seedStatusPage(orgId: Int, slug: String = "test-slug-${System.nanoTime()}"): UUID {
        val pageId = UUID.randomUUID()
        val now = Clock.System.now()
        transaction {
            StatusPages.insert {
                it[id] = pageId
                it[organizationId] = orgId
                it[name] = "Test Page"
                it[StatusPages.slug] = slug
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        return pageId
    }

    // ==================== LIST STATUS PAGES ====================

    @Test
    fun `list status pages returns 200 empty list when user has no org`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val userId = seedUser()
            val response = client.get("/v1/status-pages") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `list status pages returns 200 when org has no pages`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val (userId, _) = seedUserAndOrg()
            val response = client.get("/v1/status-pages") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `list status pages returns pages when they exist`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val (userId, orgId) = seedUserAndOrg()
            seedStatusPage(orgId)
            val response = client.get("/v1/status-pages") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Test Page"))
        }

    // ==================== CREATE STATUS PAGE ====================

    @Test
    fun `create status page returns 403 when user has no org`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val userId = seedUser()
            val response = client.post("/v1/status-pages") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Test","slug":"test-slug"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `create status page returns 201 with valid request`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val (userId, _) = seedUserAndOrg()
            val slug = "new-page-${System.nanoTime()}"
            val response = client.post("/v1/status-pages") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"My Page","slug":"$slug"}""")
            }
            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(response.bodyAsText().contains(slug))
        }

    @Test
    fun `create status page returns 400 for invalid slug`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val (userId, _) = seedUserAndOrg()
            val response = client.post("/v1/status-pages") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Bad","slug":"INVALID SLUG!"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Slug"))
        }

    // ==================== GET STATUS PAGE ====================

    @Test
    fun `status page detail endpoint validates UUID format`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val response = client.get("/v1/status-pages/not-a-uuid") {
                header(HttpHeaders.Authorization, "Bearer ${token(999)}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid page ID format"))
        }

    @Test
    fun `get status page returns 403 when user has no org`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val userId = seedUser()
            val response = client.get("/v1/status-pages/${UUID.randomUUID()}") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `get status page returns 404 when not found`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val (userId, _) = seedUserAndOrg()
            val response = client.get("/v1/status-pages/${UUID.randomUUID()}") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `get status page returns 200 when found`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val (userId, orgId) = seedUserAndOrg()
            val pageId = seedStatusPage(orgId)
            val response = client.get("/v1/status-pages/$pageId") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Test Page"))
        }

    // ==================== UPDATE STATUS PAGE ====================

    @Test
    fun `update status page returns 400 for invalid uuid`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val userId = seedUser()
            val response = client.put("/v1/status-pages/not-a-uuid") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Updated"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `update status page returns 403 when user has no org`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val userId = seedUser()
            val response = client.put("/v1/status-pages/${UUID.randomUUID()}") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Updated"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `update status page returns 404 when not found`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val (userId, _) = seedUserAndOrg()
            val response = client.put("/v1/status-pages/${UUID.randomUUID()}") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Updated"}""")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `update status page returns 200 when found`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val (userId, orgId) = seedUserAndOrg()
            val pageId = seedStatusPage(orgId)
            val response = client.put("/v1/status-pages/$pageId") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Updated Name"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Updated Name"))
        }

    // ==================== DELETE STATUS PAGE ====================

    @Test
    fun `delete status page returns 400 for invalid uuid`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val userId = seedUser()
            val response = client.delete("/v1/status-pages/not-a-uuid") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `delete status page returns 403 when user has no org`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val userId = seedUser()
            val response = client.delete("/v1/status-pages/${UUID.randomUUID()}") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `delete status page returns 404 when not found`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val (userId, _) = seedUserAndOrg()
            val response = client.delete("/v1/status-pages/${UUID.randomUUID()}") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `delete status page returns 204 when deleted`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val (userId, orgId) = seedUserAndOrg()
            val pageId = seedStatusPage(orgId)
            val response = client.delete("/v1/status-pages/$pageId") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    // ==================== MANAGE MONITORS ====================

    @Test
    fun `add monitor to page returns 400 for invalid page uuid`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val userId = seedUser()
            val response = client.post("/v1/status-pages/not-a-uuid/monitors") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"monitors":[]}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `add monitor to page returns 403 when user has no org`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val userId = seedUser()
            val response = client.post("/v1/status-pages/${UUID.randomUUID()}/monitors") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"monitors":[]}""")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `remove monitor from page returns 400 for invalid page uuid`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val userId = seedUser()
            val response = client.delete("/v1/status-pages/not-a-uuid/monitors/${UUID.randomUUID()}") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `remove monitor from page returns 403 when user has no org`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val userId = seedUser()
            val response = client.delete("/v1/status-pages/${UUID.randomUUID()}/monitors/${UUID.randomUUID()}") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    // ==================== INCIDENTS ====================

    @Test
    fun `get incidents returns 400 for invalid page uuid`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val userId = seedUser()
            val response = client.get("/v1/status-pages/not-a-uuid/incidents") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `get incidents returns 403 when user has no org`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val userId = seedUser()
            val response = client.get("/v1/status-pages/${UUID.randomUUID()}/incidents") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    // ==================== CREATE INCIDENT ====================

    @Test
    fun `create incident returns 201 for valid request`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val (userId, orgId) = seedUserAndOrg()
            val pageId = seedStatusPage(orgId)
            val response = client.post("/v1/status-pages/$pageId/incidents") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"title":"Site down","status":"investigating","message":"We are looking into it"}""")
            }
            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(response.bodyAsText().contains("Site down"))
        }

    @Test
    fun `create incident returns 400 for invalid page uuid`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val userId = seedUser()
            val response = client.post("/v1/status-pages/not-a-uuid/incidents") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"title":"x","status":"investigating","message":"m"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `create incident returns 403 when user has no org`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val userId = seedUser()
            val response = client.post("/v1/status-pages/${UUID.randomUUID()}/incidents") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"title":"x","status":"investigating","message":"m"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    // ==================== UPDATE INCIDENT ====================

    @Test
    fun `update incident returns 200 for valid request`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val (userId, orgId) = seedUserAndOrg()
            val pageId = seedStatusPage(orgId)

            // First create an incident
            val createResp = client.post("/v1/status-pages/$pageId/incidents") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"title":"Original","status":"investigating","message":"Looking into it"}""")
            }
            assertEquals(HttpStatusCode.Created, createResp.status)
            val incidentId = kotlinx.serialization.json.Json.parseToJsonElement(createResp.bodyAsText())
                .jsonObject["id"]!!.jsonPrimitive.content

            val response = client.put("/v1/status-pages/$pageId/incidents/$incidentId") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"title":"Updated"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Updated"))
        }

    @Test
    fun `update incident returns 404 when incident not found`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val (userId, orgId) = seedUserAndOrg()
            val pageId = seedStatusPage(orgId)
            val incidentId = UUID.randomUUID()

            val response = client.put("/v1/status-pages/$pageId/incidents/$incidentId") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"title":"x"}""")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    // ==================== INCIDENT UPDATES ====================

    @Test
    fun `post incident update returns 201`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val (userId, orgId) = seedUserAndOrg()
            val pageId = seedStatusPage(orgId)

            // Create incident first
            val createResp = client.post("/v1/status-pages/$pageId/incidents") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"title":"Outage","status":"investigating","message":"Investigating"}""")
            }
            assertEquals(HttpStatusCode.Created, createResp.status)
            val incidentId = kotlinx.serialization.json.Json.parseToJsonElement(createResp.bodyAsText())
                .jsonObject["id"]!!.jsonPrimitive.content

            val response = client.post("/v1/status-pages/$pageId/incidents/$incidentId/updates") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"status":"identified","message":"Root cause identified"}""")
            }
            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(response.bodyAsText().contains("identified"))
        }

    @Test
    fun `post incident update returns 400 for invalid uuids`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val userId = seedUser()
            val response = client.post("/v1/status-pages/not-uuid/incidents/also-not/updates") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"status":"identified","message":"m"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ==================== CUSTOM DOMAINS ====================

    @Test
    fun `add custom domain returns 201 for valid domain`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val (userId, orgId) = seedUserAndOrg()
            val pageId = seedStatusPage(orgId)

            val response = client.post("/v1/status-pages/$pageId/domains") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"domain":"status.example.com"}""")
            }
            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(response.bodyAsText().contains("status.example.com"))
        }

    @Test
    fun `add custom domain returns 403 when user has no org`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val userId = seedUser()
            val response = client.post("/v1/status-pages/${UUID.randomUUID()}/domains") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"domain":"status.example.com"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `remove custom domain returns 204 when deleted`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val (userId, orgId) = seedUserAndOrg()
            val pageId = seedStatusPage(orgId)

            // Add domain first
            val addResp = client.post("/v1/status-pages/$pageId/domains") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"domain":"status2.example.com"}""")
            }
            assertEquals(HttpStatusCode.Created, addResp.status)
            val domainId = kotlinx.serialization.json.Json.parseToJsonElement(addResp.bodyAsText())
                .jsonObject["id"]!!.jsonPrimitive.content.toInt()

            val response = client.delete("/v1/status-pages/$pageId/domains/$domainId") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    @Test
    fun `remove custom domain returns 403 when user has no org`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val userId = seedUser()
            val response = client.delete("/v1/status-pages/${UUID.randomUUID()}/domains/1") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    // ==================== GET INCIDENTS HAPPY PATH ====================

    @Test
    fun `get incidents returns 200 with empty list`() =
        testApplication {
            application {
                installAuth()
                routing { statusPageRoutes() }
            }
            val (userId, orgId) = seedUserAndOrg()
            val pageId = seedStatusPage(orgId)

            val response = client.get("/v1/status-pages/$pageId/incidents") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

}

