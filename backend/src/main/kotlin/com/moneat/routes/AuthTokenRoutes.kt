package com.moneat.routes

import com.moneat.models.CreateAuthTokenRequest
import com.moneat.models.UpdateAuthTokenRequest
import com.moneat.services.AuthTokenService
import io.ktor.http.*
import com.moneat.utils.ErrorResponse
import com.moneat.utils.MessageResponse
import com.moneat.utils.BooleanResponse
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authTokenRoutes() {
    val authTokenService = AuthTokenService()
    
    authenticate("auth-jwt") {
        route("/v1/auth-tokens") {
            // Create a new auth token
            post {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val request = call.receive<CreateAuthTokenRequest>()
                
                try {
                    val tokenResponse = authTokenService.generateToken(
                        userId = userId,
                        name = request.name,
                        scopes = request.scopes,
                        expiresInDays = request.expiresInDays
                    )
                    call.respond(HttpStatusCode.Created, tokenResponse)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }
            
            // List all tokens for the current user
            get {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val tokens = authTokenService.listUserTokens(userId)
                call.respond(tokens)
            }
            
            // Revoke a token
            delete("/{tokenId}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val tokenId = call.parameters["tokenId"]?.toIntOrNull()
                if (tokenId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid token ID"))
                    return@delete
                }
                
                val success = authTokenService.revokeToken(userId, tokenId)
                if (success) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Token not found"))
                }
            }
            
            // Update a token (name and/or scopes)
            put("/{tokenId}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val tokenId = call.parameters["tokenId"]?.toIntOrNull()
                if (tokenId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid token ID"))
                    return@put
                }
                
                val request = call.receive<UpdateAuthTokenRequest>()
                
                try {
                    val success = authTokenService.updateToken(
                        userId = userId,
                        tokenId = tokenId,
                        name = request.name,
                        scopes = request.scopes
                    )
                    
                    if (success) {
                        call.respond(HttpStatusCode.OK)
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Token not found"))
                    }
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }
        }
    }
}
