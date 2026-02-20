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

import com.moneat.models.Organizations
import com.moneat.models.ProjectKeys
import com.moneat.models.Projects
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyticsIngestRoutesTest {

    private val testPublicKey = "analyticskey456"
    private var testProjectId: Long = 0

    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_analytics_routes;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction {
                SchemaUtils.create(Organizations, Projects, ProjectKeys)
            }
            dbInitialized = true
        }

        transaction {
            ProjectKeys.deleteAll()
            Projects.deleteAll()
            Organizations.deleteAll()
        }

        transaction {
            val orgId = Organizations.insert {
                it[name] = "Analytics Test Org"
                it[slug] = "analytics-test-org"
            }[Organizations.id]

            testProjectId = Projects.insert {
                it[organization_id] = orgId
                it[name] = "analytics-project"
                it[slug] = "analytics-project"
                it[framework] = "web"
            }[Projects.id]

            ProjectKeys.insert {
                it[project_id] = testProjectId
                it[public_key] = testPublicKey
                it[secret_key] = "secret-$testPublicKey"
                it[platform_target] = "web"
                it[is_active] = true
            }
        }
    }

    @Test
    fun `returns 401 when sentry_key query param is missing`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { analyticsIngestRoutes(insertEvent = { }) }
            }

            val response = client.post("/api/moneat.io/analytics/event") {
                contentType(ContentType.Application.Json)
                setBody("""{"n":"pageview","u":"https://moneat.io","d":"moneat.io"}""")
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("Missing sentry_key"))
        }

    @Test
    fun `returns 401 when sentry_key does not match any project key`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { analyticsIngestRoutes(insertEvent = { }) }
            }

            val response = client.post("/api/moneat.io/analytics/event?sentry_key=badkey999") {
                contentType(ContentType.Application.Json)
                setBody("""{"n":"pageview","u":"https://moneat.io","d":"moneat.io"}""")
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("Invalid sentry_key"))
        }

    @Test
    fun `returns 400 for malformed JSON body`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { analyticsIngestRoutes(insertEvent = { }) }
            }

            val response = client.post("/api/moneat.io/analytics/event?sentry_key=$testPublicKey") {
                contentType(ContentType.Application.Json)
                setBody("not-json")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid JSON payload"))
        }

    @Test
    fun `returns 202 and calls insertEvent with correct payload for valid pageview`() =
        testApplication {
            val captured = mutableListOf<AnalyticsEventPayload>()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    analyticsIngestRoutes(insertEvent = { captured.add(it) })
                }
            }

            val response = client.post("/api/moneat.io/analytics/event?sentry_key=$testPublicKey") {
                contentType(ContentType.Application.Json)
                setBody("""{"n":"pageview","u":"https://moneat.io/pricing",
                    |"d":"moneat.io","r":"https://google.com","w":1440}""".trimMargin())
            }

            assertEquals(HttpStatusCode.Accepted, response.status)
            assertEquals(1, captured.size)
            val payload = captured.first()
            assertEquals(testProjectId, payload.projectId)
            assertEquals("pageview", payload.eventName)
            assertEquals("moneat.io", payload.hostname)
            assertEquals("/pricing", payload.pathname)
            assertEquals("https://google.com", payload.referrer)
            assertEquals(1440, payload.screenWidth)
        }

    @Test
    fun `returns 202 and captures custom event with props`() =
        testApplication {
            val captured = mutableListOf<AnalyticsEventPayload>()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    analyticsIngestRoutes(insertEvent = { captured.add(it) })
                }
            }

            val response = client.post("/api/moneat.io/analytics/event?sentry_key=$testPublicKey") {
                contentType(ContentType.Application.Json)
                setBody("""{"n":"signup","u":"https://moneat.io/signup","d":"moneat.io","p":{"plan":"pro"}}""")
            }

            assertEquals(HttpStatusCode.Accepted, response.status)
            assertEquals(1, captured.size)
            val payload = captured.first()
            assertEquals("signup", payload.eventName)
            assertEquals(mapOf("plan" to "pro"), payload.props)
        }

    @Test
    fun `returns 500 when insertEvent throws`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing {
                    analyticsIngestRoutes(insertEvent = { throw RuntimeException("CH down") })
                }
            }

            val response = client.post("/api/moneat.io/analytics/event?sentry_key=$testPublicKey") {
                contentType(ContentType.Application.Json)
                setBody("""{"n":"pageview","u":"https://moneat.io","d":"moneat.io"}""")
            }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertTrue(response.bodyAsText().contains("Failed to record event"))
        }

    @Test
    fun `defaults event_name to pageview when n field is absent`() =
        testApplication {
            val captured = mutableListOf<AnalyticsEventPayload>()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    analyticsIngestRoutes(insertEvent = { captured.add(it) })
                }
            }

            val response = client.post("/api/moneat.io/analytics/event?sentry_key=$testPublicKey") {
                contentType(ContentType.Application.Json)
                setBody("""{"u":"https://moneat.io","d":"moneat.io"}""")
            }

            assertEquals(HttpStatusCode.Accepted, response.status)
            assertEquals("pageview", captured.first().eventName)
        }

    @Test
    fun `handles invalid URL gracefully and defaults pathname to slash`() =
        testApplication {
            val captured = mutableListOf<AnalyticsEventPayload>()

            application {
                install(ContentNegotiation) { json() }
                routing {
                    analyticsIngestRoutes(insertEvent = { captured.add(it) })
                }
            }

            val response = client.post("/api/moneat.io/analytics/event?sentry_key=$testPublicKey") {
                contentType(ContentType.Application.Json)
                setBody("""{"n":"pageview","u":"not a url","d":"moneat.io"}""")
            }

            assertEquals(HttpStatusCode.Accepted, response.status)
            assertEquals("/", captured.first().pathname)
        }
}
