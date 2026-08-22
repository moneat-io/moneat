// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.events

import com.moneat.alerts.models.IncidentSeverity
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
            "INCIDENT_DECLARE" ->
                workflowService.publishDeclaredIncidentCreated(
                    organizationId = event.organizationId,
                    incidentId = event.incidentId,
                    title = event.title(),
                    severity = event.severity(),
                )
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

    private fun NativeIncidentDomainEvent.title(): String = payload.getValue("title").jsonPrimitive.content

    /** Triage incidents can still be unclassified, so workflows see the lowest severity until acceptance. */
    private fun NativeIncidentDomainEvent.severity(): IncidentSeverity {
        val raw = (payload["severity"] as? JsonPrimitive)?.contentOrNull ?: return UNCLASSIFIED_SEVERITY
        return requireNotNull(IncidentSeverity.fromString(raw)) { "Incident event has invalid severity" }
    }

    private companion object {
        private val UNCLASSIFIED_SEVERITY = IncidentSeverity.SEV4
    }
}
