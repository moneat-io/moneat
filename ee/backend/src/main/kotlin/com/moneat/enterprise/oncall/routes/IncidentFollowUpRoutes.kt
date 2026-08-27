// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.routes

import com.moneat.enterprise.incidents.commands.AcceptIncidentFollowUpCommand
import com.moneat.enterprise.incidents.commands.AddIncidentFollowUpCommand
import com.moneat.enterprise.incidents.commands.CancelIncidentFollowUpCommand
import com.moneat.enterprise.incidents.commands.CompleteIncidentFollowUpCommand
import com.moneat.enterprise.incidents.commands.IncidentCommand
import com.moneat.enterprise.incidents.commands.IncidentCommandActor
import com.moneat.enterprise.incidents.commands.IncidentCommandException
import com.moneat.enterprise.incidents.commands.UpdateIncidentFollowUpCommand
import com.moneat.enterprise.incidents.followups.IncidentFollowUpPriority
import com.moneat.enterprise.incidents.followups.IncidentFollowUpService
import com.moneat.enterprise.incidents.followups.IncidentFollowUpStatus
import com.moneat.enterprise.incidents.commands.IncidentEntitlement
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.enterprise.oncall.services.OnCallIncidentService
import com.moneat.enterprise.incidents.models.NativeIncidentRoleAssignments
import com.moneat.enterprise.incidents.models.NativeIncidentParticipants
import com.moneat.enterprise.incidents.models.IncidentParticipationType
import com.moneat.enterprise.incidents.models.NativeIncidentVisibility
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrganizationTeams
import com.moneat.shared.models.Users
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class CreateIncidentFollowUpRequest(
    val title: String,
    val description: String,
    val ownerUserId: String? = null,
    val ownerTeamId: String? = null,
    val priority: String = "P2",
    val labels: List<String> = emptyList(),
    val dueAt: String? = null,
    val slaMinutes: Int? = null,
    val reminderMinutes: Int? = null,
    val source: String = "API",
    val slackChannelId: String? = null,
    val slackMessageTs: String? = null,
    val expectedVersion: Int? = null,
)

@Serializable
data class UpdateIncidentFollowUpRequest(
    val title: String? = null,
    val description: String? = null,
    val ownerUserId: String? = null,
    val ownerTeamId: String? = null,
    val priority: String? = null,
    val labels: List<String>? = null,
    val dueAt: String? = null,
    val clearDueAt: Boolean = false,
    val slaMinutes: Int? = null,
    val reminderMinutes: Int? = null,
    val clearReminderAt: Boolean = false,
    val expectedVersion: Int? = null,
)

@Serializable
data class IncidentFollowUpStatusRequest(
    val note: String? = null,
    val expectedVersion: Int? = null,
)

internal fun Route.registerIncidentFollowUpRoutes(
    onCallIncidentService: OnCallIncidentService,
    followUpService: IncidentFollowUpService,
    incidentEntitlement: IncidentEntitlement,
) {
    registerFollowUpQueueRoutes(followUpService, incidentEntitlement)
    registerFollowUpIncidentRoutes(onCallIncidentService, followUpService, incidentEntitlement)
}

private fun Route.registerFollowUpQueueRoutes(
    followUpService: IncidentFollowUpService,
    incidentEntitlement: IncidentEntitlement,
) {
    route("/v1/on-call/follow-ups") {
        authenticate("auth-jwt") {
            installNativeIncidentEntitlementGate("NativeIncidentFollowUpsQueueGate", incidentEntitlement)
            get {
                call.handleFollowUpQueue(followUpService)
            }
        }
    }
}

private fun Route.registerFollowUpIncidentRoutes(
    onCallIncidentService: OnCallIncidentService,
    followUpService: IncidentFollowUpService,
    incidentEntitlement: IncidentEntitlement,
) {
    route("/v1/on-call/incidents/{id}/follow-ups") {
        authenticate("auth-jwt") {
            installNativeIncidentEntitlementGate("NativeIncidentFollowUpsGate", incidentEntitlement)
            get {
                call.handleFollowUpList(onCallIncidentService, followUpService)
            }
            post {
                call.handleFollowUpCreate(onCallIncidentService, followUpService)
            }
            route("/{followUpId}") {
                get { call.handleFollowUpGet(onCallIncidentService, followUpService) }
                post("/update") { call.handleFollowUpUpdate(onCallIncidentService, followUpService) }
                post("/accept") { call.handleFollowUpTransition(onCallIncidentService, followUpService, "accept") }
                post("/complete") { call.handleFollowUpTransition(onCallIncidentService, followUpService, "complete") }
                post("/cancel") { call.handleFollowUpTransition(onCallIncidentService, followUpService, "cancel") }
            }
        }
    }
}

