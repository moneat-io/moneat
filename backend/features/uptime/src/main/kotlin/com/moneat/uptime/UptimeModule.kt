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

package com.moneat.uptime

import com.moneat.alerts.services.AlertLifecycleOrchestrator
import com.moneat.billing.services.BillingQuotaService
import com.moneat.enterprise.EnterpriseModule
import com.moneat.incident.services.IncidentService
import com.moneat.uptime.repositories.UptimeMonitorRepository
import com.moneat.uptime.repositories.UptimeMonitorRepositoryImpl
import com.moneat.uptime.routes.uptimeRoutes
import com.moneat.uptime.services.UptimeCheckExecutor
import com.moneat.uptime.services.UptimeScheduler
import com.moneat.uptime.services.UptimeService
import com.moneat.workflows.services.WorkflowService
import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.dsl.module
import java.util.concurrent.atomic.AtomicReference

class UptimeModule : EnterpriseModule {
    override val name: String = "Uptime"
    private val uptimeScheduler = AtomicReference<UptimeScheduler?>(null)
    private val lifecycleLock = Any()

    override fun registerRoutes(route: Route) {
        route.uptimeRoutes()
    }

    override fun koinModules(): List<Module> =
        listOf(
            module {
                single<UptimeMonitorRepository> { UptimeMonitorRepositoryImpl() }
                single { UptimeService(get(), get()) }
                single { UptimeCheckExecutor() }
            }
        )

    override fun startBackgroundJobs(application: Application) {
        synchronized(lifecycleLock) {
            if (uptimeScheduler.get() != null) return

            val koin = GlobalContext.get()
            val scheduler = UptimeScheduler(
                uptimeService = koin.get<UptimeService>(),
                checkExecutor = koin.get<UptimeCheckExecutor>(),
                incidentService = koin.get<IncidentService>(),
                billingQuotaService = koin.get<BillingQuotaService>(),
                frontendBaseUrl = application.frontendBaseUrl(),
                alertOrchestrator = koin.getOrNull<AlertLifecycleOrchestrator>() ?: AlertLifecycleOrchestrator(
                    workflowFanoutProvider = { koin.get<WorkflowService>() },
                    incidentFanoutProvider = { koin.get<IncidentService>() },
                ),
            )
            uptimeScheduler.set(scheduler)
            scheduler.start()
        }
    }

    override fun stopBackgroundJobs() {
        synchronized(lifecycleLock) {
            uptimeScheduler.getAndSet(null)?.stop()
        }
    }

    private fun Application.frontendBaseUrl(): String =
        environment.config
            .propertyOrNull(FRONTEND_BASE_URL_CONFIG)
            ?.getString()
            ?: DEFAULT_FRONTEND_BASE_URL

    private companion object {
        private const val FRONTEND_BASE_URL_CONFIG = "email.frontendUrl"
        private const val DEFAULT_FRONTEND_BASE_URL = "https://moneat.io"
    }
}
