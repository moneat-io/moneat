// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

import com.moneat.enterprise.oncall.models.Incident
import com.moneat.enterprise.oncall.models.IncidentTimeline
import com.moneat.enterprise.oncall.models.IncidentTimelineEvent
import com.moneat.enterprise.oncall.models.Incidents
import com.moneat.enterprise.oncall.models.OnCallIncident
import com.moneat.enterprise.oncall.models.OnCallIncidentAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidentTimeline
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.shared.models.Users
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
import kotlin.time.Clock

class OnCallIncidentService {
    fun declareIncident(
        organizationId: Int,
        userId: Int,
        alertId: Int,
        title: String,
        description: String?,
        priorityLevel: String,
    ): OnCallIncident =
        transaction {
            val now = Clock.System.now()

            // Guard: check if alert is already linked to an incident
            val existingLink =
                OnCallIncidentAlerts
                    .selectAll()
                    .where { OnCallIncidentAlerts.alertId eq alertId }
                    .singleOrNull()

            if (existingLink != null) {
                throw IllegalStateException("Alert is already linked to a declared incident")
            }

            // Create incident
            val incidentId =
                OnCallIncidents
                    .insertAndGetId {
                        it[OnCallIncidents.organizationId] = organizationId
                        it[OnCallIncidents.title] = title
                        it[OnCallIncidents.description] = description
                        it[OnCallIncidents.severity] = priorityLevel
                        it[OnCallIncidents.status] = "OPEN"
                        it[OnCallIncidents.declaredBy] = userId
                        it[OnCallIncidents.declaredAt] = now
                        it[OnCallIncidents.createdAt] = now
                        it[OnCallIncidents.updatedAt] = now
                    }.value

            // Link alert
            OnCallIncidentAlerts.insert {
                it[OnCallIncidentAlerts.incidentId] = incidentId
                it[OnCallIncidentAlerts.alertId] = alertId
            }

            // Update alert with incident_id reference
            Incidents.update({ Incidents.id eq alertId }) {
                it[Incidents.incidentId] = incidentId
            }

            // Add DECLARED event to timeline
            OnCallIncidentTimeline.insert {
                it[OnCallIncidentTimeline.incidentId] = incidentId
                it[OnCallIncidentTimeline.eventType] = "DECLARED"
                it[OnCallIncidentTimeline.actorUserId] = userId
                it[OnCallIncidentTimeline.details] = emptyMap()
                it[OnCallIncidentTimeline.createdAt] = now
            }

            getIncident(incidentId)!!
        }

