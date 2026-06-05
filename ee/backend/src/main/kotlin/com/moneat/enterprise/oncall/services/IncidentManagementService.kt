// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

import com.moneat.enterprise.oncall.models.OnCallAlert
import com.moneat.enterprise.oncall.models.OnCallAlertTimeline
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.enterprise.oncall.models.OnCallTimelineEvent
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.Users
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.JoinType
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

            OnCallAlert(
                id = row[OnCallAlerts.id].value,
                organizationId = row[OnCallAlerts.organizationId],
                declaredIncidentId = row[OnCallAlerts.declaredIncidentId],
                escalationPolicyId = row[OnCallAlerts.escalationPolicyId],
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
                acknowledgedBy = row[OnCallAlerts.acknowledgedBy],
                acknowledgedByName = row.getOrNull(Users.name),
                resolvedAt = row[OnCallAlerts.resolvedAt]?.toString(),
                resolvedBy = row[OnCallAlerts.resolvedBy],
                metadata = row[OnCallAlerts.metadata],
                nextEscalationAt = escalationTimes[row[OnCallAlerts.id].value],
                viewedByCurrentUser = viewed,
                createdAt = row[OnCallAlerts.createdAt].toString(),
                updatedAt = row[OnCallAlerts.updatedAt].toString(),
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

            query
                .orderBy(OnCallAlerts.triggeredAt to SortOrder.DESC)
                .limit(limit)
                .offset(offset.toLong())
                .map { row ->
                    val alertId = row[OnCallAlerts.id].value
                    OnCallAlert(
                        id = alertId,
                        organizationId = row[OnCallAlerts.organizationId],
                        declaredIncidentId = row[OnCallAlerts.declaredIncidentId],
                        escalationPolicyId = row[OnCallAlerts.escalationPolicyId],
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
                        acknowledgedBy = row[OnCallAlerts.acknowledgedBy],
                        resolvedAt = row[OnCallAlerts.resolvedAt]?.toString(),
                        resolvedBy = row[OnCallAlerts.resolvedBy],
                        metadata = row[OnCallAlerts.metadata],
                        nextEscalationAt = escalationTimes[alertId],
                        viewedByCurrentUser = alertId in viewedAlertIds,
                        createdAt = row[OnCallAlerts.createdAt].toString(),
                        updatedAt = row[OnCallAlerts.updatedAt].toString(),
                    )
                }
        }

    fun getTimeline(alertId: Int): List<OnCallTimelineEvent> =
        transaction {
            OnCallAlertTimeline
                .join(Users, JoinType.LEFT, OnCallAlertTimeline.actorUserId, Users.id)
                .selectAll()
                .where { OnCallAlertTimeline.alertId eq alertId }
                .orderBy(OnCallAlertTimeline.createdAt to SortOrder.ASC)
                .map { row ->
                    OnCallTimelineEvent(
                        id = row[OnCallAlertTimeline.id].value,
                        targetId = row[OnCallAlertTimeline.alertId],
                        eventType = row[OnCallAlertTimeline.eventType],
                        actorUserId = row[OnCallAlertTimeline.actorUserId],
                        actorName = row.getOrNull(Users.name),
                        details = row[OnCallAlertTimeline.details],
                        createdAt = row[OnCallAlertTimeline.createdAt].toString(),
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
                    .join(Users, JoinType.LEFT, OnCallAlertTimeline.actorUserId, Users.id)
                    .selectAll()
                    .where { OnCallAlertTimeline.id eq eventId }
                    .single()

            OnCallTimelineEvent(
                id = row[OnCallAlertTimeline.id].value,
                targetId = row[OnCallAlertTimeline.alertId],
                eventType = row[OnCallAlertTimeline.eventType],
                actorUserId = row[OnCallAlertTimeline.actorUserId],
                actorName = row.getOrNull(Users.name),
                details = row[OnCallAlertTimeline.details],
                createdAt = row[OnCallAlertTimeline.createdAt].toString(),
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
}
