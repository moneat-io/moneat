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

package com.moneat.datadog

import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.datadog.networkdevices.NdmIngestionWorker
import com.moneat.datadog.security.SecurityIngestionWorker
import com.moneat.datadog.services.ProfileStorageService
import com.moneat.billing.services.BillingQuotaService
import com.moneat.datadog.workers.DatadogEventIngestionWorker
import com.moneat.datadog.workers.DatadogInfraIngestionWorker
import com.moneat.datadog.workers.DatadogMetricIngestionWorker
import com.moneat.datadog.workers.DbmIngestionWorker
import com.moneat.datadog.workers.DebuggerIngestionWorker
import com.moneat.datadog.workers.MiscIngestionWorker
import com.moneat.datadog.workers.OrchestratorIngestionWorker
import com.moneat.datadog.workers.TraceIngestionWorker
import com.moneat.runtime.RuntimeMode
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import org.koin.core.Koin
import org.koin.core.context.GlobalContext

class DatadogModuleTest {
    @AfterTest
    fun cleanup() {
        unmockkAll()
    }

    @Test
    fun `registerRoutes wires datadog module routes`() = testApplication {
        val mockKoin = mockk<Koin>(relaxed = true)
        val mockQuotaService = mockk<BillingQuotaService>(relaxed = true)
        mockkObject(GlobalContext)
        every { GlobalContext.get() } returns mockKoin
        every { mockKoin.get<BillingQuotaService>() } returns mockQuotaService

        application {
            installJwtAuth()
            installDatadogRateLimits()
            routing {
                DatadogModule().registerRoutes(this)
            }
        }

        val validateResponse = client.get("/api/v1/validate")
        assertEquals(HttpStatusCode.Forbidden, validateResponse.status)

        val ddValidateResponse = client.get("/dd/api/v1/validate/")
        assertEquals(HttpStatusCode.Forbidden, ddValidateResponse.status)
    }

    @Test
    fun `resolveRateLimitKey uses datadog api key extractor`() {
        mockkObject(DatadogAuthMiddleware)
        every { DatadogAuthMiddleware.extractApiKey(any()) } returns "abc"
        every { DatadogAuthMiddleware.resolveOrgId("abc") } returns 77

        val module = DatadogModule()
        val call = mockk<ApplicationCall>()

        val key = module.resolveRateLimitKey(call)
        assertEquals("org:77", key)
    }

    @Test
    fun `resolveRateLimitKey returns null when api key missing`() {
        mockkObject(DatadogAuthMiddleware)
        every { DatadogAuthMiddleware.extractApiKey(any()) } returns null

        val module = DatadogModule()
        val call = mockk<ApplicationCall>()

        val key = module.resolveRateLimitKey(call)
        assertNull(key)
    }

    @Test
    fun `resolveRateLimitKey exposes configured rate limit name`() {
        val module = DatadogModule()
        assertEquals("datadog-ingestion", module.rateLimitName)
    }

    @Test
    fun `startBackgroundJobs starts all ingestion workers when enabled`() {
        withMockedRuntimeAndWorkers {
            val module = DatadogModule()
            val application = mockk<Application>(relaxed = true)

            module.startBackgroundJobs(
                application,
                startSchedulers = false,
                startIngestionWorkers = true,
            )

            verify(exactly = 1) {
                ProfileStorageService.init()
            }
            verify(exactly = 1) { anyConstructed<TraceIngestionWorker>().start() }
            verify(exactly = 1) { anyConstructed<DatadogMetricIngestionWorker>().start() }
            verify(exactly = 1) { anyConstructed<DatadogEventIngestionWorker>().start() }
            verify(exactly = 1) { anyConstructed<DatadogInfraIngestionWorker>().start() }
            verify(exactly = 1) { anyConstructed<MiscIngestionWorker>().start() }
            verify(exactly = 1) { anyConstructed<OrchestratorIngestionWorker>().start() }
            verify(exactly = 1) { anyConstructed<DbmIngestionWorker>().start() }
            verify(exactly = 1) { anyConstructed<DebuggerIngestionWorker>().start() }
            verify(exactly = 1) { anyConstructed<NdmIngestionWorker>().start() }
            verify(exactly = 1) { anyConstructed<SecurityIngestionWorker>().start() }
        }
    }

    @Test
    fun `startBackgroundJobs default overload starts all ingestion workers when enabled`() {
        withMockedRuntimeAndWorkers {
            val module = DatadogModule()
            val application = mockk<Application>(relaxed = true)

            module.startBackgroundJobs(application)

            verify(exactly = 1) { anyConstructed<TraceIngestionWorker>().start() }
            verify(exactly = 1) { anyConstructed<DatadogMetricIngestionWorker>().start() }
            verify(exactly = 1) { anyConstructed<DatadogEventIngestionWorker>().start() }
            verify(exactly = 1) { anyConstructed<DatadogInfraIngestionWorker>().start() }
            verify(exactly = 1) { anyConstructed<OrchestratorIngestionWorker>().start() }
            verify(exactly = 1) { anyConstructed<DbmIngestionWorker>().start() }
            verify(exactly = 1) { anyConstructed<DebuggerIngestionWorker>().start() }
            verify(exactly = 1) { anyConstructed<NdmIngestionWorker>().start() }
            verify(exactly = 1) { anyConstructed<SecurityIngestionWorker>().start() }
        }
    }

