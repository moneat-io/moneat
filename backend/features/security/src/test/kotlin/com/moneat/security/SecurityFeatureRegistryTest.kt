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

package com.moneat.security

import com.moneat.enterprise.DemoDataFeatureSeeder
import com.moneat.enterprise.DetectionSignalEvidenceBridge
import com.moneat.enterprise.EnterpriseModule
import com.moneat.enterprise.FeatureRegistry
import com.moneat.plugins.FeaturesResponse
import com.moneat.plugins.configureSerialization
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.ServiceLoader
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SecurityFeatureRegistryTest {
    @BeforeTest
    fun resetBefore() {
        startTestKoin()
        FeatureRegistry.resetForTest()
    }

    @AfterTest
    fun resetAfter() {
        FeatureRegistry.resetForTest()
        stopTestKoin()
    }

    @Test
    fun `ServiceLoader discovers Security module from feature resources`() {
        val moduleNames = ServiceLoader.load(EnterpriseModule::class.java)
            .map { module -> module.name }

        assertTrue("Security" in moduleNames)
    }

    @Test
    fun `ServiceLoader Security module exposes detection feature bridges`() {
        val securityModule = ServiceLoader
            .load(EnterpriseModule::class.java)
            .first { module -> module.name == "Security" }

        assertTrue(securityModule is DemoDataFeatureSeeder)
        assertTrue(securityModule is DetectionSignalEvidenceBridge)
        assertEquals(
            "service = 'api'",
            (securityModule as DetectionSignalEvidenceBridge).entityPredicate("service", "api", ::escapeSqlForTest),
        )
    }

    @Test
    fun `features response includes Security when runtime module is present`() = testApplication {
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
        assertTrue("Security" in response.modules)
    }

    @Test
    fun `ServiceLoader Security module registers vulnerability routes`() = testApplication {
        val securityModule = ServiceLoader
            .load(EnterpriseModule::class.java)
            .first { module -> module.name == "Security" }

        application {
            installJwtAuth()
            installFeatureRouteRateLimits()
            routing {
                securityModule.registerRoutes(this)
            }
        }

        val response = client.post("/v1/security/vulnerabilities/sbom") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
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

    private fun io.ktor.server.application.Application.installFeatureRouteRateLimits() {
        install(RateLimit) {
            FEATURE_ROUTE_RATE_LIMITS.forEach { name ->
                register(RateLimitName(name)) {
                    requestKey { "test-user" }
                    rateLimiter(limit = TEST_RATE_LIMIT, refillPeriod = TEST_RATE_LIMIT_REFILL)
                }
            }
        }
    }

    private companion object {
        private val FEATURE_ROUTE_RATE_LIMITS = listOf(
            "api",
            "contact",
            "datadog-ingestion",
            "ingestion",
            "log-ingestion",
            "mcp",
            "otlp-ingestion",
            "telemetry",
        )
        private const val TEST_RATE_LIMIT = 1000
        private val TEST_RATE_LIMIT_REFILL = 1.seconds

        private fun escapeSqlForTest(value: String): String = value.replace("'", "''")
    }
}
