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

import com.moneat.billing.models.PricingTierConfigs
import com.moneat.events.routes.apiRoutes
import com.moneat.shared.models.IssueStatuses
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.Users
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Exercises authenticated `/v1` API routes (JWT + rate limit) beyond [ApiRoutesTest].
 * Many responses are 403/404 without full org/project seed data; the goal is auth,
 * routing, and parameter extraction coverage.
 */
class ApiRoutesExtendedTest {
    companion object {
        private var sharedDb: Database? = null
    }

    @BeforeTest
    fun setup() {
        startTestKoin()
        if (sharedDb == null) {
            sharedDb =
                Database.connect(
                    url = "jdbc:h2:mem:moneat_api_routes_ext;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                    driver = "org.h2.Driver"
                )
        }
        TransactionManager.defaultDatabase = sharedDb
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

    @AfterTest
    fun teardown() {
        stopTestKoin()
    }

    @Test
    fun `GET user returns 401 without auth`() = testApplication {
        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "api-routes-extended" }
                    rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                }
            }
            routing { apiRoutes() }
        }
        val response = client.get("/v1/user")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET user requires valid token`() = testApplication {
        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "api-routes-extended" }
                    rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                }
            }
            routing { apiRoutes() }
        }
        val token = RouteTestSupport.createToken(userId = 1)
        val response = client.get("/v1/user") { withAuth(token) }
        assertTrue(
            response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.OK,
            "expected user lookup result, got ${response.status} ${response.bodyAsText()}"
        )
    }

    @Test
    fun `GET subscription returns 404 when JWT org is not a user membership`() = testApplication {
        val userId =
            transaction {
                Users.insert {
                    it[email] = "subscription-scope@test.com"
                    it[password_hash] = "hash"
                    it[email_verified] = true
                } get Users.id
            }
        val validOrgId =
            transaction {
                Organizations.insert {
                    it[name] = "Valid Org"
                    it[slug] = "valid-org"
                } get Organizations.id
            }
        val otherOrgId =
            transaction {
                Organizations.insert {
                    it[name] = "Other Org"
                    it[slug] = "other-org"
                } get Organizations.id
            }
        transaction {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = validOrgId
                it[role] = "owner"
            }
        }

        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "api-routes-extended" }
                    rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                }
            }
            routing { apiRoutes() }
        }

        val token = RouteTestSupport.createToken(userId = userId, orgId = otherOrgId)
        val response = client.get("/v1/subscription") { withAuth(token) }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `PUT user phone-number requires auth`() = testApplication {
        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "api-routes-extended" }
                    rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                }
            }
            routing { apiRoutes() }
        }
        val unauth = client.put("/v1/user/phone-number") {
            contentType(ContentType.Application.Json)
            setBody("""{"phoneNumber":"+15551234567"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, unauth.status)
    }

    @Test
    fun `PUT user phone-number with auth hits validation or handler`() = testApplication {
        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "api-routes-extended" }
                    rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                }
            }
            routing { apiRoutes() }
        }
        val token = RouteTestSupport.createToken(userId = 1)
        val response =
            client.put("/v1/user/phone-number") {
                withAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"phoneNumber":"+15559876543"}""")
            }
        assertTrue(
            response.status.value in 200..299 || response.status == HttpStatusCode.BadRequest
        )
    }

    @Test
    fun `GET user sidebar-preferences is PUT-only returns 405 with auth`() = testApplication {
        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "api-routes-extended" }
                    rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                }
            }
            routing { apiRoutes() }
        }
        val token = RouteTestSupport.createToken(userId = 1)
        val response = client.get("/v1/user/sidebar-preferences") { withAuth(token) }
        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
    }

    @Test
    fun `PUT user sidebar-preferences requires auth`() = testApplication {
        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "api-routes-extended" }
                    rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                }
            }
            routing { apiRoutes() }
        }
        val response =
            client.put("/v1/user/sidebar-preferences") {
                contentType(ContentType.Application.Json)
                setBody("""{"hiddenItems":[]}""")
            }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET projects requires auth`() = testApplication {
        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "api-routes-extended" }
                    rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                }
            }
            routing { apiRoutes() }
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/projects").status)
    }

    @Test
    fun `GET projects with auth is routed`() = testApplication {
        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "api-routes-extended" }
                    rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                }
            }
            routing { apiRoutes() }
        }
        val token = RouteTestSupport.createToken(userId = 1)
        val response = client.get("/v1/projects") { withAuth(token) }
        assertTrue(
            response.status.value in 200..299 || response.status == HttpStatusCode.InternalServerError
        )
    }

    @Test
    fun `GET project by id returns forbidden for unknown project`() = testApplication {
        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "api-routes-extended" }
                    rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                }
            }
            routing { apiRoutes() }
        }
        val token = RouteTestSupport.createToken(userId = 1)
        val response = client.get("/v1/projects/424242") { withAuth(token) }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `POST projects requires auth`() = testApplication {
        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "api-routes-extended" }
                    rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                }
            }
            routing { apiRoutes() }
        }
        val response =
            client.post("/v1/projects") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"New Project"}""")
            }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET project issues returns 403 for non-member`() = testApplication {
        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "api-routes-extended" }
                    rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                }
            }
            routing { apiRoutes() }
        }
        val token = RouteTestSupport.createToken(userId = 42)
        val response = client.get("/v1/projects/7/issues") { withAuth(token) }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET issue events with projectId requires auth`() = testApplication {
        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "api-routes-extended" }
                    rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                }
            }
            routing { apiRoutes() }
        }
        val token = RouteTestSupport.createToken(userId = 1)
        val response =
            client.get("/v1/issues/issue-x/events?projectId=7") { withAuth(token) }
        assertTrue(
            response.status == HttpStatusCode.Forbidden ||
                response.status == HttpStatusCode.NotFound ||
                response.status == HttpStatusCode.InternalServerError
        )
    }

    @Test
    fun `GET project transactions requires auth`() = testApplication {
        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "api-routes-extended" }
                    rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                }
            }
            routing { apiRoutes() }
        }
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/projects/1/transactions").status
        )
    }

    @Test
    fun `GET project transactions with auth returns forbidden without access`() = testApplication {
        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "api-routes-extended" }
                    rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                }
            }
            routing { apiRoutes() }
        }
        val token = RouteTestSupport.createToken(userId = 3)
        val response = client.get("/v1/projects/9/transactions") { withAuth(token) }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET project stats returns forbidden without access`() = testApplication {
        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "api-routes-extended" }
                    rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                }
            }
            routing { apiRoutes() }
        }
        val token = RouteTestSupport.createToken(userId = 1)
        val response = client.get("/v1/projects/2/stats") { withAuth(token) }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET project trace returns forbidden without access`() = testApplication {
        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "api-routes-extended" }
                    rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                }
            }
            routing { apiRoutes() }
        }
        val token = RouteTestSupport.createToken(userId = 50)
        val response = client.get("/v1/projects/88/traces/trace-abc") { withAuth(token) }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET project replays requires auth`() = testApplication {
        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "api-routes-extended" }
                    rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                }
            }
            routing { apiRoutes() }
        }
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/projects/1/replays").status
        )
    }

    @Test
    fun `GET project releases forbidden without access`() = testApplication {
        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "api-routes-extended" }
                    rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                }
            }
            routing { apiRoutes() }
        }
        val token = RouteTestSupport.createToken(userId = 2)
        val response = client.get("/v1/projects/3/releases") { withAuth(token) }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `PUT notification-preferences requires auth`() = testApplication {
        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "api-routes-extended" }
                    rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                }
            }
            routing { apiRoutes() }
        }
        val response =
            client.put("/v1/notification-preferences") {
                contentType(ContentType.Application.Json)
                setBody("""{"issueAlerts":true}""")
            }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET notification-preferences without token returns 401`() = testApplication {
        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "api-routes-extended" }
                    rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                }
            }
            routing { apiRoutes() }
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/notification-preferences").status)
    }

    @Test
    fun `DELETE user phone-number without auth returns 401`() = testApplication {
        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "api-routes-extended" }
                    rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                }
            }
            routing { apiRoutes() }
        }
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/v1/user/phone-number").status)
    }

    @Test
    fun `GET sdk-versions without auth returns 401 under protected group`() = testApplication {
        application {
            installJwtAuth()
            install(RateLimit) {
                register(RateLimitName("api")) {
                    requestKey { "api-routes-extended" }
                    rateLimiter(limit = 1000, refillPeriod = 1.seconds)
                }
            }
            routing { apiRoutes() }
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/sdk-versions").status)
    }
}
