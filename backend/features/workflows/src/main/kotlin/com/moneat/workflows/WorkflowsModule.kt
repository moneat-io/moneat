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

package com.moneat.workflows

import com.moneat.enterprise.EnterpriseModule
import com.moneat.monitor.services.MonitorAlertService
import com.moneat.workflows.engine.temporal.ExecuteActionActivityImpl
import com.moneat.workflows.engine.temporal.ExecuteEgressActionActivityImpl
import com.moneat.workflows.engine.temporal.PersistRunActivityImpl
import com.moneat.workflows.engine.temporal.RequestApprovalActivityImpl
import com.moneat.workflows.engine.temporal.TemporalClientProvider
import com.moneat.workflows.engine.temporal.TemporalWorkflowExecutionEngine
import com.moneat.workflows.engine.temporal.WorkflowExecutionEngine
import com.moneat.workflows.routes.workflowRoutes
import com.moneat.workflows.services.WorkflowActionExecutor
import com.moneat.workflows.services.WorkflowEgressActionExecutor
import com.moneat.workflows.services.WorkflowGovernanceService
import com.moneat.workflows.services.WorkflowService
import com.moneat.workflows.services.WorkflowStepRenderer
import com.moneat.workflows.services.WorkflowTrustedActionExecutor
import io.ktor.server.application.Application
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.routing.Route
import org.koin.core.module.Module
import org.koin.dsl.module

class WorkflowsModule : EnterpriseModule {
    private val backgroundWorkers = WorkflowBackgroundWorkers()

    override val name: String = "Workflows"

    override fun registerRoutes(route: Route) {
        route.rateLimit(RateLimitName("api")) {
            workflowRoutes()
        }
    }

    override fun koinModules(): List<Module> =
        listOf(
            module {
                single { WorkflowStepRenderer() }
                single {
                    WorkflowTrustedActionExecutor(
                        logService = get(),
                        dashboardService = get(),
                        monitorService = get(),
                        monitorAlertServiceProvider = { get<MonitorAlertService>() },
                        statusPageService = get(),
                    )
                }
                single { WorkflowActionExecutor(get(), get(), get(), get(), get()) }
                single { WorkflowEgressActionExecutor() }
                single { PersistRunActivityImpl() }
                single { RequestApprovalActivityImpl() }
                single { ExecuteActionActivityImpl(get()) }
                single { ExecuteEgressActionActivityImpl(get()) }
                single { TemporalClientProvider() }
                single<WorkflowExecutionEngine> { TemporalWorkflowExecutionEngine(get()) }
                single { WorkflowService(get(), get(), get(), get(), get(), get(), get(), get()) }
                single { WorkflowGovernanceService(get()) }
            }
        )

    override fun startBackgroundJobs(application: Application) {
        backgroundWorkers.start(application, startSchedulers = true)
    }

    override fun startBackgroundJobs(
        application: Application,
        startSchedulers: Boolean,
        startIngestionWorkers: Boolean,
    ) {
        if (!startSchedulers && startIngestionWorkers) return
        backgroundWorkers.start(application, startSchedulers)
    }

    override fun stopBackgroundJobs() {
        backgroundWorkers.stop()
    }
}
