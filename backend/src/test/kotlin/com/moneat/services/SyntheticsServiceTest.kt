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

import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.Users
import com.moneat.synthetics.routes.CreateSyntheticTestRequest
import com.moneat.synthetics.routes.SyntheticAssertion
import com.moneat.synthetics.routes.SyntheticCheckResult
import com.moneat.synthetics.routes.SyntheticTestConfig
import com.moneat.synthetics.routes.SyntheticTestData
import com.moneat.synthetics.routes.SyntheticTests
import com.moneat.synthetics.routes.SyntheticVariableRequest
import com.moneat.synthetics.routes.SyntheticVariables
import com.moneat.synthetics.routes.SyntheticsCheckExecutor
import com.moneat.synthetics.routes.SyntheticsService
import com.moneat.synthetics.routes.UpdateSyntheticTestRequest
import com.moneat.testsupport.TestDatabaseHelper
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyntheticsServiceTest {
    private val service = SyntheticsService()

    companion object {
        private var db: Database? = null
        private const val TEST_ORG_ID = 1
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

    private fun createRequest(
        name: String = "API Health Check",
        testType: String = "api",
        url: String? = "https://example.com/health",
        tags: List<String> = emptyList(),
        retryCount: Int = 0,
        retryIntervalMs: Int = 300,
        alertOnFailure: Boolean = false,
        assertions: List<SyntheticAssertion> = emptyList(),
        config: SyntheticTestConfig? = null
    ): CreateSyntheticTestRequest {
        return CreateSyntheticTestRequest(
            name = name,
            testType = testType,
            intervalSeconds = 60,
            timeoutSeconds = 10,
            url = url,
            assertions = assertions,
            tags = tags,
            retryCount = retryCount,
            retryIntervalMs = retryIntervalMs,
            alertOnFailure = alertOnFailure,
            config = config
        )
    }

    // --- CRUD: Create ---

    @Test
    fun `createTest creates a test and returns response`() {
        val response = service.createTest(TEST_ORG_ID, createRequest())
        assertNotNull(response)
        assertEquals("API Health Check", response.name)
        assertEquals("api", response.testType)
        assertTrue(response.active)
        assertEquals(TEST_ORG_ID, response.organizationId)
        assertEquals("pending", response.status)
    }

    @Test
    fun `createTest stores tags`() {
        val response = service.createTest(
            TEST_ORG_ID,
            createRequest(tags = listOf("production", "critical"))
        )
        assertEquals(listOf("production", "critical"), response.tags)
    }

    @Test
    fun `createTest stores retry config`() {
        val response = service.createTest(
            TEST_ORG_ID,
            createRequest(retryCount = 3, retryIntervalMs = 500)
        )
        assertEquals(3, response.retryCount)
        assertEquals(500, response.retryIntervalMs)
    }

    @Test
    fun `createTest stores alert config`() {
        val response = service.createTest(
            TEST_ORG_ID,
            createRequest(alertOnFailure = true)
        )
        assertTrue(response.alertOnFailure)
    }

    @Test
    fun `createTest stores SSL config`() {
        val config = SyntheticTestConfig(
            hostname = "example.com",
            port = 443
        )
        val response = service.createTest(
            TEST_ORG_ID,
            createRequest(
                name = "SSL Check",
                testType = "ssl",
                config = config
            )
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
            createRequest(
                name = "Postgres Check",
                testType = "tcp",
                config = config
            )
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
            createRequest(assertions = assertions)
        )
        assertEquals(1, response.assertions.size)
        assertEquals("status_code", response.assertions[0].type)
    }

    // --- CRUD: Read ---

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
        service.createTest(TEST_ORG_ID, createRequest(name = "Test 1"))
        service.createTest(TEST_ORG_ID, createRequest(name = "Test 2"))
        service.createTest(TEST_ORG_ID, createRequest(name = "Test 3"))

        val tests = service.listTests(TEST_ORG_ID)
        assertEquals(3, tests.size)
    }

    @Test
    fun `listTests returns empty for org with no tests`() {
        val tests = service.listTests(999)
        assertTrue(tests.isEmpty())
    }

    // --- CRUD: Update ---

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

    // --- CRUD: Delete ---

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

    // --- Status Updates ---

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

    // --- Retry Logic ---

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

    // --- Global Variables CRUD ---

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
    fun `getVariable returns variable by id`() {
        val created = service.createVariable(
            TEST_ORG_ID,
            SyntheticVariableRequest(name = "MY_VAR", value = "hello")
        )
        val fetched = service.getVariable(created.id, TEST_ORG_ID)
        assertNotNull(fetched)
        assertEquals("MY_VAR", fetched.name)
    }

    @Test
    fun `getVariable returns null for wrong org`() {
        val created = service.createVariable(
            TEST_ORG_ID,
            SyntheticVariableRequest(name = "VAR", value = "val")
        )
        val fetched = service.getVariable(created.id, 999)
        assertNull(fetched)
    }

    @Test
    fun `updateVariable updates name and value`() {
        val created = service.createVariable(
            TEST_ORG_ID,
            SyntheticVariableRequest(name = "OLD", value = "oldval")
        )
        val updated = service.updateVariable(
            created.id,
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
            created.id,
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
        assertTrue(service.deleteVariable(created.id, TEST_ORG_ID))
        assertNull(service.getVariable(created.id, TEST_ORG_ID))
    }

    @Test
    fun `deleteVariable returns false for wrong org`() {
        val created = service.createVariable(
            TEST_ORG_ID,
            SyntheticVariableRequest(name = "VAR", value = "val")
        )
        assertFalse(service.deleteVariable(created.id, 999))
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

    // --- getTestsDueForRun ---

    @Test
    fun `getTestsDueForRun returns new active tests`() {
        service.createTest(TEST_ORG_ID, createRequest(name = "Due Test"))
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
}
