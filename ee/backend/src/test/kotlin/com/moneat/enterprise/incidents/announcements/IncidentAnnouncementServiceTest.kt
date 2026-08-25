// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.announcements

import com.moneat.enterprise.incidents.IncidentTestDatabase
import com.moneat.enterprise.incidents.SeededMember
import com.moneat.enterprise.incidents.events.NativeIncidentDomainEvent
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.notifications.services.SlackOutboundEnqueueRequest
import com.moneat.shared.models.OrganizationIntegrations
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
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
