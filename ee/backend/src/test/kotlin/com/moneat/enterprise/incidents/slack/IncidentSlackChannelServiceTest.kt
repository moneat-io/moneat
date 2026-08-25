// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.slack

import com.moneat.enterprise.incidents.IncidentTestDatabase
import com.moneat.enterprise.incidents.events.NativeIncidentDomainEvent
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.shared.models.OrganizationIntegrations
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class IncidentSlackChannelServiceTest {
    private lateinit var organization: com.moneat.enterprise.incidents.SeededMember
    private lateinit var incidentResourceId: Uuid
    private val now = Instant.parse("2026-08-25T00:00:00Z")

    @BeforeTest
    fun setUp() {
        IncidentTestDatabase.reset()
        organization = IncidentTestDatabase.seedMember("slack-channel-org")
        incidentResourceId = seedIncident(mode = "LIVE", status = NativeIncidentStatus.TRIAGE.wire)
    }

    @AfterTest
    fun tearDown() {
        IncidentTestDatabase.clearReference()
    }

    @Test
    fun `live declaration creates one durable channel request per workspace`() {
        transaction {
            OrganizationIntegrations.insert {
                it[organization_id] = organization.organizationId
                it[integration_type] = "slack"
                it[access_token] = "token"
                it[team_id] = "T-channel"
                it[enabled] = true
                it[created_at] = now
                it[updated_at] = now
            }
        }
        val requests = mutableListOf<com.moneat.notifications.services.SlackOutboundEnqueueRequest>()
        val service = IncidentSlackChannelService(
            enqueue = { request ->
                requests += request
                Uuid.random().toString()
            },
        )
        val event = event("INCIDENT_DECLARE", NativeIncidentStatus.TRIAGE.wire)

        service.provision(event)
        service.provision(event)

        val row = transaction { NativeIncidentSlackChannels.selectAll().single() }
        assertEquals(IncidentSlackChannelState.PROVISIONING.wire, row[NativeIncidentSlackChannels.state])
        assertEquals("T-channel", row[NativeIncidentSlackChannels.teamId])
        assertEquals(1, requests.size)
        assertEquals("CHANNEL_CREATE", requests.single().operation.wire)
        assertTrue(requests.single().payload.contains("inc-supply-chain-degraded"))
    }

    @Test
    fun `retrospective incidents remain channelless`() {
        incidentResourceId = seedIncident(mode = "RETROSPECTIVE", status = NativeIncidentStatus.ACTIVE.wire)
        transaction {
            OrganizationIntegrations.insert {
                it[organization_id] = organization.organizationId
                it[integration_type] = "slack"
                it[access_token] = "token"
                it[team_id] = "T-channel"
                it[enabled] = true
                it[created_at] = now
                it[updated_at] = now
            }
        }
        val requests = mutableListOf<com.moneat.notifications.services.SlackOutboundEnqueueRequest>()
        IncidentSlackChannelService(enqueue = { request -> requests += request; Uuid.random().toString() })
            .provision(event("INCIDENT_DECLARE", NativeIncidentStatus.ACTIVE.wire))

        assertEquals(0, requests.size)
        assertEquals(0, transaction { NativeIncidentSlackChannels.selectAll().count() })
    }

    @Test
    fun `missing workspace records visible failure without throwing`() {
        IncidentSlackChannelService(enqueue = { error("queue should not be called") })
            .provision(event("INCIDENT_DECLARE", NativeIncidentStatus.TRIAGE.wire))

        val row = transaction { NativeIncidentSlackChannels.selectAll().single() }
        assertEquals(IncidentSlackChannelState.FAILED.wire, row[NativeIncidentSlackChannels.state])
        assertTrue(row[NativeIncidentSlackChannels.lastError]!!.contains("workspace"))
    }

    private fun seedIncident(mode: String, status: String): Uuid = transaction {
        val resourceId = Uuid.random()
        OnCallIncidents.insert {
            it[OnCallIncidents.resourceId] = resourceId
            it[organizationId] = organization.organizationId
            it[title] = "Supply chain degraded"
            it[description] = "Incident description"
            it[severity] = "SEV-2"
            it[OnCallIncidents.status] = status
            it[OnCallIncidents.mode] = mode
            it[visibility] = "ORGANIZATION"
            it[declarationSnapshot] = emptyMap()
            it[version] = 1
            it[declaredBy] = organization.userId
            it[declaredAt] = now
            it[triagedAt] = now
            it[acceptedAt] = null
            it[resolvedBy] = null
            it[resolvedAt] = null
            it[postIncidentAt] = null
            it[closedAt] = null
            it[cancelledAt] = null
            it[declinedAt] = null
            it[mergedAt] = null
            it[mergedIntoIncidentId] = null
            it[createdAt] = now
            it[updatedAt] = now
        }
        resourceId
    }

    private fun event(eventType: String, status: String) = NativeIncidentDomainEvent(
        id = 1,
        resourceId = Uuid.random().toString(),
        organizationId = organization.organizationId,
        incidentId = 1,
        eventType = eventType,
        aggregateVersion = 1,
        idempotencyKey = "incident-channel-$eventType",
        payload = mapOf(
            "incidentId" to JsonPrimitive(incidentResourceId.toString()),
            "title" to JsonPrimitive("Supply chain degraded"),
            "status" to JsonPrimitive(status),
        ),
        createdAt = now.toString(),
    )
}
