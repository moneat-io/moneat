// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.alerts.services.AlertFanoutContext
import com.moneat.alerts.services.AlertRouteFanout
import com.moneat.alerts.services.AlertRouteExecutionOutcome
import com.moneat.alerts.services.AlertRouteExecutionState
import com.moneat.alerts.services.AlertRouteOutcomeFanout
import com.moneat.enterprise.FeatureRegistry

/** Enterprise route-evaluation arm. Action execution is added by the downstream execution task. */
class EnterpriseAlertRouteFanout(
    private val executionService: AlertRouteExecutionService = AlertRouteExecutionService(),
    private val slackCardService: AlertRouteSlackCardService = AlertRouteSlackCardService(),
    private val enabled: (Int) -> Boolean = FeatureRegistry::isNativeIncidentResponseEntitled,
) : AlertRouteFanout, AlertRouteOutcomeFanout {
    override suspend fun process(context: AlertFanoutContext) {
        processWithOutcome(context)
    }

    override suspend fun processWithOutcome(context: AlertFanoutContext): AlertRouteExecutionOutcome {
        if (!enabled(context.event.organizationId)) {
            return AlertRouteExecutionOutcome(
                state = AlertRouteExecutionState.UNAVAILABLE,
                reason = "Alert Route entitlement is unavailable for this organization",
            )
        }
        val outcome = executionService.executeWithOutcome(context)
        slackCardService.publish(context, outcome)
        return outcome
    }
}
