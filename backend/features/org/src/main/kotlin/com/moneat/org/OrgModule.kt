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

package com.moneat.org

import com.moneat.enterprise.EnterpriseModule
import com.moneat.org.routes.adminRoutes
import com.moneat.org.routes.orgManagementRoutes
import io.ktor.server.application.Application
import io.ktor.server.routing.Route

class OrgModule : EnterpriseModule {
    override val name: String = "Organization"

    override fun registerRoutes(route: Route) {
        route.adminRoutes()
        route.orgManagementRoutes()
    }

    override fun startBackgroundJobs(application: Application) = Unit

    override fun stopBackgroundJobs() = Unit
}
