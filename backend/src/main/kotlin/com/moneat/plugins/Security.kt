package com.moneat.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.services.AuthTokenService
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*

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
    
    install(Authentication) {
        // JWT authentication for user sessions
        jwt("auth-jwt") {
            this.realm = realm
            verifier(
                JWT
                    .require(Algorithm.HMAC256(secret))
                    .withAudience(audience)
                    .withIssuer(issuer)
                    .build()
            )
            validate { credential ->
                if (credential.payload.getClaim("userId").asInt() != null) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
        }
        
        // Bearer token authentication for build tools (CLI, CI/CD)
        bearer("auth-bearer") {
            this.realm = realm
            authenticate { tokenCredential ->
                val validationResult = authTokenService.validateToken(tokenCredential.token)
                if (validationResult != null) {
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
            authenticate { tokenCredential ->
                // First try as auth token
                val validationResult = authTokenService.validateToken(tokenCredential.token)
                if (validationResult != null) {
                    return@authenticate AuthTokenPrincipal(
                        userId = validationResult.userId,
                        scopes = validationResult.scopes,
                        tokenId = validationResult.tokenId
                    )
                }
                
                // If not an auth token, try as JWT
                try {
                    val verifier = JWT
                        .require(Algorithm.HMAC256(secret))
                        .withAudience(audience)
                        .withIssuer(issuer)
                        .build()
                    
                    val decodedJWT = verifier.verify(tokenCredential.token)
                    val userId = decodedJWT.getClaim("userId").asInt()
                    
                    if (userId != null) {
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
