// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.routes

import com.moneat.enterprise.oncall.models.BusinessHoursWindow
import com.moneat.enterprise.oncall.services.BusinessHoursService
import com.moneat.enterprise.oncall.services.PriorityService
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.time.LocalTime

private const val INVALID_TOKEN_MESSAGE = "Invalid token"

@Serializable
data class UpdatePriorityRequest(
    val priority: String? = null,
    val priorityLevel: String? = null,
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
            registerGetPrioritiesRoute(priorityService)
            registerUpdatePriorityRoute(priorityService)
        }
    }

    route("/v1/business-hours") {
        authenticate("auth-jwt") {
            registerGetBusinessHoursRoute(businessHoursService)
            registerUpdateBusinessHoursRoute(businessHoursService)
        }
    }
}

private suspend fun ApplicationCall.requireOrganizationId(): Int? {
    val organizationId = principal<JWTPrincipal>()?.payload?.getClaim("orgId")?.asInt()
    if (organizationId == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
    }
    return organizationId
}

private fun Route.registerGetPrioritiesRoute(priorityService: PriorityService) {
    get {
        val organizationId = call.requireOrganizationId() ?: return@get
        val priorities = priorityService.getAllPriorities(organizationId)
        call.respond(priorities)
    }
}

private fun Route.registerUpdatePriorityRoute(priorityService: PriorityService) {
    put {
        val organizationId = call.requireOrganizationId() ?: return@put
        val request = call.receive<UpdatePriorityRequest>()

        try {
            val requestedPriority = request.priority ?: request.priorityLevel
            if (requestedPriority.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing alert priority"))
                return@put
            }

            val priority =
                priorityService.updatePriority(
                    organizationId = organizationId,
                    priority = requestedPriority,
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

private fun Route.registerGetBusinessHoursRoute(businessHoursService: BusinessHoursService) {
    get {
        val organizationId = call.requireOrganizationId() ?: return@get
        val config = businessHoursService.getBusinessHours(organizationId)
        if (config != null) {
            call.respond(config)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Business hours not configured"))
        }
    }
}

private fun Route.registerUpdateBusinessHoursRoute(businessHoursService: BusinessHoursService) {
    put {
        val organizationId = call.requireOrganizationId() ?: return@put
        val request = call.receive<UpdateBusinessHoursRequest>()

        try {
            val windows = request.windows.map(::businessHoursWindow)
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

private fun businessHoursWindow(window: BusinessHoursWindowRequest): BusinessHoursWindow =
    BusinessHoursWindow(
        dayOfWeek = window.dayOfWeek,
        startTime = LocalTime.parse(window.startTime),
        endTime = LocalTime.parse(window.endTime),
    )
