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

package com.moneat.enterprise

import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import org.koin.core.module.Module

/**
 * Service Provider Interface for feature modules.
 *
 * Implementations register through META-INF/services/com.moneat.enterprise.EnterpriseModule.
 */
interface EnterpriseModule {
    /** Human-readable name of the module (e.g., "SSO", "On-Call"). */
    val name: String

    /**
     * The license feature name required to activate this module (e.g. "sso", "oncall").
     * Null means the module is open-source and always loaded.
     */
    val licenseFeature: String? get() = null

    /** Register module routes into the core routing tree. */
    fun registerRoutes(route: Route)

    /** Register module ingestion routes inside the core ingestion rate-limit bucket. */
    fun registerIngestionRoutes(route: Route) = Unit

    /** Register module-owned Koin bindings during application startup. */
    fun koinModules(): List<Module> = emptyList()

    /** Whether this module needs isolated startup before core infrastructure and routes are configured. */
    fun shouldStartIsolatedBackgroundJobs(application: Application): Boolean = false

    /** Start module background jobs during application startup. */
    fun startBackgroundJobs(application: Application)

    /**
     * Start module background jobs for the active process role. Modules that only have scheduler-like
     * background work should rely on the default; ingestion modules can override this to honor pipeline roles.
     */
    fun startBackgroundJobs(
        application: Application,
        startSchedulers: Boolean,
        startIngestionWorkers: Boolean,
    ) {
        if (startSchedulers) {
            startBackgroundJobs(application)
        }
    }

    /** Stop module background jobs during application shutdown. */
    fun stopBackgroundJobs()
}
