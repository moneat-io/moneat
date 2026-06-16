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

package com.moneat.monitoring

import com.moneat.enterprise.EnterpriseModule
import com.moneat.monitor.repositories.HostAlertRepository
import com.moneat.monitor.repositories.HostAlertRepositoryImpl
import com.moneat.monitor.repositories.HostRepository
import com.moneat.monitor.repositories.HostRepositoryImpl
import com.moneat.monitor.repositories.ResourceOwnershipRepository
import com.moneat.monitor.repositories.ResourceOwnershipRepositoryImpl
import com.moneat.monitor.routes.agentApiKeyRoutes
import com.moneat.monitor.routes.cloudSourceRoutes
import com.moneat.monitor.routes.infraRoutes
import com.moneat.monitor.routes.monitorRoutes
import com.moneat.monitor.routes.resourceCatalogRoutes
import com.moneat.monitor.services.AgentApiKeyService
import com.moneat.monitor.services.ClickHouseCloudResourceWriter
import com.moneat.monitor.services.CloudResourceWriter
import com.moneat.monitor.services.CloudSourceService
import com.moneat.monitor.services.CloudSourceVerifier
import com.moneat.monitor.services.ManagedIdentityCloudSourceVerifier
import com.moneat.monitor.services.MonitorAlertService
import com.moneat.monitor.services.MonitorService
import com.moneat.monitor.services.ResourceCatalogService
import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import mu.KotlinLogging
import org.koin.core.module.Module
import org.koin.dsl.module

private val logger = KotlinLogging.logger {}

/**
 * Enterprise monitoring module that contributes monitor and resource APIs.
 */
class MonitoringModule : EnterpriseModule {
    override val name: String = "Monitoring"

    override fun registerRoutes(route: Route) {
        route.apply {
            monitorRoutes()
            resourceCatalogRoutes()
            cloudSourceRoutes()
            infraRoutes()
            agentApiKeyRoutes()
        }
    }

    override fun koinModules(): List<Module> =
        listOf(
            module {
                single<HostRepository> { HostRepositoryImpl() }
                single<HostAlertRepository> { HostAlertRepositoryImpl() }

                single { MonitorService(get(), get(), get(), get()) }
                single { MonitorAlertService(get(), get()) }
                single<ResourceOwnershipRepository> { ResourceOwnershipRepositoryImpl() }
                single { ResourceCatalogService(monitorService = get(), ownershipRepository = get()) }
                single<CloudSourceVerifier> { ManagedIdentityCloudSourceVerifier() }
                single<CloudResourceWriter> { ClickHouseCloudResourceWriter() }
                single { CloudSourceService(get(), get()) }
                single { AgentApiKeyService() }
            }
        )

    override fun startBackgroundJobs(application: Application) {
        logger.info { "Starting monitoring enterprise background jobs" }
    }

    override fun stopBackgroundJobs() {
        logger.info { "Stopping monitoring enterprise background jobs" }
    }
}
