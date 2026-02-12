package com.moneat.routes

import com.moneat.services.oncall.EscalationPolicyService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class CreatePolicyRequest(
    val name: String,
    val description: String? = null,
    val repeatCount: Int,
    val steps: List<CreatePolicyStepRequest>
)

@Serializable
data class CreatePolicyStepRequest(
    val stepOrder: Int,
    val timeoutMinutes: Int,
    val targets: List<CreatePolicyTargetRequest>
)

@Serializable
data class CreatePolicyTargetRequest(
    val targetType: String, // USER or ON_CALL_SCHEDULE
    val targetId: Int
)

@Serializable
data class UpdatePolicyRequest(
    val name: String? = null,
    val description: String? = null,
    val repeatCount: Int? = null,
    val steps: List<CreatePolicyStepRequest>? = null
)

fun Route.escalationRoutes() {
    val policyService = EscalationPolicyService()
    
    route("/escalation-policies") {
        authenticate("jwt-auth") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("organization_id")?.asInt()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@get
                }
                
                val policies = policyService.listPolicies(organizationId)
                call.respond(policies)
            }
            
            post {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("organization_id")?.asInt()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@post
                }
                
                val request = call.receive<CreatePolicyRequest>()
                
                try {
                    val steps = request.steps.map { step ->
                        EscalationPolicyService.CreateStepData(
                            stepOrder = step.stepOrder,
                            timeoutMinutes = step.timeoutMinutes,
                            targets = step.targets.map { target ->
                                EscalationPolicyService.CreateTargetData(
                                    targetType = target.targetType,
                                    targetId = target.targetId
                                )
                            }
                        )
                    }
                    
                    val policy = policyService.createPolicy(
                        organizationId = organizationId,
                        name = request.name,
                        description = request.description,
                        repeatCount = request.repeatCount,
                        steps = steps
                    )
                    call.respond(HttpStatusCode.Created, policy)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }
            
            get("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("organization_id")?.asInt()
                val policyId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@get
                }
                
                if (policyId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid policy ID"))
                    return@get
                }
                
                val policy = policyService.getPolicy(policyId)
                if (policy != null && policy.organizationId == organizationId) {
                    call.respond(policy)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Policy not found"))
                }
            }
            
            put("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("organization_id")?.asInt()
                val policyId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@put
                }
                
                if (policyId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid policy ID"))
                    return@put
                }
                
                val existingPolicy = policyService.getPolicy(policyId)
                if (existingPolicy == null || existingPolicy.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Policy not found"))
                    return@put
                }
                
                val request = call.receive<UpdatePolicyRequest>()
                
                try {
                    val steps = request.steps?.map { step ->
                        EscalationPolicyService.CreateStepData(
                            stepOrder = step.stepOrder,
                            timeoutMinutes = step.timeoutMinutes,
                            targets = step.targets.map { target ->
                                EscalationPolicyService.CreateTargetData(
                                    targetType = target.targetType,
                                    targetId = target.targetId
                                )
                            }
                        )
                    }
                    
                    val policy = policyService.updatePolicy(
                        policyId = policyId,
                        name = request.name,
                        description = request.description,
                        repeatCount = request.repeatCount,
                        steps = steps
                    )
                    
                    if (policy != null) {
                        call.respond(policy)
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Policy not found"))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }
            
            delete("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.payload?.getClaim("organization_id")?.asInt()
                val policyId = call.parameters["id"]?.toIntOrNull()
                
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    return@delete
                }
                
                if (policyId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid policy ID"))
                    return@delete
                }
                
                val existingPolicy = policyService.getPolicy(policyId)
                if (existingPolicy == null || existingPolicy.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Policy not found"))
                    return@delete
                }
                
                val deleted = policyService.deletePolicy(policyId)
                if (deleted) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Policy not found"))
                }
            }
        }
    }
}
