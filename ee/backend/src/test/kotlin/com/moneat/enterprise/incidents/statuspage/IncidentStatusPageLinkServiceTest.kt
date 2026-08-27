// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.statuspage

import com.moneat.enterprise.incidents.IncidentTestDatabase
import com.moneat.enterprise.incidents.SeededMember
import com.moneat.enterprise.incidents.commands.DeclareIncidentCommand
import com.moneat.enterprise.incidents.commands.IncidentCommandActor
import com.moneat.enterprise.incidents.commands.IncidentCommandPolicy
import com.moneat.enterprise.incidents.commands.IncidentCommandService
import com.moneat.enterprise.incidents.models.NativeIncidentMode
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.incidents.models.NativeIncidentVisibility
import com.moneat.enterprise.incidents.models.NativeIncidentSourceLinks
import com.moneat.enterprise.oncall.models.OnCallIncidentTimeline
import com.moneat.statuspage.models.CreateStatusPageRequest
import com.moneat.statuspage.services.StatusPageService
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class IncidentStatusPageLinkServiceTest {
    private lateinit var member: SeededMember
    private var incidentId: Int = 0
    private lateinit var statusPageId: UUID
    private lateinit var service: IncidentStatusPageLinkService

    @BeforeEach
    fun setUp() {
        IncidentTestDatabase.reset()
        member = IncidentTestDatabase.seedMember("status-page-link")
        val commandService = IncidentCommandService(policy = IncidentCommandPolicy.allowForTests())
        incidentId = commandService.execute(
            DeclareIncidentCommand(
                commandKey = "declare-status-page",
                actor = actor(),
                title = "Checkout degraded",
                description = "Checkout requests are slow",
                severity = "SEV-2",
                mode = NativeIncidentMode.TEST,
                visibility = NativeIncidentVisibility.ORGANIZATION,
                initialStatus = NativeIncidentStatus.ACTIVE,
            ),
        ).incidentId
        statusPageId = StatusPageService().createStatusPage(
            member.organizationId,
            CreateStatusPageRequest("Public status", "public-status"),
        ).id.let(UUID::fromString)
        service = IncidentStatusPageLinkService(incidentCommandService = commandService)
    }

    @AfterEach
    fun tearDown() {
        IncidentTestDatabase.clearReference()
    }

    @Test
    fun `create is linked and idempotent by correlation key`() {
        val request = createRequest()
        val target = target()
        val first = service.create(target, request, "create-1")
        val second = service.create(target, request, "create-2")

        assertEquals(first.id, second.id)
        assertEquals(
            1,
            transaction {
                NativeIncidentSourceLinks.selectAll().where {
                    (NativeIncidentSourceLinks.incidentId eq incidentId) and
                        (NativeIncidentSourceLinks.sourceType eq "URL")
                }.count()
            },
        )
        assertNotNull(
            transaction {
                OnCallIncidentTimeline.selectAll().where {
                    (OnCallIncidentTimeline.incidentId eq incidentId) and
                        (OnCallIncidentTimeline.eventType eq "STATUS_PAGE_INCIDENT_CREATED")
                }.singleOrNull()
            },
        )
    }

    @Test
    fun `update with a message publishes a status-page update and resolve changes status`() {
        val created = service.create(target(), createRequest(), "create")
        val updated = service.update(
            target().copy(statusPageIncidentId = UUID.fromString(created.id)),
            UpdateLinkedStatusPageIncidentRequest(message = "We are monitoring recovery"),
            "update",
        )
        val resolved = service.update(
            target().copy(statusPageIncidentId = UUID.fromString(created.id)),
            UpdateLinkedStatusPageIncidentRequest(status = "resolved"),
            "resolve",
        )

        assertEquals("investigating", updated.status)
        assertEquals("resolved", resolved.status)
    }

    @Test
    fun `private incidents require an active responder`() {
        val privateIncident = IncidentCommandService(policy = IncidentCommandPolicy.allowForTests()).execute(
            DeclareIncidentCommand(
                commandKey = "declare-private-status-page",
                actor = actor(),
                title = "Private incident",
                description = null,
                severity = "SEV-2",
                mode = NativeIncidentMode.TEST,
                visibility = NativeIncidentVisibility.PRIVATE,
                initialStatus = NativeIncidentStatus.ACTIVE,
            ),
        )
        val outsiderUserId = IncidentTestDatabase.seedUserInOrganization(member.organizationId, "status-page-outsider")

        assertFailsWith<com.moneat.enterprise.incidents.commands.IncidentCommandDeniedException> {
            service.create(
                target().copy(actorUserId = outsiderUserId, incidentId = privateIncident.incidentId),
                createRequest(),
                "private-create",
            )
        }
    }

    private fun createRequest() = CreateLinkedStatusPageIncidentRequest(
        title = "Checkout degraded",
        status = "investigating",
        impact = "minor",
        message = "We are investigating elevated checkout latency.",
        sourceUrl = "https://status.example.test/incidents/checkout",
        correlationKey = "checkout-degraded",
    )

    private fun target() = IncidentStatusPageLinkTarget(
        organizationId = member.organizationId,
        actorUserId = member.userId,
        incidentId = incidentId,
        statusPageId = statusPageId,
    )

    private fun actor() = IncidentCommandActor(member.organizationId, member.userId, "TEST")
}
