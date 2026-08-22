// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.commands

import com.moneat.enterprise.incidents.IncidentTestDatabase
import com.moneat.enterprise.incidents.SeededMember
import com.moneat.enterprise.incidents.models.NativeIncidentCommands
import com.moneat.enterprise.incidents.models.NativeIncidentMode
import com.moneat.enterprise.incidents.models.NativeIncidentOutboxEvents
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.incidents.models.NativeIncidentVisibility
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidentAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidentTimeline
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class IncidentCommandServiceTest {
    private lateinit var member: SeededMember
    private lateinit var service: IncidentCommandService

    @BeforeEach
    fun setUp() {
        IncidentTestDatabase.reset()
        member = IncidentTestDatabase.seedMember()
        service = IncidentCommandService(policy = IncidentCommandPolicy.allowForTests())
    }

    @AfterEach
    fun tearDown() {
        IncidentTestDatabase.clearReference()
    }

    @Test
    fun `supports the complete canonical lifecycle with opaque IDs and versioned mutations`() {
        val declared = service.execute(
            DeclareIncidentCommand(
                commandKey = "declare-lifecycle",
                actor = actor(),
                title = "Checkout unavailable",
                description = "Requests are failing",
                summary = "Checkout is unavailable",
                severity = "SEV-1",
                mode = NativeIncidentMode.TEST,
                visibility = NativeIncidentVisibility.PRIVATE,
                initialStatus = NativeIncidentStatus.TRIAGE,
            ),
        )
        assertEquals(NativeIncidentStatus.TRIAGE, declared.status)
        assertEquals(1, declared.version)
        assertNotNull(Uuid.parse(declared.incidentResourceId))

        val accepted = service.execute(AcceptIncidentCommand("accept-lifecycle", actor(), declared.incidentId, 1))
        val resolved = service.execute(
            ResolveIncidentCommand("resolve-lifecycle", actor(), declared.incidentId, "Fixed", 2),
        )
        val postIncident = service.execute(
            TransitionIncidentCommand(
                "post-lifecycle",
                actor(),
                declared.incidentId,
                NativeIncidentStatus.POST_INCIDENT,
                expectedVersion = 3,
            ),
        )
        val closed = service.execute(
            TransitionIncidentCommand(
                "close-lifecycle",
                actor(),
                declared.incidentId,
                NativeIncidentStatus.CLOSED,
                expectedVersion = 4,
            ),
        )
        val reopened = service.execute(
            ReopenIncidentCommand("reopen-lifecycle", actor(), declared.incidentId, expectedVersion = 5),
        )
        val cancelled = service.execute(
            CancelIncidentCommand("cancel-lifecycle", actor(), declared.incidentId, expectedVersion = 6),
        )

        assertEquals(NativeIncidentStatus.ACTIVE, accepted.status)
        assertEquals(NativeIncidentStatus.RESOLVED, resolved.status)
        assertEquals(NativeIncidentStatus.POST_INCIDENT, postIncident.status)
        assertEquals(NativeIncidentStatus.CLOSED, closed.status)
        assertEquals(NativeIncidentStatus.ACTIVE, reopened.status)
        assertEquals(NativeIncidentStatus.CANCELLED, cancelled.status)
        assertEquals(7, cancelled.version)

        transaction {
            val row = OnCallIncidents.selectAll().where { OnCallIncidents.id eq declared.incidentId }.single()
            assertEquals("TEST", row[OnCallIncidents.mode])
            assertEquals("PRIVATE", row[OnCallIncidents.visibility])
            assertNotNull(row[OnCallIncidents.triagedAt])
            assertNotNull(row[OnCallIncidents.acceptedAt])
            assertNotNull(row[OnCallIncidents.resolvedAt])
            assertNotNull(row[OnCallIncidents.postIncidentAt])
            assertNotNull(row[OnCallIncidents.closedAt])
            assertNotNull(row[OnCallIncidents.cancelledAt])
        }
    }

    @Test
    fun `replays duplicate keys and rejects stale optimistic versions`() {
        val command =
            DeclareIncidentCommand(
                commandKey = "same-declare-key",
                actor = actor(),
                title = "API latency",
                description = null,
                severity = "SEV-2",
            )
        val first = service.execute(command)
        val replay = service.execute(command)

        assertTrue(replay.replayed)
        assertEquals(first.incidentResourceId, replay.incidentResourceId)
        assertFailsWith<IncidentCommandConflictException> {
            service.execute(command.copy(title = "A different request"))
        }
        transaction {
            assertEquals(1, NativeIncidentCommands.selectAll().count())
            assertEquals(1, NativeIncidentOutboxEvents.selectAll().count())
        }

        assertFailsWith<IncidentCommandConflictException> {
            service.execute(
                UpdateIncidentCommand(
                    commandKey = "stale-update",
                    actor = actor(),
                    incidentId = first.incidentId,
                    expectedVersion = 99,
                    title = "Should not apply",
                ),
            )
        }
    }

    @Test
    fun `scopes command and outbox idempotency keys by organization`() {
        val otherMember = IncidentTestDatabase.seedMember("second-idempotency-org")
        val first = service.execute(declareCommand("shared-client-key", actor()))
        val second = service.execute(
            declareCommand(
                "shared-client-key",
                IncidentCommandActor(otherMember.organizationId, otherMember.userId, "REST"),
            ),
        )

        assertTrue(first.incidentId != second.incidentId)
        transaction {
            assertEquals(2, NativeIncidentCommands.selectAll().count())
            assertEquals(2, NativeIncidentOutboxEvents.selectAll().count())
        }
    }

    @Test
    fun `merge moves linked on-call alerts and cancels the source incident`() {
        val target = service.execute(declareCommand("merge-target", actor()))
        val source = service.execute(declareCommand("merge-source", actor()))
        val alertId = transaction {
            val now = Clock.System.now()
            OnCallAlerts.insertAndGetId {
                it[organizationId] = member.organizationId
                it[declaredIncidentId] = null
                it[escalationPolicyId] = null
                it[title] = "Database page"
                it[description] = null
                it[priority] = "P1"
                it[status] = "TRIGGERED"
                it[alertSource] = "TEST"
                it[deduplicationKey] = "merge-alert"
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
        service.execute(LinkOnCallAlertCommand("link-source-alert", actor(), source.incidentId, alertId, 1))

        val merged = service.execute(
            MergeIncidentCommand(
                commandKey = "merge-incidents",
                actor = actor(),
                incidentId = target.incidentId,
                sourceIncidentId = source.incidentId,
                expectedVersion = 1,
            ),
        )

        assertEquals(2, merged.version)
        transaction {
            val link = OnCallIncidentAlerts.selectAll().single()
            assertEquals(target.incidentId, link[OnCallIncidentAlerts.incidentId])
            assertEquals(
                target.incidentId,
                OnCallAlerts.selectAll().where { OnCallAlerts.id eq alertId }.single()[OnCallAlerts.declaredIncidentId],
            )
            val sourceRow = OnCallIncidents.selectAll().where { OnCallIncidents.id eq source.incidentId }.single()
            assertEquals(NativeIncidentStatus.CANCELLED.wire, sourceRow[OnCallIncidents.status])
            assertEquals(3, sourceRow[OnCallIncidents.version])
        }
    }

    @Test
    fun `supporting commands use the same policy version and outbox pipeline`() {
        val incident = service.execute(declareCommand("supporting-declare", actor()))

        val role = service.execute(
            AssignIncidentRoleCommand(
                commandKey = "supporting-role",
                actor = actor(),
                incidentId = incident.incidentId,
                role = "incident_commander",
                assigneeUserId = member.userId,
                expectedVersion = 1,
            ),
        )
        val action = service.execute(
            AddIncidentActionCommand(
                commandKey = "supporting-action",
                actor = actor(),
                incidentId = incident.incidentId,
                title = "Drain unhealthy node",
                assigneeUserId = member.userId,
                expectedVersion = 2,
            ),
        )
        val timeline = service.execute(
            AddIncidentTimelineEventCommand(
                commandKey = "supporting-timeline",
                actor = actor(),
                incidentId = incident.incidentId,
                eventType = "CUSTOM_NOTE",
                details = emptyMap(),
                expectedVersion = 3,
            ),
        )
        val updated = service.execute(
            UpdateIncidentCommand(
                commandKey = "supporting-update",
                actor = actor(),
                incidentId = incident.incidentId,
                title = "Database node unavailable",
                expectedVersion = 4,
            ),
        )

        assertEquals(listOf(2, 3, 4, 5), listOf(role.version, action.version, timeline.version, updated.version))
        transaction {
            assertEquals(5, NativeIncidentCommands.selectAll().count())
            assertEquals(5, NativeIncidentOutboxEvents.selectAll().count())
            val eventTypes = OnCallIncidentTimeline.selectAll().map { it[OnCallIncidentTimeline.eventType] }
            assertTrue("ROLE_ASSIGNED" in eventTypes)
            assertTrue("ACTION_ADDED" in eventTypes)
            assertTrue("CUSTOM_NOTE" in eventTypes)
            assertTrue("UPDATED" in eventTypes)
        }
    }

    @Test
    fun `enforces entitlement membership and organization scope before mutation`() {
        val deniedService =
            IncidentCommandService(
                policy = IncidentCommandPolicy(entitlement = IncidentEntitlement { false }),
            )
        assertFailsWith<IncidentCommandDeniedException> {
            deniedService.execute(
                DeclareIncidentCommand(
                    commandKey = "not-entitled",
                    actor = actor(),
                    title = "Denied",
                    description = null,
                    severity = "SEV-3",
                ),
            )
        }

        val incident = service.execute(
            DeclareIncidentCommand(
                commandKey = "scoped-declare",
                actor = actor(),
                title = "Scoped",
                description = null,
                severity = "SEV-3",
            ),
        )
        val otherMember = IncidentTestDatabase.seedMember("other-incident-org")
        assertFailsWith<IncidentCommandNotFoundException> {
            service.execute(
                ResolveIncidentCommand(
                    commandKey = "cross-org-resolve",
                    actor = IncidentCommandActor(otherMember.organizationId, otherMember.userId, "REST"),
                    incidentId = incident.incidentId,
                ),
            )
        }

        val outsiderId =
            transaction {
                Users.insert {
                    it[email] = "outsider@example.test"
                    it[password_hash] = "x"
                    it[name] = "Outsider"
                }[Users.id]
            }
        assertFailsWith<IncidentCommandDeniedException> {
            service.execute(
                DeclareIncidentCommand(
                    commandKey = "scoped-declare",
                    actor = IncidentCommandActor(member.organizationId, outsiderId, "REST"),
                    title = "Scoped",
                    description = null,
                    severity = "SEV-3",
                ),
            )
        }
    }

    private fun actor() = IncidentCommandActor(member.organizationId, member.userId, "REST")

    private fun declareCommand(commandKey: String, actor: IncidentCommandActor) =
        DeclareIncidentCommand(
            commandKey = commandKey,
            actor = actor,
            title = "Shared idempotency key",
            description = null,
            severity = "SEV-3",
        )
}
