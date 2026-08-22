// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes

import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.alerts.models.AlertPriority
import com.moneat.alerts.models.AlertSource
import com.moneat.alerts.models.AlertStatus
import com.moneat.enterprise.alertroutes.commands.AlertRouteActor
import com.moneat.enterprise.alertroutes.commands.AlertRouteConditionGroupInput
import com.moneat.enterprise.alertroutes.commands.AlertRouteConditionInput
import com.moneat.enterprise.alertroutes.commands.AlertRoutePolicy
import com.moneat.enterprise.alertroutes.commands.AlertRouteSpecification
import com.moneat.enterprise.alertroutes.commands.CreateAlertRouteCommand
import com.moneat.enterprise.alertroutes.context.AlertRouteSampleInput
import com.moneat.enterprise.alertroutes.models.AlertRouteConditionOperator
import com.moneat.enterprise.alertroutes.models.AlertRouteContextField
import com.moneat.enterprise.alertroutes.services.AlertRouteCommandService
import com.moneat.enterprise.alertroutes.services.AlertRouteEvaluationService
import com.moneat.enterprise.incidents.IncidentDomainGlossary
import com.moneat.enterprise.incidents.IncidentDomainObject
import com.moneat.incident.models.IncidentEventLog
import com.moneat.incident.models.IncidentProviderConfigs
import com.moneat.incident.models.IncidentRoutingRules
import com.moneat.incident.models.ProviderConfig
import com.moneat.incident.services.IncidentProvider
import com.moneat.incident.services.IncidentProviderRegistry
import com.moneat.incident.services.IncidentService
import com.moneat.workflows.services.WorkflowService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Enterprise Alert Routes must not change the open-source incident-provider passthrough.
 * These tests keep the two routing concepts separate and prove external forwarding still runs.
 */
class AlertRouteOssCompatibilityTest {
    private lateinit var organization: SeededAlertRouteOrganization
    private lateinit var provider: RecordingIncidentProvider

    @BeforeEach
    fun setUp() {
        AlertRouteTestDatabase.reset()
        organization = AlertRouteTestDatabase.seedOrganization("oss-compat")
        provider = RecordingIncidentProvider()
        IncidentProviderRegistry.register(provider)
    }

    @AfterEach
    fun tearDown() {
        AlertRouteTestDatabase.clearReference()
    }

    @Test
    fun `the glossary separates enterprise alert routes from provider routing rules`() {
        val alertRoute = IncidentDomainGlossary.requireCanonicalApiName("enterprise_alert_route")
        val providerRule = IncidentDomainGlossary.requireCanonicalApiName("provider_routing_rule")

        assertEquals(IncidentDomainObject.ENTERPRISE_ALERT_ROUTE, alertRoute)
        assertEquals(IncidentDomainObject.PROVIDER_ROUTING_RULE, providerRule)
        assertNotEquals(alertRoute.owner, providerRule.owner)
        assertNotEquals(alertRoute.lifecycle, providerRule.lifecycle)
        assertEquals(IncidentDomainGlossary.ROUTING_OBJECTS, setOf(alertRoute, providerRule))
    }

    @Test
    fun `external provider forwarding is unchanged when an alert route also matches the alert`() {
        val providerConfigId = seedProviderPassthrough()
        createMatchingAlertRoute()
        val event = alertEvent()
        val workflowService = mockk<WorkflowService>()
        coEvery { workflowService.publishAlertTriggered(event) } returns true

        runBlocking { IncidentService(workflowService = workflowService).fireAlert(event) }

        assertEquals(listOf(event.deduplicationKey), provider.sent.map { it.deduplicationKey })
        val logged =
            transaction {
                IncidentEventLog
                    .selectAll()
                    .where { IncidentEventLog.providerConfigId eq providerConfigId }
                    .map { it[IncidentEventLog.success] to it[IncidentEventLog.providerIncidentId] }
            }
        assertEquals(listOf(true to "provider-incident-1"), logged)
    }

