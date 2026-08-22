// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package com.moneat.featureflags

import com.moneat.enterprise.EnterpriseModule
import com.moneat.enterprise.NativeIncidentRolloutBridge
import com.moneat.featureflags.routes.featureFlagRoutes
import com.moneat.featureflags.services.FeatureFlagEvaluator
import com.moneat.featureflags.services.FeatureFlagEventService
import com.moneat.featureflags.services.FeatureFlagNativeIncidentRolloutBridge
import com.moneat.featureflags.services.FeatureFlagService
import io.ktor.server.application.Application
import io.ktor.server.routing.Route

class FeatureFlagsModule :
    EnterpriseModule,
    NativeIncidentRolloutBridge {
    override val name: String = "Feature Flags"

    private val featureFlagService = FeatureFlagService()
    private val evaluator = FeatureFlagEvaluator()
    private val eventService = FeatureFlagEventService()
    private val nativeIncidentRolloutBridge = FeatureFlagNativeIncidentRolloutBridge(featureFlagService, evaluator)

    override fun registerRoutes(route: Route) {
        route.featureFlagRoutes(
            featureFlagService = featureFlagService,
            evaluator = evaluator,
            eventService = eventService,
        )
    }

    override fun startBackgroundJobs(application: Application) = Unit

    override fun stopBackgroundJobs() = Unit

    override fun status(organizationId: Int, environment: String) =
        nativeIncidentRolloutBridge.status(organizationId, environment)
}
