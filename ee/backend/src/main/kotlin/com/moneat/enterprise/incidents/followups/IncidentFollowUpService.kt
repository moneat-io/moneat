// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.followups

import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.shared.models.OrganizationTeams
import com.moneat.shared.models.Users
import com.moneat.shared.services.toUuidOrNull
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class IncidentFollowUpService {
    fun list(organizationId: Int, incidentId: Int): List<IncidentFollowUp> = transaction {
        val rows = NativeIncidentFollowUps
            .selectAll()
            .where {
                (NativeIncidentFollowUps.organizationId eq organizationId) and
                    (NativeIncidentFollowUps.incidentId eq incidentId)
            }
            .orderBy(NativeIncidentFollowUps.createdAt to SortOrder.ASC)
            .toList()
        toFollowUps(rows)
    }

    /** Returns outstanding work across the organization, ordered by priority then due date. */
    fun queue(
        organizationId: Int,
        statuses: Set<IncidentFollowUpStatus> = setOf(IncidentFollowUpStatus.OPEN, IncidentFollowUpStatus.ACCEPTED),
        priority: IncidentFollowUpPriority? = null,
        visibleIncidentIds: Set<Int>? = null,
    ): List<IncidentFollowUp> = transaction {
        if (statuses.isEmpty() || visibleIncidentIds?.isEmpty() == true) return@transaction emptyList()
        var predicate: Op<Boolean> = (NativeIncidentFollowUps.organizationId eq organizationId) and
            (NativeIncidentFollowUps.status inList statuses.map(IncidentFollowUpStatus::wire))
        priority?.let { selectedPriority ->
            predicate = predicate and (NativeIncidentFollowUps.priority eq selectedPriority.wire)
        }
        visibleIncidentIds?.let { incidentIds ->
            predicate = predicate and (NativeIncidentFollowUps.incidentId inList incidentIds)
        }
        val rows = NativeIncidentFollowUps
            .selectAll()
            .where { predicate }
            .toList()
            .sortedWith(
                compareBy<ResultRow> { row ->
                    IncidentFollowUpPriority.parse(row[NativeIncidentFollowUps.priority])?.rank ?: Int.MAX_VALUE
                }.thenComparator { left, right ->
                    compareDueAt(left[NativeIncidentFollowUps.dueAt], right[NativeIncidentFollowUps.dueAt])
                }.thenBy { row -> row[NativeIncidentFollowUps.createdAt] },
            )
        toFollowUps(rows)
    }

    fun get(organizationId: Int, incidentId: Int, resourceId: String): IncidentFollowUp? {
        val parsed = resourceId.toUuidOrNull() ?: return null
        return transaction {
            NativeIncidentFollowUps
                .selectAll()
                .where {
                    (NativeIncidentFollowUps.organizationId eq organizationId) and
                        (NativeIncidentFollowUps.incidentId eq incidentId) and
                        (NativeIncidentFollowUps.resourceId eq parsed)
                }
                .singleOrNull()
                ?.let { toFollowUps(listOf(it)).single() }
        }
    }

    private fun toFollowUps(rows: List<ResultRow>): List<IncidentFollowUp> {
        if (rows.isEmpty()) return emptyList()
        val incidentIds = rows.map { it[NativeIncidentFollowUps.incidentId] }.distinct()
        val incidentResourceIds = OnCallIncidents
            .selectAll()
            .where { OnCallIncidents.id inList incidentIds }
            .associate { it[OnCallIncidents.id].value to it[OnCallIncidents.resourceId].toString() }
        val userIds = rows.flatMap { row ->
            listOfNotNull(
                row[NativeIncidentFollowUps.ownerUserId],
                row[NativeIncidentFollowUps.acceptedBy],
                row[NativeIncidentFollowUps.completedBy],
                row[NativeIncidentFollowUps.createdBy],
            )
        }.distinct()
        val users: Map<Int, FollowUpUser> = if (userIds.isEmpty()) {
            emptyMap()
        } else {
            Users
                .selectAll()
                .where { Users.id inList userIds }
                .associate { row ->
                    row[Users.id] to FollowUpUser(
                        row[Users.resource_id].toString(),
                        row[Users.name] ?: row[Users.email],
                    )
                }
        }
        val teamIds = rows.mapNotNull { it[NativeIncidentFollowUps.ownerTeamId] }.distinct()
        val teams: Map<Int, FollowUpTeam> = if (teamIds.isEmpty()) {
            emptyMap()
        } else {
            OrganizationTeams
                .selectAll()
                .where { OrganizationTeams.id inList teamIds }
                .associate { row ->
                    row[OrganizationTeams.id].value to FollowUpTeam(
                        row[OrganizationTeams.resourceId].toString(),
                        row[OrganizationTeams.name],
                    )
                }
        }
        return rows.map { row ->
            val ownerUser = row[NativeIncidentFollowUps.ownerUserId]?.let(users::get)
            val ownerTeam = row[NativeIncidentFollowUps.ownerTeamId]?.let(teams::get)
            IncidentFollowUp(
                id = row[NativeIncidentFollowUps.resourceId].toString(),
                incidentId = checkNotNull(incidentResourceIds[row[NativeIncidentFollowUps.incidentId]]),
                title = row[NativeIncidentFollowUps.title],
                description = row[NativeIncidentFollowUps.description],
                ownerUserId = ownerUser?.resourceId,
                ownerUserName = ownerUser?.name,
                ownerTeamId = ownerTeam?.resourceId,
                ownerTeamName = ownerTeam?.name,
                priority = row[NativeIncidentFollowUps.priority],
                labels = row[NativeIncidentFollowUps.labels],
                dueAt = row[NativeIncidentFollowUps.dueAt]?.toString(),
                slaMinutes = row[NativeIncidentFollowUps.slaMinutes],
                reminderMinutes = row[NativeIncidentFollowUps.reminderMinutes],
                nextReminderAt = row[NativeIncidentFollowUps.nextReminderAt]?.toString(),
                escalationLevel = row[NativeIncidentFollowUps.escalationLevel],
                status = row[NativeIncidentFollowUps.status],
                acceptedBy = row[NativeIncidentFollowUps.acceptedBy]?.let { users[it]?.resourceId },
                acceptedAt = row[NativeIncidentFollowUps.acceptedAt]?.toString(),
                completedBy = row[NativeIncidentFollowUps.completedBy]?.let { users[it]?.resourceId },
                completedAt = row[NativeIncidentFollowUps.completedAt]?.toString(),
                createdBy = row[NativeIncidentFollowUps.createdBy]?.let { users[it]?.resourceId },
                source = row[NativeIncidentFollowUps.sourceType],
                slackChannelId = row[NativeIncidentFollowUps.slackChannelId],
                slackMessageTs = row[NativeIncidentFollowUps.slackMessageTs],
                createdAt = row[NativeIncidentFollowUps.createdAt].toString(),
                updatedAt = row[NativeIncidentFollowUps.updatedAt].toString(),
            )
        }
    }

    private fun compareDueAt(left: kotlin.time.Instant?, right: kotlin.time.Instant?): Int = when {
        left == null && right == null -> 0
        left == null -> 1
        right == null -> -1
        else -> left.compareTo(right)
    }

    private data class FollowUpUser(val resourceId: String, val name: String?)

    private data class FollowUpTeam(val resourceId: String, val name: String)
}

@Serializable
data class IncidentFollowUp(
    val id: String,
    val incidentId: String,
    val title: String,
    val description: String,
    val ownerUserId: String? = null,
    val ownerUserName: String? = null,
    val ownerTeamId: String? = null,
    val ownerTeamName: String? = null,
    val priority: String,
    val labels: List<String> = emptyList(),
    val dueAt: String? = null,
    val slaMinutes: Int? = null,
    val reminderMinutes: Int? = null,
    val nextReminderAt: String? = null,
    val escalationLevel: Int = 0,
    val status: String,
    val acceptedBy: String? = null,
    val acceptedAt: String? = null,
    val completedBy: String? = null,
    val completedAt: String? = null,
    val createdBy: String? = null,
    val source: String,
    val slackChannelId: String? = null,
    val slackMessageTs: String? = null,
    val createdAt: String,
    val updatedAt: String,
)
