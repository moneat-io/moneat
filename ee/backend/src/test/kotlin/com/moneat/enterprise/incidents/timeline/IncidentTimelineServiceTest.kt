// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.timeline

import com.moneat.enterprise.incidents.IncidentTestDatabase
import com.moneat.enterprise.incidents.SeededMember
import com.moneat.enterprise.incidents.commands.AddIncidentTimelineEventCommand
import com.moneat.enterprise.incidents.commands.DeclareIncidentCommand
import com.moneat.enterprise.incidents.commands.IncidentCommandActor
import com.moneat.enterprise.incidents.commands.IncidentCommandPolicy
import com.moneat.enterprise.incidents.commands.IncidentCommandService
import com.moneat.enterprise.incidents.models.IncidentTimelineProvenance
import com.moneat.enterprise.incidents.models.IncidentTimelineVisibility
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IncidentTimelineServiceTest {
    private lateinit var member: SeededMember
    private lateinit var commandService: IncidentCommandService
    private lateinit var service: IncidentTimelineService

    @BeforeEach
    fun setUp() {
        IncidentTestDatabase.reset()
        member = IncidentTestDatabase.seedMember()
        commandService = IncidentCommandService(policy = IncidentCommandPolicy.allowForTests())
        service = IncidentTimelineService()
    }

    @AfterEach
    fun tearDown() {
        IncidentTestDatabase.clearReference()
    }

    @Test
    fun `preserves evidence while editing reordering deleting and restoring events`() {
        val incident =
            commandService.execute(
                DeclareIncidentCommand(
                    commandKey = "declare-timeline-test",
                    actor = actor(),
                    title = "Search unavailable",
                    description = null,
                    severity = "SEV-2",
                ),
            )
        commandService.execute(
            AddIncidentTimelineEventCommand(
                commandKey = "evidence-timeline-test",
                actor = actor(),
                incidentId = incident.incidentId,
                eventType = "EVIDENCE_ADDED",
                details = mapOf("message" to JsonPrimitive("Error rate crossed 20%")),
                expectedVersion = 1,
            ),
        )

        val original = service.list(member.organizationId, incident.incidentId)
        assertEquals(2, original.size)
        assertTrue(original.all { it.provenance == IncidentTimelineProvenance.REST })
        val declared = original.single { it.eventType == "DECLARED" }
        val evidence = original.single { it.eventType == "EVIDENCE_ADDED" }

        val annotated =
            service.annotate(
                member.organizationId,
                incident.incidentId,
                evidence.id,
                member.userId,
                AnnotateIncidentTimelineEvent("Confirmed from metrics", "Clarify evidence"),
            )
        assertEquals("Confirmed from metrics", annotated.annotation)
        assertEquals(evidence.eventKey, annotated.eventKey)
        assertEquals(evidence.originalOccurredAt, annotated.originalOccurredAt)

        val edited =
            service.edit(
                member.organizationId,
                incident.incidentId,
                evidence.id,
                member.userId,
                EditIncidentTimelineEvent(
                    details = mapOf("message" to JsonPrimitive("Error rate crossed 25%")),
                    visibility = IncidentTimelineVisibility.PARTICIPANTS,
                    reason = "Correct the observed value",
                ),
            )
        assertEquals(JsonPrimitive("Error rate crossed 25%"), edited.details["message"])
        assertEquals(IncidentTimelineVisibility.PARTICIPANTS, edited.visibility)

        val reordered =
            service.reorder(
                member.organizationId,
                incident.incidentId,
                member.userId,
                listOf(evidence.id, declared.id),
                "Promote the key evidence",
            )
        assertEquals(listOf(evidence.id, declared.id), reordered.map { it.id })

        service.delete(
            member.organizationId,
            incident.incidentId,
            declared.id,
            member.userId,
            "Hide setup noise",
        )
        assertEquals(listOf(evidence.id), service.list(member.organizationId, incident.incidentId).map { it.id })
        assertNotNull(
            service.list(
                member.organizationId,
                incident.incidentId,
                IncidentTimelineFilters(includeDeleted = true),
            ).single { it.id == declared.id }.deletedAt,
        )

        service.restore(
            member.organizationId,
            incident.incidentId,
            declared.id,
            member.userId,
            "Restore complete record",
        )
        assertEquals(2, service.list(member.organizationId, incident.incidentId).size)
        assertEquals(
            listOf("ANNOTATE", "EDIT", "REORDER"),
            service.revisions(member.organizationId, incident.incidentId, evidence.id).map { it.action },
        )
        assertEquals(
            listOf("REORDER", "DELETE", "RESTORE"),
            service.revisions(member.organizationId, incident.incidentId, declared.id).map { it.action },
        )
        assertEquals(2, service.export(member.organizationId, incident.incidentId).events.size)
    }

    @Test
    fun `filters canonical events by provenance visibility and type`() {
        val incident =
            commandService.execute(
                DeclareIncidentCommand(
                    commandKey = "declare-filter-test",
                    actor = actor(),
                    title = "API degraded",
                    description = null,
                    severity = "SEV-3",
                ),
            )
        val filtered =
            service.list(
                member.organizationId,
                incident.incidentId,
                IncidentTimelineFilters(
                    eventTypes = listOf("declared"),
                    provenance = listOf(IncidentTimelineProvenance.REST),
                    visibility = listOf(IncidentTimelineVisibility.ORGANIZATION),
                ),
            )
        assertEquals(listOf("DECLARED"), filtered.map { it.eventType })
    }

    private fun actor() = IncidentCommandActor(member.organizationId, member.userId, "REST")
}
