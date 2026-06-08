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
import io.ktor.server.auth.jwt.JWTPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CurrentOrgContextTest {
    private val secret = Algorithm.HMAC256("current-org-test")

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

    private fun principal(userId: Int, orgId: Int?, role: String?): JWTPrincipal {
        val token = JWT.create()
            .withClaim("userId", userId)
            .apply { orgId?.let { withClaim("orgId", it) } }
            .apply { role?.let { withClaim("orgRole", it) } }
            .sign(secret)
        return JWTPrincipal(JWT.decode(token))
    }
}
