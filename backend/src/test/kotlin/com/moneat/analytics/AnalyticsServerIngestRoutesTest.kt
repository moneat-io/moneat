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

package com.moneat.analytics

import com.moneat.analytics.routes.analyticsServerIngestRoutes
import com.moneat.analytics.services.AnalyticsIngestionWorker
import com.moneat.config.RedisConfig
import com.moneat.otlp.services.OtlpApiKeyService
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.testsupport.TestDatabaseHelper
import io.lettuce.core.api.sync.RedisCommands
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyticsServerIngestRoutesTest {
    // ──── Companion ────

    companion object {
        private const val API_KEY = "motlp_valid_server_key"
        private const val TOO_MANY_EVENTS_COUNT = 501
        private var db: Database? = null
    }

    // ──── Mocks & Setup ────

    private val otlpApiKeyService = mockk<OtlpApiKeyService>()

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_server_analytics_routes;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Organizations, Projects)
    }

    @AfterTest
    fun teardown() {
        unmockkObject(RedisConfig)
    }

    // ──── Tests ────

    @Test
    fun `POST server analytics event enqueues canonical product event`() = testApplication {
        val (organizationId, projectId) = seedProject()
        every { otlpApiKeyService.validateKey(API_KEY) } returns organizationId
        val queuedMessages = mutableListOf<String>()
        var enqueueCalls = 0

        application {
            install(ContentNegotiation) { json() }
            routing {
                analyticsServerIngestRoutes(
                    otlpApiKeyService = otlpApiKeyService,
                    enqueueEvents = { messages ->
                        enqueueCalls += 1
                        queuedMessages.addAll(messages)
                    },
                )
            }
        }

        val response = client.post(analyticsRoute(projectId)) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $API_KEY")
            setBody(
                """
                {
                  "events": [
                    {
                      "name": "recording.started",
                      "user_id": "sha256-user",
                      "session_id": "sess-abc",
                      "props": {"platform": "ios", "app_version": "1.2.3"},
                      "timestamp": 1716825600000
                    },
                    {
                      "name": "export.completed",
                      "user_id": "sha256-user",
                      "session_id": "sess-abc",
                      "props": {"platform": "ios"},
                      "timestamp": 1716825601000
                    }
                  ]
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        assertEquals(1, enqueueCalls)
        assertEquals(2, queuedMessages.size)
        val message = queuedMessages.first()
        assertTrue(message.contains("\"projectId\":$projectId"))
        assertTrue(message.contains("\"sessionId\":\"sess-abc\""))
        assertTrue(message.contains("\"userId\":\"sha256-user\""))
        assertTrue(message.contains("\"eventName\":\"recording.started\""))
        assertTrue(message.contains("\"source\":\"server\""))
        assertTrue(message.contains("\"platform\":\"ios\""))
    }

    @Test
    fun `POST server analytics event uses default Redis queue`() = testApplication {
        val (organizationId, projectId) = seedProject()
        every { otlpApiKeyService.validateKey(API_KEY) } returns organizationId
        val mockRedis = mockk<RedisCommands<String, String>>()
        mockkObject(RedisConfig)
        every {
            mockRedis.lpush(AnalyticsIngestionWorker.QUEUE_KEY, *anyVararg())
        } returns 2L
        every { RedisConfig.sync() } returns mockRedis

        application {
            install(ContentNegotiation) { json() }
            routing {
                analyticsServerIngestRoutes(otlpApiKeyService = otlpApiKeyService)
            }
        }

        val response = client.post(analyticsRoute(projectId)) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $API_KEY")
            setBody(
                """
                {
                  "events": [
                    {"name": "recording.started", "user_id": "user-a"},
                    {"name": "export.completed", "user_id": "user-a"}
                  ]
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        verify(exactly = 2) {
            mockRedis.lpush(AnalyticsIngestionWorker.QUEUE_KEY, *anyVararg())
        }
    }

    @Test
    fun `POST server analytics event rejects invalid project ID`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                analyticsServerIngestRoutes(
                    otlpApiKeyService = otlpApiKeyService,
                    enqueueEvents = { },
                )
            }
        }

        val response = client.post("/v1/analytics/not-a-number/events") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $API_KEY")
            setBody("""{"events":[{"name":"recording.started","user_id":"user"}]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST server analytics event requires bearer token`() = testApplication {
        val (_, projectId) = seedProject()

        application {
            install(ContentNegotiation) { json() }
            routing {
                analyticsServerIngestRoutes(
                    otlpApiKeyService = otlpApiKeyService,
                    enqueueEvents = { },
                )
            }
        }

        val response = client.post(analyticsRoute(projectId)) {
            contentType(ContentType.Application.Json)
            setBody("""{"events":[]}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST server analytics event rejects invalid API key`() = testApplication {
        val (_, projectId) = seedProject()
        every { otlpApiKeyService.validateKey(API_KEY) } returns null

        application {
            install(ContentNegotiation) { json() }
            routing {
                analyticsServerIngestRoutes(
                    otlpApiKeyService = otlpApiKeyService,
                    enqueueEvents = { },
                )
            }
        }

        val response = client.post(analyticsRoute(projectId)) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $API_KEY")
            setBody("""{"events":[{"name":"recording.started","user_id":"user"}]}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST server analytics event rejects API key from another organization`() = testApplication {
        val (_, projectId) = seedProject()
        every { otlpApiKeyService.validateKey(API_KEY) } returns 999

        application {
            install(ContentNegotiation) { json() }
            routing {
                analyticsServerIngestRoutes(
                    otlpApiKeyService = otlpApiKeyService,
                    enqueueEvents = { },
                )
            }
        }

        val response = client.post(analyticsRoute(projectId)) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $API_KEY")
            setBody("""{"events":[{"name":"recording.started","user_id":"user"}]}""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `POST server analytics event rejects malformed payload`() = testApplication {
        val (organizationId, projectId) = seedProject()
        every { otlpApiKeyService.validateKey(API_KEY) } returns organizationId

        application {
            install(ContentNegotiation) { json() }
            routing {
                analyticsServerIngestRoutes(
                    otlpApiKeyService = otlpApiKeyService,
                    enqueueEvents = { },
                )
            }
        }

        val response = client.post(analyticsRoute(projectId)) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $API_KEY")
            setBody("{not-json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST server analytics event rejects empty event list`() = testApplication {
        val (organizationId, projectId) = seedProject()
        every { otlpApiKeyService.validateKey(API_KEY) } returns organizationId

        application {
            install(ContentNegotiation) { json() }
            routing {
                analyticsServerIngestRoutes(
                    otlpApiKeyService = otlpApiKeyService,
                    enqueueEvents = { },
                )
            }
        }

        val response = client.post(analyticsRoute(projectId)) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $API_KEY")
            setBody("""{"events":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST server analytics event rejects oversized event list`() = testApplication {
        val (organizationId, projectId) = seedProject()
        every { otlpApiKeyService.validateKey(API_KEY) } returns organizationId
        val events = List(TOO_MANY_EVENTS_COUNT) { index ->
            """{"name":"recording.started","user_id":"user-$index"}"""
        }.joinToString(prefix = """{"events":[""", separator = ",", postfix = "]}")

        application {
            install(ContentNegotiation) { json() }
            routing {
                analyticsServerIngestRoutes(
                    otlpApiKeyService = otlpApiKeyService,
                    enqueueEvents = { },
                )
            }
        }

        val response = client.post(analyticsRoute(projectId)) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $API_KEY")
            setBody(events)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST server analytics event validates payload events`() = testApplication {
        val (organizationId, projectId) = seedProject()
        every { otlpApiKeyService.validateKey(API_KEY) } returns organizationId

        application {
            install(ContentNegotiation) { json() }
            routing {
                analyticsServerIngestRoutes(
                    otlpApiKeyService = otlpApiKeyService,
                    enqueueEvents = { },
                )
            }
        }

        val response = client.post(analyticsRoute(projectId)) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $API_KEY")
            setBody("""{"events":[{"name":"","user_id":""}]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST server analytics event returns 500 when enqueue fails`() = testApplication {
        val (organizationId, projectId) = seedProject()
        every { otlpApiKeyService.validateKey(API_KEY) } returns organizationId

        application {
            install(ContentNegotiation) { json() }
            routing {
                analyticsServerIngestRoutes(
                    otlpApiKeyService = otlpApiKeyService,
                    enqueueEvents = { throw RuntimeException("queue unavailable") },
                )
            }
        }

        val response = client.post(analyticsRoute(projectId)) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $API_KEY")
            setBody("""{"events":[{"name":"recording.started","user_id":"user"}]}""")
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
    }

    // ──── Helpers ────

    private fun analyticsRoute(projectId: Long): String =
        "/v1/analytics/${projectResourceId(projectId)}/events"

    private fun projectResourceId(projectId: Long): String = transaction {
        Projects
            .selectAll()
            .where { Projects.id eq projectId }
            .first()[Projects.resource_id]
            .toString()
    }

    private fun seedProject(): Pair<Int, Long> {
        return transaction {
            val organizationId = Organizations.insert {
                it[name] = "Server Analytics Org"
                it[slug] = "server-analytics-org"
            } get Organizations.id
            val projectId = Projects.insert {
                it[organization_id] = organizationId
                it[name] = "Server Analytics Project"
                it[slug] = "server-analytics-project"
                it[framework] = "mobile"
            } get Projects.id
            organizationId to projectId
        }
    }
}
