// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.events

import com.moneat.alerts.models.IncidentSeverity
import com.moneat.workflows.services.WorkflowService
import kotlinx.serialization.json.jsonPrimitive

class WorkflowIncidentEventConsumer(
    private val workflowService: WorkflowService = WorkflowService(),
) : NativeIncidentEventConsumer {
    override val name: String = "workflows"

    override suspend fun consume(event: NativeIncidentDomainEvent, deliveryKey: String) {
        require(deliveryKey.isNotBlank()) { "Workflow incident delivery key is required" }
        val title = event.payload.getValue("title").jsonPrimitive.content
        val severity =
            requireNotNull(IncidentSeverity.fromString(event.payload.getValue("severity").jsonPrimitive.content)) {
                "Incident event has invalid severity"
            }
        when (event.eventType) {
            "INCIDENT_DECLARE" ->
                workflowService.publishDeclaredIncidentCreated(
                    organizationId = event.organizationId,
                    incidentId = event.incidentId,
                    title = title,
                    severity = severity,
                )
            "INCIDENT_RESOLVE" ->
                workflowService.publishDeclaredIncidentResolved(
                    organizationId = event.organizationId,
                    incidentId = event.incidentId,
                    title = title,
                    severity = severity,
                )
        }
    }
}
