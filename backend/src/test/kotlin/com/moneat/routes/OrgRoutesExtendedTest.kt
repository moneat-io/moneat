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

import com.moneat.events.models.BulkInviteFailure
import com.moneat.events.models.BulkInviteResult
import com.moneat.events.models.InvitationDetailsResponse
import com.moneat.events.models.InvitationResponse
import com.moneat.org.routes.orgManagementRoutes
import com.moneat.org.services.OrgInvitationService
import com.moneat.org.services.OrgMembershipService
import com.moneat.org.services.OrgRole
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrgInvitations
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.RouteTestSupport.createToken
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
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
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class OrgRoutesExtendedTest {

    companion object {
        private const val OWNER_EMAIL = "owner@ext.test"
        private const val MEMBER_EMAIL = "member@ext.test"
        private const val EMAIL_A_EXT = "a@ext.test"
        private const val EMAIL_B_EXT = "b@ext.test"
        private const val EMAIL_OK_EXT = "ok@ext.test"
        private const val INVITATION_RESOURCE_ID = "00000000-0000-0000-0000-000000000042"
        private const val RESEND_INVITATION_RESOURCE_ID = "00000000-0000-0000-0000-000000000007"
    }

    private val mockMembershipService = mockk<OrgMembershipService>(relaxed = true)
    private val mockInvitationService = mockk<OrgInvitationService>(relaxed = true)

    @BeforeTest
    fun setupDatabase() {
        val db = Database.connect(
            url = "jdbc:h2:mem:moneat_org_ext;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships, OrgInvitations)
    }

    private fun Application.installAuth() {
        installJwtAuth()
    }

    private fun token(userId: Int, orgId: Int): String =
        createToken(userId, orgId)

    private fun seedOrg(name: String): Int = transaction {
        Organizations.insert {
            it[Organizations.name] = name
            it[Organizations.slug] = name.lowercase()
        } get Organizations.id
    }

    private fun seedUser(email: String): Int = transaction {
        Users.insert {
            it[Users.email] = email
            it[Users.password_hash] = "hashed"
            it[Users.name] = email.substringBefore("@")
            it[Users.email_verified] = true
        } get Users.id
    }

    private fun userResourceId(userId: Int): String =
        transaction {
            Users
                .selectAll()
                .where { Users.id eq userId }
                .single()[Users.resource_id]
                .toString()
        }

    private fun seedMembership(orgId: Int, userId: Int, role: String) = transaction {
        Memberships.insert {
            it[Memberships.organization_id] = orgId
            it[Memberships.user_id] = userId
            it[Memberships.role] = role
        }
    }

    // ── DELETE /v1/org/members/{userId} ─────────────────────────

    @Test
    fun `DELETE member delegates to membershipService removeMember`() {
        val orgId = seedOrg("Acme")
        val ownerId = seedUser(OWNER_EMAIL)
        val memberId = seedUser(MEMBER_EMAIL)
        seedMembership(orgId, ownerId, "owner")
        seedMembership(orgId, memberId, "member")

        every { mockMembershipService.removeMember(orgId, memberId, ownerId) } returns true
        every {
            mockMembershipService.resolveMemberUserId(orgId, Uuid.parse(userResourceId(memberId)))
        } returns memberId

        testApplication {
            application {
                installAuth()
                routing { orgManagementRoutes(mockMembershipService, mockInvitationService) }
            }
            val response = client.delete("/v1/org/members/${userResourceId(memberId)}") {
                header(HttpHeaders.Authorization, "Bearer ${token(ownerId, orgId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("true"))
            verify { mockMembershipService.removeMember(orgId, memberId, ownerId) }
        }
    }

    @Test
    fun `DELETE member returns 400 for non-numeric user id`() {
        val orgId = seedOrg("Acme")
        val ownerId = seedUser(OWNER_EMAIL)
        seedMembership(orgId, ownerId, "owner")

        testApplication {
            application {
                installAuth()
                routing { orgManagementRoutes(mockMembershipService, mockInvitationService) }
            }
            val response = client.delete("/v1/org/members/abc") {
                header(HttpHeaders.Authorization, "Bearer ${token(ownerId, orgId)}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid user ID"))
        }
    }

    // ── PUT /v1/org/members/{userId}/role (happy path) ──────────

    @Test
    fun `PUT member role delegates to membershipService updateMemberRole`() {
        val orgId = seedOrg("Acme")
        val ownerId = seedUser(OWNER_EMAIL)
        val memberId = seedUser(MEMBER_EMAIL)
        seedMembership(orgId, ownerId, "owner")
        seedMembership(orgId, memberId, "member")

        every {
            mockMembershipService.updateMemberRole(orgId, memberId, "admin", ownerId)
        } returns true
        every {
            mockMembershipService.resolveMemberUserId(orgId, Uuid.parse(userResourceId(memberId)))
        } returns memberId

        testApplication {
            application {
                installAuth()
                routing { orgManagementRoutes(mockMembershipService, mockInvitationService) }
            }
            val response = client.put("/v1/org/members/${userResourceId(memberId)}/role") {
                header(HttpHeaders.Authorization, "Bearer ${token(ownerId, orgId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"role":"admin"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("true"))
            verify {
                mockMembershipService.updateMemberRole(orgId, memberId, "admin", ownerId)
            }
        }
    }

    // ── POST /v1/org/invitations/bulk ───────────────────────────

    @Test
    fun `POST bulk invite delegates to invitationService bulkInvite`() {
        val orgId = seedOrg("Acme")
        val ownerId = seedUser(OWNER_EMAIL)
        seedMembership(orgId, ownerId, "owner")

        val emails = listOf(EMAIL_A_EXT, EMAIL_B_EXT)
        every {
            mockInvitationService.bulkInvite(orgId, emails, "member", ownerId)
        } returns BulkInviteResult(
            success = listOf(EMAIL_A_EXT, EMAIL_B_EXT),
            failed = emptyList()
        )

        testApplication {
            application {
                installAuth()
                routing { orgManagementRoutes(mockMembershipService, mockInvitationService) }
            }
            val response = client.post("/v1/org/invitations/bulk") {
                header(HttpHeaders.Authorization, "Bearer ${token(ownerId, orgId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"emails":["$EMAIL_A_EXT","$EMAIL_B_EXT"],"role":"member"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains(EMAIL_A_EXT))
            assertTrue(body.contains(EMAIL_B_EXT))
            verify { mockInvitationService.bulkInvite(orgId, emails, "member", ownerId) }
        }
    }

    @Test
    fun `POST bulk invite returns partial failures`() {
        val orgId = seedOrg("Acme")
        val ownerId = seedUser(OWNER_EMAIL)
        seedMembership(orgId, ownerId, "owner")

        val emails = listOf(EMAIL_OK_EXT, "bad@ext.test")
        every {
            mockInvitationService.bulkInvite(orgId, emails, "member", ownerId)
        } returns BulkInviteResult(
            success = listOf(EMAIL_OK_EXT),
            failed = listOf(BulkInviteFailure("bad@ext.test", "Already a member"))
        )

        testApplication {
            application {
                installAuth()
                routing { orgManagementRoutes(mockMembershipService, mockInvitationService) }
            }
            val response = client.post("/v1/org/invitations/bulk") {
                header(HttpHeaders.Authorization, "Bearer ${token(ownerId, orgId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"emails":["$EMAIL_OK_EXT","bad@ext.test"],"role":"member"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains(EMAIL_OK_EXT))
            assertTrue(body.contains("Already a member"))
        }
    }

    // ── GET /v1/org/invitations ─────────────────────────────────

    @Test
    fun `GET invitations returns pending invitations`() {
        val orgId = seedOrg("Acme")
        val ownerId = seedUser(OWNER_EMAIL)
        seedMembership(orgId, ownerId, "owner")

        every { mockMembershipService.requireRole(orgId, ownerId, OrgRole.ADMIN) } just runs
        every { mockInvitationService.getPendingInvitations(orgId) } returns listOf(
            InvitationResponse(
                id = "00000000-0000-0000-0000-000000000001",
                email = "pending@ext.test",
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
            val response = client.get("/v1/org/invitations") {
                header(HttpHeaders.Authorization, "Bearer ${token(ownerId, orgId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("pending@ext.test"))
            verify { mockMembershipService.requireRole(orgId, ownerId, OrgRole.ADMIN) }
        }
    }

    // ── DELETE /v1/org/invitations/{invitationId} (happy path) ──

    @Test
    fun `DELETE invitation delegates to invitationService revokeInvitation`() {
        val orgId = seedOrg("Acme")
        val ownerId = seedUser(OWNER_EMAIL)
        seedMembership(orgId, ownerId, "owner")

        every { mockInvitationService.revokeInvitation(Uuid.parse(INVITATION_RESOURCE_ID), ownerId) } returns true

        testApplication {
            application {
                installAuth()
                routing { orgManagementRoutes(mockMembershipService, mockInvitationService) }
            }
            val response = client.delete("/v1/org/invitations/$INVITATION_RESOURCE_ID") {
                header(HttpHeaders.Authorization, "Bearer ${token(ownerId, orgId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("true"))
            verify { mockInvitationService.revokeInvitation(Uuid.parse(INVITATION_RESOURCE_ID), ownerId) }
        }
    }

    // ── POST /v1/org/invitations/{invitationId}/resend ──────────

    @Test
    fun `POST resend invitation delegates to invitationService`() {
        val orgId = seedOrg("Acme")
        val ownerId = seedUser(OWNER_EMAIL)
        seedMembership(orgId, ownerId, "owner")

        every {
            mockInvitationService.resendInvitation(Uuid.parse(RESEND_INVITATION_RESOURCE_ID), ownerId)
        } returns true

        testApplication {
            application {
                installAuth()
                routing { orgManagementRoutes(mockMembershipService, mockInvitationService) }
            }
            val response = client.post("/v1/org/invitations/$RESEND_INVITATION_RESOURCE_ID/resend") {
                header(HttpHeaders.Authorization, "Bearer ${token(ownerId, orgId)}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("true"))
            verify { mockInvitationService.resendInvitation(Uuid.parse(RESEND_INVITATION_RESOURCE_ID), ownerId) }
        }
    }

    @Test
    fun `POST resend invitation returns 400 for non-numeric id`() {
        val orgId = seedOrg("Acme")
        val ownerId = seedUser(OWNER_EMAIL)
        seedMembership(orgId, ownerId, "owner")

        testApplication {
            application {
                installAuth()
                routing { orgManagementRoutes(mockMembershipService, mockInvitationService) }
            }
            val response = client.post("/v1/org/invitations/xyz/resend") {
                header(HttpHeaders.Authorization, "Bearer ${token(ownerId, orgId)}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid invitation ID"))
        }
    }

    // ── POST /v1/org/invitations/accept ─────────────────────────

    @Test
    fun `POST accept invitation delegates to invitationService`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser(MEMBER_EMAIL)
        seedMembership(orgId, userId, "member")

        every { mockInvitationService.acceptInvitation("valid-token", userId) } returns true

        testApplication {
            application {
                installAuth()
                routing { orgManagementRoutes(mockMembershipService, mockInvitationService) }
            }
            val response = client.post("/v1/org/invitations/accept") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"token":"valid-token"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("true"))
            verify { mockInvitationService.acceptInvitation("valid-token", userId) }
        }
    }

    // ── GET /v1/org/invitations/details (happy path) ────────────

    @Test
    fun `GET invitation details returns details for valid token`() {
        every { mockInvitationService.getInvitationDetails("tok-123") } returns
            InvitationDetailsResponse(
                orgName = "Acme",
                role = "member",
                invitedBy = "owner",
                expiresAt = "2026-12-31T00:00:00Z",
                valid = true
            )

        testApplication {
            application {
                installAuth()
                routing { orgManagementRoutes(mockMembershipService, mockInvitationService) }
            }
            val response = client.get("/v1/org/invitations/details?token=tok-123")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Acme"))
            assertTrue(body.contains("\"valid\":true"))
        }
    }

    // ── Service exception propagation ───────────────────────────

    @Test
    fun `GET members propagates service exception as 500`() {
        val orgId = seedOrg("Acme")
        val ownerId = seedUser(OWNER_EMAIL)
        seedMembership(orgId, ownerId, "owner")

        every {
            mockMembershipService.requireRole(orgId, ownerId, OrgRole.MEMBER)
        } throws IllegalStateException("DB unreachable")

        testApplication {
            application {
                installAuth()
                routing { orgManagementRoutes(mockMembershipService, mockInvitationService) }
            }
            val response = client.get("/v1/org/members") {
                header(HttpHeaders.Authorization, "Bearer ${token(ownerId, orgId)}")
            }
            assertEquals(HttpStatusCode.InternalServerError, response.status)
        }
    }

    @Test
    fun `POST invite propagates requireRole exception as 500`() {
        val orgId = seedOrg("Acme")
        val userId = seedUser(MEMBER_EMAIL)
        seedMembership(orgId, userId, "member")

        every {
            mockMembershipService.requireRole(orgId, userId, OrgRole.ADMIN)
        } throws IllegalStateException("Insufficient permissions")

        testApplication {
            application {
                installAuth()
                routing { orgManagementRoutes(mockMembershipService, mockInvitationService) }
            }
            val response = client.post("/v1/org/invitations") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId, orgId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"email":"new@ext.test","role":"member"}""")
            }
            assertEquals(HttpStatusCode.InternalServerError, response.status)
        }
    }

    @Test
    fun `DELETE member propagates service exception as 500`() {
        val orgId = seedOrg("Acme")
        val ownerId = seedUser(OWNER_EMAIL)
        val memberId = seedUser(MEMBER_EMAIL)
        seedMembership(orgId, ownerId, "owner")
        seedMembership(orgId, memberId, "member")

        every {
            mockMembershipService.resolveMemberUserId(orgId, Uuid.parse(userResourceId(memberId)))
        } returns memberId
        every {
            mockMembershipService.removeMember(orgId, memberId, ownerId)
        } throws IllegalStateException("Member not found")

        testApplication {
            application {
                installAuth()
                routing { orgManagementRoutes(mockMembershipService, mockInvitationService) }
            }
            val response = client.delete("/v1/org/members/${userResourceId(memberId)}") {
                header(HttpHeaders.Authorization, "Bearer ${token(ownerId, orgId)}")
            }
            assertEquals(HttpStatusCode.InternalServerError, response.status)
        }
    }
}
