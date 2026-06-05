// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

import com.moneat.alerts.models.IncidentSeverity
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
                incidentId = incident.id,
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
                            "alertId" to JsonPrimitive(alertId),
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
                incidentId = incident.id,
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
                    .map { toOnCallAlert(it) }

            toOnCallIncident(row, alerts)
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

            query
                .orderBy(OnCallIncidents.createdAt to SortOrder.DESC)
                .map { row ->
                    val alerts =
                        OnCallAlerts
                            .innerJoin(OnCallIncidentAlerts)
                            .selectAll()
                            .where { OnCallIncidentAlerts.incidentId eq row[OnCallIncidents.id].value }
                            .map { toOnCallAlert(it) }
                    toOnCallIncident(row, alerts)
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

            // 1. Fetch incident-level events
            val incidentEvents =
                OnCallIncidentTimeline
                    .selectAll()
                    .where { OnCallIncidentTimeline.incidentId eq incidentId }
                    .map { row ->
                        val actorId = row[OnCallIncidentTimeline.actorUserId]
                        val actorName =
                            if (actorId != null) {
                                val user = Users.selectAll().where { Users.id eq actorId }.singleOrNull()
                                user?.get(Users.name) ?: user?.get(Users.email)
                            } else {
                                null
                            }

                        OnCallTimelineEvent(
                            id = row[OnCallIncidentTimeline.id].value,
                            targetId = incidentId,
                            eventType = row[OnCallIncidentTimeline.eventType],
                            actorUserId = actorId,
                            actorName = actorName,
                            details = row[OnCallIncidentTimeline.details],
                            createdAt = row[OnCallIncidentTimeline.createdAt].toString(),
                            source = "incident",
                            alertId = null,
                            alertTitle = null,
                        )
                    }
            events.addAll(incidentEvents)

            // 2. Fetch all linked alert IDs
            val alertIds =
                OnCallIncidentAlerts
                    .selectAll()
                    .where { OnCallIncidentAlerts.incidentId eq incidentId }
                    .map { it[OnCallIncidentAlerts.alertId] }

            // 3. For each linked alert, fetch its timeline events
            for (alertId in alertIds) {
                val alertTitle =
                    OnCallAlerts
                        .selectAll()
                        .where { OnCallAlerts.id eq alertId }
                        .singleOrNull()
                        ?.get(OnCallAlerts.title)

                val alertEvents =
                    OnCallAlertTimeline
                        .selectAll()
                        .where { OnCallAlertTimeline.alertId eq alertId }
                        .map { row ->
                            val actorId = row[OnCallAlertTimeline.actorUserId]
                            val actorName =
                                if (actorId != null) {
                                    val user = Users.selectAll().where { Users.id eq actorId }.singleOrNull()
                                    user?.get(Users.name) ?: user?.get(Users.email)
                                } else {
                                    null
                                }

                            OnCallTimelineEvent(
                                id = row[OnCallAlertTimeline.id].value,
                                targetId = alertId,
                                eventType = row[OnCallAlertTimeline.eventType],
                                actorUserId = actorId,
                                actorName = actorName,
                                details = row[OnCallAlertTimeline.details],
                                createdAt = row[OnCallAlertTimeline.createdAt].toString(),
                                source = "alert",
                                alertId = alertId,
                                alertTitle = alertTitle,
                            )
                        }
                events.addAll(alertEvents)
            }

            // 4. Sort by createdAt ascending
            events.sortedBy { it.createdAt }
        }

    private fun toOnCallIncident(
        row: ResultRow,
        alerts: List<OnCallAlert>,
    ): OnCallIncident {
        val declaredById = row[OnCallIncidents.declaredBy]
        val declaredUser = Users.selectAll().where { Users.id eq declaredById }.singleOrNull()
        val declaredByName = declaredUser?.get(Users.name) ?: declaredUser?.get(Users.email)

        val resolvedById = row[OnCallIncidents.resolvedBy]
        val resolvedByName =
            if (resolvedById != null) {
                val u = Users.selectAll().where { Users.id eq resolvedById }.singleOrNull()
                u?.get(Users.name) ?: u?.get(Users.email)
            } else {
                null
            }

        return OnCallIncident(
            id = row[OnCallIncidents.id].value,
            organizationId = row[OnCallIncidents.organizationId],
            title = row[OnCallIncidents.title],
            description = row[OnCallIncidents.description],
            severity = row[OnCallIncidents.severity],
            status = row[OnCallIncidents.status],
            declaredBy = declaredById,
            declaredByName = declaredByName,
            declaredAt = row[OnCallIncidents.declaredAt].toString(),
            resolvedBy = resolvedById,
            resolvedByName = resolvedByName,
            resolvedAt = row[OnCallIncidents.resolvedAt]?.toString(),
            alertCount = alerts.size,
            alerts = alerts,
            createdAt = row[OnCallIncidents.createdAt].toString(),
            updatedAt = row[OnCallIncidents.updatedAt].toString(),
        )
    }

    private fun toOnCallAlert(row: ResultRow): OnCallAlert {
        val ackById = row[OnCallAlerts.acknowledgedBy]
        val ackUser = if (ackById != null) Users.selectAll().where { Users.id eq ackById }.singleOrNull() else null
        val ackByName = ackUser?.get(Users.name) ?: ackUser?.get(Users.email)

        val resById = row[OnCallAlerts.resolvedBy]
        val resUser = if (resById != null) Users.selectAll().where { Users.id eq resById }.singleOrNull() else null
        val resByName = resUser?.get(Users.name) ?: resUser?.get(Users.email)

        return OnCallAlert(
            id = row[OnCallAlerts.id].value,
            organizationId = row[OnCallAlerts.organizationId],
            declaredIncidentId = row[OnCallAlerts.declaredIncidentId],
            escalationPolicyId = row[OnCallAlerts.escalationPolicyId],
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
            acknowledgedBy = ackById,
            acknowledgedByName = ackByName,
            resolvedAt = row[OnCallAlerts.resolvedAt]?.toString(),
            resolvedBy = resById,
            resolvedByName = resByName,
            metadata = row[OnCallAlerts.metadata],
            createdAt = row[OnCallAlerts.createdAt].toString(),
            updatedAt = row[OnCallAlerts.updatedAt].toString(),
        )
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
