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

package com.moneat.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.withAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CurrentOrgContextTest {
    private val secret = Algorithm.HMAC256(SECRET_VALUE)

    @Test
    fun `current org context is extracted from signed jwt claims`() {
        val principal = principal(userId = 7, orgId = 42, role = "owner")

        val context = principal.currentOrgContextOrNull()

        assertEquals(CurrentOrgContext(userId = 7, orgId = 42, orgRole = "owner"), context)
    }

    @Test
    fun `current org context is absent when org claim is missing`() {
        val principal = principal(userId = 7, orgId = null, role = "owner")

        assertNull(principal.currentOrgContextOrNull())
    }

    @Test
    fun `current org context is absent when user claim is missing`() {
        val token = JWT.create()
            .withClaim("orgId", 42)
            .sign(secret)

        assertNull(JWTPrincipal(JWT.decode(token)).currentOrgContextOrNull())
    }

    @Test
    fun `decoded jwt current org context is extracted from claims`() {
        val decoded = JWT.decode(token(userId = 8, orgId = 44, role = "admin"))

        val context = decoded.currentOrgContextOrNull()

        assertEquals(CurrentOrgContext(userId = 8, orgId = 44, orgRole = "admin"), context)
    }

    @Test
    fun `decoded jwt current org context is absent when required claims are missing`() {
        val missingOrg = JWT.decode(token(userId = 8, orgId = null, role = "admin"))
        val missingUserToken = JWT.create()
            .withClaim("orgId", 44)
            .sign(secret)

        assertNull(missingOrg.currentOrgContextOrNull())
        assertNull(JWT.decode(missingUserToken).currentOrgContextOrNull())
    }

    @Test
    fun `application call current org helpers use authenticated jwt claims`() =
        testApplication {
            installCurrentOrgTestRoute()

            val token = token(userId = 9, orgId = 33, role = "member")
            val requireResponse = client.get("/require-current-org") { withAuth(token) }
            val orgIdResponse = client.get("/current-org-id") { withAuth(token) }

            assertEquals(HttpStatusCode.OK, requireResponse.status)
            assertEquals("9:33:member", requireResponse.bodyAsText())
            assertEquals(HttpStatusCode.OK, orgIdResponse.status)
            assertEquals("33", orgIdResponse.bodyAsText())
        }

    @Test
    fun `require current org rejects authenticated jwt without org claim`() =
        testApplication {
            installCurrentOrgTestRoute()

            val response = client.get("/require-current-org") {
                withAuth(token(userId = 9, orgId = null, role = "member"))
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    private fun io.ktor.server.testing.ApplicationTestBuilder.installCurrentOrgTestRoute() {
        application {
            with(RouteTestSupport) { installJwtAuth(SECRET_VALUE) }
            routing {
                authenticate("auth-jwt") {
                    get("/require-current-org") {
                        val context = call.requireCurrentOrg() ?: return@get
                        call.respondText("${context.userId}:${context.orgId}:${context.orgRole}")
                    }
                    get("/current-org-id") {
                        call.respondText(call.currentOrgIdOrNull()?.toString() ?: "missing")
                    }
                }
            }
        }
    }

    private fun principal(userId: Int, orgId: Int?, role: String?): JWTPrincipal {
        return JWTPrincipal(JWT.decode(token(userId, orgId, role)))
    }

    private fun token(userId: Int, orgId: Int?, role: String?): String =
        JWT.create()
            .withIssuer("moneat")
            .withAudience("moneat-users")
            .withClaim("userId", userId)
            .apply { orgId?.let { withClaim("orgId", it) } }
            .apply { role?.let { withClaim("orgRole", it) } }
            .sign(secret)

    private companion object {
        const val SECRET_VALUE = "current-org-test"
    }
}
