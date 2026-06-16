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
import com.moneat.enterprise.FeatureRegistry
import com.moneat.alerts.services.AlertEpisodeService
import com.moneat.events.services.DashboardService
import com.moneat.logs.services.LogService
import com.moneat.monitor.services.MonitorAlertService
import com.moneat.monitor.services.MonitorService
import com.moneat.notifications.services.DiscordService
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.SlackService
import com.moneat.plugins.FeaturesResponse
import com.moneat.plugins.configureSerialization
import com.moneat.statuspage.services.StatusPageService
import com.moneat.workflows.engine.temporal.ExecuteActionActivityImpl
import com.moneat.workflows.engine.temporal.ExecuteEgressActionActivityImpl
import com.moneat.workflows.engine.temporal.PersistRunActivityImpl
import com.moneat.workflows.engine.temporal.RequestApprovalActivityImpl
import com.moneat.workflows.engine.temporal.TemporalClientProvider
import com.moneat.workflows.engine.temporal.WorkflowExecutionEngine
import com.moneat.workflows.services.WorkflowActionExecutor
import com.moneat.workflows.services.WorkflowEgressActionExecutor
import com.moneat.workflows.services.WorkflowGovernanceService
import com.moneat.workflows.services.WorkflowService
import com.moneat.workflows.services.WorkflowStepRenderer
import com.moneat.workflows.services.WorkflowTrustedActionExecutor
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import org.koin.core.KoinApplication
import org.koin.dsl.module
import java.util.ServiceLoader
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkflowsFeatureRegistryTest {
    @BeforeTest
    fun resetBefore() {
        FeatureRegistry.resetForTest()
    }

    @AfterTest
    fun resetAfter() {
        FeatureRegistry.resetForTest()
    }

    @Test
    fun `ServiceLoader discovers Workflows module from feature resources`() {
        val moduleNames = ServiceLoader.load(EnterpriseModule::class.java)
            .map { module -> module.name }

        assertTrue("Workflows" in moduleNames)
    }

    @Test
    fun `Workflows module provides feature Koin bindings`() {
        val koinApplication = KoinApplication.init()
            .modules(
                module {
                    single { mockk<EmailService>(relaxed = true) }
                    single { mockk<SlackService>(relaxed = true) }
                    single { mockk<DiscordService>(relaxed = true) }
                    single { mockk<AlertEpisodeService>(relaxed = true) }
                    single { mockk<LogService>(relaxed = true) }
                    single { mockk<DashboardService>(relaxed = true) }
                    single { mockk<MonitorService>(relaxed = true) }
                    single { mockk<MonitorAlertService>(relaxed = true) }
                    single { mockk<StatusPageService>(relaxed = true) }
                },
                *WorkflowsModule().koinModules().toTypedArray(),
            )

        try {
            assertIs<WorkflowStepRenderer>(koinApplication.koin.get<WorkflowStepRenderer>())
            assertIs<WorkflowTrustedActionExecutor>(koinApplication.koin.get<WorkflowTrustedActionExecutor>())
            assertIs<WorkflowActionExecutor>(koinApplication.koin.get<WorkflowActionExecutor>())
            assertIs<WorkflowEgressActionExecutor>(koinApplication.koin.get<WorkflowEgressActionExecutor>())
            assertIs<PersistRunActivityImpl>(koinApplication.koin.get<PersistRunActivityImpl>())
            assertIs<RequestApprovalActivityImpl>(koinApplication.koin.get<RequestApprovalActivityImpl>())
            assertIs<ExecuteActionActivityImpl>(koinApplication.koin.get<ExecuteActionActivityImpl>())
            assertIs<ExecuteEgressActionActivityImpl>(koinApplication.koin.get<ExecuteEgressActionActivityImpl>())
            assertIs<TemporalClientProvider>(koinApplication.koin.get<TemporalClientProvider>())
            assertIs<WorkflowExecutionEngine>(koinApplication.koin.get<WorkflowExecutionEngine>())
            assertIs<WorkflowService>(koinApplication.koin.get<WorkflowService>())
            assertIs<WorkflowGovernanceService>(koinApplication.koin.get<WorkflowGovernanceService>())
        } finally {
            koinApplication.close()
        }
    }

    @Test
    fun `features response includes Workflows when runtime module is present`() = testApplication {
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
        assertTrue("Workflows" in response.modules)
    }

    @Test
    fun `Workflows module skips Temporal workers for ingestion worker role`() = testApplication {
        application {
            val workflowsModule = WorkflowsModule()

            workflowsModule.startBackgroundJobs(this, startSchedulers = false, startIngestionWorkers = true)
            workflowsModule.stopBackgroundJobs()
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
}
