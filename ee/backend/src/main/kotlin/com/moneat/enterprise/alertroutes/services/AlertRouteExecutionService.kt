// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.alerts.models.AlertStatus
import com.moneat.alerts.models.AlertPriority
import com.moneat.alerts.services.AlertRouteActionOutcome
import com.moneat.alerts.services.AlertRouteActionState
import com.moneat.alerts.services.AlertFanoutContext
import com.moneat.alerts.services.AlertRouteExecutionOutcome
import com.moneat.alerts.services.AlertRouteExecutionState
import com.moneat.enterprise.FeatureRegistry
import com.moneat.enterprise.alertroutes.commands.AlertGroupActor
import com.moneat.enterprise.alertroutes.commands.ALERT_PRIORITY_SEVERITY
import com.moneat.enterprise.alertroutes.commands.AttachAlertGroupIncidentCommand
import com.moneat.enterprise.alertroutes.commands.CreateAlertGroupTriageCommand
import com.moneat.enterprise.alertroutes.models.AlertGroupEscalationState
import com.moneat.enterprise.alertroutes.models.AlertGroupMemberState
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingBehavior
import com.moneat.enterprise.alertroutes.models.AlertRouteIncidentMode
import com.moneat.enterprise.alertroutes.models.AlertRoutePagingMode
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRoutes
import com.moneat.enterprise.incidents.commands.DeclineIncidentCommand
import com.moneat.enterprise.incidents.commands.IncidentCommandActor
import com.moneat.enterprise.incidents.commands.IncidentCommandNotFoundException
import com.moneat.enterprise.incidents.commands.IncidentCommandService
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.incidents.models.NativeIncidentSourceLinks
import com.moneat.enterprise.incidents.models.IncidentSourceType
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.shared.models.Memberships
import com.moneat.utils.suspendRunCatching
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val ROUTE_ALERT_SOURCE = "alert-route"
private const val ROUTE_AUTOMATION_ORIGIN = "ALERT_ROUTE"
private val executionLogger = KotlinLogging.logger {}

