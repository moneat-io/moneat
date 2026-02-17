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
import com.moneat.services.AuthTokenService
import com.moneat.utils.SentryUtils
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

data class AuthTokenPrincipal(
    val userId: Int,
    val scopes: List<String>,
    val tokenId: Int
) : Principal

fun Application.configureSecurity() {
    val config = environment.config
    val secret = config.property("jwt.secret").getString()
    val issuer = config.property("jwt.issuer").getString()
    val audience = config.property("jwt.audience").getString()
    val realm = config.property("jwt.realm").getString()
    val authTokenService = AuthTokenService()
    
    val jwtVerifier = JWT
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
                if (authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true)) {
                    try {
                        val token = authHeader.removePrefix("Bearer ").removePrefix("bearer ").trim()
                        io.ktor.http.auth.HttpAuthHeader.Single("Bearer", token)
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    val cookieToken = call.request.cookies["auth_token"]
                    if (cookieToken != null) {
                        try {
                            io.ktor.http.auth.HttpAuthHeader.Single("Bearer", cookieToken)
                        } catch (e: Exception) {
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
            authenticate { tokenCredential ->
                val validationResult = authTokenService.validateToken(tokenCredential.token)
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
                logger.warn { "!!! authHeader block called, Authorization header: ${authHeader?.take(80)}" }
                if (authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true)) {
                    val token = authHeader.removePrefix("Bearer ").removePrefix("bearer ").trim()
                    logger.warn { "!!! Extracted token: ${token.take(50)}..." }
                    io.ktor.http.auth.HttpAuthHeader.Single("Bearer", token)
                } else {
                    logger.warn { "!!! No Bearer header found" }
                    null
                }
            }
            authenticate { tokenCredential ->
                logger.warn { "!!! auth-combined authenticate() called with token: ${tokenCredential.token.take(50)}..." }
                // First try as auth token
                val validationResult = authTokenService.validateToken(tokenCredential.token)
                logger.warn { "!!! validateToken returned: $validationResult" }
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
                try {
                    val decodedJWT = jwtVerifier.verify(tokenCredential.token)
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
                } catch (e: Exception) {
                    // Not a valid JWT either
                }
                
                null
            }
        }
    }
}
