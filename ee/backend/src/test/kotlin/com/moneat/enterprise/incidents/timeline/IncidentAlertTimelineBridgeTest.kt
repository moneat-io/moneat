// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.timeline

import com.moneat.enterprise.incidents.IncidentTestDatabase
import com.moneat.enterprise.incidents.SeededMember
import com.moneat.enterprise.incidents.commands.DeclareIncidentCommand
import com.moneat.enterprise.incidents.commands.IncidentCommandActor
import com.moneat.enterprise.incidents.commands.IncidentCommandPolicy
import com.moneat.enterprise.incidents.commands.IncidentCommandService
import com.moneat.enterprise.incidents.commands.LinkOnCallAlertCommand
import com.moneat.enterprise.oncall.models.OnCallAlertTimeline
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidentTimeline
import com.moneat.enterprise.oncall.models.EscalationPolicyVersions
import com.moneat.enterprise.oncall.services.EscalationPathService
import com.moneat.shared.models.EscalationPolicies
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq

class IncidentAlertTimelineBridgeTest {
    private lateinit var member: SeededMember

    @BeforeEach
    fun setUp() {
        IncidentTestDatabase.reset()
        member = IncidentTestDatabase.seedMember()
    }

    @AfterEach
    fun tearDown() {
        IncidentTestDatabase.clearReference()
    }

    @Test
    fun `backfills legacy alert events once when an alert is linked`() {
        val alertId = seedAlert()
        val occurredAt = Instant.parse("2026-08-25T00:00:00Z")
        seedAlertTimeline(alertId, "ACKNOWLEDGED", occurredAt)
        val incident = declareIncident()
        val service = IncidentCommandService(policy = IncidentCommandPolicy.allowForTests())

        service.execute(
            LinkOnCallAlertCommand(
                commandKey = "link-alert-timeline",
                actor = actor(),
                incidentId = incident,
                alertId = alertId,
                expectedVersion = 1,
            ),
        )

        val bridge = IncidentAlertTimelineBridge(clock = FixedClock(Instant.parse("2026-08-25T00:01:00Z")))
        assertEquals(1, bridge.backfill(incident, alertId))
        assertEquals(1, bridge.backfill(incident, alertId))
        transaction {
            val entries = OnCallIncidentTimeline.selectAll().where {
                (OnCallIncidentTimeline.incidentId eq incident) and
                    (OnCallIncidentTimeline.eventType eq "ALERT_ACKNOWLEDGED")
            }.toList()
            assertEquals(1, entries.size)
            assertEquals(occurredAt, entries.single()[OnCallIncidentTimeline.originalOccurredAt])
            assertEquals("INTEGRATION", entries.single()[OnCallIncidentTimeline.provenance])
            assertTrue(entries.single()[OnCallIncidentTimeline.eventKey].startsWith("on-call-alert:"))
        }
    }

    @Test
    fun `records new alert events for an already linked incident`() {
        val alertId = seedAlert()
        val incident = declareIncident()
        val service = IncidentCommandService(policy = IncidentCommandPolicy.allowForTests())
        service.execute(
            LinkOnCallAlertCommand(
                commandKey = "link-new-alert-event",
                actor = actor(),
                incidentId = incident,
                alertId = alertId,
                expectedVersion = 1,
            ),
        )
        val timelineId = seedAlertTimeline(
            alertId,
            "RESOLVED",
            Instant.parse("2026-08-25T00:02:00Z"),
        )

        assertTrue(IncidentAlertTimelineBridge().recordForAlertTimeline(timelineId))
        transaction {
            assertEquals(
                1,
                OnCallIncidentTimeline.selectAll().where {
                    (OnCallIncidentTimeline.incidentId eq incident) and
                        (OnCallIncidentTimeline.eventType eq "ALERT_RESOLVED")
                }.count(),
            )
        }
    }

    @Test
    fun `records escalation events for an already linked incident`() {
        val alertId = seedAlert()
        val incident = declareIncident()
        val service = IncidentCommandService(policy = IncidentCommandPolicy.allowForTests())
        service.execute(
            LinkOnCallAlertCommand(
                commandKey = "link-escalation-event",
                actor = actor(),
                incidentId = incident,
                alertId = alertId,
                expectedVersion = 1,
            ),
        )

        EscalationPathService().createExecution(member.organizationId, alertId, seedPolicyVersion(), "root")

        transaction {
            val entries = OnCallIncidentTimeline.selectAll().where {
                (OnCallIncidentTimeline.incidentId eq incident) and
                    (OnCallIncidentTimeline.eventType eq "ESCALATION_STARTED")
            }.toList()
            assertEquals(1, entries.size)
            assertEquals("INTEGRATION", entries.single()[OnCallIncidentTimeline.provenance])
            assertTrue(entries.single()[OnCallIncidentTimeline.eventKey].startsWith("on-call-escalation:"))
        }
    }

    private fun actor() = IncidentCommandActor(member.organizationId, member.userId, "REST")

    private fun declareIncident(): Int =
        IncidentCommandService(policy = IncidentCommandPolicy.allowForTests()).execute(
            DeclareIncidentCommand(
                commandKey = "declare-alert-timeline",
                actor = actor(),
                title = "Checkout unavailable",
                description = null,
                severity = "SEV-2",
            ),
        ).incidentId

    private fun seedAlert(): Int = transaction {
        val now = Clock.System.now()
        OnCallAlerts.insertAndGetId {
            it[organizationId] = member.organizationId
            it[declaredIncidentId] = null
            it[escalationPolicyId] = null
            it[title] = "Checkout alert"
            it[description] = null
            it[priority] = "P1"
            it[status] = "TRIGGERED"
            it[alertSource] = "TEST"
            it[deduplicationKey] = "alert-timeline"
            it[currentStep] = 0
            it[repeatIteration] = 0
            it[triggeredAt] = now
            it[acknowledgedAt] = null
            it[acknowledgedBy] = null
            it[resolvedAt] = null
            it[resolvedBy] = null
            it[metadata] = null
            it[createdAt] = now
            it[updatedAt] = now
        }.value
    }

    private fun seedPolicyVersion(): Int = transaction {
        val now = Clock.System.now()
        val policyId = EscalationPolicies.insertAndGetId {
            it[organizationId] = member.organizationId
            it[name] = "Incident escalation"
            it[description] = null
            it[repeatCount] = 1
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        EscalationPolicyVersions.insertAndGetId {
            it[organizationId] = member.organizationId
            it[escalationPolicyId] = policyId
            it[version] = 1
            it[status] = "PUBLISHED"
            it[path] = emptyMap()
            it[createdBy] = member.userId
            it[createdAt] = now
            it[publishedAt] = now
        }.value
    }

    private fun seedAlertTimeline(alertId: Int, eventType: String, occurredAt: Instant): Int = transaction {
        OnCallAlertTimeline.insertAndGetId {
            it[OnCallAlertTimeline.alertId] = alertId
            it[OnCallAlertTimeline.eventType] = eventType
            it[OnCallAlertTimeline.actorUserId] = member.userId
            it[OnCallAlertTimeline.details] = mapOf("channel" to JsonPrimitive("push"))
            it[OnCallAlertTimeline.createdAt] = occurredAt
        }.value
    }

    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }
}