private suspend fun ApplicationCall.handleFollowUpQueue(followUpService: IncidentFollowUpService) {
    val context = requireUserContext() ?: return
    val statuses: Set<IncidentFollowUpStatus>
    val priority: IncidentFollowUpPriority?
    try {
        statuses = parseFollowUpStatuses(request.queryParameters.getAll("status"))
        priority = request.queryParameters["priority"]?.let {
            IncidentFollowUpPriority.parse(it) ?: throw IllegalArgumentException("Invalid follow-up priority")
        }
    } catch (error: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(error.message))
        return
    }
    respond(
        followUpService.queue(
            organizationId = context.organizationId,
            statuses = statuses,
            priority = priority,
            visibleIncidentIds = visibleIncidentIds(context),
        ),
    )
}

private suspend fun ApplicationCall.handleFollowUpList(
    onCallIncidentService: OnCallIncidentService,
    followUpService: IncidentFollowUpService,
) {
    val context = requireFollowUpContext(onCallIncidentService) ?: return
    respond(followUpService.list(context.organizationId, context.incidentId))
}

private suspend fun ApplicationCall.handleFollowUpCreate(
    onCallIncidentService: OnCallIncidentService,
    followUpService: IncidentFollowUpService,
) {
    val context = requireFollowUpContext(onCallIncidentService, requireResponder = true) ?: return
    val request = receive<CreateIncidentFollowUpRequest>()
    val priority = IncidentFollowUpPriority.parse(request.priority)
        ?: return respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid follow-up priority"))
    val owner = resolveFollowUpOwner(context.organizationId, request.ownerUserId, request.ownerTeamId) ?: return
    val source = parseFollowUpSource(request.source)
        ?: return respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid follow-up source"))
    try {
        val result = onCallIncidentService.executeIncidentCommand(
            AddIncidentFollowUpCommand(
                commandKey = incidentCommandKey("add-follow-up"),
                actor = IncidentCommandActor(context.organizationId, context.userId, "REST"),
                incidentId = context.incidentId,
                title = request.title,
                description = request.description,
                ownerUserId = owner.userId,
                ownerTeamId = owner.teamId,
                priority = priority,
                labels = request.labels,
                dueAt = request.dueAt?.let(Instant::parse),
                slaMinutes = request.slaMinutes,
                reminderMinutes = request.reminderMinutes,
                source = source,
                slackChannelId = request.slackChannelId,
                slackMessageTs = request.slackMessageTs,
                expectedVersion = request.expectedVersion,
            ),
        )
        val followUp = result.followUpResourceId?.let {
            followUpService.get(context.organizationId, context.incidentId, it)
        }
        if (followUp == null) {
            respond(HttpStatusCode.InternalServerError, ErrorResponse("Incident follow-up was not created"))
        } else {
            respond(HttpStatusCode.Created, followUp)
        }
    } catch (error: IncidentCommandException) {
        respondIncidentCommandFailure(error)
    } catch (error: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(error.message))
    }
}

private suspend fun ApplicationCall.handleFollowUpGet(
    onCallIncidentService: OnCallIncidentService,
    followUpService: IncidentFollowUpService,
) {
    val context = requireFollowUpContext(onCallIncidentService) ?: return
    val followUp = followUpService.get(context.organizationId, context.incidentId, parameters["followUpId"].orEmpty())
    if (followUp == null) {
        respond(HttpStatusCode.NotFound, ErrorResponse("Incident follow-up not found"))
    } else {
        respond(followUp)
    }
}

