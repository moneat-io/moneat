// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.alerts.models.AlertEpisodes
import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.alerts.models.AlertPriority
import com.moneat.alerts.models.AlertSource
import com.moneat.alerts.models.AlertStatus
import com.moneat.alerts.services.AlertEpisodeContext
import com.moneat.alerts.services.AlertFanoutContext
import com.moneat.enterprise.alertroutes.AlertRouteTestDatabase
import com.moneat.enterprise.alertroutes.SeededAlertRouteOrganization
import com.moneat.enterprise.alertroutes.commands.AlertGroupPolicy
import com.moneat.enterprise.alertroutes.commands.AlertRouteActor
import com.moneat.enterprise.alertroutes.commands.AlertRouteGroupingInput
import com.moneat.enterprise.alertroutes.commands.AlertRouteIncidentInput
import com.moneat.enterprise.alertroutes.commands.AlertRoutePagingInput
import com.moneat.enterprise.alertroutes.commands.AlertRoutePolicy
import com.moneat.enterprise.alertroutes.commands.AlertRouteRecoveryInput
import com.moneat.enterprise.alertroutes.commands.AlertRouteSpecification
import com.moneat.enterprise.alertroutes.commands.AlertRouteTargetInput
import com.moneat.enterprise.alertroutes.commands.CreateAlertRouteCommand
import com.moneat.enterprise.alertroutes.models.AlertGroupDecisionType
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingBehavior
import com.moneat.enterprise.alertroutes.models.AlertRouteIncidentMode
import com.moneat.enterprise.alertroutes.models.AlertRoutePagingMode
import com.moneat.enterprise.alertroutes.models.AlertRouteTargetKind
import com.moneat.enterprise.incidents.commands.IncidentCommandPolicy
import com.moneat.enterprise.incidents.commands.IncidentCommandService
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.incidents.models.NativeIncidentSourceLinks
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.enterprise.oncall.models.OnCallAlerts
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AlertRouteExecutionServiceTest {
    private lateinit var organization: SeededAlertRouteOrganization
    private lateinit var routeService: AlertRouteCommandService
    private lateinit var groupService: AlertGroupService
    private lateinit var executionService: AlertRouteExecutionService
    private lateinit var gateway: RecordingGateway
    private lateinit var currentTime: AtomicReference<Instant>

    @BeforeEach
    fun setUp() {
        AlertRouteTestDatabase.reset()
        organization = AlertRouteTestDatabase.seedOrganization("route-execution")
        routeService = AlertRouteCommandService(AlertRoutePolicy.allowForTests())
        groupService = AlertGroupService(AlertGroupPolicy.allowForTests())
        val incidentService = IncidentCommandService(policy = IncidentCommandPolicy.allowForTests())
        gateway = RecordingGateway()
        currentTime = AtomicReference(Instant.parse("2026-08-23T12:00:00Z"))
        executionService = AlertRouteExecutionService(
            evaluationService = AlertRouteEvaluationService(policy = AlertRoutePolicy.allowForTests()),
            groupService = groupService,
            groupCommands = AlertGroupCommandService(AlertGroupPolicy.allowForTests(), incidentService),
            incidentCommands = incidentService,
            onCallGateway = gateway,
            now = currentTime::get,
        )
    }

    @AfterEach
    fun tearDown() = AlertRouteTestDatabase.clearReference()

    @Test
    fun `matched route pages every target once and creates one active incident across retries`() = runBlocking {
        createRoute(
            pagingTargets = 2,
            incident = AlertRouteIncidentInput(
                create = true,
                mode = AlertRouteIncidentMode.ACTIVE,
                titleTemplate = "{{alert.title}} incident",
                severity = "SEV-1",
            ),
        )
        val episodeId = AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "DASHBOARD_ALERT", "retry")
        val context = firingContext(episodeId)

        executionService.execute(context)
        executionService.execute(context)
        val secondEpisode = AlertRouteTestDatabase.seedAlertEpisode(
            organization.organizationId,
            "DASHBOARD_ALERT",
            "retry-second-member",
        )
        executionService.execute(firingContext(secondEpisode))

        assertEquals(2, gateway.triggered.size)
        val listed = groupService.list(organization.organizationId).single()
        val group = groupService.get(organization.organizationId, listed.id)
        assertEquals(2, groupService.escalations(organization.organizationId, group.id).size)
        assertNotNull(group.incidentId)
        transaction {
            val incident = OnCallIncidents.selectAll().single()
            assertEquals(NativeIncidentStatus.ACTIVE.wire, incident[OnCallIncidents.status])
            assertEquals("Checkout latency incident", incident[OnCallIncidents.title])
        }
    }

    @Test
    fun `silence retains a group without paging or automatic incident creation`() = runBlocking {
        createRoute(
            pagingTargets = 1,
            incident = AlertRouteIncidentInput(create = true),
        )
        val episodeId = AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "HOST_ALERT", "silent")

        executionService.execute(firingContext(episodeId).copy(deliverySilenced = true))

        assertTrue(gateway.triggered.isEmpty())
        val listed = groupService.list(organization.organizationId).single()
        val group = groupService.get(organization.organizationId, listed.id)
        assertEquals(null, group.incidentId)
        assertEquals(1, group.members.size)
    }

    @Test
    fun `resolution cancels route escalation and declines only unaccepted triage`() = runBlocking {
        createRoute(
            pagingTargets = 1,
            incident = AlertRouteIncidentInput(create = true),
            recovery = AlertRouteRecoveryInput(cancelEscalations = true, declineUnacceptedTriage = true),
        )
        val episodeId = AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "HOST_ALERT", "recover")
        executionService.execute(firingContext(episodeId))

        gateway.resolveResult = false
        currentTime.set(currentTime.get() + 1.seconds)
        executionService.execute(firingContext(episodeId, AlertStatus.RESOLVED))

        assertEquals(1, gateway.resolved.size)
        val listed = groupService.list(organization.organizationId).single()
        val group = groupService.get(organization.organizationId, listed.id)
        assertTrue(group.decisions.any { it.type == AlertGroupDecisionType.TRIAGE_CREATED })
        transaction {
            assertEquals(
                NativeIncidentStatus.DECLINED.wire,
                OnCallIncidents.selectAll().single()[OnCallIncidents.status],
            )
        }
    }

    @Test
    fun `dedup hit after a post-trigger crash reconciles the existing on-call alert`() = runBlocking {
        createRoute(pagingTargets = 1, incident = AlertRouteIncidentInput())
        gateway.returnNullAfterInsert = true
        val episodeId = AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "HOST_ALERT", "dedup")

        executionService.execute(firingContext(episodeId))

        val group = groupService.list(organization.organizationId).single()
        assertEquals(1, gateway.triggered.size)
        assertEquals(
            com.moneat.enterprise.alertroutes.models.AlertGroupEscalationState.TRIGGERED,
            groupService.escalations(organization.organizationId, group.id).single().state,
        )
    }

    @Test
    fun `stale paging claim is safely reclaimed after a process interruption`() = runBlocking {
        createRoute(pagingTargets = 1, incident = AlertRouteIncidentInput())
        gateway.cancelNext = true
        val episodeId = AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "HOST_ALERT", "claim")
        val context = firingContext(episodeId)

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            runBlocking { executionService.execute(context) }
        }
        currentTime.set(currentTime.get() + 5.minutes + 1.seconds)
        executionService.execute(context)

        assertEquals(1, gateway.triggered.size)
    }

    @Test
    fun `stale every-episode claim cannot reclaim a fresh member claim`() = runBlocking {
        createRoute(
            pagingTargets = 1,
            incident = AlertRouteIncidentInput(),
            pagingMode = AlertRoutePagingMode.EVERY_EPISODE,
        )
        val staleEpisode = AlertRouteTestDatabase.seedAlertEpisode(
            organization.organizationId,
            "HOST_ALERT",
            "stale-member",
        )
        gateway.cancelNext = true
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            runBlocking { executionService.execute(firingContext(staleEpisode)) }
        }

        currentTime.set(currentTime.get() + 5.minutes + 1.seconds)
        val freshEpisode = AlertRouteTestDatabase.seedAlertEpisode(
            organization.organizationId,
            "HOST_ALERT",
            "fresh-member",
        )
        gateway.cancelNext = true
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            runBlocking { executionService.execute(firingContext(freshEpisode)) }
        }

        executionService.execute(firingContext(freshEpisode))
        assertTrue(gateway.triggered.isEmpty())
        executionService.execute(firingContext(staleEpisode))
        assertEquals(1, gateway.triggered.size)
    }

    @Test
    fun `closed incident link does not prevent independent paging`() = runBlocking {
        createRoute(
            pagingTargets = 1,
            incident = AlertRouteIncidentInput(create = true),
            pagingMode = AlertRoutePagingMode.EVERY_EPISODE,
        )
        val first = AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "HOST_ALERT", "closed-1")
        executionService.execute(firingContext(first))
        transaction {
            OnCallIncidents.update({ OnCallIncidents.id eq OnCallIncidents.selectAll().single()[OnCallIncidents.id] }) {
                it[status] = NativeIncidentStatus.RESOLVED.wire
            }
        }
        val second = AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "HOST_ALERT", "closed-2")

        executionService.execute(firingContext(second))

        assertEquals(2, gateway.triggered.size)
        transaction { assertEquals(1, NativeIncidentSourceLinks.selectAll().count()) }
    }

    @Test
    fun `failed multi-target paging retries only targets not already triggered`() = runBlocking {
        createRoute(pagingTargets = 2, incident = AlertRouteIncidentInput())
        gateway.failAfterSuccesses = 1
        val episodeId = AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "HOST_ALERT", "retry-page")
        val context = firingContext(episodeId)

        assertFailsWith<IllegalStateException> { runBlocking { executionService.execute(context) } }
        executionService.execute(context)

        assertEquals(3, gateway.attempted.size)
        assertEquals(2, gateway.triggered.size)
        assertEquals(2, gateway.triggered.distinct().size)
    }

    @Test
    fun `automatic grouping reuses an eligible incident only after the first group window`() = runBlocking {
        createRoute(
            pagingTargets = 0,
            incident = AlertRouteIncidentInput(create = true),
            grouping = AlertRouteGroupingInput(
                behavior = AlertRouteGroupingBehavior.AUTOMATIC,
                keys = listOf("alert.source"),
                windowSeconds = 60,
            ),
        )
        val firstEpisode = AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "HOST_ALERT", "first")
        executionService.execute(firingContext(firstEpisode))
        val firstGroup = groupService.list(organization.organizationId).single()

        currentTime.set(currentTime.get() + 61.seconds)
        val secondEpisode = AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "HOST_ALERT", "second")
        executionService.execute(firingContext(secondEpisode))

        val groups = groupService.list(organization.organizationId)
        assertEquals(2, groups.size)
        val secondGroup = groupService.get(organization.organizationId, groups.single { it.id != firstGroup.id }.id)
        assertEquals(firstGroup.incidentId, secondGroup.incidentId)
        assertTrue(secondGroup.decisions.any { it.type == AlertGroupDecisionType.AUTOMATICALLY_ATTACHED })
        transaction {
            assertEquals(1, OnCallIncidents.selectAll().count())
            assertEquals(2, NativeIncidentSourceLinks.selectAll().count())
        }
    }

    @Test
    fun `suggested grouping persists the candidate decision without attaching`() = runBlocking {
        createRoute(
            pagingTargets = 0,
            incident = AlertRouteIncidentInput(create = true),
            grouping = AlertRouteGroupingInput(
                behavior = AlertRouteGroupingBehavior.SUGGESTED,
                keys = listOf("alert.source"),
                windowSeconds = 60,
            ),
        )
        val firstEpisode = AlertRouteTestDatabase.seedAlertEpisode(
            organization.organizationId,
            "HOST_ALERT",
            "suggest-1",
        )
        executionService.execute(firingContext(firstEpisode))
        val firstGroup = groupService.list(organization.organizationId).single()

        currentTime.set(currentTime.get() + 61.seconds)
        val secondEpisode = AlertRouteTestDatabase.seedAlertEpisode(
            organization.organizationId,
            "HOST_ALERT",
            "suggest-2",
        )
        executionService.execute(firingContext(secondEpisode))

        val second = groupService.list(organization.organizationId).single { it.id != firstGroup.id }
        val secondGroup = groupService.get(organization.organizationId, second.id)
        assertEquals(null, secondGroup.incidentId)
        assertEquals(firstGroup.incidentId, secondGroup.candidateIncidentId)
        assertTrue(secondGroup.decisions.any { it.type == AlertGroupDecisionType.SUGGESTED })
    }

    @Test
    fun `every episode paging pages each member in one durable group`() = runBlocking {
        createRoute(
            pagingTargets = 1,
            incident = AlertRouteIncidentInput(),
            pagingMode = AlertRoutePagingMode.EVERY_EPISODE,
        )
        val first = AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "HOST_ALERT", "every-1")
        val second = AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "HOST_ALERT", "every-2")

        executionService.execute(firingContext(first))
        executionService.execute(firingContext(second))
        executionService.execute(firingContext(second))

        assertEquals(2, gateway.triggered.size)
        assertEquals(1, groupService.list(organization.organizationId).size)
    }

    @Test
    fun `recovery worker completes grace-delayed automation after restart`() = runBlocking {
        createRoute(
            pagingTargets = 1,
            incident = AlertRouteIncidentInput(create = true),
            recovery = AlertRouteRecoveryInput(
                cancelEscalations = true,
                declineUnacceptedTriage = true,
                graceSeconds = 60,
            ),
        )
        val episodeId = AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "HOST_ALERT", "grace")
        executionService.execute(firingContext(episodeId))
        currentTime.set(currentTime.get() + 1.seconds)
        executionService.execute(firingContext(episodeId, AlertStatus.RESOLVED))
        transaction {
            assertEquals(NativeIncidentStatus.TRIAGE.wire, OnCallIncidents.selectAll().single()[OnCallIncidents.status])
        }

        currentTime.set(currentTime.get() + 60.seconds)
        executionService.recoverDue { true }

        assertEquals(1, gateway.resolved.size)
        transaction {
            assertEquals(
                NativeIncidentStatus.DECLINED.wire,
                OnCallIncidents.selectAll().single()[OnCallIncidents.status],
            )
        }
    }

    @Test
    fun `recovery isolates candidates and revisits groups closed by their window`() = runBlocking {
        createRoute(
            pagingTargets = 1,
            incident = AlertRouteIncidentInput(create = true),
            recovery = AlertRouteRecoveryInput(
                cancelEscalations = true,
                declineUnacceptedTriage = true,
                graceSeconds = 60,
            ),
            grouping = AlertRouteGroupingInput(
                keys = listOf("alert.deduplication_key"),
                windowSeconds = 60,
            ),
        )
        val first = AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "HOST_ALERT", "isolate-1")
        executionService.execute(firingContext(first))
        currentTime.set(currentTime.get() + 61.seconds)
        val second = AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "HOST_ALERT", "isolate-2")
        executionService.execute(firingContext(second))
        currentTime.set(currentTime.get() + 1.seconds)
        executionService.execute(firingContext(first, AlertStatus.RESOLVED))
        currentTime.set(currentTime.get() + 1.seconds)
        executionService.execute(firingContext(second, AlertStatus.RESOLVED))

        gateway.throwResolveOnce = true
        currentTime.set(currentTime.get() + 60.seconds)
        executionService.recoverDue { true }

        transaction {
            val statuses = OnCallIncidents.selectAll().map { it[OnCallIncidents.status] }
            assertEquals(2, statuses.size, statuses.toString())
            assertEquals(1, statuses.count { it == NativeIncidentStatus.DECLINED.wire })
            assertEquals(1, statuses.count { it == NativeIncidentStatus.TRIAGE.wire }, statuses.toString())
        }
        executionService.recoverDue { true }
        transaction {
            assertTrue(
                OnCallIncidents.selectAll().all {
                    it[OnCallIncidents.status] == NativeIncidentStatus.DECLINED.wire
                },
            )
        }
    }

    @Test
    fun `recovery waits for every active episode and leaves active incidents untouched`() = runBlocking {
        createRoute(
            pagingTargets = 1,
            incident = AlertRouteIncidentInput(
                create = true,
                mode = AlertRouteIncidentMode.ACTIVE,
                severity = "SEV-1",
            ),
            recovery = AlertRouteRecoveryInput(
                cancelEscalations = true,
                declineUnacceptedTriage = true,
            ),
            pagingMode = AlertRoutePagingMode.EVERY_EPISODE,
        )
        val first = AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "HOST_ALERT", "members-1")
        val second = AlertRouteTestDatabase.seedAlertEpisode(organization.organizationId, "HOST_ALERT", "members-2")
        executionService.execute(firingContext(first))
        executionService.execute(firingContext(second))

        executionService.execute(firingContext(first, AlertStatus.RESOLVED))
        assertTrue(gateway.resolved.isEmpty())
        executionService.execute(firingContext(second, AlertStatus.RESOLVED))

        assertEquals(2, gateway.resolved.size)
        transaction {
            assertEquals(NativeIncidentStatus.ACTIVE.wire, OnCallIncidents.selectAll().single()[OnCallIncidents.status])
        }
    }

    private fun createRoute(
        pagingTargets: Int,
        incident: AlertRouteIncidentInput,
        recovery: AlertRouteRecoveryInput = AlertRouteRecoveryInput(),
        pagingMode: AlertRoutePagingMode? = null,
        grouping: AlertRouteGroupingInput = AlertRouteGroupingInput(
            keys = listOf("alert.source"),
            windowSeconds = 60,
        ),
    ) {
        val targets = (1..pagingTargets).map { index ->
            val policy = AlertRouteTestDatabase.seedEscalationPolicy(organization.organizationId, "Policy $index")
            AlertRouteTargetInput(AlertRouteTargetKind.ESCALATION_POLICY, escalationPolicyId = policy.resourceId)
        }
        routeService.execute(
            CreateAlertRouteCommand(
                commandKey = "create-route-${Uuid.random()}",
                actor = AlertRouteActor(organization.organizationId, organization.adminUserId, "TEST"),
                specification = AlertRouteSpecification(
                    key = "route-${Uuid.random()}",
                    name = "Execution route",
                    paging = AlertRoutePagingInput(
                        pagingMode
                            ?: if (targets.isEmpty()) {
                                AlertRoutePagingMode.NONE
                            } else {
                                AlertRoutePagingMode.FIRST_EPISODE_PER_GROUP
                            },
                        targets,
                    ),
                    grouping = grouping,
                    incident = incident,
                    recovery = recovery,
                ),
            ),
        )
    }

    private fun firingContext(episodeId: Uuid, status: AlertStatus = AlertStatus.FIRING): AlertFanoutContext {
        val episode = transaction {
            val row = AlertEpisodes.selectAll().where { AlertEpisodes.resourceId eq episodeId }.single()
            AlertEpisodeContext(
                id = row[AlertEpisodes.id].value,
                resourceId = episodeId,
                organizationId = organization.organizationId,
                source = row[AlertEpisodes.sourceName],
                deduplicationKey = row[AlertEpisodes.deduplicationKey],
                title = row[AlertEpisodes.title],
                description = row[AlertEpisodes.description],
                priority = row[AlertEpisodes.priority],
                episodeSeq = row[AlertEpisodes.episodeSeq],
                episodeKey = row[AlertEpisodes.episodeKey],
                status = row[AlertEpisodes.status],
                openedAt = row[AlertEpisodes.openedAt],
                lastSeenAt = row[AlertEpisodes.lastSeenAt],
                resolvedAt = row[AlertEpisodes.resolvedAt],
                lastNotificationAt = row[AlertEpisodes.lastNotificationAt],
                notificationCount = row[AlertEpisodes.notificationCount],
                suppressedAt = row[AlertEpisodes.suppressedAt],
                suppressedByUserId = row[AlertEpisodes.suppressedByUserId],
                suppressReason = row[AlertEpisodes.suppressReason],
            )
        }
        val event = AlertLifecycleEvent(
            title = "Checkout latency",
            description = "Latency above threshold",
            priority = AlertPriority.P1,
            status = status,
            source = AlertSource.valueOf(episode.source),
            deduplicationKey = episode.deduplicationKey,
            organizationId = organization.organizationId,
            moneatUrl = "https://moneat.example/alerts/$episodeId",
        )
        return AlertFanoutContext(event, null, episode, deliverySilenced = false)
    }

    private class RecordingGateway : AlertRouteOnCallGateway {
        val attempted = mutableListOf<String>()
        val triggered = mutableListOf<String>()
        val resolved = mutableListOf<String>()
        var failAfterSuccesses: Int? = null
        var returnNullAfterInsert = false
        var cancelNext = false
        var resolveResult = true
        var throwResolveOnce = false
        private var failed = false

        override suspend fun trigger(request: AlertRouteEscalationRequest): String? {
            attempted += request.deduplicationKey
            if (cancelNext) {
                cancelNext = false
                throw kotlinx.coroutines.CancellationException("Simulated process interruption")
            }
            if (!failed && failAfterSuccesses == triggered.size) {
                failed = true
                error("Simulated paging failure")
            }
            triggered += request.deduplicationKey
            return transaction {
                val resourceId = Uuid.random()
                val timestamp = kotlin.time.Clock.System.now()
                OnCallAlerts.insert {
                    it[OnCallAlerts.resourceId] = resourceId
                    it[OnCallAlerts.organizationId] = request.organizationId
                    it[OnCallAlerts.declaredIncidentId] = null
                    it[OnCallAlerts.escalationPolicyId] = request.escalationPolicyId
                    it[OnCallAlerts.title] = request.title
                    it[OnCallAlerts.description] = request.description
                    it[OnCallAlerts.priority] = request.priority
                    it[OnCallAlerts.status] = "TRIGGERED"
                    it[OnCallAlerts.alertSource] = request.alertSource
                    it[OnCallAlerts.deduplicationKey] = request.deduplicationKey
                    it[OnCallAlerts.currentStep] = 0
                    it[OnCallAlerts.repeatIteration] = 0
                    it[OnCallAlerts.triggeredAt] = timestamp
                    it[OnCallAlerts.acknowledgedAt] = null
                    it[OnCallAlerts.acknowledgedBy] = null
                    it[OnCallAlerts.resolvedAt] = null
                    it[OnCallAlerts.resolvedBy] = null
                    it[OnCallAlerts.metadata] = JsonObject(emptyMap())
                    it[OnCallAlerts.createdAt] = timestamp
                    it[OnCallAlerts.updatedAt] = timestamp
                }
                resourceId.toString().takeUnless { returnNullAfterInsert }
            }
        }

        override suspend fun resolve(organizationId: Int, alertSource: String, deduplicationKey: String): Boolean {
            if (throwResolveOnce) {
                throwResolveOnce = false
                error("Simulated recovery failure")
            }
            resolved += deduplicationKey
            return resolveResult
        }
    }
}
