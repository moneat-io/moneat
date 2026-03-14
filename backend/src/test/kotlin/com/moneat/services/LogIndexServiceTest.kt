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

@file:Suppress("USELESS_CAST", "UNNECESSARY_NOT_NULL_ASSERTION", "UNNECESSARY_SAFE_CALL")

package com.moneat.services

import com.moneat.config.ClickHouseClient
import com.moneat.logs.models.CreateLogIndexRequest
import com.moneat.logs.models.UpdateLogIndexRequest
import com.moneat.logs.services.LogIndexService
import com.moneat.shared.models.LogIndexes
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogIndexServiceTest {

    private val service = LogIndexService()

    companion object {
        private var db: Database? = null
        private const val ORG_ID = 1
        private const val SERVICE_API = "service:api"
        private const val API_INDEX = "api-index"
        private const val TEXT_PLAIN = "text/plain"
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_log_index;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Users, Organizations, LogIndexes)
        seedOrganization()
        ClickHouseClient.close()
    }

    @AfterTest
    fun teardown() {
        ClickHouseClient.close()
    }

    private fun seedOrganization(): Int = transaction {
        Organizations.insert {
            it[name] = "Test Org"
            it[slug] = "test-org"
        } get Organizations.id
    }

    private fun createIndex(
        name: String = "main",
        filterQuery: String = "",
        retentionDays: Int = 30,
        samplingRate: Float = 1.0f,
        priority: Int = 0,
        dailyQuotaGb: Float? = null
    ) = service.create(
        ORG_ID,
        CreateLogIndexRequest(
            name = name,
            filterQuery = filterQuery,
            retentionDays = retentionDays,
            samplingRate = samplingRate,
            priority = priority,
            dailyQuotaGb = dailyQuotaGb
        )
    )

    // --- create ---

    @Test
    fun `create inserts index with correct fields`() {
        val result = createIndex(
            name = "prod-logs",
            filterQuery = SERVICE_API,
            retentionDays = 90,
            samplingRate = 0.5f,
            priority = 1,
            dailyQuotaGb = 10.0f
        )

        assertEquals("prod-logs", result.name)
        assertEquals(SERVICE_API, result.filterQuery)
        assertEquals(90, result.retentionDays)
        assertEquals(0.5f, result.samplingRate)
        assertEquals(1, result.priority)
        assertEquals(10.0f, result.dailyQuotaGb)
        assertTrue(result.isActive)
        assertNotNull(result.createdAt)
        assertNotNull(result.updatedAt)
    }

    @Test
    fun `create trims whitespace from name`() {
        val result = createIndex(name = "  padded-name  ")
        assertEquals("padded-name", result.name)
    }

    @Test
    fun `create throws on empty name`() {
        assertFailsWith<IllegalArgumentException> {
            createIndex(name = "")
        }
    }

    @Test
    fun `create throws on blank name`() {
        assertFailsWith<IllegalArgumentException> {
            createIndex(name = "   ")
        }
    }

    @Test
    fun `create coerces retention days to max 365`() {
        val result = createIndex(retentionDays = 500)
        assertEquals(365, result.retentionDays)
    }

    @Test
    fun `create coerces retention days to min 1`() {
        val result = createIndex(retentionDays = 0)
        assertEquals(1, result.retentionDays)
    }

    @Test
    fun `create coerces sampling rate to max 1`() {
        val result = createIndex(samplingRate = 2.0f)
        assertEquals(1.0f, result.samplingRate)
    }

    @Test
    fun `create coerces sampling rate to min 0`() {
        val result = createIndex(samplingRate = -0.5f)
        assertEquals(0.0f, result.samplingRate)
    }

    @Test
    fun `create stores null daily quota`() {
        val result = createIndex(dailyQuotaGb = null)
        assertNull(result.dailyQuotaGb)
    }

    // --- getById ---

    @Test
    fun `getById returns index for correct org`() {
        val created = createIndex(name = "my-index")
        val fetched = service.getById(ORG_ID, created.id)

        assertNotNull(fetched)
        assertEquals(created.id, fetched.id)
        assertEquals("my-index", fetched.name)
    }

    @Test
    fun `getById returns null for wrong org`() {
        val created = createIndex()
        val result = service.getById(9999, created.id)
        assertNull(result)
    }

    @Test
    fun `getById returns null for non-existent id`() {
        val result = service.getById(ORG_ID, 9999)
        assertNull(result)
    }

    // --- list ---

    @Test
    fun `list returns all indexes for organization`() {
        createIndex(name = "index-a", priority = 2)
        createIndex(name = "index-b", priority = 1)

        val results = service.list(ORG_ID)
        assertEquals(2, results.size)
        // ordered by priority
        assertEquals("index-b", results[0].name)
        assertEquals("index-a", results[1].name)
    }

    @Test
    fun `list returns empty for unknown organization`() {
        createIndex()
        val results = service.list(9999)
        assertTrue(results.isEmpty())
    }

    // --- update ---

    @Test
    fun `update modifies name`() {
        val created = createIndex(name = "old-name")
        val updated = service.update(
            ORG_ID,
            created.id,
            UpdateLogIndexRequest(name = "new-name")
        )

        assertNotNull(updated)
        assertEquals("new-name", updated.name)
    }

    @Test
    fun `update modifies filter query`() {
        val created = createIndex()
        val updated = service.update(
            ORG_ID,
            created.id,
            UpdateLogIndexRequest(filterQuery = "env:prod")
        )

        assertNotNull(updated)
        assertEquals("env:prod", updated.filterQuery)
    }

    @Test
    fun `update modifies retention days with coercion`() {
        val created = createIndex(retentionDays = 30)
        val updated = service.update(
            ORG_ID,
            created.id,
            UpdateLogIndexRequest(retentionDays = 999)
        )

        assertNotNull(updated)
        assertEquals(365, updated.retentionDays)
    }

    @Test
    fun `update modifies sampling rate with coercion`() {
        val created = createIndex(samplingRate = 1.0f)
        val updated = service.update(
            ORG_ID,
            created.id,
            UpdateLogIndexRequest(samplingRate = -1.0f)
        )

        assertNotNull(updated)
        assertEquals(0.0f, updated.samplingRate)
    }

    @Test
    fun `update modifies isActive`() {
        val created = createIndex()
        val updated = service.update(
            ORG_ID,
            created.id,
            UpdateLogIndexRequest(isActive = false)
        )

        assertNotNull(updated)
        assertFalse(updated.isActive)
    }

    @Test
    fun `update modifies priority and dailyQuotaGb`() {
        val created = createIndex(priority = 0)
        val updated = service.update(
            ORG_ID,
            created.id,
            UpdateLogIndexRequest(priority = 5, dailyQuotaGb = 25.0f)
        )

        assertNotNull(updated)
        assertEquals(5, updated.priority)
        assertEquals(25.0f, updated.dailyQuotaGb)
    }

    @Test
    fun `update returns null for non-existent index`() {
        val result = service.update(
            ORG_ID,
            9999,
            UpdateLogIndexRequest(name = "nope")
        )
        assertNull(result)
    }

    @Test
    fun `update returns null for wrong organization`() {
        val created = createIndex()
        val result = service.update(
            9999,
            created.id,
            UpdateLogIndexRequest(name = "nope")
        )
        assertNull(result)
    }

    @Test
    fun `update throws on empty name`() {
        val created = createIndex()
        assertFailsWith<IllegalArgumentException> {
            service.update(
                ORG_ID,
                created.id,
                UpdateLogIndexRequest(name = "   ")
            )
        }
    }

    @Test
    fun `update with no fields still updates timestamp`() {
        val created = createIndex()
        val updated = service.update(
            ORG_ID,
            created.id,
            UpdateLogIndexRequest()
        )

        assertNotNull(updated)
        assertEquals(created.name, updated.name)
    }

    // --- delete ---

    @Test
    fun `delete removes an existing index`() {
        val created = createIndex()
        assertTrue(service.delete(ORG_ID, created.id))
        assertNull(service.getById(ORG_ID, created.id))
    }

    @Test
    fun `delete returns false for non-existent index`() {
        assertFalse(service.delete(ORG_ID, 9999))
    }

    @Test
    fun `delete returns false for wrong organization`() {
        val created = createIndex()
        assertFalse(service.delete(9999, created.id))
        // index still exists for correct org
        assertNotNull(service.getById(ORG_ID, created.id))
    }

    // --- matchIndex ---

    @Test
    fun `matchIndex returns name for blank filter query`() = runBlocking {
        createIndex(name = "catch-all", filterQuery = "", priority = 0)
        val result = service.matchIndex(
            ORG_ID,
            mapOf("service" to "api", "message" to "hello")
        )
        assertEquals("catch-all", result)
    }

    @Test
    fun `matchIndex returns empty string when no indexes exist`() =
        runBlocking {
            val result = service.matchIndex(
                ORG_ID,
                mapOf("service" to "api")
            )
            assertEquals("", result)
        }

    @Test
    fun `matchIndex matches field filter`() = runBlocking {
        createIndex(
            name = API_INDEX,
            filterQuery = SERVICE_API,
            priority = 0
        )
        val result = service.matchIndex(
            ORG_ID,
            mapOf("service" to "api")
        )
        assertEquals(API_INDEX, result)
    }

    @Test
    fun `matchIndex skips non-matching filter`() = runBlocking {
        createIndex(
            name = "api-only",
            filterQuery = SERVICE_API,
            priority = 0
        )
        createIndex(
            name = "fallback",
            filterQuery = "",
            priority = 1
        )

        val result = service.matchIndex(
            ORG_ID,
            mapOf("service" to "worker")
        )
        assertEquals("fallback", result)
    }

    @Test
    fun `matchIndex respects priority ordering`() = runBlocking {
        createIndex(
            name = "low-priority",
            filterQuery = "",
            priority = 10
        )
        createIndex(
            name = "high-priority",
            filterQuery = "",
            priority = 1
        )

        val result = service.matchIndex(
            ORG_ID,
            mapOf("service" to "api")
        )
        assertEquals("high-priority", result)
    }

    @Test
    fun `matchIndex skips inactive indexes`() = runBlocking {
        val created = createIndex(
            name = "active-index",
            filterQuery = "",
            priority = 0
        )
        service.update(
            ORG_ID,
            created.id,
            UpdateLogIndexRequest(isActive = false)
        )

        val result = service.matchIndex(
            ORG_ID,
            mapOf("service" to "api")
        )
        assertEquals("", result)
    }

    @Test
    fun `matchIndex handles AND filter`() = runBlocking {
        createIndex(
            name = "prod-api",
            filterQuery = "service:api AND environment:prod",
            priority = 0
        )

        val match = service.matchIndex(
            ORG_ID,
            mapOf("service" to "api", "environment" to "prod")
        )
        assertEquals("prod-api", match)

        val noMatch = service.matchIndex(
            ORG_ID,
            mapOf("service" to "api", "environment" to "staging")
        )
        assertEquals("", noMatch)
    }

    @Test
    fun `matchIndex handles OR filter`() = runBlocking {
        createIndex(
            name = "web-services",
            filterQuery = "service:api OR service:web",
            priority = 0
        )

        val match = service.matchIndex(
            ORG_ID,
            mapOf("service" to "web")
        )
        assertEquals("web-services", match)
    }

    @Test
    fun `matchIndex handles NOT filter with dash prefix`() = runBlocking {
        createIndex(
            name = "non-debug",
            filterQuery = "-service:debug",
            priority = 0
        )

        val match = service.matchIndex(
            ORG_ID,
            mapOf("service" to "api")
        )
        assertEquals("non-debug", match)

        val noMatch = service.matchIndex(
            ORG_ID,
            mapOf("service" to "debug")
        )
        assertEquals("", noMatch)
    }

    @Test
    fun `matchIndex handles exact field match case insensitive`() =
        runBlocking {
            createIndex(
                name = API_INDEX,
                filterQuery = "service:API",
                priority = 0
            )

            val match = service.matchIndex(
                ORG_ID,
                mapOf("service" to "api")
            )
            assertEquals("api-index", match)
        }

    @Test
    fun `matchIndex handles full text search`() = runBlocking {
        createIndex(
            name = "error-index",
            filterQuery = "timeout",
            priority = 0
        )

        val match = service.matchIndex(
            ORG_ID,
            mapOf("message" to "connection timeout occurred")
        )
        assertEquals("error-index", match)
    }

    @Test
    fun `matchIndex handles comparison filter`() = runBlocking {
        createIndex(
            name = "high-latency",
            filterQuery = "duration:>500",
            priority = 0
        )

        val match = service.matchIndex(
            ORG_ID,
            mapOf("duration" to "600")
        )
        assertEquals("high-latency", match)

        val noMatch = service.matchIndex(
            ORG_ID,
            mapOf("duration" to "100")
        )
        assertEquals("", noMatch)
    }

    @Test
    fun `matchIndex handles range filter`() = runBlocking {
        createIndex(
            name = "mid-range",
            filterQuery = "status_code:[200 TO 299]",
            priority = 0
        )

        val match = service.matchIndex(
            ORG_ID,
            mapOf("status_code" to "204")
        )
        assertEquals("mid-range", match)

        val noMatch = service.matchIndex(
            ORG_ID,
            mapOf("status_code" to "500")
        )
        assertEquals("", noMatch)
    }

    // --- testFilter (ClickHouse) ---

    @Test
    fun `testFilter returns counts from ClickHouse`() = runBlocking {
        MockHttpServer { exchange ->
            val body = exchange.requestBodyText()
            when {
                body.contains("AND (") -> exchange.respond(
                    200,
                    """{"cnt":3}""",
                    contentType = TEXT_PLAIN
                )
                body.contains("count()") -> exchange.respond(
                    200,
                    """{"cnt":10}""",
                    contentType = TEXT_PLAIN
                )
                else -> exchange.respond(
                    500,
                    "unexpected",
                    contentType = TEXT_PLAIN
                )
            }
        }.use { server ->
            ClickHouseClient.init(
                server.baseUrl,
                "test",
                "default",
                ""
            )

            val result = service.testFilter(ORG_ID, SERVICE_API)
            assertEquals(10L, result.totalCount)
            assertEquals(3L, result.matchCount)
        }
    }

    @Test
    fun `testFilter with blank query returns total as match`() =
        runBlocking {
            MockHttpServer { exchange ->
                exchange.respond(
                    200,
                    """{"cnt":42}""",
                    contentType = TEXT_PLAIN
                )
            }.use { server ->
                ClickHouseClient.init(
                    server.baseUrl,
                    "test",
                    "default",
                    ""
                )

                val result = service.testFilter(ORG_ID, "")
                assertEquals(42L, result.totalCount)
                assertEquals(42L, result.matchCount)
            }
        }

    @Test
    fun `testFilter returns zero match on clickhouse error`() =
        runBlocking {
            var requestCount = 0
            MockHttpServer { exchange ->
                requestCount++
                if (requestCount == 1) {
                    // total count query succeeds
                    exchange.respond(
                        200,
                        """{"cnt":10}""",
                        contentType = TEXT_PLAIN
                    )
                } else {
                    // filter query fails
                    exchange.respond(
                        500,
                        "DB error",
                        contentType = TEXT_PLAIN
                    )
                }
            }.use { server ->
                ClickHouseClient.init(
                    server.baseUrl,
                    "test",
                    "default",
                    ""
                )

                val result = service.testFilter(
                    ORG_ID,
                    SERVICE_API
                )
                assertEquals(10L, result.totalCount)
                assertEquals(0L, result.matchCount)
            }
        }
}
