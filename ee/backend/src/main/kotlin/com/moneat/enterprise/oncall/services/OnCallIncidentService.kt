// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

import com.moneat.alerts.models.IncidentSeverity
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
import com.moneat.utils.suspendRunCatching
import com.moneat.workflows.services.WorkflowService
import kotlinx.serialization.json.JsonPrimitive
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
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import kotlin.time.Clock

private val logger = LoggerFactory.getLogger(OnCallIncidentService::class.java)
private const val ALERT_NOT_FOUND_MESSAGE = "Alert not found"

class OnCallIncidentService(
    private val workflowService: WorkflowService = WorkflowService(),
) {
    suspend fun declareIncident(
        organizationId: Int,
        userId: Int,
        alertId: Int?,
        title: String,
        description: String?,
        severity: String,
    ): OnCallIncident {
        val result =
            transaction {
                val now = Clock.System.now()
                val incidentSeverity =
                    requireNotNull(IncidentSeverity.fromString(severity)) {
                        "Invalid incident severity: $severity"
                    }

                if (alertId != null) {
                    validateAlertForDeclaration(organizationId, alertId)
                }

                // Create incident
                val incidentId =
                    OnCallIncidents
                        .insertAndGetId {
                            it[OnCallIncidents.organizationId] = organizationId
                            it[OnCallIncidents.title] = title
                            it[OnCallIncidents.description] = description
                            it[OnCallIncidents.severity] = incidentSeverity.wire
                            it[OnCallIncidents.status] = "OPEN"
                            it[OnCallIncidents.declaredBy] = userId
                            it[OnCallIncidents.declaredAt] = now
                            it[OnCallIncidents.createdAt] = now
                            it[OnCallIncidents.updatedAt] = now
                        }.value

                if (alertId != null) {
                    // Link alert
                    OnCallIncidentAlerts.insert {
                        it[OnCallIncidentAlerts.incidentId] = incidentId
                        it[OnCallIncidentAlerts.alertId] = alertId
                    }

                    // Update alert with incident_id reference
                    OnCallAlerts.update({ OnCallAlerts.id eq alertId }) {
                        it[OnCallAlerts.declaredIncidentId] = incidentId
                    }
                }

                // Add DECLARED event to timeline
                OnCallIncidentTimeline.insert {
                    it[OnCallIncidentTimeline.incidentId] = incidentId
                    it[OnCallIncidentTimeline.eventType] = "DECLARED"
                    it[OnCallIncidentTimeline.actorUserId] = userId
                    it[OnCallIncidentTimeline.details] = emptyMap()
                    it[OnCallIncidentTimeline.createdAt] = now
                }

                DeclaredIncidentResult(
                    incident = getIncident(incidentId)!!,
                    severity = incidentSeverity,
                )
            }

        publishIncidentCreated(result.incident, result.severity)
        return result.incident
    }

    private fun validateAlertForDeclaration(
        organizationId: Int,
        alertId: Int,
    ) {
        val alert =
            OnCallAlerts
                .selectAll()
                .where { OnCallAlerts.id eq alertId }
                .singleOrNull() ?: throw IllegalArgumentException(ALERT_NOT_FOUND_MESSAGE)

        require(alert[OnCallAlerts.organizationId] == organizationId) {
            ALERT_NOT_FOUND_MESSAGE
        }

        val existingLink =
            OnCallIncidentAlerts
                .selectAll()
                .where { OnCallIncidentAlerts.alertId eq alertId }
                .singleOrNull()

        check(existingLink == null) {
            "Alert is already linked to a declared incident"
        }
    }

    private suspend fun publishIncidentCreated(
        incident: OnCallIncident,
        severity: IncidentSeverity,
    ) {
        suspendRunCatching {
            workflowService.publishDeclaredIncidentCreated(
                organizationId = incident.organizationId,
                incidentId = incident.internalId,
                title = incident.title,
                severity = severity,
            )
        }.getOrElse { e ->
            logger.error("Error publishing incident-created workflow for declared incident ${incident.id}", e)
        }
    }

    fun addAlertToIncident(
        incidentId: Int,
        alertId: Int,
    ) = transaction {
        val incident =
            OnCallIncidents.selectAll().where { OnCallIncidents.id eq incidentId }.singleOrNull()
            ?: throw IllegalArgumentException("Incident not found")

        val alert =
            OnCallAlerts.selectAll().where { OnCallAlerts.id eq alertId }.singleOrNull()
                ?: throw IllegalArgumentException(ALERT_NOT_FOUND_MESSAGE)

        require(incident[OnCallIncidents.organizationId] == alert[OnCallAlerts.organizationId]) {
            ALERT_NOT_FOUND_MESSAGE
        }

        // Insert if not exists
        val exists =
            OnCallIncidentAlerts
                .selectAll()
                .where {
                    (OnCallIncidentAlerts.incidentId eq incidentId) and (OnCallIncidentAlerts.alertId eq alertId)
                }.empty()
                .not()

        if (!exists) {
            OnCallIncidentAlerts.insert {
                it[OnCallIncidentAlerts.incidentId] = incidentId
                it[OnCallIncidentAlerts.alertId] = alertId
            }

            OnCallAlerts.update({ OnCallAlerts.id eq alertId }) {
                it[OnCallAlerts.declaredIncidentId] = incidentId
            }

            // Add ALERT_LINKED event to timeline
            OnCallIncidentTimeline.insert {
                it[OnCallIncidentTimeline.incidentId] = incidentId
                it[OnCallIncidentTimeline.eventType] = "ALERT_LINKED"
                    it[OnCallIncidentTimeline.actorUserId] = null
                    it[OnCallIncidentTimeline.details] =
                        mapOf(
                            "alertId" to JsonPrimitive(alert[OnCallAlerts.resourceId].toString()),
                            "alertTitle" to JsonPrimitive(alert[OnCallAlerts.title]),
                        )
                it[OnCallIncidentTimeline.createdAt] = Clock.System.now()
            }
        }
    }

    suspend fun resolveIncident(
        incidentId: Int,
        userId: Int,
        resolutionNote: String? = null,
    ): OnCallIncident? {
        val result =
            transaction {
                val current =
                    OnCallIncidents
                        .selectAll()
                        .where { OnCallIncidents.id eq incidentId }
                        .singleOrNull() ?: return@transaction null

                val incidentSeverity =
                    requireNotNull(IncidentSeverity.fromString(current[OnCallIncidents.severity])) {
                        "Invalid incident severity: ${current[OnCallIncidents.severity]}"
                    }

                if (current[OnCallIncidents.status] == "RESOLVED") {
                    return@transaction ResolvedIncidentResult(getIncident(incidentId), null)
                }

                val now = Clock.System.now()
                val resolutionDetails =
                    resolutionNote
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { note -> mapOf("note" to JsonPrimitive(note)) }
                        ?: emptyMap()

                val updated =
                    OnCallIncidents.update({
                        (OnCallIncidents.id eq incidentId) and (OnCallIncidents.status eq "OPEN")
                    }) {
                        it[status] = "RESOLVED"
                        it[resolvedBy] = userId
                        it[resolvedAt] = now
                        it[updatedAt] = now
                    }

                if (updated == 0) {
                    return@transaction ResolvedIncidentResult(getIncident(incidentId), null)
                }

                // Add RESOLVED event to timeline
                OnCallIncidentTimeline.insert {
                    it[OnCallIncidentTimeline.incidentId] = incidentId
                    it[OnCallIncidentTimeline.eventType] = "RESOLVED"
                    it[OnCallIncidentTimeline.actorUserId] = userId
                    it[OnCallIncidentTimeline.details] = resolutionDetails
                    it[OnCallIncidentTimeline.createdAt] = now
                }

                ResolvedIncidentResult(getIncident(incidentId), incidentSeverity)
            }

        val incident = result?.incident ?: return null
        result.severityToPublish?.let { severity ->
            publishIncidentResolved(incident, severity)
        }
        return incident
    }

    private suspend fun publishIncidentResolved(
        incident: OnCallIncident,
        severity: IncidentSeverity,
    ) {
        suspendRunCatching {
            workflowService.publishDeclaredIncidentResolved(
                organizationId = incident.organizationId,
                incidentId = incident.internalId,
                title = incident.title,
                severity = severity,
            )
        }.getOrElse { e ->
            logger.error("Error publishing incident-resolved workflow for declared incident ${incident.id}", e)
        }
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
                query = query.andWhere { OnCallIncidents.status eq status }
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

    fun addNote(
        incidentId: Int,
        userId: Int,
        note: String,
    ) = transaction {
        val now = Clock.System.now()

        OnCallIncidentTimeline.insert {
            it[OnCallIncidentTimeline.incidentId] = incidentId
            it[OnCallIncidentTimeline.eventType] = "NOTE_ADDED"
            it[OnCallIncidentTimeline.actorUserId] = userId
            it[OnCallIncidentTimeline.details] =
                mapOf(
                    "note" to JsonPrimitive(note),
                )
            it[OnCallIncidentTimeline.createdAt] = now
        }
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
            declaredByResourceId = resourceIds.user(declaredById),
            declaredByName = resourceIds.userName(declaredById),
            declaredAt = row[OnCallIncidents.declaredAt].toString(),
            resolvedByResourceId = resourceIds.userOrNull(resolvedById),
            resolvedByName = resourceIds.userNameOrNull(resolvedById),
            resolvedAt = row[OnCallIncidents.resolvedAt]?.toString(),
            alertCount = alerts.size,
            alerts = alerts,
            createdAt = row[OnCallIncidents.createdAt].toString(),
            updatedAt = row[OnCallIncidents.updatedAt].toString(),
            internalId = row[OnCallIncidents.id].value,
            organizationId = row[OnCallIncidents.organizationId],
            declaredBy = declaredById,
            resolvedBy = resolvedById,
        )
    }

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
                alertRows.mapNotNull { row -> row[OnCallAlerts.declaredIncidentId] }
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

    private data class DeclaredIncidentResult(
        val incident: OnCallIncident,
        val severity: IncidentSeverity,
    )

    private data class ResolvedIncidentResult(
        val incident: OnCallIncident?,
        val severityToPublish: IncidentSeverity?,
    )
}
