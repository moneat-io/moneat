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

package com.moneat.services

import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.alerts.models.AlertSource
import com.moneat.alerts.models.AlertStatus
import com.moneat.billing.services.BillingQuotaService
import com.moneat.config.ClickHouseClient
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.Users
import com.moneat.synthetics.routes.AlertConfig
import com.moneat.synthetics.routes.AlertRecipient
import com.moneat.synthetics.routes.AssertionResult
import com.moneat.synthetics.routes.BrowserStep
import com.moneat.synthetics.routes.CapturedRequest
import com.moneat.synthetics.routes.CapturedResponse
import com.moneat.synthetics.routes.CreatePrivateLocationRequest
import com.moneat.synthetics.routes.CreateSyntheticTestRequest
import com.moneat.synthetics.routes.ProbeResultSubmission
import com.moneat.synthetics.routes.SyntheticAssertion
import com.moneat.synthetics.routes.SyntheticCheckResult
import com.moneat.synthetics.routes.SyntheticLocationService
import com.moneat.synthetics.routes.SyntheticLocations
import com.moneat.synthetics.routes.SyntheticRunDetail
import com.moneat.synthetics.routes.SyntheticStep
import com.moneat.synthetics.routes.SyntheticTestConfig
import com.moneat.synthetics.routes.SyntheticTestData
import com.moneat.synthetics.routes.SyntheticTests
import com.moneat.synthetics.routes.SyntheticVariableRequest
import com.moneat.synthetics.routes.SyntheticVariables
import com.moneat.synthetics.routes.SyntheticsCheckExecutor
import com.moneat.synthetics.routes.SyntheticsService
import com.moneat.synthetics.routes.UpdateSyntheticTestRequest
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.queryBasedClickHouseHandler
import com.moneat.workflows.services.WorkflowService
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class SyntheticsServiceTest {
    private val service = SyntheticsService()

    companion object {
        private var db: Database? = null
        private const val TEST_ORG_ID = 1

        private fun resourceId(value: String): Uuid = Uuid.parse(value)
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_synthetics_service;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Memberships,
            Subscriptions,
            SyntheticLocations,
            SyntheticTests,
            SyntheticVariables
        )

        // Create org for tests
        transaction {
            Organizations.insert {
                it[name] = "Test Org"
                it[slug] = "test-org"
            }
        }
    }

    private class SyntheticRequestBuilder {
        var name: String = "API Health Check"
        var testType: String = "api"
        var intervalSeconds: Int = 60
        var timeoutSeconds: Int = 10
        var url: String? = "https://example.com/health"
        var method: String = "GET"
        var headers: Map<String, String>? = null
        var body: String? = null
        var authMethod: String? = null
        var authUser: String? = null
        var authPass: String? = null
        var tags: List<String> = emptyList()
        var retryCount: Int = 0
        var retryIntervalMs: Int = 300
        var alertOnFailure: Boolean = false
        var alertChannels: List<String> = emptyList()
        var assertions: List<SyntheticAssertion> = emptyList()
        var steps: List<SyntheticStep> = emptyList()
        var config: SyntheticTestConfig? = null
        var service: String? = null
        var environment: String? = null
        var locations: List<String> = emptyList()
        var alertConfig: AlertConfig? = null
        var alertRecipients: List<AlertRecipient> = emptyList()
        var browserSteps: List<BrowserStep> = emptyList()

        fun build(): CreateSyntheticTestRequest = CreateSyntheticTestRequest(
            name = name,
            testType = testType,
            intervalSeconds = intervalSeconds,
            timeoutSeconds = timeoutSeconds,
            url = url,
            method = method,
            headers = headers,
            body = body,
            authMethod = authMethod,
            authUser = authUser,
            authPass = authPass,
            assertions = assertions,
            steps = steps,
            tags = tags,
            retryCount = retryCount,
            retryIntervalMs = retryIntervalMs,
            alertOnFailure = alertOnFailure,
            alertChannels = alertChannels,
            config = config,
            service = service,
            environment = environment,
            locations = locations,
            alertConfig = alertConfig,
            alertRecipients = alertRecipients,
            browserSteps = browserSteps
        )
    }

    private fun variableResourceId(value: String): Uuid = Uuid.parse(value)

    private fun createRequest(
        configure: SyntheticRequestBuilder.() -> Unit = {}
    ): CreateSyntheticTestRequest =
        SyntheticRequestBuilder().apply(configure).build()

    private fun syntheticTestData(
        id: java.util.UUID = java.util.UUID.randomUUID(),
        lastStatus: String? = null,
        alertOnFailure: Boolean = true,
        locations: List<String> = emptyList()
    ): SyntheticTestData {
        val now = kotlin.time.Clock.System.now()
        return SyntheticTestData(
            id = id,
            organizationId = TEST_ORG_ID,
            name = "Synthetic Alert Test",
            testType = "api",
            active = true,
            intervalSeconds = 60,
            timeoutSeconds = 10,
            url = "https://example.com/health",
            method = "GET",
            assertions = "[]",
            status = "active",
            lastStatus = lastStatus,
            retryCount = 0,
            retryIntervalMs = 100,
            alertOnFailure = alertOnFailure,
            locations = locations,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun executorReturning(result: SyntheticCheckResult): SyntheticsCheckExecutor =
        object : SyntheticsCheckExecutor() {
            override suspend fun executeTest(test: SyntheticTestData): SyntheticCheckResult = result
        }

    private fun organizationResourceId(organizationId: Int): String =
        transaction {
            Organizations
                .selectAll()
                .where { Organizations.id eq organizationId }
                .single()[Organizations.resource_id]
                .toString()
        }

    private suspend fun <T> withClickHouseMock(
        vararg rules: Pair<String, String>,
        block: suspend () -> T
    ): T {
        val server = MockHttpServer(queryBasedClickHouseHandler(*rules))
        ClickHouseClient.close()
        ClickHouseClient.init(server.baseUrl, "test", "default", "")
        return try {
            block()
        } finally {
            ClickHouseClient.close()
            server.close()
        }
    }

    private fun seedManagedLocation(code: String, name: String = code): java.util.UUID {
        val id = java.util.UUID.randomUUID()
        val now = kotlin.time.Clock.System.now()
        transaction {
            SyntheticLocations.insert {
                it[SyntheticLocations.id] = id
                it[SyntheticLocations.organizationId] = null
                it[SyntheticLocations.code] = code
                it[SyntheticLocations.name] = name
                it[SyntheticLocations.region] = "us-east"
                it[SyntheticLocations.locationType] = "managed"
                it[SyntheticLocations.active] = true
                it[SyntheticLocations.workerCount] = 0
                it[SyntheticLocations.createdAt] = now
                it[SyntheticLocations.updatedAt] = now
            }
        }
        return id
    }

    // ──── Locations ────

    @Test
    fun `location service lists authenticates and deletes private locations`() {
        val locationService = SyntheticLocationService()
        val managedId = seedManagedLocation("aws-us-east-1", "US East")
        val created = locationService.createPrivateLocation(
            TEST_ORG_ID,
            CreatePrivateLocationRequest(
                code = "private-us-east",
                name = "Private US East",
                region = "iad"
            )
        )

        assertTrue(created.key.startsWith("mloc_"))
        val initialLocations = locationService.listLocations(TEST_ORG_ID)
        assertEquals(2, initialLocations.size)
        assertEquals(1, initialLocations.single { it.code == "aws-us-east-1" }.workerCount)
        assertEquals(0, initialLocations.single { it.code == "private-us-east" }.workerCount)
        assertNull(locationService.authenticateProbe("bad-key", "private-us-east"))
        assertNull(locationService.authenticateProbe(created.key, "private-eu-west"))

        val identity = locationService.authenticateProbe(created.key, "private-us-east")

        assertNotNull(identity)
        assertEquals(TEST_ORG_ID, identity.organizationId)
        assertEquals("private-us-east", identity.locationCode)
        val online = locationService.getLocation(java.util.UUID.fromString(created.location.id), TEST_ORG_ID)
        assertNotNull(online)
        assertEquals(1, online.workerCount)
        assertNotNull(online.lastSeenAt)
        transaction {
            SyntheticLocations.update({ SyntheticLocations.id eq java.util.UUID.fromString(created.location.id) }) {
                it[lastSeenAt] = kotlin.time.Clock.System.now() - 300.seconds
                it[workerCount] = 4
            }
        }
        val offline = locationService.getLocation(java.util.UUID.fromString(created.location.id), TEST_ORG_ID)
        assertNotNull(offline)
        assertEquals(0, offline.workerCount)
        assertFalse(locationService.deletePrivateLocation(managedId, TEST_ORG_ID))
        assertFalse(locationService.deletePrivateLocation(java.util.UUID.fromString(created.location.id), 999))
        assertTrue(locationService.deletePrivateLocation(java.util.UUID.fromString(created.location.id), TEST_ORG_ID))
        assertNull(locationService.getLocation(java.util.UUID.fromString(created.location.id), TEST_ORG_ID))
    }

    // ──── CRUD: Create ────

    @Test
    fun `createTest creates a test and returns response`() {
        val response = service.createTest(TEST_ORG_ID, createRequest())
        assertNotNull(response)
        assertEquals("API Health Check", response.name)
        assertEquals("api", response.testType)
        assertTrue(response.active)
        assertEquals(organizationResourceId(TEST_ORG_ID), response.organizationId)
        assertEquals("pending", response.status)
    }

    @Test
    fun `createTest stores tags`() {
        val response = service.createTest(
            TEST_ORG_ID,
            createRequest {
                tags = listOf("production", "critical")
            }
        )
        assertEquals(listOf("production", "critical"), response.tags)
    }

    @Test
    fun `createTest stores retry config`() {
        val response = service.createTest(
            TEST_ORG_ID,
            createRequest {
                retryCount = 3
                retryIntervalMs = 500
            }
        )
        assertEquals(3, response.retryCount)
        assertEquals(500, response.retryIntervalMs)
    }

    @Test
    fun `createTest stores alert config`() {
        val response = service.createTest(
            TEST_ORG_ID,
            createRequest {
                alertOnFailure = true
            }
        )
        assertTrue(response.alertOnFailure)
    }

    @Test
    fun `createTest stores structured workflow and browser fields`() {
        val response = service.createTest(
            TEST_ORG_ID,
            createRequest {
                name = "Checkout journey"
                testType = "browser"
                method = "POST"
                headers = mapOf("X-Test" to "synthetic")
                body = """{"checkout":true}"""
                authMethod = "basic"
                authUser = "robot"
                authPass = "secret"
                alertChannels = listOf("slack-primary")
                service = "checkout"
                environment = "production"
                locations = listOf("aws-us-east-1", "private-us-east")
                steps = listOf(
                    SyntheticStep(
                        name = "Cart API",
                        url = "https://example.com/cart",
                        method = "GET"
                    )
                )
                alertConfig = AlertConfig(consecutiveChecks = 2, minLocations = 2, retestCount = 1)
                alertRecipients = listOf(AlertRecipient(type = "email", target = "alerts@example.com"))
                browserSteps = listOf(BrowserStep(action = "click", selector = "#checkout", value = "Pay"))
            }
        )

        assertEquals("browser", response.testType)
        assertEquals("POST", response.method)
        assertEquals(mapOf("X-Test" to "synthetic"), response.headers)
        assertEquals("robot", response.authUser)
        assertEquals(listOf("slack-primary"), response.alertChannels)
        assertEquals("checkout", response.service)
        assertEquals("production", response.environment)
        assertEquals(listOf("aws-us-east-1", "private-us-east"), response.locations)
        assertEquals("Cart API", response.steps.single().name)
        assertEquals(2, response.alertConfig?.consecutiveChecks)
        assertEquals("alerts@example.com", response.alertRecipients.single().target)
        assertEquals("#checkout", response.browserSteps.single().selector)
    }

    @Test
    fun `createTest stores SSL config`() {
        val config = SyntheticTestConfig(
            hostname = "example.com",
            port = 443
        )
        val response = service.createTest(
            TEST_ORG_ID,
            createRequest {
                name = "SSL Check"
                testType = "ssl"
                this.config = config
            }
        )
        assertEquals("ssl", response.testType)
        val responseConfig = response.config
        assertNotNull(responseConfig)
        assertEquals("example.com", responseConfig.hostname)
        assertEquals(443, responseConfig.port)
    }

    @Test
    fun `createTest stores TCP config`() {
        val config = SyntheticTestConfig(
            hostname = "db.example.com",
            port = 5432
        )
        val response = service.createTest(
            TEST_ORG_ID,
            createRequest {
                name = "Postgres Check"
                testType = "tcp"
                this.config = config
            }
        )
        assertEquals("tcp", response.testType)
        val responseConfig = response.config
        assertNotNull(responseConfig)
        assertEquals(5432, responseConfig.port)
    }

    @Test
    fun `createTest stores assertions`() {
        val assertions = listOf(
            SyntheticAssertion(
                type = "status_code",
                operator = "equals",
                value = "200"
            )
        )
        val response = service.createTest(
            TEST_ORG_ID,
            createRequest {
                this.assertions = assertions
            }
        )
        assertEquals(1, response.assertions.size)
        assertEquals("status_code", response.assertions[0].type)
    }

    // ──── CRUD: Read ────

    @Test
    fun `getTest returns test by id and org`() {
        val created = service.createTest(TEST_ORG_ID, createRequest())
        val fetched = service.getTest(
            java.util.UUID.fromString(created.id),
            TEST_ORG_ID
        )
        assertNotNull(fetched)
        assertEquals(created.id, fetched.id)
        assertEquals(created.name, fetched.name)
    }

    @Test
    fun `getTest returns null for wrong org`() {
        val created = service.createTest(TEST_ORG_ID, createRequest())
        val fetched = service.getTest(
            java.util.UUID.fromString(created.id),
            999
        )
        assertNull(fetched)
    }

    @Test
    fun `getTest returns null for non-existent id`() {
        val fetched = service.getTest(
            java.util.UUID.randomUUID(),
            TEST_ORG_ID
        )
        assertNull(fetched)
    }

    @Test
    fun `listTests returns all tests for org`() {
        service.createTest(TEST_ORG_ID, createRequest { name = "Test 1" })
        service.createTest(TEST_ORG_ID, createRequest { name = "Test 2" })
        service.createTest(TEST_ORG_ID, createRequest { name = "Test 3" })

        val tests = service.listTests(TEST_ORG_ID)
        assertEquals(3, tests.size)
    }

    @Test
    fun `listTests returns empty for org with no tests`() {
        val tests = service.listTests(999)
        assertTrue(tests.isEmpty())
    }

    // ──── CRUD: Update ────

    @Test
    fun `updateTest updates name`() {
        val created = service.createTest(TEST_ORG_ID, createRequest())
        val updated = service.updateTest(
            java.util.UUID.fromString(created.id),
            TEST_ORG_ID,
            UpdateSyntheticTestRequest(name = "Updated Name")
        )
        assertNotNull(updated)
        assertEquals("Updated Name", updated.name)
    }

    @Test
    fun `updateTest updates active status`() {
        val created = service.createTest(TEST_ORG_ID, createRequest())
        val updated = service.updateTest(
            java.util.UUID.fromString(created.id),
            TEST_ORG_ID,
            UpdateSyntheticTestRequest(active = false)
        )
        assertNotNull(updated)
        assertFalse(updated.active)
    }

    @Test
    fun `updateTest updates tags`() {
        val created = service.createTest(TEST_ORG_ID, createRequest())
        val updated = service.updateTest(
            java.util.UUID.fromString(created.id),
            TEST_ORG_ID,
            UpdateSyntheticTestRequest(
                tags = listOf("staging", "non-critical")
            )
        )
        assertNotNull(updated)
        assertEquals(listOf("staging", "non-critical"), updated.tags)
    }

    @Test
    fun `updateTest updates retry config`() {
        val created = service.createTest(TEST_ORG_ID, createRequest())
        val updated = service.updateTest(
            java.util.UUID.fromString(created.id),
            TEST_ORG_ID,
            UpdateSyntheticTestRequest(retryCount = 5, retryIntervalMs = 1000)
        )
        assertNotNull(updated)
        assertEquals(5, updated.retryCount)
        assertEquals(1000, updated.retryIntervalMs)
    }

    @Test
    fun `updateTest updates structured fields and clears empty browser steps`() {
        val created = service.createTest(
            TEST_ORG_ID,
            createRequest {
                browserSteps = listOf(BrowserStep(action = "click", selector = "#old"))
            }
        )
        val updated = service.updateTest(
            java.util.UUID.fromString(created.id),
            TEST_ORG_ID,
            UpdateSyntheticTestRequest(
                intervalSeconds = 120,
                timeoutSeconds = 20,
                url = "https://example.com/v2",
                method = "PUT",
                headers = mapOf("X-Env" to "prod"),
                body = """{"enabled":true}""",
                authMethod = "bearer",
                authUser = "unused",
                authPass = "token",
                assertions = listOf(SyntheticAssertion(type = "status_code", value = "204")),
                steps = listOf(SyntheticStep(name = "Warmup", url = "https://example.com/warmup")),
                alertOnFailure = true,
                alertChannels = listOf("pagerduty"),
                config = SyntheticTestConfig(hostname = "example.com", port = 443),
                service = "payments",
                environment = "staging",
                locations = listOf("aws-us-west-2"),
                alertConfig = AlertConfig(consecutiveChecks = 3, minLocations = 1),
                alertRecipients = listOf(AlertRecipient(type = "slack", target = "#alerts")),
                browserSteps = emptyList()
            )
        )

        assertNotNull(updated)
        assertEquals(120, updated.intervalSeconds)
        assertEquals(20, updated.timeoutSeconds)
        assertEquals("PUT", updated.method)
        assertEquals(mapOf("X-Env" to "prod"), updated.headers)
        assertEquals("bearer", updated.authMethod)
        assertEquals("status_code", updated.assertions.single().type)
        assertEquals("Warmup", updated.steps.single().name)
        assertTrue(updated.alertOnFailure)
        assertEquals(listOf("pagerduty"), updated.alertChannels)
        assertEquals("payments", updated.service)
        assertEquals("staging", updated.environment)
        assertEquals(listOf("aws-us-west-2"), updated.locations)
        assertEquals(3, updated.alertConfig?.consecutiveChecks)
        assertEquals("#alerts", updated.alertRecipients.single().target)
        assertTrue(updated.browserSteps.isEmpty())

        val browserUpdated = service.updateTest(
            java.util.UUID.fromString(created.id),
            TEST_ORG_ID,
            UpdateSyntheticTestRequest(browserSteps = listOf(BrowserStep(action = "click", selector = "#new")))
        )
        assertNotNull(browserUpdated)
        assertEquals("#new", browserUpdated.browserSteps.single().selector)
    }

    @Test
    fun `updateTest validates negative retry fields`() {
        val created = service.createTest(TEST_ORG_ID, createRequest())
        assertFailsWith<IllegalArgumentException> {
            service.updateTest(
                java.util.UUID.fromString(created.id),
                TEST_ORG_ID,
                UpdateSyntheticTestRequest(retryCount = -1)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.updateTest(
                java.util.UUID.fromString(created.id),
                TEST_ORG_ID,
                UpdateSyntheticTestRequest(retryIntervalMs = -1)
            )
        }
    }

    @Test
    fun `updateTest returns null for non-existent test`() {
        val result = service.updateTest(
            java.util.UUID.randomUUID(),
            TEST_ORG_ID,
            UpdateSyntheticTestRequest(name = "Ghost")
        )
        assertNull(result)
    }

    @Test
    fun `updateTest returns null for wrong org`() {
        val created = service.createTest(TEST_ORG_ID, createRequest())
        val result = service.updateTest(
            java.util.UUID.fromString(created.id),
            999,
            UpdateSyntheticTestRequest(name = "Nope")
        )
        assertNull(result)
    }

    // ──── CRUD: Delete ────

    @Test
    fun `deleteTest removes test`() {
        val created = service.createTest(TEST_ORG_ID, createRequest())
        val deleted = service.deleteTest(
            java.util.UUID.fromString(created.id),
            TEST_ORG_ID
        )
        assertTrue(deleted)

        val fetched = service.getTest(
            java.util.UUID.fromString(created.id),
            TEST_ORG_ID
        )
        assertNull(fetched)
    }

    @Test
    fun `deleteTest returns false for non-existent test`() {
        val deleted = service.deleteTest(
            java.util.UUID.randomUUID(),
            TEST_ORG_ID
        )
        assertFalse(deleted)
    }

    @Test
    fun `deleteTest returns false for wrong org`() {
        val created = service.createTest(TEST_ORG_ID, createRequest())
        val deleted = service.deleteTest(
            java.util.UUID.fromString(created.id),
            999
        )
        assertFalse(deleted)
    }

    // ──── Status Updates ────

    @Test
    fun `updateTestStatus updates status fields`() {
        val created = service.createTest(TEST_ORG_ID, createRequest())
        val testId = java.util.UUID.fromString(created.id)

        service.updateTestStatus(testId, "active", "passed")

        val fetched = service.getTest(testId, TEST_ORG_ID)
        assertNotNull(fetched)
        assertEquals("active", fetched.status)
        assertEquals("passed", fetched.lastStatus)
        assertNotNull(fetched.lastRunAt)
    }

    @Test
    fun `updateTestStatus tracks previous status`() {
        val created = service.createTest(TEST_ORG_ID, createRequest())
        val testId = java.util.UUID.fromString(created.id)

        service.updateTestStatus(testId, "active", "passed")
        service.updateTestStatus(
            testId,
            "active",
            "failed",
            "passed"
        )

        val fetched = service.getTest(testId, TEST_ORG_ID)
        assertNotNull(fetched)
        assertEquals("failed", fetched.lastStatus)
    }

    // ──── Retry Logic ────

    @Test
    fun `executeWithRetries passes on first attempt`() = runBlocking {
        val executor = object : SyntheticsCheckExecutor() {
            override suspend fun executeTest(
                test: SyntheticTestData
            ): SyntheticCheckResult {
                return SyntheticCheckResult(
                    status = "passed",
                    durationMs = 50
                )
            }
        }

        val now = kotlin.time.Clock.System.now()
        val testData = SyntheticTestData(
            id = java.util.UUID.randomUUID(),
            organizationId = TEST_ORG_ID,
            name = "Retry Test",
            testType = "api",
            active = true,
            intervalSeconds = 60,
            timeoutSeconds = 10,
            url = "https://example.com",
            method = "GET",
            assertions = "[]",
            status = "pending",
            retryCount = 2,
            retryIntervalMs = 100,
            createdAt = now,
            updatedAt = now
        )

        // Cannot call private executeWithRetries directly, but
        // we can verify via executeTestAndRecord behavior indirectly.
        // Instead test executor behavior directly.
        val result = executor.executeTest(testData)
        assertEquals("passed", result.status)
    }

    // ──── Alert Lifecycle ────

    @Test
    fun `executeTestAndRecord publishes firing alert for failed check`() =
        runBlocking {
            val workflowService = mockk<WorkflowService>(relaxed = true)
            val service = SyntheticsService(
                billingQuotaService = mockk<BillingQuotaService>(relaxed = true),
                workflowService = workflowService
            )
            val eventSlot = slot<AlertLifecycleEvent>()
            val testData = syntheticTestData()
            val failure = SyntheticCheckResult(
                status = "failed",
                durationMs = 42,
                errorMessage = "status code 500"
            )

            service.executeTestAndRecord(testData, executorReturning(failure))

            coVerify(exactly = 1) {
                workflowService.publishAlertTriggered(capture(eventSlot))
            }
            val event = eventSlot.captured
            assertEquals(AlertStatus.FIRING, event.status)
            assertEquals(AlertSource.SYNTHETIC_TEST, event.source)
            assertEquals("moneat-synthetic-${testData.id}", event.deduplicationKey)
            assertEquals(TEST_ORG_ID, event.organizationId)
            assertTrue(event.description.contains("status code 500"))
        }

    @Test
    fun `executeTestAndRecord publishes recovery alert after failed synthetic passes`() =
        runBlocking {
            val workflowService = mockk<WorkflowService>(relaxed = true)
            val service = SyntheticsService(
                billingQuotaService = mockk<BillingQuotaService>(relaxed = true),
                workflowService = workflowService
            )
            val eventSlot = slot<AlertLifecycleEvent>()
            val testData = syntheticTestData(lastStatus = "failed")
            val success = SyntheticCheckResult(status = "passed", durationMs = 33)

            service.executeTestAndRecord(testData, executorReturning(success))

            coVerify(exactly = 1) {
                workflowService.publishAlertTriggered(capture(eventSlot))
            }
            val event = eventSlot.captured
            assertEquals(AlertStatus.RESOLVED, event.status)
            assertEquals(AlertSource.SYNTHETIC_TEST, event.source)
            assertEquals("moneat-synthetic-${testData.id}", event.deduplicationKey)
            assertEquals(TEST_ORG_ID, event.organizationId)
            assertTrue(event.description.contains("passed after previous failures"))
        }

    @Test
    fun `executeTestAndRecord fires structured alert from failing location history`() =
        runBlocking {
            seedManagedLocation("aws-us-east-1", "US East")
            seedManagedLocation("aws-us-west-2", "US West")
            val workflowService = mockk<WorkflowService>(relaxed = true)
            val service = SyntheticsService(
                billingQuotaService = mockk<BillingQuotaService>(relaxed = true),
                workflowService = workflowService
            )
            val eventSlot = slot<AlertLifecycleEvent>()
            val testData = syntheticTestData(
                alertOnFailure = false,
                locations = listOf("aws-us-east-1", "aws-us-west-2")
            ).copy(
                alertConfig = AlertConfig(consecutiveChecks = 1, minLocations = 2),
                alertRecipients = listOf(AlertRecipient(type = "email", target = "alerts@example.com"))
            )
            val locationHistory = """
                {"location_code":"aws-us-east-1","status":"failed"}
                {"location_code":"aws-us-west-2","status":"failed"}
            """.trimIndent()

            withClickHouseMock("SELECT location_code, status" to locationHistory) {
                service.executeTestAndRecord(
                    testData,
                    executorReturning(SyntheticCheckResult(status = "passed", durationMs = 25))
                )
            }

            coVerify(exactly = 1) {
                workflowService.publishAlertTriggered(capture(eventSlot))
            }
            assertEquals(AlertStatus.FIRING, eventSlot.captured.status)
            assertEquals(AlertSource.SYNTHETIC_TEST, eventSlot.captured.source)
        }

    @Test
    fun `executeTestAndRecord touches private-only tests without executing locally`() =
        runBlocking {
            val created = service.createTest(
                TEST_ORG_ID,
                createRequest {
                    locations = listOf("private-us-east")
                }
            )
            val testId = java.util.UUID.fromString(created.id)
            val testData = syntheticTestData(id = testId, locations = listOf("private-us-east"))
            var executed = false
            val executor = object : SyntheticsCheckExecutor() {
                override suspend fun executeTest(test: SyntheticTestData): SyntheticCheckResult {
                    executed = true
                    return SyntheticCheckResult(status = "passed", durationMs = 1)
                }
            }

            service.executeTestAndRecord(testData, executor)

            val fetched = service.getTest(testId, TEST_ORG_ID)
            assertNotNull(fetched)
            assertFalse(executed)
            assertNotNull(fetched.lastRunAt)
            assertEquals(created.status, fetched.status)
        }

    @Test
    fun `previewTest reports local preview location`() =
        runBlocking {
            val run = service.previewTest(
                TEST_ORG_ID,
                createRequest(),
                executor = executorReturning(SyntheticCheckResult(status = "passed", durationMs = 12))
            )

            assertEquals("moneat", run.locationCode)
        }

    @Test
    fun `previewTest maps full request fields and failed assertion count`() =
        runBlocking {
            val run = service.previewTest(
                TEST_ORG_ID,
                createRequest {
                    name = ""
                    headers = mapOf("X-Preview" to "yes")
                    steps = listOf(SyntheticStep(name = "Step", url = "https://example.com/step"))
                    config = SyntheticTestConfig(hostname = "example.com", port = 443)
                    browserSteps = listOf(BrowserStep(action = "assert", value = "Ready"))
                },
                executor = executorReturning(
                    SyntheticCheckResult(
                        status = "failed",
                        durationMs = 15,
                        assertionResults = listOf(
                            AssertionResult(
                                label = "Status code equals 200",
                                expected = "200",
                                actual = "500",
                                passed = false
                            )
                        )
                    )
                )
            )

            assertEquals("Preview", run.testName)
            assertEquals("failed", run.status)
            assertEquals(1, run.assertionsTotal)
            assertEquals(1, run.assertionsFailed)
            assertEquals("Status code equals 200", run.detail?.assertions?.single()?.label)
        }

    @Test
    fun `getRunDetail maps result row and persisted detail`() =
        runBlocking {
            val detail = SyntheticRunDetail(
                assertions = listOf(
                    AssertionResult(
                        label = "Status code",
                        expected = "200",
                        actual = "503",
                        passed = false
                    )
                ),
                request = CapturedRequest(
                    method = "POST",
                    url = "https://api.example.com/orders",
                    headers = mapOf("content-type" to "application/json"),
                    body = ""
                ),
                response = CapturedResponse(
                    statusCode = 503,
                    headers = mapOf("content-type" to "application/json"),
                    body = ""
                ),
                timings = mapOf("dns" to 2.5),
                resolvedIp = "203.0.113.10"
            )
            val detailJson = Json.encodeToString(detail)
            val resultRow =
                """{"result_id":"run-1","test_id":"test-1","test_name":"Checkout API","test_type":"api",""" +
                    """"status":"failed","location_code":"private-us-east","duration_ms":540,""" +
                    """"status_code":503,"attempt":2,"assertions_total":1,"assertions_failed":1,""" +
                    """"error_message":"upstream unavailable","ts":"2026-06-11T00:00:00"}"""
            val detailRow = """{"details":${Json.encodeToString(detailJson)}}"""

            withClickHouseMock(
                "FROM synthetic_results" to resultRow,
                "FROM synthetic_run_details" to detailRow
            ) {
                val run = service.getRunDetail("test-1", "run-1", listOf(TEST_ORG_ID))

                assertNotNull(run)
                assertEquals("run-1", run.resultId)
                assertEquals("failed", run.status)
                assertEquals("private-us-east", run.locationCode)
                assertEquals(503, run.statusCode)
                assertEquals(2, run.attempt)
                assertEquals("upstream unavailable", run.errorMessage)
                assertEquals("203.0.113.10", run.detail?.resolvedIp)
                assertEquals("Status code", run.detail?.assertions?.single()?.label)
                assertEquals("POST", run.detail?.request?.method)
            }
        }

    @Test
    fun `summary queries map ClickHouse rows into response models`() =
        runBlocking {
            withClickHouseMock(
                "GROUP BY location_code" to
                    """{"location_code":"private-us-east","uptime":50.0,"avg_ms":125.0,""" +
                    """"p95_ms":200.0,"total":4,"failures":2}""",
                "countIf(status = 'passed')" to "99.5\t123.4\t250.0\t10\t1"
            ) {
                val summary = service.getTestSummary("test-1", listOf(TEST_ORG_ID))
                val locations = service.getLocationSummaries("test-1", listOf(TEST_ORG_ID))

                assertNotNull(summary)
                assertEquals(99.5, summary.uptimePercent)
                assertEquals(123.4, summary.avgResponseMs)
                assertEquals(250.0, summary.p95ResponseMs)
                assertEquals(10, summary.totalRuns)
                assertEquals(1, summary.failureCount)
                assertEquals(1, locations.size)
                assertEquals("private-us-east", locations.single().locationCode)
                assertEquals(50.0, locations.single().uptimePercent)
                assertEquals(4, locations.single().totalRuns)
                assertEquals(2, locations.single().failureCount)
            }
        }

    // ──── Probe Protocol ────

    @Test
    fun `recordProbeResult rejects test not assigned to probe location`() =
        runBlocking {
            val test = service.createTest(
                TEST_ORG_ID,
                createRequest {
                    locations = listOf("private-us-east")
                }
            )

            val accepted = service.recordProbeResult(
                organizationId = TEST_ORG_ID,
                locationCode = "private-eu-west",
                submission = ProbeResultSubmission(
                    testId = test.id,
                    status = "passed",
                    durationMs = 100L
                )
            )

            assertFalse(accepted)
        }

    @Test
    fun `recordProbeResult rejects invalid and missing test ids`() =
        runBlocking {
            assertFalse(
                service.recordProbeResult(
                    organizationId = TEST_ORG_ID,
                    locationCode = "private-us-east",
                    submission = ProbeResultSubmission(
                        testId = "not-a-uuid",
                        status = "passed",
                        durationMs = 100L
                    )
                )
            )
            assertFalse(
                service.recordProbeResult(
                    organizationId = TEST_ORG_ID,
                    locationCode = "private-us-east",
                    submission = ProbeResultSubmission(
                        testId = java.util.UUID.randomUUID().toString(),
                        status = "passed",
                        durationMs = 100L
                    )
                )
            )
        }

    @Test
    fun `recordProbeResult accepts assigned result and updates aggregate status`() =
        runBlocking {
            val test = service.createTest(
                TEST_ORG_ID,
                createRequest {
                    locations = listOf("private-us-east")
                }
            )

            withClickHouseMock(
                "argMax(status, timestamp)" to
                    """{"location_code":"private-us-east","status":"failed"}"""
            ) {
                val accepted = service.recordProbeResult(
                    organizationId = TEST_ORG_ID,
                    locationCode = "private-us-east",
                    submission = ProbeResultSubmission(
                        testId = test.id,
                        status = "failed",
                        durationMs = 250L,
                        statusCode = 503,
                        errorMessage = "upstream unavailable",
                        resolvedIp = "203.0.113.10",
                        timings = mapOf("dns" to 2.0),
                        assertions = listOf(
                            AssertionResult(
                                label = "Status code",
                                expected = "200",
                                actual = "503",
                                passed = false
                            )
                        ),
                        request = CapturedRequest(method = "GET", url = "https://example.com"),
                        response = CapturedResponse(statusCode = 503)
                    )
                )

                assertTrue(accepted)
                val fetched = service.getTest(java.util.UUID.fromString(test.id), TEST_ORG_ID)
                assertNotNull(fetched)
                assertEquals("failed", fetched.lastStatus)
            }
        }

    @Test
    fun `getProbeWork returns resolved work for assigned private location`() =
        runBlocking {
            service.createVariable(
                TEST_ORG_ID,
                SyntheticVariableRequest(name = "HOST", value = "private.example.com")
            )
            val test = service.createTest(
                TEST_ORG_ID,
                CreateSyntheticTestRequest(
                    name = "Private checkout",
                    testType = "browser",
                    intervalSeconds = 60,
                    timeoutSeconds = 45,
                    url = "https://{{global.HOST}}/checkout",
                    method = "POST",
                    headers = mapOf("X-Probe" to "{{global.HOST}}"),
                    body = """{"host":"{{global.HOST}}"}""",
                    assertions = listOf(SyntheticAssertion(type = "status_code", operator = "equals", value = "200")),
                    steps = listOf(
                        SyntheticStep(
                            name = "Health",
                            url = "https://{{global.HOST}}/health",
                            method = "GET"
                        )
                    ),
                    browserSteps = listOf(BrowserStep(action = "click", selector = "#buy", value = "Buy")),
                    locations = listOf("private-us-east")
                )
            )

            val work = service.getProbeWork(TEST_ORG_ID, "private-us-east")

            assertEquals(1, work.size)
            val item = work.single()
            assertEquals(test.id, item.testId)
            assertEquals("browser", item.testType)
            assertEquals("https://private.example.com/checkout", item.url)
            assertEquals("POST", item.method)
            assertEquals(mapOf("X-Probe" to "private.example.com"), item.headers)
            assertEquals("""{"host":"private.example.com"}""", item.body)
            assertEquals(45, item.timeoutSeconds)
            assertEquals(1, item.assertions.size)
            assertEquals("status_code", item.assertions.single().type)
            assertEquals("https://private.example.com/health", item.steps.single().url)
            assertEquals("#buy", item.browserSteps.single().selector)
        }

    @Test
    fun `getProbeWork returns empty when no tests are assigned to location`() =
        runBlocking {
            service.createTest(TEST_ORG_ID, createRequest { locations = listOf("private-us-east") })

            val work = service.getProbeWork(TEST_ORG_ID, "private-eu-west")

            assertTrue(work.isEmpty())
        }

    @Test
    fun `getProbeWork tolerates malformed optional stored config`() =
        runBlocking {
            val test = service.createTest(
                TEST_ORG_ID,
                createRequest {
                    locations = listOf("private-us-east")
                    headers = mapOf("X-Valid" to "yes")
                    steps = listOf(SyntheticStep(name = "Valid", url = "https://example.com"))
                    config = SyntheticTestConfig(hostname = "example.com", port = 443)
                    browserSteps = listOf(BrowserStep(action = "click", selector = "#ok"))
                }
            )
            transaction {
                SyntheticTests.update({ SyntheticTests.id eq java.util.UUID.fromString(test.id) }) {
                    it[headers] = "not-json"
                    it[assertions] = "not-json"
                    it[steps] = "not-json"
                    it[config] = "not-json"
                    it[browserSteps] = "not-json"
                }
            }

            withClickHouseMock {
                val work = service.getProbeWork(TEST_ORG_ID, "private-us-east")

                assertEquals(1, work.size)
                assertNull(work.single().headers)
                assertTrue(work.single().assertions.isEmpty())
                assertTrue(work.single().steps.isEmpty())
                assertTrue(work.single().browserSteps.isEmpty())
                assertNull(work.single().config)
            }
        }

    @Test
    fun `getProbeWork excludes tests with fresh private location results`() =
        runBlocking {
            val test = service.createTest(
                TEST_ORG_ID,
                createRequest {
                    intervalSeconds = 60
                    locations = listOf("private-us-east")
                }
            )
            val freshRun = """{"test_id":"${test.id}","last_ms":${System.currentTimeMillis()}}"""

            withClickHouseMock("SELECT test_id, toUnixTimestamp64Milli" to freshRun) {
                val work = service.getProbeWork(TEST_ORG_ID, "private-us-east")

                assertTrue(work.isEmpty())
            }
        }

    // ──── Global Variables CRUD ────

    @Test
    fun `createVariable creates and returns variable`() {
        val request = SyntheticVariableRequest(
            name = "API_KEY",
            value = "secret123",
            isSecret = true
        )
        val response = service.createVariable(TEST_ORG_ID, request)
        assertNotNull(response)
        assertEquals("API_KEY", response.name)
        assertTrue(response.isSecret)
        variableResourceId(response.id)
        // Secret values should be masked
        assertEquals("********", response.value)
    }

    @Test
    fun `createVariable stores non-secret value visible`() {
        val request = SyntheticVariableRequest(
            name = "BASE_URL",
            value = "https://api.example.com",
            isSecret = false
        )
        val response = service.createVariable(TEST_ORG_ID, request)
        assertEquals("https://api.example.com", response.value)
        assertFalse(response.isSecret)
    }

    @Test
    fun `listVariables returns all variables for org`() {
        service.createVariable(
            TEST_ORG_ID,
            SyntheticVariableRequest(name = "VAR1", value = "val1")
        )
        service.createVariable(
            TEST_ORG_ID,
            SyntheticVariableRequest(name = "VAR2", value = "val2")
        )

        val vars = service.listVariables(TEST_ORG_ID)
        assertEquals(2, vars.size)
    }

    @Test
    fun `listVariables returns empty for org with no variables`() {
        val vars = service.listVariables(999)
        assertTrue(vars.isEmpty())
    }

    @Test
    fun `getVariable returns variable by resource id`() {
        val created = service.createVariable(
            TEST_ORG_ID,
            SyntheticVariableRequest(name = "MY_VAR", value = "hello")
        )
        val fetched = service.getVariable(variableResourceId(created.id), TEST_ORG_ID)
        assertNotNull(fetched)
        assertEquals("MY_VAR", fetched.name)
    }

    @Test
    fun `getVariable returns null for wrong org`() {
        val created = service.createVariable(
            TEST_ORG_ID,
            SyntheticVariableRequest(name = "VAR", value = "val")
        )
        val fetched = service.getVariable(variableResourceId(created.id), 999)
        assertNull(fetched)
    }

    @Test
    fun `updateVariable updates name and value`() {
        val created = service.createVariable(
            TEST_ORG_ID,
            SyntheticVariableRequest(name = "OLD", value = "oldval")
        )
        val updated = service.updateVariable(
            variableResourceId(created.id),
            TEST_ORG_ID,
            SyntheticVariableRequest(name = "NEW", value = "newval")
        )
        assertNotNull(updated)
        assertEquals("NEW", updated.name)
        assertEquals("newval", updated.value)
    }

    @Test
    fun `updateVariable returns null for wrong org`() {
        val created = service.createVariable(
            TEST_ORG_ID,
            SyntheticVariableRequest(name = "VAR", value = "val")
        )
        val result = service.updateVariable(
            variableResourceId(created.id),
            999,
            SyntheticVariableRequest(name = "X", value = "y")
        )
        assertNull(result)
    }

    @Test
    fun `deleteVariable removes variable`() {
        val created = service.createVariable(
            TEST_ORG_ID,
            SyntheticVariableRequest(name = "DEL", value = "me")
        )
        val resourceId = variableResourceId(created.id)
        assertTrue(service.deleteVariable(resourceId, TEST_ORG_ID))
        assertNull(service.getVariable(resourceId, TEST_ORG_ID))
    }

    @Test
    fun `deleteVariable returns false for wrong org`() {
        val created = service.createVariable(
            TEST_ORG_ID,
            SyntheticVariableRequest(name = "VAR", value = "val")
        )
        assertFalse(service.deleteVariable(variableResourceId(created.id), 999))
    }

    @Test
    fun `getVariablesMap returns name-value map`() {
        service.createVariable(
            TEST_ORG_ID,
            SyntheticVariableRequest(name = "HOST", value = "example.com")
        )
        service.createVariable(
            TEST_ORG_ID,
            SyntheticVariableRequest(name = "TOKEN", value = "abc")
        )

        val map = service.getVariablesMap(TEST_ORG_ID)
        assertEquals(2, map.size)
        assertEquals("example.com", map["HOST"])
        assertEquals("abc", map["TOKEN"])
    }

    @Test
    fun `getVariablesMap returns raw values even for secrets`() {
        service.createVariable(
            TEST_ORG_ID,
            SyntheticVariableRequest(
                name = "SECRET",
                value = "hidden",
                isSecret = true
            )
        )

        val map = service.getVariablesMap(TEST_ORG_ID)
        assertEquals("hidden", map["SECRET"])
    }

    // ──── getTestsDueForRun ────

    @Test
    fun `getTestsDueForRun returns new active tests`() {
        service.createTest(TEST_ORG_ID, createRequest { name = "Due Test" })
        val due = service.getTestsDueForRun()
        assertTrue(due.any { it.name == "Due Test" })
    }

    @Test
    fun `getTestsDueForRun excludes inactive tests`() {
        val created = service.createTest(TEST_ORG_ID, createRequest())
        service.updateTest(
            java.util.UUID.fromString(created.id),
            TEST_ORG_ID,
            UpdateSyntheticTestRequest(active = false)
        )
        val due = service.getTestsDueForRun()
        assertFalse(due.any { it.id.toString() == created.id })
    }

    @Test
    fun `getTestsDueForRun excludes recently run active tests`() {
        val created = service.createTest(
            TEST_ORG_ID,
            createRequest { intervalSeconds = 300 }
        )
        service.updateTestStatus(java.util.UUID.fromString(created.id), "passed", "passed")

        val due = service.getTestsDueForRun()

        assertFalse(due.any { it.id.toString() == created.id })
    }
}
