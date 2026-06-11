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

import com.moneat.billing.models.BillingUsageResponse
import com.moneat.billing.services.QuotaReservationResult
import com.moneat.events.models.EnvelopeItem
import com.moneat.events.repositories.models.ProjectKeyVerification
import com.moneat.events.routes.IngestRouteDependencies
import com.moneat.events.routes.ingestRoutes
import com.moneat.events.routes.mapEnvelopeItemToQuotaType
import com.moneat.events.routes.mapEnvelopeItemTypeToQuotaType
import com.moneat.events.services.EventService
import com.moneat.logs.services.LogService
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.ProjectKeys
import com.moneat.shared.models.Projects
import com.moneat.testsupport.TestDatabaseHelper
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
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IngestRoutesEnvelopeTest {
    private var testOrgId: Int = 0
    private var testProjectId: Long = 0
    private val testPublicKey = "ingestroutekey123"

    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        startTestKoin()
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_ingest_routes;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            dbInitialized = true
        }

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(Organizations, Projects, ProjectKeys)

        transaction {
            testOrgId =
                Organizations.insert {
                    it[name] = "Ingest Test Org"
                    it[slug] = "ingest-test-org"
                }[Organizations.id]

            testProjectId =
                Projects.insert {
                    it[organization_id] = testOrgId
                    it[name] = "ingest-project"
                    it[slug] = "ingest-project"
                    it[framework] = "kotlin"
                }[Projects.id]

            ProjectKeys.insert {
                it[project_id] = testProjectId
                it[public_key] = testPublicKey
                it[secret_key] = "secret-$testPublicKey"
                it[platform_target] = "jvm"
                it[is_active] = true
            }
        }
    }

    @Test
    fun `envelope endpoint groups multi-item envelope by quota type and accepts`() =
        testApplication {
            val reservations = mutableListOf<Map<String, Int>>()

            environment {
                config =
                    MapApplicationConfig(
                        "ingest.queueKey" to "test:ingest:q",
                        "logs.queueKey" to "test:logs:q"
                    )
            }
            application {
                install(ContentNegotiation) { json() }
                routing {
                    ingestRoutes(
                        dependencies = IngestRouteDependencies().apply {
                            enqueueEnvelope = { _, _ -> }
                            isQuotaEnforcementEnabled = { true }
                            reserveEnvelopeQuota = { orgId, requestedUnitsByType, _ ->
                                reservations.add(requestedUnitsByType)
                                QuotaReservationResult(allowed = true, usage = emptyUsage(orgId))
                            }
                        },
                    )
                }
            }

            val response =
                client.post("/api/$testProjectId/envelope/") {
                    contentType(ContentType.Application.OctetStream)
                    header("X-Sentry-Auth", "Sentry sentry_key=$testPublicKey, sentry_version=7")
                    setBody(
                        buildEnvelope(
                            eventId = "evt-multi-1",
                            items =
                            listOf(
                                "transaction" to """{"event_id":"txn-1","type":"transaction"}""".toByteArray(),
                                "session" to """{"sid":"session-1","status":"ok"}""".toByteArray(),
                                "check_in" to """{"check_in_id":"check-1","status":"ok"}""".toByteArray()
                            )
                        )
                    )
                }

            assertEquals(HttpStatusCode.Accepted, response.status)
            assertEquals(1, reservations.size)
            assertEquals(1, reservations[0]["transaction"])
            assertEquals(1, reservations[0]["session"])
            assertEquals(1, reservations[0]["error"])
        }

    @Test
    fun `envelope endpoint reserves event feedback payload as feedback quota`() =
        testApplication {
            val reservations = mutableListOf<Map<String, Int>>()

            environment {
                config =
                    MapApplicationConfig(
                        "ingest.queueKey" to "test:ingest:q",
                        "logs.queueKey" to "test:logs:q"
                    )
            }
            application {
                install(ContentNegotiation) { json() }
                routing {
                    ingestRoutes(
                        dependencies = IngestRouteDependencies().apply {
                            enqueueEnvelope = { _, _ -> }
                            isQuotaEnforcementEnabled = { true }
                            reserveEnvelopeQuota = { orgId, requestedUnitsByType, _ ->
                                reservations.add(requestedUnitsByType)
                                QuotaReservationResult(allowed = true, usage = emptyUsage(orgId))
                            }
                        },
                    )
                }
            }

            val feedbackPayload =
                """
                {
                    "event_id":"44bad9a2e3774046977a21440ddb39b2",
                    "type":"feedback",
                    "contexts":{"feedback":{"message":"Great app"}}
                }
                """.trimIndent()

            val response =
                client.post("/api/$testProjectId/envelope/") {
                    contentType(ContentType.Application.OctetStream)
                    header("X-Sentry-Auth", "Sentry sentry_key=$testPublicKey, sentry_version=7")
                    setBody(
                        buildEnvelope(
                            eventId = "evt-feedback-1",
                            items = listOf("event" to feedbackPayload.toByteArray())
                        )
                    )
                }

            assertEquals(HttpStatusCode.Accepted, response.status)
            assertEquals(1, reservations.size)
            assertEquals(1, reservations[0]["feedback"])
            assertEquals(null, reservations[0]["error"])
        }

    @Test
    fun `store endpoint reserves feedback event payload as feedback quota`() =
        testApplication {
            val eventService = mockk<EventService>()
            val reservedTypes = mutableListOf<String>()

            every { eventService.verifyProjectKey(testProjectId, testPublicKey) } returns
                ProjectKeyVerification(true, "jvm")
            every { eventService.getOrganizationIdForProject(testProjectId) } returns testOrgId
            coEvery { eventService.processStoreEvent(testProjectId, any()) } returns Unit

            environment {
                config = MapApplicationConfig("ingest.queueKey" to "test:ingest:q")
            }
            application {
                install(ContentNegotiation) { json() }
                routing {
                    ingestRoutes(
                        dependencies = IngestRouteDependencies().apply {
                            this.eventService = eventService
                            isQuotaEnforcementEnabled = { true }
                            reserveSingleQuota = { orgId, _, eventType, _ ->
                                reservedTypes.add(eventType)
                                QuotaReservationResult(allowed = true, usage = emptyUsage(orgId))
                            }
                        },
                    )
                }
            }

            val response =
                client.post("/api/$testProjectId/store/") {
                    contentType(ContentType.Application.Json)
                    header("X-Sentry-Auth", "Sentry sentry_key=$testPublicKey, sentry_version=7")
                    setBody(
                        """
                        {
                            "event_id":"64bad9a2e3774046977a21440ddb39b2",
                            "type":"feedback",
                            "contexts":{"feedback":{"message":"Legacy store feedback"}}
                        }
                        """.trimIndent()
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf("feedback"), reservedTypes)
        }

    @Test
    fun `logs endpoint accepts gzipped SDK logs and uses default queue`() =
        testApplication {
            val eventService = mockk<EventService>()
            val logService = mockk<LogService>()
            val reservedUnits = mutableListOf<Int>()

            every { eventService.verifyProjectKey(testProjectId, testPublicKey) } returns
                ProjectKeyVerification(true, "jvm")
            every { eventService.getOrganizationIdForProject(testProjectId) } returns testOrgId
            every { logService.estimateBillableBytes(any()) } returns 23L
            coEvery {
                logService.enqueueSdkLogs(testOrgId.toLong(), any(), "moneat:logs:queue")
            } returns 1

            environment {
                config = MapApplicationConfig("ingest.queueKey" to "test:ingest:q")
            }
            application {
                install(ContentNegotiation) { json() }
                routing {
                    ingestRoutes(
                        dependencies = IngestRouteDependencies().apply {
                            this.eventService = eventService
                            this.logService = logService
                            isQuotaEnforcementEnabled = { true }
                            reserveLogQuota = { orgId, units, bytes ->
                                reservedUnits.add(units)
                                assertEquals(23L, bytes)
                                QuotaReservationResult(allowed = true, usage = emptyUsage(orgId))
                            }
                        },
                    )
                }
            }

            val response =
                client.post("/api/$testProjectId/logs/") {
                    contentType(ContentType.Application.Json)
                    header("x-moneat-dsn", "https://$testPublicKey@example.test/$testProjectId")
                    header("Content-Encoding", "gzip")
                    setBody(gzip("""[{"level":"info","message":"hello","service":"sdk"}]"""))
                }

            assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("\"accepted\":1"))
            assertEquals(listOf(1), reservedUnits)
            coVerify(exactly = 1) {
                logService.enqueueSdkLogs(testOrgId.toLong(), any(), "moneat:logs:queue")
            }
        }

    @Test
    fun `logs endpoint returns accepted zero for empty log list`() =
        testApplication {
            val eventService = mockk<EventService>()

            every { eventService.verifyProjectKey(testProjectId, testPublicKey) } returns
                ProjectKeyVerification(true, "jvm")
            every { eventService.getOrganizationIdForProject(testProjectId) } returns testOrgId

            application {
                install(ContentNegotiation) { json() }
                routing {
                    ingestRoutes(
                        dependencies = IngestRouteDependencies().apply {
                            this.eventService = eventService
                            isQuotaEnforcementEnabled = { true }
                        },
                    )
                }
            }

            val response =
                client.post("/api/$testProjectId/logs/") {
                    contentType(ContentType.Application.Json)
                    header("X-Sentry-Auth", "Sentry sentry_key=$testPublicKey")
                    setBody("[]")
                }

            assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("\"accepted\":0"))
        }

    @Test
    fun `logs endpoint returns bad request for malformed payload`() =
        testApplication {
            val eventService = mockk<EventService>()

            every { eventService.verifyProjectKey(testProjectId, testPublicKey) } returns
                ProjectKeyVerification(true, "jvm")
            every { eventService.getOrganizationIdForProject(testProjectId) } returns testOrgId

            application {
                install(ContentNegotiation) { json() }
                routing {
                    ingestRoutes(
                        dependencies = IngestRouteDependencies().apply {
                            this.eventService = eventService
                        },
                    )
                }
            }

            val response =
                client.post("/api/$testProjectId/logs/") {
                    contentType(ContentType.Application.Json)
                    header("X-Sentry-Auth", "Sentry sentry_key=$testPublicKey")
                    setBody("{not-json")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid log payload"))
        }

    @Test
    fun `logs endpoint returns 429 when quota reservation rejects`() =
        testApplication {
            val eventService = mockk<EventService>()
            val logService = mockk<LogService>()

            every { eventService.verifyProjectKey(testProjectId, testPublicKey) } returns
                ProjectKeyVerification(true, "jvm")
            every { eventService.getOrganizationIdForProject(testProjectId) } returns testOrgId
            every { logService.estimateBillableBytes(any()) } returns 42L

            application {
                install(ContentNegotiation) { json() }
                routing {
                    ingestRoutes(
                        dependencies = IngestRouteDependencies().apply {
                            this.eventService = eventService
                            this.logService = logService
                            isQuotaEnforcementEnabled = { true }
                            reserveLogQuota = { orgId, _, _ ->
                                QuotaReservationResult(
                                    allowed = false,
                                    reason = "log_quota",
                                    usage = emptyUsage(orgId),
                                )
                            }
                        },
                    )
                }
            }

            val response =
                client.post("/api/$testProjectId/logs/") {
                    contentType(ContentType.Application.Json)
                    header("X-Sentry-Auth", "Sentry sentry_key=$testPublicKey")
                    setBody("""[{"message":"blocked"}]""")
                }

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertTrue(response.bodyAsText().contains("Quota exceeded"))
        }

    @Test
    fun `logs endpoint returns not found when project organization is missing`() =
        testApplication {
            val eventService = mockk<EventService>()

            every { eventService.verifyProjectKey(testProjectId, testPublicKey) } returns
                ProjectKeyVerification(true, "jvm")
            every { eventService.getOrganizationIdForProject(testProjectId) } returns null

            application {
                install(ContentNegotiation) { json() }
                routing {
                    ingestRoutes(
                        dependencies = IngestRouteDependencies().apply {
                            this.eventService = eventService
                        },
                    )
                }
            }

            val response =
                client.post("/api/$testProjectId/logs/") {
                    contentType(ContentType.Application.Json)
                    header("X-Sentry-Auth", "Sentry sentry_key=$testPublicKey")
                    setBody("""[{"message":"orphaned"}]""")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("Project organization not found"))
        }

    @Test
    fun `store endpoint returns not found when project organization is missing`() =
        testApplication {
            val eventService = mockk<EventService>()

            every { eventService.verifyProjectKey(testProjectId, testPublicKey) } returns
                ProjectKeyVerification(true, "jvm")
            every { eventService.getOrganizationIdForProject(testProjectId) } returns null

            application {
                install(ContentNegotiation) { json() }
                routing {
                    ingestRoutes(
                        dependencies = IngestRouteDependencies().apply {
                            this.eventService = eventService
                            isQuotaEnforcementEnabled = { true }
                        },
                    )
                }
            }

            val response =
                client.post("/api/$testProjectId/store/") {
                    contentType(ContentType.Application.Json)
                    header("X-Sentry-Auth", "Sentry sentry_key=$testPublicKey")
                    setBody("""{"event_id":"store-orphan"}""")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("Project organization not found"))
        }

    @Test
    fun `store endpoint returns 429 when quota reservation rejects`() =
        testApplication {
            val eventService = mockk<EventService>()

            every { eventService.verifyProjectKey(testProjectId, testPublicKey) } returns
                ProjectKeyVerification(true, "jvm")
            every { eventService.getOrganizationIdForProject(testProjectId) } returns testOrgId

            application {
                install(ContentNegotiation) { json() }
                routing {
                    ingestRoutes(
                        dependencies = IngestRouteDependencies().apply {
                            this.eventService = eventService
                            isQuotaEnforcementEnabled = { true }
                            reserveSingleQuota = { orgId, _, _, _ ->
                                QuotaReservationResult(
                                    allowed = false,
                                    reason = "store_quota",
                                    usage = emptyUsage(orgId),
                                )
                            }
                        },
                    )
                }
            }

            val response =
                client.post("/api/$testProjectId/store/") {
                    contentType(ContentType.Application.Json)
                    header("X-Sentry-Auth", "Sentry sentry_key=$testPublicKey")
                    setBody("""{"event_id":"store-quota"}""")
                }

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertTrue(response.bodyAsText().contains("Quota exceeded"))
        }

    @Test
    fun `store endpoint returns bad request when processing fails`() =
        testApplication {
            val eventService = mockk<EventService>()

            every { eventService.verifyProjectKey(testProjectId, testPublicKey) } returns
                ProjectKeyVerification(true, "jvm")
            coEvery { eventService.processStoreEvent(testProjectId, any()) } throws IllegalStateException("bad event")

            application {
                install(ContentNegotiation) { json() }
                routing {
                    ingestRoutes(
                        dependencies = IngestRouteDependencies().apply {
                            this.eventService = eventService
                            isQuotaEnforcementEnabled = { false }
                        },
                    )
                }
            }

            val response =
                client.post("/api/$testProjectId/store/") {
                    contentType(ContentType.Application.Json)
                    header("X-Sentry-Auth", "Sentry sentry_key=$testPublicKey")
                    setBody("""{"event_id":"bad-store"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("Invalid event format", response.bodyAsText())
        }

    @Test
    fun `envelope endpoint returns 429 when quota reservation rejects`() =
        testApplication {
            environment {
                config = MapApplicationConfig("ingest.queueKey" to "test:ingest:q")
            }
            application {
                install(ContentNegotiation) { json() }
                routing {
                    ingestRoutes(
                        dependencies = IngestRouteDependencies().apply {
                            enqueueEnvelope = { _, _ -> }
                            isQuotaEnforcementEnabled = { true }
                            reserveEnvelopeQuota = { orgId, _, _ ->
                                QuotaReservationResult(
                                    allowed = false,
                                    reason = "quota_exceeded",
                                    usage = emptyUsage(orgId)
                                )
                            }
                        },
                    )
                }
            }

            val response =
                client.post("/api/$testProjectId/envelope/") {
                    contentType(ContentType.Application.OctetStream)
                    header("X-Sentry-Auth", "Sentry sentry_key=$testPublicKey, sentry_version=7")
                    setBody(
                        buildEnvelope(
                            eventId = "evt-over-quota",
                            items = listOf(
                                "transaction" to """{"event_id":"txn-2","type":"transaction"}""".toByteArray()
                            )
                        )
                    )
                }

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertTrue(response.bodyAsText().contains("Quota exceeded"))
        }

    @Test
    fun `envelope endpoint returns bad request for malformed envelope payload`() =
        testApplication {
            environment {
                config = MapApplicationConfig("ingest.queueKey" to "test:ingest:q")
            }
            application {
                install(ContentNegotiation) { json() }
                routing {
                    ingestRoutes(
                        dependencies = IngestRouteDependencies().apply {
                            enqueueEnvelope = { _, _ -> }
                        }
                    )
                }
            }

            val response =
                client.post("/api/$testProjectId/envelope/") {
                    contentType(ContentType.Application.OctetStream)
                    header("X-Sentry-Auth", "Sentry sentry_key=$testPublicKey, sentry_version=7")
                    setBody("not-a-valid-envelope".toByteArray())
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid envelope format"))
        }

    @Test
    fun `envelope item type mapping covers transaction and fallback types`() {
        assertEquals("transaction", mapEnvelopeItemTypeToQuotaType("transaction"))
        assertEquals("replay", mapEnvelopeItemTypeToQuotaType("replay_video"))
        assertEquals("feedback", mapEnvelopeItemTypeToQuotaType("feedback"))
        assertEquals("feedback", mapEnvelopeItemTypeToQuotaType("user_report"))
        assertEquals("llm", mapEnvelopeItemTypeToQuotaType("llm_generation"))
        assertEquals("session", mapEnvelopeItemTypeToQuotaType("session"))
        assertEquals("session", mapEnvelopeItemTypeToQuotaType("sessions"))
        assertEquals("error", mapEnvelopeItemTypeToQuotaType("check_in"))
        assertEquals("error", mapEnvelopeItemTypeToQuotaType("unknown_type"))
    }

    @Test
    fun `envelope item quota mapping detects feedback event payloads`() {
        val feedbackPayload =
            """
            {
                "event_id": "44bad9a2e3774046977a21440ddb39b2",
                "type": "feedback",
                "contexts": {"feedback": {"message": "Great app"}}
            }
            """.trimIndent()

        assertEquals("feedback", mapEnvelopeItemToQuotaType(EnvelopeItem("event", feedbackPayload)))
        assertEquals("error", mapEnvelopeItemToQuotaType(EnvelopeItem("event", """{"message":"User Feedback"}""")))
    }

    private fun buildEnvelope(
        eventId: String,
        items: List<Pair<String, ByteArray>>
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("""{"event_id":"$eventId"}""".toByteArray())
        out.write('\n'.code)
        for ((type, payload) in items) {
            out.write("""{"type":"$type","length":${payload.size}}""".toByteArray())
            out.write('\n'.code)
            out.write(payload)
            out.write('\n'.code)
        }
        return out.toByteArray()
    }

    private fun gzip(payload: String): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            gzip.write(payload.toByteArray())
        }
        return output.toByteArray()
    }

    private fun emptyUsage(organizationId: Int): BillingUsageResponse {
        return BillingUsageResponse(
            organizationId = organizationId,
            periodStart = "2026-01-01",
            periodEnd = "2026-01-31",
            retentionDays = 30,
            apmTraceRetentionDays = 30,
            usedUnits = 0,
            usedErrors = 0,
            errorLimit = 1000,
            usedTransactions = 0,
            transactionLimit = 1000,
            usedReplays = 0,
            replayLimit = 1000,
            usedFeedback = 0,
            feedbackLimit = 1000,
            usedBytes = 0,
            bytesLimit = 1_073_741_824,
            baseLimitUnits = 1000,
            paygLimitUnits = 0,
            totalLimitUnits = 1000,
            paygBudgetCents = 0,
            paygUsedUnits = 0,
            paygUsedCentsEstimate = 0,
            plan = "pro",
            status = "active",
            withinQuota = true
        )
    }

    @AfterTest
    fun teardownKoin() {
        stopTestKoin()
    }
}
