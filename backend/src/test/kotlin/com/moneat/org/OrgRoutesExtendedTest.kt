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

package com.moneat.org

import com.moneat.org.routes.orgManagementRoutes
import com.moneat.org.services.OrgInvitationService
import com.moneat.org.services.OrgMembershipService
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OrgRoutesExtendedTest {

    private val membershipService = mockk<OrgMembershipService>(relaxed = true)
    private val invitationService = mockk<OrgInvitationService>(relaxed = true)

    @BeforeTest
    fun setup() {
        startTestKoin()
        coEvery { membershipService.getMembers(any()) } returns emptyList()
        coEvery { invitationService.getPendingInvitations(any()) } returns emptyList()
        coEvery { invitationService.getInvitationDetails(any()) } throws
            IllegalArgumentException("Invalid or expired invitation token")
    }

    @AfterTest
    fun teardown() {
        stopTestKoin()
    }

    private fun installRoutes(): io.ktor.server.testing.ApplicationTestBuilder.() -> Unit = {
        application {
            with(RouteTestSupport) { installJwtAuth() }
            routing {
                orgManagementRoutes(
                    membershipService = membershipService,
                    invitationService = invitationService
                )
            }
        }
    }

    // ──── GET /v1/org/members ────

    @Test
    fun `get members returns 401 without jwt`() = testApplication {
        installRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/org/members").status)
    }

    @Test
    fun `get members returns 200 with valid jwt`() = testApplication {
        installRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.get("/v1/org/members") { withAuth(token) }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    // ──── PUT /v1/org/members/{userId}/role ────

    @Test
    fun `update member role returns 401 without jwt`() = testApplication {
        installRoutes()()
        val resp = client.put("/v1/org/members/2/role") {
            contentType(ContentType.Application.Json)
            setBody("""{"role":"admin"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `update member role returns 400 for non-numeric userId`() = testApplication {
        installRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.put("/v1/org/members/abc/role") {
            withAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"role":"admin"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    // ──── DELETE /v1/org/members/{userId} ────

    @Test
    fun `remove member returns 401 without jwt`() = testApplication {
        installRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/v1/org/members/2").status)
    }

    @Test
    fun `remove member returns 400 for non-numeric userId`() = testApplication {
        installRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.delete("/v1/org/members/abc") { withAuth(token) }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    // ──── POST /v1/org/invitations ────

    @Test
    fun `invite member returns 401 without jwt`() = testApplication {
        installRoutes()()
        val resp = client.post("/v1/org/invitations") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"test@example.com","role":"member"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    // ──── POST /v1/org/invitations/bulk ────

    @Test
    fun `bulk invite returns 401 without jwt`() = testApplication {
        installRoutes()()
        val resp = client.post("/v1/org/invitations/bulk") {
            contentType(ContentType.Application.Json)
            setBody("""{"emails":["a@b.com"],"role":"member"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    // ──── GET /v1/org/invitations ────

    @Test
    fun `get invitations returns 401 without jwt`() = testApplication {
        installRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/org/invitations").status)
    }

    // ──── DELETE /v1/org/invitations/{invitationId} ────

    @Test
    fun `revoke invitation returns 401 without jwt`() = testApplication {
        installRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/v1/org/invitations/1").status)
    }

    @Test
    fun `revoke invitation returns 400 for non-numeric id`() = testApplication {
        installRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.delete("/v1/org/invitations/abc") { withAuth(token) }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    // ──── POST /v1/org/invitations/{invitationId}/resend ────

    @Test
    fun `resend invitation returns 401 without jwt`() = testApplication {
        installRoutes()()
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/org/invitations/1/resend").status)
    }

    @Test
    fun `resend invitation returns 400 for non-numeric id`() = testApplication {
        installRoutes()()
        val token = RouteTestSupport.createToken(userId = 1, orgId = 10)
        val resp = client.post("/v1/org/invitations/abc/resend") { withAuth(token) }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    // ──── POST /v1/org/invitations/accept ────

    @Test
    fun `accept invitation returns 401 without jwt`() = testApplication {
        installRoutes()()
        val resp = client.post("/v1/org/invitations/accept") {
            contentType(ContentType.Application.Json)
            setBody("""{"token":"some-token"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    // ──── GET /v1/org/invitations/details (no auth required) ────

    @Test
    fun `invitation details returns 400 when token is missing`() = testApplication {
        installRoutes()()
        val resp = client.get("/v1/org/invitations/details")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `invitation details returns 500 for invalid token`() = testApplication {
        installRoutes()()
        val resp = client.get("/v1/org/invitations/details?token=bad-token")
        assertEquals(HttpStatusCode.InternalServerError, resp.status)
    }
}
