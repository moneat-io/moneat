package com.moneat.routes

import com.moneat.services.oncall.PushNotificationService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class RegisterDeviceRequest(
    val deviceToken: String,
    val platform: String, // IOS, ANDROID, WEB
    val deviceName: String? = null
)

fun Route.deviceRoutes() {
    val pushService = PushNotificationService()
    
    route("/devices") {
        authenticate("auth-jwt") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                
                if (userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@get
                }
                
                val devices = pushService.getUserDevices(userId)
                call.respond(devices)
            }
            
            post {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                
                if (userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
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
                        call.respond(HttpStatusCode.Created, mapOf("message" to "Device registered"))
                    } else {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Failed to register device"))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }
            
            delete("/{token}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asInt()
                val deviceToken = call.parameters["token"]
                
                if (userId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@delete
                }
                
                if (deviceToken == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid device token"))
                    return@delete
                }
                
                val unregistered = pushService.unregisterDeviceToken(userId, deviceToken)
                if (unregistered) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Device not found"))
                }
            }
        }
    }
}