private suspend fun ApplicationCall.handleFollowUpUpdate(
    onCallIncidentService: OnCallIncidentService,
    followUpService: IncidentFollowUpService,
) {
    val context = requireFollowUpContext(onCallIncidentService, requireResponder = true) ?: return
    val request = receive<UpdateIncidentFollowUpRequest>()
    val owner = if (request.ownerUserId == null && request.ownerTeamId == null) {
        FollowUpOwner(null, null)
    } else {
        resolveFollowUpOwner(context.organizationId, request.ownerUserId, request.ownerTeamId) ?: return
    }
    val priority = request.priority?.let {
        IncidentFollowUpPriority.parse(it)
            ?: return respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid follow-up priority"))
    }
    try {
        executeFollowUpCommand(onCallIncidentService, followUpService, context, this) {
            UpdateIncidentFollowUpCommand(
                commandKey = incidentCommandKey("update-follow-up"),
                actor = IncidentCommandActor(context.organizationId, context.userId, "REST"),
                incidentId = context.incidentId,
                followUpResourceId = parameters["followUpId"].orEmpty(),
                title = request.title,
                description = request.description,
                ownerUserId = owner.userId,
                ownerTeamId = owner.teamId,
                priority = priority,
                labels = request.labels,
                dueAt = request.dueAt?.let(Instant::parse),
                clearDueAt = request.clearDueAt,
                slaMinutes = request.slaMinutes,
                reminderMinutes = request.reminderMinutes,
                clearReminderAt = request.clearReminderAt,
                expectedVersion = request.expectedVersion,
            )
        }
    } catch (error: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(error.message))
    }
}

private suspend fun ApplicationCall.handleFollowUpTransition(
    onCallIncidentService: OnCallIncidentService,
    followUpService: IncidentFollowUpService,
    transition: String,
) {
    val context = requireFollowUpContext(onCallIncidentService, requireResponder = true) ?: return
    val request = receiveNullable<IncidentFollowUpStatusRequest>() ?: IncidentFollowUpStatusRequest()
    val followUpId = parameters["followUpId"].orEmpty()
    executeFollowUpCommand(onCallIncidentService, followUpService, context, this) {
        when (transition) {
            "accept" -> AcceptIncidentFollowUpCommand(
                commandKey = incidentCommandKey("accept-follow-up"),
                actor = IncidentCommandActor(context.organizationId, context.userId, "REST"),
                incidentId = context.incidentId,
                followUpResourceId = followUpId,
                expectedVersion = request.expectedVersion,
            )
            "complete" -> CompleteIncidentFollowUpCommand(
                commandKey = incidentCommandKey("complete-follow-up"),
                actor = IncidentCommandActor(context.organizationId, context.userId, "REST"),
                incidentId = context.incidentId,
                followUpResourceId = followUpId,
                note = request.note,
                expectedVersion = request.expectedVersion,
            )
            else -> CancelIncidentFollowUpCommand(
                commandKey = incidentCommandKey("cancel-follow-up"),
                actor = IncidentCommandActor(context.organizationId, context.userId, "REST"),
                incidentId = context.incidentId,
                followUpResourceId = followUpId,
                reason = request.note,
                expectedVersion = request.expectedVersion,
            )
        }
    }
}

private suspend fun ApplicationCall.requireFollowUpContext(
    onCallIncidentService: OnCallIncidentService,
    requireResponder: Boolean = false,
): IncidentFollowUpRouteContext? {
    val context = requireUserContext() ?: return null
    val incidentId = requireIncidentId(context.organizationId) ?: return null
    val incident = onCallIncidentService.getIncident(incidentId)
    if (incident == null) {
        respond(HttpStatusCode.NotFound, ErrorResponse("Incident not found"))
        return null
    }
    if ((requireResponder || incident.visibility == NativeIncidentVisibility.PRIVATE.wire) &&
        !requireIncidentResponderOrAdmin(context, incidentId)
    ) {
        return null
    }
    return IncidentFollowUpRouteContext(context.organizationId, context.userId, context.role, incidentId)
}

private suspend fun executeFollowUpCommand(
    onCallIncidentService: OnCallIncidentService,
    followUpService: IncidentFollowUpService,
    context: IncidentFollowUpRouteContext,
    call: ApplicationCall,
    command: () -> IncidentCommand,
) {
    val followUpId = call.parameters["followUpId"].orEmpty()
    if (followUpService.get(context.organizationId, context.incidentId, followUpId) == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Incident follow-up not found"))
        return
    }
    try {
        onCallIncidentService.executeIncidentCommand(command())
        call.respond(checkNotNull(followUpService.get(context.organizationId, context.incidentId, followUpId)))
    } catch (error: IncidentCommandException) {
        call.respondIncidentCommandFailure(error)
    } catch (error: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message))
    }
}

