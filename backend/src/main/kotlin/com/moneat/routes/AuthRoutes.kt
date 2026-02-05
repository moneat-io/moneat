package com.moneat.routes

import com.moneat.models.LoginRequest
import com.moneat.models.ResendVerificationRequest
import com.moneat.models.SignupRequest
import com.moneat.models.VerifyEmailRequest
import com.moneat.services.AuthService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authRoutes() {
    val authService = AuthService()
    
    route("/auth") {
        post("/signup") {
            val request = call.receive<SignupRequest>()
            
            try {
                val result = authService.signup(request)
                call.respond(HttpStatusCode.Created, result)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            }
        }
        
        post("/login") {
            val request = call.receive<LoginRequest>()
            
            val result = authService.login(request)
            if (result != null) {
                call.respond(result)
            } else {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
            }
        }
        
        post("/verify-email") {
            val request = call.receive<VerifyEmailRequest>()
            
            val success = authService.verifyEmail(request.token)
            if (success) {
                call.respond(mapOf("message" to "Email verified successfully"))
            } else {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid or expired token"))
            }
        }
        
        post("/resend-verification") {
            val request = call.receive<ResendVerificationRequest>()
            
            try {
                val success = authService.resendVerificationEmail(request.email)
                if (success) {
                    call.respond(mapOf("message" to "Verification email sent"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to send email"))
                }
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            }
        }
    }
}
