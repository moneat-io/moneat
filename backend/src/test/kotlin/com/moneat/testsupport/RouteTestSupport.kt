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

package com.moneat.testsupport

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/**
 * Shared helpers for route tests to reduce duplication of JWT auth setup.
 * Each test class should use a unique JWT_SECRET to avoid token collisions.
 */
object RouteTestSupport {

    private const val ISSUER = "moneat"
    private const val AUDIENCE = "moneat-users"

    /**
     * Installs ContentNegotiation (json) and JWT authentication on the application.
     * Use with a unique secret per test class.
     */
    fun Application.installJwtAuth(secret: String) {
        install(ContentNegotiation) { json() }
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(
                    JWT.require(Algorithm.HMAC256(secret))
                        .withIssuer(ISSUER)
                        .withAudience(AUDIENCE)
                        .build()
                )
                validate { JWTPrincipal(it.payload) }
            }
        }
    }

    /**
     * Creates a JWT token for route tests. Use the same secret as installJwtAuth.
     */
    fun createToken(
        secret: String,
        userId: Int,
        orgId: Int? = null,
        email: String = "user$userId@test.com"
    ): String {
        return JWT.create()
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withClaim("userId", userId)
            .apply { orgId?.let { withClaim("orgId", it) } }
            .withClaim("email", email)
            .sign(Algorithm.HMAC256(secret))
    }
}
