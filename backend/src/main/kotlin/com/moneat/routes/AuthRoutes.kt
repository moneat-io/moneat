package com.moneat.routes

import com.moneat.models.LoginRequest
import com.moneat.models.SignupRequest
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
    }
}
