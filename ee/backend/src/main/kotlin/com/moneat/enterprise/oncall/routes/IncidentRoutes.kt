// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.routes

import com.moneat.auth.currentOrgIdOrNull
import com.moneat.alerts.models.IncidentSeverity
import com.moneat.alerts.models.AlertEpisodes
import com.moneat.enterprise.FeatureRegistry
import com.moneat.enterprise.NativeIncidentRolloutStatus
import com.moneat.enterprise.incidents.commands.DeclareIncidentCommand
import com.moneat.enterprise.incidents.commands.IncidentCommandActor
import com.moneat.enterprise.incidents.commands.IncidentCommandConflictException
import com.moneat.enterprise.incidents.commands.IncidentCommandDeniedException
import com.moneat.enterprise.incidents.commands.IncidentEntitlement
import com.moneat.enterprise.incidents.commands.IncidentCommandException
import com.moneat.enterprise.incidents.commands.IncidentCommandNotFoundException
import com.moneat.enterprise.incidents.commands.LinkOnCallAlertCommand
import com.moneat.enterprise.incidents.commands.IncidentSourceReference
import com.moneat.enterprise.incidents.commands.LinkIncidentSourceCommand
import com.moneat.enterprise.incidents.commands.UnlinkIncidentSourceCommand
import com.moneat.enterprise.incidents.config.IncidentConfigurationService
import com.moneat.enterprise.incidents.responders.IncidentResponderService
import com.moneat.enterprise.incidents.timeline.IncidentTimelineService
import com.moneat.enterprise.incidents.models.IncidentFormStage
import com.moneat.enterprise.incidents.models.IncidentParticipationType
import com.moneat.enterprise.incidents.models.NativeIncidentMode
import com.moneat.enterprise.incidents.models.NativeIncidentParticipants
import com.moneat.enterprise.incidents.models.NativeIncidentRoleAssignments
import com.moneat.enterprise.incidents.models.NativeIncidentRoleDefinitions
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.incidents.models.NativeIncidentVisibility
import com.moneat.enterprise.incidents.models.IncidentSourceType
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.enterprise.oncall.models.OnCallIncident
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.enterprise.oncall.services.OnCallAlertService
import com.moneat.enterprise.oncall.services.OnCallIncidentService
import com.moneat.org.services.OrgRole
import com.moneat.monitoring.OperationalMetrics
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Users
import com.moneat.shared.services.toUuidOrNull
import com.moneat.utils.ErrorResponse
import com.moneat.utils.MessageResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.isHandled
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private const val JWT_AUTH_PROVIDER = "auth-jwt"

private const val DEFAULT_INCIDENT_LIMIT = 50
private const val MIN_INCIDENT_LIMIT = 1
private const val MAX_INCIDENT_LIMIT = 200
private const val INVALID_TOKEN_MESSAGE = "Invalid token"
private const val INVALID_ALERT_ID_MESSAGE = "Invalid alert ID"
private const val ALERT_NOT_FOUND_MESSAGE = "Alert not found"
private const val INVALID_INCIDENT_ID_MESSAGE = "Invalid incident ID"
private const val INCIDENT_NOT_FOUND_MESSAGE = "Incident not found"
private const val FORBIDDEN_MESSAGE = "Insufficient permissions"
private const val INCIDENT_COMMANDER_ROLE_KEY = "incident-commander"
private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

internal data class OnCallUserContext(
    val organizationId: Int,
    val userId: Int,
    val role: OrgRole,
)

internal fun ApplicationCall.incidentCommandKey(action: String): String =
    namespacedIncidentCommandKey(action, request.headers[IDEMPOTENCY_KEY_HEADER])
        ?: "$action:${Uuid.random()}"

internal fun namespacedIncidentCommandKey(action: String, suppliedKey: String?): String? =
    suppliedKey
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { "$action:$it" }

internal suspend fun ApplicationCall.respondIncidentCommandFailure(error: IncidentCommandException) {
    respond(incidentCommandStatus(error), ErrorResponse(error.message))
}

internal fun incidentCommandStatus(error: IncidentCommandException): HttpStatusCode =
    when (error) {
        is IncidentCommandDeniedException -> HttpStatusCode.Forbidden
        is IncidentCommandNotFoundException -> HttpStatusCode.NotFound
        is IncidentCommandConflictException -> HttpStatusCode.Conflict
        else -> HttpStatusCode.BadRequest
    }

