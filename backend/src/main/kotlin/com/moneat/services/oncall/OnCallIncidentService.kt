package com.moneat.services.oncall

import com.moneat.models.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

class OnCallIncidentService {

    fun declareIncident(
        organizationId: Int,
        userId: Int,
        alertId: Int,
        title: String,
        description: String?,
        priorityLevel: String
    ): OnCallIncident = transaction {
        val now = Clock.System.now()
        
        // Guard: check if alert is already linked to an incident
        val existingLink = OnCallIncidentAlerts.selectAll()
            .where { OnCallIncidentAlerts.alertId eq alertId }
            .singleOrNull()
        
        if (existingLink != null) {
            throw IllegalStateException("Alert is already linked to a declared incident")
        }
        
        // Create incident
        val incidentId = OnCallIncidents.insertAndGetId {
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
        
        getIncident(incidentId)!!
    }

    fun addAlertToIncident(incidentId: Int, alertId: Int) = transaction {
        // Check existence
        OnCallIncidents.selectAll().where { OnCallIncidents.id eq incidentId }.singleOrNull() 
            ?: throw IllegalArgumentException("Incident not found")
            
        // Check alert exists
        Incidents.selectAll().where { Incidents.id eq alertId }.singleOrNull()
            ?: throw IllegalArgumentException("Alert not found")
            
        // Insert if not exists
        val exists = OnCallIncidentAlerts.selectAll().where { 
            (OnCallIncidentAlerts.incidentId eq incidentId) and (OnCallIncidentAlerts.alertId eq alertId)
        }.empty().not()
        
        if (!exists) {
            OnCallIncidentAlerts.insert {
                it[OnCallIncidentAlerts.incidentId] = incidentId
                it[OnCallIncidentAlerts.alertId] = alertId
            }
            
            Incidents.update({ Incidents.id eq alertId }) {
                it[Incidents.incidentId] = incidentId
            }
        }
    }

    fun resolveIncident(incidentId: Int, userId: Int): OnCallIncident? = transaction {
        val now = Clock.System.now()
        
        OnCallIncidents.update({ OnCallIncidents.id eq incidentId }) {
            it[status] = "RESOLVED"
            it[resolvedBy] = userId
            it[resolvedAt] = now
            it[updatedAt] = now
        }
        
        getIncident(incidentId)
    }

    fun getIncident(incidentId: Int): OnCallIncident? = transaction {
        val row = OnCallIncidents
            .selectAll()
            .where { OnCallIncidents.id eq incidentId }
            .singleOrNull() ?: return@transaction null
            
        val alerts = Incidents
            .innerJoin(OnCallIncidentAlerts)
            .selectAll()
            .where { OnCallIncidentAlerts.incidentId eq incidentId }
            .map { toIncident(it) }
            
        toOnCallIncident(row, alerts)
    }
    
    fun getIncidents(organizationId: Int, status: String? = null, priorityLevel: String? = null): List<OnCallIncident> = transaction {
        val query = OnCallIncidents.selectAll()
            .where { OnCallIncidents.organizationId eq organizationId }
            
        if (status != null) {
            query.andWhere { OnCallIncidents.status eq status }
        }
        
        if (priorityLevel != null) {
            query.andWhere { OnCallIncidents.severity eq priorityLevel }
        }
        
        query.orderBy(OnCallIncidents.createdAt to SortOrder.DESC)
            .map { row -> 
                val alerts = Incidents
                    .innerJoin(OnCallIncidentAlerts)
                    .selectAll()
                    .where { OnCallIncidentAlerts.incidentId eq row[OnCallIncidents.id].value }
                    .map { toIncident(it) }
                toOnCallIncident(row, alerts)
            }
    }
    
    fun isIncidentInOrganization(incidentId: Int, organizationId: Int): Boolean = transaction {
        OnCallIncidents
            .selectAll()
            .where { (OnCallIncidents.id eq incidentId) and (OnCallIncidents.organizationId eq organizationId) }
            .limit(1)
            .singleOrNull() != null
    }
    
    private fun toOnCallIncident(row: ResultRow, alerts: List<Incident>): OnCallIncident {
        val declaredById = row[OnCallIncidents.declaredBy]
        val declaredUser = Users.selectAll().where { Users.id eq declaredById }.singleOrNull()
        val declaredByName = declaredUser?.get(Users.name) ?: declaredUser?.get(Users.email)
        
        val resolvedById = row[OnCallIncidents.resolvedBy]
        val resolvedByName = if (resolvedById != null) {
            val u = Users.selectAll().where { Users.id eq resolvedById }.singleOrNull()
            u?.get(Users.name) ?: u?.get(Users.email)
        } else null
        
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
            updatedAt = row[OnCallIncidents.updatedAt].toString()
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
            updatedAt = row[Incidents.updatedAt].toString()
        )
    }
}