/** Executes the selected route without coupling it to workflow or provider delivery. */
class AlertRouteExecutionService(
    private val evaluationService: AlertRouteEvaluationService = AlertRouteEvaluationService(),
    private val groupService: AlertGroupService = AlertGroupService(),
    private val groupCommands: AlertGroupCommandService = AlertGroupCommandService(),
    private val incidentCommands: IncidentCommandService = IncidentCommandService(),
    private val onCallGateway: AlertRouteOnCallGateway = FeatureRegistryAlertRouteOnCallGateway,
    private val now: () -> Instant = { Clock.System.now() },
) {
    suspend fun execute(context: AlertFanoutContext) {
        val outcome = executeInternal(context, propagateFailures = true)
        check(outcome.state != AlertRouteExecutionState.FAILED) { outcome.reason ?: "Alert-route execution failed" }
    }

    suspend fun executeWithOutcome(context: AlertFanoutContext): AlertRouteExecutionOutcome =
        suspendRunCatching { executeInternal(context, propagateFailures = false) }
            .getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                executionLogger.error(error) { "Alert-route execution failed" }
                AlertRouteExecutionOutcome(
                    state = AlertRouteExecutionState.FAILED,
                    reason = error.message ?: "Alert-route execution failed",
                )
            }

    private suspend fun executeInternal(
        context: AlertFanoutContext,
        propagateFailures: Boolean,
    ): AlertRouteExecutionOutcome {
        val episode = context.episode ?: return AlertRouteExecutionOutcome(
                state = AlertRouteExecutionState.UNAVAILABLE,
                reason = "Alert episode is unavailable for route evaluation",
            )
        val episodeId = episode.resourceId
        if (context.event.status == AlertStatus.RESOLVED) {
            recover(context.event.organizationId, episodeId)
            return AlertRouteExecutionOutcome(
                state = AlertRouteExecutionState.SKIPPED,
                reason = "Resolved alerts are handled by route recovery",
            )
        }
        return executeFiring(context, episode, propagateFailures)
    }

    private suspend fun executeFiring(
        context: AlertFanoutContext,
        episode: com.moneat.alerts.services.AlertEpisodeContext,
        propagateFailures: Boolean,
    ): AlertRouteExecutionOutcome {
        val episodeId = episode.resourceId
        val live = evaluationService.evaluateLive(
            context.event,
            com.moneat.enterprise.alertroutes.context.AlertRouteEpisodeIdentity.from(episode),
        )
        val decision = live.result.decision ?: return AlertRouteExecutionOutcome(
            state = AlertRouteExecutionState.NO_MATCH,
            reason = "No enabled alert route matched this alert",
        )
        val candidate = if (decision.grouping.behavior in CORRELATING_GROUPING_BEHAVIORS) {
            groupService.findCandidateIncident(
                context.event.organizationId,
                decision.routeId,
                decision.grouping.groupKey,
            )
        } else {
            null
        }
        val group = groupService.recordFiring(live.context, decision, episodeId, candidate, now())
        val incidentResult = runCatching { applyIncidentActions(context, episodeId, group) }
        val finalGroup = incidentResult.getOrNull() ?: group
        val incidentOutcome = incidentResult.fold(
            onSuccess = { updatedGroup -> incidentActionOutcome(context, group, updatedGroup) },
            onFailure = { error ->
                AlertRouteActionOutcome(AlertRouteActionState.FAILED, error.message ?: "Incident action failed")
            },
        )
        val pagingOutcome = if (context.deliverySilenced) {
            AlertRouteActionOutcome(AlertRouteActionState.SKIPPED, "Paging skipped because delivery is silenced")
        } else {
            page(context, finalGroup, decision.paging.escalationPolicies.map { it.id })
        }
        if (propagateFailures) {
            incidentResult.exceptionOrNull()?.let { throw it }
            check(pagingOutcome.state != AlertRouteActionState.FAILED) {
                pagingOutcome.reason ?: "Alert-route paging failed"
            }
        }
        val overallState = when {
            incidentOutcome.state == AlertRouteActionState.FAILED ||
                pagingOutcome.state == AlertRouteActionState.FAILED -> AlertRouteExecutionState.FAILED
            else -> AlertRouteExecutionState.MATCHED
        }
        return AlertRouteExecutionOutcome(
            state = overallState,
            reason = if (overallState == AlertRouteExecutionState.FAILED) {
                incidentOutcome.reason ?: pagingOutcome.reason
            } else {
                null
            },
            matchedRouteId = decision.routeId.toString(),
            matchedRouteRevision = decision.revision,
            groupId = finalGroup.id.toString(),
            incidentId = finalGroup.incidentId?.toString(),
            grouping = AlertRouteActionOutcome(AlertRouteActionState.SUCCEEDED, "Alert grouped"),
            paging = pagingOutcome,
            incident = incidentOutcome,
        )
    }

    private fun incidentActionOutcome(
        context: AlertFanoutContext,
        initialGroup: AlertGroupRecord,
        finalGroup: AlertGroupRecord,
    ): AlertRouteActionOutcome = when {
        context.deliverySilenced ->
            AlertRouteActionOutcome(
                AlertRouteActionState.SKIPPED,
                "Incident action skipped because delivery is silenced",
            )
        finalGroup.incidentId != null && initialGroup.incidentId == null ->
            AlertRouteActionOutcome(AlertRouteActionState.SUCCEEDED, "Incident created or attached")
        finalGroup.incidentId != null ->
            AlertRouteActionOutcome(AlertRouteActionState.SUCCEEDED, "Alert linked to the existing incident")
        finalGroup.candidateIncidentId != null && finalGroup.behavior != AlertRouteGroupingBehavior.AUTOMATIC ->
            AlertRouteActionOutcome(AlertRouteActionState.SKIPPED, "Incident attachment requires automatic grouping")
        else ->
            AlertRouteActionOutcome(AlertRouteActionState.SKIPPED, "Incident creation is disabled for this route")
    }

    private fun applyIncidentActions(
        context: AlertFanoutContext,
        episodeId: Uuid,
        initialGroup: AlertGroupRecord,
    ): AlertGroupRecord {
        var group = initialGroup
        val actor by lazy { resolveActor(context.event.organizationId, group.routeId) }
        if (!context.deliverySilenced && group.incidentId == null) {
            group = when {
                group.candidateIncidentId != null && group.behavior == AlertRouteGroupingBehavior.AUTOMATIC ->
                    groupCommands.execute(
                        AttachAlertGroupIncidentCommand(
                            commandKey = "route-auto-attach:${group.id}",
                            actor = actor,
                            groupId = group.id,
                            expectedVersion = group.version,
                            incidentId = group.candidateIncidentId,
                            automatic = true,
                        ),
                    ).group
                group.candidateIncidentId == null && group.incidentTemplateSnapshot.boolean("create") == true ->
                    groupCommands.execute(
                        CreateAlertGroupTriageCommand(
                            commandKey = "route-create-incident:${group.id}",
                            actor = actor,
                    groupId = group.id,
                    expectedVersion = group.version,
                    initialStatus = group.incidentTemplateSnapshot.incidentStatus(),
                    severity = derivedIncidentSeverity(context.event.priority, group),
                ),
                    ).group
                else -> group
            }
        }
        val attachedIncident = group.incidentId
        if (attachedIncident != null && isIncidentEligible(attachedIncident) &&
            !isEpisodeLinked(attachedIncident, episodeId)
        ) {
            group = groupCommands.execute(
                AttachAlertGroupIncidentCommand(
                    commandKey = "route-link-episode:${group.id}:$episodeId",
                    actor = actor,
                    groupId = group.id,
                    expectedVersion = group.version,
                    incidentId = attachedIncident,
                ),
            ).group
        }
        return group
    }

    private fun derivedIncidentSeverity(priority: AlertPriority, group: AlertGroupRecord): String? {
        val configured = group.incidentTemplateSnapshot.text("severity") ?: return null
        if (configured != ALERT_PRIORITY_SEVERITY) return configured
        return when (priority) {
            AlertPriority.P0 -> "SEV-0"
            AlertPriority.P1 -> "SEV-1"
            AlertPriority.P2 -> "SEV-2"
            else -> null
        }
    }

    suspend fun recoverDue(enabled: (Int) -> Boolean = FeatureRegistry::isNativeIncidentResponseEntitled) {
        groupService.recoveryCandidates().forEach { (organizationId, groupId) ->
            if (!enabled(organizationId)) return@forEach
            try {
                recoverGroup(organizationId, groupService.get(organizationId, groupId))
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                executionLogger.error(error) {
                    "Alert-route recovery failed for organization $organizationId and group $groupId"
                }
            }
        }
    }

    private suspend fun page(
        context: AlertFanoutContext,
        group: AlertGroupRecord,
        policyIds: List<Int>,
    ): AlertRouteActionOutcome {
        if (policyIds.isEmpty()) {
            return AlertRouteActionOutcome(AlertRouteActionState.SKIPPED, "No paging targets are configured")
        }
        val episodeId = requireNotNull(context.episode).resourceId
        val commandKey = pagingCommandKey(group, episodeId)
            ?: return AlertRouteActionOutcome(AlertRouteActionState.SKIPPED, "Paging is disabled for this route")
        val attemptTime = now()
        val existingByKey = groupService.escalations(context.event.organizationId, group.id)
            .associateBy(AlertGroupEscalationRecord::escalationKey)
        policyIds.distinct().forEach { policyId ->
            val escalationKey = "$commandKey:$policyId"
            if (existingByKey[escalationKey] == null) {
                groupService.recordEscalation(
                    AlertGroupEscalationRecord(
                        context.event.organizationId,
                        group.id,
                        policyId,
                        escalationKey,
                        null,
                        AlertGroupEscalationState.PENDING,
                    ),
                    attemptTime,
                )
            }
        }
        val persisted = groupService.escalations(context.event.organizationId, group.id)
        if (!groupService.claimPaging(
                AlertGroupPagingClaim(
                    context.event.organizationId,
                    group.id,
                    episodeId,
                    commandKey,
                    attemptTime,
                ),
            )
        ) {
            return AlertRouteActionOutcome(AlertRouteActionState.SKIPPED, "Paging was already claimed for this group")
        }
        var succeeded = true
        policyIds.distinct().forEach { policyId ->
            val escalationKey = "$commandKey:$policyId"
            val existing = persisted.firstOrNull { it.escalationKey == escalationKey }
            if (existing?.state == AlertGroupEscalationState.TRIGGERED) return@forEach
            try {
                val alertId = onCallGateway.trigger(
                        AlertRouteEscalationRequest(
                            organizationId = context.event.organizationId,
                            escalationPolicyId = policyId,
                            title = context.event.title,
                            description = context.event.description,
                            priority = context.event.priority.wire,
                            alertSource = ROUTE_ALERT_SOURCE,
                            deduplicationKey = escalationKey,
                            metadata = Json.encodeToString(context.event.metadata),
                        ),
                    ) ?: existingOpenAlertId(context.event.organizationId, escalationKey)
                    ?: error("Alert-route escalation did not create or locate an on-call alert")
                groupService.recordEscalation(
                    AlertGroupEscalationRecord(
                        context.event.organizationId,
                        group.id,
                        policyId,
                        escalationKey,
                        Uuid.parse(alertId),
                        AlertGroupEscalationState.TRIGGERED,
                    ),
                    now(),
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                succeeded = false
                groupService.recordEscalation(
                    AlertGroupEscalationRecord(
                        context.event.organizationId,
                        group.id,
                        policyId,
                        escalationKey,
                        null,
                        AlertGroupEscalationState.FAILED,
                    ),
                    now(),
                )
            }
        }
        groupService.completePaging(
            AlertGroupPagingCompletion(
                context.event.organizationId,
                group.id,
                episodeId,
                commandKey,
                succeeded,
            ),
        )
        return if (succeeded) {
            AlertRouteActionOutcome(
                AlertRouteActionState.SUCCEEDED,
                "Paged ${policyIds.distinct().size} escalation target(s)",
            )
        } else {
            AlertRouteActionOutcome(AlertRouteActionState.FAILED, "One or more alert-route paging targets failed")
        }
    }

    private fun pagingCommandKey(group: AlertGroupRecord, episodeId: Uuid): String? =
        when (group.pagingMode) {
            AlertRoutePagingMode.FIRST_EPISODE_PER_GROUP -> "route-page:${group.id}"
            AlertRoutePagingMode.EVERY_EPISODE -> "route-page:${group.id}:$episodeId"
            AlertRoutePagingMode.NONE -> null
        }

    private suspend fun recover(organizationId: Int, episodeId: Uuid) {
        val group = groupService.recordResolution(organizationId, episodeId, now()) ?: return
        recoverGroup(organizationId, group)
    }

    private suspend fun recoverGroup(organizationId: Int, group: AlertGroupRecord) {
        if (group.members.any { it.state == AlertGroupMemberState.ACTIVE && it.resolvedAt == null }) return
        val recovery = group.routeSnapshot["route"]?.jsonObject?.get("recovery")?.jsonObject ?: JsonObject(emptyMap())
        val grace = recovery["graceSeconds"]?.jsonPrimitive?.intOrNull ?: 0
        val lastResolution = group.members.mapNotNull { it.resolvedAt }.maxOrNull() ?: return
        if (now() < lastResolution + grace.seconds) return
        if (recovery["cancelEscalations"]?.jsonPrimitive?.booleanOrNull == true) {
            cancelEscalations(organizationId, group)
        }
        if (recovery["declineUnacceptedTriage"]?.jsonPrimitive?.booleanOrNull == true) declineTriage(group)
        groupService.completeRecovery(organizationId, group.id)
    }

    private suspend fun cancelEscalations(organizationId: Int, group: AlertGroupRecord) {
        groupService.escalations(organizationId, group.id)
            .filter { it.state == AlertGroupEscalationState.TRIGGERED }
            .forEach { escalation ->
                onCallGateway.resolve(
                    escalation.organizationId,
                    ROUTE_ALERT_SOURCE,
                    escalation.escalationKey,
                )
                groupService.recordEscalation(
                    escalation.copy(state = AlertGroupEscalationState.CANCELLED),
                )
            }
    }

    private fun declineTriage(group: AlertGroupRecord) {
        val incidentResourceId = group.incidentId ?: return
        val incident = transaction {
            OnCallIncidents.selectAll().where { OnCallIncidents.resourceId eq incidentResourceId }.singleOrNull()
        } ?: return
        if (NativeIncidentStatus.fromWire(incident[OnCallIncidents.status]) != NativeIncidentStatus.TRIAGE) return
        val organizationId = incident[OnCallIncidents.organizationId]
        val actor = resolveActor(organizationId, group.routeId)
        incidentCommands.execute(
            DeclineIncidentCommand(
                commandKey = "route-auto-decline:${group.id}",
                actor = IncidentCommandActor(organizationId, actor.userId, ROUTE_AUTOMATION_ORIGIN),
                incidentId = incident[OnCallIncidents.id].value,
                reason = "All linked alert episodes resolved",
            ),
        )
    }

    private fun resolveActor(organizationId: Int, routeId: Uuid): AlertGroupActor = transaction {
        val creator = EnterpriseAlertRoutes.selectAll().where {
            (EnterpriseAlertRoutes.organizationId eq organizationId) and
                (EnterpriseAlertRoutes.resourceId eq routeId)
        }.singleOrNull()?.get(EnterpriseAlertRoutes.createdBy)
        val userId = creator?.takeIf { candidate ->
            Memberships.selectAll().where {
                (Memberships.organization_id eq organizationId) and (Memberships.user_id eq candidate)
            }.limit(1).any()
        } ?: Memberships.selectAll().where { Memberships.organization_id eq organizationId }
            .orderBy(Memberships.user_id to SortOrder.ASC)
            .limit(1)
            .firstOrNull()
            ?.get(Memberships.user_id)
            ?: throw IncidentCommandNotFoundException(
                "No organization member is available for alert-route automation",
            )
        AlertGroupActor(organizationId, userId, ROUTE_AUTOMATION_ORIGIN)
    }

    private fun isEpisodeLinked(incidentId: Uuid, episodeId: Uuid): Boolean = transaction {
        val internalIncidentId = OnCallIncidents.selectAll().where { OnCallIncidents.resourceId eq incidentId }
            .single()[OnCallIncidents.id].value
        NativeIncidentSourceLinks.selectAll().where {
            (NativeIncidentSourceLinks.incidentId eq internalIncidentId) and
                (NativeIncidentSourceLinks.sourceType eq IncidentSourceType.ALERT_EPISODE.wire) and
                (NativeIncidentSourceLinks.sourceKey eq episodeId.toString())
        }.limit(1).any()
    }

    private fun isIncidentEligible(incidentId: Uuid): Boolean = transaction {
        OnCallIncidents.selectAll().where { OnCallIncidents.resourceId eq incidentId }
            .singleOrNull()
            ?.get(OnCallIncidents.status)
            ?.let(NativeIncidentStatus::fromWire)
            ?.let { it == NativeIncidentStatus.TRIAGE || it == NativeIncidentStatus.ACTIVE }
            ?: false
    }

    private fun existingOpenAlertId(organizationId: Int, escalationKey: String): String? = transaction {
        OnCallAlerts.selectAll().where {
            (OnCallAlerts.organizationId eq organizationId) and
                (OnCallAlerts.alertSource eq ROUTE_ALERT_SOURCE) and
                (OnCallAlerts.deduplicationKey eq escalationKey) and
                (OnCallAlerts.status inList listOf("TRIGGERED", "ACKNOWLEDGED"))
        }.singleOrNull()?.get(OnCallAlerts.resourceId)?.toString()
    }

    private fun Map<String, kotlinx.serialization.json.JsonElement>.boolean(key: String): Boolean? =
        get(key)?.jsonPrimitive?.booleanOrNull

    private fun Map<String, kotlinx.serialization.json.JsonElement>.text(key: String): String? =
        get(key)?.jsonPrimitive?.contentOrNull

    private fun Map<String, kotlinx.serialization.json.JsonElement>.incidentStatus(): NativeIncidentStatus =
        when (get("mode")?.jsonPrimitive?.content) {
            AlertRouteIncidentMode.ACTIVE.wire -> NativeIncidentStatus.ACTIVE
            else -> NativeIncidentStatus.TRIAGE
        }

    private companion object {
        val CORRELATING_GROUPING_BEHAVIORS = setOf(
            AlertRouteGroupingBehavior.SUGGESTED,
            AlertRouteGroupingBehavior.AUTOMATIC,
        )
    }
}

data class AlertRouteEscalationRequest(
    val organizationId: Int,
    val escalationPolicyId: Int,
    val title: String,
    val description: String?,
    val priority: String,
    val alertSource: String,
    val deduplicationKey: String,
    val metadata: String,
)

interface AlertRouteOnCallGateway {
    suspend fun trigger(request: AlertRouteEscalationRequest): String?

    suspend fun resolve(organizationId: Int, alertSource: String, deduplicationKey: String): Boolean
}

private object FeatureRegistryAlertRouteOnCallGateway : AlertRouteOnCallGateway {
    override suspend fun trigger(request: AlertRouteEscalationRequest): String? =
        requireNotNull(FeatureRegistry.getOnCallBridge()) { "On-call bridge is unavailable" }
        .triggerEscalation(
            request.organizationId,
            request.escalationPolicyId,
            request.title,
            request.description,
            request.priority,
            request.alertSource,
            request.deduplicationKey,
            request.metadata,
        )

    override suspend fun resolve(organizationId: Int, alertSource: String, deduplicationKey: String): Boolean =
        requireNotNull(FeatureRegistry.getOnCallBridge()) { "On-call bridge is unavailable" }
            .resolveEscalation(organizationId, alertSource, deduplicationKey)
}
