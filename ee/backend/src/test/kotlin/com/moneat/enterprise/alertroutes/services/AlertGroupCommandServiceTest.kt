// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.enterprise.alertroutes.AlertRouteTestDatabase
import com.moneat.enterprise.alertroutes.SeededAlertRouteOrganization
import com.moneat.enterprise.alertroutes.commands.AlertGroupActor
import com.moneat.enterprise.alertroutes.commands.AlertGroupPolicy
import com.moneat.enterprise.alertroutes.commands.AttachAlertGroupIncidentCommand
import com.moneat.enterprise.alertroutes.commands.CreateAlertGroupTriageCommand
import com.moneat.enterprise.alertroutes.commands.MarkAlertGroupEpisodeUnrelatedCommand
import com.moneat.enterprise.alertroutes.commands.RemoveAlertGroupEpisodeCommand
import com.moneat.enterprise.alertroutes.commands.AlertRouteActor
import com.moneat.enterprise.alertroutes.commands.AlertRouteGroupingInput
import com.moneat.enterprise.alertroutes.commands.AlertRoutePolicy
import com.moneat.enterprise.alertroutes.commands.AlertRouteSpecification
import com.moneat.enterprise.alertroutes.commands.CreateAlertRouteCommand
import com.moneat.enterprise.alertroutes.context.AlertRouteContext
import com.moneat.enterprise.alertroutes.context.AlertRouteEpisodeIdentity
import com.moneat.enterprise.alertroutes.evaluation.AlertRouteEvaluator
import com.moneat.enterprise.alertroutes.models.AlertGroupDecisionType
import com.moneat.enterprise.alertroutes.models.AlertGroupMemberState
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingBehavior
import com.moneat.enterprise.incidents.commands.DeclareIncidentCommand
import com.moneat.enterprise.incidents.commands.IncidentCommandActor
import com.moneat.enterprise.incidents.commands.IncidentCommandConflictException
import com.moneat.enterprise.incidents.commands.IncidentCommandDeniedException
import com.moneat.enterprise.incidents.commands.IncidentCommandNotFoundException
import com.moneat.enterprise.incidents.commands.IncidentCommandPolicy
import com.moneat.enterprise.incidents.commands.IncidentCommandService
import com.moneat.enterprise.incidents.models.NativeIncidentSourceLinks
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.incidents.models.NativeIncidentVisibility
import com.moneat.enterprise.oncall.models.OnCallIncidents
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AlertGroupCommandServiceTest {
    private lateinit var organization: SeededAlertRouteOrganization
    private lateinit var groupService: AlertGroupService
    private lateinit var commandService: AlertGroupCommandService
    private lateinit var incidentService: IncidentCommandService

    @BeforeEach
    fun setUp() {
        AlertRouteTestDatabase.reset()
        organization = AlertRouteTestDatabase.seedOrganization("alert-group-commands")
        groupService = AlertGroupService(AlertGroupPolicy.allowForTests())
        incidentService = IncidentCommandService(policy = IncidentCommandPolicy.allowForTests())
        commandService = AlertGroupCommandService(
            policy = AlertGroupPolicy.allowForTests(),
            incidentCommands = incidentService,
        )
    }

    @AfterEach
    fun tearDown() = AlertRouteTestDatabase.clearReference()

    @Test
    fun `unrelated and remove commands are versioned idempotent and retain history`() {
        val episode = AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "HOST_ALERT", "member")
        val group = createGroup(episode)
        val actor = AlertGroupActor(organization.organizationId, organization.adminUserId, "TEST")
        val unrelated = MarkAlertGroupEpisodeUnrelatedCommand("unrelated", actor, group.id, group.version, episode)

        val changed = commandService.execute(unrelated)
        val replayed = commandService.execute(unrelated)

        assertEquals(AlertGroupMemberState.UNRELATED, changed.group.members.single().state)
        assertTrue(changed.group.decisions.any { it.type == AlertGroupDecisionType.MARKED_UNRELATED })
        assertEquals(changed.group.version, replayed.group.version)
        assertTrue(replayed.replayed)
        assertFailsWith<IncidentCommandConflictException> {
            commandService.execute(RemoveAlertGroupEpisodeCommand("remove-stale", actor, group.id, 1, episode))
        }
    }

    @Test
    fun `creating triage attaches the group and links its active episode source`() {
        val episode = AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "HOST_ALERT", "triage")
        val group = createGroup(episode)
        val actor = AlertGroupActor(organization.organizationId, organization.adminUserId, "TEST")

        val result = commandService.execute(
            CreateAlertGroupTriageCommand(
                commandKey = "create-triage",
                actor = actor,
                groupId = group.id,
                expectedVersion = group.version,
                title = "Checkout unavailable",
            ),
        )

        assertNotNull(result.group.incidentId)
        assertTrue(result.group.decisions.any { it.type == AlertGroupDecisionType.TRIAGE_CREATED })
        transaction {
            val incident = OnCallIncidents.selectAll().where {
                OnCallIncidents.resourceId eq result.group.incidentId
            }.single()
            assertEquals(NativeIncidentStatus.TRIAGE.wire, incident[OnCallIncidents.status])
            assertEquals(1, NativeIncidentSourceLinks.selectAll().count())
        }
    }

    @Test
    fun `group and episode UUIDs cannot cross organization scope`() {
        val episode = AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "HOST_ALERT", "scoped")
        val group = createGroup(episode)
        val other = AlertRouteTestDatabase.seedOrganization("other-alert-group-org")
        val actor = AlertGroupActor(other.organizationId, other.adminUserId, "TEST")

        assertFailsWith<IncidentCommandNotFoundException> {
            commandService.execute(
                MarkAlertGroupEpisodeUnrelatedCommand("cross-org", actor, group.id, group.version, episode),
            )
        }
    }

    @Test
    fun `automatic attachment requires route opt in and an eligible organization incident`() {
        val episode = AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "HOST_ALERT", "automatic")
        val incident = incidentService.execute(
            DeclareIncidentCommand(
                commandKey = "active-incident",
                actor = IncidentCommandActor(organization.organizationId, organization.adminUserId, "TEST"),
                title = "Checkout incident",
                description = null,
                severity = "SEV-2",
                initialStatus = NativeIncidentStatus.ACTIVE,
            ),
        )
        val group = createGroup(episode, AlertRouteGroupingBehavior.AUTOMATIC)
        val actor = AlertGroupActor(organization.organizationId, organization.adminUserId, "TEST")

        val attached = commandService.execute(
            AttachAlertGroupIncidentCommand(
                commandKey = "automatic-attach",
                actor = actor,
                groupId = group.id,
                expectedVersion = group.version,
                incidentId = Uuid.parse(incident.incidentResourceId),
                automatic = true,
            ),
        )

        assertEquals(Uuid.parse(incident.incidentResourceId), attached.group.incidentId)
        assertTrue(attached.group.decisions.any { it.type == AlertGroupDecisionType.AUTOMATICALLY_ATTACHED })
    }

    @Test
    fun `automatic attachment rejects suggested groups and resolved incidents`() {
        val actor = AlertGroupActor(organization.organizationId, organization.adminUserId, "TEST")
        val incident = incidentService.execute(
            DeclareIncidentCommand(
                commandKey = "automatic-negative-incident",
                actor = IncidentCommandActor(organization.organizationId, organization.adminUserId, "TEST"),
                title = "Checkout incident",
                description = null,
                severity = "SEV-2",
                initialStatus = NativeIncidentStatus.ACTIVE,
            ),
        )
        val incidentId = Uuid.parse(incident.incidentResourceId)
        val suggestedEpisode = AlertRouteTestDatabase.seedAlertEpisode(
            organization.organizationId,
            "HOST_ALERT",
            "suggested-negative",
        )
        val suggestedGroup = createGroup(suggestedEpisode)

        assertFailsWith<IncidentCommandConflictException> {
            commandService.execute(
                AttachAlertGroupIncidentCommand(
                    "automatic-suggested",
                    actor,
                    suggestedGroup.id,
                    suggestedGroup.version,
                    incidentId,
                    automatic = true,
                ),
            )
        }

        transaction {
            OnCallIncidents.update({ OnCallIncidents.resourceId eq incidentId }) {
                it[status] = NativeIncidentStatus.RESOLVED.wire
            }
        }
        val automaticEpisode = AlertRouteTestDatabase.seedAlertEpisode(
            organization.organizationId,
            "HOST_ALERT",
            "resolved-negative",
        )
        val automaticGroup = createGroup(automaticEpisode, AlertRouteGroupingBehavior.AUTOMATIC)
        assertFailsWith<IncidentCommandConflictException> {
            commandService.execute(
                AttachAlertGroupIncidentCommand(
                    "automatic-resolved",
                    actor,
                    automaticGroup.id,
                    automaticGroup.version,
                    incidentId,
                    automatic = true,
                ),
            )
        }
    }

    @Test
    fun `private incident attachment requires incident access`() {
        val episode = AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "HOST_ALERT", "private")
        val group = createGroup(episode)
        val incident = incidentService.execute(
            DeclareIncidentCommand(
                commandKey = "private-incident",
                actor = IncidentCommandActor(organization.organizationId, organization.adminUserId, "TEST"),
                title = "Private checkout incident",
                description = null,
                severity = "SEV-2",
                visibility = NativeIncidentVisibility.PRIVATE,
                initialStatus = NativeIncidentStatus.ACTIVE,
            ),
        )
        val memberUserId = AlertRouteTestDatabase.seedUser(organization.organizationId, "private-member")
        val actor = AlertGroupActor(organization.organizationId, memberUserId, "SLACK")

        assertFailsWith<IncidentCommandDeniedException> {
            commandService.execute(
                AttachAlertGroupIncidentCommand(
                    commandKey = "private-attach-denied",
                    actor = actor,
                    groupId = group.id,
                    expectedVersion = group.version,
                    incidentId = Uuid.parse(incident.incidentResourceId),
                ),
            )
        }
    }

    @Test
    fun `disassociated episode refiring does not extend or reactivate its group`() {
        val episode = AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "HOST_ALERT", "unrelated")
        val group = createGroup(episode)
        val resolvedAt = Instant.parse("2026-08-23T12:00:05Z")
        groupService.recordResolution(organization.organizationId, episode, resolvedAt)
        val actor = AlertGroupActor(organization.organizationId, organization.adminUserId, "TEST")
        commandService.execute(
            MarkAlertGroupEpisodeUnrelatedCommand("unrelated-refire", actor, group.id, group.version, episode),
        )
        val context = AlertRouteContext(
            organizationId = organization.organizationId,
            source = "HOST_ALERT",
            status = "FIRING",
            priority = "P1",
            title = "Checkout unavailable",
            description = "Health checks failed",
            deduplicationKey = episode.toString(),
            url = "",
            episode = AlertRouteEpisodeIdentity(episode.toString(), "$episode#1", 1, "OPEN"),
            metadata = mapOf("service" to "checkout"),
        )
        val route = AlertRouteCommandService(policy = AlertRoutePolicy.allowForTests()).get(
            organization.organizationId,
            group.routeId,
        )
        val refired = groupService.recordFiring(
            context,
            AlertRouteEvaluator().evaluate(listOf(route), context).decision!!,
            episode,
            now = Instant.parse("2026-08-23T12:00:30Z"),
        )

        assertEquals(group.closesAt, refired.closesAt)
        assertEquals(AlertGroupMemberState.UNRELATED, refired.members.single().state)
        assertEquals(resolvedAt, refired.members.single().resolvedAt)
        assertEquals(resolvedAt, refired.members.single().lastSeenAt)
    }

    private fun createGroup(
        episodeId: Uuid,
        behavior: AlertRouteGroupingBehavior = AlertRouteGroupingBehavior.SUGGESTED,
    ): AlertGroupRecord {
        val route = AlertRouteCommandService(policy = AlertRoutePolicy.allowForTests()).execute(
            CreateAlertRouteCommand(
                commandKey = "route-${Uuid.random()}",
                actor = AlertRouteActor(organization.organizationId, organization.adminUserId, "TEST"),
                specification = AlertRouteSpecification(
                    key = "group-${Uuid.random()}",
                    name = "Command grouping",
                    grouping = AlertRouteGroupingInput(
                        behavior = behavior,
                        keys = listOf("metadata.service"),
                        windowSeconds = 60,
                    ),
                ),
            ),
        ).route!!
        val context = AlertRouteContext(
            organizationId = organization.organizationId,
            source = "HOST_ALERT",
            status = "FIRING",
            priority = "P1",
            title = "Checkout unavailable",
            description = "Health checks failed",
            deduplicationKey = episodeId.toString(),
            url = "",
            episode = AlertRouteEpisodeIdentity(episodeId.toString(), "$episodeId#1", 1, "OPEN"),
            metadata = mapOf("service" to "checkout"),
        )
        val decision = AlertRouteEvaluator().evaluate(listOf(route), context).decision!!
        return groupService.recordFiring(context, decision, episodeId, now = Instant.parse("2026-08-23T12:00:00Z"))
    }
}
