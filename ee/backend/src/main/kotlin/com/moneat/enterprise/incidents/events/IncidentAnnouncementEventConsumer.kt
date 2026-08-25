// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.events

import com.moneat.enterprise.incidents.announcements.IncidentAnnouncementService

class IncidentAnnouncementEventConsumer(
    private val announcementService: IncidentAnnouncementService = IncidentAnnouncementService(),
) : NativeIncidentEventConsumer {
    override val name: String = "incident-announcements"

    override suspend fun consume(event: NativeIncidentDomainEvent, deliveryKey: String) {
        announcementService.consume(event, deliveryKey)
    }
}
