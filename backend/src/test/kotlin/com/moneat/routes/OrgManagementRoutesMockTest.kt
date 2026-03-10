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
import com.moneat.events.models.InvitationResponse
import com.moneat.events.models.OrgMemberResponse
import com.moneat.org.routes.orgManagementRoutes
import com.moneat.org.services.OrgInvitationService
import com.moneat.org.services.OrgMembershipService
import com.moneat.org.services.OrgRole
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrgInvitations
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrgManagementRoutesMockTest {

    companion object {
        private const val JWT_SECRET = "org-mock-secret"
        private const val OWNER_EMAIL = "owner@acme.test"
        private const val NEW_MEMBER_EMAIL = "new@acme.test"
        private var dbInitialized = false
    }

    private val mockMembershipService = mockk<OrgMembershipService>(relaxed = true)
    private val mockInvitationService = mockk<OrgInvitationService>(relaxed = true)

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_org_mock;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            dbInitialized = true
        }
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships, OrgInvitations)
    }

    private fun Application.installAuth() {
        install(ContentNegotiation) { json() }
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(
                    JWT.require(Algorithm.HMAC256(JWT_SECRET))
                        .withIssuer("moneat").withAudience("moneat-users").build()
                )
                validate { JWTPrincipal(it.payload) }
            }
        }
    }

    private fun token(userId: Int, orgId: Int): String = JWT.create()
        .withIssuer("moneat")
        .withAudience("moneat-users")
        .withClaim("userId", userId)
        .withClaim("orgId", orgId)
        .withClaim("email", "user$userId@test.com")
        .sign(Algorithm.HMAC256(JWT_SECRET))

    private fun seedOrg(name: String): Int = transaction {
        Organizations.insert {
            it[Organizations.name] = name
            it[slug] = name.lowercase()
        } get Organizations.id
    }

    private fun seedUser(email: String): Int = transaction {
        Users.insert {
            it[Users.email] = email
            it[password_hash] = "hashed"
            it[Users.name] = email.substringBefore("@")
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

    @Test
    fun `GET members returns members and invitations from service`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser(OWNER_EMAIL)
        seedMembership(orgId, userId, "owner")

        every { mockMembershipService.requireRole(orgId, userId, OrgRole.MEMBER) } just runs
        every { mockMembershipService.getMembers(orgId) } returns listOf(
            OrgMemberResponse(
                userId = userId, email = OWNER_EMAIL, name = "owner", role = "owner", joinedAt = null
            )
        )
        every { mockInvitationService.getPendingInvitations(orgId) } returns listOf(
            InvitationResponse(
                id = 1,
                email = "invitee@acme.test",
                role = "member",
                status = "pending",
                invitedBy = "owner",
                invitedByEmail = OWNER_EMAIL,
                createdAt = "2026-01-01T00:00:00Z",
                expiresAt = "2026-12-31T00:00:00Z"
            )
        )

        testApplication {
            application {
                installAuth()
                routing { orgManagementRoutes(mockMembershipService, mockInvitationService) }
            }
            val response = client.get("/v1/org/members") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains(OWNER_EMAIL))
            assertTrue(body.contains("invitee@acme.test"))
        }
    }

    @Test
    fun `PUT member role returns 400 for non-numeric user id`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser(OWNER_EMAIL)
        seedMembership(orgId, userId, "owner")

        testApplication {
            application {
                installAuth()
                routing { orgManagementRoutes(mockMembershipService, mockInvitationService) }
            }
            val response = client.put("/v1/org/members/not-a-number/role") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"role":"member"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid user ID"))
        }
    }

    @Test
    fun `DELETE invitation returns 400 for non-numeric invitation id`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser(OWNER_EMAIL)
        seedMembership(orgId, userId, "owner")

        testApplication {
            application {
                installAuth()
                routing { orgManagementRoutes(mockMembershipService, mockInvitationService) }
            }
            val response = client.delete("/v1/org/invitations/abc") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid invitation ID"))
        }
    }

    @Test
    fun `GET invitation details returns 400 without token param`() {
        testApplication {
            application {
                installAuth()
                routing { orgManagementRoutes(mockMembershipService, mockInvitationService) }
            }
            val response = client.get("/v1/org/invitations/details")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Token required"))
        }
    }

    @Test
    fun `POST invite member delegates to invitationService`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser(OWNER_EMAIL)
        seedMembership(orgId, userId, "owner")

        every { mockMembershipService.requireRole(orgId, userId, OrgRole.ADMIN) } just runs
        every { mockInvitationService.inviteMember(orgId, NEW_MEMBER_EMAIL, "member", userId) } returns
            InvitationResponse(
                id = 99,
                email = NEW_MEMBER_EMAIL,
                role = "member",
                status = "pending",
                invitedBy = "owner",
                invitedByEmail = OWNER_EMAIL,
                createdAt = "2026-01-01T00:00:00Z",
                expiresAt = "2026-12-31T00:00:00Z"
            )

        testApplication {
            application {
                installAuth()
                routing { orgManagementRoutes(mockMembershipService, mockInvitationService) }
            }
            val response = client.post("/v1/org/invitations") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$NEW_MEMBER_EMAIL","role":"member"}""")
            }
            assertEquals(HttpStatusCode.Created, response.status)
            verify { mockInvitationService.inviteMember(orgId, NEW_MEMBER_EMAIL, "member", userId) }
        }
    }
}
