// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.announcements

import com.moneat.enterprise.incidents.IncidentTestDatabase
import com.moneat.enterprise.incidents.SeededMember
import com.moneat.enterprise.incidents.events.NativeIncidentDomainEvent
import com.moneat.enterprise.incidents.models.NativeIncidentRoleAssignments
import com.moneat.enterprise.incidents.models.NativeIncidentRoleDefinitions
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.incidents.response.NativeIncidentResponseActivations
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.notifications.services.SlackOutboundEnqueueRequest
import com.moneat.shared.models.OrganizationIntegrations
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class IncidentAnnouncementServiceTest {
    private lateinit var member: SeededMember
    private lateinit var incidentResourceId: Uuid
    private val now = Instant.parse("2026-08-25T00:00:00Z")

    @BeforeEach
    fun setUp() {
        IncidentTestDatabase.reset()
        member = IncidentTestDatabase.seedMember("announcement-org")
        incidentResourceId = seedIncident(NativeIncidentStatus.ACTIVE.wire)
        transaction {
            OrganizationIntegrations.insert {
                it[organization_id] = member.organizationId
                it[integration_type] = "slack"
                it[access_token] = "token"
                it[team_id] = "T-announcements"
                it[channel_id] = "C-announcements"
                it[enabled] = true
                it[created_at] = now
                it[updated_at] = now
            }
        }
    }

    @AfterEach
    fun tearDown() {
        IncidentTestDatabase.clearReference()
    }

    @Test
    fun `active incident creates one stable card and suppresses same version replay`() = runBlocking {
        val requests = mutableListOf<SlackOutboundEnqueueRequest>()
        val service = IncidentAnnouncementService(
            enqueue = { request -> requests += request; Uuid.random().toString() },
        )
        val event = event("INCIDENT_DECLARE", 1, NativeIncidentStatus.ACTIVE.wire)

        service.consume(event, "delivery-1")
        service.consume(event, "delivery-1")

        assertEquals(1, requests.size)
        assertEquals("MESSAGE", requests.single().operation.wire)
        assertTrue(requests.single().payload.contains("incident_accept"))
        assertTrue(requests.single().payload.contains("Incident summary"))
        assertEquals(1, transaction { NativeIncidentAnnouncements.selectAll().count() })
    }

    @Test
    fun `announcement card includes assigned roles and escalation status`() = runBlocking {
        val incidentId = transaction {
            OnCallIncidents
                .selectAll()
                .where { OnCallIncidents.resourceId eq incidentResourceId }
                .single()[OnCallIncidents.id]
        }
        transaction {
            val roleId = NativeIncidentRoleDefinitions.insertAndGetId {
                it[organizationId] = member.organizationId
                it[stableKey] = "incident-commander"
                it[version] = 1
                it[isCurrent] = true
                it[name] = "Incident Commander"
                it[description] = null
                it[responsibilities] = "Coordinate response"
                it[privateInstructions] = null
                it[isRequired] = true
                it[isDefault] = true
                it[createdBy] = member.userId
                it[createdAt] = now
                it[supersededAt] = null
            }
            NativeIncidentRoleAssignments.insert {
                it[organizationId] = member.organizationId
                it[NativeIncidentRoleAssignments.incidentId] = incidentId.value
                it[roleDefinitionId] = roleId.value
                it[assigneeUserId] = member.userId
                it[assignedBy] = member.userId
                it[assignedAt] = now
                it[endedBy] = null
                it[endedAt] = null
                it[endReason] = null
            }
            NativeIncidentResponseActivations.insert {
                it[organizationId] = member.organizationId
                it[NativeIncidentResponseActivations.incidentId] = incidentId.value
                it[activationRevision] = 1
                it[trigger] = "INCIDENT_CREATED"
                it[status] = "ACTIVE"
                it[desiredCount] = 1
                it[attemptedCount] = 1
                it[acknowledgedCount] = 1
                it[lastError] = null
                it[startedAt] = now
                it[completedAt] = null
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        val requests = mutableListOf<SlackOutboundEnqueueRequest>()
        val service = IncidentAnnouncementService(
            enqueue = { request -> requests += request; Uuid.random().toString() },
        )

        service.consume(event("INCIDENT_DECLARE", 1, NativeIncidentStatus.ACTIVE.wire), "role-overview")

        val payload = requests.single().payload
        assertTrue(payload.contains("Incident Commander"))
        assertTrue(payload.contains("1/1 acknowledged"))
    }

    @Test
    fun `triage card waits for acceptance unless rule opts in`() = runBlocking {
        incidentResourceId = seedIncident(NativeIncidentStatus.TRIAGE.wire)
        val requests = mutableListOf<SlackOutboundEnqueueRequest>()
        val service = IncidentAnnouncementService(
            enqueue = { request -> requests += request; Uuid.random().toString() },
        )

        service.consume(event("INCIDENT_DECLARE", 1, NativeIncidentStatus.TRIAGE.wire), "triage-declare")
        assertEquals(0, requests.size)

        transaction {
            OnCallIncidents.update({ OnCallIncidents.resourceId eq incidentResourceId }) {
                it[OnCallIncidents.status] = NativeIncidentStatus.ACTIVE.wire
                it[OnCallIncidents.severity] = "SEV-1"
                it[OnCallIncidents.version] = 2
            }
        }
        service.consume(event("INCIDENT_ACCEPT", 2, NativeIncidentStatus.ACTIVE.wire), "triage-accept")
        assertEquals(1, requests.size)
    }

    @Test
    fun `conditions filter severity service and fields`() {
        val conditions = IncidentAnnouncementRuleConditions(
            severities = setOf("SEV-1"),
            services = setOf("payments"),
            fields = mapOf("environment" to setOf("production")),
        )
        val matching = IncidentAnnouncementContext(
            incidentType = null,
            severity = "SEV-1",
            service = "payments",
            team = null,
            fields = mapOf("environment" to "production"),
            visibility = "ORGANIZATION",
            mode = "LIVE",
            status = "ACTIVE",
        )
        assertTrue(conditions.matches(matching))
        assertEquals(false, conditions.matches(matching.copy(severity = "SEV-3")))
    }

    @Test
    fun `announcement card includes configured actions links and response nudges`() = runBlocking {
        val requests = mutableListOf<SlackOutboundEnqueueRequest>()
        val service = IncidentAnnouncementService(
            enqueue = { request -> requests += request; Uuid.random().toString() },
        )
        service.createRule(
            organizationId = member.organizationId,
            actorUserId = member.userId,
            request = CreateIncidentAnnouncementRule(
                name = "Incident response overview",
                teamId = "T-announcements",
                channelId = "C-announcements",
                enabled = true,
                announceTriage = false,
                allowPrivate = false,
                allowTest = false,
                conditions = IncidentAnnouncementRuleConditions(
                    quickActions = listOf(
                        IncidentAnnouncementQuickAction(
                            label = "Add update",
                            actionId = "incident_update",
                        ),
                    ),
                    links = listOf(IncidentAnnouncementLink("Runbook", "https://runbooks.example.test/incident")),
                ),
            ),
        )

        service.consume(event("INCIDENT_DECLARE", 1, NativeIncidentStatus.ACTIVE.wire), "overview-card")

        val payload = requests.single().payload
        assertTrue(payload.contains("Add update"))
        assertTrue(payload.contains("Runbook"))
        assertTrue(payload.contains("Response nudges"))
        assertTrue(payload.contains("Assign an incident lead"))
        assertTrue(payload.contains("Activate the response escalation"))
    }

    @Test
    fun `announcement card lists every nudge for an incomplete triage incident`() = runBlocking {
        incidentResourceId = seedIncident(NativeIncidentStatus.TRIAGE.wire)
        transaction {
            OnCallIncidents.update({ OnCallIncidents.resourceId eq incidentResourceId }) {
                it[OnCallIncidents.description] = null
                it[OnCallIncidents.summary] = null
                it[OnCallIncidents.declarationSnapshot] = emptyMap()
            }
        }
        val requests = mutableListOf<SlackOutboundEnqueueRequest>()
        val service = IncidentAnnouncementService(
            enqueue = { request -> requests += request; Uuid.random().toString() },
        )
        service.createRule(
            organizationId = member.organizationId,
            actorUserId = member.userId,
            request = CreateIncidentAnnouncementRule(
                name = "Triage response overview",
                teamId = "T-announcements",
                channelId = "C-announcements",
                enabled = true,
                announceTriage = true,
                allowPrivate = false,
                allowTest = false,
                conditions = IncidentAnnouncementRuleConditions(
                    nudges = IncidentAnnouncementNudgePolicy(
                        enabled = true,
                        missingLead = true,
                        missingSummary = true,
                        missingUpdate = true,
                        missingStatusPage = true,
                        missingTriageDecision = true,
                        missingEscalation = true,
                        missingClosure = true,
                    ),
                ),
            ),
        )

        service.consume(event("INCIDENT_DECLARE", 1, NativeIncidentStatus.TRIAGE.wire), "triage-overview")

        val payload = requests.single().payload
        assertTrue(payload.contains("Assign an incident lead"))
        assertTrue(payload.contains("Add an incident summary"))
        assertTrue(payload.contains("Set the next update time"))
        assertTrue(payload.contains("Publish a status page update"))
        assertTrue(payload.contains("Make the triage decision"))
        assertTrue(payload.contains("Activate the response escalation"))
        assertTrue(payload.contains("Keep the closure checklist current"))
    }

    @Test
    fun `announcement rules reject duplicate and reserved action IDs`() = runBlocking {
        val service = IncidentAnnouncementService(enqueue = { Uuid.random().toString() })
        val request = CreateIncidentAnnouncementRule(
            name = "Validated actions",
            teamId = "T-announcements",
            channelId = "C-announcements",
            enabled = true,
            announceTriage = false,
            allowPrivate = false,
            allowTest = false,
            conditions = IncidentAnnouncementRuleConditions(
                quickActions = listOf(
                    IncidentAnnouncementQuickAction("First", "incident_update"),
                    IncidentAnnouncementQuickAction("Second", "incident_update"),
                ),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            service.createRule(member.organizationId, member.userId, request)
        }
        assertFailsWith<IllegalArgumentException> {
            service.createRule(
                member.organizationId,
                member.userId,
                request.copy(
                    conditions = request.conditions.copy(
                        quickActions = listOf(IncidentAnnouncementQuickAction("Reserved", "incident_accept:custom")),
                    ),
                ),
            )
        }
        Unit
    }

    @Test
    fun `announcement rules reject invalid presentation limits`() = runBlocking {
        val service = IncidentAnnouncementService(enqueue = { Uuid.random().toString() })
        val baseConditions = IncidentAnnouncementRuleConditions()
        val request = CreateIncidentAnnouncementRule(
            name = "Invalid presentation",
            teamId = "T-announcements",
            channelId = "C-announcements",
            enabled = true,
            announceTriage = false,
            allowPrivate = false,
            allowTest = false,
            conditions = baseConditions,
        )

        fun reject(conditions: IncidentAnnouncementRuleConditions) {
            assertFailsWith<IllegalArgumentException> {
                service.createRule(member.organizationId, member.userId, request.copy(conditions = conditions))
            }
        }

        reject(
            baseConditions.copy(
                quickActions = List(6) { IncidentAnnouncementQuickAction("Action", "action_$it") },
            ),
        )
        reject(
            baseConditions.copy(
                links = List(6) { IncidentAnnouncementLink("Runbook", "https://example.test/$it") },
            ),
        )
        reject(baseConditions.copy(quickActions = listOf(IncidentAnnouncementQuickAction("", "action"))))
        reject(baseConditions.copy(quickActions = listOf(IncidentAnnouncementQuickAction("Action", "ACTION"))))
        reject(
            baseConditions.copy(
                quickActions = listOf(IncidentAnnouncementQuickAction("Action", "action", "x".repeat(2_001))),
            ),
        )
        reject(baseConditions.copy(links = listOf(IncidentAnnouncementLink("", "https://example.test"))))
        reject(baseConditions.copy(links = listOf(IncidentAnnouncementLink("Runbook", "ftp://example.test"))))
        Unit
    }

    private fun seedIncident(status: String): Uuid = transaction {
        val resourceId = Uuid.random()
        OnCallIncidents.insert {
            it[OnCallIncidents.resourceId] = resourceId
            it[organizationId] = member.organizationId
            it[title] = "Database unavailable"
            it[description] = "Incident summary"
            it[severity] = if (status == NativeIncidentStatus.TRIAGE.wire) null else "SEV-1"
            it[OnCallIncidents.status] = status
            it[mode] = "LIVE"
            it[visibility] = "ORGANIZATION"
            it[incidentType] = "Outage"
            it[declarationSnapshot] = mapOf("service" to JsonPrimitive("payments"))
            it[version] = 1
            it[declaredBy] = member.userId
            it[declaredAt] = now
            it[createdAt] = now
            it[updatedAt] = now
        }
        resourceId
    }

    private fun event(type: String, version: Int, status: String) = NativeIncidentDomainEvent(
        id = version,
        resourceId = Uuid.random().toString(),
        organizationId = member.organizationId,
        incidentId = transaction {
            OnCallIncidents
                .selectAll()
                .where { OnCallIncidents.resourceId eq incidentResourceId }
                .single()[OnCallIncidents.id]
                .value
        },
        eventType = type,
        aggregateVersion = version,
        idempotencyKey = "event-$version",
        payload = mapOf(
            "incidentId" to JsonPrimitive(incidentResourceId.toString()),
            "status" to JsonPrimitive(status),
        ),
        createdAt = now.toString(),
    )
}
