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
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import kotlin.time.Duration.Companion.seconds

/**
 * Shared helpers for route tests to reduce duplication of JWT auth setup.
 */
object RouteTestSupport {

    /** Shared JWT secret for all route tests. Tests run in isolation, so one secret is sufficient. */
    const val TEST_JWT_SECRET = "moneat-test-jwt-secret"

    private const val ISSUER = "moneat"
    private const val AUDIENCE = "moneat-users"

    /**
     * Installs ContentNegotiation (json) and JWT authentication using the shared test secret.
     */
    fun Application.installJwtAuth() {
        installJwtAuth(TEST_JWT_SECRET)
    }

    /**
     * Installs ContentNegotiation (json) and JWT authentication with a custom secret.
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

    fun Application.installApiRouteRateLimits(testRequestKey: String = "test-user") {
        install(RateLimit) {
            register(RateLimitName("api")) {
                requestKey { testRequestKey }
                rateLimiter(limit = TEST_RATE_LIMIT, refillPeriod = TEST_RATE_LIMIT_REFILL)
            }
            register(RateLimitName("contact")) {
                requestKey { testRequestKey }
                rateLimiter(limit = TEST_RATE_LIMIT, refillPeriod = TEST_RATE_LIMIT_REFILL)
            }
        }
    }

    /**
     * Creates a JWT token using the shared test secret.
     */
    fun createToken(
        userId: Int,
        orgId: Int? = null,
        email: String = "user$userId@test.com"
    ): String = createToken(TEST_JWT_SECRET, userId, orgId, email)

    /**
     * Creates a JWT token with a custom secret.
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
            .apply { if (orgId != null) withClaim("orgId", orgId) }
            .withClaim("email", email)
            .sign(Algorithm.HMAC256(secret))
    }

    /**
     * Adds Bearer token to request. Use with client.get(path) { withAuth(token) }.
     */
    fun HttpRequestBuilder.withAuth(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private const val TEST_RATE_LIMIT = 1000
    private val TEST_RATE_LIMIT_REFILL = 1.seconds
}
