// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.events

import com.moneat.enterprise.incidents.slack.IncidentSlackChannelService

class IncidentSlackChannelEventConsumer(
    private val channelService: IncidentSlackChannelService = IncidentSlackChannelService(),
) : NativeIncidentEventConsumer {
    override val name: String = "incident-slack-channels"

    override suspend fun consume(event: NativeIncidentDomainEvent, deliveryKey: String) {
        require(deliveryKey.isNotBlank()) { "Incident Slack channel delivery key is required" }
        when (event.eventType) {
            "INCIDENT_DECLARE", "INCIDENT_ACCEPT", "INCIDENT_REOPEN" -> channelService.provision(event)
            "INCIDENT_RESOLVE", "INCIDENT_CANCEL", "INCIDENT_CLOSE", "INCIDENT_MERGE" -> channelService.archive(event)
            else -> Unit
        }
    }
}