private fun parseStatusFilters(rawStatuses: List<String>?): List<String> =
    rawStatuses
        .orEmpty()
        .flatMap { it.split(",") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

@Serializable
data class DeclareIncidentRequest(
    val title: String,
    val description: String? = null,
    val summary: String? = null,
    val severity: String? = null,
    val mode: String = "LIVE",
    val visibility: String = "ORGANIZATION",
    val initialStatus: String = "ACTIVE",
    val incidentTypeId: String? = null,
    val fields: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class AddAlertToIncidentRequest(
    val alertId: String,
)

@Serializable
data class AddIncidentSourceRequest(
    val sourceType: String,
    val sourceId: String? = null,
    val sourceKey: String? = null,
    val label: String? = null,
    val sourceUrl: String? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class ReassignIncidentRequest(
    val toUserId: String,
)

@Serializable
data class AddNoteRequest(
    val note: String,
)

@Serializable
data class ResolveIncidentRequest(
    val note: String? = null,
)

@Serializable
data class NativeIncidentRolloutResponse(
    val enabled: Boolean,
    val environment: String,
    val state: String,
    val externalProviderPassthroughAffected: Boolean,
)

private data class IncidentRouteServices(
    val alertServiceProvider: () -> OnCallAlertService,
    val incidentService: OnCallIncidentService,
    val configurationService: IncidentConfigurationService,
    val responderService: IncidentResponderService,
    val timelineService: IncidentTimelineService,
)

fun Route.incidentRoutes(
    alertServiceProvider: () -> OnCallAlertService,
    onCallIncidentService: OnCallIncidentService = OnCallIncidentService(),
    incidentEntitlement: IncidentEntitlement = IncidentEntitlement {
        FeatureRegistry.isNativeIncidentResponseEnabled(it)
    },
    rolloutStatusProvider: (Int) -> NativeIncidentRolloutStatus = FeatureRegistry::nativeIncidentRolloutStatus,
) {
    val services =
        IncidentRouteServices(
            alertServiceProvider = alertServiceProvider,
            incidentService = onCallIncidentService,
            configurationService = IncidentConfigurationService(),
            responderService = IncidentResponderService(),
            timelineService = IncidentTimelineService(),
        )
    registerIncidentRolloutStatusRoute(rolloutStatusProvider)
    registerAlertRoutes(services.alertServiceProvider, services.incidentService, incidentEntitlement)
    registerDeclaredIncidentRoutes(services, incidentEntitlement)
    registerIncidentConfigurationRoutes(
        services.configurationService,
        services.responderService,
        incidentEntitlement,
    )
}

private fun Route.registerIncidentRolloutStatusRoute(
    rolloutStatusProvider: (Int) -> NativeIncidentRolloutStatus,
) {
    route("/v1/on-call/incident-response/capabilities") {
        authenticate(JWT_AUTH_PROVIDER) {
            get {
                val organizationId = call.requireOrganizationId() ?: return@get
                val status = rolloutStatusProvider(organizationId)
                call.respond(
                    NativeIncidentRolloutResponse(
                        enabled = status.enabled,
                        environment = status.environment,
                        state = status.state.name,
                        externalProviderPassthroughAffected = false,
                    ),
                )
            }
        }
    }
}

private fun Route.registerAlertRoutes(
    alertServiceProvider: () -> OnCallAlertService,
    onCallIncidentService: OnCallIncidentService,
    incidentEntitlement: IncidentEntitlement,
) {
    route("/v1/on-call/alerts") {
        authenticate(JWT_AUTH_PROVIDER) {
            registerListAlertsRoute(alertServiceProvider)
            registerGetAlertRoute(alertServiceProvider)
            registerAlertTimelineRoute(alertServiceProvider)
            registerAcknowledgeAlertRoute(alertServiceProvider)
            registerResolveAlertRoute(alertServiceProvider)
            registerReassignAlertRoute(alertServiceProvider)
            registerAddAlertNoteRoute(alertServiceProvider)
            registerViewAlertRoute(alertServiceProvider)
            registerUnavailableAlertRoute(alertServiceProvider)
            registerDeclareIncidentFromAlertRoute(
                alertServiceProvider,
                onCallIncidentService,
                incidentEntitlement,
            )
        }
    }
}

private fun Route.registerDeclaredIncidentRoutes(
    services: IncidentRouteServices,
    incidentEntitlement: IncidentEntitlement,
) {
    route("/v1/on-call/incidents") {
        authenticate(JWT_AUTH_PROVIDER) {
            installNativeIncidentRolloutGate("NativeIncidentRoutesGate", incidentEntitlement)
            registerDeclareIncidentRoute(services.incidentService, services.configurationService)
            registerListDeclaredIncidentsRoute(services.incidentService)
            registerGetDeclaredIncidentRoute(services.incidentService)
            registerResolveDeclaredIncidentRoute(services.incidentService)
            registerAddAlertToIncidentRoute(services.alertServiceProvider, services.incidentService)
            registerIncidentSourceRoutes(services.incidentService)
            registerIncidentResponderRoutes(services.incidentService, services.responderService)
            registerIncidentTimelineRoutes(services.timelineService)
            registerAddIncidentNoteRoute(services.incidentService)
        }
    }
}

internal fun Route.installNativeIncidentRolloutGate(
    pluginName: String,
    incidentEntitlement: IncidentEntitlement,
) {
    install(
        createRouteScopedPlugin(pluginName) {
            on(AuthenticationChecked) { call ->
                if (call.isHandled) return@on
                val organizationId = call.principal<JWTPrincipal>()?.currentOrgIdOrNull()
                if (organizationId == null) {
                    OperationalMetrics.recordNativeIncidentRolloutDecision("http", "denied")
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Organization context is required"))
                    return@on
                }
                if (!incidentEntitlement.isEnabled(organizationId)) {
                    OperationalMetrics.recordNativeIncidentRolloutDecision("http", "denied")
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("Native incident response is not enabled for this organization"),
                    )
                }
            }
        },
    )
}

private fun Route.registerIncidentSourceRoutes(onCallIncidentService: OnCallIncidentService) {
    get("/{id}/sources") {
        val context = call.requireUserContext() ?: return@get
        val incidentId = call.requireIncidentId(context.organizationId) ?: return@get
        call.respond(onCallIncidentService.getIncidentSources(context.organizationId, incidentId))
    }
    post("/{id}/sources") {
        val context = call.requireUserContext() ?: return@post
        val incidentId = call.requireIncidentId(context.organizationId) ?: return@post
        val request = call.receive<AddIncidentSourceRequest>()
        try {
            val source = call.resolveIncidentSource(context.organizationId, request) ?: return@post
            onCallIncidentService.addSourceToIncident(
                LinkIncidentSourceCommand(
                    commandKey = call.incidentCommandKey("link-source"),
                    actor = IncidentCommandActor(context.organizationId, context.userId, "REST"),
                    incidentId = incidentId,
                    source = source,
                ),
            )
            call.respond(
                HttpStatusCode.Created,
                onCallIncidentService.getIncidentSources(context.organizationId, incidentId),
            )
        } catch (error: IncidentCommandException) {
            call.respondIncidentCommandFailure(error)
        } catch (error: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message))
        }
    }
    delete("/{id}/sources/{sourceId}") {
        val context = call.requireUserContext() ?: return@delete
        val incidentId = call.requireIncidentId(context.organizationId) ?: return@delete
        val sourceResourceId = call.parameters["sourceId"]
        if (sourceResourceId?.toUuidOrNull() == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid incident source ID"))
            return@delete
        }
        val source =
            onCallIncidentService.getIncidentSourceIdentity(
                context.organizationId,
                incidentId,
                checkNotNull(sourceResourceId),
            )
        if (source == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Incident source not found"))
            return@delete
        }
        try {
            onCallIncidentService.removeSourceFromIncident(
                UnlinkIncidentSourceCommand(
                    commandKey = call.incidentCommandKey("unlink-source"),
                    actor = IncidentCommandActor(context.organizationId, context.userId, "REST"),
                    incidentId = incidentId,
                    sourceType = source.sourceType,
                    sourceKey = source.sourceKey,
                ),
            )
            call.respond(HttpStatusCode.OK, MessageResponse("Incident source removed"))
        } catch (error: IncidentCommandException) {
            call.respondIncidentCommandFailure(error)
        }
    }
}

