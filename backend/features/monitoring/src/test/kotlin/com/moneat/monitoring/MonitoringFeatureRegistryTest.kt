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

import com.moneat.alerts.services.AlertSilenceService
import com.moneat.enterprise.EnterpriseModule
import com.moneat.enterprise.FeatureRegistry
import com.moneat.incident.services.IncidentService
import com.moneat.monitor.repositories.HostAlertRepository
import com.moneat.monitor.repositories.HostAlertRepositoryImpl
import com.moneat.monitor.repositories.HostRepository
import com.moneat.monitor.repositories.HostRepositoryImpl
import com.moneat.monitor.repositories.ResourceOwnershipRepository
import com.moneat.monitor.repositories.ResourceOwnershipRepositoryImpl
import com.moneat.monitor.services.AgentApiKeyService
import com.moneat.monitor.services.ClickHouseCloudResourceWriter
import com.moneat.monitor.services.CloudResourceWriter
import com.moneat.monitor.services.CloudSourceService
import com.moneat.monitor.services.CloudSourceVerifier
import com.moneat.monitor.services.ManagedIdentityCloudSourceVerifier
import com.moneat.monitor.services.MonitorAlertService
import com.moneat.monitor.services.MonitorService
import com.moneat.monitor.services.ResourceCatalogService
import com.moneat.monitor.services.ResourceCatalogTeamResolver
import com.moneat.plugins.FeaturesResponse
import com.moneat.plugins.configureSerialization
import com.moneat.billing.services.PricingTierService
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

class MonitoringFeatureRegistryTest {
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
    fun `ServiceLoader discovers Monitoring module from feature resources`() {
        val moduleNames = ServiceLoader.load(EnterpriseModule::class.java)
            .map { module -> module.name }

        assertTrue("Monitoring" in moduleNames)
    }

    @Test
    fun `Monitoring module provides feature Koin bindings`() {
        val koinApplication = KoinApplication.init()
            .modules(
                module {
                    single { mockk<PricingTierService>(relaxed = true) }
                    single { mockk<RetentionPolicyService>(relaxed = true) }
                    single { mockk<IncidentService>(relaxed = true) }
                    single { mockk<WorkflowService>(relaxed = true) }
                    single { mockk<AlertSilenceService>(relaxed = true) }
                    single { mockk<ResourceCatalogTeamResolver>(relaxed = true) }
                },
                *MonitoringModule().koinModules().toTypedArray(),
            )

        try {
            assertIs<HostRepositoryImpl>(koinApplication.koin.get<HostRepository>())
            assertIs<HostAlertRepositoryImpl>(koinApplication.koin.get<HostAlertRepository>())
            assertIs<MonitorService>(koinApplication.koin.get<MonitorService>())
            assertIs<MonitorAlertService>(koinApplication.koin.get<MonitorAlertService>())
            assertIs<ResourceOwnershipRepositoryImpl>(koinApplication.koin.get<ResourceOwnershipRepository>())
            assertIs<ResourceCatalogService>(koinApplication.koin.get<ResourceCatalogService>())
            assertIs<ManagedIdentityCloudSourceVerifier>(koinApplication.koin.get<CloudSourceVerifier>())
            assertIs<ClickHouseCloudResourceWriter>(koinApplication.koin.get<CloudResourceWriter>())
            assertIs<CloudSourceService>(koinApplication.koin.get<CloudSourceService>())
            assertIs<AgentApiKeyService>(koinApplication.koin.get<AgentApiKeyService>())
        } finally {
            koinApplication.close()
        }
    }

    @Test
    fun `features response includes Monitoring when runtime module is present`() = testApplication {
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
        assertTrue("Monitoring" in response.modules)
    }

    @Test
    fun `Monitoring module owns alert scheduler lifecycle`() {
        val application = mockk<Application>(relaxed = true)
        val monitorAlertService = mockk<MonitorAlertService>(relaxUnitFun = true)
        startKoin {
            modules(
                module {
                    single { monitorAlertService }
                }
            )
        }

        try {
            val monitoringModule = MonitoringModule()

            monitoringModule.startBackgroundJobs(
                application,
                startSchedulers = false,
                startIngestionWorkers = true,
            )
            verify(exactly = 0) { monitorAlertService.start(any()) }

            monitoringModule.startBackgroundJobs(
                application,
                startSchedulers = true,
                startIngestionWorkers = false,
            )
            verify(exactly = 1) { monitorAlertService.start(any()) }

            monitoringModule.stopBackgroundJobs()
            verify(exactly = 1) { monitorAlertService.stop() }
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
