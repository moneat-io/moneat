// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

import com.moneat.alerts.models.IncidentSeverity
import com.moneat.enterprise.incidents.commands.AddIncidentTimelineEventCommand
import com.moneat.enterprise.incidents.commands.DeclareIncidentCommand
import com.moneat.enterprise.incidents.commands.IncidentCommandActor
import com.moneat.enterprise.incidents.commands.IncidentCommand
import com.moneat.enterprise.incidents.commands.IncidentCommandResult
import com.moneat.enterprise.incidents.commands.IncidentCommandNotFoundException
import com.moneat.enterprise.incidents.commands.IncidentCommandService
import com.moneat.enterprise.incidents.commands.LinkOnCallAlertCommand
import com.moneat.enterprise.incidents.commands.LinkIncidentSourceCommand
import com.moneat.enterprise.incidents.commands.ResolveIncidentCommand
import com.moneat.enterprise.incidents.commands.UnlinkIncidentSourceCommand
import com.moneat.enterprise.incidents.models.IncidentSourceLink
import com.moneat.enterprise.incidents.models.IncidentSourceType
import com.moneat.enterprise.incidents.models.NativeIncidentSourceLinks
import com.moneat.enterprise.oncall.escalationPolicyResourceIds
import com.moneat.enterprise.oncall.incidentResourceIds
import com.moneat.enterprise.oncall.organizationResourceId
import com.moneat.enterprise.oncall.requireValue
import com.moneat.enterprise.oncall.models.OnCallAlert
import com.moneat.enterprise.oncall.models.OnCallAlertTimeline
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.enterprise.oncall.models.OnCallIncident
import com.moneat.enterprise.oncall.models.OnCallIncidentAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidentTimeline
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.enterprise.oncall.models.OnCallTimelineEvent
import com.moneat.shared.models.Users
import com.moneat.shared.services.toUuidOrNull
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class OnCallIncidentService(
    private val commandService: IncidentCommandService = IncidentCommandService(),
) {
    fun executeIncidentCommand(command: IncidentCommand): IncidentCommandResult = commandService.execute(command)

    suspend fun declareIncident(command: DeclareIncidentCommand): OnCallIncident {
        val result = commandService.execute(command)
        return checkNotNull(getIncident(result.incidentId))
    }

    fun addAlertToIncident(command: LinkOnCallAlertCommand) {
        commandService.execute(command)
    }

    fun addSourceToIncident(command: LinkIncidentSourceCommand) {
        commandService.execute(command)
    }

    fun removeSourceFromIncident(command: UnlinkIncidentSourceCommand) {
        commandService.execute(command)
    }

    fun getIncidentSources(
        organizationId: Int,
        incidentId: Int,
    ): List<IncidentSourceLink> =
        transaction {
            NativeIncidentSourceLinks
                .selectAll()
                .where {
                    (NativeIncidentSourceLinks.organizationId eq organizationId) and
                        (NativeIncidentSourceLinks.incidentId eq incidentId)
                }.orderBy(NativeIncidentSourceLinks.createdAt to SortOrder.ASC)
                .map(::toIncidentSourceLink)
        }

    fun getIncidentSourceIdentity(
        organizationId: Int,
        incidentId: Int,
        sourceResourceId: String,
    ): IncidentSourceIdentity? {
        val resourceId = sourceResourceId.toUuidOrNull() ?: return null
        return transaction {
            NativeIncidentSourceLinks
                .selectAll()
                .where {
                    (NativeIncidentSourceLinks.organizationId eq organizationId) and
                        (NativeIncidentSourceLinks.incidentId eq incidentId) and
                        (NativeIncidentSourceLinks.resourceId eq resourceId)
                }.singleOrNull()
                ?.let {
                    IncidentSourceIdentity(
                        sourceType = IncidentSourceType.valueOf(it[NativeIncidentSourceLinks.sourceType]),
                        sourceKey = it[NativeIncidentSourceLinks.sourceKey],
                    )
                }
        }
    }

    suspend fun resolveIncident(
        incidentId: Int,
        userId: Int,
        resolutionNote: String? = null,
        commandKey: String = Uuid.random().toString(),
        origin: String = "SERVICE",
    ): OnCallIncident? {
        val organizationId = getIncidentOrganizationId(incidentId) ?: return null
        commandService.execute(
            ResolveIncidentCommand(
                commandKey = commandKey,
                actor = IncidentCommandActor(organizationId, userId, origin),
                incidentId = incidentId,
                note = resolutionNote,
            ),
        )
        return getIncident(incidentId)
    }

    fun getIncident(incidentId: Int): OnCallIncident? =
        transaction {
            val row =
                OnCallIncidents
                    .selectAll()
                    .where { OnCallIncidents.id eq incidentId }
                    .singleOrNull() ?: return@transaction null

            val alerts =
                OnCallAlerts
                    .innerJoin(OnCallIncidentAlerts)
                    .selectAll()
                    .where { OnCallIncidentAlerts.incidentId eq incidentId }
                    .toList()
            val resourceIds = responseResourceIdsForIncidents(listOf(row), alerts)

            toOnCallIncident(
                row = row,
                alerts = alerts.map { alertRow -> toOnCallAlert(alertRow, resourceIds) },
                resourceIds = resourceIds,
            )
        }

    fun getIncidents(
        organizationId: Int,
        status: String? = null,
        severity: String? = null,
    ): List<OnCallIncident> =
        transaction {
            var query =
                OnCallIncidents
                    .selectAll()
                    .where { OnCallIncidents.organizationId eq organizationId }

            if (status != null) {
                val normalizedStatus = status.uppercase()
                val canonicalStatus = if (normalizedStatus == "OPEN") "ACTIVE" else normalizedStatus
                query = query.andWhere { OnCallIncidents.status eq canonicalStatus }
            }

            val normalizedSeverity = severity?.let {
                requireNotNull(IncidentSeverity.wireValue(it)) { "Invalid incident severity: $it" }
            }

            if (normalizedSeverity != null) {
                query = query.andWhere { OnCallIncidents.severity eq normalizedSeverity }
            }

            val incidentRows = query
                .orderBy(OnCallIncidents.createdAt to SortOrder.DESC)
                .toList()
            if (incidentRows.isEmpty()) return@transaction emptyList()

            val incidentIds = incidentRows.map { row -> row[OnCallIncidents.id].value }
            val alertRows =
                OnCallAlerts
                    .innerJoin(OnCallIncidentAlerts)
                    .selectAll()
                    .where { OnCallIncidentAlerts.incidentId inList incidentIds }
                    .toList()
            val alertsByIncidentId = alertRows.groupBy { row -> row[OnCallIncidentAlerts.incidentId] }
            val resourceIds = responseResourceIdsForIncidents(incidentRows, alertRows)

            incidentRows.map { row ->
                val incidentAlerts =
                    alertsByIncidentId[row[OnCallIncidents.id].value]
                        ?.map { alertRow -> toOnCallAlert(alertRow, resourceIds) }
                        ?: emptyList()
                toOnCallIncident(
                    row = row,
                    alerts = incidentAlerts,
                    resourceIds = resourceIds,
                )
            }
        }

    fun isIncidentInOrganization(
        incidentId: Int,
        organizationId: Int,
    ): Boolean =
        transaction {
            OnCallIncidents
                .selectAll()
                .where { (OnCallIncidents.id eq incidentId) and (OnCallIncidents.organizationId eq organizationId) }
                .limit(1)
                .singleOrNull() != null
        }

    fun getIncidentOrganizationId(incidentId: Int): Int? =
        transaction {
            OnCallIncidents
                .selectAll()
                .where { OnCallIncidents.id eq incidentId }
                .singleOrNull()
                ?.get(OnCallIncidents.organizationId)
        }

    fun addNote(
        incidentId: Int,
        userId: Int,
        note: String,
        commandKey: String = Uuid.random().toString(),
        origin: String = "SERVICE",
    ) {
        val organizationId =
            getIncidentOrganizationId(incidentId)
                ?: throw IncidentCommandNotFoundException("Native incident not found")
        commandService.execute(
            AddIncidentTimelineEventCommand(
                commandKey = commandKey,
                actor = IncidentCommandActor(organizationId, userId, origin),
                incidentId = incidentId,
                eventType = "NOTE_ADDED",
                details = mapOf("note" to JsonPrimitive(note)),
            ),
        )
    }

    fun getIncidentTimeline(incidentId: Int): List<OnCallTimelineEvent> =
        transaction {
            val events = mutableListOf<OnCallTimelineEvent>()
            val incidentRow =
                OnCallIncidents
                    .selectAll()
                    .where { OnCallIncidents.id eq incidentId }
                    .singleOrNull() ?: return@transaction emptyList()
            val incidentTargetResourceId = incidentRow[OnCallIncidents.resourceId].toString()

            val incidentEvents =
                OnCallIncidentTimeline
                    .selectAll()
                    .where { OnCallIncidentTimeline.incidentId eq incidentId }
                    .toList()

            val alertIds =
                OnCallIncidentAlerts
                    .selectAll()
                    .where { OnCallIncidentAlerts.incidentId eq incidentId }
                    .map { it[OnCallIncidentAlerts.alertId] }

            val alertRows =
                if (alertIds.isEmpty()) {
                    emptyList()
                } else {
                    OnCallAlerts
                        .selectAll()
                        .where { OnCallAlerts.id inList alertIds }
                        .toList()
                }
            val alertTitles = alertRows.associate { row -> row[OnCallAlerts.id].value to row[OnCallAlerts.title] }
            val alertResourceIds =
                alertRows.associate { row -> row[OnCallAlerts.id].value to row[OnCallAlerts.resourceId].toString() }

            val alertEvents =
                if (alertIds.isEmpty()) {
                    emptyList()
                } else {
                    OnCallAlertTimeline
                        .selectAll()
                        .where { OnCallAlertTimeline.alertId inList alertIds }
                        .toList()
                }
            val actorUserIds = (
                incidentEvents.mapNotNull { row -> row[OnCallIncidentTimeline.actorUserId] } +
                    alertEvents.mapNotNull { row -> row[OnCallAlertTimeline.actorUserId] }
                ).distinct()
            val users = userResponseData(actorUserIds)

            events.addAll(
                incidentEvents.map { row ->
                    val actorId = row[OnCallIncidentTimeline.actorUserId]
                    OnCallTimelineEvent(
                        id = row[OnCallIncidentTimeline.resourceId].toString(),
                        targetResourceId = incidentTargetResourceId,
                        eventType = row[OnCallIncidentTimeline.eventType],
                        actorUserResourceId = actorResourceId(actorId, users),
                        actorName = actorName(actorId, users),
                        details = row[OnCallIncidentTimeline.details],
                        createdAt = row[OnCallIncidentTimeline.createdAt].toString(),
                        source = "incident",
                        alertResourceId = null,
                        alertTitle = null,
                        internalId = row[OnCallIncidentTimeline.id].value,
                        targetId = incidentId,
                        actorUserId = actorId,
                        alertId = null,
                    )
                }
            )

            events.addAll(
                alertEvents.map { row ->
                    val alertId = row[OnCallAlertTimeline.alertId]
                    val alertResourceId = alertResourceIds.requireValue(alertId, "on-call alert")
                    val actorId = row[OnCallAlertTimeline.actorUserId]
                    OnCallTimelineEvent(
                        id = row[OnCallAlertTimeline.resourceId].toString(),
                        targetResourceId = alertResourceId,
                        eventType = row[OnCallAlertTimeline.eventType],
                        actorUserResourceId = actorResourceId(actorId, users),
                        actorName = actorName(actorId, users),
                        details = row[OnCallAlertTimeline.details],
                        createdAt = row[OnCallAlertTimeline.createdAt].toString(),
                        source = "alert",
                        alertResourceId = alertResourceId,
                        alertTitle = alertTitles[alertId],
                        internalId = row[OnCallAlertTimeline.id].value,
                        targetId = alertId,
                        actorUserId = actorId,
                        alertId = alertId,
                    )
                }
            )

            events.sortedBy { it.createdAt }
        }

    private fun toOnCallIncident(
        row: ResultRow,
        alerts: List<OnCallAlert>,
        resourceIds: IncidentResponseResourceIds,
    ): OnCallIncident {
        val declaredById = row[OnCallIncidents.declaredBy]
        val resolvedById = row[OnCallIncidents.resolvedBy]

        return OnCallIncident(
            id = row[OnCallIncidents.resourceId].toString(),
            organizationResourceId = resourceIds.organizationResourceId,
            title = row[OnCallIncidents.title],
            description = row[OnCallIncidents.description],
            severity = row[OnCallIncidents.severity],
            status = row[OnCallIncidents.status],
            mode = row[OnCallIncidents.mode],
            visibility = row[OnCallIncidents.visibility],
            incidentType = row[OnCallIncidents.incidentType],
            summary = row[OnCallIncidents.summary],
            version = row[OnCallIncidents.version],
            declaredByResourceId = resourceIds.user(declaredById),
            declaredByName = resourceIds.userName(declaredById),
            declaredAt = row[OnCallIncidents.declaredAt].toString(),
            triagedAt = row[OnCallIncidents.triagedAt]?.toString(),
            acceptedAt = row[OnCallIncidents.acceptedAt]?.toString(),
            resolvedByResourceId = resourceIds.userOrNull(resolvedById),
            resolvedByName = resourceIds.userNameOrNull(resolvedById),
            resolvedAt = row[OnCallIncidents.resolvedAt]?.toString(),
            postIncidentAt = row[OnCallIncidents.postIncidentAt]?.toString(),
            closedAt = row[OnCallIncidents.closedAt]?.toString(),
            cancelledAt = row[OnCallIncidents.cancelledAt]?.toString(),
            declinedAt = row[OnCallIncidents.declinedAt]?.toString(),
            mergedAt = row[OnCallIncidents.mergedAt]?.toString(),
            mergedIntoIncidentResourceId = resourceIds.incidentOrNull(row[OnCallIncidents.mergedIntoIncidentId]),
            alertCount = alerts.size,
            alerts = alerts,
            createdAt = row[OnCallIncidents.createdAt].toString(),
            updatedAt = row[OnCallIncidents.updatedAt].toString(),
        )
    }

    private fun toIncidentSourceLink(row: ResultRow): IncidentSourceLink =
        IncidentSourceLink(
            id = row[NativeIncidentSourceLinks.resourceId].toString(),
            sourceType = IncidentSourceType.valueOf(row[NativeIncidentSourceLinks.sourceType]),
            sourceKey = row[NativeIncidentSourceLinks.sourceKey],
            label = row[NativeIncidentSourceLinks.label],
            sourceUrl = row[NativeIncidentSourceLinks.sourceUrl],
            metadata = row[NativeIncidentSourceLinks.metadata],
            createdAt = row[NativeIncidentSourceLinks.createdAt].toString(),
        )

    private fun toOnCallAlert(
        row: ResultRow,
        resourceIds: IncidentResponseResourceIds,
    ): OnCallAlert {
        val ackById = row[OnCallAlerts.acknowledgedBy]
        val resById = row[OnCallAlerts.resolvedBy]

        return OnCallAlert(
            id = row[OnCallAlerts.resourceId].toString(),
            organizationResourceId = resourceIds.organizationResourceId,
            declaredIncidentResourceId = resourceIds.incidentOrNull(row[OnCallAlerts.declaredIncidentId]),
            escalationPolicyResourceId = resourceIds.policyOrNull(row[OnCallAlerts.escalationPolicyId]),
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
            acknowledgedByResourceId = resourceIds.userOrNull(ackById),
            acknowledgedByName = resourceIds.userNameOrNull(ackById),
            resolvedAt = row[OnCallAlerts.resolvedAt]?.toString(),
            resolvedByResourceId = resourceIds.userOrNull(resById),
            resolvedByName = resourceIds.userNameOrNull(resById),
            metadata = row[OnCallAlerts.metadata],
            createdAt = row[OnCallAlerts.createdAt].toString(),
            updatedAt = row[OnCallAlerts.updatedAt].toString(),
            internalId = row[OnCallAlerts.id].value,
            organizationId = row[OnCallAlerts.organizationId],
            declaredIncidentId = row[OnCallAlerts.declaredIncidentId],
            escalationPolicyId = row[OnCallAlerts.escalationPolicyId],
            acknowledgedBy = ackById,
            resolvedBy = resById,
        )
    }

    private fun responseResourceIdsForIncidents(
        incidentRows: List<ResultRow>,
        alertRows: List<ResultRow>,
    ): IncidentResponseResourceIds {
        val organizationId = incidentRows.first()[OnCallIncidents.organizationId]
        val userIds = (
            incidentRows.flatMap { row ->
                listOfNotNull(row[OnCallIncidents.declaredBy], row[OnCallIncidents.resolvedBy])
            } + alertRows.flatMap { row ->
                listOfNotNull(row[OnCallAlerts.acknowledgedBy], row[OnCallAlerts.resolvedBy])
            }
            ).distinct()

        return IncidentResponseResourceIds(
            organizationResourceId = organizationResourceId(organizationId),
            users = userResponseData(userIds),
            incidentResourceIds = incidentResourceIds(
                alertRows.mapNotNull { row -> row[OnCallAlerts.declaredIncidentId] } +
                    incidentRows.mapNotNull { row -> row[OnCallIncidents.mergedIntoIncidentId] }
            ),
            policyResourceIds = escalationPolicyResourceIds(
                alertRows.mapNotNull { row -> row[OnCallAlerts.escalationPolicyId] }
            ),
        )
    }

    private fun userResponseData(userIds: Collection<Int>): Map<Int, UserResponseData> {
        if (userIds.isEmpty()) return emptyMap()
        return Users
            .selectAll()
            .where { Users.id inList userIds.distinct() }
            .associate { row ->
                row[Users.id] to UserResponseData(
                    resourceId = row[Users.resource_id].toString(),
                    displayName = row[Users.name] ?: row[Users.email],
                )
            }
    }

    private fun actorResourceId(
        actorId: Int?,
        users: Map<Int, UserResponseData>,
    ): String? =
        actorId?.let { users.requireValue(it, "user").resourceId }

    private fun actorName(
        actorId: Int?,
        users: Map<Int, UserResponseData>,
    ): String? =
        actorId?.let { users[it]?.displayName }

    private data class UserResponseData(
        val resourceId: String,
        val displayName: String?,
    )

    private data class IncidentResponseResourceIds(
        val organizationResourceId: String,
        val users: Map<Int, UserResponseData>,
        val incidentResourceIds: Map<Int, String>,
        val policyResourceIds: Map<Int, String>,
    ) {
        fun user(userId: Int): String =
            users.requireValue(userId, "user").resourceId

        fun userOrNull(userId: Int?): String? =
            userId?.let(::user)

        fun userName(userId: Int): String? =
            users.requireValue(userId, "user").displayName

        fun userNameOrNull(userId: Int?): String? =
            userId?.let(::userName)

        fun incidentOrNull(incidentId: Int?): String? =
            incidentId?.let { incidentResourceIds.requireValue(it, "on-call incident") }

        fun policyOrNull(policyId: Int?): String? =
            policyId?.let { policyResourceIds.requireValue(it, "escalation policy") }
    }
}

data class IncidentSourceIdentity(
    val sourceType: IncidentSourceType,
    val sourceKey: String,
)