private suspend fun ApplicationCall.resolveIncidentSource(
    organizationId: Int,
    request: AddIncidentSourceRequest,
): IncidentSourceReference? {
    val sourceType = IncidentSourceType.entries.firstOrNull { it.wire == request.sourceType.trim().uppercase() }
    if (sourceType == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid incident source type"))
        return null
    }
    return when (sourceType) {
        IncidentSourceType.ON_CALL_ALERT -> {
            val alertId = requireAlertIdValue(request.sourceId, organizationId) ?: return null
            IncidentSourceReference(
                sourceType = sourceType,
                sourceKey = checkNotNull(request.sourceId),
                onCallAlertId = alertId,
                label = request.label,
                metadata = request.metadata,
            )
        }
        IncidentSourceType.ALERT_EPISODE -> {
            val episodeId = requireAlertEpisodeIdValue(request.sourceId, organizationId) ?: return null
            IncidentSourceReference(
                sourceType = sourceType,
                sourceKey = checkNotNull(request.sourceId),
                alertEpisodeId = episodeId,
                label = request.label,
                metadata = request.metadata,
            )
        }
        else ->
            IncidentSourceReference(
                sourceType = sourceType,
                sourceKey = request.sourceKey ?: request.sourceId.orEmpty(),
                label = request.label,
                sourceUrl = request.sourceUrl,
                metadata = request.metadata,
            )
    }
}

