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
import com.moneat.enterprise.incidents.updates.IncidentUpdateReminderService
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidentAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidentTimeline
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.enterprise.oncall.services.OnCallIncidentService
import com.moneat.enterprise.incidents.responders.CreateIncidentRole
import com.moneat.enterprise.incidents.responders.IncidentResponderService
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
import kotlin.time.Duration.Companion.minutes
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
                NativeIncidentOutboxEvents.selectAll().last()[NativeIncidentOutboxEvents.eventType],
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
