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

package com.moneat.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.auth.services.AuthTokenService
import com.moneat.utils.SentryUtils
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.BearerTokenCredential
import io.ktor.server.auth.bearer
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import mu.KotlinLogging
import com.moneat.utils.suspendRunCatching
import java.util.Base64

private val logger = KotlinLogging.logger {}

data class AuthTokenPrincipal(
    val userId: Int,
    val scopes: List<String>,
    val tokenId: Int
)

private fun extractBearerToken(rawHeader: String?): String? {
    if (rawHeader == null || !rawHeader.startsWith("Bearer ", ignoreCase = true)) {
        return null
    }

    val rawToken = rawHeader.removePrefix("Bearer ").removePrefix("bearer ").trim()
    if (rawToken.isBlank()) {
        return null
    }

    val token =
        if (rawToken.any { it.isWhitespace() }) {
            logger.warn("Authorization token contains whitespace; normalizing before parsing")
            rawToken.filterNot { it.isWhitespace() }
        } else {
            rawToken
        }

    return token.takeIf { it.isNotBlank() }
}

private fun parseBearerHeaderSafely(rawHeader: String?): HttpAuthHeader? {
    val token = extractBearerToken(rawHeader) ?: return null

    return suspendRunCatching {
        HttpAuthHeader.Single("Bearer", token)
    }.getOrElse { e ->
        logger.warn(e) { "Invalid Bearer auth header format (tokenLength=${token.length})" }
        null
    }
}

private fun parseBearerHeaderForKtor(rawHeader: String?): HttpAuthHeader? {
    val token = extractBearerToken(rawHeader) ?: return null
    val encodedToken = Base64.getUrlEncoder().withoutPadding().encodeToString(token.toByteArray())

    return suspendRunCatching {
        HttpAuthHeader.Single("Bearer", encodedToken)
    }.getOrElse { e ->
        logger.warn(e) { "Failed to build token68-compatible bearer header (tokenLength=${token.length})" }
        null
    }
}

private fun decodeKtorBearerToken(tokenCredential: BearerTokenCredential): String {
    return try {
        String(Base64.getUrlDecoder().decode(tokenCredential.token))
    } catch (_: IllegalArgumentException) {
        // Backward compatibility for already token68-safe tokens.
        tokenCredential.token
    }
}

fun Application.configureSecurity() {
    val config = environment.config
    val secret = config.property("jwt.secret").getString()
    val issuer = config.property("jwt.issuer").getString()
    val audience = config.property("jwt.audience").getString()
    val realm = config.property("jwt.realm").getString()
    val authTokenService = AuthTokenService()

    val jwtVerifier =
        JWT
            .require(Algorithm.HMAC256(secret))
            .withAudience(audience)
            .withIssuer(issuer)
            .build()

    install(Authentication) {
        // JWT authentication for user sessions (reads from Authorization header or auth_token cookie)
        jwt("auth-jwt") {
            this.realm = realm
            verifier(jwtVerifier)
            validate { credential ->
                val userId = credential.payload.getClaim("userId").asInt()
                if (userId != null) {
                    // Set user context in Sentry
                    val email = credential.payload.getClaim("email").asString()
                    SentryUtils.setUser(userId, email = email)
                    SentryUtils.setTag("auth.type", "jwt")

                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
            // Fall back to reading JWT from httpOnly cookie when no Authorization header present
            authHeader { call ->
                val authHeader = call.request.headers["Authorization"]
                parseBearerHeaderSafely(authHeader) ?: run {
                    val cookieToken = call.request.cookies["auth_token"]
                    if (cookieToken != null) {
                        suspendRunCatching {
                            io.ktor.http.auth.HttpAuthHeader
                                .Single("Bearer", cookieToken)
                        }.getOrElse { _ ->
                            null
                        }
                    } else {
                        null
                    }
                }
            }
        }

        // Bearer token authentication for build tools (CLI, CI/CD)
        bearer("auth-bearer") {
            this.realm = realm
            authSchemes("Bearer")
            authHeader { call ->
                parseBearerHeaderForKtor(call.request.headers["Authorization"])
            }
            authenticate { tokenCredential ->
                val token = decodeKtorBearerToken(tokenCredential)
                val validationResult = authTokenService.validateToken(token)
                if (validationResult != null) {
                    // Set user context in Sentry
                    SentryUtils.setUser(validationResult.userId)
                    SentryUtils.setTag("auth.type", "bearer")
                    SentryUtils.setTag("auth.token_id", validationResult.tokenId.toString())

                    AuthTokenPrincipal(
                        userId = validationResult.userId,
                        scopes = validationResult.scopes,
                        tokenId = validationResult.tokenId
                    )
                } else {
                    null
                }
            }
        }

        // Combined authentication - accepts either JWT or Bearer token
        // Use this for endpoints that should work with both user sessions and auth tokens
        bearer("auth-combined") {
            this.realm = realm
            authSchemes("Bearer")
            authHeader { call ->
                // Extract Authorization header manually
                val authHeader = call.request.headers["Authorization"]
                parseBearerHeaderForKtor(authHeader)
            }
            authenticate { tokenCredential ->
                val token = decodeKtorBearerToken(tokenCredential)
                // First try as auth token
                val validationResult = authTokenService.validateToken(token)
                if (validationResult != null) {
                    // Set user context in Sentry
                    SentryUtils.setUser(validationResult.userId)
                    SentryUtils.setTag("auth.type", "bearer")
                    SentryUtils.setTag("auth.token_id", validationResult.tokenId.toString())

                    return@authenticate AuthTokenPrincipal(
                        userId = validationResult.userId,
                        scopes = validationResult.scopes,
                        tokenId = validationResult.tokenId
                    )
                }

                // If not an auth token, try as JWT
                suspendRunCatching {
                    val decodedJWT = jwtVerifier.verify(token)
                    val userId = decodedJWT.getClaim("userId").asInt()

                    if (userId != null) {
                        // Set user context in Sentry
                        val email = decodedJWT.getClaim("email").asString()
                        SentryUtils.setUser(userId, email = email)
                        SentryUtils.setTag("auth.type", "jwt")

                        // Return as AuthTokenPrincipal with full scopes for JWT users
                        return@authenticate AuthTokenPrincipal(
                            userId = userId,
                            scopes = AuthTokenService.VALID_SCOPES.toList(),
                            tokenId = -1 // Not applicable for JWT
                        )
                    }
                }.getOrElse { _ ->
                    // Not a valid JWT either
                }

                null
            }
        }
    }
}