private suspend fun ApplicationCall.requireAlertEpisodeIdValue(
    raw: String?,
    organizationId: Int,
): Int? {
    val resourceId = parseOnCallResourceId(raw)
    if (resourceId == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid alert episode ID"))
        return null
    }
    val id =
        transaction {
            AlertEpisodes
                .selectAll()
                .where {
                    (AlertEpisodes.resourceId eq resourceId) and
                        (AlertEpisodes.organizationId eq organizationId)
                }.singleOrNull()
                ?.get(AlertEpisodes.id)
                ?.value
        }
    if (id == null) respond(HttpStatusCode.NotFound, ErrorResponse("Alert episode not found"))
    return id
}

private fun Route.registerDeclareIncidentRoute(
    onCallIncidentService: OnCallIncidentService,
    configurationService: IncidentConfigurationService,
) {
    post {
        val context = call.requireUserContext() ?: return@post
        val request = call.receive<DeclareIncidentRequest>()
        val severity = call.requireIncidentSeverity(request.severity) ?: return@post
        try {
            val incident =
                declareIncident(
                    IncidentDeclaration(
                        call = call,
                        context = context,
                        request = request,
                        severity = severity,
                        onCallAlertId = null,
                        incidentService = onCallIncidentService,
                        configurationService = configurationService,
                    ),
                )
            call.respond(HttpStatusCode.Created, incident)
        } catch (e: IncidentCommandException) {
            call.respondIncidentCommandFailure(e)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
        }
    }
}

private suspend fun ApplicationCall.requireOrganizationId(): Int? {
    val organizationId = principal<JWTPrincipal>()?.currentOrgIdOrNull()
    if (organizationId == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
    }
    return organizationId
}

internal suspend fun ApplicationCall.requireUserContext(): OnCallUserContext? {
    val principal = principal<JWTPrincipal>()
    val organizationId = principal?.currentOrgIdOrNull()
    val userId = principal?.payload?.getClaim("userId")?.asInt()
    if (organizationId == null || userId == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
        return null
    }
    val role =
        transaction {
            Memberships
                .selectAll()
                .where {
                    (Memberships.organization_id eq organizationId) and
                        (Memberships.user_id eq userId)
                }.firstOrNull()
                ?.get(Memberships.role)
        }
    if (role == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse(INVALID_TOKEN_MESSAGE))
        return null
    }
    return OnCallUserContext(organizationId, userId, OrgRole.fromString(role))
}

internal suspend fun ApplicationCall.requireIncidentAdmin(context: OnCallUserContext): Boolean {
    if (context.role.level >= OrgRole.ADMIN.level) return true
    respond(HttpStatusCode.Forbidden, ErrorResponse(FORBIDDEN_MESSAGE))
    return false
}

internal suspend fun ApplicationCall.requireIncidentAdminContext(): OnCallUserContext? {
    val context = requireUserContext() ?: return null
    if (!requireIncidentAdmin(context)) return null
    return context
}

internal suspend fun ApplicationCall.requireIncidentResponderOrAdmin(
    context: OnCallUserContext,
    incidentId: Int,
): Boolean {
    if (context.role.level >= OrgRole.ADMIN.level) return true
    val isResponder =
        transaction {
            val hasRole =
                NativeIncidentRoleAssignments
                    .selectAll()
                    .where {
                        (NativeIncidentRoleAssignments.organizationId eq context.organizationId) and
                            (NativeIncidentRoleAssignments.incidentId eq incidentId) and
                            (NativeIncidentRoleAssignments.assigneeUserId eq context.userId) and
                            NativeIncidentRoleAssignments.endedAt.isNull()
                    }.limit(1)
                    .firstOrNull() != null
            hasRole ||
                NativeIncidentParticipants
                    .selectAll()
                    .where {
                        (NativeIncidentParticipants.organizationId eq context.organizationId) and
                            (NativeIncidentParticipants.incidentId eq incidentId) and
                            (NativeIncidentParticipants.userId eq context.userId) and
                            (NativeIncidentParticipants.participationType eq
                                IncidentParticipationType.PARTICIPANT.wire) and
                            NativeIncidentParticipants.leftAt.isNull()
                    }.limit(1)
                    .firstOrNull() != null
        }
    if (isResponder) return true
    respond(HttpStatusCode.Forbidden, ErrorResponse(FORBIDDEN_MESSAGE))
    return false
}

