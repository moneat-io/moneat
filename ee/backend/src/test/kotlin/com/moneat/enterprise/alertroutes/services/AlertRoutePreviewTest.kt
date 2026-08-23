// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.enterprise.alertroutes.AlertRouteTestDatabase
import com.moneat.enterprise.alertroutes.SeededAlertRouteOrganization
import com.moneat.enterprise.alertroutes.commands.AlertRouteActor
import com.moneat.enterprise.alertroutes.commands.AlertRouteConditionGroupInput
import com.moneat.enterprise.alertroutes.commands.AlertRouteConditionInput
import com.moneat.enterprise.alertroutes.commands.AlertRouteGroupingInput
import com.moneat.enterprise.alertroutes.commands.AlertRouteIncidentInput
import com.moneat.enterprise.alertroutes.commands.AlertRoutePagingInput
import com.moneat.enterprise.alertroutes.commands.AlertRoutePolicy
import com.moneat.enterprise.alertroutes.commands.AlertRouteSpecification
import com.moneat.enterprise.alertroutes.commands.AlertRouteTargetInput
import com.moneat.enterprise.alertroutes.commands.CreateAlertRouteCommand
import com.moneat.enterprise.alertroutes.context.AlertRouteSampleInput
import com.moneat.enterprise.alertroutes.evaluation.AlertRouteEvaluationReason
import com.moneat.enterprise.alertroutes.models.AlertRouteConditionOperator
import com.moneat.enterprise.alertroutes.models.AlertRouteContextField
import com.moneat.enterprise.alertroutes.models.AlertRouteIncidentMode
import com.moneat.enterprise.alertroutes.models.AlertRouteOwnershipSource
import com.moneat.enterprise.alertroutes.models.AlertRoutePagingMode
import com.moneat.enterprise.alertroutes.models.AlertRouteTargetKind
import com.moneat.enterprise.incidents.commands.IncidentCommandDeniedException
import com.moneat.enterprise.incidents.commands.IncidentCommandNotFoundException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AlertRoutePreviewTest {
    private val catalogResourceId = "service:7:checkout"

    private lateinit var organization: SeededAlertRouteOrganization
    private lateinit var commandService: AlertRouteCommandService
    private lateinit var evaluationService: AlertRouteEvaluationService

    @BeforeEach
    fun setUp() {
        AlertRouteTestDatabase.reset()
        organization = AlertRouteTestDatabase.seedOrganization("preview-org")
        commandService = AlertRouteCommandService(policy = AlertRoutePolicy.allowForTests())
        evaluationService = AlertRouteEvaluationService(policy = AlertRoutePolicy.allowForTests())
    }

    @AfterEach
    fun tearDown() {
        AlertRouteTestDatabase.clearReference()
    }

    private fun seedOwnedCatalogResource(): Int {
        val policy = AlertRouteTestDatabase.seedEscalationPolicy(organization.organizationId, "Checkout")
        val team = AlertRouteTestDatabase.seedTeam(organization.organizationId, "Checkout Team", policy.id)
        AlertRouteTestDatabase.seedOwnershipClaim(organization.organizationId, catalogResourceId, team.id)
        return policy.id
    }

    private fun createCatalogRoute() {
        commandService.execute(
            CreateAlertRouteCommand(
                commandKey = "create-catalog-route",
                actor = AlertRouteActor(organization.organizationId, organization.adminUserId, "TEST"),
                specification =
                    AlertRouteSpecification(
                        key = "checkout-ownership",
                        name = "Checkout ownership",
                        conditionGroups =
                            listOf(
                                AlertRouteConditionGroupInput(
                                    listOf(
                                        AlertRouteConditionInput(
                                            field = AlertRouteContextField.METADATA,
                                            operator = AlertRouteConditionOperator.EQUALS,
                                            values = listOf(catalogResourceId),
                                            metadataKey = "catalog_resource_id",
                                        ),
                                    ),
                                ),
                            ),
                        paging =
                            AlertRoutePagingInput(
                                mode = AlertRoutePagingMode.EVERY_EPISODE,
                                targets =
                                    listOf(
                                        AlertRouteTargetInput(
                                            kind = AlertRouteTargetKind.OWNERSHIP_DERIVED,
                                            ownershipSource = AlertRouteOwnershipSource.CATALOG,
                                        ),
                                    ),
                            ),
                        grouping = AlertRouteGroupingInput(keys = listOf("episode.key")),
                        incident =
                            AlertRouteIncidentInput(
                                create = true,
                                mode = AlertRouteIncidentMode.ACTIVE,
                                titleTemplate = "{{ ownership.team }} - {{ alert.title }}",
                                severity = "SEV-2",
                            ),
                    ),
            ),
        )
    }

    @Test
    fun `preview of a supplied sample resolves ownership derived escalation targets`() {
        val policyId = seedOwnedCatalogResource()
        createCatalogRoute()

        val result =
            evaluationService.preview(
                organization.organizationId,
                AlertRouteSampleInput(
                    source = "DASHBOARD_ALERT",
                    priority = "P1",
                    title = "Checkout latency high",
                    deduplicationKey = "moneat-dashboard-alert-42",
                    metadata = mapOf(" catalog_resource_id " to catalogResourceId),
                ),
            )

        val decision = result.decision!!
        assertEquals(listOf(policyId), decision.paging.escalationPolicies.map { it.id })
        assertEquals("Checkout Team - Checkout latency high", decision.incident.title)
        assertTrue(result.evaluations.single().selected)
    }

    @Test
    fun `preview treats blank ownership metadata the same as live evaluation`() {
        commandService.execute(
            CreateAlertRouteCommand(
                commandKey = "create-service-ownership-route",
                actor = AlertRouteActor(organization.organizationId, organization.adminUserId, "TEST"),
                specification =
                    AlertRouteSpecification(
                        key = "service-ownership",
                        name = "Service ownership",
                        paging =
                            AlertRoutePagingInput(
                                mode = AlertRoutePagingMode.EVERY_EPISODE,
                                targets =
                                    listOf(
                                        AlertRouteTargetInput(
                                            kind = AlertRouteTargetKind.OWNERSHIP_DERIVED,
                                            ownershipSource = AlertRouteOwnershipSource.SERVICE,
                                        ),
                                    ),
                            ),
                    ),
            ),
        )

        val result =
            evaluationService.preview(
                organization.organizationId,
                AlertRouteSampleInput(source = "DASHBOARD_ALERT", metadata = mapOf("service" to "")),
            )

        assertEquals(listOf("OWNERSHIP_DERIVED:SERVICE"), result.decision!!.paging.unresolvedTargets)
    }

    @Test
    fun `preview explains non-matching routes without selecting one`() {
        seedOwnedCatalogResource()
        createCatalogRoute()

        val result =
            evaluationService.preview(
                organization.organizationId,
                AlertRouteSampleInput(
                    source = "HOST_ALERT",
                    metadata = mapOf("catalog_resource_id" to "service:9:billing"),
                ),
            )

        assertNull(result.decision)
        val evaluation = result.evaluations.single()
        assertEquals(AlertRouteEvaluationReason.NO_GROUP_MATCHED, evaluation.reason)
        val condition = evaluation.groups.single().conditions.single()
        assertEquals("service:9:billing", condition.actualValue)
        assertEquals(false, condition.matched)
    }

    @Test
    fun `preview of a saved alert episode carries episode identity into grouping`() {
        seedOwnedCatalogResource()
        createCatalogRoute()
        val episodeId =
            AlertRouteTestDatabase.seedAlertEpisode(
                organizationId = organization.organizationId,
                source = "DASHBOARD_ALERT",
                deduplicationKey = "moneat-dashboard-alert-42",
            )

        val result =
            evaluationService.previewSavedEpisode(
                organizationId = organization.organizationId,
                episodeId = episodeId,
                metadata = mapOf("catalog_resource_id" to catalogResourceId),
            )

        val grouping = result.decision!!.grouping
        assertEquals("checkout-ownership|moneat-dashboard-alert-42#1", grouping.groupKey)
        assertTrue(grouping.unresolvedKeys.isEmpty())
    }

    @Test
    fun `preview reports unapproved metadata keys and rejects unknown episodes`() {
        seedOwnedCatalogResource()
        createCatalogRoute()

        val result =
            evaluationService.preview(
                organization.organizationId,
                AlertRouteSampleInput(
                    source = "DASHBOARD_ALERT",
                    metadata =
                        mapOf(
                            "catalog_resource_id" to catalogResourceId,
                            "internal_secret" to "hidden",
                        ),
                ),
            )

        assertEquals(listOf("internal_secret"), result.droppedMetadataKeys)
        assertFailsWith<IncidentCommandNotFoundException> {
            evaluationService.previewSavedEpisode(organization.organizationId, Uuid.random())
        }
    }

    @Test
    fun `preview is rollout gated`() {
        val gated = AlertRouteEvaluationService(policy = AlertRoutePolicy.denyForTests())

        assertFailsWith<IncidentCommandDeniedException> {
            gated.preview(organization.organizationId, AlertRouteSampleInput(source = "HOST_ALERT"))
        }
    }
}