private data class FollowUpOwner(val userId: Int?, val teamId: Int?)

private data class IncidentFollowUpRouteContext(
    val organizationId: Int,
    val userId: Int,
    val role: com.moneat.org.services.OrgRole,
    val incidentId: Int,
)

private suspend fun ApplicationCall.resolveFollowUpOwner(
    organizationId: Int,
    userResourceId: String?,
    teamResourceId: String?,
): FollowUpOwner? {
    if (userResourceId == null && teamResourceId == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("A user or team owner is required"))
        return null
    }
    if (userResourceId != null && teamResourceId != null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Only one follow-up owner may be selected"))
        return null
    }
    val owner = transaction {
        userResourceId?.let { raw ->
            val parsed = runCatching { Uuid.parse(raw) }.getOrNull()
            if (parsed == null) return@transaction null
            Users.innerJoin(Memberships).selectAll().where {
                (Users.resource_id eq parsed) and (Memberships.organization_id eq organizationId)
            }.firstOrNull()?.get(Users.id)?.let { FollowUpOwner(it, null) }
        } ?: teamResourceId?.let { raw ->
            val parsed = runCatching { Uuid.parse(raw) }.getOrNull()
            if (parsed == null) return@transaction null
            OrganizationTeams.selectAll().where {
                (OrganizationTeams.resourceId eq parsed) and (OrganizationTeams.organizationId eq organizationId)
            }.firstOrNull()?.get(OrganizationTeams.id)?.value?.let { FollowUpOwner(null, it) }
        }
    }
    if (owner == null) respond(HttpStatusCode.NotFound, ErrorResponse("Follow-up owner not found"))
    return owner
}

private fun parseFollowUpSource(raw: String): com.moneat.enterprise.incidents.models.IncidentActionSource? =
    com.moneat.enterprise.incidents.models.IncidentActionSource.entries.firstOrNull {
        it.wire == raw.trim().uppercase()
    }

private fun parseFollowUpStatuses(raw: List<String>?): Set<IncidentFollowUpStatus> {
    val values = raw.orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
    if (values.isEmpty()) return setOf(IncidentFollowUpStatus.OPEN, IncidentFollowUpStatus.ACCEPTED)
    return values.map {
        IncidentFollowUpStatus.entries.firstOrNull { status -> status.wire == it.uppercase() }
            ?: throw IllegalArgumentException("Invalid follow-up status")
    }.toSet()
}

private fun visibleIncidentIds(context: OnCallUserContext): Set<Int> = transaction {
    val incidents = OnCallIncidents
        .selectAll()
        .where { OnCallIncidents.organizationId eq context.organizationId }
        .toList()
    val isAdmin = context.role.level >= com.moneat.org.services.OrgRole.ADMIN.level
    val respondingIncidentIds = if (isAdmin) {
        emptySet()
    } else {
        val assignedIncidentIds = NativeIncidentRoleAssignments
            .selectAll()
            .where {
                (NativeIncidentRoleAssignments.organizationId eq context.organizationId) and
                    (NativeIncidentRoleAssignments.assigneeUserId eq context.userId) and
                    NativeIncidentRoleAssignments.endedAt.isNull()
            }
            .map { it[NativeIncidentRoleAssignments.incidentId] }
        val participatingIncidentIds = NativeIncidentParticipants
            .selectAll()
            .where {
                (NativeIncidentParticipants.organizationId eq context.organizationId) and
                    (NativeIncidentParticipants.userId eq context.userId) and
                    (NativeIncidentParticipants.participationType eq IncidentParticipationType.PARTICIPANT.wire) and
                    NativeIncidentParticipants.leftAt.isNull()
            }
            .map { it[NativeIncidentParticipants.incidentId] }
        (assignedIncidentIds + participatingIncidentIds).toSet()
    }
    incidents.filter { incident ->
        incident[OnCallIncidents.visibility] != NativeIncidentVisibility.PRIVATE.wire ||
            isAdmin ||
            incident[OnCallIncidents.id].value in respondingIncidentIds
    }.map { it[OnCallIncidents.id].value }.toSet()
}