internal suspend fun ApplicationCall.requireIncidentManagerOrSelf(
    context: OnCallUserContext,
    incidentId: Int,
    targetUserId: Int,
): Boolean {
    if (context.role.level >= OrgRole.ADMIN.level || context.userId == targetUserId) return true
    val isIncidentCommander =
        transaction {
            (NativeIncidentRoleAssignments innerJoin NativeIncidentRoleDefinitions)
                .selectAll()
                .where {
                    (NativeIncidentRoleAssignments.organizationId eq context.organizationId) and
                        (NativeIncidentRoleAssignments.incidentId eq incidentId) and
                        (NativeIncidentRoleAssignments.assigneeUserId eq context.userId) and
                        NativeIncidentRoleAssignments.endedAt.isNull() and
                        (NativeIncidentRoleDefinitions.stableKey eq INCIDENT_COMMANDER_ROLE_KEY)
                }.limit(1)
                .firstOrNull() != null
        }
    if (isIncidentCommander) return true
    respond(HttpStatusCode.Forbidden, ErrorResponse(FORBIDDEN_MESSAGE))
    return false
}

private suspend fun ApplicationCall.requireAlertId(
    organizationId: Int,
    parameterName: String = "id",
): Int? =
    requireAlertIdValue(parameters[parameterName], organizationId)

internal suspend fun ApplicationCall.requireIncidentId(organizationId: Int): Int? =
    requireIncidentIdValue(parameters["id"], organizationId)

private suspend fun ApplicationCall.requireAlertIdValue(
    raw: String?,
    organizationId: Int,
): Int? {
    val resourceId = parseOnCallResourceId(raw)
    if (resourceId == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_ALERT_ID_MESSAGE))
        return null
    }
    val id =
        transaction {
            OnCallAlerts
                .selectAll()
                .where {
                    (OnCallAlerts.resourceId eq resourceId) and
                        (OnCallAlerts.organizationId eq organizationId)
                }
                .firstOrNull()
                ?.get(OnCallAlerts.id)
                ?.value
        }
    if (id == null) {
        respond(HttpStatusCode.NotFound, ErrorResponse(ALERT_NOT_FOUND_MESSAGE))
    }
    return id
}

private suspend fun ApplicationCall.requireIncidentIdValue(
    raw: String?,
    organizationId: Int,
): Int? {
    val resourceId = parseOnCallResourceId(raw)
    if (resourceId == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_INCIDENT_ID_MESSAGE))
        return null
    }
    val id =
        transaction {
            OnCallIncidents
                .selectAll()
                .where {
                    (OnCallIncidents.resourceId eq resourceId) and
                        (OnCallIncidents.organizationId eq organizationId)
                }
                .firstOrNull()
                ?.get(OnCallIncidents.id)
                ?.value
        }
    if (id == null) {
        respond(HttpStatusCode.NotFound, ErrorResponse(INCIDENT_NOT_FOUND_MESSAGE))
    }
    return id
}

private fun parseOnCallResourceId(raw: String?): Uuid? =
    raw?.toUuidOrNull()

private suspend fun ApplicationCall.requireReassignmentUserId(
    organizationId: Int,
    raw: String,
): Int? {
    val resourceId = parseOnCallResourceId(raw)
    if (resourceId == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user ID"))
        return null
    }
    val userId =
        transaction {
            Users
                .innerJoin(Memberships)
                .selectAll()
                .where {
                    (Users.resource_id eq resourceId) and
                        (Memberships.organization_id eq organizationId)
                }
                .firstOrNull()
                ?.get(Users.id)
        }
    if (userId == null) {
        respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
    }
    return userId
}

private suspend fun ApplicationCall.ensureAlertInOrganization(
    alertService: OnCallAlertService,
    alertId: Int,
    organizationId: Int,
): Boolean {
    val alert = alertService.getAlert(alertId)
    if (alert == null || alert.organizationId != organizationId) {
        respond(HttpStatusCode.NotFound, ErrorResponse(ALERT_NOT_FOUND_MESSAGE))
        return false
    }
    return true
}

private suspend fun ApplicationCall.ensureIncidentInOrganization(
    onCallIncidentService: OnCallIncidentService,
    incidentId: Int,
    organizationId: Int,
): Boolean {
    if (!onCallIncidentService.isIncidentInOrganization(incidentId, organizationId)) {
        respond(HttpStatusCode.NotFound, ErrorResponse(INCIDENT_NOT_FOUND_MESSAGE))
        return false
    }
    return true
}

