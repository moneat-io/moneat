// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.sso

import com.moneat.enterprise.EnterpriseModule
import com.moneat.enterprise.sso.routes.ssoRoutes
import io.ktor.server.application.Application
import io.ktor.server.routing.Route

/**
 * Enterprise module for SSO/SAML/OIDC authentication.
 */
class SsoModule : EnterpriseModule {
    override val name: String = "SSO"
    override val licenseFeature: String = "sso"

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
