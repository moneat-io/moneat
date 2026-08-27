// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.commands

import com.moneat.enterprise.incidents.IncidentTestDatabase
import com.moneat.enterprise.incidents.SeededMember
import com.moneat.enterprise.incidents.models.NativeIncidentCommands
import com.moneat.enterprise.incidents.models.NativeIncidentMode
import com.moneat.enterprise.incidents.models.NativeIncidentOutboxEvents
import com.moneat.enterprise.incidents.models.NativeIncidentSourceLinks
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.incidents.models.NativeIncidentVisibility
import com.moneat.enterprise.incidents.models.IncidentSourceType
import com.moneat.enterprise.incidents.models.IncidentUpdateRequestStatus
import com.moneat.enterprise.incidents.models.NativeIncidentUpdateRequests
import com.moneat.enterprise.incidents.models.IncidentActionSource
import com.moneat.enterprise.incidents.models.IncidentActionState
import com.moneat.enterprise.incidents.models.NativeIncidentActions
import com.moneat.enterprise.incidents.models.NativeIncidentActionEvents
import com.moneat.enterprise.incidents.actions.IncidentActionService
import com.moneat.enterprise.incidents.followups.IncidentFollowUpPriority
import com.moneat.enterprise.incidents.followups.IncidentFollowUpReminderService
import com.moneat.enterprise.incidents.followups.IncidentFollowUpService
import com.moneat.enterprise.incidents.followups.IncidentFollowUpStatus
import com.moneat.enterprise.incidents.followups.NativeIncidentFollowUps
import com.moneat.enterprise.incidents.updates.IncidentUpdateReminderService
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidentAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidentTimeline
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.enterprise.oncall.services.OnCallIncidentService
import com.moneat.enterprise.incidents.responders.CreateIncidentRole
import com.moneat.enterprise.incidents.responders.IncidentResponderService
import com.moneat.shared.models.Users
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid
import java.util.concurrent.Callable
import java.util.concurrent.Executors

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
            assertEquals(null, row[OnCallIncidents.resolvedAt])
            assertEquals(null, row[OnCallIncidents.postIncidentAt])
            assertEquals(null, row[OnCallIncidents.closedAt])
            assertNotNull(row[OnCallIncidents.cancelledAt])
        }
    }

    @Test
    fun `rejects declaration in a terminal lifecycle status`() {
        assertFailsWith<IllegalArgumentException> {
            service.execute(
                declareCommand("terminal-declare", actor()).copy(initialStatus = NativeIncidentStatus.RESOLVED),
            )
        }
        transaction {
            assertEquals(0, OnCallIncidents.selectAll().count())
        }
    }

    @Test
    fun `publishes structured updates and fulfils the active update request`() {
        val declared = service.execute(declareCommand("structured-update", actor()))
        val dueAt = Clock.System.now().plus(5.minutes)
        val requested = service.execute(
            RequestIncidentUpdateCommand(
                commandKey = "request-structured-update",
                actor = actor(),
                incidentId = declared.incidentId,
                message = "Share customer impact and mitigation progress",
                dueAt = dueAt,
                expectedVersion = 1,
            ),
        )
        assertEquals(2, requested.version)

        val updated = service.execute(
            UpdateIncidentCommand(
                commandKey = "publish-structured-update",
                actor = actor(),
                incidentId = declared.incidentId,
                expectedVersion = 2,
                message = "Mitigation is rolling out",
                customerImpact = "Checkout requests fail for a subset of customers",
                nextUpdateAt = dueAt,
                status = NativeIncidentStatus.RESOLVED,
            ),
        )

        assertEquals(NativeIncidentStatus.RESOLVED, updated.status)
        transaction {
            val incident = OnCallIncidents.selectAll().where { OnCallIncidents.id eq declared.incidentId }.single()
            assertEquals("Mitigation is rolling out", incident[OnCallIncidents.summary])
            assertEquals("Checkout requests fail for a subset of customers", incident[OnCallIncidents.customerImpact])
            assertEquals(
                dueAt.toString().substringBefore('.'),
                incident[OnCallIncidents.nextUpdateAt]?.toString()?.substringBefore('.'),
            )
            assertEquals(NativeIncidentStatus.RESOLVED.wire, incident[OnCallIncidents.status])
            assertEquals(
                IncidentUpdateRequestStatus.FULFILLED.wire,
                NativeIncidentUpdateRequests.selectAll().single()[NativeIncidentUpdateRequests.status],
            )
        }
    }

    @Test
    fun `escalates overdue update reminders and stops them when paused`() {
        val declared = service.execute(declareCommand("reminder-update", actor()))
        val now = Clock.System.now()
        service.execute(
            RequestIncidentUpdateCommand(
                commandKey = "request-reminder-update",
                actor = actor(),
                incidentId = declared.incidentId,
                dueAt = now.minus(1.minutes),
                expectedVersion = 1,
            ),
        )

        val reminderService = IncidentUpdateReminderService()
        assertEquals(1, reminderService.processDue(now))
        transaction {
            val request = NativeIncidentUpdateRequests.selectAll().single()
            assertEquals(1, request[NativeIncidentUpdateRequests.escalationLevel])
            assertNotNull(request[NativeIncidentUpdateRequests.lastRemindedAt])
            assertEquals(
                "INCIDENT_UPDATE_REMINDER",
                NativeIncidentOutboxEvents.selectAll()
                    .where { NativeIncidentOutboxEvents.eventType eq "INCIDENT_UPDATE_REMINDER" }
                    .single()[NativeIncidentOutboxEvents.eventType],
            )
        }

        val paused = service.execute(
            PauseIncidentUpdateRemindersCommand(
                commandKey = "pause-reminder-update",
                actor = actor(),
                incidentId = declared.incidentId,
                paused = true,
                expectedVersion = 3,
            ),
        )
        assertEquals(4, paused.version)
        transaction {
            assertEquals(
                IncidentUpdateRequestStatus.PAUSED.wire,
                NativeIncidentUpdateRequests.selectAll().single()[NativeIncidentUpdateRequests.status],
            )
            assertTrue(OnCallIncidents.selectAll().single()[OnCallIncidents.updateReminderPaused])
        }
    }

    @Test
    fun `reminder processing pauses and cancels overdue requests from incident state`() {
        val now = Clock.System.now()
        val pausedIncident = service.execute(declareCommand("paused-reminder", actor()))
        service.execute(
            RequestIncidentUpdateCommand(
                commandKey = "request-paused-reminder",
                actor = actor(),
                incidentId = pausedIncident.incidentId,
                dueAt = now.minus(1.minutes),
                expectedVersion = 1,
            ),
        )
        transaction {
            OnCallIncidents.update({ OnCallIncidents.id eq pausedIncident.incidentId }) {
                it[OnCallIncidents.updateReminderPaused] = true
            }
        }

        val reminderService = IncidentUpdateReminderService()
        assertEquals(0, reminderService.processDue(now))
        transaction {
            assertEquals(
                IncidentUpdateRequestStatus.PAUSED.wire,
                NativeIncidentUpdateRequests.selectAll().single()[NativeIncidentUpdateRequests.status],
            )
        }

        val terminalIncident = service.execute(declareCommand("terminal-reminder", actor()))
        service.execute(
            RequestIncidentUpdateCommand(
                commandKey = "request-terminal-reminder",
                actor = actor(),
                incidentId = terminalIncident.incidentId,
                dueAt = now.minus(1.minutes),
                expectedVersion = 1,
            ),
        )
        transaction {
            OnCallIncidents.update({ OnCallIncidents.id eq terminalIncident.incidentId }) {
                it[OnCallIncidents.status] = NativeIncidentStatus.RESOLVED.wire
            }
        }

        assertEquals(0, reminderService.processDue(now))
        transaction {
            val requests = NativeIncidentUpdateRequests.selectAll().toList()
            assertEquals(
                IncidentUpdateRequestStatus.CANCELLED.wire,
                requests.last()[NativeIncidentUpdateRequests.status],
            )
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
    fun `merge moves linked on-call alerts and retires the source incident`() {
        val target = service.execute(declareCommand("merge-target", actor()))
        val source = service.execute(declareCommand("merge-source", actor()))
        val alertId = seedAlert("merge-alert")
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
            assertEquals(NativeIncidentStatus.MERGED.wire, sourceRow[OnCallIncidents.status])
            assertEquals(target.incidentId, sourceRow[OnCallIncidents.mergedIntoIncidentId])
            assertNotNull(sourceRow[OnCallIncidents.mergedAt])
            assertEquals(3, sourceRow[OnCallIncidents.version])
        }
    }

    @Test
    fun `links several alerts as idempotent incident sources`() {
        val firstAlertId = seedAlert("first-source-alert")
        val secondAlertId = seedAlert("second-source-alert")
        val incident = service.execute(declareCommand("declare-source-alerts", actor()))

        fun link(commandKey: String, alertId: Int) =
            service.execute(
                LinkIncidentSourceCommand(
                    commandKey = commandKey,
                    actor = actor(),
                    incidentId = incident.incidentId,
                    source =
                        IncidentSourceReference(
                            sourceType = IncidentSourceType.ON_CALL_ALERT,
                            sourceKey = "resolved-by-service",
                            onCallAlertId = alertId,
                        ),
                ),
            )

        val first = link("link-first-source-alert", firstAlertId)
        val replay = link("replay-first-source-alert", firstAlertId)
        val second = link("link-second-source-alert", secondAlertId)

        assertEquals(listOf(2, 2, 3), listOf(first.version, replay.version, second.version))
        transaction {
            assertEquals(2, OnCallIncidentAlerts.selectAll().count())
            assertEquals(2, NativeIncidentSourceLinks.selectAll().count())
        }
    }

    @Test
    fun `maps a conflicting alert link to the command conflict contract`() {
        val alertId = seedAlert("duplicate-link")
        service.execute(declareCommand("first-alert-owner", actor()).copy(onCallAlertId = alertId))

        assertFailsWith<IncidentCommandConflictException> {
            service.execute(declareCommand("second-alert-owner", actor()).copy(onCallAlertId = alertId))
        }
        transaction {
            assertEquals(1, OnCallIncidents.selectAll().count())
            assertEquals(1, NativeIncidentCommands.selectAll().count())
        }
    }

    @Test
    fun `merge obeys the canonical lifecycle policy`() {
        val target = service.execute(declareCommand("merge-policy-target", actor()))
        val source = service.execute(declareCommand("merge-policy-source", actor()))
        service.execute(ResolveIncidentCommand("resolve-merge-source", actor(), source.incidentId, expectedVersion = 1))

        assertFailsWith<IncidentCommandConflictException> {
            service.execute(
                MergeIncidentCommand(
                    commandKey = "merge-resolved-source",
                    actor = actor(),
                    incidentId = target.incidentId,
                    sourceIncidentId = source.incidentId,
                    expectedVersion = 1,
                ),
            )
        }
        transaction {
            val sourceRow = OnCallIncidents.selectAll().where { OnCallIncidents.id eq source.incidentId }.single()
            assertEquals(NativeIncidentStatus.RESOLVED.wire, sourceRow[OnCallIncidents.status])
        }
    }

    @Test
    fun `missing incident notes use the shared not found contract`() {
        val incidentService = OnCallIncidentService(service)

        assertFailsWith<IncidentCommandNotFoundException> {
            incidentService.addNote(Int.MAX_VALUE, member.userId, "Not found")
        }
    }

    @Test
    fun `supporting commands use the same policy version and outbox pipeline`() {
        val incident = service.execute(declareCommand("supporting-declare", actor()))
        val responderService = IncidentResponderService()
        val roleDefinition =
            responderService.createRole(
                member.organizationId,
                member.userId,
                CreateIncidentRole(
                    stableKey = "incident-commander-test",
                    name = "Incident Commander",
                    description = null,
                    responsibilities = listOf("Coordinate the response"),
                    privateInstructions = null,
                    required = true,
                    default = false,
                ),
            )
        val roleDefinitionId = responderService.resolveRoleId(member.organizationId, roleDefinition.id)

        val role = service.execute(
            AssignIncidentRoleCommand(
                commandKey = "supporting-role",
                actor = actor(),
                incidentId = incident.incidentId,
                roleDefinitionId = roleDefinitionId,
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
    fun `persists action lifecycle and audit history`() {
        val incident = service.execute(declareCommand("action-lifecycle", actor()))
        val actionService = IncidentActionService()
        assertTrue(actionService.list(member.organizationId, incident.incidentId).isEmpty())
        assertNull(actionService.get(member.organizationId, incident.incidentId, "not-a-uuid"))
        assertTrue(actionService.events(member.organizationId, incident.incidentId, "not-a-uuid").isEmpty())
        assertNull(actionService.internalId(member.organizationId, incident.incidentId, "not-a-uuid"))
        val addCommand = AddIncidentActionCommand(
            commandKey = "action-created",
            actor = actor(),
            incidentId = incident.incidentId,
            title = "Drain unhealthy node",
            description = "Drain the unhealthy node and verify capacity",
            source = IncidentActionSource.SLACK,
            slackChannelId = "C123",
            slackMessageTs = "1712345678.000100",
            expectedVersion = 1,
        )
        val created = service.execute(addCommand)
        val replay = service.execute(addCommand)
        assertTrue(replay.replayed)
        assertEquals(created.actionResourceId, replay.actionResourceId)
        val actionResourceId = checkNotNull(created.actionResourceId)

        assertEquals(IncidentActionState.OPEN.wire, actionService.get(
            member.organizationId,
            incident.incidentId,
            actionResourceId,
        )?.state)

        val claimed = service.execute(
            ClaimIncidentActionCommand(
                commandKey = "action-claimed",
                actor = actor(),
                incidentId = incident.incidentId,
                actionResourceId = actionResourceId,
                expectedVersion = 2,
            ),
        )
        val reassignedUserId = IncidentTestDatabase.seedUserInOrganization(member.organizationId, "action-assignee")
        val reassigned = service.execute(
            ReassignIncidentActionCommand(
                commandKey = "action-reassigned",
                actor = actor(),
                incidentId = incident.incidentId,
                actionResourceId = actionResourceId,
                assigneeUserId = reassignedUserId,
                expectedVersion = 3,
            ),
        )
        val completed = service.execute(
            CompleteIncidentActionCommand(
                commandKey = "action-completed",
                actor = actor(),
                incidentId = incident.incidentId,
                actionResourceId = actionResourceId,
                note = "Capacity is healthy",
                expectedVersion = 4,
            ),
        )
        assertEquals(3, claimed.version)
        assertEquals(4, reassigned.version)
        assertEquals(5, completed.version)
        val action = checkNotNull(
            actionService.get(member.organizationId, incident.incidentId, actionResourceId),
        )
        assertEquals(incident.incidentResourceId, action.incidentId)
        assertEquals(IncidentActionState.COMPLETED.wire, action.state)
        assertEquals("C123", action.slackChannelId)
        assertEquals("1712345678.000100", action.slackMessageTs)
        assertEquals(1, actionService.list(member.organizationId, incident.incidentId).size)
        assertEquals(
            1,
            actionService.metrics(member.organizationId, incident.incidentId).completed,
        )
        assertNotNull(actionService.internalId(member.organizationId, incident.incidentId, actionResourceId))
        assertEquals(
            4,
            actionService.events(member.organizationId, incident.incidentId, actionResourceId).size,
        )
        transaction {
            assertEquals(4, NativeIncidentActionEvents.selectAll().count())
            assertEquals(1, NativeIncidentActions.selectAll().count())
        }
    }

    @Test
    fun `action creation audit captures the initial assignee`() {
        val incident = service.execute(declareCommand("action-assignee-audit", actor()))
        service.execute(
            AddIncidentActionCommand(
                commandKey = "action-assignee-created",
                actor = actor(),
                incidentId = incident.incidentId,
                title = "Verify failover",
                assigneeUserId = member.userId,
                expectedVersion = 1,
            ),
        )

        val assigneeResourceId = transaction {
            Users.selectAll().where { Users.id eq member.userId }.single()[Users.resource_id].toString()
        }
        transaction {
            val event = NativeIncidentActionEvents.selectAll().single()
            assertEquals(
                assigneeResourceId,
                event[NativeIncidentActionEvents.details]["assigneeUserId"]?.jsonPrimitive?.content,
            )
        }
    }

    @Test
    fun `action command replay retains resource id`() {
        val incident = service.execute(declareCommand("action-replay", actor()))
        val command = AddIncidentActionCommand(
            commandKey = "action-replay-command",
            actor = actor(),
            incidentId = incident.incidentId,
            title = "Check queue depth",
            expectedVersion = 1,
        )
        val created = service.execute(command)
        val replay = service.execute(command)
        assertTrue(replay.replayed)
        assertEquals(created.actionResourceId, replay.actionResourceId)
    }

    @Test
    fun `persists follow-up ownership policy and completion lifecycle`() {
        val incident = service.execute(declareCommand("follow-up-lifecycle", actor()))
        val ownerId = IncidentTestDatabase.seedUserInOrganization(member.organizationId, "follow-up-owner")
        val dueAt = Clock.System.now().plus(2.hours)
        val created = service.execute(
            AddIncidentFollowUpCommand(
                commandKey = "follow-up-created",
                actor = actor(),
                incidentId = incident.incidentId,
                title = "Publish the customer communication timeline",
                description = "Document the communication gaps and the remediation owner",
                ownerUserId = ownerId,
                priority = IncidentFollowUpPriority.P1,
                labels = listOf("communications", "process"),
                dueAt = dueAt,
                slaMinutes = 120,
                reminderMinutes = 30,
                expectedVersion = 1,
            ),
        )
        val replay = service.execute(
            AddIncidentFollowUpCommand(
                commandKey = "follow-up-created",
                actor = actor(),
                incidentId = incident.incidentId,
                title = "Publish the customer communication timeline",
                description = "Document the communication gaps and the remediation owner",
                ownerUserId = ownerId,
                priority = IncidentFollowUpPriority.P1,
                labels = listOf("communications", "process"),
                dueAt = dueAt,
                slaMinutes = 120,
                reminderMinutes = 30,
                expectedVersion = 1,
            ),
        )
        assertTrue(replay.replayed)
        assertEquals(created.followUpResourceId, replay.followUpResourceId)
        val followUpId = checkNotNull(created.followUpResourceId)
        val followUpService = IncidentFollowUpService()
        val initial = checkNotNull(followUpService.get(member.organizationId, incident.incidentId, followUpId))
        assertEquals(IncidentFollowUpStatus.OPEN.wire, initial.status)
        assertEquals(IncidentFollowUpPriority.P1.wire, initial.priority)
        assertEquals(listOf("communications", "process"), initial.labels)
        assertEquals(120, initial.slaMinutes)
        assertEquals(30, initial.reminderMinutes)

        service.execute(
            AcceptIncidentFollowUpCommand(
                commandKey = "follow-up-accepted",
                actor = actor(),
                incidentId = incident.incidentId,
                followUpResourceId = followUpId,
                expectedVersion = 2,
            ),
        )
        service.execute(
            UpdateIncidentFollowUpCommand(
                commandKey = "follow-up-updated",
                actor = actor(),
                incidentId = incident.incidentId,
                followUpResourceId = followUpId,
                labels = listOf("communications", "process", "reviewed"),
                expectedVersion = 3,
            ),
        )
        val completed = service.execute(
            CompleteIncidentFollowUpCommand(
                commandKey = "follow-up-completed",
                actor = actor(),
                incidentId = incident.incidentId,
                followUpResourceId = followUpId,
                note = "Action item is documented and assigned",
                expectedVersion = 4,
            ),
        )
        assertEquals(5, completed.version)
        val final = checkNotNull(followUpService.get(member.organizationId, incident.incidentId, followUpId))
        assertEquals(IncidentFollowUpStatus.COMPLETED.wire, final.status)
        assertEquals(listOf("communications", "process", "reviewed"), final.labels)
        assertNotNull(final.acceptedAt)
        assertNotNull(final.completedAt)
        transaction {
            assertEquals(1, NativeIncidentFollowUps.selectAll().count())
            val command = NativeIncidentCommands.selectAll()
                .where { NativeIncidentCommands.commandKey eq "follow-up-created" }
                .single()
            assertEquals(followUpId, command[NativeIncidentCommands.followUpResourceId].toString())
        }
    }

    @Test
    fun `escalates overdue follow-up policy and stops after incident closure`() {
        val now = Clock.System.now()
        val incident = service.execute(declareCommand("follow-up-reminder", actor()))
        val created = service.execute(
            AddIncidentFollowUpCommand(
                commandKey = "follow-up-reminder-created",
                actor = actor(),
                incidentId = incident.incidentId,
                title = "Review the incident timeline",
                description = "Capture the missing response milestones",
                ownerUserId = member.userId,
                dueAt = now.minus(1.minutes),
                reminderMinutes = 5,
                expectedVersion = 1,
            ),
        )
        val followUpId = checkNotNull(created.followUpResourceId)
        val reminderService = IncidentFollowUpReminderService()

        assertEquals(1, reminderService.processDue(now))
        transaction {
            val followUp = NativeIncidentFollowUps.selectAll().single()
            assertEquals(1, followUp[NativeIncidentFollowUps.escalationLevel])
            assertTrue(followUp[NativeIncidentFollowUps.nextReminderAt]!! > now)
            val event = NativeIncidentOutboxEvents.selectAll()
                .where { NativeIncidentOutboxEvents.eventType eq "INCIDENT_FOLLOW_UP_REMINDER" }
                .single()
            assertEquals(followUpId, event[NativeIncidentOutboxEvents.payload]["followUpId"]?.jsonPrimitive?.content)
        }

        service.execute(
            ResolveIncidentCommand(
                commandKey = "follow-up-reminder-resolve",
                actor = actor(),
                incidentId = incident.incidentId,
                note = "Mitigated",
                expectedVersion = 3,
            ),
        )
        assertEquals(0, reminderService.processDue(now.plus(10.minutes)))
        transaction {
            assertEquals(
                IncidentFollowUpStatus.CANCELLED.wire,
                NativeIncidentFollowUps.selectAll().single()[NativeIncidentFollowUps.status],
            )
        }
    }

    @Test
    fun `no-op action mutations still validate the incident version`() {
        val incident = service.execute(declareCommand("action-noop-version", actor()))
        val created = service.execute(
            AddIncidentActionCommand(
                commandKey = "action-noop-created",
                actor = actor(),
                incidentId = incident.incidentId,
                title = "Check queue depth",
                expectedVersion = 1,
            ),
        )
        val actionResourceId = checkNotNull(created.actionResourceId)
        service.execute(
            ClaimIncidentActionCommand(
                commandKey = "action-noop-claimed",
                actor = actor(),
                incidentId = incident.incidentId,
                actionResourceId = actionResourceId,
                expectedVersion = 2,
            ),
        )

        assertFailsWith<IncidentCommandConflictException> {
            service.execute(
                ClaimIncidentActionCommand(
                    commandKey = "action-noop-stale",
                    actor = actor(),
                    incidentId = incident.incidentId,
                    actionResourceId = actionResourceId,
                    expectedVersion = 2,
                ),
            )
        }
    }

    @Test
    fun `concurrent action claims serialize against the locked action row`() {
        val incident = service.execute(declareCommand("action-concurrent-claim", actor()))
        val created = service.execute(
            AddIncidentActionCommand(
                commandKey = "action-concurrent-created",
                actor = actor(),
                incidentId = incident.incidentId,
                title = "Check queue depth",
                expectedVersion = 1,
            ),
        )
        val actionResourceId = checkNotNull(created.actionResourceId)
        val secondUserId = IncidentTestDatabase.seedUserInOrganization(member.organizationId, "action-concurrent-other")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = executor.invokeAll(
                listOf(
                    Callable {
                        runCatching {
                            service.execute(
                                ClaimIncidentActionCommand(
                                    commandKey = "action-concurrent-claim-first",
                                    actor = actor(),
                                    incidentId = incident.incidentId,
                                    actionResourceId = actionResourceId,
                                ),
                            )
                        }
                    },
                    Callable {
                        runCatching {
                            service.execute(
                                ClaimIncidentActionCommand(
                                    commandKey = "action-concurrent-claim-second",
                                    actor = IncidentCommandActor(
                                        member.organizationId,
                                        secondUserId,
                                        "REST",
                                    ),
                                    incidentId = incident.incidentId,
                                    actionResourceId = actionResourceId,
                                ),
                            )
                        }
                    },
                ),
            ).map { it.get() }

            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.exceptionOrNull() is IncidentCommandConflictException })
            transaction {
                val action = NativeIncidentActions.selectAll().single()
                assertEquals(IncidentActionState.CLAIMED.wire, action[NativeIncidentActions.state])
                val claimEvents = NativeIncidentActionEvents.selectAll()
                    .where { NativeIncidentActionEvents.eventType eq "ACTION_CLAIMED" }
                assertEquals(1, claimEvents.count())
                assertEquals(IncidentActionState.OPEN.wire, claimEvents.single()[NativeIncidentActionEvents.fromState])
            }
        } finally {
            executor.shutdownNow()
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
                    actor = IncidentCommandActor(member.organizationId, member.userId, "SLACK"),
                    title = "Denied",
                    description = null,
                    severity = "SEV-3",
                ),
            )
        }
        transaction {
            assertEquals(0, NativeIncidentCommands.selectAll().count())
            assertEquals(0, NativeIncidentOutboxEvents.selectAll().count())
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

    @Test
    fun `quota exhaustion rejects declaration without mutation`() {
        val quotaDeniedService =
            IncidentCommandService(
                policy = IncidentCommandPolicy(
                    entitlement = IncidentEntitlement { true },
                    authorizer = IncidentCommandAuthorizer { _, _ -> true },
                    quotaAdmission = IncidentQuotaAdmission {
                        com.moneat.enterprise.NativeIncidentQuotaDecision(
                            allowed = false,
                            status = com.moneat.enterprise.NativeIncidentQuotaStatus(
                                com.moneat.enterprise.NativeIncidentQuotaKey.NATIVE_INCIDENTS,
                                limit = 1,
                                used = 1,
                            ),
                            message = "native incidents quota is exhausted; upgrade the plan or reduce usage",
                        )
                    },
                ),
            )

        val error = assertFailsWith<IncidentCommandQuotaExceededException> {
            quotaDeniedService.execute(
                DeclareIncidentCommand(
                    commandKey = "quota-denied",
                    actor = actor(),
                    title = "Denied by quota",
                    description = null,
                    severity = "SEV-3",
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("upgrade the plan"))
        transaction {
            assertEquals(0, NativeIncidentCommands.selectAll().count())
            assertEquals(0, NativeIncidentOutboxEvents.selectAll().count())
        }
    }

    @Test
    fun `declaration replay returns before quota admission`() {
        var quotaAdmissions = 0
        val quotaAwareService =
            IncidentCommandService(
                policy = IncidentCommandPolicy(
                    entitlement = IncidentEntitlement { true },
                    authorizer = IncidentCommandAuthorizer { _, _ -> true },
                    quotaAdmission = IncidentQuotaAdmission {
                        quotaAdmissions += 1
                        com.moneat.enterprise.NativeIncidentQuotaDecision(
                            allowed = true,
                            status = com.moneat.enterprise.NativeIncidentQuotaStatus(
                                key = com.moneat.enterprise.NativeIncidentQuotaKey.NATIVE_INCIDENTS,
                                limit = 10,
                                used = quotaAdmissions.toLong(),
                            ),
                        )
                    },
                ),
            )
        val command = DeclareIncidentCommand(
            commandKey = "quota-replay",
            actor = actor(),
            title = "Replay-safe declaration",
            description = null,
            severity = "SEV-3",
        )

        val declared = quotaAwareService.execute(command)
        val replay = quotaAwareService.execute(command)

        assertEquals(declared.incidentId, replay.incidentId)
        assertEquals(declared.incidentResourceId, replay.incidentResourceId)
        assertEquals(declared.version, replay.version)
        assertTrue(replay.replayed)
        assertEquals(1, quotaAdmissions)
        transaction {
            assertEquals(1, NativeIncidentCommands.selectAll().count())
            assertEquals(1, NativeIncidentOutboxEvents.selectAll().count())
        }
    }

    private fun actor() = IncidentCommandActor(member.organizationId, member.userId, "REST")

    private fun seedAlert(deduplicationKey: String): Int =
        transaction {
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
                it[OnCallAlerts.deduplicationKey] = deduplicationKey
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

    private fun declareCommand(commandKey: String, actor: IncidentCommandActor) =
        DeclareIncidentCommand(
            commandKey = commandKey,
            actor = actor,
            title = "Shared idempotency key",
            description = null,
            severity = "SEV-3",
        )
}