private fun Route.registerListAlertsRoute(alertServiceProvider: () -> OnCallAlertService) {
    get {
        val context = call.requireUserContext() ?: return@get
        val statuses = parseStatusFilters(call.request.queryParameters.getAll("status"))
        val priority = call.request.queryParameters["priority"]
        val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull()
        val limit = parseLimit(rawLimit)
        val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
        val alerts =
            alertServiceProvider().listAlerts(
                organizationId = context.organizationId,
                statuses = statuses.ifEmpty { null },
                priority = priority,
                limit = limit,
                offset = offset,
                currentUserId = context.userId,
            )
        call.respond(alerts)
    }
}

private fun parseLimit(rawLimit: Int?): Int =
    when {
        rawLimit == null -> DEFAULT_INCIDENT_LIMIT
        rawLimit < MIN_INCIDENT_LIMIT -> throw BadRequestException("limit must be >= $MIN_INCIDENT_LIMIT")
        else -> rawLimit.coerceAtMost(MAX_INCIDENT_LIMIT)
    }

private fun Route.registerGetAlertRoute(alertServiceProvider: () -> OnCallAlertService) {
    get("/{id}") {
        val context = call.requireUserContext() ?: return@get
        val alertId = call.requireAlertId(context.organizationId) ?: return@get
        val alert = alertServiceProvider().getAlert(alertId, context.userId)
        if (alert != null && alert.organizationId == context.organizationId) {
            call.respond(alert)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(ALERT_NOT_FOUND_MESSAGE))
        }
    }
}

private fun Route.registerAlertTimelineRoute(alertServiceProvider: () -> OnCallAlertService) {
    get("/{id}/timeline") {
        val organizationId = call.requireOrganizationId() ?: return@get
        val alertId = call.requireAlertId(organizationId) ?: return@get
        val alertService = alertServiceProvider()
        if (!call.ensureAlertInOrganization(alertService, alertId, organizationId)) return@get
        call.respond(alertService.getTimeline(alertId))
    }
}

private fun Route.registerAcknowledgeAlertRoute(alertServiceProvider: () -> OnCallAlertService) {
    post("/{id}/acknowledge") {
        val context = call.requireUserContext() ?: return@post
        val alertId = call.requireAlertId(context.organizationId) ?: return@post
        val alertService = alertServiceProvider()
        if (!call.ensureAlertInOrganization(alertService, alertId, context.organizationId)) return@post
        val acknowledged = alertService.acknowledge(alertId, context.userId)
        if (acknowledged) {
            call.respond(HttpStatusCode.OK, MessageResponse("Alert acknowledged"))
        } else {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Could not acknowledge alert"))
        }
    }
}

private fun Route.registerResolveAlertRoute(alertServiceProvider: () -> OnCallAlertService) {
    post("/{id}/resolve") {
        val context = call.requireUserContext() ?: return@post
        val alertId = call.requireAlertId(context.organizationId) ?: return@post
        val alertService = alertServiceProvider()
        if (!call.ensureAlertInOrganization(alertService, alertId, context.organizationId)) return@post
        val resolved = alertService.resolve(alertId, context.userId)
        if (resolved) {
            call.respond(HttpStatusCode.OK, MessageResponse("Alert resolved"))
        } else {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Could not resolve alert"))
        }
    }
}

private fun Route.registerReassignAlertRoute(alertServiceProvider: () -> OnCallAlertService) {
    post("/{id}/reassign") {
        val context = call.requireUserContext() ?: return@post
        val alertId = call.requireAlertId(context.organizationId) ?: return@post
        val alertService = alertServiceProvider()
        if (!call.ensureAlertInOrganization(alertService, alertId, context.organizationId)) return@post
        val request = call.receive<ReassignIncidentRequest>()
        val toUserId = call.requireReassignmentUserId(context.organizationId, request.toUserId) ?: return@post
        val reassigned = alertService.reassign(alertId, toUserId, context.userId)
        if (reassigned) {
            call.respond(HttpStatusCode.OK, MessageResponse("Alert reassigned"))
        } else {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Could not reassign alert"))
        }
    }
}

private fun Route.registerAddAlertNoteRoute(alertServiceProvider: () -> OnCallAlertService) {
    post("/{id}/notes") {
        val context = call.requireUserContext() ?: return@post
        val alertId = call.requireAlertId(context.organizationId) ?: return@post
        val alertService = alertServiceProvider()
        if (!call.ensureAlertInOrganization(alertService, alertId, context.organizationId)) return@post
        val request = call.receive<AddNoteRequest>()
        val event = alertService.addNote(alertId, context.userId, request.note)
        call.respond(HttpStatusCode.Created, event)
    }
}

