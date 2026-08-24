// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

import com.moneat.enterprise.alertroutes.services.AlertRouteOutcomeReader
import com.moneat.enterprise.oncall.escalationPolicyResourceIds
import com.moneat.enterprise.oncall.incidentResourceIds
import com.moneat.enterprise.oncall.organizationResourceId
import com.moneat.enterprise.oncall.requireValue
import com.moneat.enterprise.oncall.userResourceIds
import com.moneat.enterprise.oncall.userResourceIdOrNull
import com.moneat.enterprise.oncall.models.OnCallAlert
import com.moneat.enterprise.oncall.models.OnCallAlertTimeline
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.enterprise.oncall.models.OnCallTimelineEvent
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.Users
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock

class OnCallAlertService(
    private val escalationEngine: EscalationEngine,
) {
    private data class AlertResponseResourceIds(
        val organizationResourceId: String,
        val incidents: Map<Int, String>,
        val policies: Map<Int, String>,
        val users: Map<Int, String>,
    ) {
        fun incident(id: Int?): String? =
            id?.let { incidents.requireValue(it, "on-call incident") }

        fun policy(id: Int?): String? =
            id?.let { policies.requireValue(it, "escalation policy") }

        fun user(id: Int?): String? =
            id?.let { users.requireValue(it, "user") }
    }

    private data class TimelineResponseResourceIds(
        val users: Map<Int, String>,
    ) {
        fun actor(id: Int?): String? =
            id?.let { users.requireValue(it, "user") }
    }

    fun getAlert(
        alertId: Int,
        currentUserId: Int? = null,
    ): OnCallAlert? =
        transaction {
            val row =
                OnCallAlerts
                    .join(EscalationPolicies, JoinType.LEFT, OnCallAlerts.escalationPolicyId, EscalationPolicies.id)
                    .join(Users, JoinType.LEFT, OnCallAlerts.acknowledgedBy, Users.id)
                    .selectAll()
                    .where { OnCallAlerts.id eq alertId }
                    .singleOrNull() ?: return@transaction null

            val escalationTimes = escalationEngine.getNextEscalationTimes()
            val viewed =
                if (currentUserId != null) {
                    hasUserViewed(alertId, currentUserId)
                } else {
                    false
                }
            val resourceIds = responseResourceIdsForAlerts(listOf(row))

            OnCallAlert(
                id = row[OnCallAlerts.resourceId].toString(),
                organizationResourceId = resourceIds.organizationResourceId,
                declaredIncidentResourceId = resourceIds.incident(row[OnCallAlerts.declaredIncidentId]),
                escalationPolicyResourceId = resourceIds.policy(row[OnCallAlerts.escalationPolicyId]),
                escalationPolicyName = row.getOrNull(EscalationPolicies.name),
                title = row[OnCallAlerts.title],
                description = row[OnCallAlerts.description],
                priority = row[OnCallAlerts.priority],
                status = row[OnCallAlerts.status],
                alertSource = row[OnCallAlerts.alertSource],
                deduplicationKey = row[OnCallAlerts.deduplicationKey],
                currentStep = row[OnCallAlerts.currentStep],
                repeatIteration = row[OnCallAlerts.repeatIteration],
                triggeredAt = row[OnCallAlerts.triggeredAt].toString(),
                acknowledgedAt = row[OnCallAlerts.acknowledgedAt]?.toString(),
                acknowledgedByResourceId = resourceIds.user(row[OnCallAlerts.acknowledgedBy]),
                acknowledgedByName = row.getOrNull(Users.name),
                resolvedAt = row[OnCallAlerts.resolvedAt]?.toString(),
                resolvedByResourceId = resourceIds.user(row[OnCallAlerts.resolvedBy]),
                metadata = row[OnCallAlerts.metadata],
                nextEscalationAt = escalationTimes[row[OnCallAlerts.id].value],
                viewedByCurrentUser = viewed,
                createdAt = row[OnCallAlerts.createdAt].toString(),
                updatedAt = row[OnCallAlerts.updatedAt].toString(),
                routeOutcome = AlertRouteOutcomeReader.findForOnCallAlert(
                    row[OnCallAlerts.organizationId],
                    row[OnCallAlerts.id].value,
                ),
                internalId = row[OnCallAlerts.id].value,
                organizationId = row[OnCallAlerts.organizationId],
                declaredIncidentId = row[OnCallAlerts.declaredIncidentId],
                escalationPolicyId = row[OnCallAlerts.escalationPolicyId],
                acknowledgedBy = row[OnCallAlerts.acknowledgedBy],
                resolvedBy = row[OnCallAlerts.resolvedBy],
            )
        }

    fun listAlerts(
        organizationId: Int,
        status: String? = null,
        statuses: List<String>? = null,
        priority: String? = null,
        limit: Int = 50,
        offset: Int = 0,
        currentUserId: Int? = null,
    ): List<OnCallAlert> =
        transaction {
            val escalationTimes = escalationEngine.getNextEscalationTimes()

            val viewedAlertIds =
                if (currentUserId != null) {
                    OnCallAlertTimeline
                        .selectAll()
                        .where {
                            (OnCallAlertTimeline.eventType eq "VIEWED") and
                                (OnCallAlertTimeline.actorUserId eq currentUserId)
                        }
                        .map { it[OnCallAlertTimeline.alertId] }
                        .toSet()
                } else {
                    emptySet()
                }

            var query =
                OnCallAlerts
                    .join(EscalationPolicies, JoinType.LEFT, OnCallAlerts.escalationPolicyId, EscalationPolicies.id)
                    .selectAll()
                    .where { OnCallAlerts.organizationId eq organizationId }

            val statusFilters = statuses?.ifEmpty { null } ?: status?.let(::listOf)
            if (!statusFilters.isNullOrEmpty()) {
                val singleStatus = statusFilters.singleOrNull()
                query =
                    if (singleStatus != null) {
                        query.andWhere { OnCallAlerts.status eq singleStatus }
                    } else {
                        query.andWhere { OnCallAlerts.status inList statusFilters }
                    }
            }

            if (priority != null) {
                query = query.andWhere { OnCallAlerts.priority eq priority }
            }

            val rows = query
                .orderBy(OnCallAlerts.triggeredAt to SortOrder.DESC)
                .limit(limit)
                .offset(offset.toLong())
                .toList()
            if (rows.isEmpty()) return@transaction emptyList()

            val resourceIds = responseResourceIdsForAlerts(rows, organizationId)
            rows.map { row ->
                val alertId = row[OnCallAlerts.id].value
                OnCallAlert(
                    id = row[OnCallAlerts.resourceId].toString(),
                    organizationResourceId = resourceIds.organizationResourceId,
                    declaredIncidentResourceId = resourceIds.incident(row[OnCallAlerts.declaredIncidentId]),
                    escalationPolicyResourceId = resourceIds.policy(row[OnCallAlerts.escalationPolicyId]),
                    escalationPolicyName = row.getOrNull(EscalationPolicies.name),
                    title = row[OnCallAlerts.title],
                    description = row[OnCallAlerts.description],
                    priority = row[OnCallAlerts.priority],
                    status = row[OnCallAlerts.status],
                    alertSource = row[OnCallAlerts.alertSource],
                    deduplicationKey = row[OnCallAlerts.deduplicationKey],
                    currentStep = row[OnCallAlerts.currentStep],
                    repeatIteration = row[OnCallAlerts.repeatIteration],
                    triggeredAt = row[OnCallAlerts.triggeredAt].toString(),
                    acknowledgedAt = row[OnCallAlerts.acknowledgedAt]?.toString(),
                    acknowledgedByResourceId = resourceIds.user(row[OnCallAlerts.acknowledgedBy]),
                    resolvedAt = row[OnCallAlerts.resolvedAt]?.toString(),
                    resolvedByResourceId = resourceIds.user(row[OnCallAlerts.resolvedBy]),
                    metadata = row[OnCallAlerts.metadata],
                    nextEscalationAt = escalationTimes[alertId],
                    viewedByCurrentUser = alertId in viewedAlertIds,
                    createdAt = row[OnCallAlerts.createdAt].toString(),
                    updatedAt = row[OnCallAlerts.updatedAt].toString(),
                    internalId = alertId,
                    organizationId = row[OnCallAlerts.organizationId],
                    declaredIncidentId = row[OnCallAlerts.declaredIncidentId],
                    escalationPolicyId = row[OnCallAlerts.escalationPolicyId],
                    acknowledgedBy = row[OnCallAlerts.acknowledgedBy],
                    resolvedBy = row[OnCallAlerts.resolvedBy],
                )
            }
        }

    fun getTimeline(alertId: Int): List<OnCallTimelineEvent> =
        transaction {
            val rows = OnCallAlertTimeline
                .innerJoin(OnCallAlerts)
                .join(Users, JoinType.LEFT, OnCallAlertTimeline.actorUserId, Users.id)
                .selectAll()
                .where { OnCallAlertTimeline.alertId eq alertId }
                .orderBy(OnCallAlertTimeline.createdAt to SortOrder.ASC)
                .toList()
            if (rows.isEmpty()) return@transaction emptyList()

            val resourceIds = timelineResponseResourceIds(rows)
            rows.map { row ->
                OnCallTimelineEvent(
                    id = row[OnCallAlertTimeline.resourceId].toString(),
                    targetResourceId = row[OnCallAlerts.resourceId].toString(),
                    eventType = row[OnCallAlertTimeline.eventType],
                    actorUserResourceId = resourceIds.actor(row[OnCallAlertTimeline.actorUserId]),
                    actorName = row.getOrNull(Users.name),
                    details = row[OnCallAlertTimeline.details],
                    createdAt = row[OnCallAlertTimeline.createdAt].toString(),
                    internalId = row[OnCallAlertTimeline.id].value,
                    targetId = row[OnCallAlertTimeline.alertId],
                    actorUserId = row[OnCallAlertTimeline.actorUserId],
                )
            }
        }

    fun acknowledge(
        alertId: Int,
        userId: Int,
    ): Boolean = escalationEngine.acknowledgeAlert(alertId, userId)

    fun resolve(
        alertId: Int,
        userId: Int,
    ): Boolean = escalationEngine.resolveAlert(alertId, userId)

    fun reassign(
        alertId: Int,
        toUserId: Int,
        byUserId: Int,
    ): Boolean = escalationEngine.reassignAlert(alertId, toUserId, byUserId)

    fun addNote(
        alertId: Int,
        userId: Int,
        noteText: String,
    ): OnCallTimelineEvent =
        transaction {
            val now = Clock.System.now()

            val eventId =
                OnCallAlertTimeline
                    .insertAndGetId {
                        it[OnCallAlertTimeline.alertId] = alertId
                        it[eventType] = "NOTE_ADDED"
                        it[actorUserId] = userId
                        it[OnCallAlertTimeline.details] = mapOf("note" to JsonPrimitive(noteText))
                        it[createdAt] = now
                    }.value

            val row =
                OnCallAlertTimeline
                    .innerJoin(OnCallAlerts)
                    .join(Users, JoinType.LEFT, OnCallAlertTimeline.actorUserId, Users.id)
                    .selectAll()
                    .where { OnCallAlertTimeline.id eq eventId }
                    .single()

            OnCallTimelineEvent(
                id = row[OnCallAlertTimeline.resourceId].toString(),
                targetResourceId = row[OnCallAlerts.resourceId].toString(),
                eventType = row[OnCallAlertTimeline.eventType],
                actorUserResourceId = userResourceIdOrNull(row[OnCallAlertTimeline.actorUserId]),
                actorName = row.getOrNull(Users.name),
                details = row[OnCallAlertTimeline.details],
                createdAt = row[OnCallAlertTimeline.createdAt].toString(),
                internalId = row[OnCallAlertTimeline.id].value,
                targetId = row[OnCallAlertTimeline.alertId],
                actorUserId = row[OnCallAlertTimeline.actorUserId],
            )
        }

    fun viewAlert(
        alertId: Int,
        userId: Int,
    ): Boolean =
        transaction {
            val alreadyViewed =
                OnCallAlertTimeline
                    .selectAll()
                    .where {
                        (OnCallAlertTimeline.alertId eq alertId) and
                            (OnCallAlertTimeline.eventType eq "VIEWED") and
                            (OnCallAlertTimeline.actorUserId eq userId)
                    }.count() > 0

            if (alreadyViewed) return@transaction false

            OnCallAlertTimeline.insert {
                it[OnCallAlertTimeline.alertId] = alertId
                it[eventType] = "VIEWED"
                it[actorUserId] = userId
                it[createdAt] = Clock.System.now()
            }
            true
        }

    fun markUnavailable(
        alertId: Int,
        userId: Int,
    ): Boolean = escalationEngine.markUnavailable(alertId, userId)

    private fun hasUserViewed(
        alertId: Int,
        userId: Int,
    ): Boolean =
        OnCallAlertTimeline
            .selectAll()
            .where {
                (OnCallAlertTimeline.alertId eq alertId) and
                    (OnCallAlertTimeline.eventType eq "VIEWED") and
                    (OnCallAlertTimeline.actorUserId eq userId)
            }.count() > 0

    private fun responseResourceIdsForAlerts(
        rows: List<ResultRow>,
        organizationId: Int = rows.first()[OnCallAlerts.organizationId],
    ): AlertResponseResourceIds =
        AlertResponseResourceIds(
            organizationResourceId = organizationResourceId(organizationId),
            incidents = incidentResourceIds(rows.mapNotNull { row -> row[OnCallAlerts.declaredIncidentId] }),
            policies = escalationPolicyResourceIds(rows.mapNotNull { row -> row[OnCallAlerts.escalationPolicyId] }),
            users = userResourceIds(
                rows.flatMap { row ->
                    listOfNotNull(row[OnCallAlerts.acknowledgedBy], row[OnCallAlerts.resolvedBy])
                }
            ),
        )

    private fun timelineResponseResourceIds(rows: List<ResultRow>): TimelineResponseResourceIds =
        TimelineResponseResourceIds(
            users = userResourceIds(rows.mapNotNull { row -> row[OnCallAlertTimeline.actorUserId] }),
        )
}
