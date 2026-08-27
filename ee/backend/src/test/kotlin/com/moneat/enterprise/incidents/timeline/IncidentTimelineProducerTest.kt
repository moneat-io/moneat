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
import com.moneat.enterprise.oncall.models.OnCallIncidentTimeline
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class IncidentTimelineProducerTest {
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
    fun `records each integration category with source metadata`() {
        val incidentId = declareIncident()
        val occurredAt = Instant.parse("2026-08-25T00:00:00Z")
        val producer = IncidentTimelineProducer()

        producer.recordStatusPageChange(event(incidentId, "status-page-1", "STATUS_PAGE_UPDATED", occurredAt))
        producer.recordCallActivity(event(incidentId, "call-1", "CALL_STARTED", occurredAt))
        producer.recordDeployActivity(event(incidentId, "deploy-1", "DEPLOY_STARTED", occurredAt))
        producer.recordSlackActivity(event(incidentId, "slack-1", "SLACK_MESSAGE_POSTED", occurredAt))

        transaction {
            val rows = OnCallIncidentTimeline.selectAll().where {
                OnCallIncidentTimeline.incidentId eq incidentId
            }.toList().filter { it[OnCallIncidentTimeline.sourceType] != null }
            assertEquals(4, rows.size)
            assertEquals(
                setOf("STATUS_PAGE", "CALL", "DEPLOY", "SLACK_MESSAGE"),
                rows.mapNotNull { it[OnCallIncidentTimeline.sourceType] }.toSet(),
            )
            assertTrue(rows.all { it[OnCallIncidentTimeline.provenance] == "INTEGRATION" })
            assertTrue(rows.all { it[OnCallIncidentTimeline.originalOccurredAt] == occurredAt })
        }
    }

    @Test
    fun `reuses the existing row for a duplicate upstream event key`() {
        val incidentId = declareIncident()
        val event = event(incidentId, "deploy-duplicate", "DEPLOY_FINISHED", Instant.parse("2026-08-25T00:02:00Z"))
        val producer = IncidentTimelineProducer()

        val firstId = producer.recordDeployActivity(event)
        val secondId = producer.recordDeployActivity(event.copy(observedAt = Instant.parse("2026-08-25T00:03:00Z")))

        assertEquals(firstId, secondId)
        transaction {
            assertEquals(
                1,
                OnCallIncidentTimeline.selectAll().where {
                    (OnCallIncidentTimeline.incidentId eq incidentId) and
                        (OnCallIncidentTimeline.eventKey eq "deploy-duplicate")
                }.count(),
            )
        }
    }

    @Test
    fun `keeps the original occurrence time when an event arrives late`() {
        val incidentId = declareIncident()
        val occurredAt = Instant.parse("2026-08-25T00:04:00Z")
        val observedAt = Instant.parse("2026-08-25T00:09:00Z")

        IncidentTimelineProducer().recordCallActivity(
            event(incidentId, "call-late", "CALL_ENDED", occurredAt).copy(observedAt = observedAt),
        )

        transaction {
            val row = OnCallIncidentTimeline.selectAll().where {
                (OnCallIncidentTimeline.incidentId eq incidentId) and
                    (OnCallIncidentTimeline.eventKey eq "call-late")
            }.single()
            assertEquals(occurredAt, row[OnCallIncidentTimeline.originalOccurredAt])
            assertEquals(observedAt, row[OnCallIncidentTimeline.observedAt])
            assertEquals(observedAt, row[OnCallIncidentTimeline.createdAt])
        }
    }

    private fun event(incidentId: Int, key: String, type: String, occurredAt: Instant) =
        IncidentTimelineProducerEvent(
            organizationId = member.organizationId,
            incidentId = incidentId,
            eventKey = key,
            eventType = type,
            originalOccurredAt = occurredAt,
            details = mapOf("source" to JsonPrimitive("integration-test")),
            actorUserId = member.userId,
            sourceReference = key,
        )

    private fun declareIncident(): Int {
        return IncidentCommandService(policy = IncidentCommandPolicy.allowForTests()).execute(
            DeclareIncidentCommand(
                commandKey = "declare-producer-${System.nanoTime()}",
                actor = IncidentCommandActor(member.organizationId, member.userId, "REST"),
                title = "Producer test incident",
                description = null,
                severity = "SEV-2",
            ),
        ).incidentId
    }
}
