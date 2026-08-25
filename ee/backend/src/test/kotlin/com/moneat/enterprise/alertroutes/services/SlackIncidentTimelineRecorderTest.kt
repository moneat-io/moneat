// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.enterprise.incidents.IncidentTestDatabase
import com.moneat.enterprise.incidents.SeededMember
import com.moneat.enterprise.incidents.commands.DeclareIncidentCommand
import com.moneat.enterprise.incidents.commands.IncidentCommandActor
import com.moneat.enterprise.incidents.commands.IncidentCommandPolicy
import com.moneat.enterprise.incidents.commands.IncidentCommandService
import com.moneat.enterprise.incidents.models.IncidentTimelineProvenance
import com.moneat.enterprise.oncall.models.OnCallIncidentTimeline
import com.moneat.enterprise.oncall.models.OnCallIncidents
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

class SlackIncidentTimelineRecorderTest {
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
    fun `records a source-linked Slack action and deduplicates delivery retries`() {
        val incident = IncidentCommandService(policy = IncidentCommandPolicy.allowForTests()).execute(
            DeclareIncidentCommand(
                commandKey = "declare-slack-timeline",
                actor = IncidentCommandActor(member.organizationId, member.userId, "REST"),
                title = "Checkout unavailable",
                description = null,
                severity = "SEV-2",
            ),
        )
        val occurredAt = Instant.parse("2026-08-25T00:00:00Z")
        val event = SlackIncidentTimelineRecord(
            organizationId = member.organizationId,
            incidentId = incident.incidentId,
            actorUserId = member.userId,
            alertEpisodeId = 42,
            onCallAlertId = 84,
            eventType = "SLACK_ALERT_ACKNOWLEDGED",
            deliveryId = "slack-delivery-1",
            occurredAt = occurredAt,
        )

        SlackIncidentTimelineRecorder().record(event)
        SlackIncidentTimelineRecorder().record(event)

        transaction {
            val timeline = OnCallIncidentTimeline.selectAll().where {
                (OnCallIncidentTimeline.organizationId eq member.organizationId) and
                    (OnCallIncidentTimeline.incidentId eq incident.incidentId)
            }.toList()
            val slackEntries = timeline.filter { it[OnCallIncidentTimeline.eventType] == event.eventType }
            assertEquals(1, slackEntries.size)
            val entry = slackEntries.single()
            assertEquals(IncidentTimelineProvenance.SLACK.wire, entry[OnCallIncidentTimeline.provenance])
            assertEquals("SLACK_MESSAGE", entry[OnCallIncidentTimeline.sourceType])
            assertEquals(event.deliveryId, entry[OnCallIncidentTimeline.sourceReference])
            assertEquals(occurredAt, entry[OnCallIncidentTimeline.originalOccurredAt])
            assertNotNull(entry[OnCallIncidentTimeline.actorUserId])
            assertEquals(incident.incidentId, entry[OnCallIncidentTimeline.incidentId])
            assertEquals(1, OnCallIncidents.selectAll().single()[OnCallIncidents.version])
        }
    }
}