    fun addAlertToIncident(
        incidentId: Int,
        alertId: Int,
    ) = transaction {
        // Check existence
        OnCallIncidents.selectAll().where { OnCallIncidents.id eq incidentId }.singleOrNull()
            ?: throw IllegalArgumentException("Incident not found")

        // Check alert exists
        val alert =
            Incidents.selectAll().where { Incidents.id eq alertId }.singleOrNull()
                ?: throw IllegalArgumentException("Alert not found")

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

            Incidents.update({ Incidents.id eq alertId }) {
                it[Incidents.incidentId] = incidentId
            }

            // Add ALERT_LINKED event to timeline
            OnCallIncidentTimeline.insert {
                it[OnCallIncidentTimeline.incidentId] = incidentId
                it[OnCallIncidentTimeline.eventType] = "ALERT_LINKED"
                it[OnCallIncidentTimeline.actorUserId] = null
                it[OnCallIncidentTimeline.details] =
                    mapOf(
                        "alertId" to kotlinx.serialization.json.JsonPrimitive(alertId),
                        "alertTitle" to kotlinx.serialization.json.JsonPrimitive(alert[Incidents.title]),
                    )
                it[OnCallIncidentTimeline.createdAt] = Clock.System.now()
            }
        }
    }

    fun resolveIncident(
        incidentId: Int,
        userId: Int,
    ): OnCallIncident? =
        transaction {
            val current =
                OnCallIncidents
                    .selectAll()
                    .where { OnCallIncidents.id eq incidentId }
                    .singleOrNull() ?: return@transaction null

            if (current[OnCallIncidents.status] == "RESOLVED") {
                return@transaction getIncident(incidentId)
            }

            val now = Clock.System.now()

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
                return@transaction getIncident(incidentId)
            }

            // Add RESOLVED event to timeline
            OnCallIncidentTimeline.insert {
                it[OnCallIncidentTimeline.incidentId] = incidentId
                it[OnCallIncidentTimeline.eventType] = "RESOLVED"
                it[OnCallIncidentTimeline.actorUserId] = userId
                it[OnCallIncidentTimeline.details] = emptyMap()
                it[OnCallIncidentTimeline.createdAt] = now
            }

            getIncident(incidentId)
        }

    fun getIncident(incidentId: Int): OnCallIncident? =
        transaction {
            val row =
                OnCallIncidents
                    .selectAll()
                    .where { OnCallIncidents.id eq incidentId }
                    .singleOrNull() ?: return@transaction null

            val alerts =
                Incidents
                    .innerJoin(OnCallIncidentAlerts)
                    .selectAll()
                    .where { OnCallIncidentAlerts.incidentId eq incidentId }
                    .map { toIncident(it) }

            toOnCallIncident(row, alerts)
        }

    fun getIncidents(
        organizationId: Int,
        status: String? = null,
        priorityLevel: String? = null,
    ): List<OnCallIncident> =
        transaction {
            var query =
                OnCallIncidents
                    .selectAll()
                    .where { OnCallIncidents.organizationId eq organizationId }

            if (status != null) {
                query = query.andWhere { OnCallIncidents.status eq status }
            }

            if (priorityLevel != null) {
                query = query.andWhere { OnCallIncidents.severity eq priorityLevel }
            }

            query
                .orderBy(OnCallIncidents.createdAt to SortOrder.DESC)
                .map { row ->
                    val alerts =
                        Incidents
                            .innerJoin(OnCallIncidentAlerts)
                            .selectAll()
                            .where { OnCallIncidentAlerts.incidentId eq row[OnCallIncidents.id].value }
                            .map { toIncident(it) }
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
                    "note" to kotlinx.serialization.json.JsonPrimitive(note),
                )
            it[OnCallIncidentTimeline.createdAt] = now
        }
    }

    fun getIncidentTimeline(incidentId: Int): List<IncidentTimelineEvent> =
        transaction {
            val events = mutableListOf<IncidentTimelineEvent>()

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

                        IncidentTimelineEvent(
                            id = row[OnCallIncidentTimeline.id].value,
                            incidentId = incidentId,
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
                    Incidents
                        .selectAll()
                        .where { Incidents.id eq alertId }
                        .singleOrNull()
                        ?.get(Incidents.title)

                val alertEvents =
                    IncidentTimeline
                        .selectAll()
                        .where { IncidentTimeline.incidentId eq alertId }
                        .map { row ->
                            val actorId = row[IncidentTimeline.actorUserId]
                            val actorName =
                                if (actorId != null) {
                                    val user = Users.selectAll().where { Users.id eq actorId }.singleOrNull()
                                    user?.get(Users.name) ?: user?.get(Users.email)
                                } else {
                                    null
                                }

                            IncidentTimelineEvent(
                                id = row[IncidentTimeline.id].value,
                                incidentId = alertId,
                                eventType = row[IncidentTimeline.eventType],
                                actorUserId = actorId,
                                actorName = actorName,
                                details = row[IncidentTimeline.details],
                                createdAt = row[IncidentTimeline.createdAt].toString(),
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
        alerts: List<Incident>,
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
            priorityLevel = row[OnCallIncidents.severity],
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

    private fun toIncident(row: ResultRow): Incident {
        val ackById = row[Incidents.acknowledgedBy]
        val ackUser = if (ackById != null) Users.selectAll().where { Users.id eq ackById }.singleOrNull() else null
        val ackByName = ackUser?.get(Users.name) ?: ackUser?.get(Users.email)

        val resById = row[Incidents.resolvedBy]
        val resUser = if (resById != null) Users.selectAll().where { Users.id eq resById }.singleOrNull() else null
        val resByName = resUser?.get(Users.name) ?: resUser?.get(Users.email)

        return Incident(
            id = row[Incidents.id].value,
            organizationId = row[Incidents.organizationId],
            escalationPolicyId = row[Incidents.escalationPolicyId],
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
            acknowledgedBy = ackById,
            acknowledgedByName = ackByName,
            resolvedAt = row[Incidents.resolvedAt]?.toString(),
            resolvedBy = resById,
            resolvedByName = resByName,
            metadata = row[Incidents.metadata],
            createdAt = row[Incidents.createdAt].toString(),
            updatedAt = row[Incidents.updatedAt].toString(),
        )
    }
}
