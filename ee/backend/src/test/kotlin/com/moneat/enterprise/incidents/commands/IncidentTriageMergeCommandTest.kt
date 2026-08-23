// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.commands

import com.moneat.alerts.models.AlertEpisodes
import com.moneat.enterprise.incidents.IncidentTestDatabase
import com.moneat.enterprise.incidents.SeededMember
import com.moneat.enterprise.incidents.config.CreateIncidentCustomField
import com.moneat.enterprise.incidents.config.CreateIncidentForm
import com.moneat.enterprise.incidents.config.IncidentConfigurationService
import com.moneat.enterprise.incidents.config.IncidentFormFieldInput
import com.moneat.enterprise.incidents.models.IncidentCustomFieldValueType
import com.moneat.enterprise.incidents.models.IncidentFormStage
import com.moneat.enterprise.incidents.models.IncidentSourceType
import com.moneat.enterprise.incidents.models.NativeIncidentAlertEpisodeLinks
import com.moneat.enterprise.incidents.models.NativeIncidentFormSubmissions
import com.moneat.enterprise.incidents.models.NativeIncidentOutboxEvents
import com.moneat.enterprise.incidents.models.NativeIncidentSourceLinks
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidentAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidentTimeline
import com.moneat.enterprise.oncall.models.OnCallIncidents
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
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
import kotlin.uuid.Uuid

class IncidentTriageMergeCommandTest {
    private lateinit var member: SeededMember
    private lateinit var service: IncidentCommandService
    private val configurationService = IncidentConfigurationService()

    @BeforeEach
    fun setUp() {
        IncidentTestDatabase.reset()
        member = IncidentTestDatabase.seedMember("triage-merge-org")
        service = IncidentCommandService(policy = IncidentCommandPolicy.allowForTests())
    }

    @AfterEach
    fun tearDown() {
        IncidentTestDatabase.clearReference()
    }

    @Test
    fun `triage declarations may omit severity while active declarations may not`() {
        val triage = service.execute(triageDeclaration("triage-unclassified"))

        assertEquals(NativeIncidentStatus.TRIAGE, triage.status)
        transaction {
            val row = incidentRow(triage.incidentId)
            assertNull(row[OnCallIncidents.severity])
            assertNull(row[OnCallIncidents.incidentType])
            assertNotNull(row[OnCallIncidents.triagedAt])
            assertNull(row[OnCallIncidents.acceptedAt])
        }

        assertFailsWith<IllegalArgumentException> {
            service.execute(
                DeclareIncidentCommand(
                    commandKey = "active-without-severity",
                    actor = actor(),
                    title = "Active without severity",
                    description = null,
                    initialStatus = NativeIncidentStatus.ACTIVE,
                ),
            )
        }
    }

