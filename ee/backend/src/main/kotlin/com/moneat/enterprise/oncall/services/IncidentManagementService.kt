// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

import com.moneat.enterprise.oncall.models.Incident
import com.moneat.enterprise.oncall.models.IncidentTimeline
import com.moneat.enterprise.oncall.models.IncidentTimelineEvent
import com.moneat.enterprise.oncall.models.Incidents
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.Users
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock

class IncidentManagementService(
    private val escalationEngine: EscalationEngine,
) {
    fun getIncident(
        incidentId: Int,
        currentUserId: Int? = null,
    ): Incident? =
        transaction {
            val row =
                Incidents
                    .join(EscalationPolicies, JoinType.LEFT, Incidents.escalationPolicyId, EscalationPolicies.id)
                    .join(Users, JoinType.LEFT, Incidents.acknowledgedBy, Users.id)
                    .selectAll()
                    .where { Incidents.id eq incidentId }
                    .singleOrNull() ?: return@transaction null

            val escalationTimes = escalationEngine.getNextEscalationTimes()
            val viewed =
                if (currentUserId != null) {
                    hasUserViewed(incidentId, currentUserId)
                } else {
                    false
                }

            Incident(
                id = row[Incidents.id].value,
                organizationId = row[Incidents.organizationId],
                escalationPolicyId = row[Incidents.escalationPolicyId],
                escalationPolicyName = row.getOrNull(EscalationPolicies.name),
                title = row[Incidents.title],
                description = row[Incidents.description],
                priorityLevel = row[Incidents.priorityLevel],
                status = row[Incidents.status],
                alertSource = row[Incidents.alertSource],
                deduplicationKey = row[Incidents.deduplicationKey],
                currentStep = row[Incidents.currentStep],
                repeatIteration = row[Incidents.repeatIteration],
                triggeredAt = row[Incidents.triggeredAt].toString(),
                acknowledgedAt = row[Incidents.acknowledgedAt]?.toString(),
                acknowledgedBy = row[Incidents.acknowledgedBy],
                acknowledgedByName = row.getOrNull(Users.name),
                resolvedAt = row[Incidents.resolvedAt]?.toString(),
                resolvedBy = row[Incidents.resolvedBy],
                metadata = row[Incidents.metadata],
                nextEscalationAt = escalationTimes[row[Incidents.id].value],
                viewedByCurrentUser = viewed,
                createdAt = row[Incidents.createdAt].toString(),
                updatedAt = row[Incidents.updatedAt].toString(),
            )
        }

    fun listIncidents(
        organizationId: Int,
        status: String? = null,
        priorityLevel: String? = null,
        limit: Int = 50,
        offset: Int = 0,
        currentUserId: Int? = null,
    ): List<Incident> =
        transaction {
            val escalationTimes = escalationEngine.getNextEscalationTimes()

            // Pre-fetch viewed incident IDs for the current user
            val viewedIncidentIds =
                if (currentUserId != null) {
                    IncidentTimeline
                        .selectAll()
                        .where { (IncidentTimeline.eventType eq "VIEWED") and (IncidentTimeline.actorUserId eq currentUserId) }
                        .map { it[IncidentTimeline.incidentId] }
                        .toSet()
                } else {
                    emptySet()
                }

            var query =
                Incidents
                    .join(EscalationPolicies, JoinType.LEFT, Incidents.escalationPolicyId, EscalationPolicies.id)
                    .selectAll()
                    .where { Incidents.organizationId eq organizationId }

            if (status != null) {
                query = query.andWhere { Incidents.status eq status }
            }

            if (priorityLevel != null) {
                query = query.andWhere { Incidents.priorityLevel eq priorityLevel }
            }

            query
                .orderBy(Incidents.triggeredAt to SortOrder.DESC)
                .limit(limit)
                .offset(offset.toLong())
                .map { row ->
                    val incId = row[Incidents.id].value
                    Incident(
                        id = incId,
                        organizationId = row[Incidents.organizationId],
                        escalationPolicyId = row[Incidents.escalationPolicyId],
                        escalationPolicyName = row.getOrNull(EscalationPolicies.name),
                        title = row[Incidents.title],
                        description = row[Incidents.description],
                        priorityLevel = row[Incidents.priorityLevel],
                        status = row[Incidents.status],
                        alertSource = row[Incidents.alertSource],
                        deduplicationKey = row[Incidents.deduplicationKey],
                        currentStep = row[Incidents.currentStep],
                        repeatIteration = row[Incidents.repeatIteration],
                        triggeredAt = row[Incidents.triggeredAt].toString(),
                        acknowledgedAt = row[Incidents.acknowledgedAt]?.toString(),
                        acknowledgedBy = row[Incidents.acknowledgedBy],
                        resolvedAt = row[Incidents.resolvedAt]?.toString(),
                        resolvedBy = row[Incidents.resolvedBy],
                        metadata = row[Incidents.metadata],
                        nextEscalationAt = escalationTimes[incId],
                        viewedByCurrentUser = incId in viewedIncidentIds,
                        createdAt = row[Incidents.createdAt].toString(),
                        updatedAt = row[Incidents.updatedAt].toString(),
                    )
                }
        }

    fun getTimeline(incidentId: Int): List<IncidentTimelineEvent> =
        transaction {
            IncidentTimeline
                .join(Users, JoinType.LEFT, IncidentTimeline.actorUserId, Users.id)
                .selectAll()
                .where { IncidentTimeline.incidentId eq incidentId }
                .orderBy(IncidentTimeline.createdAt to SortOrder.ASC)
                .map { row ->
                    IncidentTimelineEvent(
                        id = row[IncidentTimeline.id].value,
                        incidentId = row[IncidentTimeline.incidentId],
                        eventType = row[IncidentTimeline.eventType],
                        actorUserId = row[IncidentTimeline.actorUserId],
                        actorName = row.getOrNull(Users.name),
                        details = row[IncidentTimeline.details],
                        createdAt = row[IncidentTimeline.createdAt].toString(),
                    )
                }
        }

    fun acknowledge(
        incidentId: Int,
        userId: Int,
    ): Boolean = escalationEngine.acknowledgeIncident(incidentId, userId)

    fun resolve(
        incidentId: Int,
        userId: Int,
    ): Boolean = escalationEngine.resolveIncident(incidentId, userId)

    fun reassign(
        incidentId: Int,
        toUserId: Int,
        byUserId: Int,
    ): Boolean = escalationEngine.reassignIncident(incidentId, toUserId, byUserId)

    fun addNote(
        incidentId: Int,
        userId: Int,
        noteText: String,
    ): IncidentTimelineEvent =
        transaction {
            val now = Clock.System.now()

            val eventId =
                IncidentTimeline
                    .insertAndGetId {
                        it[IncidentTimeline.incidentId] = incidentId
                        it[eventType] = "NOTE_ADDED"
                        it[actorUserId] = userId
                        it[IncidentTimeline.details] = mapOf("note" to JsonPrimitive(noteText))
                        it[createdAt] = now
                    }.value

            val row =
                IncidentTimeline
                    .join(Users, JoinType.LEFT, IncidentTimeline.actorUserId, Users.id)
                    .selectAll()
                    .where { IncidentTimeline.id eq eventId }
                    .single()

            IncidentTimelineEvent(
                id = row[IncidentTimeline.id].value,
                incidentId = row[IncidentTimeline.incidentId],
                eventType = row[IncidentTimeline.eventType],
                actorUserId = row[IncidentTimeline.actorUserId],
                actorName = row.getOrNull(Users.name),
                details = row[IncidentTimeline.details],
                createdAt = row[IncidentTimeline.createdAt].toString(),
            )
        }

    fun viewIncident(
        incidentId: Int,
        userId: Int,
    ): Boolean =
        transaction {
            // Only log once per user per incident
            val alreadyViewed =
                IncidentTimeline
                    .selectAll()
                    .where {
                        (IncidentTimeline.incidentId eq incidentId) and
                            (IncidentTimeline.eventType eq "VIEWED") and
                            (IncidentTimeline.actorUserId eq userId)
                    }.count() > 0

            if (alreadyViewed) return@transaction false

            IncidentTimeline.insert {
                it[IncidentTimeline.incidentId] = incidentId
                it[eventType] = "VIEWED"
                it[actorUserId] = userId
                it[createdAt] = Clock.System.now()
            }
            true
        }

    fun markUnavailable(
        incidentId: Int,
        userId: Int,
    ): Boolean = escalationEngine.markUnavailable(incidentId, userId)

    private fun hasUserViewed(
        incidentId: Int,
        userId: Int,
    ): Boolean =
        IncidentTimeline
            .selectAll()
            .where {
                (IncidentTimeline.incidentId eq incidentId) and
                    (IncidentTimeline.eventType eq "VIEWED") and
                    (IncidentTimeline.actorUserId eq userId)
            }.count() > 0
}
