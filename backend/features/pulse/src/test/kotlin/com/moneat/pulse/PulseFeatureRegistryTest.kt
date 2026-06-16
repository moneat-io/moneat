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

package com.moneat.pulse

import com.moneat.config.EnvConfig
import com.moneat.enterprise.EnterpriseModule
import com.moneat.enterprise.FeatureRegistry
import com.moneat.plugins.FeaturesResponse
import com.moneat.plugins.configureSerialization
import com.moneat.shared.services.PulseService
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.server.config.MapApplicationConfig
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.util.ServiceLoader
import kotlin.test.assertEquals
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class PulseFeatureRegistryTest {
    @BeforeTest
    fun resetBefore() {
        FeatureRegistry.resetForTest()
    }

    @AfterTest
    fun resetAfter() {
        FeatureRegistry.resetForTest()
    }

    @Test
    fun `ServiceLoader discovers Pulse module from feature resources`() {
        val moduleNames = ServiceLoader.load(EnterpriseModule::class.java)
            .map { module -> module.name }

        assertTrue("Pulse" in moduleNames)
    }

    @Test
    fun `features response includes Pulse when runtime module is present`() = testApplication {
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
        assertTrue("Pulse" in response.modules)
    }

    @Test
    fun `Pulse module owns telemetry pulse scheduler lifecycle`() {
        withSelfHostedTelemetryEnabled {
            testApplication {
                environment {
                    config = MapApplicationConfig("pulse.intervalHours" to "2")
                }

                val pulseService = mockk<PulseService>(relaxUnitFun = true)
                var capturedInterval: Duration? = null
                val pulseModule = PulseModule { interval ->
                    capturedInterval = interval
                    pulseService
                }

                application {
                    pulseModule.startBackgroundJobs(
                        this,
                        startSchedulers = false,
                        startIngestionWorkers = true,
                    )
                    verify(exactly = 0) { pulseService.start(any()) }

                    pulseModule.startBackgroundJobs(
                        this,
                        startSchedulers = true,
                        startIngestionWorkers = false,
                    )
                    assertEquals(2.hours, capturedInterval)
                    verify(exactly = 1) { pulseService.start(any()) }

                    pulseModule.stopBackgroundJobs()
                    verify(exactly = 1) { pulseService.stop() }
                }
            }
        }
    }

    private suspend fun ApplicationCall.respondFeatures() {
        respond(
            FeaturesResponse(
                enterprise = FeatureRegistry.isEnterpriseAvailable,
                modules = FeatureRegistry.registeredModules.map { module -> module.name },
                selfHost = EnvConfig.SelfHost.enabled,
            )
        )
    }

    private fun withSelfHostedTelemetryEnabled(block: () -> Unit) {
        val previousTelemetryEnabled = System.getProperty("TELEMETRY_ENABLED")
        mockkObject(EnvConfig.SelfHost)
        try {
            every { EnvConfig.SelfHost.enabled } returns true
            System.setProperty("TELEMETRY_ENABLED", "true")
            block()
        } finally {
            if (previousTelemetryEnabled == null) {
                System.clearProperty("TELEMETRY_ENABLED")
            } else {
                System.setProperty("TELEMETRY_ENABLED", previousTelemetryEnabled)
            }
            unmockkObject(EnvConfig.SelfHost)
        }
    }
}
