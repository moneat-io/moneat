// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.events

import com.moneat.enterprise.incidents.response.IncidentResponseActivationService

class IncidentResponseEventConsumer(
    private val activationService: IncidentResponseActivationService,
) : NativeIncidentEventConsumer {
    override val name: String = "incident-response"

    override suspend fun consume(event: NativeIncidentDomainEvent, deliveryKey: String) {
        require(deliveryKey.isNotBlank()) { "Incident response delivery key is required" }
        activationService.activate(event)
    }
}
