// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.alerts.models.AlertEpisodes
import com.moneat.enterprise.alertroutes.commands.AlertGroupPolicy
import com.moneat.enterprise.alertroutes.context.AlertRouteContext
import com.moneat.enterprise.alertroutes.evaluation.AlertRouteDecision
import com.moneat.enterprise.alertroutes.evaluation.CanonicalAlertGrouping
import com.moneat.enterprise.alertroutes.models.AlertGroupDecisionType
import com.moneat.enterprise.alertroutes.models.AlertGroupMemberState
import com.moneat.enterprise.alertroutes.models.AlertGroupPagingState
import com.moneat.enterprise.alertroutes.models.AlertGroupState
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingBehavior
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingWindowKind
import com.moneat.enterprise.alertroutes.models.AlertRoutePagingMode
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroupDecisions
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroupEscalations
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroupMembers
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroups
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRouteRevisions
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRoutes
import com.moneat.enterprise.incidents.commands.IncidentCommandConflictException
import com.moneat.enterprise.incidents.commands.IncidentCommandNotFoundException
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.EscalationPolicies
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Transactional grouping persistence used by route execution and responder commands. */
class AlertGroupService(private val policy: AlertGroupPolicy = AlertGroupPolicy()) {
    fun list(organizationId: Int): List<AlertGroupRecord> {
        policy.requireEnabled(organizationId)
        return AlertGroupReader.list(organizationId)
    }

    fun get(organizationId: Int, groupId: Uuid): AlertGroupRecord {
        policy.requireEnabled(organizationId)
        return AlertGroupReader.get(organizationId, groupId)
    }

    fun recordFiring(
        context: AlertRouteContext,
        decision: AlertRouteDecision,
        episodeId: Uuid,
        candidateIncidentId: Uuid? = null,
        now: Instant = Clock.System.now(),
    ): AlertGroupRecord {
        policy.requireEnabled(context.organizationId)
        return transaction {
            lockOrganization(context.organizationId)
            val episode = requireEpisode(context.organizationId, episodeId)
            val effectiveDecision = normalizeSingletonDecision(context, decision, episodeId)
            closeExpiredGroups(context.organizationId, now)
            existingMembership(context.organizationId, episode[AlertEpisodes.id].value)?.let { membership ->
                return@transaction updateRepeatedFiring(context.organizationId, membership, effectiveDecision, now)
            }
            val group =
                findOpenGroup(context.organizationId, effectiveDecision, now)
                    ?: createGroup(context, effectiveDecision, episode, candidateIncidentId, now)
            addMember(
                context.organizationId,
                group[EnterpriseAlertGroups.id].value,
                episode,
                effectiveDecision.paging.mode,
                now,
            )
            touchGroup(group, effectiveDecision, now)
            AlertGroupReader.get(context.organizationId, group[EnterpriseAlertGroups.resourceId])
        }
    }

    fun recordResolution(organizationId: Int, episodeId: Uuid, now: Instant = Clock.System.now()): AlertGroupRecord? {
        policy.requireEnabled(organizationId)
        return transaction {
            lockOrganization(organizationId)
            val episode = requireEpisode(organizationId, episodeId)
            val membership =
                existingMembership(organizationId, episode[AlertEpisodes.id].value)
                    ?: return@transaction null
            EnterpriseAlertGroupMembers.update({
                EnterpriseAlertGroupMembers.id eq membership[EnterpriseAlertGroupMembers.id]
            }) {
                it[resolvedAt] = now
                it[lastSeenAt] = now
                it[updatedAt] = now
                it[version] = membership[EnterpriseAlertGroupMembers.version] + 1
            }
            val group = AlertGroupReader.requireGroup(
                organizationId,
                groupResourceId(membership[EnterpriseAlertGroupMembers.groupId]),
            )
            AlertGroupReader.load(organizationId, group)
        }
    }

