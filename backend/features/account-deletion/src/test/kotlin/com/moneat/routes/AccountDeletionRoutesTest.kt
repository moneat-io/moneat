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
import com.moneat.auth.routes.accountDeletionRoutes
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountDeletionRoutesTest {
    private val jwtSecret = "test-secret-for-account-deletion-routes"

    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        startTestKoin()
        if (!dbInitialized) {
            Database.connect(
                url =
                "jdbc:h2:mem:moneat_account_deletion_routes;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            dbInitialized = true
        }

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships, Subscriptions)
    }

    private fun Application.installAuth() {
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
    }

    private fun token(userId: Int): String =
        JWT
            .create()
            .withIssuer("moneat")
            .withAudience("moneat-users")
            .withClaim("userId", userId)
            .sign(Algorithm.HMAC256(jwtSecret))

    private fun seedUser(email: String = "user@test.com"): Int =
        transaction {
            Users.insert {
                it[Users.email] = email
                it[password_hash] = "hash"
                it[email_verified] = true
            } get Users.id
        }

    private fun seedOrg(name: String = "Test Org"): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    private fun orgResourceId(orgId: Int): String =
        transaction {
            Organizations
                .selectAll()
                .where { Organizations.id eq orgId }
                .single()[Organizations.resource_id]
                .toString()
        }

    private fun seedMembership(
        userId: Int,
        orgId: Int,
        role: String = "member"
    ) = transaction {
        Memberships.insert {
            it[Memberships.user_id] = userId
            it[organization_id] = orgId
            it[Memberships.role] = role
        }
    }

    // ──── GET /organizations/{orgId} ────

    @Test
    fun `get org details returns 400 for invalid org id`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        accountDeletionRoutes()
                    }
                }
            }

            val userId = seedUser()
            val response =
                client.get("/organizations/not-a-number") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `get org details returns 400 for numeric org id`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        accountDeletionRoutes()
                    }
                }
            }

            val userId = seedUser()
            val orgId = seedOrg()
            val response =
                client.get("/organizations/$orgId") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `get org details returns 404 when user has no membership`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        accountDeletionRoutes()
                    }
                }
            }

            val userId = seedUser()
            val orgId = seedOrg()
            val response =
                client.get("/organizations/${orgResourceId(orgId)}") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `get org details returns 200 with org info for member`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        accountDeletionRoutes()
                    }
                }
            }

            val userId = seedUser()
            val orgId = seedOrg("Acme Corp")
            seedMembership(userId, orgId, "owner")
            val resourceId = orgResourceId(orgId)

            val response =
                client.get("/organizations/$resourceId") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Acme Corp"))
            assertTrue(body.contains(""""id":"$resourceId""""))
            assertTrue(!body.contains(""""id":"$orgId""""))
        }

    // ──── GET /account/deletion-validation ────

    @Test
    fun `account deletion validation returns can delete when user has no owned orgs`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        accountDeletionRoutes()
                    }
                }
            }

            val userId = seedUser()
            val response =
                client.get("/account/deletion-validation") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("true"))
        }

    @Test
    fun `account deletion validation returns cannot delete when user is last owner`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        accountDeletionRoutes()
                    }
                }
            }

            val userId = seedUser()
            val orgId = seedOrg("Solo Org")
            seedMembership(userId, orgId, "owner")

            val response =
                client.get("/account/deletion-validation") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            // canDelete should be false because user is the only owner
            assertTrue(body.contains("false"))
        }

    // ──── GET /organizations/{orgId}/deletion-validation ────

    @Test
    fun `org deletion validation returns 400 for invalid org id`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        accountDeletionRoutes()
                    }
                }
            }

            val userId = seedUser()
            val response =
                client.get("/organizations/not-a-number/deletion-validation") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `org deletion validation returns 400 for numeric org id`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        accountDeletionRoutes()
                    }
                }
            }

            val userId = seedUser()
            val orgId = seedOrg()
            val response =
                client.get("/organizations/$orgId/deletion-validation") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `org deletion validation returns cannot delete for non-owner`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        accountDeletionRoutes()
                    }
                }
            }

            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId, "member")

            val response =
                client.get("/organizations/${orgResourceId(orgId)}/deletion-validation") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("false"))
        }

    @Test
    fun `org deletion validation returns can delete for owner without subscription`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        accountDeletionRoutes()
                    }
                }
            }

            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId, "owner")

            val response =
                client.get("/organizations/${orgResourceId(orgId)}/deletion-validation") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("true"))
        }

    // ──── DELETE /account ────

    @Test
    fun `delete account returns 400 for invalid request body`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        accountDeletionRoutes()
                    }
                }
            }

            val userId = seedUser()
            val response =
                client.delete("/account") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                    contentType(ContentType.Application.Json)
                    setBody("not valid json")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `delete account returns 400 when confirmation does not match email`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        accountDeletionRoutes()
                    }
                }
            }

            val userId = seedUser("real@email.com")
            val response =
                client.delete("/account") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"confirmation":"wrong@email.com"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Confirmation does not match"))
        }

    // ──── DELETE /organizations/{orgId} ────

    @Test
    fun `delete organization returns 400 for invalid org id`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        accountDeletionRoutes()
                    }
                }
            }

            val userId = seedUser()
            val response =
                client.delete("/organizations/not-a-number") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"confirmation":"Org Name"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `delete organization returns 400 for numeric org id`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        accountDeletionRoutes()
                    }
                }
            }

            val userId = seedUser()
            val orgId = seedOrg()
            val response =
                client.delete("/organizations/$orgId") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"confirmation":"Org Name"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `delete organization returns 400 for invalid request body`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        accountDeletionRoutes()
                    }
                }
            }

            val userId = seedUser()
            val orgId = seedOrg()
            seedMembership(userId, orgId, "owner")
            val response =
                client.delete("/organizations/${orgResourceId(orgId)}") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                    contentType(ContentType.Application.Json)
                    setBody("not valid json")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `delete organization returns 404 when org does not exist`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        accountDeletionRoutes()
                    }
                }
            }

            val userId = seedUser()
            val response =
                client.delete("/organizations/11111111-1111-4111-8111-111111111111") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"confirmation":"Nonexistent"}""")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `delete organization returns 400 when confirmation does not match org name`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        accountDeletionRoutes()
                    }
                }
            }

            val userId = seedUser()
            val orgId = seedOrg("My Company")
            seedMembership(userId, orgId, "owner")
            val response =
                client.delete("/organizations/${orgResourceId(orgId)}") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"confirmation":"wrong name"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Confirmation does not match"))
        }

    @Test
    fun `delete organization returns 403 when user is not owner`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    authenticate("auth-jwt") {
                        accountDeletionRoutes()
                    }
                }
            }

            val userId = seedUser()
            val orgId = seedOrg("Member Org")
            seedMembership(userId, orgId, "member")

            val response =
                client.delete("/organizations/${orgResourceId(orgId)}") {
                    header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"confirmation":"Member Org"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @AfterTest
    fun teardownKoin() {
        stopTestKoin()
    }
}
