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
import com.moneat.enterprise.alertroutes.commands.AlertRouteQuotaAdmission
import com.moneat.enterprise.alertroutes.commands.AlertRouteRecoveryInput
import com.moneat.enterprise.alertroutes.commands.AlertRouteOrderEntry
import com.moneat.enterprise.alertroutes.commands.AlertRouteSpecification
import com.moneat.enterprise.alertroutes.commands.AlertRouteTargetInput
import com.moneat.enterprise.alertroutes.commands.CreateAlertRouteCommand
import com.moneat.enterprise.alertroutes.commands.DeleteAlertRouteCommand
import com.moneat.enterprise.alertroutes.commands.ReorderAlertRoutesCommand
import com.moneat.enterprise.alertroutes.commands.UpdateAlertRouteCommand
import com.moneat.enterprise.alertroutes.models.AlertRouteChangeType
import com.moneat.enterprise.alertroutes.models.AlertRouteConditionOperator
import com.moneat.enterprise.alertroutes.models.AlertRouteContextField
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingBehavior
import com.moneat.enterprise.alertroutes.models.AlertRouteIncidentMode
import com.moneat.enterprise.alertroutes.models.AlertRoutePagingMode
import com.moneat.enterprise.alertroutes.models.AlertRouteTargetKind
import com.moneat.enterprise.alertroutes.models.EnterpriseAlertRoutes
import com.moneat.enterprise.NativeIncidentQuotaDecision
import com.moneat.enterprise.NativeIncidentQuotaKey
import com.moneat.enterprise.NativeIncidentQuotaStatus
import com.moneat.enterprise.incidents.commands.IncidentCommandConflictException
import com.moneat.enterprise.incidents.commands.IncidentCommandDeniedException
import com.moneat.enterprise.incidents.commands.IncidentCommandNotFoundException
import com.moneat.enterprise.incidents.commands.IncidentCommandQuotaExceededException
import com.moneat.enterprise.incidents.commands.IncidentEntitlement
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.OrganizationTeams
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class AlertRouteCommandServiceTest {
    private lateinit var organization: SeededAlertRouteOrganization
    private lateinit var service: AlertRouteCommandService

    @BeforeEach
    fun setUp() {
        AlertRouteTestDatabase.reset()
        organization = AlertRouteTestDatabase.seedOrganization()
        service = AlertRouteCommandService(policy = AlertRoutePolicy.allowForTests())
    }

    @AfterEach
    fun tearDown() {
        AlertRouteTestDatabase.clearReference()
    }

    private fun admin(): AlertRouteActor =
        AlertRouteActor(organization.organizationId, organization.adminUserId, "TEST")

    private fun specification(
        key: String = "checkout-paging",
        name: String = "Checkout paging",
        enabled: Boolean = true,
    ): AlertRouteSpecification =
        AlertRouteSpecification(
            key = key,
            name = name,
            enabled = enabled,
            conditionGroups =
                listOf(
                    AlertRouteConditionGroupInput(
                        listOf(
                            AlertRouteConditionInput(
                                field = AlertRouteContextField.METADATA,
                                operator = AlertRouteConditionOperator.EQUALS,
                                values = listOf("checkout"),
                                metadataKey = "service",
                            ),
                        ),
                    ),
                ),
            grouping =
                AlertRouteGroupingInput(
                    behavior = AlertRouteGroupingBehavior.AUTOMATIC,
                    keys = listOf("ownership.service"),
                ),
            incident =
                AlertRouteIncidentInput(
                    create = true,
                    mode = AlertRouteIncidentMode.ACTIVE,
                    titleTemplate = "{{ alert.title }}",
                    severity = "SEV-2",
                ),
        )

    private fun create(
        key: String = "checkout-paging",
        commandKey: String = "create-$key",
    ) = service.execute(
        CreateAlertRouteCommand(
            commandKey = commandKey,
            actor = admin(),
            specification = specification(key = key, name = key),
        ),
    )

    @Test
    fun `create persists the full specification and records the first audited revision`() {
        val policy = AlertRouteTestDatabase.seedEscalationPolicy(organization.organizationId, "Payments")
        val result =
            service.execute(
                CreateAlertRouteCommand(
                    commandKey = "create-checkout",
                    actor = admin(),
                    specification =
                        specification().copy(
                            paging =
                                AlertRoutePagingInput(
                                    mode = AlertRoutePagingMode.EVERY_EPISODE,
                                    targets =
                                        listOf(
                                            AlertRouteTargetInput(
                                                kind = AlertRouteTargetKind.ESCALATION_POLICY,
                                                escalationPolicyId = policy.resourceId,
                                            ),
                                        ),
                                ),
                            recovery =
                                AlertRouteRecoveryInput(
                                    cancelEscalations = true,
                                    declineUnacceptedTriage = false,
                                    graceSeconds = 120,
                                ),
                        ),
                ),
            )

        val route = result.route!!
        assertFalse(result.replayed)
        assertEquals(1, route.revision)
        assertEquals(0, route.position)
        assertEquals(AlertRouteGroupingBehavior.AUTOMATIC, route.grouping.behavior)
        assertEquals(AlertRoutePagingMode.EVERY_EPISODE, route.paging.mode)
        assertTrue(route.recovery.cancelEscalations)
        assertFalse(route.recovery.declineUnacceptedTriage)
        assertEquals(120, route.recovery.graceSeconds)
        assertEquals(policy.id, route.paging.targets.single().escalationPolicyId)
        assertEquals(policy.resourceId, route.paging.targets.single().escalationPolicyResourceId)
        assertEquals("service", route.conditionGroups.single().conditions.single().metadataKey)

        val revisions = service.revisions(organization.organizationId, route.resourceId)
        assertEquals(listOf(AlertRouteChangeType.CREATED), revisions.map { it.changeType })
        assertEquals("create-checkout", revisions.single().commandKey)
    }

    @Test
    fun `repeating a command key replays the same route and a different payload conflicts`() {
        val first = create(commandKey = "idempotent-create")
        val replay = create(commandKey = "idempotent-create")

        assertTrue(replay.replayed)
        assertEquals(first.route!!.resourceId, replay.route!!.resourceId)
        assertEquals(1, service.list(organization.organizationId).size)

        assertFailsWith<IncidentCommandConflictException> {
            service.execute(
                CreateAlertRouteCommand(
                    commandKey = "idempotent-create",
                    actor = admin(),
                    specification = specification(key = "other-key", name = "Other"),
                ),
            )
        }
    }

    @Test
    fun `command keys are organization global across different route mutations`() {
        val first = create(key = "route-a", commandKey = "create-a").route!!
        val second = create(key = "route-b", commandKey = "create-b").route!!

        service.execute(
            UpdateAlertRouteCommand(
                commandKey = "global-update",
                actor = admin(),
                routeId = first.resourceId,
                expectedRevision = first.revision,
                specification = specification(key = first.key, name = "Updated A"),
            ),
        )

        assertFailsWith<IncidentCommandConflictException> {
            service.execute(
                UpdateAlertRouteCommand(
                    commandKey = "global-update",
                    actor = admin(),
                    routeId = second.resourceId,
                    expectedRevision = second.revision,
                    specification = specification(key = second.key, name = "Updated B"),
                ),
            )
        }
        assertEquals("route-b", service.get(organization.organizationId, second.resourceId).name)
    }

    @Test
    fun `duplicate route keys are rejected as conflicts`() {
        create(key = "checkout-paging", commandKey = "first")

        assertFailsWith<IncidentCommandConflictException> {
            create(key = "checkout-paging", commandKey = "second")
        }
    }

    @Test
    fun `update bumps the revision and rejects stale expected revisions`() {
        val route = create().route!!

        val updated =
            service.execute(
                UpdateAlertRouteCommand(
                    commandKey = "update-1",
                    actor = admin(),
                    routeId = route.resourceId,
                    expectedRevision = route.revision,
                    specification = specification(name = "Checkout paging v2").copy(enabled = false),
                ),
            ).route!!

        assertEquals(2, updated.revision)
        assertFalse(updated.enabled)

        assertFailsWith<IncidentCommandConflictException> {
            service.execute(
                UpdateAlertRouteCommand(
                    commandKey = "update-2",
                    actor = admin(),
                    routeId = route.resourceId,
                    expectedRevision = route.revision,
                    specification = specification(name = "Checkout paging v3"),
                ),
            )
        }

        assertEquals(
            listOf(AlertRouteChangeType.UPDATED, AlertRouteChangeType.CREATED),
            service.revisions(organization.organizationId, route.resourceId).map { it.changeType },
        )
    }

    @Test
    fun `reorder rewrites positions once and replays when the order already matches`() {
        val first = create(key = "route-a", commandKey = "create-a").route!!
        val second = create(key = "route-b", commandKey = "create-b").route!!

        val reordered =
            service.execute(
                ReorderAlertRoutesCommand(
                    commandKey = "reorder-1",
                    actor = admin(),
                    orderedRoutes =
                        listOf(
                            AlertRouteOrderEntry(second.resourceId, second.revision),
                            AlertRouteOrderEntry(first.resourceId, first.revision),
                        ),
                ),
            )

        assertFalse(reordered.replayed)
        assertEquals(listOf(second.resourceId, first.resourceId), reordered.routes.map { it.resourceId })
        assertEquals(listOf(0, 1), reordered.routes.map { it.position })
        assertEquals(listOf(2, 2), reordered.routes.map { it.revision })

        val replay =
            service.execute(
                ReorderAlertRoutesCommand(
                    commandKey = "reorder-1",
                    actor = admin(),
                    orderedRoutes =
                        listOf(
                            AlertRouteOrderEntry(second.resourceId, second.revision),
                            AlertRouteOrderEntry(first.resourceId, first.revision),
                        ),
                ),
            )
        assertTrue(replay.replayed)
        assertEquals(listOf(2, 2), service.list(organization.organizationId).map { it.revision })

        val appended = create(key = "route-c", commandKey = "create-c").route!!
        assertEquals(2, appended.position)
    }

    @Test
    fun `delete and reorder reject stale revisions without partial mutations`() {
        val first = create(key = "route-a", commandKey = "create-a").route!!
        val second = create(key = "route-b", commandKey = "create-b").route!!
        val updated =
            service.execute(
                UpdateAlertRouteCommand(
                    commandKey = "update-a",
                    actor = admin(),
                    routeId = first.resourceId,
                    expectedRevision = first.revision,
                    specification = specification(key = first.key, name = "Updated A"),
                ),
            ).route!!

        assertFailsWith<IncidentCommandConflictException> {
            service.execute(
                DeleteAlertRouteCommand(
                    commandKey = "stale-delete",
                    actor = admin(),
                    routeId = first.resourceId,
                    expectedRevision = first.revision,
                ),
            )
        }
        assertFailsWith<IncidentCommandConflictException> {
            service.execute(
                ReorderAlertRoutesCommand(
                    commandKey = "stale-reorder",
                    actor = admin(),
                    orderedRoutes =
                        listOf(
                            AlertRouteOrderEntry(second.resourceId, second.revision),
                            AlertRouteOrderEntry(first.resourceId, first.revision),
                        ),
                ),
            )
        }
        assertEquals(
            listOf(first.resourceId, second.resourceId),
            service.list(organization.organizationId).map { it.resourceId },
        )
        assertEquals(updated.revision, service.get(organization.organizationId, first.resourceId).revision)
    }

    @Test
    fun `delete removes the route while the audit history survives`() {
        val route = create().route!!

        service.execute(
            DeleteAlertRouteCommand(
                commandKey = "delete-1",
                actor = admin(),
                routeId = route.resourceId,
                expectedRevision = route.revision,
            ),
        )

        assertTrue(service.list(organization.organizationId).isEmpty())
        assertEquals(
            listOf(AlertRouteChangeType.DELETED, AlertRouteChangeType.CREATED),
            service.revisions(organization.organizationId, route.resourceId).map { it.changeType },
        )
        val deletedSnapshot = service.revisions(organization.organizationId, route.resourceId).first().snapshot
        assertEquals(
            route.resourceId.toString(),
            deletedSnapshot.getValue("route").jsonObject.getValue("id").jsonPrimitive.content,
        )
        assertFailsWith<IncidentCommandNotFoundException> {
            service.get(organization.organizationId, route.resourceId)
        }
    }

    @Test
    fun `enabled route quota denial rolls back the attempted mutation`() {
        val quotaPolicy =
            AlertRoutePolicy(
                entitlement = IncidentEntitlement { true },
                quotaAdmission = AlertRouteQuotaAdmission { _, count, _ ->
                    NativeIncidentQuotaDecision(
                        allowed = count <= 1,
                        status = NativeIncidentQuotaStatus(NativeIncidentQuotaKey.ENABLED_ALERT_ROUTES, 1, count),
                    )
                },
            )
        service = AlertRouteCommandService(policy = quotaPolicy)
        create(key = "route-a", commandKey = "create-a")

        assertFailsWith<IncidentCommandQuotaExceededException> {
            create(key = "route-b", commandKey = "create-b")
        }
        assertEquals(listOf("route-a"), service.list(organization.organizationId).map { it.key })
        assertEquals(1, transaction { EnterpriseAlertRoutes.selectAll().count() })
    }

    @Test
    fun `referenced teams and escalation policies cannot be deleted silently`() {
        val policy = AlertRouteTestDatabase.seedEscalationPolicy(organization.organizationId, "Payments")
        val team = AlertRouteTestDatabase.seedTeam(organization.organizationId, "Payments Team")
        val route =
            service.execute(
                CreateAlertRouteCommand(
                    commandKey = "create-referenced",
                    actor = admin(),
                    specification =
                        specification(key = "referenced", name = "Referenced").copy(
                            paging =
                                AlertRoutePagingInput(
                                    mode = AlertRoutePagingMode.EVERY_EPISODE,
                                    targets =
                                        listOf(
                                            AlertRouteTargetInput(
                                                kind = AlertRouteTargetKind.ESCALATION_POLICY,
                                                escalationPolicyId = policy.resourceId,
                                            ),
                                            AlertRouteTargetInput(
                                                kind = AlertRouteTargetKind.TEAM,
                                                teamId = team.resourceId,
                                            ),
                                        ),
                                ),
                        ),
                ),
            ).route!!

        assertFailsWith<ExposedSQLException> {
            transaction { OrganizationTeams.deleteWhere { OrganizationTeams.id eq team.id } }
        }
        assertFailsWith<ExposedSQLException> {
            transaction { EscalationPolicies.deleteWhere { EscalationPolicies.id eq policy.id } }
        }
        assertEquals(route.revision, service.get(organization.organizationId, route.resourceId).revision)
        assertEquals(2, service.get(organization.organizationId, route.resourceId).paging.targets.size)
    }

    @Test
    fun `concurrent creates serialize into unique appended positions`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results =
                executor.invokeAll(
                    listOf(
                        Callable { create(key = "route-a", commandKey = "concurrent-a") },
                        Callable { create(key = "route-b", commandKey = "concurrent-b") },
                    ),
                ).map { it.get() }

            assertEquals(2, results.size)
            assertEquals(listOf(0, 1), service.list(organization.organizationId).map { it.position })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent retries with one command key create exactly one route`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results =
                executor.invokeAll(
                    listOf(
                        Callable { create(key = "route-a", commandKey = "same-command") },
                        Callable { create(key = "route-a", commandKey = "same-command") },
                    ),
                ).map { it.get() }

            assertEquals(listOf(false, true), results.map { it.replayed }.sorted())
            assertEquals(1, service.list(organization.organizationId).size)
            assertEquals(1, service.revisions(organization.organizationId, results.first().route!!.resourceId).size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `unknown routes are not found and other organizations cannot read them`() {
        val route = create().route!!
        val otherOrganization = AlertRouteTestDatabase.seedOrganization("other-org")

        assertFailsWith<IncidentCommandNotFoundException> {
            service.get(organization.organizationId, Uuid.random())
        }
        assertFailsWith<IncidentCommandNotFoundException> {
            service.get(otherOrganization.organizationId, route.resourceId)
        }
    }

    @Test
    fun `only organization administrators may mutate alert routes`() {
        val memberUserId = AlertRouteTestDatabase.seedUser(organization.organizationId, "member")
        val outsiderOrganization = AlertRouteTestDatabase.seedOrganization("outsider-org")

        assertFailsWith<IncidentCommandDeniedException> {
            service.execute(
                CreateAlertRouteCommand(
                    commandKey = "member-create",
                    actor = AlertRouteActor(organization.organizationId, memberUserId, "TEST"),
                    specification = specification(),
                ),
            )
        }
        assertFailsWith<IncidentCommandDeniedException> {
            service.execute(
                CreateAlertRouteCommand(
                    commandKey = "outsider-create",
                    actor = AlertRouteActor(organization.organizationId, outsiderOrganization.adminUserId, "TEST"),
                    specification = specification(),
                ),
            )
        }
    }

    @Test
    fun `rollout gating denies reads and writes for organizations without native incident response`() {
        val gatedService = AlertRouteCommandService(policy = AlertRoutePolicy.denyForTests())

        assertFailsWith<IncidentCommandDeniedException> { gatedService.list(organization.organizationId) }
        assertFailsWith<IncidentCommandDeniedException> {
            gatedService.execute(
                CreateAlertRouteCommand(
                    commandKey = "gated-create",
                    actor = admin(),
                    specification = specification(),
                ),
            )
        }
    }

    @Test
    fun `specifications reject unapproved metadata and require severity only for active incidents`() {
        assertFailsWith<IllegalArgumentException> {
            service.execute(
                CreateAlertRouteCommand(
                    commandKey = "bad-metadata",
                    actor = admin(),
                    specification =
                        specification().copy(
                            conditionGroups =
                                listOf(
                                    AlertRouteConditionGroupInput(
                                        listOf(
                                            AlertRouteConditionInput(
                                                field = AlertRouteContextField.METADATA,
                                                operator = AlertRouteConditionOperator.EQUALS,
                                                values = listOf("nope"),
                                                metadataKey = "internal_secret",
                                            ),
                                        ),
                                    ),
                                ),
                        ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.execute(
                CreateAlertRouteCommand(
                    commandKey = "bad-priority-list",
                    actor = admin(),
                    specification =
                        specification().copy(
                            conditionGroups =
                                listOf(
                                    AlertRouteConditionGroupInput(
                                        listOf(
                                            AlertRouteConditionInput(
                                                field = AlertRouteContextField.PRIORITY,
                                                operator = AlertRouteConditionOperator.AT_LEAST_AS_SEVERE_AS,
                                                values = listOf("P1", "not-a-priority"),
                                            ),
                                        ),
                                    ),
                                ),
                        ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.execute(
                CreateAlertRouteCommand(
                    commandKey = "bad-incident",
                    actor = admin(),
                    specification =
                        specification().copy(
                            incident =
                                AlertRouteIncidentInput(
                                    create = true,
                                    mode = AlertRouteIncidentMode.ACTIVE,
                                    severity = null,
                                ),
                        ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.execute(
                CreateAlertRouteCommand(
                    commandKey = "long-incident-type",
                    actor = admin(),
                    specification =
                        specification().copy(
                            incident = specification().incident.copy(incidentType = "x".repeat(121)),
                        ),
                ),
            )
        }

        val triage =
            service.execute(
                CreateAlertRouteCommand(
                    commandKey = "unclassified-triage",
                    actor = admin(),
                    specification =
                        specification(key = "triage", name = "Triage").copy(
                            incident = AlertRouteIncidentInput(create = true),
                        ),
                ),
            ).route!!
        assertEquals(AlertRouteIncidentMode.TRIAGE, triage.incident.mode)
        assertEquals(null, triage.incident.severity)
    }

    @Test
    fun `grouping references require canonical spelling`() {
        listOf(
            listOf(" metadata.service "),
            listOf("Metadata.service"),
            listOf("metadata.service", " metadata.service "),
        ).forEachIndexed { index, keys ->
            assertFailsWith<IllegalArgumentException> {
                service.execute(
                    CreateAlertRouteCommand(
                        commandKey = "non-canonical-grouping-$index",
                        actor = admin(),
                        specification = specification().copy(
                            grouping = AlertRouteGroupingInput(keys = keys),
                        ),
                    ),
                )
            }
        }
    }
}
