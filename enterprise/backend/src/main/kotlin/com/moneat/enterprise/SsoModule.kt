// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise

import com.moneat.enterprise.routes.ssoRoutes
import io.ktor.server.application.Application
import io.ktor.server.routing.Route

/**
 * Enterprise module for SSO/SAML/OIDC authentication.
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