    @Test
    fun `alert route storage never touches provider routing rules`() {
        val providerConfigId = seedProviderPassthrough()
        val workflowService = mockk<WorkflowService>()
        createMatchingAlertRoute()
        AlertRouteEvaluationService(policy = AlertRoutePolicy.allowForTests())
            .preview(
                organization.organizationId,
                AlertRouteSampleInput(source = AlertSource.DASHBOARD_ALERT.name),
            )

        val rules =
            transaction {
                IncidentRoutingRules
                    .selectAll()
                    .where { IncidentRoutingRules.providerConfigId eq providerConfigId }
                    .map { it[IncidentRoutingRules.alertSource] to it[IncidentRoutingRules.alertPriority] }
            }
        assertEquals(listOf(AlertSource.DASHBOARD_ALERT.name to "P2"), rules)
        assertTrue(
            AlertRouteCommandService(policy = AlertRoutePolicy.allowForTests())
                .list(organization.organizationId)
                .isNotEmpty(),
        )
        assertEquals(
            "P2",
            IncidentService(workflowService = workflowService)
                .resolveAlertPriority(providerConfigId, AlertSource.DASHBOARD_ALERT, null)
                ?.wire,
        )
    }

    private fun alertEvent(): AlertLifecycleEvent =
        AlertLifecycleEvent(
            title = "Checkout latency high",
            description = "p95 latency above 800ms",
            priority = AlertPriority.P1,
            status = AlertStatus.FIRING,
            source = AlertSource.DASHBOARD_ALERT,
            deduplicationKey = "moneat-dashboard-alert-42",
            organizationId = organization.organizationId,
            moneatUrl = "https://moneat.example/alerts/42",
        )

    private fun seedProviderPassthrough(): Int =
        transaction {
            val now = Clock.System.now()
            val configId =
                IncidentProviderConfigs.insert {
                    it[organizationId] = organization.organizationId
                    it[providerType] = RecordingIncidentProvider.PROVIDER_TYPE
                    it[name] = "External provider"
                    it[apiKey] = "secret"
                    it[configJson] = "{}"
                    it[enabled] = true
                    it[createdAt] = now
                    it[updatedAt] = now
                }[IncidentProviderConfigs.id].value
            IncidentRoutingRules.insert {
                it[providerConfigId] = configId
                it[alertSource] = AlertSource.DASHBOARD_ALERT.name
                it[alertPriority] = "P2"
                it[createdAt] = now
                it[updatedAt] = now
            }
            configId
        }

    private fun createMatchingAlertRoute() {
        AlertRouteCommandService(policy = AlertRoutePolicy.allowForTests()).execute(
            CreateAlertRouteCommand(
                commandKey = "oss-compat-route",
                actor = AlertRouteActor(organization.organizationId, organization.adminUserId, "TEST"),
                specification =
                    AlertRouteSpecification(
                        key = "dashboard-alerts",
                        name = "Dashboard alerts",
                        conditionGroups =
                            listOf(
                                AlertRouteConditionGroupInput(
                                    listOf(
                                        AlertRouteConditionInput(
                                            field = AlertRouteContextField.SOURCE,
                                            operator = AlertRouteConditionOperator.EQUALS,
                                            values = listOf(AlertSource.DASHBOARD_ALERT.name),
                                        ),
                                    ),
                                ),
                            ),
                    ),
            ),
        )
    }
}

private class RecordingIncidentProvider : IncidentProvider {
    val sent = mutableListOf<AlertLifecycleEvent>()

    override val providerType: String = PROVIDER_TYPE

    override suspend fun sendAlert(event: AlertLifecycleEvent, config: ProviderConfig): Result<String> {
        sent.add(event)
        return Result.success("provider-incident-1")
    }

    override suspend fun resolveAlert(deduplicationKey: String, config: ProviderConfig): Result<String> =
        Result.success(deduplicationKey)

    override suspend fun testConnection(config: ProviderConfig): Result<Boolean> = Result.success(true)

    companion object {
        const val PROVIDER_TYPE = "test_recording_provider"
    }
}