private fun Route.registerViewAlertRoute(alertServiceProvider: () -> OnCallAlertService) {
    post("/{id}/view") {
        val context = call.requireUserContext() ?: return@post
        val alertId = call.requireAlertId(context.organizationId) ?: return@post
        val alertService = alertServiceProvider()
        if (!call.ensureAlertInOrganization(alertService, alertId, context.organizationId)) return@post
        alertService.viewAlert(alertId, context.userId)
        call.respond(HttpStatusCode.OK, MessageResponse("Alert viewed"))
    }
}

private fun Route.registerUnavailableAlertRoute(alertServiceProvider: () -> OnCallAlertService) {
    post("/{id}/unavailable") {
        val context = call.requireUserContext() ?: return@post
        val alertId = call.requireAlertId(context.organizationId) ?: return@post
        val alertService = alertServiceProvider()
        if (!call.ensureAlertInOrganization(alertService, alertId, context.organizationId)) return@post
        val result = alertService.markUnavailable(alertId, context.userId)
        if (result) {
            call.respond(HttpStatusCode.OK, MessageResponse("Escalated to next on-call"))
        } else {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Could not escalate alert"))
        }
    }
}

private fun Route.registerDeclareIncidentFromAlertRoute(
    alertServiceProvider: () -> OnCallAlertService,
    onCallIncidentService: OnCallIncidentService,
    incidentEntitlement: IncidentEntitlement,
) {
    post("/{alertId}/declare-incident") {
        val context = call.requireUserContext() ?: return@post
        if (!incidentEntitlement.isEnabled(context.organizationId)) {
            OperationalMetrics.recordNativeIncidentRolloutDecision("http", "denied")
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("Native incident response is not enabled for this organization"),
            )
            return@post
        }
        val alertId = call.requireAlertId(context.organizationId, "alertId") ?: return@post
        val alertService = alertServiceProvider()
        if (!call.ensureAlertInOrganization(alertService, alertId, context.organizationId)) return@post
        val request = call.receive<DeclareIncidentRequest>()
        val incidentSeverity = call.requireIncidentSeverity(request.severity) ?: return@post

        try {
            val incident =
                declareIncident(
                    IncidentDeclaration(
                        call = call,
                        context = context,
                        request = request,
                        severity = incidentSeverity,
                        onCallAlertId = alertId,
                        incidentService = onCallIncidentService,
                        configurationService = IncidentConfigurationService(),
                    ),
                )
            call.respond(HttpStatusCode.Created, incident)
        } catch (e: IncidentCommandException) {
            call.respondIncidentCommandFailure(e)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
        }
    }
}

private data class IncidentDeclaration(
    val call: ApplicationCall,
    val context: OnCallUserContext,
    val request: DeclareIncidentRequest,
    val severity: String,
    val onCallAlertId: Int?,
    val incidentService: OnCallIncidentService,
    val configurationService: IncidentConfigurationService,
)

private suspend fun declareIncident(declaration: IncidentDeclaration): OnCallIncident {
    val request = declaration.request
    val mode =
        NativeIncidentMode.entries.firstOrNull { it.wire == request.mode.trim().uppercase() }
            ?: throw IllegalArgumentException("Invalid incident mode")
    val visibility =
        NativeIncidentVisibility.entries.firstOrNull { it.wire == request.visibility.trim().uppercase() }
            ?: throw IllegalArgumentException("Invalid incident visibility")
    val initialStatus = NativeIncidentStatus.fromWire(request.initialStatus.trim().uppercase())
        ?: throw IllegalArgumentException("Invalid incident status")
    val resolvedForm =
        declaration.configurationService.resolveForm(
            organizationId = declaration.context.organizationId,
            incidentTypeResourceId = request.incidentTypeId,
            stage = IncidentFormStage.DECLARATION,
            submittedValues = request.fields,
        )
    return declaration.incidentService.declareIncident(
        DeclareIncidentCommand(
            commandKey = declaration.call.incidentCommandKey("declare"),
            actor = IncidentCommandActor(
                declaration.context.organizationId,
                declaration.context.userId,
                "REST",
            ),
            title = request.title,
            description = request.description,
            summary = request.summary,
            severity = declaration.severity,
            mode = mode,
            visibility = visibility,
            incidentType = resolvedForm.incidentTypeName,
            incidentTypeDefinitionId = resolvedForm.incidentTypeDefinitionId,
            formDefinitionId = resolvedForm.formDefinitionId,
            formDefinitionSnapshot = resolvedForm.definitionSnapshot,
            formValues = resolvedForm.values,
            initialStatus = initialStatus,
            onCallAlertId = declaration.onCallAlertId,
        ),
    )
}

