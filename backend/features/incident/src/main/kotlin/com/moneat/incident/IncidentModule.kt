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

package com.moneat.incident

import com.moneat.enterprise.EnterpriseModule
import com.moneat.incident.routes.incidentProviderRoutes
import com.moneat.incident.services.IncidentService
import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import org.koin.core.module.Module
import org.koin.dsl.module

class IncidentModule : EnterpriseModule {
    override val name: String = "Incident Providers"

    override fun registerRoutes(route: Route) {
        route.incidentProviderRoutes()
    }

    override fun koinModules(): List<Module> =
        listOf(
            module {
                single { IncidentService(get()) }
            }
        )

    override fun startBackgroundJobs(application: Application) = Unit

    override fun stopBackgroundJobs() = Unit
}
