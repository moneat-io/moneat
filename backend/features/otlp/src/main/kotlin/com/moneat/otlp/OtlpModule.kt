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

package com.moneat.otlp

import com.moneat.enterprise.EnterpriseModule
import com.moneat.otlp.routes.otlpFeedbackRoutes
import com.moneat.otlp.routes.otlpMetricsRoutes
import com.moneat.otlp.routes.otlpTraceRoutes
import io.ktor.server.application.Application
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.routing.Route

class OtlpModule : EnterpriseModule {
    override val name: String = "OTLP"

    override fun registerRoutes(route: Route) {
        route.rateLimit(RateLimitName("otlp-ingestion")) {
            otlpTraceRoutes()
            otlpMetricsRoutes()
            otlpFeedbackRoutes()
        }
    }

    override fun startBackgroundJobs(application: Application) = Unit

    override fun stopBackgroundJobs() = Unit
}
