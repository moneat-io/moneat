package com.moneat.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.config.ClickHouseClient
import com.moneat.models.Memberships
import com.moneat.models.Organizations
import com.moneat.models.Projects
import com.moneat.models.Users
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.Collections
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ApiRoutesTest {
    private val jwtSecret = "test-secret-for-unit-tests"

    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_api_routes;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction {
                SchemaUtils.create(Users, Organizations, Memberships, Projects)
            }
            dbInitialized = true
        }

        transaction {
            Memberships.deleteAll()
            Projects.deleteAll()
            Users.deleteAll()
            Organizations.deleteAll()
        }
    }

    @Test
    fun `issues route forwards pagination and status filter to dashboard query`() {
        val queries = Collections.synchronizedList(mutableListOf<String>())
        MockHttpServer { exchange ->
            val query = exchange.requestBodyText()
            queries += query
            if (query.contains("FROM test.events e") && query.contains("GROUP BY issue_id")) {
                exchange.respond(
                    200,
                    """
                    {"issue_id":"issue-api-1","title":"API crash","culprit":"controller","level":"error","platform":"kotlin","first_seen":"2026-02-01T00:00:00.000Z","last_seen":"2026-02-02T00:00:00.000Z","event_count":4,"user_count":2,"status":"resolved"}
                    """.trimIndent(),
                    contentType = "text/plain"
                )
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
                                JWT.require(Algorithm.HMAC256(jwtSecret))
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

                val response = client.get("/v1/projects/-1/issues?page=2&limit=5&status=resolved") {
                    header(HttpHeaders.Authorization, "Bearer ${demoToken()}")
                }

                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains("issue-api-1"))
                assertTrue(queries.any { it.contains("LIMIT 5 OFFSET 5") })
                assertTrue(queries.any { it.contains("AND status = 'resolved'") })
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
                                JWT.require(Algorithm.HMAC256(jwtSecret))
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

                val response = client.get("/v1/issues/missing-issue") {
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
                            JWT.require(Algorithm.HMAC256(jwtSecret))
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

            val response = client.get("/v1/projects/99/issues") {
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
                            JWT.require(Algorithm.HMAC256(jwtSecret))
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

            val response = client.get("/v1/projects/99/traces/trace-1") {
                header(HttpHeaders.Authorization, "Bearer ${regularToken(userId = 555)}")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    private fun demoToken(): String {
        return JWT.create()
            .withIssuer("moneat")
            .withAudience("moneat-users")
            .withClaim("userId", -1)
            .withClaim("email", "demo@moneat.dev")
            .withClaim("isDemo", true)
            .sign(Algorithm.HMAC256(jwtSecret))
    }

    private fun regularToken(userId: Int): String {
        return JWT.create()
            .withIssuer("moneat")
            .withAudience("moneat-users")
            .withClaim("userId", userId)
            .withClaim("email", "user$userId@test.com")
            .sign(Algorithm.HMAC256(jwtSecret))
    }
}
