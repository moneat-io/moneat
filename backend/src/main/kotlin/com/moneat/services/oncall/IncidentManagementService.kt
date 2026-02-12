package com.moneat.services.oncall

import com.moneat.models.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

class IncidentManagementService(
    private val escalationEngine: EscalationEngine
) {
    
    fun getIncident(incidentId: Int): Incident? = transaction {
        val row = Incidents
            .leftJoin(EscalationPolicies, { escalationPolicyId }, { id })
            .leftJoin(Users, { acknowledgedBy }, { id }, additionalConstraint = { Users.id.isNotNull() })
            .selectAll()
            .where { Incidents.id eq incidentId }
            .singleOrNull() ?: return@transaction null
        
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
            metadata = row[Incidents.metadata]?.let { Json.decodeFromString(it) },
            createdAt = row[Incidents.createdAt].toString(),
            updatedAt = row[Incidents.updatedAt].toString()
        )
    }
    
    fun listIncidents(
        organizationId: Int,
        status: String? = null,
        priorityLevel: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): List<Incident> = transaction {
        var query = Incidents
            .leftJoin(EscalationPolicies, { escalationPolicyId }, { id })
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
            .limit(limit, offset.toLong())
            .map { row ->
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
                    resolvedAt = row[Incidents.resolvedAt]?.toString(),
                    resolvedBy = row[Incidents.resolvedBy],
                    metadata = row[Incidents.metadata]?.let { Json.decodeFromString(it) },
                    createdAt = row[Incidents.createdAt].toString(),
                    updatedAt = row[Incidents.updatedAt].toString()
                )
            }
    }
    
    fun getTimeline(incidentId: Int): List<IncidentTimelineEvent> = transaction {
        IncidentTimeline
            .leftJoin(Users, { actorUserId }, { id })
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
                    details = row[IncidentTimeline.details]?.let { Json.decodeFromString(it) },
                    createdAt = row[IncidentTimeline.createdAt].toString()
                )
            }
    }
    
    fun acknowledge(incidentId: Int, userId: Int): Boolean {
        return escalationEngine.acknowledgeIncident(incidentId, userId)
    }
    
    fun resolve(incidentId: Int, userId: Int): Boolean {
        return escalationEngine.resolveIncident(incidentId, userId)
    }
    
    fun reassign(incidentId: Int, toUserId: Int, byUserId: Int): Boolean {
        return escalationEngine.reassignIncident(incidentId, toUserId, byUserId)
    }
    
    fun addNote(incidentId: Int, userId: Int, noteText: String): IncidentTimelineEvent = transaction {
        val now = Clock.System.now()
        
        val eventId = IncidentTimeline.insertAndGetId {
            it[IncidentTimeline.incidentId] = incidentId
            it[eventType] = "NOTE_ADDED"
            it[actorUserId] = userId
            it[details] = Json.encodeToString(kotlinx.serialization.serializer(), mapOf("note" to noteText))
            it[createdAt] = now
        }.value
        
        val row = IncidentTimeline
            .leftJoin(Users, { actorUserId }, { id })
            .selectAll()
            .where { IncidentTimeline.id eq eventId }
            .single()
        
        IncidentTimelineEvent(
            id = row[IncidentTimeline.id].value,
            incidentId = row[IncidentTimeline.incidentId],
            eventType = row[IncidentTimeline.eventType],
            actorUserId = row[IncidentTimeline.actorUserId],
            actorName = row.getOrNull(Users.name),
            details = row[IncidentTimeline.details]?.let { Json.decodeFromString(it) },
            createdAt = row[IncidentTimeline.createdAt].toString()
        )
    }
}
