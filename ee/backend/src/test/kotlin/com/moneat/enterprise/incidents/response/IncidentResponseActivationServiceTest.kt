// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.response

import com.moneat.enterprise.incidents.IncidentTestDatabase
import com.moneat.enterprise.incidents.events.NativeIncidentDomainEvent
import com.moneat.enterprise.incidents.models.IncidentParticipationType
import com.moneat.enterprise.incidents.models.NativeIncidentParticipants
import com.moneat.enterprise.incidents.models.NativeIncidentRoleDefinitions
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.shared.models.EscalationPolicies
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class IncidentResponseActivationServiceTest {
    private lateinit var member: com.moneat.enterprise.incidents.SeededMember
    private lateinit var incident: SeededIncident
    private lateinit var policy: SeededPolicy

    @BeforeTest
    fun setUp() {
        IncidentTestDatabase.reset()
        member = IncidentTestDatabase.seedMember()
        policy = seedPolicy()
        incident = seedIncident(NativeIncidentStatus.ACTIVE.wire)
    }

    @AfterTest
    fun tearDown() {
        IncidentTestDatabase.clearReference()
    }

    @Test
    fun `active declaration pages once and replay is idempotent`() {
        val pagedAlerts = mutableListOf<Int>()
        val alertId = seedAlert()
        val service = IncidentResponseActivationService(
            pager = IncidentResponsePager { _ ->
                pagedAlerts += alertId
                alertId
            },
        )
        service.updatePolicy(
            member.organizationId,
            member.userId,
            IncidentResponsePolicyInput(
                commanderPolicyResourceId = policy.resourceId.toString(),
                ownershipPolicyResourceId = null,
                pageOwnership = false,
                pageTestIncidents = false,
                pageRetrospectiveIncidents = false,
            ),
        )

        val event = event("INCIDENT_DECLARE", NativeIncidentStatus.ACTIVE.wire)
        service.activate(event)
        service.activate(event)

        val response = service.incidentResponse(member.organizationId, incident.resourceId.toString()).single()
        assertEquals(IncidentResponseActivationStatus.COMPLETED.wire, response.status)
        assertEquals(1, response.desiredCount)
        assertEquals(1, response.attemptedCount)
        assertEquals(1, pagedAlerts.size)
        assertEquals(
            1,
            transaction {
                OnCallAlerts.selectAll().where { OnCallAlerts.declaredIncidentId eq incident.id }.count()
            },
        )
    }

    @Test
    fun `triage declaration waits for acceptance before paging`() {
        val triageIncident = seedIncident(NativeIncidentStatus.TRIAGE.wire)
        val service = IncidentResponseActivationService()
        service.updatePolicy(
            member.organizationId,
            member.userId,
            IncidentResponsePolicyInput(policy.resourceId.toString(), null, false, false, false),
        )

        service.activate(event("INCIDENT_DECLARE", NativeIncidentStatus.TRIAGE.wire, triageIncident))
        assertEquals(0, service.incidentResponse(member.organizationId, triageIncident.resourceId.toString()).size)

        transaction {
            OnCallIncidents.update({ OnCallIncidents.id eq triageIncident.id }) {
                it[OnCallIncidents.status] = NativeIncidentStatus.ACTIVE.wire
                it[OnCallIncidents.severity] = "SEV-2"
                it[OnCallIncidents.acceptedAt] = Clock.System.now()
            }
        }
        service.activate(event("INCIDENT_ACCEPT", NativeIncidentStatus.ACTIVE.wire, triageIncident))

        val response = service.incidentResponse(member.organizationId, triageIncident.resourceId.toString()).single()
        assertEquals("INCIDENT_ACCEPT", response.trigger)
    }

    @Test
    fun `missing page result is durable and retry can recover`() {
        var shouldPage = false
        val service = IncidentResponseActivationService(
            pager = IncidentResponsePager { if (shouldPage) seedAlert() else null },
        )
        service.updatePolicy(
            member.organizationId,
            member.userId,
            IncidentResponsePolicyInput(policy.resourceId.toString(), null, false, false, false),
        )

        service.activate(event("INCIDENT_DECLARE", NativeIncidentStatus.ACTIVE.wire))
        val failed = service.incidentResponse(member.organizationId, incident.resourceId.toString()).single()
        assertEquals(IncidentResponseActivationStatus.FAILED.wire, failed.status)
        assertEquals(IncidentResponseTargetStatus.FAILED.wire, failed.targets.single().status)
        assertNotNull(failed.lastError)

        shouldPage = true
        service.retry(member.organizationId, failed.id, member.userId)
        val recovered = service.incidentResponse(member.organizationId, incident.resourceId.toString()).single()
        assertEquals(IncidentResponseActivationStatus.COMPLETED.wire, recovered.status)
        assertEquals(2, recovered.targets.single().attemptCount)
    }

    @Test
    fun `acknowledgement claims commander and adds participant`() {
        val alertId = seedAlert()
        seedCommanderRole()
        val service = IncidentResponseActivationService(
            pager = IncidentResponsePager { alertId },
        )
        service.updatePolicy(
            member.organizationId,
            member.userId,
            IncidentResponsePolicyInput(policy.resourceId.toString(), null, false, false, false),
        )
        service.activate(event("INCIDENT_DECLARE", NativeIncidentStatus.ACTIVE.wire))

        assertTrue(service.markAcknowledged(alertId, member.userId))
        assertTrue(service.markAcknowledged(alertId, member.userId).not())
        assertEquals(
            1,
            transaction {
                NativeIncidentParticipants.selectAll().where {
                    (NativeIncidentParticipants.incidentId eq incident.id) and
                        (NativeIncidentParticipants.userId eq member.userId) and
                        (NativeIncidentParticipants.participationType eq IncidentParticipationType.PARTICIPANT.wire)
                }.count()
            },
        )
        val activation = service.incidentResponse(member.organizationId, incident.resourceId.toString()).single()
        assertEquals(IncidentResponseTargetStatus.ACKNOWLEDGED.wire, activation.targets.single().status)
    }

    @Test
    fun `retry rejects an activation from another incident`() {
        val service = IncidentResponseActivationService()
        service.updatePolicy(
            member.organizationId,
            member.userId,
            IncidentResponsePolicyInput(policy.resourceId.toString(), null, false, false, false),
        )
        service.activate(event("INCIDENT_DECLARE", NativeIncidentStatus.ACTIVE.wire))
        val response = service.incidentResponse(member.organizationId, incident.resourceId.toString()).single()
        val other = seedIncident(NativeIncidentStatus.ACTIVE.wire)

        assertFailsWith<NoSuchElementException> {
            service.retry(member.organizationId, response.id, member.userId, other.id)
        }
    }

    private fun seedPolicy(): SeededPolicy = transaction {
        val resourceId = Uuid.random()
        val id = EscalationPolicies.insertAndGetId {
            it[EscalationPolicies.resourceId] = resourceId
            it[organizationId] = member.organizationId
            it[name] = "Incident Commander"
            it[repeatCount] = 1
            it[createdAt] = Clock.System.now()
            it[updatedAt] = Clock.System.now()
        }.value
        SeededPolicy(id, resourceId)
    }

    private fun seedIncident(status: String): SeededIncident = transaction {
        val resourceId = Uuid.random()
        val now = Clock.System.now()
        val id = OnCallIncidents.insertAndGetId {
            it[OnCallIncidents.resourceId] = resourceId
            it[organizationId] = member.organizationId
            it[title] = "Checkout unavailable"
            it[description] = null
            it[severity] = if (status == NativeIncidentStatus.ACTIVE.wire) "SEV-2" else null
            it[OnCallIncidents.status] = status
            it[mode] = "LIVE"
            it[visibility] = "ORGANIZATION"
            it[declarationSnapshot] = emptyMap<String, JsonElement>()
            it[version] = 1
            it[declaredBy] = member.userId
            it[declaredAt] = now
            it[acceptedAt] = now.takeIf { status == NativeIncidentStatus.ACTIVE.wire }
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        SeededIncident(id, resourceId)
    }

    private fun seedAlert(): Int = transaction {
        val now = Clock.System.now()
        OnCallAlerts.insertAndGetId {
            it[resourceId] = Uuid.random()
            it[organizationId] = member.organizationId
            it[escalationPolicyId] = policy.id
            it[title] = "Incident response page"
            it[description] = null
            it[priority] = "P1"
            it[status] = "TRIGGERED"
            it[alertSource] = "incident-response"
            it[deduplicationKey] = null
            it[currentStep] = 0
            it[repeatIteration] = 0
            it[triggeredAt] = now
            it[acknowledgedAt] = null
            it[acknowledgedBy] = null
            it[resolvedAt] = null
            it[resolvedBy] = null
            it[metadata] = emptyMap()
            it[createdAt] = now
            it[updatedAt] = now
        }.value
    }

    private fun seedCommanderRole() = transaction {
        NativeIncidentRoleDefinitions.insert {
            it[resourceId] = Uuid.random()
            it[organizationId] = member.organizationId
            it[stableKey] = "incident-commander"
            it[version] = 1
            it[isCurrent] = true
            it[name] = "Incident Commander"
            it[description] = null
            it[responsibilities] = "Coordinate the response"
            it[privateInstructions] = null
            it[isRequired] = true
            it[isDefault] = true
            it[createdBy] = member.userId
            it[createdAt] = Clock.System.now()
            it[supersededAt] = null
        }
    }

    private fun event(
        eventType: String,
        status: String,
        sourceIncident: SeededIncident = incident,
    ) = NativeIncidentDomainEvent(
        id = 1,
        resourceId = Uuid.random().toString(),
        organizationId = member.organizationId,
        incidentId = sourceIncident.id,
        eventType = eventType,
        aggregateVersion = 1,
        idempotencyKey = "incident-event-$eventType-${sourceIncident.id}",
        payload = mapOf(
            "title" to JsonPrimitive("Checkout unavailable"),
            "status" to JsonPrimitive(status),
            "severity" to JsonPrimitive("SEV-2"),
        ),
        createdAt = Clock.System.now().toString(),
    )
}

private data class SeededIncident(val id: Int, val resourceId: Uuid)

private data class SeededPolicy(val id: Int, val resourceId: Uuid)
