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
import com.moneat.config.ClickHouseClient
import com.moneat.events.routes.apiRoutes
import com.moneat.billing.models.PricingTierConfigs
import com.moneat.shared.models.IssueStatuses
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.Users
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import java.util.Collections
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import com.moneat.testsupport.TestDatabaseHelper

class ApiRoutesTest {
    private val jwtSecret = "test-secret-for-unit-tests"

    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_api_routes;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            dbInitialized = true
        }

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Memberships,
            Projects,
            IssueStatuses,
            Subscriptions,
            PricingTierConfigs
        )
    }

    @Test
    fun `issues route forwards pagination and status filter to dashboard query`() {
        val queries = Collections.synchronizedList(mutableListOf<String>())
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            queries += query
            if (query.contains("FROM `test`.events e") && query.contains("GROUP BY issue_id")) {
                exchange.respond(
                    200,
                    """
                    {"issue_id":"issue-api-1","title":"API crash","culprit":"controller","level":"error","platform":"kotlin","first_seen":"2026-02-01T00:00:00.000Z","last_seen":"2026-02-02T00:00:00.000Z","event_count":4,"user_count":2}
                    """.trimIndent(),
                    contentType = "text/plain"
                )
            } else {
                exchange.respond(200, "", contentType = "text/plain")
            }
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            // Insert PG status row so overlay returns "resolved"
            org.jetbrains.exposed.v1.jdbc.transactions.transaction {
                Organizations.insert {
                    it[id] = 1
                    it[name] = "Test Org"
                    it[slug] = "test-org"
                }
                Projects.insert {
                    it[id] = -1
                    it[organization_id] = 1
                    it[name] = "Test Project"
                    it[slug] = "test-project"
                }
                IssueStatuses.insert {
                    it[issue_id] = "issue-api-1"
                    it[project_id] = -1
                    it[status] = "resolved"
                    it[updated_at] = kotlin.time.Clock.System.now()
                }
            }

            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(Authentication) {
                        jwt("auth-jwt") {
                            verifier(
                                JWT
                                    .require(Algorithm.HMAC256(jwtSecret))
                                    .withIssuer("moneat")
                                    .withAudience("moneat-users")
                                    .build()
                            )
                            validate { JWTPrincipal(it.payload) }
                        }
                    }
                    install(RateLimit) {
                        register(RateLimitName("api")) {
                            requestKey { "test-user" }
                            rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                        }
                    }
                    routing { apiRoutes() }
                }

                val response =
                    client.get("/v1/projects/-1/issues?page=2&limit=5&status=resolved") {
                        header(HttpHeaders.Authorization, "Bearer ${demoToken()}")
                    }

                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains("issue-api-1"))
                assertTrue(queries.any { it.contains("LIMIT 5 OFFSET 5") })
            }
        }
    }

    @Test
    fun `issue detail route returns 404 when no issue exists`() {
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            if (query.contains("FROM test.issues") && query.contains("WHERE issue_id = 'missing-issue'")) {
                exchange.respond(200, "", contentType = "text/plain")
            } else {
                exchange.respond(200, "", contentType = "text/plain")
            }
        }.use { server ->
            ClickHouseClient.close()
            ClickHouseClient.init(server.baseUrl, "test", "default", "")

            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(Authentication) {
                        jwt("auth-jwt") {
                            verifier(
                                JWT
                                    .require(Algorithm.HMAC256(jwtSecret))
                                    .withIssuer("moneat")
                                    .withAudience("moneat-users")
                                    .build()
                            )
                            validate { JWTPrincipal(it.payload) }
                        }
                    }
                    install(RateLimit) {
                        register(RateLimitName("api")) {
                            requestKey { "test-user" }
                            rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                        }
                    }
                    routing { apiRoutes() }
                }

                val response =
                    client.get("/v1/issues/missing-issue") {
                        header(HttpHeaders.Authorization, "Bearer ${demoToken()}")
                    }
                assertEquals(HttpStatusCode.NotFound, response.status)
            }
        }
    }

    @Test
    fun `issues route denies non demo user without project access`() {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(Authentication) {
                    jwt("auth-jwt") {
                        verifier(
                            JWT
                                .require(Algorithm.HMAC256(jwtSecret))
                                .withIssuer("moneat")
                                .withAudience("moneat-users")
                                .build()
                        )
                        validate { JWTPrincipal(it.payload) }
                    }
                }
                install(RateLimit) {
                    register(RateLimitName("api")) {
                        requestKey { "test-user" }
                        rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                    }
                }
                routing { apiRoutes() }
            }

            val response =
                client.get("/v1/projects/99/issues") {
                    header(HttpHeaders.Authorization, "Bearer ${regularToken(userId = 123)}")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    @Test
    fun `trace route denies non demo user without project access`() {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(Authentication) {
                    jwt("auth-jwt") {
                        verifier(
                            JWT
                                .require(Algorithm.HMAC256(jwtSecret))
                                .withIssuer("moneat")
                                .withAudience("moneat-users")
                                .build()
                        )
                        validate { JWTPrincipal(it.payload) }
                    }
                }
                install(RateLimit) {
                    register(RateLimitName("api")) {
                        requestKey { "test-user" }
                        rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                    }
                }
                routing { apiRoutes() }
            }

            val response =
                client.get("/v1/projects/99/traces/trace-1") {
                    header(HttpHeaders.Authorization, "Bearer ${regularToken(userId = 555)}")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    private fun demoToken(): String {
        return JWT
            .create()
            .withIssuer("moneat")
            .withAudience("moneat-users")
            .withClaim("userId", -1)
            .withClaim("email", "demo@moneat.dev")
            .withClaim("isDemo", true)
            .sign(Algorithm.HMAC256(jwtSecret))
    }

    private fun regularToken(userId: Int): String {
        return JWT
            .create()
            .withIssuer("moneat")
            .withAudience("moneat-users")
            .withClaim("userId", userId)
            .withClaim("email", "user$userId@test.com")
            .sign(Algorithm.HMAC256(jwtSecret))
    }
}
