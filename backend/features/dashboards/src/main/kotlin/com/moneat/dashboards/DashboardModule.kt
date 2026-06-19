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

package com.moneat.dashboards

import com.moneat.dashboards.repositories.DashboardFolderRepository
import com.moneat.dashboards.repositories.DashboardFolderRepositoryImpl
import com.moneat.dashboards.repositories.DashboardRepository
import com.moneat.dashboards.repositories.DashboardRepositoryImpl
import com.moneat.dashboards.repositories.DashboardWidgetRepository
import com.moneat.dashboards.repositories.DashboardWidgetRepositoryImpl
import com.moneat.dashboards.routes.customDashboardRoutes
import com.moneat.dashboards.services.CustomDashboardService
import com.moneat.dashboards.services.CustomDataSourceExecutor
import com.moneat.dashboards.services.CustomDataSourceService
import com.moneat.dashboards.services.DashboardAlertService
import com.moneat.dashboards.services.DashboardQueryEngine
import com.moneat.dashboards.services.DashboardTemplateCatalogService
import com.moneat.dashboards.services.DashboardVariableResolver
import com.moneat.enterprise.EnterpriseModule
import com.moneat.events.repositories.ProjectRepositoryImpl
import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mu.KotlinLogging
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.dsl.module

private val logger = KotlinLogging.logger {}

class DashboardModule : EnterpriseModule {
    private var backgroundJobs: DashboardBackgroundJobs? = null

    override val name: String = "Dashboards"

    override fun registerRoutes(route: Route) {
        route.customDashboardRoutes()
    }

    override fun koinModules(): List<Module> =
        listOf(
            module {
                single<DashboardFolderRepository> { DashboardFolderRepositoryImpl() }
                single<DashboardRepository> { DashboardRepositoryImpl() }
                single<DashboardWidgetRepository> { DashboardWidgetRepositoryImpl() }

                single { DashboardQueryEngine() }
                single { DashboardTemplateCatalogService() }
                single {
                    DashboardAlertService(
                        incidentService = get(),
                        workflowService = get(),
                        queryEngine = get(),
                        retentionPolicyService = get(),
                        dataSourceService = get(),
                        dataSourceExecutor = get(),
                    )
                }
                single {
                    CustomDashboardService(
                        folderRepository = get(),
                        dashboardRepository = get(),
                        dashboardWidgetRepository = get(),
                        projectRepository = ProjectRepositoryImpl { col, _, _ -> col },
                    )
                }
                single { CustomDataSourceService() }
                single { CustomDataSourceExecutor() }
                single { DashboardVariableResolver(get(), get()) }
            }
        )

    override fun startBackgroundJobs(application: Application) {
        if (backgroundJobs != null) return
        logger.info { "Starting dashboard background jobs" }
        backgroundJobs = DashboardBackgroundJobs(GlobalContext.get().get<DashboardAlertService>())
            .also { it.start() }
    }

    override fun stopBackgroundJobs() {
        logger.info { "Stopping dashboard background jobs" }
        backgroundJobs?.stop()
        backgroundJobs = null
    }
}

private class DashboardBackgroundJobs(
    private val dashboardAlertService: DashboardAlertService,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun start() {
        dashboardAlertService.start(scope)
    }

    fun stop() {
        dashboardAlertService.stop()
        scope.cancel()
    }
}
