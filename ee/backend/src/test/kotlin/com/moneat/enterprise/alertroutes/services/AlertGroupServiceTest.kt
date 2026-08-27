// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.enterprise.alertroutes.AlertRouteTestDatabase
import com.moneat.enterprise.alertroutes.SeededAlertRouteOrganization
import com.moneat.enterprise.alertroutes.commands.AlertGroupPolicy
import com.moneat.enterprise.alertroutes.commands.AlertGroupQuotaAdmission
import com.moneat.enterprise.alertroutes.commands.AlertRouteActor
import com.moneat.enterprise.alertroutes.commands.AlertRouteGroupingInput
import com.moneat.enterprise.alertroutes.commands.AlertRoutePolicy
import com.moneat.enterprise.alertroutes.commands.AlertRoutePagingInput
import com.moneat.enterprise.alertroutes.commands.AlertRouteSpecification
import com.moneat.enterprise.alertroutes.commands.AlertRouteTargetInput
import com.moneat.enterprise.alertroutes.commands.CreateAlertRouteCommand
import com.moneat.enterprise.alertroutes.context.AlertRouteContext
import com.moneat.enterprise.alertroutes.context.AlertRouteEpisodeIdentity
import com.moneat.enterprise.alertroutes.evaluation.AlertRouteDecision
import com.moneat.enterprise.alertroutes.evaluation.AlertRouteEvaluator
import com.moneat.enterprise.alertroutes.models.AlertGroupDecisionType
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingWindowKind
import com.moneat.enterprise.alertroutes.models.AlertRoutePagingMode
import com.moneat.enterprise.alertroutes.models.AlertRouteTargetKind
import com.moneat.enterprise.alertroutes.models.AlertGroupEscalationState
import com.moneat.enterprise.alertroutes.models.AlertGroupPagingState
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertGroupEscalations
import com.moneat.enterprise.NativeIncidentQuotaDecision
import com.moneat.enterprise.NativeIncidentQuotaStatus
import com.moneat.enterprise.incidents.commands.IncidentCommandDeniedException
import com.moneat.enterprise.incidents.commands.IncidentCommandQuotaExceededException
import com.moneat.enterprise.incidents.commands.DeclareIncidentCommand
import com.moneat.enterprise.incidents.commands.IncidentCommandActor
import com.moneat.enterprise.incidents.commands.IncidentCommandPolicy
import com.moneat.enterprise.incidents.commands.IncidentCommandService
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AlertGroupServiceTest {
    private lateinit var organization: SeededAlertRouteOrganization
    private lateinit var routeService: AlertRouteCommandService
    private lateinit var groupService: AlertGroupService

    @BeforeEach
    fun setUp() {
        AlertRouteTestDatabase.reset()
        organization = AlertRouteTestDatabase.seedOrganization("alert-groups")
        routeService = AlertRouteCommandService(policy = AlertRoutePolicy.allowForTests())
        groupService = AlertGroupService(policy = AlertGroupPolicy.allowForTests())
    }

    @AfterEach
    fun tearDown() = AlertRouteTestDatabase.clearReference()

    @Test
    fun `partial tuples group while wholly missing tuples remain episode singletons`() {
        val route = createRoute(listOf("metadata.service", "metadata.environment"))
        val firstEpisode = seedEpisode("first")
        val secondEpisode = seedEpisode("second")
        val thirdEpisode = seedEpisode("third")
        val now = Instant.parse("2026-08-23T12:00:00Z")

        val first = record(route, firstEpisode, mapOf("service" to "checkout"), now)
        val second = record(route, secondEpisode, mapOf("service" to "checkout"), now + 1.seconds)
        val singleton = record(route, thirdEpisode, emptyMap(), now + 2.seconds)

        assertEquals(first.id, second.id)
        assertEquals(2, second.members.size)
        assertEquals(mapOf("metadata.service" to JsonPrimitive("checkout")), second.groupingTuple)
        assertTrue(!second.singleton)
        assertNotEquals(first.id, singleton.id)
        assertTrue(singleton.singleton)
        assertEquals(thirdEpisode.toString(), (singleton.groupingTuple["episode.identity"] as JsonPrimitive).content)
    }

    @Test
    fun `repeated episode firings update one membership and rolling boundary without paging duplication`() {
        val route = createRoute(listOf("metadata.service"), AlertRouteGroupingWindowKind.ROLLING)
        val episode = seedEpisode("repeat")
        val now = Instant.parse("2026-08-23T12:00:00Z")

        val first = record(route, episode, mapOf("service" to "checkout"), now)
        assertTrue(groupService.claimPaging(organization.organizationId, first.id, episode, "page-once"))
        groupService.completePaging(
            AlertGroupPagingCompletion(
                organization.organizationId,
                first.id,
                episode,
                "page-once",
                succeeded = true,
            ),
        )
        val repeated = record(route, episode, mapOf("service" to "checkout"), now + 30.seconds)

        assertEquals(first.id, repeated.id)
        assertEquals(1, repeated.members.size)
        assertEquals(now + 30.seconds, repeated.members.single().lastSeenAt)
        assertEquals(now + 90.seconds, repeated.closesAt)
        assertEquals(AlertGroupPagingState.DELIVERED, repeated.pagingState)
        assertTrue(!groupService.claimPaging(organization.organizationId, first.id, episode, "page-twice"))
    }

    @Test
    fun `fixed groups close at their original boundary and later episodes open a new group`() {
        val route = createRoute(listOf("metadata.service"), AlertRouteGroupingWindowKind.FIXED)
        val firstEpisode = seedEpisode("fixed-first")
        val secondEpisode = seedEpisode("fixed-second")
        val now = Instant.parse("2026-08-23T12:00:00Z")

        val first = record(route, firstEpisode, mapOf("service" to "checkout"), now)
        val second = record(route, secondEpisode, mapOf("service" to "checkout"), now + 60.seconds)
        val restarted = AlertGroupService(policy = AlertGroupPolicy.allowForTests())
        val context = context(firstEpisode, mapOf("service" to "checkout"))
        val refired = restarted.recordFiring(
            context,
            AlertRouteEvaluator().evaluate(listOf(route), context).decision!!,
            firstEpisode,
            now = now + 62.seconds,
        )

        assertNotEquals(first.id, second.id)
        assertEquals(first.id, refired.id)
        assertEquals(1, refired.members.size)
        assertEquals("CLOSED", refired.state.wire)
        assertEquals(now + 60.seconds, first.closesAt)
        assertEquals("CLOSED", groupService.get(organization.organizationId, first.id).state.wire)
    }

    @Test
    fun `suggested grouping persists an eligible incident candidate and decision`() {
        val route = createRoute(listOf("metadata.service"))
        val episode = seedEpisode("suggested-candidate")
        val incident = IncidentCommandService(policy = IncidentCommandPolicy.allowForTests()).execute(
            DeclareIncidentCommand(
                commandKey = "suggested-candidate-incident",
                actor = IncidentCommandActor(organization.organizationId, organization.adminUserId, "TEST"),
                title = "Checkout incident",
                description = null,
                severity = "SEV-2",
                initialStatus = NativeIncidentStatus.ACTIVE,
            ),
        )
        val context = context(episode, mapOf("service" to "checkout"))
        val group = groupService.recordFiring(
            context,
            AlertRouteEvaluator().evaluate(listOf(route), context).decision!!,
            episode,
            candidateIncidentId = Uuid.parse(incident.incidentResourceId),
            now = Instant.parse("2026-08-23T12:00:00Z"),
        )

        assertEquals(Uuid.parse(incident.incidentResourceId), group.candidateIncidentId)
        assertTrue(group.decisions.any { it.type == AlertGroupDecisionType.SUGGESTED })
    }

    @Test
    fun `entitlement and active group quota rejection leave existing history unchanged`() {
        val route = createRoute(listOf("metadata.service"))
        val existingEpisode = seedEpisode("existing")
        val now = Instant.parse("2026-08-23T12:00:00Z")
        record(route, existingEpisode, mapOf("service" to "checkout"), now)
        val deniedEpisode = seedEpisode("denied")
        val deniedContext = context(deniedEpisode, mapOf("service" to "payments"))
        val deniedDecision = AlertRouteEvaluator().evaluate(listOf(route), deniedContext).decision!!
        val quota = AlertGroupQuotaAdmission { _, key, usage, _ ->
            NativeIncidentQuotaDecision(false, NativeIncidentQuotaStatus(key, 1, usage), "quota exhausted")
        }
        val quotaDenied = AlertGroupService(AlertGroupPolicy(enabled = { true }, capacityAdmission = quota))

        assertFailsWith<IncidentCommandQuotaExceededException> {
            quotaDenied.recordFiring(deniedContext, deniedDecision, deniedEpisode, now = now)
        }
        val entitlementDenied = AlertGroupService(AlertGroupPolicy(enabled = { false }))
        assertFailsWith<IncidentCommandDeniedException> {
            entitlementDenied.recordFiring(deniedContext, deniedDecision, deniedEpisode, now = now)
        }
        assertEquals(1, groupService.list(organization.organizationId).size)
    }

    @Test
    fun `canonical identities do not collide for delimiter-like values`() {
        val route = createRoute(listOf("metadata.service", "metadata.environment"))
        val first = decision(route, seedEpisode("collision-a"), mapOf("service" to "a|b", "environment" to "c"))
        val second = decision(route, seedEpisode("collision-b"), mapOf("service" to "a", "environment" to "b|c"))

        assertNotEquals(first.grouping.groupKey, second.grouping.groupKey)
        assertNotEquals(first.grouping.tuple, second.grouping.tuple)
    }

    @Test
    fun `episode resolution and escalation state survive service restarts`() {
        val route = createRoute(listOf("metadata.service"))
        val episode = seedEpisode("lifecycle")
        val now = Instant.parse("2026-08-23T12:00:00Z")
        val group = record(route, episode, mapOf("service" to "checkout"), now)
        val policy = AlertRouteTestDatabase.seedEscalationPolicy(organization.organizationId, "Lifecycle")

        groupService.recordEscalation(
            AlertGroupEscalationRecord(
                organization.organizationId,
                group.id,
                policy.id,
                "lifecycle-page",
                onCallAlertId = null,
                state = AlertGroupEscalationState.PENDING,
            ),
        )
        AlertGroupService(AlertGroupPolicy.allowForTests()).recordEscalation(
            AlertGroupEscalationRecord(
                organization.organizationId,
                group.id,
                policy.id,
                "lifecycle-page",
                onCallAlertId = null,
                state = AlertGroupEscalationState.FAILED,
            ),
        )
        val resolved = groupService.recordResolution(organization.organizationId, episode, now + 5.seconds)!!

        assertEquals(now + 5.seconds, resolved.members.single().resolvedAt)
        assertEquals(group.id, groupService.list(organization.organizationId).single().id)
        assertEquals(group.id, groupService.get(organization.organizationId, group.id).id)
        transaction {
            val escalations = EnterpriseAlertGroupEscalations.selectAll().toList()
            assertEquals(1, escalations.size)
            assertEquals(AlertGroupEscalationState.FAILED.wire,
                escalations.single()[EnterpriseAlertGroupEscalations.state])
        }
        val ungrouped = seedEpisode("ungrouped")
        assertEquals(null, groupService.recordResolution(organization.organizationId, ungrouped, now))
    }

    @Test
    fun `every episode paging owns an independent durable claim`() {
        val route = createRoute(
            listOf("metadata.service"),
            pagingMode = AlertRoutePagingMode.EVERY_EPISODE,
        )
        val firstEpisode = seedEpisode("every-first")
        val secondEpisode = seedEpisode("every-second")
        val now = Instant.parse("2026-08-23T12:00:00Z")
        val first = record(route, firstEpisode, mapOf("service" to "checkout"), now)
        record(route, secondEpisode, mapOf("service" to "checkout"), now + 1.seconds)

        assertTrue(groupService.claimPaging(
            organization.organizationId,
            first.id,
            firstEpisode,
            "every-first",
        ))
        assertTrue(groupService.claimPaging(
            organization.organizationId,
            first.id,
            secondEpisode,
            "every-second",
        ))
        groupService.completePaging(
            AlertGroupPagingCompletion(
                organization.organizationId,
                first.id,
                secondEpisode,
                "every-second",
                succeeded = false,
            ),
        )

        val members = groupService.get(organization.organizationId, first.id).members.associateBy { it.episodeId }
        assertEquals(AlertGroupPagingState.CLAIMED, members.getValue(firstEpisode).pagingState)
        assertEquals(AlertGroupPagingState.FAILED, members.getValue(secondEpisode).pagingState)
        assertTrue(groupService.claimPaging(
            organization.organizationId,
            first.id,
            secondEpisode,
            "every-second-retry",
        ))
    }

    @Test
    fun `failed first episode group paging can be claimed again`() {
        val route = createRoute(listOf("metadata.service"))
        val episode = seedEpisode("retry-first")
        val group = record(route, episode, mapOf("service" to "checkout"), Instant.parse("2026-08-23T12:00:00Z"))
        assertTrue(groupService.claimPaging(organization.organizationId, group.id, episode, "first-attempt"))
        groupService.completePaging(
            AlertGroupPagingCompletion(
                organization.organizationId,
                group.id,
                episode,
                "first-attempt",
                succeeded = false,
            ),
        )

        assertTrue(groupService.claimPaging(organization.organizationId, group.id, episode, "retry-attempt"))
    }

    private fun createRoute(
        keys: List<String>,
        windowKind: AlertRouteGroupingWindowKind = AlertRouteGroupingWindowKind.ROLLING,
        pagingMode: AlertRoutePagingMode = AlertRoutePagingMode.FIRST_EPISODE_PER_GROUP,
    ): com.moneat.enterprise.alertroutes.models.AlertRouteDefinition {
        val policy = AlertRouteTestDatabase.seedEscalationPolicy(organization.organizationId, "Durable grouping")
        return routeService.execute(
            CreateAlertRouteCommand(
                commandKey = "route-${Uuid.random()}",
                actor = AlertRouteActor(organization.organizationId, organization.adminUserId, "TEST"),
                specification = AlertRouteSpecification(
                    key = "route-${Uuid.random()}",
                    name = "Durable grouping",
                    paging = AlertRoutePagingInput(
                        mode = pagingMode,
                        targets = listOf(
                            AlertRouteTargetInput(
                                kind = AlertRouteTargetKind.ESCALATION_POLICY,
                                escalationPolicyId = policy.resourceId,
                            ),
                        ),
                    ),
                    grouping = AlertRouteGroupingInput(keys = keys, windowKind = windowKind, windowSeconds = 60),
                ),
            ),
        ).route!!
    }

    private fun seedEpisode(key: String): Uuid =
        AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "DASHBOARD_ALERT", key)

    private fun record(
        route: com.moneat.enterprise.alertroutes.models.AlertRouteDefinition,
        episodeId: Uuid,
        metadata: Map<String, String>,
        now: Instant,
    ): AlertGroupRecord {
        val context = context(episodeId, metadata)
        return groupService.recordFiring(context, AlertRouteEvaluator().evaluate(listOf(route), context).decision!!,
            episodeId, now = now)
    }

    private fun decision(
        route: com.moneat.enterprise.alertroutes.models.AlertRouteDefinition,
        episodeId: Uuid,
        metadata: Map<String, String>,
    ): AlertRouteDecision {
        val context = context(episodeId, metadata)
        return AlertRouteEvaluator().evaluate(listOf(route), context).decision!!
    }

    private fun context(episodeId: Uuid, metadata: Map<String, String>) =
        AlertRouteContext(
            organizationId = organization.organizationId,
            source = "DASHBOARD_ALERT",
            status = "FIRING",
            priority = "P1",
            title = "Checkout alert",
            description = "Latency above threshold",
            deduplicationKey = episodeId.toString(),
            url = "",
            episode = AlertRouteEpisodeIdentity(episodeId.toString(), "$episodeId#1", 1, "OPEN"),
            metadata = metadata,
        )
}
