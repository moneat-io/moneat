// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.alerts.models.AlertPriority
import com.moneat.alerts.models.AlertSource
import com.moneat.alerts.models.AlertStatus
import com.moneat.alerts.services.AlertFanoutContext
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class EnterpriseAlertRouteFanoutTest {
    @Test
    fun `live fanout skips route evaluation when organization is not entitled`() = runBlocking {
        val executionService = mockk<AlertRouteExecutionService>()
        val fanout = EnterpriseAlertRouteFanout(
            executionService = executionService,
            enabled = { false },
        )

        fanout.process(
            AlertFanoutContext(
                event = AlertLifecycleEvent(
                    title = "Error rate elevated",
                    description = "Error rate exceeded its threshold",
                    priority = AlertPriority.P2,
                    status = AlertStatus.FIRING,
                    source = AlertSource.DASHBOARD_ALERT,
                    deduplicationKey = "dashboard-alert:error-rate",
                    organizationId = 42,
                    moneatUrl = "https://moneat.example/alerts/error-rate",
                ),
                episodeDecision = null,
                episode = null,
                deliverySilenced = false,
            ),
        )

        confirmVerified(executionService)
    }
}
