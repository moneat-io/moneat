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

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.synthetics.routes.CreateSyntheticTestRequest
import com.moneat.synthetics.routes.SyntheticTestResponse
import com.moneat.synthetics.routes.SyntheticTestSummary
import com.moneat.synthetics.routes.SyntheticVariableRequest
import com.moneat.synthetics.routes.SyntheticVariableResponse
import com.moneat.synthetics.routes.SyntheticsService
import com.moneat.synthetics.routes.UpdateSyntheticTestRequest
import com.moneat.synthetics.routes.syntheticsRoutes
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyntheticsRoutesExtendedTest {
    companion object {
        private const val JWT_SECRET = "synth-routes-test-secret"
        private var dbInitialized = false
        private const val TEST_UUID = "11111111-1111-1111-1111-111111111111"
    }

    private val mockService = mockk<SyntheticsService>(relaxed = true)

    @BeforeTest
    fun setup() {
        startTestKoin()
        loadKoinModules(
            module { single<SyntheticsService> { mockService } }
        )
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_synth_routes;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            dbInitialized = true
        }
        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Memberships
        )
    }

    @AfterTest
    fun teardown() {
        stopTestKoin()
    }

    private fun Application.installTestApp() {
        install(ContentNegotiation) {
            json(
                Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    coerceInputValues = true
                }
            )
        }
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(
                    JWT.require(Algorithm.HMAC256(JWT_SECRET))
                        .withIssuer("moneat")
                        .withAudience("moneat-users")
                        .build()
                )
                validate { JWTPrincipal(it.payload) }
            }
        }
        routing { syntheticsRoutes() }
    }

    private fun token(userId: Int): String =
        JWT.create()
            .withIssuer("moneat")
            .withAudience("moneat-users")
            .withClaim("userId", userId)
            .sign(Algorithm.HMAC256(JWT_SECRET))

    private fun seedUser(): Int = transaction {
        Users.insert {
            it[email] = "synth-${System.nanoTime()}@test.com"
            it[password_hash] = "hash"
            it[email_verified] = true
        } get Users.id
    }

    private fun seedOrg(): Int = transaction {
        Organizations.insert {
            it[name] = "Synth Org"
            it[slug] = "synth-org-${System.nanoTime()}"
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

    private fun sampleTestResponse(orgId: Int) = SyntheticTestResponse(
        id = TEST_UUID,
        organizationId = orgId,
        name = "My API Test",
        testType = "api",
        active = true,
        intervalSeconds = 300,
        timeoutSeconds = 30,
        url = "https://example.com",
        method = "GET",
        headers = null,
        body = null,
        authMethod = null,
        authUser = null,
        assertions = emptyList(),
        steps = emptyList(),
        status = "pending",
        lastRunAt = null,
        lastStatus = null,
        tags = emptyList(),
        retryCount = 0,
        retryIntervalMs = 300,
        alertOnFailure = false,
        alertChannels = emptyList(),
        config = null,
        createdAt = 1700000000000L,
        updatedAt = 1700000000000L
    )

    private fun sampleVariableResponse(orgId: Int) = SyntheticVariableResponse(
        id = 1,
        organizationId = orgId,
        name = "API_KEY",
        value = "test-key-123",
        isSecret = false,
        createdAt = 1700000000000L,
        updatedAt = 1700000000000L
    )

    // ─── Auth ──────────────────────────────────────────────────

    @Test
    fun `GET tests returns 401 when unauthenticated`() =
        testApplication {
            application { installTestApp() }
            val r = client.get("/v1/synthetics/tests")
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }

    @Test
    fun `POST tests returns 401 when unauthenticated`() =
        testApplication {
            application { installTestApp() }
            val r = client.post("/v1/synthetics/tests") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"test"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }

    @Test
    fun `GET variables returns 401 when unauthenticated`() =
        testApplication {
            application { installTestApp() }
            val r = client.get("/v1/synthetics/variables")
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }

    // ─── No Org (403) ──────────────────────────────────────────

    @Test
    fun `GET tests returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            application { installTestApp() }
            val r = client.get("/v1/synthetics/tests") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    @Test
    fun `GET test by id returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            application { installTestApp() }
            val r = client.get("/v1/synthetics/tests/$TEST_UUID") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    @Test
    fun `POST tests returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            application { installTestApp() }
            val r = client.post("/v1/synthetics/tests") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"test"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    @Test
    fun `PUT test returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            application { installTestApp() }
            val r = client.put("/v1/synthetics/tests/$TEST_UUID") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"updated"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    @Test
    fun `DELETE test returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            application { installTestApp() }
            val r = client.delete("/v1/synthetics/tests/$TEST_UUID") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    @Test
    fun `POST run test returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            application { installTestApp() }
            val r = client.post("/v1/synthetics/tests/$TEST_UUID/run") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    @Test
    fun `GET test summary returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            application { installTestApp() }
            val r = client.get("/v1/synthetics/tests/$TEST_UUID/summary") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    @Test
    fun `GET variables returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            application { installTestApp() }
            val r = client.get("/v1/synthetics/variables") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    @Test
    fun `POST variables returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            application { installTestApp() }
            val r = client.post("/v1/synthetics/variables") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"VAR","value":"val"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    @Test
    fun `PUT variable returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            application { installTestApp() }
            val r = client.put("/v1/synthetics/variables/1") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"VAR","value":"val"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    @Test
    fun `DELETE variable returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            application { installTestApp() }
            val r = client.delete("/v1/synthetics/variables/1") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    // ─── Invalid IDs (400) ──────────────────────────────────────

    @Test
    fun `GET test by invalid UUID returns 400`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            application { installTestApp() }
            val r = client.get("/v1/synthetics/tests/not-a-uuid") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `PUT test by invalid UUID returns 400`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            application { installTestApp() }
            val r = client.put("/v1/synthetics/tests/bad-id") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"updated"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `DELETE test by invalid UUID returns 400`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            application { installTestApp() }
            val r = client.delete("/v1/synthetics/tests/bad-id") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `POST run test by invalid UUID returns 400`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            application { installTestApp() }
            val r = client.post("/v1/synthetics/tests/bad-id/run") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `GET test results by invalid UUID returns 400`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            application { installTestApp() }
            val r = client.get("/v1/synthetics/tests/bad-id/results") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `GET test summary by invalid UUID returns 400`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            application { installTestApp() }
            val r = client.get("/v1/synthetics/tests/bad-id/summary") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `PUT variable by invalid ID returns 400`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            application { installTestApp() }
            val r = client.put("/v1/synthetics/variables/abc") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"VAR","value":"val"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `DELETE variable by invalid ID returns 400`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            application { installTestApp() }
            val r = client.delete("/v1/synthetics/variables/abc") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    // ─── Test CRUD (happy paths) ─────────────────────────────────

    @Test
    fun `GET tests returns 200 with list`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockService.listTests(orgId) } returns
                listOf(sampleTestResponse(orgId))
            application { installTestApp() }

            val r = client.get("/v1/synthetics/tests") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("My API Test"))
        }

    @Test
    fun `GET test by id returns 200`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockService.getTest(any(), orgId)
            } returns sampleTestResponse(orgId)
            application { installTestApp() }

            val r = client.get("/v1/synthetics/tests/$TEST_UUID") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("My API Test"))
        }

    @Test
    fun `GET test by id returns 404 when not found`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockService.getTest(any(), orgId) } returns null
            application { installTestApp() }

            val r = client.get("/v1/synthetics/tests/$TEST_UUID") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    @Test
    fun `POST tests returns 201 on create`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockService.createTest(orgId, any<CreateSyntheticTestRequest>())
            } returns sampleTestResponse(orgId)
            application { installTestApp() }

            val r = client.post("/v1/synthetics/tests") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"My API Test","testType":"api",""" +
                        """"url":"https://example.com"}"""
                )
            }
            assertEquals(HttpStatusCode.Created, r.status)
            assertTrue(r.bodyAsText().contains("My API Test"))
        }

    @Test
    fun `POST tests returns 400 when quota exceeded`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockService.createTest(orgId, any<CreateSyntheticTestRequest>())
            } throws IllegalStateException("Synthetic test limit reached")
            application { installTestApp() }

            val r = client.post("/v1/synthetics/tests") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"test","url":"https://example.com"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertTrue(r.bodyAsText().contains("limit reached"))
        }

    @Test
    fun `PUT test returns 200 on update`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockService.updateTest(
                    any(), orgId, any<UpdateSyntheticTestRequest>()
                )
            } returns sampleTestResponse(orgId).copy(name = "Updated Test")
            application { installTestApp() }

            val r = client.put("/v1/synthetics/tests/$TEST_UUID") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Updated Test"}""")
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("Updated Test"))
        }

    @Test
    fun `PUT test returns 404 when not found`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockService.updateTest(
                    any(), orgId, any<UpdateSyntheticTestRequest>()
                )
            } returns null
            application { installTestApp() }

            val r = client.put("/v1/synthetics/tests/$TEST_UUID") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Updated Test"}""")
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    @Test
    fun `DELETE test returns 200 on success`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockService.deleteTest(any(), orgId)
            } returns true
            application { installTestApp() }

            val r = client.delete("/v1/synthetics/tests/$TEST_UUID") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("true"))
        }

    @Test
    fun `DELETE test returns 404 when not found`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockService.deleteTest(any(), orgId)
            } returns false
            application { installTestApp() }

            val r = client.delete("/v1/synthetics/tests/$TEST_UUID") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    // ─── Run Test ────────────────────────────────────────────────

    @Test
    fun `POST run test returns 202 on success`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockService.runTestNow(any(), orgId)
            } returns true
            application { installTestApp() }

            val r = client.post("/v1/synthetics/tests/$TEST_UUID/run") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.Accepted, r.status)
        }

    @Test
    fun `POST run test returns 404 when not found`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockService.runTestNow(any(), orgId)
            } returns false
            application { installTestApp() }

            val r = client.post("/v1/synthetics/tests/$TEST_UUID/run") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    // ─── Test Summary ────────────────────────────────────────────

    @Test
    fun `GET test summary returns 200`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            coEvery {
                mockService.getTestSummary(TEST_UUID, any())
            } returns SyntheticTestSummary(
                testId = TEST_UUID,
                uptimePercent = 99.5,
                avgResponseMs = 120.0,
                p95ResponseMs = 250.0,
                totalRuns = 100L,
                failureCount = 1L
            )
            application { installTestApp() }

            val r = client.get("/v1/synthetics/tests/$TEST_UUID/summary") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("99.5"))
        }

    @Test
    fun `GET test summary returns 404 when no data`() =
        testApplication {
            val (userId, _) = seedUserAndOrg()
            coEvery {
                mockService.getTestSummary(TEST_UUID, any())
            } returns null
            application { installTestApp() }

            val r = client.get("/v1/synthetics/tests/$TEST_UUID/summary") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    // ─── Variable CRUD ──────────────────────────────────────────

    @Test
    fun `GET variables returns 200 with list`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockService.listVariables(orgId) } returns
                listOf(sampleVariableResponse(orgId))
            application { installTestApp() }

            val r = client.get("/v1/synthetics/variables") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("API_KEY"))
        }

    @Test
    fun `POST variables returns 201 on create`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockService.createVariable(
                    orgId, any<SyntheticVariableRequest>()
                )
            } returns sampleVariableResponse(orgId)
            application { installTestApp() }

            val r = client.post("/v1/synthetics/variables") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"API_KEY","value":"test-key-123"}"""
                )
            }
            assertEquals(HttpStatusCode.Created, r.status)
            assertTrue(r.bodyAsText().contains("API_KEY"))
        }

    @Test
    fun `PUT variable returns 200 on update`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockService.updateVariable(
                    1, orgId, any<SyntheticVariableRequest>()
                )
            } returns sampleVariableResponse(orgId)
                .copy(value = "updated-key")
            application { installTestApp() }

            val r = client.put("/v1/synthetics/variables/1") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"API_KEY","value":"updated-key"}"""
                )
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("updated-key"))
        }

    @Test
    fun `PUT variable returns 404 when not found`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockService.updateVariable(
                    99, orgId, any<SyntheticVariableRequest>()
                )
            } returns null
            application { installTestApp() }

            val r = client.put("/v1/synthetics/variables/99") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"VAR","value":"val"}"""
                )
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    @Test
    fun `DELETE variable returns 200 on success`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockService.deleteVariable(1, orgId)
            } returns true
            application { installTestApp() }

            val r = client.delete("/v1/synthetics/variables/1") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("true"))
        }

    @Test
    fun `DELETE variable returns 404 when not found`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockService.deleteVariable(99, orgId)
            } returns false
            application { installTestApp() }

            val r = client.delete("/v1/synthetics/variables/99") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }
}
