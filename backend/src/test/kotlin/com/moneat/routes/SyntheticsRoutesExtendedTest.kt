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
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.synthetics.routes.CreatePrivateLocationRequest
import com.moneat.synthetics.routes.CreateSyntheticTestRequest
import com.moneat.synthetics.routes.SyntheticLocationService
import com.moneat.synthetics.routes.SyntheticLocations
import com.moneat.synthetics.routes.SyntheticRunResponse
import com.moneat.synthetics.routes.SyntheticsCheckExecutor
import com.moneat.synthetics.routes.SyntheticTestResponse
import com.moneat.synthetics.routes.SyntheticTestSummary
import com.moneat.synthetics.routes.SyntheticVariableRequest
import com.moneat.synthetics.routes.SyntheticVariableResponse
import com.moneat.synthetics.routes.SyntheticsService
import com.moneat.synthetics.routes.UpdateSyntheticTestRequest
import com.moneat.synthetics.routes.syntheticsRoutes
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.statement.HttpResponse
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SyntheticsRoutesExtendedTest {
    companion object {
        private var db: Database? = null
        private const val TEST_UUID = "11111111-1111-1111-1111-111111111111"
        private const val VARIABLE_UUID = "11111111-1111-4111-8111-111111111111"
        private const val MISSING_VARIABLE_UUID = "99999999-9999-4999-8999-999999999999"
        private const val MY_API_TEST = "My API Test"
        private const val SYNTHETICS_TESTS_PATH = "/v1/synthetics/tests"
        private const val SYNTHETICS_VARIABLES_PATH = "/v1/synthetics/variables"
        private const val BODY_NAME_VAR_VALUE = """{"name":"VAR","value":"val"}"""
        private const val SYNTHETICS_VARIABLE_PATH = "/v1/synthetics/variables/$VARIABLE_UUID"
        private const val MISSING_SYNTHETICS_VARIABLE_PATH = "/v1/synthetics/variables/$MISSING_VARIABLE_UUID"
    }

    private lateinit var mockService: SyntheticsService

    @BeforeTest
    fun setup() {
        mockService = mockk(relaxed = true)
        mockkObject(ClickHouseClient)
        mockkStatic(HttpResponse::bodyAsText)
        startTestKoin()
        loadKoinModules(
            module { single<SyntheticsService> { mockService } }
        )
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_synth_routes;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Memberships,
            SyntheticLocations
        )
    }

    @AfterTest
    fun teardown() {
        clearMocks(mockService)
        unmockkObject(ClickHouseClient)
        unmockkStatic(HttpResponse::bodyAsText)
        stopTestKoin()
    }

    private fun Application.installTestApp() {
        installJwtAuth()
        routing { syntheticsRoutes() }
    }

    private fun token(userId: Int, orgId: Int? = null): String =
        RouteTestSupport.createToken(userId, orgId)

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
        name = MY_API_TEST,
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
        id = VARIABLE_UUID,
        organizationId = orgId,
        name = "API_KEY",
        value = "test-key-123",
        isSecret = false,
        createdAt = 1700000000000L,
        updatedAt = 1700000000000L
    )

    private fun sampleRunResponse(locationCode: String) = SyntheticRunResponse(
        resultId = "preview",
        testId = "preview",
        testName = MY_API_TEST,
        testType = "api",
        status = "passed",
        locationCode = locationCode,
        durationMs = 123L,
        statusCode = 200,
        attempt = 1,
        assertionsTotal = 0,
        assertionsFailed = 0,
        errorMessage = "",
        timestamp = "2026-06-11T00:00:00Z"
    )

    private fun resourceUuid(value: String): Uuid = Uuid.parse(value)

    private fun stubSyntheticResultRows(body: String, totalCount: String = "1") {
        val rowsResponse = mockk<HttpResponse>()
        every { rowsResponse.status } returns HttpStatusCode.OK
        coEvery { rowsResponse.bodyAsText(any()) } returns body

        val countResponse = mockk<HttpResponse>()
        every { countResponse.status } returns HttpStatusCode.OK
        coEvery { countResponse.bodyAsText(any()) } returns totalCount

        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            if (firstArg<String>().contains("count()")) countResponse else rowsResponse
        }
    }

    private fun sampleSyntheticResultRow(orgId: Int): String =
        """{"result_id":"22222222-2222-4222-8222-222222222222","organization_id":$orgId,""" +
            """"test_id":"$TEST_UUID","test_name":"$MY_API_TEST","test_type":"api","status":"passed",""" +
            """"probe_dc":"aws-us-east-1","duration_ms":123,"error_message":"","timings":{},""" +
            """"timestamp":"2026-06-11 19:28:56.652","location_code":"aws-us-east-1","attempt":1,""" +
            """"status_code":200,"assertions_total":0,"assertions_failed":0,"resolved_ip":""}"""

    private fun assertNoPublicOrganizationId(body: String) {
        assertFalse(body.contains("organizationId"))
        assertFalse(body.contains("organization_id"))
    }

    // ──── Auth ────

    @Test
    fun `GET tests returns 401 when unauthenticated`() =
        testApplication {
            application { installTestApp() }
            val r = client.get(SYNTHETICS_TESTS_PATH)
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }

    @Test
    fun `POST tests returns 401 when unauthenticated`() =
        testApplication {
            application { installTestApp() }
            val r = client.post(SYNTHETICS_TESTS_PATH) {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"test"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }

    @Test
    fun `GET variables returns 401 when unauthenticated`() =
        testApplication {
            application { installTestApp() }
            val r = client.get(SYNTHETICS_VARIABLES_PATH)
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }

    // ──── No Org (403) ────

    @Test
    fun `GET tests returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            application { installTestApp() }
            val r = client.get(SYNTHETICS_TESTS_PATH) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    @Test
    fun `GET test by id returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            application { installTestApp() }
            val r = client.get("/v1/synthetics/tests/$TEST_UUID") {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    @Test
    fun `POST tests returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            application { installTestApp() }
            val r = client.post(SYNTHETICS_TESTS_PATH) {
                withAuth(token(userId))
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
                withAuth(token(userId))
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
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    @Test
    fun `POST run test returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            application { installTestApp() }
            val r = client.post("/v1/synthetics/tests/$TEST_UUID/run") {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    @Test
    fun `GET test summary returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            application { installTestApp() }
            val r = client.get("/v1/synthetics/tests/$TEST_UUID/summary") {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    @Test
    fun `GET variables returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            application { installTestApp() }
            val r = client.get(SYNTHETICS_VARIABLES_PATH) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    @Test
    fun `POST variables returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            application { installTestApp() }
            val r = client.post(SYNTHETICS_VARIABLES_PATH) {
                withAuth(token(userId))
                contentType(ContentType.Application.Json)
                setBody(BODY_NAME_VAR_VALUE)
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    @Test
    fun `PUT variable returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            application { installTestApp() }
            val r = client.put(SYNTHETICS_VARIABLE_PATH) {
                withAuth(token(userId))
                contentType(ContentType.Application.Json)
                setBody(BODY_NAME_VAR_VALUE)
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    @Test
    fun `DELETE variable returns 403 when user has no org`() =
        testApplication {
            val userId = seedUser()
            application { installTestApp() }
            val r = client.delete(SYNTHETICS_VARIABLE_PATH) {
                withAuth(token(userId))
            }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }

    // ──── Invalid IDs (400) ────

    @Test
    fun `GET test by invalid UUID returns 400`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            application { installTestApp() }
            val r = client.get("/v1/synthetics/tests/not-a-uuid") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `PUT test by invalid UUID returns 400`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            application { installTestApp() }
            val r = client.put("/v1/synthetics/tests/bad-id") {
                withAuth(token(userId, orgId))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"updated"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `DELETE test by invalid UUID returns 400`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            application { installTestApp() }
            val r = client.delete("/v1/synthetics/tests/bad-id") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `POST run test by invalid UUID returns 400`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            application { installTestApp() }
            val r = client.post("/v1/synthetics/tests/bad-id/run") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `GET test results by invalid UUID returns 400`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            application { installTestApp() }
            val r = client.get("/v1/synthetics/tests/bad-id/results") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `GET test summary by invalid UUID returns 400`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            application { installTestApp() }
            val r = client.get("/v1/synthetics/tests/bad-id/summary") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `PUT variable by invalid ID returns 400`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            application { installTestApp() }
            val r = client.put("$SYNTHETICS_VARIABLES_PATH/abc") {
                withAuth(token(userId, orgId))
                contentType(ContentType.Application.Json)
                setBody(BODY_NAME_VAR_VALUE)
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    @Test
    fun `DELETE variable by invalid ID returns 400`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            application { installTestApp() }
            val r = client.delete("$SYNTHETICS_VARIABLES_PATH/abc") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }

    // ──── Test CRUD (happy paths) ────

    @Test
    fun `GET tests returns 200 with list`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockService.listTests(orgId) } returns
                listOf(sampleTestResponse(orgId))
            application { installTestApp() }

            val r = client.get(SYNTHETICS_TESTS_PATH) {
                withAuth(token(userId, orgId))
            }
            val body = r.bodyAsText()

            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(body.contains(MY_API_TEST))
            assertNoPublicOrganizationId(body)
        }

    @Test
    fun `GET results strips organization id from public response`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            stubSyntheticResultRows(sampleSyntheticResultRow(orgId))
            application { installTestApp() }

            val r = client.get("/v1/synthetics/results?limit=1") {
                withAuth(token(userId, orgId))
            }
            val body = r.bodyAsText()

            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(body.contains(""""resultId":"22222222-2222-4222-8222-222222222222""""))
            assertNoPublicOrganizationId(body)
        }

    @Test
    fun `GET test results strips organization id from public response`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            stubSyntheticResultRows(sampleSyntheticResultRow(orgId))
            application { installTestApp() }

            val r = client.get("/v1/synthetics/tests/$TEST_UUID/results?limit=1") {
                withAuth(token(userId, orgId))
            }
            val body = r.bodyAsText()

            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(body.contains(""""testId":"$TEST_UUID""""))
            assertNoPublicOrganizationId(body)
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
                withAuth(token(userId, orgId))
            }
            val body = r.bodyAsText()

            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(body.contains(MY_API_TEST))
            assertNoPublicOrganizationId(body)
        }

    @Test
    fun `GET test by id returns 404 when not found`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockService.getTest(any(), orgId) } returns null
            application { installTestApp() }

            val r = client.get("/v1/synthetics/tests/$TEST_UUID") {
                withAuth(token(userId, orgId))
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

            val r = client.post(SYNTHETICS_TESTS_PATH) {
                withAuth(token(userId, orgId))
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"$MY_API_TEST","testType":"api",""" +
                        """"url":"https://example.com"}"""
                )
            }
            val body = r.bodyAsText()

            assertEquals(HttpStatusCode.Created, r.status)
            assertTrue(body.contains(MY_API_TEST))
            assertNoPublicOrganizationId(body)
        }

    @Test
    fun `POST tests returns 400 when quota exceeded`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockService.createTest(orgId, any<CreateSyntheticTestRequest>())
            } throws IllegalStateException("Synthetic test limit reached")
            application { installTestApp() }

            val r = client.post(SYNTHETICS_TESTS_PATH) {
                withAuth(token(userId, orgId))
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
                withAuth(token(userId, orgId))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Updated Test"}""")
            }
            val body = r.bodyAsText()

            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(body.contains("Updated Test"))
            assertNoPublicOrganizationId(body)
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
                withAuth(token(userId, orgId))
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
                withAuth(token(userId, orgId))
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
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    // ──── Preview ────

    @Test
    fun `POST preview forwards location query parameter`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            coEvery {
                mockService.previewTest(
                    orgId,
                    any<CreateSyntheticTestRequest>(),
                    "aws-eu-central-1",
                    any<SyntheticsCheckExecutor>()
                )
            } returns sampleRunResponse("aws-eu-central-1")
            application { installTestApp() }

            val r = client.post("/v1/synthetics/preview?location=aws-eu-central-1") {
                withAuth(token(userId, orgId))
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "name": "Preview API",
                      "testType": "api",
                      "intervalSeconds": 60,
                      "timeoutSeconds": 10,
                      "url": "https://example.com"
                    }
                    """.trimIndent()
                )
            }

            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("aws-eu-central-1"))
            coVerify(exactly = 1) {
                mockService.previewTest(
                    orgId,
                    any<CreateSyntheticTestRequest>(),
                    "aws-eu-central-1",
                    any<SyntheticsCheckExecutor>()
                )
            }
        }

    // ──── Probe Work ────

    @Test
    fun `GET probe work authenticates using location query parameter`() =
        testApplication {
            val orgId = seedOrg()
            val created = SyntheticLocationService().createPrivateLocation(
                orgId,
                CreatePrivateLocationRequest(
                    code = "private-us-east",
                    name = "Private US East"
                )
            )
            coEvery {
                mockService.getProbeWork(orgId, "private-us-east")
            } returns emptyList()
            application { installTestApp() }

            val r = client.get("/v1/synthetics/probe/work?location=private-us-east") {
                header("X-Probe-Key", created.key)
            }

            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("private-us-east"))
            coVerify(exactly = 1) {
                mockService.getProbeWork(orgId, "private-us-east")
            }
        }

    // ──── Run Test ────

    @Test
    fun `POST run test returns 202 on success`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockService.runTestNow(any(), orgId)
            } returns true
            application { installTestApp() }

            val r = client.post("/v1/synthetics/tests/$TEST_UUID/run") {
                withAuth(token(userId, orgId))
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
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    // ──── Test Summary ────

    @Test
    fun `GET test summary returns 200`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
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
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("99.5"))
        }

    @Test
    fun `GET test summary returns 404 when no data`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            coEvery {
                mockService.getTestSummary(TEST_UUID, any())
            } returns null
            application { installTestApp() }

            val r = client.get("/v1/synthetics/tests/$TEST_UUID/summary") {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    // ──── Variable CRUD ────

    @Test
    fun `GET variables returns 200 with list`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every { mockService.listVariables(orgId) } returns
                listOf(sampleVariableResponse(orgId))
            application { installTestApp() }

            val r = client.get(SYNTHETICS_VARIABLES_PATH) {
                withAuth(token(userId, orgId))
            }
            val body = r.bodyAsText()

            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(body.contains("API_KEY"))
            assertNoPublicOrganizationId(body)
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

            val r = client.post(SYNTHETICS_VARIABLES_PATH) {
                withAuth(token(userId, orgId))
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"API_KEY","value":"test-key-123"}"""
                )
            }
            val body = r.bodyAsText()

            assertEquals(HttpStatusCode.Created, r.status)
            assertTrue(body.contains("API_KEY"))
            assertNoPublicOrganizationId(body)
        }

    @Test
    fun `PUT variable returns 200 on update`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockService.updateVariable(
                    resourceUuid(VARIABLE_UUID),
                    orgId,
                    any<SyntheticVariableRequest>()
                )
            } returns sampleVariableResponse(orgId)
                .copy(value = "updated-key")
            application { installTestApp() }

            val r = client.put(SYNTHETICS_VARIABLE_PATH) {
                withAuth(token(userId, orgId))
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"API_KEY","value":"updated-key"}"""
                )
            }
            val body = r.bodyAsText()

            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(body.contains("updated-key"))
            assertNoPublicOrganizationId(body)
        }

    @Test
    fun `PUT variable returns 404 when not found`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockService.updateVariable(
                    resourceUuid(MISSING_VARIABLE_UUID),
                    orgId,
                    any<SyntheticVariableRequest>()
                )
            } returns null
            application { installTestApp() }

            val r = client.put(MISSING_SYNTHETICS_VARIABLE_PATH) {
                withAuth(token(userId, orgId))
                contentType(ContentType.Application.Json)
                setBody(BODY_NAME_VAR_VALUE)
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }

    @Test
    fun `DELETE variable returns 200 on success`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockService.deleteVariable(resourceUuid(VARIABLE_UUID), orgId)
            } returns true
            application { installTestApp() }

            val r = client.delete(SYNTHETICS_VARIABLE_PATH) {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("true"))
        }

    @Test
    fun `DELETE variable returns 404 when not found`() =
        testApplication {
            val (userId, orgId) = seedUserAndOrg()
            every {
                mockService.deleteVariable(resourceUuid(MISSING_VARIABLE_UUID), orgId)
            } returns false
            application { installTestApp() }

            val r = client.delete(MISSING_SYNTHETICS_VARIABLE_PATH) {
                withAuth(token(userId, orgId))
            }
            assertEquals(HttpStatusCode.NotFound, r.status)
        }
}
