package com.moneat.routes

import com.moneat.models.*
import com.moneat.services.AuthService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
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
            
            try {
                val result = authService.login(request)
                if (result != null) {
                    call.respond(result)
                } else {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
                }
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to e.message))
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
        
        post("/forgot-password") {
            val request = call.receive<ForgotPasswordRequest>()
            
            // Always return success to prevent email enumeration
            val success = authService.requestPasswordReset(request.email)
            call.respond(mapOf("message" to "If an account exists with this email, a password reset link has been sent"))
        }
        
        post("/reset-password") {
            val request = call.receive<ResetPasswordRequest>()
            
            try {
                val success = authService.resetPassword(request.token, request.newPassword)
                if (success) {
                    call.respond(mapOf("message" to "Password reset successfully"))
                } else {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid or expired token"))
                }
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            }
        }
    }
    
    authenticate("auth-jwt") {
        route("/auth") {
            post("/complete-onboarding") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val request = call.receive<CompleteOnboardingRequest>()
                
                try {
                    val user = authService.completeOnboarding(userId, request.organizationName, request.companySize)
                    call.respond(user)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }
        }
    }
}
