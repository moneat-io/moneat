// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.actions

import com.moneat.enterprise.incidents.models.IncidentActionState
import com.moneat.enterprise.incidents.models.NativeIncidentActionEvents
import com.moneat.enterprise.incidents.models.NativeIncidentActions
import com.moneat.shared.models.Users
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.shared.services.toUuidOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class IncidentActionService {
    fun list(organizationId: Int, incidentId: Int): List<IncidentAction> = transaction {
        val rows = NativeIncidentActions
            .selectAll()
            .where {
                (NativeIncidentActions.organizationId eq organizationId) and
                    (NativeIncidentActions.incidentId eq incidentId)
            }
            .orderBy(NativeIncidentActions.createdAt to SortOrder.ASC)
            .toList()
        toActions(rows)
    }

    fun get(organizationId: Int, incidentId: Int, resourceId: String): IncidentAction? {
        val parsed = resourceId.toUuidOrNull() ?: return null
        return transaction {
            NativeIncidentActions
                .selectAll()
                .where {
                    (NativeIncidentActions.organizationId eq organizationId) and
                        (NativeIncidentActions.incidentId eq incidentId) and
                        (NativeIncidentActions.resourceId eq parsed)
                }
                .singleOrNull()
                ?.let { toActions(listOf(it)).single() }
        }
    }

    fun events(organizationId: Int, incidentId: Int, resourceId: String): List<IncidentActionEvent> {
        val parsed = resourceId.toUuidOrNull() ?: return emptyList()
        return transaction {
            val actionId = NativeIncidentActions
                .selectAll()
                .where {
                    (NativeIncidentActions.organizationId eq organizationId) and
                        (NativeIncidentActions.incidentId eq incidentId) and
                        (NativeIncidentActions.resourceId eq parsed)
                }
                .singleOrNull()
                ?.get(NativeIncidentActions.id)
                ?: return@transaction emptyList()
            val rows = NativeIncidentActionEvents
                .selectAll()
                .where {
                    (NativeIncidentActionEvents.organizationId eq organizationId) and
                        (NativeIncidentActionEvents.incidentId eq incidentId) and
                    (NativeIncidentActionEvents.actionId eq actionId.value)
                }
                .orderBy(NativeIncidentActionEvents.createdAt to SortOrder.ASC)
                .toList()
            val users = userData(rows.mapNotNull { it[NativeIncidentActionEvents.actorUserId] })
            rows.map { row ->
                IncidentActionEvent(
                    id = row[NativeIncidentActionEvents.resourceId].toString(),
                    eventType = row[NativeIncidentActionEvents.eventType],
                    fromState = row[NativeIncidentActionEvents.fromState],
                    toState = row[NativeIncidentActionEvents.toState],
                    actorUserId = row[NativeIncidentActionEvents.actorUserId]?.let { users[it]?.resourceId },
                    actorName = row[NativeIncidentActionEvents.actorUserId]?.let { users[it]?.name },
                    details = row[NativeIncidentActionEvents.details],
                    createdAt = row[NativeIncidentActionEvents.createdAt].toString(),
                )
            }
        }
    }

    fun metrics(organizationId: Int, incidentId: Int): IncidentActionMetrics = transaction {
        val states = NativeIncidentActions
            .selectAll()
            .where {
                (NativeIncidentActions.organizationId eq organizationId) and
                    (NativeIncidentActions.incidentId eq incidentId)
            }
            .map { it[NativeIncidentActions.state] }
        IncidentActionMetrics(
            total = states.size,
            open = states.count { it == IncidentActionState.OPEN.wire },
            claimed = states.count { it == IncidentActionState.CLAIMED.wire },
            completed = states.count { it == IncidentActionState.COMPLETED.wire },
            cancelled = states.count { it == IncidentActionState.CANCELLED.wire },
            followUp = states.count { it == IncidentActionState.FOLLOW_UP.wire },
        )
    }

    fun internalId(organizationId: Int, incidentId: Int, resourceId: String): Int? {
        val parsed = resourceId.toUuidOrNull() ?: return null
        return transaction {
            NativeIncidentActions
                .selectAll()
                .where {
                    (NativeIncidentActions.organizationId eq organizationId) and
                        (NativeIncidentActions.incidentId eq incidentId) and
                        (NativeIncidentActions.resourceId eq parsed)
                }
                .singleOrNull()
                ?.get(NativeIncidentActions.id)
                ?.value
        }
    }

    private fun toActions(rows: List<ResultRow>): List<IncidentAction> {
        val incidentIds = rows.map { it[NativeIncidentActions.incidentId] }.distinct()
        val incidentResourceIds = if (incidentIds.isEmpty()) {
            emptyMap()
        } else {
            OnCallIncidents
                .selectAll()
                .where { OnCallIncidents.id inList incidentIds }
                .associate { it[OnCallIncidents.id].value to it[OnCallIncidents.resourceId].toString() }
        }
        val users = userData(
            rows.flatMap { row ->
                listOfNotNull(row[NativeIncidentActions.assigneeUserId], row[NativeIncidentActions.createdBy])
            },
        )
        return rows.map { row ->
            IncidentAction(
                id = row[NativeIncidentActions.resourceId].toString(),
                incidentId = checkNotNull(incidentResourceIds[row[NativeIncidentActions.incidentId]]) {
                    "Incident resource ID is missing"
                },
                description = row[NativeIncidentActions.description],
                assigneeUserId = row[NativeIncidentActions.assigneeUserId]?.let { users[it]?.resourceId },
                assigneeName = row[NativeIncidentActions.assigneeUserId]?.let { users[it]?.name },
                state = row[NativeIncidentActions.state],
                source = row[NativeIncidentActions.actionSource],
                slackChannelId = row[NativeIncidentActions.slackChannelId],
                slackMessageTs = row[NativeIncidentActions.slackMessageTs],
                createdBy = row[NativeIncidentActions.createdBy]?.let { users[it]?.resourceId },
                claimedAt = row[NativeIncidentActions.claimedAt]?.toString(),
                completedAt = row[NativeIncidentActions.completedAt]?.toString(),
                cancelledAt = row[NativeIncidentActions.cancelledAt]?.toString(),
                convertedToFollowUpAt = row[NativeIncidentActions.convertedToFollowUpAt]?.toString(),
                createdAt = row[NativeIncidentActions.createdAt].toString(),
                updatedAt = row[NativeIncidentActions.updatedAt].toString(),
            )
        }
    }

    private fun userData(userIds: Collection<Int>): Map<Int, UserData> {
        if (userIds.isEmpty()) return emptyMap()
        return Users
            .selectAll()
            .where { Users.id inList userIds.distinct() }
            .associate { row ->
                row[Users.id] to UserData(row[Users.resource_id].toString(), row[Users.name] ?: row[Users.email])
            }
    }

    private data class UserData(val resourceId: String, val name: String?)
}

@Serializable
data class IncidentAction(
    val id: String,
    val incidentId: String,
    val description: String,
    val assigneeUserId: String? = null,
    val assigneeName: String? = null,
    val state: String,
    val source: String,
    val slackChannelId: String? = null,
    val slackMessageTs: String? = null,
    val createdBy: String? = null,
    val claimedAt: String? = null,
    val completedAt: String? = null,
    val cancelledAt: String? = null,
    val convertedToFollowUpAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class IncidentActionEvent(
    val id: String,
    val eventType: String,
    val fromState: String? = null,
    val toState: String? = null,
    val actorUserId: String? = null,
    val actorName: String? = null,
    val details: Map<String, JsonElement> = emptyMap(),
    val createdAt: String,
)

@Serializable
data class IncidentActionMetrics(
    val total: Int,
    val open: Int,
    val claimed: Int,
    val completed: Int,
    val cancelled: Int,
    val followUp: Int,
)
