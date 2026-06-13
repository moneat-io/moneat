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

package com.moneat.summary

import com.moneat.enterprise.EnterpriseModule
import com.moneat.events.services.DashboardService
import com.moneat.events.services.ReleaseService
import com.moneat.monitor.services.MonitorAlertService
import com.moneat.monitor.services.MonitorService
import com.moneat.summary.routes.summaryRoutes
import com.moneat.summary.services.SummaryService
import com.moneat.uptime.services.UptimeService
import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import org.koin.core.context.GlobalContext

class SummaryModule : EnterpriseModule {
    override val name: String = "Summary"

    private val summaryService by lazy {
        val koin = GlobalContext.get()
        SummaryService(
            monitorService = koin.get<MonitorService>(),
            uptimeService = koin.get<UptimeService>(),
            alertService = koin.get<MonitorAlertService>(),
            dashboardService = koin.get<DashboardService>(),
            releaseService = koin.get<ReleaseService>(),
        )
    }

    override fun registerRoutes(route: Route) {
        route.summaryRoutes(summaryService)
    }

    override fun startBackgroundJobs(application: Application) {
        // Summary has no background jobs.
    }

    override fun stopBackgroundJobs() {
        // No-op.
    }
}
