// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.routes

import com.moneat.enterprise.services.oncall.PushNotificationService
import io.ktor.http.HttpStatusCode
import com.moneat.utils.ErrorResponse
import com.moneat.utils.MessageResponse
import com.moneat.utils.BooleanResponse
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class RegisterDeviceRequest(
    val deviceToken: String,
    val platform: String, // IOS, ANDROID, WEB
    val deviceName: String? = null
)

fun Route.deviceRoutes() {
    val pushService = PushNotificationService()
    
    route("/v1/devices") {
        authenticate("auth-jwt") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                
                if (userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@get
                }
                
                val devices = pushService.getUserDevices(userId)
                call.respond(devices)
            }
            
            post {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                
                if (userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }
                
                val request = call.receive<RegisterDeviceRequest>()
                
                try {
                    val registered = pushService.registerDeviceToken(
                        userId = userId,
                        deviceToken = request.deviceToken,
                        platform = request.platform,
                        deviceName = request.deviceName
                    )
                    
                    if (registered) {
                        call.respond(HttpStatusCode.Created, MessageResponse("Device registered"))
                    } else {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Failed to register device"))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }
            
            delete("/{token}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                val deviceToken = call.parameters["token"]
                
                if (userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@delete
                }
                
                if (deviceToken == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid device token"))
                    return@delete
                }
                
                val unregistered = pushService.unregisterDeviceToken(userId, deviceToken)
                if (unregistered) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Device not found"))
                }
            }
        }
    }
}
