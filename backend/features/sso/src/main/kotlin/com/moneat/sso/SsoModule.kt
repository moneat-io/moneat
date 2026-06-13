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

package com.moneat.sso

import com.moneat.enterprise.EnterpriseModule
import com.moneat.sso.routes.ssoRoutes
import io.ktor.server.application.Application
import io.ktor.server.routing.Route

/**
 * Core SSO module providing OIDC single sign-on for all deployments.
 * No license required (licenseFeature = null, always loaded).
 *
 * SAML 2.0 and SSO enforcement ("Require SSO") require the enterprise
 * SamlModule (licenseFeature = "sso").
 */
class SsoModule : EnterpriseModule {
    override val name: String = "SSO"

    override fun registerRoutes(route: Route) {
        route.ssoRoutes()
    }

    override fun startBackgroundJobs(application: Application) {
        // SSO has no background jobs
    }

    override fun stopBackgroundJobs() {
        // No-op
    }
}
