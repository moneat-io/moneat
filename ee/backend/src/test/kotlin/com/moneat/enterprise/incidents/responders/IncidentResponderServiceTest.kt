// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.responders

import com.moneat.enterprise.incidents.IncidentTestDatabase
import com.moneat.enterprise.incidents.SeededMember
import com.moneat.enterprise.incidents.commands.DeclareIncidentCommand
import com.moneat.enterprise.incidents.commands.HandoverIncidentRoleCommand
import com.moneat.enterprise.incidents.commands.IncidentCommandActor
import com.moneat.enterprise.incidents.commands.IncidentCommandPolicy
import com.moneat.enterprise.incidents.commands.IncidentCommandService
import com.moneat.enterprise.incidents.commands.LeaveIncidentCommand
import com.moneat.enterprise.incidents.commands.SetIncidentParticipationCommand
import com.moneat.enterprise.incidents.commands.AssignIncidentRoleCommand
import com.moneat.enterprise.incidents.models.IncidentParticipationType
import com.moneat.enterprise.incidents.models.NativeIncidentHandovers
import com.moneat.enterprise.incidents.models.NativeIncidentOutboxEvents
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncidentResponderServiceTest {
    private lateinit var member: SeededMember
    private lateinit var service: IncidentResponderService
    private lateinit var commandService: IncidentCommandService

    @BeforeEach
    fun setUp() {
        IncidentTestDatabase.reset()
        member = IncidentTestDatabase.seedMember()
        service = IncidentResponderService()
        commandService = IncidentCommandService(policy = IncidentCommandPolicy.allowForTests())
    }

    @AfterEach
    fun tearDown() {
        IncidentTestDatabase.clearReference()
    }

    @Test
    fun `provides defaults and versions custom role definitions`() {
        val defaults = service.listRoles(member.organizationId, member.userId)
        assertEquals(setOf("incident-commander", "communications-lead"), defaults.mapTo(mutableSetOf()) { it.key })
        assertTrue(defaults.all { it.default && it.required })

        val first = service.createRole(member.organizationId, member.userId, customRole("operations-lead"))
        val second =
            service.createRole(
                member.organizationId,
                member.userId,
                customRole("operations-lead").copy(name = "Operations Coordinator"),
            )
        assertEquals(1, first.version)
        assertEquals(2, second.version)
        assertEquals(
            "Operations Coordinator",
            service.listRoles(member.organizationId, member.userId).single { it.key == "operations-lead" }.name,
        )
    }

    @Test
    fun `tracks role handover and participant observer history through commands`() {
        val secondUserId = IncidentTestDatabase.seedUserInOrganization(member.organizationId, "second-responder")
        val secondUserResourceId = publicUserId(secondUserId)
        val role = service.createRole(member.organizationId, member.userId, customRole("operations-lead"))
        val roleId = service.resolveRoleId(member.organizationId, role.id)
        val incident =
            commandService.execute(
                DeclareIncidentCommand(
                    commandKey = "declare-responder-test",
                    actor = actor(),
                    title = "Queue unavailable",
                    description = null,
                    severity = "SEV-2",
                ),
            )

        commandService.execute(
            AssignIncidentRoleCommand(
                commandKey = "assign-responder-test",
                actor = actor(),
                incidentId = incident.incidentId,
                roleDefinitionId = roleId,
                assigneeUserId = secondUserId,
                expectedVersion = 1,
            ),
        )
        val assignedRole = service.listAssignments(member.organizationId, incident.incidentId).single()
        assertEquals(secondUserResourceId, assignedRole.assigneeUserId)
        assertEquals(null, assignedRole.role.privateInstructions)

        commandService.execute(
            HandoverIncidentRoleCommand(
                commandKey = "handover-responder-test",
                actor = actor(),
                incidentId = incident.incidentId,
                roleDefinitionId = roleId,
                toUserId = member.userId,
                note = "Shift change",
                expectedVersion = 2,
            ),
        )
        assertEquals(
            publicUserId(member.userId),
            service.listAssignments(member.organizationId, incident.incidentId).single().assigneeUserId,
        )
        transaction {
            assertEquals(1, NativeIncidentHandovers.selectAll().count())
            assertTrue(
                NativeIncidentOutboxEvents.selectAll().any {
                    it[NativeIncidentOutboxEvents.eventType] == "INCIDENT_ROLE_INSTRUCTIONS"
                },
            )
        }

        commandService.execute(
            SetIncidentParticipationCommand(
                commandKey = "observe-responder-test",
                actor = actor(),
                incidentId = incident.incidentId,
                userId = secondUserId,
                participationType = IncidentParticipationType.OBSERVER,
                expectedVersion = 3,
            ),
        )
        assertEquals(
            IncidentParticipationType.OBSERVER,
            service.listParticipants(member.organizationId, incident.incidentId)
                .single { it.userId == secondUserResourceId }
                .type,
        )

        commandService.execute(
            LeaveIncidentCommand(
                commandKey = "leave-responder-test",
                actor = actor(),
                incidentId = incident.incidentId,
                userId = secondUserId,
                expectedVersion = 4,
            ),
        )
        assertTrue(
            service.listParticipants(member.organizationId, incident.incidentId)
                .none { it.userId == secondUserResourceId },
        )
    }

    private fun actor() = IncidentCommandActor(member.organizationId, member.userId, "REST")

    private fun publicUserId(userId: Int): String =
        transaction {
            Users.selectAll().where { Users.id eq userId }.single()[Users.resource_id].toString()
        }

    private fun customRole(key: String) =
        CreateIncidentRole(
            stableKey = key,
            name = "Operations Lead",
            description = "Coordinates technical operations.",
            responsibilities = listOf("Coordinate mitigation", "Track technical owners"),
            privateInstructions = "Keep operational details in the private responder thread.",
            required = false,
            default = false,
        )
}
