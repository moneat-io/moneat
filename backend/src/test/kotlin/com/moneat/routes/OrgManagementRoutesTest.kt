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
import com.moneat.models.Memberships
import com.moneat.models.OrgInvitations
import com.moneat.models.Organizations
import com.moneat.models.Users
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
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
import kotlin.time.Clock

class OrgManagementRoutesTest {
    private val jwtSecret = "test-secret-for-org-management-routes"

    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_org_management_routes;MODE=PostgreSQL;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction {
                SchemaUtils.create(Users, Organizations, Memberships, OrgInvitations)
            }
            dbInitialized = true
        }

        transaction {
            OrgInvitations.deleteAll()
            Memberships.deleteAll()
            Users.deleteAll()
            Organizations.deleteAll()
        }
    }

    @Test
    fun `members endpoint returns organization members and pending invitations`() {
        val orgId = seedOrganization("Acme")
        val ownerId = seedUser("owner@acme.test", "Owner")
        val memberId = seedUser("member@acme.test", "Member")
        seedMembership(orgId, ownerId, "owner")
        seedMembership(orgId, memberId, "member")
        seedInvitation(orgId, "invitee@acme.test", ownerId)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { orgManagementRoutes() }
            }

            val response = client.get("/v1/org/members") {
                header(HttpHeaders.Authorization, "Bearer ${token(ownerId, orgId)}")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("member@acme.test"))
            assertTrue(body.contains("invitee@acme.test"))
            assertTrue(body.contains("\"pendingInvitations\""))
        }
    }

    @Test
    fun `update member role rejects invalid user id parameter`() {
        val orgId = seedOrganization("Acme")
        val ownerId = seedUser("owner@acme.test", "Owner")
        seedMembership(orgId, ownerId, "owner")

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { orgManagementRoutes() }
            }

            val response = client.put("/v1/org/members/not-a-number/role") {
                header(HttpHeaders.Authorization, "Bearer ${token(ownerId, orgId)}")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid user ID"))
        }
    }

    @Test
    fun `revoke invitation rejects invalid invitation id parameter`() {
        val orgId = seedOrganization("Acme")
        val ownerId = seedUser("owner@acme.test", "Owner")
        seedMembership(orgId, ownerId, "owner")

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { orgManagementRoutes() }
            }

            val response = client.delete("/v1/org/invitations/not-a-number") {
                header(HttpHeaders.Authorization, "Bearer ${token(ownerId, orgId)}")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid invitation ID"))
        }
    }

    @Test
    fun `invitation details endpoint requires token query parameter`() {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { orgManagementRoutes() }
            }

            val response = client.get("/v1/org/invitations/details")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Token required"))
        }
    }

    private fun io.ktor.server.application.Application.installAuth() {
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
    }

    private fun token(userId: Int, orgId: Int): String {
        return JWT.create()
            .withIssuer("moneat")
            .withAudience("moneat-users")
            .withClaim("userId", userId)
            .withClaim("orgId", orgId)
            .withClaim("email", "user$userId@test.com")
            .sign(Algorithm.HMAC256(jwtSecret))
    }

    private fun seedOrganization(name: String): Int = transaction {
        Organizations.insert {
            it[Organizations.name] = name
            it[slug] = name.lowercase()
        } get Organizations.id
    }

    private fun seedUser(email: String, name: String): Int = transaction {
        Users.insert {
            it[Users.email] = email
            it[password_hash] = "hashed"
            it[Users.name] = name
            it[email_verified] = true
        } get Users.id
    }

    private fun seedMembership(orgId: Int, userId: Int, role: String) = transaction {
        Memberships.insert {
            it[organization_id] = orgId
            it[Memberships.user_id] = userId
            it[Memberships.role] = role
        }
    }

    private fun seedInvitation(orgId: Int, email: String, inviterId: Int) = transaction {
        OrgInvitations.insert {
            it[organization_id] = orgId
            it[OrgInvitations.email] = email
            it[role] = "member"
            it[invited_by] = inviterId
            it[token] = "token-${System.nanoTime()}"
            it[status] = "pending"
            it[expires_at] = Clock.System.now().toEpochMilliseconds() + 24 * 60 * 60 * 1000
            it[created_at] = Clock.System.now()
        }
    }
}