    /** Atomically reserves the route-configured paging slot. False means this delivery was already claimed. */
    fun claimPaging(
        organizationId: Int,
        groupId: Uuid,
        episodeId: Uuid,
        commandKey: String,
    ): Boolean {
        policy.requireEnabled(organizationId)
        return transaction {
            lockOrganization(organizationId)
            val group = AlertGroupReader.requireGroup(organizationId, groupId, lock = true)
            when (AlertRoutePagingMode.valueOf(group[EnterpriseAlertGroups.pagingMode])) {
                AlertRoutePagingMode.NONE -> false
                AlertRoutePagingMode.FIRST_EPISODE_PER_GROUP ->
                    EnterpriseAlertGroups.update({
                        (EnterpriseAlertGroups.id eq group[EnterpriseAlertGroups.id]) and
                            (EnterpriseAlertGroups.pagingState eq AlertGroupPagingState.READY.wire)
                    }) {
                        it[pagingState] = AlertGroupPagingState.CLAIMED.wire
                        it[pagingCommandKey] = commandKey
                        it[updatedAt] = Clock.System.now()
                    } == 1
                AlertRoutePagingMode.EVERY_EPISODE -> {
                    val member = requireMember(organizationId, group[EnterpriseAlertGroups.id].value, episodeId)
                    EnterpriseAlertGroupMembers.update({
                        (EnterpriseAlertGroupMembers.id eq member[EnterpriseAlertGroupMembers.id]) and
                            (EnterpriseAlertGroupMembers.pagingState eq AlertGroupPagingState.READY.wire)
                    }) {
                        it[pagingState] = AlertGroupPagingState.CLAIMED.wire
                        it[pagingCommandKey] = commandKey
                        it[updatedAt] = Clock.System.now()
                    } == 1
                }
            }
        }
    }

    fun completePaging(completion: AlertGroupPagingCompletion) {
        policy.requireEnabled(completion.organizationId)
        transaction {
            lockOrganization(completion.organizationId)
            val group = AlertGroupReader.requireGroup(completion.organizationId, completion.groupId, lock = true)
            val next = if (completion.succeeded) AlertGroupPagingState.DELIVERED else AlertGroupPagingState.FAILED
            when (AlertRoutePagingMode.valueOf(group[EnterpriseAlertGroups.pagingMode])) {
                AlertRoutePagingMode.NONE -> Unit
                AlertRoutePagingMode.FIRST_EPISODE_PER_GROUP ->
                    EnterpriseAlertGroups.update({
                        (EnterpriseAlertGroups.id eq group[EnterpriseAlertGroups.id]) and
                            (EnterpriseAlertGroups.pagingCommandKey eq completion.commandKey)
                    }) {
                        it[pagingState] = next.wire
                        it[updatedAt] = Clock.System.now()
                    }
                AlertRoutePagingMode.EVERY_EPISODE -> {
                    val member = requireMember(
                        completion.organizationId,
                        group[EnterpriseAlertGroups.id].value,
                        completion.episodeId,
                    )
                    EnterpriseAlertGroupMembers.update({
                        (EnterpriseAlertGroupMembers.id eq member[EnterpriseAlertGroupMembers.id]) and
                            (EnterpriseAlertGroupMembers.pagingCommandKey eq completion.commandKey)
                    }) {
                        it[pagingState] = next.wire
                        it[updatedAt] = Clock.System.now()
                    }
                }
            }
        }
    }

    /** Persist the concrete on-call alert created for one escalation target. */
    fun recordEscalation(record: AlertGroupEscalationRecord) {
        policy.requireEnabled(record.organizationId)
        transaction {
            val group = AlertGroupReader.requireGroup(record.organizationId, record.groupId)
            requireEscalationPolicy(record.organizationId, record.escalationPolicyId)
            val alertInternalId = record.onCallAlertId?.let { requireOnCallAlert(record.organizationId, it) }
            val now = Clock.System.now()
            EnterpriseAlertGroupEscalations.insertIgnore {
                it[resourceId] = Uuid.random()
                it[EnterpriseAlertGroupEscalations.organizationId] = record.organizationId
                it[EnterpriseAlertGroupEscalations.groupId] = group[EnterpriseAlertGroups.id].value
                it[EnterpriseAlertGroupEscalations.escalationKey] = record.escalationKey
                it[EnterpriseAlertGroupEscalations.escalationPolicyId] = record.escalationPolicyId
                it[EnterpriseAlertGroupEscalations.onCallAlertId] = alertInternalId
                it[EnterpriseAlertGroupEscalations.state] = record.state.wire
                it[createdAt] = now
                it[updatedAt] = now
            }
            EnterpriseAlertGroupEscalations.update({
                (EnterpriseAlertGroupEscalations.groupId eq group[EnterpriseAlertGroups.id].value) and
                    (EnterpriseAlertGroupEscalations.escalationKey eq record.escalationKey)
            }) {
                it[onCallAlertId] = alertInternalId
                it[state] = record.state.wire
                it[updatedAt] = now
            }
        }
    }

