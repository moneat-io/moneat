// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.events

import com.moneat.alerts.models.IncidentSeverity
import com.moneat.workflows.services.DeclaredIncidentRoleChange
import com.moneat.workflows.services.WorkflowService
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class WorkflowIncidentEventConsumer(
    private val workflowService: WorkflowService = WorkflowService(),
) : NativeIncidentEventConsumer {
    override val name: String = "workflows"

    override suspend fun consume(event: NativeIncidentDomainEvent, deliveryKey: String) {
        require(deliveryKey.isNotBlank()) { "Workflow incident delivery key is required" }
        when (event.eventType) {
            "INCIDENT_DECLARE" -> if (event.status() == ACCEPTED_STATUS) event.publishCreatedWorkflow()
            "INCIDENT_ACCEPT" -> event.publishCreatedWorkflow()
            in ROLE_CHANGE_EVENTS -> event.publishRoleChangedWorkflow()
            "INCIDENT_RESOLVE" ->
                workflowService.publishDeclaredIncidentResolved(
                    organizationId = event.organizationId,
                    incidentId = event.incidentId,
                    title = event.title(),
                    severity = event.severity(),
                )
            else -> Unit
        }
    }

    private suspend fun NativeIncidentDomainEvent.publishCreatedWorkflow() {
        workflowService.publishDeclaredIncidentCreated(
            organizationId = organizationId,
            incidentId = incidentId,
            title = title(),
            severity = severity(),
        )
    }

    private suspend fun NativeIncidentDomainEvent.publishRoleChangedWorkflow() {
        workflowService.publishDeclaredIncidentRoleChanged(
            DeclaredIncidentRoleChange(
                organizationId = organizationId,
                incidentId = incidentId,
                title = title(),
                severity = severityOrNull(),
                role = payload["role"]?.jsonPrimitive?.contentOrNull ?: "Incident role",
                assignee = payload["assigneeUserId"]?.jsonPrimitive?.contentOrNull,
                action = eventType.removePrefix("INCIDENT_").lowercase(),
            ),
        )
    }

    private fun NativeIncidentDomainEvent.title(): String = payload.getValue("title").jsonPrimitive.content

    private fun NativeIncidentDomainEvent.severity(): IncidentSeverity {
        val raw = (payload["severity"] as? JsonPrimitive)?.contentOrNull
            ?: error("Accepted incident event is missing severity")
        return requireNotNull(IncidentSeverity.fromString(raw)) { "Incident event has invalid severity" }
    }

    private fun NativeIncidentDomainEvent.severityOrNull(): IncidentSeverity? {
        val raw = (payload["severity"] as? JsonPrimitive)?.contentOrNull ?: return null
        return IncidentSeverity.fromString(raw)
    }

    private fun NativeIncidentDomainEvent.status(): String? =
        (payload["status"] as? JsonPrimitive)?.contentOrNull

    private companion object {
        private const val ACCEPTED_STATUS = "ACTIVE"
        private val ROLE_CHANGE_EVENTS = setOf(
            "INCIDENT_ASSIGN_ROLE",
            "INCIDENT_CLAIM_ROLE",
            "INCIDENT_UNASSIGN_ROLE",
            "INCIDENT_HANDOVER_ROLE",
        )
    }
}
