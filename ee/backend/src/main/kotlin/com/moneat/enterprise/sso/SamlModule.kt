// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.sso

import com.moneat.enterprise.EnterpriseModule
import com.moneat.enterprise.sso.routes.samlRoutes
import io.ktor.server.application.Application
import io.ktor.server.routing.Route

/**
 * Enterprise module for SAML 2.0 single sign-on and SSO enforcement.
 * Requires the "sso" license feature to activate.
 *
 * OIDC SSO is provided by the core SsoModule (no license required).
 */
class SamlModule : EnterpriseModule {
    override val name: String = "SAML"
    override val licenseFeature: String = "sso"

    override fun registerRoutes(route: Route) {
        route.samlRoutes()
    }

    override fun startBackgroundJobs(application: Application) {
        // SAML has no background jobs
    }

    override fun stopBackgroundJobs() {
        // No-op
    }
}
