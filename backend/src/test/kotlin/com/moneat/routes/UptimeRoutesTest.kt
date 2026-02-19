package com.moneat.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.models.Organizations
import com.moneat.models.UptimeMonitors
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import java.util.UUID

class UptimeRoutesTest {
    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_uptime_routes;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction {
                SchemaUtils.create(
                    Organizations,
                    UptimeMonitors
                )
            }
            dbInitialized = true
        }

        transaction {
            UptimeMonitors.deleteAll()
            Organizations.deleteAll()
        }
    }

    private fun seedPushMonitor(token: String = "token-1234"): UUID {
        val orgId = transaction {
            Organizations.insert {
                it[name] = "Uptime Routes Org"
                it[slug] = "uptime-routes-org"
            } get Organizations.id
        }

        val monitorId = UUID.randomUUID()
        val now = Clock.System.now()
        transaction {
            UptimeMonitors.insert {
                it[id] = monitorId
                it[organizationId] = orgId
                it[name] = "Push monitor"
                it[type] = "push"
                it[active] = true
                it[method] = "GET"
                it[maxRedirects] = 10
                it[ignoreTls] = false
                it[keywordInverse] = false
                it[sslExpiryWarnDays] = 30
                it[intervalSeconds] = 60
                it[timeoutSeconds] = 10
                it[retries] = 0
                it[retryIntervalSeconds] = 60
                it[status] = "pending"
                it[lastStatusChangeAt] = now
                it[consecutiveFailures] = 0
                it[pushToken] = token
                it[createdAt] = now
                it[updatedAt] = now
            }
        }

        return monitorId
    }

    @Test
    fun `push endpoint returns not found for invalid token`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                jwt("auth-jwt") {
                    verifier(
                        JWT.require(Algorithm.HMAC256("test-secret"))
                            .withIssuer("moneat")
                            .withAudience("moneat-users")
                            .build()
                    )
                    validate { JWTPrincipal(it.payload) }
                }
            }
            routing { uptimeRoutes() }
        }

        val response = client.post("/v1/uptime/push/does-not-exist")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `push endpoint records heartbeat and updates monitor status`() = testApplication {
        val monitorId = seedPushMonitor(token = "ok-token")

        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                jwt("auth-jwt") {
                    verifier(
                        JWT.require(Algorithm.HMAC256("test-secret"))
                            .withIssuer("moneat")
                            .withAudience("moneat-users")
                            .build()
                    )
                    validate { JWTPrincipal(it.payload) }
                }
            }
            routing { uptimeRoutes() }
        }

        val response = client.post("/v1/uptime/push/ok-token") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"0","msg":"probe failed","ping":45}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("true", body["ok"]?.jsonPrimitive?.content)

        val status = transaction {
            UptimeMonitors.selectAll().first { it[UptimeMonitors.id] == monitorId }[UptimeMonitors.status]
        }
        assertEquals("down", status)
    }
}