    @Test
    fun `stopBackgroundJobs is safe with no initialized workers`() {
        val module = DatadogModule()
        module.stopBackgroundJobs()
    }

    @Test
    fun `startBackgroundJobs skips ingestion when disabled`() {
        val module = DatadogModule()
        val application = mockk<Application>(relaxed = true)

        module.startBackgroundJobs(
            application,
            startSchedulers = true,
            startIngestionWorkers = false,
        )

        assertEquals("Datadog", module.name)
    }

    @Test
    fun `stopBackgroundJobs stops initialized workers`() {
        withMockedRuntimeAndWorkers {
            val module = DatadogModule()
            val application = mockk<Application>(relaxed = true)

            module.startBackgroundJobs(
                application,
                startSchedulers = false,
                startIngestionWorkers = true,
            )
            module.stopBackgroundJobs()

            verify(exactly = 1) { anyConstructed<TraceIngestionWorker>().stop() }
            verify(exactly = 1) { anyConstructed<DatadogMetricIngestionWorker>().stop() }
            verify(exactly = 1) { anyConstructed<DatadogEventIngestionWorker>().stop() }
            verify(exactly = 1) { anyConstructed<DatadogInfraIngestionWorker>().stop() }
            verify(exactly = 1) { anyConstructed<MiscIngestionWorker>().stop() }
            verify(exactly = 1) { anyConstructed<OrchestratorIngestionWorker>().stop() }
            verify(exactly = 1) { anyConstructed<DbmIngestionWorker>().stop() }
            verify(exactly = 1) { anyConstructed<DebuggerIngestionWorker>().stop() }
            verify(exactly = 1) { anyConstructed<NdmIngestionWorker>().stop() }
            verify(exactly = 1) { anyConstructed<SecurityIngestionWorker>().stop() }
        }
    }

    private fun withMockedRuntimeAndWorkers(action: () -> Unit) {
        mockkObject(RuntimeMode)
        every { RuntimeMode.shouldStartPipeline(any()) } returns true

        mockkObject(ProfileStorageService)
        every { ProfileStorageService.init() } answers {}

        mockkConstructor(
            TraceIngestionWorker::class,
            OrchestratorIngestionWorker::class,
            DbmIngestionWorker::class,
            DebuggerIngestionWorker::class,
            DatadogMetricIngestionWorker::class,
            DatadogEventIngestionWorker::class,
            DatadogInfraIngestionWorker::class,
            MiscIngestionWorker::class,
            NdmIngestionWorker::class,
            SecurityIngestionWorker::class,
        )

        every {
            anyConstructed<TraceIngestionWorker>().start()
        } answers {}
        every {
            anyConstructed<OrchestratorIngestionWorker>().start()
        } answers {}
        every {
            anyConstructed<DbmIngestionWorker>().start()
        } answers {}
        every {
            anyConstructed<DebuggerIngestionWorker>().start()
        } answers {}
        every {
            anyConstructed<DatadogMetricIngestionWorker>().start()
        } answers {}
        every {
            anyConstructed<DatadogEventIngestionWorker>().start()
        } answers {}
        every {
            anyConstructed<DatadogInfraIngestionWorker>().start()
        } answers {}
        every {
            anyConstructed<MiscIngestionWorker>().start()
        } answers {}
        every {
            anyConstructed<NdmIngestionWorker>().start()
        } answers {}
        every {
            anyConstructed<SecurityIngestionWorker>().start()
        } answers {}
        every {
            anyConstructed<TraceIngestionWorker>().stop()
        } answers {}
        every {
            anyConstructed<OrchestratorIngestionWorker>().stop()
        } answers {}
        every {
            anyConstructed<DbmIngestionWorker>().stop()
        } answers {}
        every {
            anyConstructed<DebuggerIngestionWorker>().stop()
        } answers {}
        every {
            anyConstructed<DatadogMetricIngestionWorker>().stop()
        } answers {}
        every {
            anyConstructed<DatadogEventIngestionWorker>().stop()
        } answers {}
        every {
            anyConstructed<DatadogInfraIngestionWorker>().stop()
        } answers {}
        every {
            anyConstructed<MiscIngestionWorker>().stop()
        } answers {}
        every {
            anyConstructed<NdmIngestionWorker>().stop()
        } answers {}
        every {
            anyConstructed<SecurityIngestionWorker>().stop()
        } answers {}

        action()
    }

    private fun io.ktor.server.application.Application.installDatadogRateLimits() {
        install(RateLimit) {
            register(RateLimitName("datadog-ingestion")) {
                requestKey { call -> call.toString() }
                rateLimiter(
                    limit = 1000,
                    refillPeriod = 1.seconds,
                )
            }
            register(RateLimitName("api")) {
                requestKey { call -> call.toString() }
                rateLimiter(
                    limit = 1000,
                    refillPeriod = 1.seconds,
                )
            }
        }
    }
}
