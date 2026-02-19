// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.routes

import com.moneat.enterprise.models.BusinessHoursWindow
import com.moneat.enterprise.services.oncall.BusinessHoursService
import com.moneat.enterprise.services.oncall.PriorityService
import com.moneat.utils.BooleanResponse
import com.moneat.utils.ErrorResponse
import com.moneat.utils.MessageResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.time.LocalTime

@Serializable
data class UpdatePriorityRequest(
    val severity: String,
    val priorityLevel: String,
    val isPageable: Boolean,
    val label: String,
    val description: String? = null,
)

@Serializable
data class UpdateBusinessHoursRequest(
    val timezone: String,
    val enabled: Boolean,
    val windows: List<BusinessHoursWindowRequest>,
)

@Serializable
data class BusinessHoursWindowRequest(
    val dayOfWeek: Int,
    val startTime: String, // HH:MM:SS format
    val endTime: String,
)

fun Route.priorityRoutes() {
    val priorityService = PriorityService()
    val businessHoursService = BusinessHoursService()

    route("/v1/priorities") {
        authenticate("auth-jwt") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@get
                }

                val priorities = priorityService.getAllPriorities(organizationId)
                call.respond(priorities)
            }

            put {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@put
                }

                val request = call.receive<UpdatePriorityRequest>()

                try {
                    val priority =
                        priorityService.updatePriority(
                            organizationId = organizationId,
                            severity = request.severity,
                            priorityLevel = request.priorityLevel,
                            isPageable = request.isPageable,
                            label = request.label,
                            description = request.description,
                        )

                    if (priority != null) {
                        call.respond(priority)
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Priority not found"))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }
        }
    }

    route("/v1/business-hours") {
        authenticate("auth-jwt") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@get
                }

                val config = businessHoursService.getBusinessHours(organizationId)
                if (config != null) {
                    call.respond(config)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Business hours not configured"))
                }
            }

            put {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@put
                }

                val request = call.receive<UpdateBusinessHoursRequest>()

                try {
                    val windows =
                        request.windows.map { w ->
                            BusinessHoursWindow(
                                dayOfWeek = w.dayOfWeek,
                                startTime = LocalTime.parse(w.startTime),
                                endTime = LocalTime.parse(w.endTime),
                            )
                        }

                    val config =
                        businessHoursService.updateBusinessHours(
                            organizationId = organizationId,
                            timezone = request.timezone,
                            enabled = request.enabled,
                            windows = windows,
                        )
                    call.respond(config)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }
        }
    }
}
