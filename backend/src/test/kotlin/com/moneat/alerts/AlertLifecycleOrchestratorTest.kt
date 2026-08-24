// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package com.moneat.alerts

import com.moneat.alerts.models.AlertEpisodes
import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.alerts.models.AlertPriority
import com.moneat.alerts.models.AlertSource
import com.moneat.alerts.models.AlertStatus
import com.moneat.alerts.services.AlertEpisodeService
import com.moneat.alerts.services.AlertFanoutArm
import com.moneat.alerts.services.AlertFanoutContext
import com.moneat.alerts.services.AlertFanoutPlan
import com.moneat.alerts.services.AlertFanoutState
import com.moneat.alerts.services.AlertIncidentFanout
import com.moneat.alerts.services.AlertLifecycleOrchestrator
import com.moneat.alerts.services.AlertRouteFanout
import com.moneat.alerts.services.AlertRouteActionOutcome
import com.moneat.alerts.services.AlertRouteActionState
import com.moneat.alerts.services.AlertRouteExecutionOutcome
import com.moneat.alerts.services.AlertRouteExecutionState
import com.moneat.alerts.services.AlertRouteOutcomeFanout
import com.moneat.alerts.services.AlertSilenceService
import com.moneat.alerts.services.AlertWorkflowFanout
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AlertLifecycleOrchestratorTest {
    companion object {
        private var database: Database? = null
    }

    private lateinit var silenceService: AlertSilenceService
    private lateinit var episodeService: AlertEpisodeService
    private var organizationId: Int = 0

    @BeforeTest
    fun setUp() {
        if (database == null) {
            database = Database.connect(
                url = "jdbc:h2:mem:moneat_alert_orchestration;MODE=MYSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }
        TransactionManager.defaultDatabase = database
        TestDatabaseHelper.dropAndPatchJsonb(AlertEpisodes, Users, Organizations)
        transaction { SchemaUtils.create(Users, Organizations, AlertEpisodes) }
        organizationId = transaction {
            Organizations.insert {
                it[name] = "Alert Orchestration"
                it[slug] = "alert-orchestration"
            }[Organizations.id]
        }
        silenceService = mockk()
        every { silenceService.isOrganizationSilenced(any(), any()) } returns false
        episodeService = AlertEpisodeService(silenceService)
    }

    @Test
    fun `one episode context is shared by every selected fanout arm`() = runBlocking {
        val captured = mutableListOf<AlertFanoutContext>()
        val workflow = AlertWorkflowFanout { captured += it }
        val incidents = capturingIncidentFanout(captured)
        val route = AlertRouteFanout { captured += it }
        val orchestrator = orchestrator(workflow, incidents, route)

        val result = orchestrator.process(event(), AlertFanoutPlan.FULL)

        assertEquals(4, captured.size)
        captured.forEach { assertSame(result.context, it) }
        assertEquals(1, transaction { AlertEpisodes.selectAll().count() })
        assertEquals(1, result.context.episode?.notificationCount)
        assertTrue(result.outcomes.all { it.state == AlertFanoutState.SUCCEEDED })
    }

    @Test
    fun `one arm failure is observable and does not suppress other arms`() = runBlocking {
        val captured = mutableListOf<AlertFanoutContext>()
        val workflow = AlertWorkflowFanout { error("workflow unavailable") }
        val orchestrator = orchestrator(
            workflow,
            capturingIncidentFanout(captured),
            AlertRouteFanout { captured += it },
        )

        val result = orchestrator.process(event(), AlertFanoutPlan.FULL)

        assertEquals(AlertFanoutState.FAILED, result.outcome(AlertFanoutArm.WORKFLOW).state)
        assertEquals(3, captured.size)
        assertEquals(AlertFanoutState.SUCCEEDED, result.outcome(AlertFanoutArm.NATIVE_ON_CALL).state)
        assertEquals(AlertFanoutState.SUCCEEDED, result.outcome(AlertFanoutArm.INCIDENT_PROVIDERS).state)
    }

    @Test
    fun `retry reuses the episode notification and delivery identity`() = runBlocking {
        var attempts = 0
        val workflow = AlertWorkflowFanout {
            attempts += 1
            if (attempts == 1) error("workflow unavailable")
        }
        val orchestrator = orchestrator(workflow, capturingIncidentFanout(mutableListOf()), AlertRouteFanout {})

        val first = orchestrator.process(event(), AlertFanoutPlan.WORKFLOW_ONLY)
        val retried = orchestrator.retry(first.context, AlertFanoutPlan.WORKFLOW_ONLY.copy(route = false))

        assertEquals(AlertFanoutState.FAILED, first.outcome(AlertFanoutArm.WORKFLOW).state)
        assertEquals(AlertFanoutState.SUCCEEDED, retried.outcome(AlertFanoutArm.WORKFLOW).state)
        assertEquals(first.context.episode?.id, retried.context.episode?.id)
        assertEquals(first.context.deliveryKey, retried.context.deliveryKey)
        assertEquals(1, transaction { AlertEpisodes.selectAll().single()[AlertEpisodes.notificationCount] })
    }

    @Test
    fun `repeat firing updates route context but suppresses reminder delivery`() = runBlocking {
        val workflowContexts = mutableListOf<AlertFanoutContext>()
        val incidentContexts = mutableListOf<AlertFanoutContext>()
        val routeContexts = mutableListOf<AlertFanoutContext>()
        val orchestrator = orchestrator(
            AlertWorkflowFanout { workflowContexts += it },
            capturingIncidentFanout(incidentContexts),
            AlertRouteFanout { routeContexts += it },
        )

        orchestrator.process(event(), AlertFanoutPlan.FULL)
        val repeated = orchestrator.process(event(), AlertFanoutPlan.FULL)

        assertEquals(2, routeContexts.size)
        assertEquals(routeContexts.first().episode?.id, routeContexts.last().episode?.id)
        assertEquals(1, workflowContexts.size)
        assertEquals(2, incidentContexts.size)
        assertEquals(AlertFanoutState.SKIPPED, repeated.outcome(AlertFanoutArm.WORKFLOW).state)
        assertEquals(AlertFanoutState.SKIPPED, repeated.outcome(AlertFanoutArm.NATIVE_ON_CALL).state)
    }

    @Test
    fun `organization silence records and routes the episode but blocks delivery arms`() = runBlocking {
        every { silenceService.isOrganizationSilenced(organizationId, any()) } returns true
        val delivered = mutableListOf<AlertFanoutContext>()
        val routed = mutableListOf<AlertFanoutContext>()
        val orchestrator = orchestrator(
            AlertWorkflowFanout { delivered += it },
            capturingIncidentFanout(delivered),
            AlertRouteFanout { routed += it },
        )

        val result = orchestrator.process(event(), AlertFanoutPlan.FULL)

        assertEquals(1, routed.size)
        assertTrue(result.context.deliverySilenced)
        assertEquals(0, result.context.episode?.notificationCount)
        assertTrue(delivered.isEmpty())
        assertEquals(AlertFanoutState.SKIPPED, result.outcome(AlertFanoutArm.WORKFLOW).state)
    }

    @Test
    fun `route action outcome is returned without executing the route twice`() = runBlocking {
        var legacyProcessCalls = 0
        var outcomeCalls = 0
        val route = object : AlertRouteFanout, AlertRouteOutcomeFanout {
            override suspend fun process(context: AlertFanoutContext) {
                legacyProcessCalls += 1
            }

            override suspend fun processWithOutcome(context: AlertFanoutContext): AlertRouteExecutionOutcome {
                outcomeCalls += 1
                return AlertRouteExecutionOutcome(
                    state = AlertRouteExecutionState.MATCHED,
                    matchedRouteId = "route-resource",
                    matchedRouteRevision = 3,
                    groupId = "group-resource",
                    grouping = AlertRouteActionOutcome(AlertRouteActionState.SUCCEEDED, "Alert grouped"),
                )
            }
        }
        val result = orchestrator(
            workflow = AlertWorkflowFanout {},
            incidents = capturingIncidentFanout(mutableListOf()),
            route = route,
        ).process(event(), AlertFanoutPlan(route = true, workflow = false))

        assertEquals(0, legacyProcessCalls)
        assertEquals(1, outcomeCalls)
        assertEquals("route-resource", result.outcomes.single { it.arm == AlertFanoutArm.ROUTE }.route?.matchedRouteId)
        assertEquals(AlertFanoutState.SUCCEEDED, result.outcome(AlertFanoutArm.ROUTE).state)
    }

    @Test
    fun `resolved lifecycle shares the closed episode with every arm`() = runBlocking {
        val captured = mutableListOf<AlertFanoutContext>()
        val orchestrator = orchestrator(
            AlertWorkflowFanout { captured += it },
            capturingIncidentFanout(captured),
            AlertRouteFanout { captured += it },
        )
        orchestrator.process(event(), AlertFanoutPlan.FULL)
        captured.clear()

        val result = orchestrator.process(event().copy(status = AlertStatus.RESOLVED), AlertFanoutPlan.FULL)

        assertEquals("RESOLVED", result.context.episode?.status)
        assertEquals(4, captured.size)
        captured.forEach { assertSame(result.context, it) }
    }

    @Test
    fun `silenced resolution closes delivery targets without publishing workflows`() = runBlocking {
        val workflowContexts = mutableListOf<AlertFanoutContext>()
        val incidentContexts = mutableListOf<AlertFanoutContext>()
        val orchestrator = orchestrator(
            AlertWorkflowFanout { workflowContexts += it },
            capturingIncidentFanout(incidentContexts),
            AlertRouteFanout {},
        )
        orchestrator.process(event(), AlertFanoutPlan.FULL)
        workflowContexts.clear()
        incidentContexts.clear()
        every { silenceService.isOrganizationSilenced(organizationId, any()) } returns true

        val result = orchestrator.process(event().copy(status = AlertStatus.RESOLVED), AlertFanoutPlan.FULL)

        assertTrue(workflowContexts.isEmpty())
        assertEquals(2, incidentContexts.size)
        assertEquals(AlertFanoutState.SKIPPED, result.outcome(AlertFanoutArm.WORKFLOW).state)
        assertEquals(AlertFanoutState.SUCCEEDED, result.outcome(AlertFanoutArm.NATIVE_ON_CALL).state)
        assertEquals(AlertFanoutState.SUCCEEDED, result.outcome(AlertFanoutArm.INCIDENT_PROVIDERS).state)
    }

    @Test
    fun `core only operation reports unavailable optional arms without failing`() = runBlocking {
        val orchestrator = AlertLifecycleOrchestrator(
            episodeService = episodeService,
            workflowFanoutProvider = { null },
            incidentFanoutProvider = { null },
            routeFanoutProvider = { null },
        )

        val result = orchestrator.process(event(), AlertFanoutPlan.FULL)

        assertTrue(result.outcomes.all { it.state == AlertFanoutState.UNAVAILABLE })
        assertEquals(1, transaction { AlertEpisodes.selectAll().count() })
    }

    private fun orchestrator(
        workflow: AlertWorkflowFanout,
        incidents: AlertIncidentFanout,
        route: AlertRouteFanout,
    ) = AlertLifecycleOrchestrator(
        episodeService = episodeService,
        workflowFanoutProvider = { workflow },
        incidentFanoutProvider = { incidents },
        routeFanoutProvider = { route },
    )

    private fun capturingIncidentFanout(captured: MutableList<AlertFanoutContext>) =
        object : AlertIncidentFanout {
            override suspend fun fireNative(context: AlertFanoutContext) {
                captured += context
            }

            override suspend fun fireProviders(context: AlertFanoutContext) {
                captured += context
            }

            override suspend fun resolveNative(context: AlertFanoutContext) {
                captured += context
            }

            override suspend fun resolveProviders(context: AlertFanoutContext) {
                captured += context
            }
        }

    private fun event() = AlertLifecycleEvent(
        title = "Checkout latency",
        description = "Latency exceeded the threshold",
        priority = AlertPriority.P1,
        status = AlertStatus.FIRING,
        source = AlertSource.DASHBOARD_ALERT,
        deduplicationKey = "dashboard-alert-1",
        organizationId = organizationId,
        moneatUrl = "https://moneat.test/dashboards/checkout",
    )

    private fun com.moneat.alerts.services.AlertOrchestrationResult.outcome(arm: AlertFanoutArm) =
        outcomes.single { it.arm == arm }
}
