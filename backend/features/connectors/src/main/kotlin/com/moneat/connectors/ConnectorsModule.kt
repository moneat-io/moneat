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

package com.moneat.connectors

import com.moneat.connectors.routes.connectorWebhookRoutes
import com.moneat.connectors.routes.connectorRoutes
import com.moneat.enterprise.EnterpriseModule
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueSettings
import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.dsl.module

class ConnectorsModule : EnterpriseModule {
    override val name: String = "Connectors"
    private var connectorEventWorker: ConnectorEventWorker? = null

    override fun registerRoutes(route: Route) {
        route.connectorRoutes()
    }

    override fun registerIngestionRoutes(route: Route) {
        route.connectorWebhookRoutes()
    }

    override fun koinModules(): List<Module> =
        listOf(
            module {
                single<RevenueCatProviderClient> { RevenueCatClient() }
                single { ConnectorService(projectIdResolver = get(), revenueCatClient = get()) }
                single { ConnectorEventWorker(connectorService = get()) }
            }
        )

    override fun startBackgroundJobs(application: Application) = Unit

    override fun startBackgroundJobs(
        application: Application,
        startSchedulers: Boolean,
        startIngestionWorkers: Boolean,
    ) {
        if (!startIngestionWorkers || !IngestionQueueSettings.isSelected(IngestionPipeline.CONNECTOR_EVENTS)) {
            return
        }
        connectorEventWorker = GlobalContext.get().get<ConnectorEventWorker>().also { worker -> worker.start() }
    }

    override fun stopBackgroundJobs() {
        connectorEventWorker?.stop()
        connectorEventWorker = null
    }
}
