// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.evaluation

import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.alerts.models.AlertPriority
import com.moneat.alerts.models.AlertSource
import com.moneat.alerts.models.AlertStatus
import com.moneat.enterprise.alertroutes.context.AlertRouteContext
import com.moneat.enterprise.alertroutes.context.AlertRouteContextFactory
import com.moneat.enterprise.alertroutes.context.AlertRouteEpisodeIdentity
import com.moneat.enterprise.alertroutes.context.AlertRouteOwnership
import com.moneat.enterprise.alertroutes.models.AlertRouteConditionDefinition
import com.moneat.enterprise.alertroutes.models.AlertRouteConditionGroupDefinition
import com.moneat.enterprise.alertroutes.models.AlertRouteConditionOperator
import com.moneat.enterprise.alertroutes.models.AlertRouteContextField
import com.moneat.enterprise.alertroutes.models.AlertRouteDefinition
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingBehavior
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingDefinition
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingWindowKind
import com.moneat.enterprise.alertroutes.models.AlertRouteIncidentDefinition
import com.moneat.enterprise.alertroutes.models.AlertRouteIncidentMode
import com.moneat.enterprise.alertroutes.models.AlertRouteOwnershipSource
import com.moneat.enterprise.alertroutes.models.AlertRoutePagingDefinition
import com.moneat.enterprise.alertroutes.models.AlertRoutePagingMode
import com.moneat.enterprise.alertroutes.models.AlertRouteRecoveryDefinition
import com.moneat.enterprise.alertroutes.models.AlertRouteTargetDefinition
import com.moneat.enterprise.alertroutes.models.AlertRouteTargetKind
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class AlertRouteEvaluatorTest {
    private val organizationId = 7
    private val now = Clock.System.now()

    private val baseRoute =
        AlertRouteDefinition(
            id = 1,
            resourceId = Uuid.random(),
            organizationId = organizationId,
            key = "base",
            name = "Base",
            description = null,
            enabled = true,
            position = 0,
            revision = 1,
            conditionGroups = emptyList(),
            paging = AlertRoutePagingDefinition(AlertRoutePagingMode.NONE, emptyList()),
            grouping =
                AlertRouteGroupingDefinition(
                    behavior = AlertRouteGroupingBehavior.SUGGESTED,
                    keys = emptyList(),
                    windowKind = AlertRouteGroupingWindowKind.ROLLING,
                    windowSeconds = 900,
                ),
            incident =
                AlertRouteIncidentDefinition(
                    create = false,
                    mode = AlertRouteIncidentMode.TRIAGE,
                    titleTemplate = null,
                    summaryTemplate = null,
                    severity = null,
                    incidentType = null,
                ),
            recovery =
                AlertRouteRecoveryDefinition(
                    cancelEscalations = false,
                    declineUnacceptedTriage = false,
                    graceSeconds = 0,
                ),
            createdAt = now,
            updatedAt = now,
        )

    private fun context(
        source: String = AlertSource.DASHBOARD_ALERT.name,
        priority: String = "P1",
        metadata: Map<String, String> = mapOf("service" to "checkout"),
        ownership: AlertRouteOwnership = AlertRouteOwnership(serviceName = "checkout"),
    ): AlertRouteContext =
        AlertRouteContext(
            organizationId = organizationId,
            source = source,
            status = AlertStatus.FIRING.name,
            priority = priority,
            title = "Checkout latency high",
            description = "p95 latency above 800ms",
            deduplicationKey = "moneat-dashboard-alert-42",
            url = "https://moneat.example/alerts/42",
            episode = AlertRouteEpisodeIdentity(key = "moneat-dashboard-alert-42#3", sequence = 3, status = "OPEN"),
            metadata = metadata,
            ownership = ownership,
        )

    private fun conditionGroup(vararg conditions: AlertRouteConditionDefinition) =
        AlertRouteConditionGroupDefinition(conditions.toList())

    @Test
    fun `first matching enabled route wins and later routes report why they were skipped`() {
        val early =
            baseRoute.copy(
                id = 1,
                resourceId = Uuid.random(),
                key = "hosts",
                position = 0,
                conditionGroups =
                    listOf(
                        conditionGroup(
                            AlertRouteConditionDefinition(
                                field = AlertRouteContextField.SOURCE,
                                operator = AlertRouteConditionOperator.EQUALS,
                                values = listOf(AlertSource.HOST_ALERT.name),
                            ),
                        ),
                    ),
            )
        val middle =
            baseRoute.copy(
                id = 2,
                resourceId = Uuid.random(),
                key = "dashboards",
                position = 1,
                conditionGroups =
                    listOf(
                        conditionGroup(
                            AlertRouteConditionDefinition(
                                field = AlertRouteContextField.SOURCE,
                                operator = AlertRouteConditionOperator.EQUALS,
                                values = listOf(AlertSource.DASHBOARD_ALERT.name),
                            ),
                        ),
                    ),
            )
        val catchAll = baseRoute.copy(id = 3, resourceId = Uuid.random(), key = "catch-all", position = 2)

        val result = AlertRouteEvaluator().evaluate(listOf(catchAll, middle, early), context())

        assertEquals(middle.resourceId, result.decision?.routeId)
        assertEquals(
            listOf(
                AlertRouteEvaluationReason.NO_GROUP_MATCHED,
                AlertRouteEvaluationReason.SELECTED,
                AlertRouteEvaluationReason.EARLIER_ROUTE_SELECTED,
            ),
            result.evaluations.map { it.reason },
        )
    }

    @Test
    fun `condition groups are ORed while conditions inside one group are ANDed`() {
        val route =
            baseRoute.copy(
                conditionGroups =
                    listOf(
                        conditionGroup(
                            AlertRouteConditionDefinition(
                                field = AlertRouteContextField.SOURCE,
                                operator = AlertRouteConditionOperator.EQUALS,
                                values = listOf(AlertSource.HOST_ALERT.name),
                            ),
                            AlertRouteConditionDefinition(
                                field = AlertRouteContextField.PRIORITY,
                                operator = AlertRouteConditionOperator.AT_LEAST_AS_SEVERE_AS,
                                values = listOf("P2"),
                            ),
                        ),
                        conditionGroup(
                            AlertRouteConditionDefinition(
                                field = AlertRouteContextField.METADATA,
                                operator = AlertRouteConditionOperator.EQUALS,
                                values = listOf("checkout"),
                                metadataKey = "service",
                            ),
                        ),
                    ),
            )

        val result = AlertRouteEvaluator().evaluate(listOf(route), context())
        val evaluation = result.evaluations.single()

        assertTrue(evaluation.matched)
        assertFalse(evaluation.groups[0].matched)
        assertTrue(evaluation.groups[1].matched)
        assertFalse(evaluation.groups[0].conditions[0].matched)
        assertTrue(evaluation.groups[0].conditions[1].matched)
    }

    @Test
    fun `disabled routes are explained but never selected`() {
        val disabled =
            baseRoute.copy(resourceId = Uuid.random(), key = "disabled", enabled = false, position = 0)

        val result = AlertRouteEvaluator().evaluate(listOf(disabled), context())

        assertNull(result.decision)
        assertEquals(AlertRouteEvaluationReason.ROUTE_DISABLED, result.evaluations.single().reason)
    }

    @Test
    fun `a matching disabled route does not prevent a later enabled route from winning`() {
        val disabled =
            baseRoute.copy(resourceId = Uuid.random(), key = "disabled", enabled = false, position = 0)
        val enabled =
            baseRoute.copy(resourceId = Uuid.random(), key = "enabled", enabled = true, position = 1)

        val result = AlertRouteEvaluator().evaluate(listOf(disabled, enabled), context())

        assertEquals(enabled.resourceId, result.decision?.routeId)
        assertEquals(
            listOf(AlertRouteEvaluationReason.ROUTE_DISABLED, AlertRouteEvaluationReason.SELECTED),
            result.evaluations.map { it.reason },
        )
    }

    @Test
    fun `suggested grouping is the default and automatic grouping needs an explicit opt in`() {
        val suggested = baseRoute.copy(resourceId = Uuid.random(), key = "suggested", position = 0)
        val automatic =
            baseRoute.copy(
                resourceId = Uuid.random(),
                key = "automatic",
                position = 0,
                grouping = baseRoute.grouping.copy(behavior = AlertRouteGroupingBehavior.AUTOMATIC),
            )

        val suggestedResult = AlertRouteEvaluator().evaluate(listOf(suggested), context())
        val automaticResult = AlertRouteEvaluator().evaluate(listOf(automatic), context())

        val suggestedGrouping = suggestedResult.decision!!.grouping
        assertFalse(suggestedGrouping.automatic)
        assertEquals(AlertRouteGroupingBehavior.SUGGESTED, suggestedGrouping.behavior)
        assertTrue(automaticResult.decision!!.grouping.automatic)
    }

    @Test
    fun `grouping keys build a stable group key and fixed windows differ from rolling windows`() {
        val route =
            baseRoute.copy(
                key = "checkout",
                grouping =
                    baseRoute.grouping.copy(
                        keys = listOf("ownership.service", "alert.source", "metadata.environment"),
                        windowKind = AlertRouteGroupingWindowKind.FIXED,
                        windowSeconds = 600,
                    ),
            )

        val result =
            AlertRouteEvaluator()
                .evaluate(listOf(route), context(metadata = mapOf("service" to "checkout")))
        val grouping = result.decision!!.grouping

        assertEquals("checkout|checkout|DASHBOARD_ALERT|", grouping.groupKey)
        assertEquals(listOf("metadata.environment"), grouping.unresolvedKeys)
        assertEquals(now + 10.minutes, grouping.windowClosesAt(groupStartedAt = now, lastAlertAt = now + 5.minutes))

        val rolling = grouping.copy(windowKind = AlertRouteGroupingWindowKind.ROLLING)
        assertEquals(now + 15.minutes, rolling.windowClosesAt(groupStartedAt = now, lastAlertAt = now + 5.minutes))
    }

    @Test
    fun `one route resolves multiple static and ownership derived escalation targets`() {
        val staticPolicyResource = Uuid.random()
        val teamResource = Uuid.random()
        val teamPolicyResource = Uuid.random()
        val ownershipPolicyResource = Uuid.random()
        val route =
            baseRoute.copy(
                paging =
                    AlertRoutePagingDefinition(
                        mode = AlertRoutePagingMode.EVERY_EPISODE,
                        targets =
                            listOf(
                                AlertRouteTargetDefinition(
                                    kind = AlertRouteTargetKind.ESCALATION_POLICY,
                                    escalationPolicyId = 11,
                                    escalationPolicyResourceId = staticPolicyResource,
                                ),
                                AlertRouteTargetDefinition(
                                    kind = AlertRouteTargetKind.TEAM,
                                    teamId = 5,
                                    teamResourceId = teamResource,
                                ),
                                AlertRouteTargetDefinition(
                                    kind = AlertRouteTargetKind.OWNERSHIP_DERIVED,
                                    ownershipSource = AlertRouteOwnershipSource.CATALOG,
                                ),
                                AlertRouteTargetDefinition(
                                    kind = AlertRouteTargetKind.OWNERSHIP_DERIVED,
                                    ownershipSource = AlertRouteOwnershipSource.TEAM,
                                ),
                            ),
                    ),
            )
        val evaluator =
            AlertRouteEvaluator { _, teamId ->
                AlertRouteEscalationPolicyRef(teamId + 100, teamPolicyResource)
            }

        val result =
            evaluator.evaluate(
                listOf(route),
                context(
                    ownership =
                        AlertRouteOwnership(
                            serviceName = "checkout",
                            catalogResourceId = "service:7:checkout",
                            escalationPolicyId = 42,
                            escalationPolicyResourceId = ownershipPolicyResource,
                        ),
                ),
            )
        val paging = result.decision!!.paging

        assertEquals(listOf(11, 105, 42), paging.escalationPolicies.map { it.id })
        assertEquals(
            listOf(staticPolicyResource, teamPolicyResource, ownershipPolicyResource),
            paging.escalationPolicies.mapNotNull { it.resourceId },
        )
        assertEquals(listOf("OWNERSHIP_DERIVED:TEAM"), paging.unresolvedTargets)
    }

    @Test
    fun `incident templates render from the normalized context and report unresolved references`() {
        val route =
            baseRoute.copy(
                incident =
                    baseRoute.incident.copy(
                        create = true,
                        mode = AlertRouteIncidentMode.ACTIVE,
                        titleTemplate = "{{ ownership.service }}: {{ alert.title }}",
                        summaryTemplate = "Episode {{ episode.key }} owner {{ ownership.team }}",
                        severity = "SEV-2",
                    ),
            )

        val incident = AlertRouteEvaluator().evaluate(listOf(route), context()).decision!!.incident

        assertEquals("checkout: Checkout latency high", incident.title)
        assertEquals("Episode moneat-dashboard-alert-42#3 owner", incident.summary)
        assertEquals(listOf("ownership.team"), incident.unresolvedReferences)
        assertTrue(incident.create)
        assertEquals(AlertRouteIncidentMode.ACTIVE, incident.mode)
    }

    @Test
    fun `unapproved metadata never reaches conditions or templates`() {
        val event =
            AlertLifecycleEvent(
                title = "Checkout latency high",
                description = "p95 latency above 800ms",
                priority = AlertPriority.P1,
                status = AlertStatus.FIRING,
                source = AlertSource.DASHBOARD_ALERT,
                deduplicationKey = "moneat-dashboard-alert-42",
                organizationId = organizationId,
                metadata =
                    mapOf(
                        "service" to JsonPrimitive("checkout"),
                        "internal_secret" to JsonPrimitive("do-not-route"),
                    ),
                moneatUrl = "https://moneat.example/alerts/42",
            )

        val routeContext = AlertRouteContextFactory.fromLifecycleEvent(event)

        assertEquals(mapOf("service" to "checkout"), routeContext.metadata)
        assertEquals(listOf("internal_secret"), routeContext.droppedMetadataKeys)
        assertEquals("checkout", routeContext.ownership.serviceName)
        assertNull(routeContext.reference("metadata.internal_secret"))
    }

    @Test
    fun `approved metadata with a non scalar value is reported as dropped`() {
        val event =
            AlertLifecycleEvent(
                title = "Checkout latency high",
                description = "p95 latency above 800ms",
                priority = AlertPriority.P1,
                status = AlertStatus.FIRING,
                source = AlertSource.DASHBOARD_ALERT,
                deduplicationKey = "moneat-dashboard-alert-42",
                organizationId = organizationId,
                metadata =
                    mapOf(
                        "environment" to JsonArray(listOf(JsonPrimitive("production"))),
                        "service" to JsonNull,
                    ),
                moneatUrl = "https://moneat.example/alerts/42",
            )

        val routeContext = AlertRouteContextFactory.fromLifecycleEvent(event)

        assertTrue(routeContext.metadata.isEmpty())
        assertEquals(listOf("environment", "service"), routeContext.droppedMetadataKeys)
    }
}