private suspend fun ApplicationCall.requireIncidentSeverity(severity: String?): String? {
    val trimmedSeverity = severity?.trim()
    if (trimmedSeverity.isNullOrBlank()) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Missing incident severity"))
        return null
    }

    val incidentSeverity = IncidentSeverity.wireValue(trimmedSeverity)
    if (incidentSeverity == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid incident severity"))
        return null
    }
    return incidentSeverity
}

private fun Route.registerListDeclaredIncidentsRoute(onCallIncidentService: OnCallIncidentService) {
    get {
        val organizationId = call.requireOrganizationId() ?: return@get
        val status = call.request.queryParameters["status"]
        val severity = call.request.queryParameters["severity"]
        val incidents = onCallIncidentService.getIncidents(organizationId, status, severity)
        call.respond(incidents)
    }
}

private fun Route.registerGetDeclaredIncidentRoute(onCallIncidentService: OnCallIncidentService) {
    get("/{id}") {
        val organizationId = call.requireOrganizationId() ?: return@get
        val incidentId = call.requireIncidentId(organizationId) ?: return@get
        if (!call.ensureIncidentInOrganization(onCallIncidentService, incidentId, organizationId)) return@get
        val incident = onCallIncidentService.getIncident(incidentId)
        if (incident != null) {
            call.respond(incident)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(INCIDENT_NOT_FOUND_MESSAGE))
        }
    }
}

private fun Route.registerResolveDeclaredIncidentRoute(onCallIncidentService: OnCallIncidentService) {
    post("/{id}/resolve") {
        val context = call.requireUserContext() ?: return@post
        val incidentId = call.requireIncidentId(context.organizationId) ?: return@post
        if (!call.ensureIncidentInOrganization(onCallIncidentService, incidentId, context.organizationId)) return@post
        val request = call.receiveNullable<ResolveIncidentRequest>() ?: ResolveIncidentRequest()
        val incident = try {
            onCallIncidentService.resolveIncident(
                incidentId = incidentId,
                userId = context.userId,
                resolutionNote = request.note,
                commandKey = call.incidentCommandKey("resolve"),
                origin = "REST",
            )
        } catch (e: IncidentCommandException) {
            call.respondIncidentCommandFailure(e)
            return@post
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
            return@post
        }
        if (incident != null) {
            call.respond(incident)
        } else {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to resolve incident"))
        }
    }
}

private fun Route.registerAddAlertToIncidentRoute(
    alertServiceProvider: () -> OnCallAlertService,
    onCallIncidentService: OnCallIncidentService,
) {
    post("/{id}/add-alert") {
        val context = call.requireUserContext() ?: return@post
        val incidentId = call.requireIncidentId(context.organizationId) ?: return@post
        if (!call.ensureIncidentInOrganization(onCallIncidentService, incidentId, context.organizationId)) return@post
        val request = call.receive<AddAlertToIncidentRequest>()
        val alertId = call.requireAlertIdValue(request.alertId, context.organizationId) ?: return@post
        val alert = alertServiceProvider().getAlert(alertId)
        if (alert == null || alert.organizationId != context.organizationId) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Alert not found or not in organization"))
            return@post
        }

        try {
            onCallIncidentService.addAlertToIncident(
                LinkOnCallAlertCommand(
                    commandKey = call.incidentCommandKey("link-alert"),
                    actor = IncidentCommandActor(context.organizationId, context.userId, "REST"),
                    incidentId = incidentId,
                    alertId = alertId,
                ),
            )
            call.respond(HttpStatusCode.OK, MessageResponse("Alert added to incident"))
        } catch (e: IncidentCommandException) {
            call.respondIncidentCommandFailure(e)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
        }
    }
}

private fun Route.registerAddIncidentNoteRoute(onCallIncidentService: OnCallIncidentService) {
    post("/{id}/notes") {
        val context = call.requireUserContext() ?: return@post
        val incidentId = call.requireIncidentId(context.organizationId) ?: return@post
        if (!call.ensureIncidentInOrganization(onCallIncidentService, incidentId, context.organizationId)) return@post
        val request = call.receive<AddNoteRequest>()
        try {
            onCallIncidentService.addNote(
                incidentId = incidentId,
                userId = context.userId,
                note = request.note,
                commandKey = call.incidentCommandKey("add-note"),
                origin = "REST",
            )
        } catch (e: IncidentCommandException) {
            call.respondIncidentCommandFailure(e)
            return@post
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
            return@post
        }
        call.respond(HttpStatusCode.OK, MessageResponse("Note added"))
    }
}
