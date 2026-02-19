// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.routes

import com.moneat.enterprise.services.oncall.EscalationPolicyService
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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class CreatePolicyRequest(
    val name: String,
    val description: String? = null,
    val repeatCount: Int,
    val steps: List<CreatePolicyStepRequest>,
)

@Serializable
data class CreatePolicyStepRequest(
    val stepOrder: Int,
    val timeoutMinutes: Int,
    val smsFallbackDelayMinutes: Int = 2,
    val targets: List<CreatePolicyTargetRequest>,
)

@Serializable
data class CreatePolicyTargetRequest(
    val targetType: String, // USER or ON_CALL_SCHEDULE
    val targetId: Int,
)

@Serializable
data class UpdatePolicyRequest(
    val name: String? = null,
    val description: String? = null,
    val repeatCount: Int? = null,
    val steps: List<CreatePolicyStepRequest>? = null,
)

fun Route.escalationRoutes() {
    val policyService = EscalationPolicyService()

    route("/v1/escalation-policies") {
        authenticate("auth-jwt") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@get
                }

                val policies = policyService.listPolicies(organizationId)
                call.respond(policies)
            }

            post {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }

                val request = call.receive<CreatePolicyRequest>()

                try {
                    val steps =
                        request.steps.map { step ->
                            EscalationPolicyService.CreateStepData(
                                stepOrder = step.stepOrder,
                                timeoutMinutes = step.timeoutMinutes,
                                smsFallbackDelayMinutes = step.smsFallbackDelayMinutes,
                                targets =
                                    step.targets.map { target ->
                                        EscalationPolicyService.CreateTargetData(
                                            targetType = target.targetType,
                                            targetId = target.targetId,
                                        )
                                    },
                            )
                        }

                    val policy =
                        policyService.createPolicy(
                            organizationId = organizationId,
                            name = request.name,
                            description = request.description,
                            repeatCount = request.repeatCount,
                            steps = steps,
                        )
                    call.respond(HttpStatusCode.Created, policy)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }

            get("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val policyId = call.parameters["id"]?.toIntOrNull()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@get
                }

                if (policyId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid policy ID"))
                    return@get
                }

                val policy = policyService.getPolicy(policyId)
                if (policy != null && policy.organizationId == organizationId) {
                    call.respond(policy)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Policy not found"))
                }
            }

            put("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val policyId = call.parameters["id"]?.toIntOrNull()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@put
                }

                if (policyId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid policy ID"))
                    return@put
                }

                val existingPolicy = policyService.getPolicy(policyId)
                if (existingPolicy == null || existingPolicy.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Policy not found"))
                    return@put
                }

                val request = call.receive<UpdatePolicyRequest>()

                try {
                    val steps =
                        request.steps?.map { step ->
                            EscalationPolicyService.CreateStepData(
                                stepOrder = step.stepOrder,
                                timeoutMinutes = step.timeoutMinutes,
                                smsFallbackDelayMinutes = step.smsFallbackDelayMinutes,
                                targets =
                                    step.targets.map { target ->
                                        EscalationPolicyService.CreateTargetData(
                                            targetType = target.targetType,
                                            targetId = target.targetId,
                                        )
                                    },
                            )
                        }

                    val policy =
                        policyService.updatePolicy(
                            policyId = policyId,
                            name = request.name,
                            description = request.description,
                            repeatCount = request.repeatCount,
                            steps = steps,
                        )

                    if (policy != null) {
                        call.respond(policy)
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Policy not found"))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }

            delete("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("orgId")?.asInt()
                val policyId = call.parameters["id"]?.toIntOrNull()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@delete
                }

                if (policyId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid policy ID"))
                    return@delete
                }

                val existingPolicy = policyService.getPolicy(policyId)
                if (existingPolicy == null || existingPolicy.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Policy not found"))
                    return@delete
                }

                val deleted = policyService.deletePolicy(policyId)
                if (deleted) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Policy not found"))
                }
            }
        }
    }
}
