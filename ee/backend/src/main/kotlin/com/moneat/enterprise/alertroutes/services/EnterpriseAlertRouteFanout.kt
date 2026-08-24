// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.alerts.services.AlertFanoutContext
import com.moneat.alerts.services.AlertRouteFanout
import com.moneat.enterprise.FeatureRegistry

/** Enterprise route-evaluation arm. Action execution is added by the downstream execution task. */
class EnterpriseAlertRouteFanout(
    private val executionService: AlertRouteExecutionService = AlertRouteExecutionService(),
    private val enabled: (Int) -> Boolean = FeatureRegistry::isNativeIncidentResponseEntitled,
) : AlertRouteFanout {
    override suspend fun process(context: AlertFanoutContext) {
        if (!enabled(context.event.organizationId)) return
        executionService.execute(context)
    }
}
