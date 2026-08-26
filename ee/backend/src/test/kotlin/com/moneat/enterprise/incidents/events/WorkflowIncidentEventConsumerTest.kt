// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.events

import com.moneat.alerts.models.IncidentSeverity
import com.moneat.workflows.services.DeclaredIncidentRoleChange
import com.moneat.workflows.services.WorkflowService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

class WorkflowIncidentEventConsumerTest {
    private val workflowService = mockk<WorkflowService>()
    private val consumer = WorkflowIncidentEventConsumer(workflowService)

    @Test
    fun `triage declaration does not publish an accepted incident workflow`() = runBlocking {
        consumer.consume(
            event(
                eventType = "INCIDENT_DECLARE",
                status = "TRIAGE",
                severity = null,
            ),
            deliveryKey = "workflows:triage-declare",
        )

        confirmVerified(workflowService)
    }

    @Test
    fun `active declaration publishes the created workflow with its real severity`() = runBlocking {
        coEvery { workflowService.publishDeclaredIncidentCreated(any(), any(), any(), any()) } returns Unit

        consumer.consume(
            event(
                eventType = "INCIDENT_DECLARE",
                status = "ACTIVE",
                severity = "SEV-2",
            ),
            deliveryKey = "workflows:active-declare",
        )

        coVerify(exactly = 1) {
            workflowService.publishDeclaredIncidentCreated(7, 42, "Checkout unavailable", IncidentSeverity.SEV2)
        }
    }

    @Test
    fun `accept publishes the classified created workflow exactly once per delivery`() = runBlocking {
        coEvery { workflowService.publishDeclaredIncidentCreated(any(), any(), any(), any()) } returns Unit

        consumer.consume(
            event(
                eventType = "INCIDENT_ACCEPT",
                status = "ACTIVE",
                severity = "SEV-1",
            ),
            deliveryKey = "workflows:accept",
        )

        coVerify(exactly = 1) {
            workflowService.publishDeclaredIncidentCreated(7, 42, "Checkout unavailable", IncidentSeverity.SEV1)
        }
    }

    @Test
    fun `role changes publish the role workflow with incident context`() = runBlocking {
        coEvery {
            workflowService.publishDeclaredIncidentRoleChanged(any())
        } returns Unit

        consumer.consume(
            event(
                eventType = "INCIDENT_ASSIGN_ROLE",
                status = "ACTIVE",
                severity = "SEV-1",
            ),
            deliveryKey = "workflows:role-assignment",
        )

        coVerify(exactly = 1) {
            workflowService.publishDeclaredIncidentRoleChanged(
                DeclaredIncidentRoleChange(
                    organizationId = 7,
                    incidentId = 42,
                    title = "Checkout unavailable",
                    severity = IncidentSeverity.SEV1,
                    role = "Incident role",
                    assignee = null,
                    action = "assign_role",
                ),
            )
        }
    }

    private fun event(
        eventType: String,
        status: String,
        severity: String?,
    ): NativeIncidentDomainEvent =
        NativeIncidentDomainEvent(
            id = 1,
            resourceId = "event-resource-id",
            organizationId = 7,
            incidentId = 42,
            eventType = eventType,
            aggregateVersion = 1,
            idempotencyKey = "incident-command",
            payload = payload(status, severity),
            createdAt = "2026-08-23T00:00:00Z",
        )

    private fun payload(status: String, severity: String?): Map<String, JsonElement> =
        mapOf(
            "title" to JsonPrimitive("Checkout unavailable"),
            "status" to JsonPrimitive(status),
            "severity" to (severity?.let(::JsonPrimitive) ?: JsonNull),
        )
}