    private fun createGroup(
        context: AlertRouteContext,
        decision: AlertRouteDecision,
        episode: ResultRow,
        candidateIncidentId: Uuid?,
        now: Instant,
    ): ResultRow {
        val activeCount = EnterpriseAlertGroups.selectAll().where {
            (EnterpriseAlertGroups.organizationId eq context.organizationId) and
                (EnterpriseAlertGroups.state eq AlertGroupState.OPEN.wire) and
                (EnterpriseAlertGroups.closesAt greater now)
        }.count()
        policy.requireActiveGroupCapacity(
            context.organizationId,
            activeCount + 1,
            "alert-group-capacity:${decision.routeId}:${decision.grouping.groupKey}:$now",
        )
        val route = requireRoute(context.organizationId, decision.routeId)
        val candidate = candidateIncidentId?.let { eligibleIncident(context.organizationId, it) }
        val routeSnapshot = requireRouteSnapshot(context.organizationId, decision)
        val templateSnapshot = incidentTemplateSnapshot(decision)
        val closesAt = now + decision.grouping.windowSeconds.seconds
        val groupResourceId = Uuid.random()
        val groupId = EnterpriseAlertGroups.insertAndGetId {
            it[resourceId] = groupResourceId
            it[organizationId] = context.organizationId
            it[routeId] = route[EnterpriseAlertRoutes.id].value
            it[routeResourceId] = decision.routeId
            it[routeRevision] = decision.revision
            it[identityHash] = decision.grouping.groupKey
            it[groupingTuple] = decision.grouping.tuple.associate { entry ->
                entry.reference to JsonPrimitive(entry.value)
            }
            it[singletonEpisodeId] = episode[AlertEpisodes.id].value.takeIf { decision.grouping.singleton }
            it[groupingBehavior] = decision.grouping.behavior.wire
            it[windowKind] = decision.grouping.windowKind.wire
            it[windowSeconds] = decision.grouping.windowSeconds
            it[openedAt] = now
            it[lastAlertAt] = now
            it[EnterpriseAlertGroups.closesAt] = closesAt
            it[state] = AlertGroupState.OPEN.wire
            it[version] = 1
            it[incidentId] = null
            it[EnterpriseAlertGroups.candidateIncidentId] = candidate?.get(OnCallIncidents.id)?.value
            it[EnterpriseAlertGroups.routeSnapshot] = routeSnapshot
            it[incidentTemplateSnapshot] = templateSnapshot
            it[pagingMode] = decision.paging.mode.wire
            it[pagingState] = if (decision.paging.mode == AlertRoutePagingMode.NONE) {
                AlertGroupPagingState.NOT_REQUIRED.wire
            } else {
                AlertGroupPagingState.READY.wire
            }
            it[pagingCommandKey] = null
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        insertDecision(
            DecisionInsert(
                organizationId = context.organizationId,
                groupId = groupId,
                type = AlertGroupDecisionType.GROUP_CREATED,
                episodeId = episode[AlertEpisodes.id].value,
                commandKey = "group-created:$groupResourceId",
                details = mapOf("routeRevision" to JsonPrimitive(decision.revision)),
                now = now,
            ),
        )
        if (decision.grouping.behavior == AlertRouteGroupingBehavior.SUGGESTED && candidate != null) {
            insertDecision(
                DecisionInsert(
                    organizationId = context.organizationId,
                    groupId = groupId,
                    type = AlertGroupDecisionType.SUGGESTED,
                    episodeId = episode[AlertEpisodes.id].value,
                    incidentId = candidate[OnCallIncidents.id].value,
                    commandKey = "group-suggested:$groupResourceId",
                    now = now,
                ),
            )
        }
        return EnterpriseAlertGroups.selectAll().where { EnterpriseAlertGroups.id eq groupId }.single()
    }

    private fun normalizeSingletonDecision(
        context: AlertRouteContext,
        decision: AlertRouteDecision,
        episodeId: Uuid,
    ): AlertRouteDecision {
        if (!decision.grouping.singleton) return decision
        val authoritativeContext = context.copy(episode = context.episode.copy(resourceId = episodeId.toString()))
        val identity = CanonicalAlertGrouping.resolve(decision.routeId, decision.grouping.keys, authoritativeContext)
        return decision.copy(
            grouping = decision.grouping.copy(
                groupKey = identity.hash,
                tuple = identity.entries,
                singleton = identity.singleton,
            ),
        )
    }

    private fun updateRepeatedFiring(
        organizationId: Int,
        membership: ResultRow,
        decision: AlertRouteDecision,
        now: Instant,
    ): AlertGroupRecord {
        val groupId = membership[EnterpriseAlertGroupMembers.groupId]
        val group = EnterpriseAlertGroups.selectAll().where { EnterpriseAlertGroups.id eq groupId }.single()
        if (membership[EnterpriseAlertGroupMembers.state] != AlertGroupMemberState.ACTIVE.wire) {
            return AlertGroupReader.get(organizationId, group[EnterpriseAlertGroups.resourceId])
        }
        EnterpriseAlertGroupMembers.update({
            EnterpriseAlertGroupMembers.id eq membership[EnterpriseAlertGroupMembers.id]
        }) {
            it[lastSeenAt] = now
            it[resolvedAt] = null
            it[updatedAt] = now
            it[version] = membership[EnterpriseAlertGroupMembers.version] + 1
        }
        touchGroup(group, decision, now)
        return AlertGroupReader.get(organizationId, group[EnterpriseAlertGroups.resourceId])
    }

    private fun touchGroup(group: ResultRow, decision: AlertRouteDecision, now: Instant) {
        if (group[EnterpriseAlertGroups.state] != AlertGroupState.OPEN.wire) return
        EnterpriseAlertGroups.update({ EnterpriseAlertGroups.id eq group[EnterpriseAlertGroups.id] }) {
            it[lastAlertAt] = now
            if (decision.grouping.windowKind == AlertRouteGroupingWindowKind.ROLLING) {
                it[closesAt] = now + decision.grouping.windowSeconds.seconds
            }
            it[updatedAt] = now
        }
    }

    private fun addMember(
        organizationId: Int,
        groupId: Int,
        episode: ResultRow,
        pagingMode: AlertRoutePagingMode,
        now: Instant,
    ) {
        EnterpriseAlertGroupMembers.insert {
            it[resourceId] = Uuid.random()
            it[EnterpriseAlertGroupMembers.organizationId] = organizationId
            it[EnterpriseAlertGroupMembers.groupId] = groupId
            it[alertEpisodeId] = episode[AlertEpisodes.id].value
            it[state] = AlertGroupMemberState.ACTIVE.wire
            it[version] = 1
            it[pagingState] = if (pagingMode == AlertRoutePagingMode.NONE) {
                AlertGroupPagingState.NOT_REQUIRED.wire
            } else {
                AlertGroupPagingState.READY.wire
            }
            it[pagingCommandKey] = null
            it[firstJoinedAt] = now
            it[lastSeenAt] = now
            it[resolvedAt] = null
            it[updatedAt] = now
        }
    }

    private fun findOpenGroup(organizationId: Int, decision: AlertRouteDecision, now: Instant): ResultRow? =
        EnterpriseAlertGroups.selectAll().where {
            (EnterpriseAlertGroups.organizationId eq organizationId) and
                (EnterpriseAlertGroups.routeResourceId eq decision.routeId) and
                (EnterpriseAlertGroups.identityHash eq decision.grouping.groupKey) and
                (EnterpriseAlertGroups.state eq AlertGroupState.OPEN.wire) and
                (EnterpriseAlertGroups.closesAt greater now)
        }.singleOrNull()

    private fun closeExpiredGroups(organizationId: Int, now: Instant) {
        EnterpriseAlertGroups.update({
            (EnterpriseAlertGroups.organizationId eq organizationId) and
                (EnterpriseAlertGroups.state eq AlertGroupState.OPEN.wire) and
                (EnterpriseAlertGroups.closesAt lessEq now)
        }) {
            it[state] = AlertGroupState.CLOSED.wire
            it[updatedAt] = now
        }
    }

    private fun existingMembership(organizationId: Int, episodeId: Int): ResultRow? =
        EnterpriseAlertGroupMembers.selectAll().where {
            (EnterpriseAlertGroupMembers.organizationId eq organizationId) and
                (EnterpriseAlertGroupMembers.alertEpisodeId eq episodeId)
        }.singleOrNull()

    private fun requireEpisode(organizationId: Int, episodeId: Uuid): ResultRow =
        AlertEpisodes.selectAll().where {
            (AlertEpisodes.organizationId eq organizationId) and (AlertEpisodes.resourceId eq episodeId)
        }.singleOrNull() ?: throw IncidentCommandNotFoundException("Alert episode not found")

    private fun requireMember(organizationId: Int, groupId: Int, episodeId: Uuid): ResultRow =
        (EnterpriseAlertGroupMembers innerJoin AlertEpisodes)
            .selectAll()
            .where {
                (EnterpriseAlertGroupMembers.organizationId eq organizationId) and
                    (EnterpriseAlertGroupMembers.groupId eq groupId) and
                    (AlertEpisodes.resourceId eq episodeId)
            }.singleOrNull() ?: throw IncidentCommandNotFoundException("Alert group episode not found")

    private fun requireOnCallAlert(organizationId: Int, alertId: Uuid): Int =
        OnCallAlerts.selectAll().where {
            (OnCallAlerts.organizationId eq organizationId) and (OnCallAlerts.resourceId eq alertId)
        }.singleOrNull()?.get(OnCallAlerts.id)?.value
            ?: throw IncidentCommandNotFoundException("On-call alert not found")

    private fun requireEscalationPolicy(organizationId: Int, policyId: Int) {
        val exists = EscalationPolicies.selectAll().where {
            (EscalationPolicies.organizationId eq organizationId) and (EscalationPolicies.id eq policyId)
        }.any()
        if (!exists) throw IncidentCommandNotFoundException("Escalation policy not found")
    }

    private fun requireRoute(organizationId: Int, routeId: Uuid): ResultRow =
        EnterpriseAlertRoutes.selectAll().where {
            (EnterpriseAlertRoutes.organizationId eq organizationId) and
                (EnterpriseAlertRoutes.resourceId eq routeId)
        }.singleOrNull() ?: throw IncidentCommandNotFoundException("Alert route not found")

    private fun requireRouteSnapshot(organizationId: Int, decision: AlertRouteDecision): Map<String, JsonElement> =
        EnterpriseAlertRouteRevisions.selectAll().where {
            (EnterpriseAlertRouteRevisions.organizationId eq organizationId) and
                (EnterpriseAlertRouteRevisions.routeResourceId eq decision.routeId) and
                (EnterpriseAlertRouteRevisions.revision eq decision.revision)
        }.singleOrNull()?.get(EnterpriseAlertRouteRevisions.snapshot)
            ?: throw IncidentCommandConflictException("Matched alert route revision is unavailable")

    private fun eligibleIncident(organizationId: Int, incidentId: Uuid): ResultRow {
        val incident = OnCallIncidents.selectAll().where {
            (OnCallIncidents.organizationId eq organizationId) and (OnCallIncidents.resourceId eq incidentId)
        }.singleOrNull() ?: throw IncidentCommandNotFoundException("Candidate incident not found")
        val status = NativeIncidentStatus.fromWire(incident[OnCallIncidents.status])
        if (status !in ELIGIBLE_INCIDENT_STATUSES) {
            throw IncidentCommandConflictException("Only triage or active incidents can receive an alert group")
        }
        return incident
    }

    private fun incidentTemplateSnapshot(decision: AlertRouteDecision): Map<String, JsonElement> =
        buildMap {
            put("create", JsonPrimitive(decision.incident.create))
            put("mode", JsonPrimitive(decision.incident.mode.wire))
            decision.incident.title?.let { put("title", JsonPrimitive(it)) }
            decision.incident.summary?.let { put("summary", JsonPrimitive(it)) }
            decision.incident.severity?.let { put("severity", JsonPrimitive(it)) }
            decision.incident.incidentType?.let { put("incidentType", JsonPrimitive(it)) }
        }

    private fun lockOrganization(organizationId: Int) {
        Organizations.selectAll().where { Organizations.id eq organizationId }.forUpdate().singleOrNull()
            ?: throw IncidentCommandNotFoundException("Organization not found")
    }

    private fun groupResourceId(groupId: Int): Uuid =
        EnterpriseAlertGroups.selectAll().where { EnterpriseAlertGroups.id eq groupId }.single()[
            EnterpriseAlertGroups.resourceId
        ]

    private fun insertDecision(decision: DecisionInsert) {
        EnterpriseAlertGroupDecisions.insert {
            it[resourceId] = Uuid.random()
            it[EnterpriseAlertGroupDecisions.organizationId] = decision.organizationId
            it[EnterpriseAlertGroupDecisions.groupId] = decision.groupId
            it[decisionType] = decision.type.wire
            it[alertEpisodeId] = decision.episodeId
            it[EnterpriseAlertGroupDecisions.incidentId] = decision.incidentId
            it[actorUserId] = decision.actorId
            it[EnterpriseAlertGroupDecisions.commandKey] = decision.commandKey.take(MAX_COMMAND_KEY_LENGTH)
            it[EnterpriseAlertGroupDecisions.details] = decision.details
            it[createdAt] = decision.now
        }
    }

    private data class DecisionInsert(
        val organizationId: Int,
        val groupId: Int,
        val type: AlertGroupDecisionType,
        val episodeId: Int? = null,
        val incidentId: Int? = null,
        val actorId: Int? = null,
        val commandKey: String,
        val details: Map<String, JsonElement> = emptyMap(),
        val now: Instant,
    )

    private companion object {
        const val MAX_COMMAND_KEY_LENGTH = 160
        val ELIGIBLE_INCIDENT_STATUSES = setOf(NativeIncidentStatus.TRIAGE, NativeIncidentStatus.ACTIVE)
    }
}
