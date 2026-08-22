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

package com.moneat.enterprise

import com.moneat.alerts.models.AlertSource
import com.moneat.config.EnvConfig
import com.moneat.incident.services.IncidentService
import com.moneat.workflows.services.WorkflowService
import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val ORGANIZATION_ID = 42
private const val UPTIME_DEDUPLICATION_KEY = "uptime-monitor:payments-api"
private const val DASHBOARD_DEDUPLICATION_KEY = "dashboard-alert:error-rate"

class IncidentServiceOnCallBridgeTest {
    private lateinit var bridge: RecordingOnCallBridgeModule
    private lateinit var service: IncidentService

    @BeforeTest
    fun setUp() {
        FeatureRegistry.resetForTest()
        bridge = RecordingOnCallBridgeModule()
        FeatureRegistry.registerForTest(bridge)
        mockkObject(EnvConfig)
        every { EnvConfig.get("ONCALL_ENABLED", "false") } returns "true"
        service = IncidentService(workflowService = mockk<WorkflowService>(relaxed = true))
    }

    @AfterTest
    fun tearDown() {
        unmockkObject(EnvConfig)
        FeatureRegistry.resetForTest()
    }

    @Test
    fun `resolveAlert resolves native on-call alert by source and deduplication key`() = runBlocking {
        service.resolveAlert(
            organizationId = ORGANIZATION_ID,
            source = AlertSource.UPTIME_MONITOR,
            deduplicationKey = UPTIME_DEDUPLICATION_KEY,
            publishWorkflow = false,
        )

        assertEquals(
            listOf(
                ResolvedEscalation(
                    organizationId = ORGANIZATION_ID,
                    alertSource = AlertSource.UPTIME_MONITOR.name,
                    deduplicationKey = UPTIME_DEDUPLICATION_KEY,
                ),
            ),
            bridge.resolvedEscalations,
        )
    }

    @Test
    fun `autoResolveAlert resolves native on-call alert through common resolve path`() = runBlocking {
        service.autoResolveAlert(
            organizationId = ORGANIZATION_ID,
            source = AlertSource.DASHBOARD_ALERT,
            deduplicationKey = DASHBOARD_DEDUPLICATION_KEY,
            publishWorkflow = false,
        )

        assertEquals(
            listOf(
                ResolvedEscalation(
                    organizationId = ORGANIZATION_ID,
                    alertSource = AlertSource.DASHBOARD_ALERT.name,
                    deduplicationKey = DASHBOARD_DEDUPLICATION_KEY,
                ),
            ),
            bridge.resolvedEscalations,
        )
    }
}

private data class ResolvedEscalation(
    val organizationId: Int,
    val alertSource: String,
    val deduplicationKey: String,
)

private class RecordingOnCallBridgeModule :
    EnterpriseModule,
    OnCallBridge {
    val resolvedEscalations = mutableListOf<ResolvedEscalation>()

    override val name: String = "Recording On-Call"

    override fun registerRoutes(route: Route) = Unit

    override fun startBackgroundJobs(application: Application) = Unit

    override fun stopBackgroundJobs() = Unit

    override fun resolvePriority(
        organizationId: Int,
        priority: String,
    ): PriorityInfo? = null

    override fun shouldEscalate(
        organizationId: Int,
        priority: String,
    ): Boolean = false

    override fun resolveEscalationPolicyId(
        organizationId: Int,
        escalationPolicyResourceId: String,
    ): Int? = null

    override fun resolveAlertId(
        organizationId: Int,
        alertResourceId: String,
    ): Int? = null

    override fun getCurrentOnCall(
        organizationId: Int,
        scheduleId: Int,
    ): OnCallUserInfo? = null

    override suspend fun triggerEscalation(
        organizationId: Int,
        escalationPolicyId: Int,
        title: String,
        description: String?,
        priority: String,
        alertSource: String,
        deduplicationKey: String?,
        metadata: String?,
    ): String? = null

    override suspend fun resolveEscalation(
        organizationId: Int,
        alertSource: String,
        deduplicationKey: String,
    ): Boolean {
        resolvedEscalations +=
            ResolvedEscalation(
                organizationId = organizationId,
                alertSource = alertSource,
                deduplicationKey = deduplicationKey,
            )
        return true
    }

    override suspend fun declareIncident(declaration: OnCallIncidentDeclaration): String? = null

    override fun getIncident(
        incidentId: Int,
        userId: Int,
    ): IncidentInfo? = null

    override fun acknowledgeIncident(
        incidentId: Int,
        userId: Int,
    ): Boolean = false

    override fun getAlert(
        alertId: Int,
        userId: Int,
    ): IncidentInfo? = null

    override fun acknowledgeAlert(
        alertId: Int,
        userId: Int,
    ): Boolean = false
}
