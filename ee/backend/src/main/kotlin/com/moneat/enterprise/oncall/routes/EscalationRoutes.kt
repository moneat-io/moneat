// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.routes

import com.moneat.auth.currentOrgIdOrNull
import com.moneat.auth.currentUserIdOrNull
import com.moneat.enterprise.oncall.services.EscalationPolicyService
import com.moneat.enterprise.oncall.models.EscalationPath
import com.moneat.enterprise.oncall.services.EscalationPathService
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OnCallSchedules
import com.moneat.shared.models.Users
import com.moneat.shared.services.toUuidOrNull
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private const val POLICY_NOT_FOUND_MESSAGE = "Policy not found"

@Serializable
data class CreatePolicyRequest(
    val name: String,
    val description: String? = null,
    val repeatCount: Int,
    val steps: List<CreatePolicyStepRequest>,
    val path: EscalationPath? = null,
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
    val targetId: String,
)

@Serializable
data class UpdatePolicyRequest(
    val name: String? = null,
    val description: String? = null,
    val repeatCount: Int? = null,
    val steps: List<CreatePolicyStepRequest>? = null,
    val path: EscalationPath? = null,
)

@Serializable
data class CreateEscalationPathVersionRequest(
    val path: EscalationPath,
)

fun Route.escalationRoutes() {
    val policyService = EscalationPolicyService()
    val pathService = EscalationPathService()

    route("/v1/escalation-policies") {
        authenticate("auth-jwt") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.currentOrgIdOrNull()

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@get
                }

                val policies = policyService.listPolicies(organizationId)
                call.respond(policies)
            }

            post {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.currentOrgIdOrNull()

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
                                            targetId = resolvePolicyTargetId(organizationId, target),
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
                    request.path?.let { path ->
                        val draft = pathService.createDraft(
                            organizationId,
                            policy.internalId,
                            path,
                            principal.currentUserIdOrNull(),
                        )
                        pathService.publishVersion(organizationId, draft.id)
                    }
                    call.respond(HttpStatusCode.Created, policy)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }

            get("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.currentOrgIdOrNull()
                val policyId = call.resolvePolicyId(call.parameters["id"], organizationId)

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@get
                }

                if (policyId == null) return@get

                val policy = policyService.getPolicy(policyId)
                if (policy != null && policy.organizationId == organizationId) {
                    call.respond(policy)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(POLICY_NOT_FOUND_MESSAGE))
                }
            }

            get("/{id}/versions") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.currentOrgIdOrNull()
                val policyId = call.resolvePolicyId(call.parameters["id"], organizationId)
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@get
                }
                if (policyId == null) return@get
                call.respond(pathService.listVersions(organizationId, policyId))
            }

            post("/{id}/versions") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.currentOrgIdOrNull()
                val policyId = call.resolvePolicyId(call.parameters["id"], organizationId)
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }
                if (policyId == null) return@post
                try {
                    val request = call.receive<CreateEscalationPathVersionRequest>()
                    val version = pathService.createDraft(
                        organizationId,
                        policyId,
                        request.path,
                        principal.currentUserIdOrNull(),
                    )
                    call.respond(HttpStatusCode.Created, version)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }

            post("/{id}/versions/{versionId}/publish") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.currentOrgIdOrNull()
                val policyId = call.resolvePolicyId(call.parameters["id"], organizationId)
                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@post
                }
                if (policyId == null) return@post
                val version = pathService.getVersion(organizationId, call.parameters["versionId"].orEmpty())
                if (version == null || version.policyResourceId != call.parameters["id"]) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Version not found"))
                    return@post
                }
                try {
                    call.respond(pathService.publishVersion(organizationId, version.id)!!)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }

            put("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.currentOrgIdOrNull()
                val policyId = call.resolvePolicyId(call.parameters["id"], organizationId)

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@put
                }

                if (policyId == null) return@put

                val existingPolicy = policyService.getPolicy(policyId)
                if (existingPolicy == null || existingPolicy.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(POLICY_NOT_FOUND_MESSAGE))
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
                                            targetId = resolvePolicyTargetId(organizationId, target),
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

                    request.path?.let { path ->
                        val draft = pathService.createDraft(
                            organizationId,
                            policyId,
                            path,
                            principal.currentUserIdOrNull(),
                        )
                        pathService.publishVersion(organizationId, draft.id)
                    }

                    if (policy != null) {
                        call.respond(policy)
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(POLICY_NOT_FOUND_MESSAGE))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }

            delete("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val organizationId = principal?.currentOrgIdOrNull()
                val policyId = call.resolvePolicyId(call.parameters["id"], organizationId)

                if (organizationId == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                    return@delete
                }

                if (policyId == null) return@delete

                val existingPolicy = policyService.getPolicy(policyId)
                if (existingPolicy == null || existingPolicy.organizationId != organizationId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(POLICY_NOT_FOUND_MESSAGE))
                    return@delete
                }

                val deleted = policyService.deletePolicy(policyId)
                if (deleted) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(POLICY_NOT_FOUND_MESSAGE))
                }
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.resolvePolicyId(
    raw: String?,
    organizationId: Int?,
): Int? {
    if (organizationId == null) return null
    val resourceId = parseEscalationResourceId(raw)
    if (resourceId == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid policy ID"))
        return null
    }
    val policyId =
        transaction {
            EscalationPolicies
                .selectAll()
                .where {
                    (EscalationPolicies.organizationId eq organizationId) and
                        (EscalationPolicies.resourceId eq resourceId)
                }
                .firstOrNull()
                ?.get(EscalationPolicies.id)
                ?.value
        }
    if (policyId == null) {
        respond(HttpStatusCode.NotFound, ErrorResponse(POLICY_NOT_FOUND_MESSAGE))
    }
    return policyId
}

private fun parseEscalationResourceId(raw: String?): Uuid? =
    raw?.toUuidOrNull()

private fun resolvePolicyTargetId(
    organizationId: Int,
    target: CreatePolicyTargetRequest,
): Int {
    val resourceId =
        target.targetId.toUuidOrNull() ?: throw IllegalArgumentException("Invalid target ID")
    return transaction {
        when (target.targetType) {
            "USER" ->
                Users
                    .innerJoin(Memberships)
                    .selectAll()
                    .where {
                        (Users.resource_id eq resourceId) and
                            (Memberships.organization_id eq organizationId)
                    }
                    .firstOrNull()
                    ?.get(Users.id)

            "ON_CALL_SCHEDULE" ->
                OnCallSchedules
                    .selectAll()
                    .where {
                        (OnCallSchedules.resourceId eq resourceId) and
                            (OnCallSchedules.organizationId eq organizationId)
                    }
                    .firstOrNull()
                    ?.get(OnCallSchedules.id)
                    ?.value

            else -> throw IllegalArgumentException("Unsupported target type")
        }
    } ?: throw IllegalArgumentException("Target not found")
}
