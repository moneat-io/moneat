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
import io.ktor.server.auth.jwt.JWTPrincipal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CurrentOrgContextTest {
    @Test
    fun `current org context is extracted from signed jwt claims`() {
        val principal =
            principalFromClaims(
                "userId" to 42,
                "orgId" to 7,
                "orgRole" to "admin",
            )

        val context = principal.currentOrgContextOrNull()

        assertEquals(CurrentOrgContext(userId = 42, orgId = 7, orgRole = "admin"), context)
    }

    @Test
    fun `current org context is absent when org claim is missing`() {
        val principal =
            principalFromClaims(
                "userId" to 42,
                "orgRole" to "admin",
            )

        assertNull(principal.currentOrgContextOrNull())
    }

    @Test
    fun `current org context is absent when user claim is missing`() {
        val principal =
            principalFromClaims(
                "orgId" to 7,
                "orgRole" to "admin",
            )

        assertNull(principal.currentOrgContextOrNull())
    }

    private fun principalFromClaims(vararg claims: Pair<String, Any>): JWTPrincipal {
        val builder = JWT.create()
        claims.forEach { (name, value) ->
            when (value) {
                is Int -> builder.withClaim(name, value)
                is String -> builder.withClaim(name, value)
                else -> error("Unsupported claim type")
            }
        }
        return JWTPrincipal(JWT.decode(builder.sign(com.auth0.jwt.algorithms.Algorithm.none())))
    }
}
