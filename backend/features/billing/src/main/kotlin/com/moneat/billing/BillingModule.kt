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

package com.moneat.billing

import com.moneat.billing.routes.billingRoutes
import com.moneat.billing.routes.publicBillingRoutes
import com.moneat.billing.routes.stripeWebhookRoutes
import com.moneat.enterprise.EnterpriseModule
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

class BillingModule : EnterpriseModule {
    override val name: String = "Billing"

    override fun registerRoutes(route: Route) {
        route.stripeWebhookRoutes()
        route.route("/v1") {
            publicBillingRoutes()
        }
        route.authenticate("auth-jwt") {
            rateLimit(RateLimitName("api")) {
                route("/v1") {
                    billingRoutes()
                }
            }
        }
    }

    override fun startBackgroundJobs(application: Application) = Unit

    override fun stopBackgroundJobs() = Unit
}
