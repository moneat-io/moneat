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
import com.moneat.dashboards.services.CustomDashboardService
import com.moneat.dashboards.services.CustomDataSourceExecutor
import com.moneat.dashboards.services.CustomDataSourceService
import com.moneat.dashboards.services.DashboardAlertService
import com.moneat.dashboards.services.DashboardQueryEngine
import com.moneat.dashboards.services.DashboardTemplateCatalogService
import com.moneat.enterprise.EnterpriseModule
import com.moneat.enterprise.FeatureRegistry
import com.moneat.incident.services.IncidentService
import com.moneat.plugins.FeaturesResponse
import com.moneat.plugins.configureSerialization
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.workflows.services.WorkflowService
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import io.mockk.verify
import org.koin.core.KoinApplication
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.ServiceLoader
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DashboardFeatureRegistryTest {
    @BeforeTest
    fun resetBefore() {
        stopKoinIfStarted()
        FeatureRegistry.resetForTest()
    }

    @AfterTest
    fun resetAfter() {
        FeatureRegistry.resetForTest()
        stopKoinIfStarted()
    }

    @Test
    fun `ServiceLoader discovers Dashboards module from feature resources`() {
        val moduleNames = ServiceLoader.load(EnterpriseModule::class.java)
            .map { module -> module.name }

        assertTrue("Dashboards" in moduleNames)
    }

    @Test
    fun `Dashboards module provides feature Koin bindings`() {
        val koinApplication = KoinApplication.init()
            .modules(
                module {
                    single { mockk<IncidentService>(relaxed = true) }
                    single { mockk<WorkflowService>(relaxed = true) }
                    single { mockk<RetentionPolicyService>(relaxed = true) }
                },
                *DashboardModule().koinModules().toTypedArray(),
            )

        try {
            assertIs<DashboardFolderRepositoryImpl>(koinApplication.koin.get<DashboardFolderRepository>())
            assertIs<DashboardRepositoryImpl>(koinApplication.koin.get<DashboardRepository>())
            assertIs<DashboardWidgetRepositoryImpl>(koinApplication.koin.get<DashboardWidgetRepository>())
            assertIs<DashboardQueryEngine>(koinApplication.koin.get<DashboardQueryEngine>())
            assertIs<DashboardTemplateCatalogService>(koinApplication.koin.get<DashboardTemplateCatalogService>())
            assertIs<DashboardAlertService>(koinApplication.koin.get<DashboardAlertService>())
            assertIs<CustomDashboardService>(koinApplication.koin.get<CustomDashboardService>())
            assertIs<CustomDataSourceService>(koinApplication.koin.get<CustomDataSourceService>())
            assertIs<CustomDataSourceExecutor>(koinApplication.koin.get<CustomDataSourceExecutor>())
        } finally {
            koinApplication.close()
        }
    }

    @Test
    fun `features response includes Dashboards when runtime module is present`() = testApplication {
        application {
            configureSerialization()
            FeatureRegistry.initialize()
            routing {
                get("/features") {
                    call.respondFeatures()
                }
            }
        }

        val jsonClient = createClient {
            install(ContentNegotiation) { json() }
        }
        val response = jsonClient.get("/features").body<FeaturesResponse>()

        assertTrue(response.enterprise)
        assertTrue("Dashboards" in response.modules)
    }

    @Test
    fun `Dashboards module owns alert scheduler lifecycle`() {
        val application = mockk<Application>(relaxed = true)
        val dashboardAlertService = mockk<DashboardAlertService>(relaxUnitFun = true)
        startKoin {
            modules(
                module {
                    single { dashboardAlertService }
                }
            )
        }

        try {
            val dashboardModule = DashboardModule()

            dashboardModule.startBackgroundJobs(
                application,
                startSchedulers = false,
                startIngestionWorkers = true,
            )
            verify(exactly = 0) { dashboardAlertService.start(any()) }

            dashboardModule.startBackgroundJobs(
                application,
                startSchedulers = true,
                startIngestionWorkers = false,
            )
            verify(exactly = 1) { dashboardAlertService.start(any()) }

            dashboardModule.stopBackgroundJobs()
            verify(exactly = 1) { dashboardAlertService.stop() }
        } finally {
            stopKoinIfStarted()
        }
    }

    private suspend fun ApplicationCall.respondFeatures() {
        respond(
            FeaturesResponse(
                enterprise = FeatureRegistry.isEnterpriseAvailable,
                modules = FeatureRegistry.registeredModules.map { module -> module.name },
                selfHost = false,
            )
        )
    }

    private fun stopKoinIfStarted() {
        if (GlobalContext.getOrNull() != null) {
            stopKoin()
        }
    }
}