    @Test
    fun `leaving triage requires a severity and records the accepted-at timestamp`() {
        val triage = service.execute(triageDeclaration("triage-accept"))

        assertFailsWith<IllegalArgumentException> {
            service.execute(
                TransitionIncidentCommand(
                    "transition-without-severity",
                    actor(),
                    triage.incidentId,
                    NativeIncidentStatus.ACTIVE,
                    expectedVersion = 1,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.execute(AcceptIncidentCommand("accept-without-severity", actor(), triage.incidentId, 1))
        }

        val accepted =
            service.execute(
                AcceptIncidentCommand(
                    commandKey = "accept-with-severity",
                    actor = actor(),
                    incidentId = triage.incidentId,
                    expectedVersion = 1,
                    severity = "SEV-2",
                ),
            )

        assertEquals(NativeIncidentStatus.ACTIVE, accepted.status)
        assertEquals(2, accepted.version)
        transaction {
            val row = incidentRow(triage.incidentId)
            assertEquals("SEV-2", row[OnCallIncidents.severity])
            assertNotNull(row[OnCallIncidents.acceptedAt])
            assertNotNull(row[OnCallIncidents.triagedAt])
            assertTrue(
                timelineEventTypes(triage.incidentId).contains("ACCEPTED"),
            )
        }
    }

    @Test
    fun `acceptance enforces the configured acceptance form and stores its submission`() {
        configureAcceptanceForm()
        val triage = service.execute(triageDeclaration("triage-form"))

        assertFailsWith<IllegalArgumentException> {
            service.execute(
                AcceptIncidentCommand(
                    commandKey = "accept-missing-form-values",
                    actor = actor(),
                    incidentId = triage.incidentId,
                    expectedVersion = 1,
                    severity = "SEV-1",
                ),
            )
        }

        service.execute(
            AcceptIncidentCommand(
                commandKey = "accept-with-form-values",
                actor = actor(),
                incidentId = triage.incidentId,
                expectedVersion = 1,
                severity = "SEV-1",
                formValues = mapOf("customer_impact" to JsonPrimitive("high")),
            ),
        )

        transaction {
            val submission =
                NativeIncidentFormSubmissions
                    .selectAll()
                    .where {
                        (NativeIncidentFormSubmissions.incidentId eq triage.incidentId) and
                            (NativeIncidentFormSubmissions.stage eq IncidentFormStage.ACCEPTANCE.wire)
                    }.single()
            assertEquals(
                "high",
                submission[NativeIncidentFormSubmissions.valuesSnapshot]
                    .getValue("customer_impact")
                    .jsonPrimitive
                    .content,
            )
        }
    }

    @Test
    fun `merging transfers every link kind and retires the source into the terminal merged status`() {
        val target = service.execute(activeDeclaration("merge-target"))
        val source = service.execute(activeDeclaration("merge-source"))
        val alertId = seedAlert("merged-alert")
        val sharedEpisodeId = seedAlertEpisode("shared-episode")
        val sourceEpisodeId = seedAlertEpisode("source-episode")

        linkEpisode("target-shared-episode", target.incidentId, sharedEpisodeId)
        linkEpisode("source-shared-episode", source.incidentId, sharedEpisodeId)
        linkEpisode("source-only-episode", source.incidentId, sourceEpisodeId)
        linkUrlSource("target-runbook", target.incidentId, "runbook")
        linkUrlSource("source-runbook", source.incidentId, "runbook")
        linkUrlSource("source-dashboard", source.incidentId, "dashboard")
        service.execute(LinkOnCallAlertCommand("link-source-alert", actor(), source.incidentId, alertId))

        val merged =
            service.execute(
                MergeIncidentCommand(
                    commandKey = "merge-full",
                    actor = actor(),
                    incidentId = target.incidentId,
                    sourceIncidentId = source.incidentId,
                ),
            )

        assertEquals(NativeIncidentStatus.ACTIVE, merged.status)
        transaction {
            assertEquals(
                target.incidentId,
                OnCallIncidentAlerts.selectAll().single()[OnCallIncidentAlerts.incidentId],
            )
            assertEquals(
                target.incidentId,
                OnCallAlerts.selectAll().where { OnCallAlerts.id eq alertId }.single()[OnCallAlerts.declaredIncidentId],
            )
            assertEquals(
                setOf(sharedEpisodeId, sourceEpisodeId),
                NativeIncidentAlertEpisodeLinks
                    .selectAll()
                    .where { NativeIncidentAlertEpisodeLinks.incidentId eq target.incidentId }
                    .mapTo(mutableSetOf()) { it[NativeIncidentAlertEpisodeLinks.alertEpisodeId] },
            )
            assertEquals(
                0,
                NativeIncidentAlertEpisodeLinks
                    .selectAll()
                    .where { NativeIncidentAlertEpisodeLinks.incidentId eq source.incidentId }
                    .count()
                    .toInt(),
            )
            val targetSourceKeys =
                NativeIncidentSourceLinks
                    .selectAll()
                    .where { NativeIncidentSourceLinks.incidentId eq target.incidentId }
                    .map { it[NativeIncidentSourceLinks.sourceKey] }
            assertEquals(
                setOf(
                    "runbook",
                    "dashboard",
                    alertResourceId(alertId),
                    episodeResourceId(sharedEpisodeId),
                    episodeResourceId(sourceEpisodeId),
                ),
                targetSourceKeys.toSet(),
            )
            // The duplicated runbook and shared-episode links collapse instead of colliding.
            assertEquals(targetSourceKeys.size, targetSourceKeys.toSet().size)
            assertEquals(
                0,
                NativeIncidentSourceLinks
                    .selectAll()
                    .where { NativeIncidentSourceLinks.incidentId eq source.incidentId }
                    .count()
                    .toInt(),
            )

            val sourceRow = incidentRow(source.incidentId)
            assertEquals(NativeIncidentStatus.MERGED.wire, sourceRow[OnCallIncidents.status])
            assertNotNull(sourceRow[OnCallIncidents.mergedAt])
            assertEquals(target.incidentId, sourceRow[OnCallIncidents.mergedIntoIncidentId])
            assertNull(sourceRow[OnCallIncidents.cancelledAt])
            assertNull(sourceRow[OnCallIncidents.declinedAt])

            // The retired incident keeps its own audit history.
            assertTrue(timelineEventTypes(source.incidentId).contains("DECLARED"))
            assertTrue(timelineEventTypes(source.incidentId).contains("MERGED_INTO_INCIDENT"))

            val mergeEvent =
                NativeIncidentOutboxEvents
                    .selectAll()
                    .where { NativeIncidentOutboxEvents.eventType eq "INCIDENT_MERGED_SOURCE" }
                    .single()
            val payload = mergeEvent[NativeIncidentOutboxEvents.payload]
            assertEquals(NativeIncidentStatus.MERGED.wire, payload.getValue("status").jsonPrimitive.content)
            assertNotNull(payload["mergedIntoIncidentId"])
            assertNotNull(payload["mergedAt"])
        }
    }

    @Test
    fun `merged incidents are terminal and never reopen`() {
        val target = service.execute(activeDeclaration("terminal-target"))
        val source = service.execute(activeDeclaration("terminal-source"))
        service.execute(MergeIncidentCommand("terminal-merge", actor(), target.incidentId, source.incidentId))

        assertFailsWith<IncidentCommandConflictException> {
            service.execute(ReopenIncidentCommand("reopen-merged", actor(), source.incidentId))
        }
        assertFailsWith<IncidentCommandConflictException> {
            service.execute(
                TransitionIncidentCommand(
                    "resolve-merged",
                    actor(),
                    source.incidentId,
                    NativeIncidentStatus.RESOLVED,
                ),
            )
        }
        assertFailsWith<IncidentCommandConflictException> {
            service.execute(
                MergeIncidentCommand("merge-into-merged", actor(), source.incidentId, target.incidentId),
            )
        }
        transaction {
            assertEquals(NativeIncidentStatus.MERGED.wire, incidentRow(source.incidentId)[OnCallIncidents.status])
        }
    }

    @Test
    fun `repeated merges converge and losing merge races conflict`() {
        val target = service.execute(activeDeclaration("retry-target"))
        val source = service.execute(activeDeclaration("retry-source"))
        val other = service.execute(activeDeclaration("retry-other"))

        val first =
            service.execute(
                MergeIncidentCommand("retry-merge", actor(), target.incidentId, source.incidentId, expectedVersion = 1),
            )
        val replay =
            service.execute(
                MergeIncidentCommand("retry-merge", actor(), target.incidentId, source.incidentId, expectedVersion = 1),
            )
        val retryWithNewKey =
            service.execute(
                MergeIncidentCommand("retry-merge-again", actor(), target.incidentId, source.incidentId),
            )

        assertEquals(first.version, replay.version)
        assertEquals(first.version, retryWithNewKey.version)

        // Another responder already merged this source elsewhere.
        assertFailsWith<IncidentCommandConflictException> {
            service.execute(MergeIncidentCommand("merge-race", actor(), other.incidentId, source.incidentId))
        }
        // A stale expected version loses the optimistic-concurrency race on the target.
        assertFailsWith<IncidentCommandConflictException> {
            service.execute(
                MergeIncidentCommand("stale-merge", actor(), target.incidentId, other.incidentId, expectedVersion = 1),
            )
        }
    }

    @Test
    fun `merge sources and targets stay inside the caller organization`() {
        val target = service.execute(activeDeclaration("scoped-target"))
        val outsider = IncidentTestDatabase.seedMember("other-triage-org")
        val outsiderActor = IncidentCommandActor(outsider.organizationId, outsider.userId, "REST")
        val foreignSource =
            service.execute(
                DeclareIncidentCommand(
                    commandKey = "foreign-source",
                    actor = outsiderActor,
                    title = "Foreign incident",
                    description = null,
                    severity = "SEV-3",
                ),
            )

        assertFailsWith<IncidentCommandNotFoundException> {
            service.execute(
                MergeIncidentCommand("cross-org-merge", actor(), target.incidentId, foreignSource.incidentId),
            )
        }
        assertFailsWith<IncidentCommandNotFoundException> {
            service.execute(
                MergeIncidentCommand("cross-org-target", outsiderActor, target.incidentId, foreignSource.incidentId),
            )
        }
    }

    @Test
    fun `triage withholds actions until the incident is accepted`() {
        val triage = service.execute(triageDeclaration("triage-capability"))

        assertFailsWith<IncidentCommandDeniedException> {
            service.execute(AddIncidentActionCommand("triage-action", actor(), triage.incidentId, "Roll back"))
        }

        service.execute(
            AcceptIncidentCommand(
                commandKey = "accept-for-actions",
                actor = actor(),
                incidentId = triage.incidentId,
                expectedVersion = 1,
                severity = "SEV-3",
            ),
        )
        val withAction =
            service.execute(AddIncidentActionCommand("accepted-action", actor(), triage.incidentId, "Roll back"))

        assertEquals(3, withAction.version)
    }

    @Test
    fun `triage incidents still support investigation and participation`() {
        val triage = service.execute(triageDeclaration("triage-investigation"))

        val linked = linkUrlSource("triage-runbook", triage.incidentId, "runbook")
        val note =
            service.execute(
                AddIncidentTimelineEventCommand(
                    "triage-note",
                    actor(),
                    triage.incidentId,
                    "NOTE",
                    mapOf("note" to JsonPrimitive("Investigating")),
                ),
            )

        assertEquals(2, linked.version)
        assertEquals(3, note.version)
    }

    @Test
    fun `storage rejects unclassified accepted incidents and inconsistent merge state`() {
        assertFailsWith<Exception> {
            transaction {
                insertRawIncident(
                    status = NativeIncidentStatus.ACTIVE.wire,
                    severity = null,
                    accepted = true,
                )
            }
        }
        assertFailsWith<Exception> {
            transaction { insertRawIncident(status = NativeIncidentStatus.MERGED.wire, severity = "SEV-2") }
        }
        val selfMergeId =
            transaction { insertRawIncident(status = NativeIncidentStatus.TRIAGE.wire, severity = null) }
        assertFailsWith<Exception> {
            transaction {
                OnCallIncidents.update({ OnCallIncidents.id eq selfMergeId }) {
                    it[status] = NativeIncidentStatus.MERGED.wire
                    it[mergedAt] = Clock.System.now()
                    it[mergedIntoIncidentId] = selfMergeId
                }
            }
        }
        // Triage outcomes that never reached acceptance stay legitimately unclassified.
        transaction { insertRawIncident(status = NativeIncidentStatus.DECLINED.wire, severity = null) }
    }

    private fun insertRawIncident(status: String, severity: String?, accepted: Boolean = false): Int {
        val now = Clock.System.now()
        return OnCallIncidents.insertAndGetId {
            it[resourceId] = Uuid.random()
            it[organizationId] = member.organizationId
            it[title] = "Raw incident"
            it[OnCallIncidents.severity] = severity
            it[OnCallIncidents.status] = status
            it[version] = 1
            it[declaredBy] = member.userId
            it[declaredAt] = now
            it[acceptedAt] = now.takeIf { accepted }
            it[createdAt] = now
            it[updatedAt] = now
        }.value
    }

    private fun configureAcceptanceForm() {
        configurationService.createCustomField(
            member.organizationId,
            member.userId,
            CreateIncidentCustomField(
                stableKey = "customer_impact",
                name = "Customer impact",
                description = null,
                valueType = IncidentCustomFieldValueType.TEXT,
                catalogResourceType = null,
                options = emptyList(),
            ),
        )
        configurationService.createForm(
            member.organizationId,
            member.userId,
            CreateIncidentForm(
                incidentTypeResourceId = null,
                stage = IncidentFormStage.ACCEPTANCE,
                name = "Acceptance",
                fields = listOf(
                    IncidentFormFieldInput(
                        fieldId = customFieldResourceId("customer_impact"),
                        position = 0,
                        required = true,
                    ),
                ),
            ),
        )
    }

    private fun customFieldResourceId(key: String): String =
        configurationService
            .listCustomFields(member.organizationId)
            .single { it.key == key }
            .id

    private fun linkEpisode(commandKey: String, incidentId: Int, alertEpisodeId: Int) =
        service.execute(
            LinkIncidentSourceCommand(
                commandKey = commandKey,
                actor = actor(),
                incidentId = incidentId,
                source =
                    IncidentSourceReference(
                        sourceType = IncidentSourceType.ALERT_EPISODE,
                        sourceKey = "resolved-by-service",
                        alertEpisodeId = alertEpisodeId,
                    ),
            ),
        )

    private fun linkUrlSource(commandKey: String, incidentId: Int, sourceKey: String) =
        service.execute(
            LinkIncidentSourceCommand(
                commandKey = commandKey,
                actor = actor(),
                incidentId = incidentId,
                source =
                    IncidentSourceReference(
                        sourceType = IncidentSourceType.URL,
                        sourceKey = sourceKey,
                        sourceUrl = "https://example.test/$sourceKey",
                    ),
            ),
        )

    private fun actor() = IncidentCommandActor(member.organizationId, member.userId, "REST")

    private fun triageDeclaration(commandKey: String) =
        DeclareIncidentCommand(
            commandKey = commandKey,
            actor = actor(),
            title = "Unclassified report",
            description = null,
            initialStatus = NativeIncidentStatus.TRIAGE,
        )

    private fun activeDeclaration(commandKey: String) =
        DeclareIncidentCommand(
            commandKey = commandKey,
            actor = actor(),
            title = "Checkout unavailable",
            description = null,
            severity = "SEV-2",
        )

    private fun incidentRow(incidentId: Int) =
        OnCallIncidents.selectAll().where { OnCallIncidents.id eq incidentId }.single()

    private fun timelineEventTypes(incidentId: Int): Set<String> =
        OnCallIncidentTimeline
            .selectAll()
            .where { OnCallIncidentTimeline.incidentId eq incidentId }
            .mapTo(mutableSetOf()) { it[OnCallIncidentTimeline.eventType] }

    private fun episodeResourceId(episodeId: Int): String =
        AlertEpisodes
            .selectAll()
            .where { AlertEpisodes.id eq episodeId }
            .single()[AlertEpisodes.resourceId]
            .toString()

    private fun alertResourceId(alertId: Int): String =
        OnCallAlerts.selectAll().where { OnCallAlerts.id eq alertId }.single()[OnCallAlerts.resourceId].toString()

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

    private fun seedAlertEpisode(key: String): Int =
        transaction {
            val now = Clock.System.now()
            AlertEpisodes.insertAndGetId {
                it[organizationId] = member.organizationId
                it[sourceName] = "TEST"
                it[deduplicationKey] = key
                it[title] = "Episode $key"
                it[description] = null
                it[priority] = "P2"
                it[episodeSeq] = 1
                it[episodeKey] = "$key-1"
                it[status] = "FIRING"
                it[openedAt] = now
                it[lastSeenAt] = now
                it[createdAt] = now
                it[updatedAt] = now
            }.value
        }
}
