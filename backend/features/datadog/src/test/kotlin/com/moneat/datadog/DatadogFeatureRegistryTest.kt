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

import com.moneat.enterprise.EnterpriseModule
import com.moneat.enterprise.FeatureRegistry
import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.datadog.models.DdApiKeys
import com.moneat.datadog.services.DatadogService
import com.moneat.plugins.FeaturesResponse
import com.moneat.plugins.configureSerialization
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.ServiceLoader
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatadogFeatureRegistryTest {
    @BeforeTest
    fun resetBefore() {
        FeatureRegistry.resetForTest()
        DatadogAuthMiddleware.clearCache()
    }

    @AfterTest
    fun resetAfter() {
        FeatureRegistry.resetForTest()
        DatadogAuthMiddleware.clearCache()
    }

    @Test
    fun `ServiceLoader discovers Datadog module from feature resources`() {
        val moduleNames = ServiceLoader.load(EnterpriseModule::class.java)
            .map { module -> module.name }

        assertTrue("Datadog" in moduleNames)
    }

    @Test
    fun `features response includes Datadog when runtime module is present`() = testApplication {
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
        assertTrue("Datadog" in response.modules)
    }

    @Test
    fun `Datadog rate-limit resolver buckets valid keys by organization and invalid keys by app fallback`() =
        testApplication {
            prepareApiKeyTable()
            val created = DatadogService.createApiKey(
                organizationId = 42,
                name = "Rate Limit Key",
                userId = 7,
            )

            application {
                FeatureRegistry.initialize()
                routing {
                    get("/rate-limit-key") {
                        val key = FeatureRegistry.resolveIngestionRateLimitKey("datadog-ingestion", call)
                        call.respondText(key ?: "fallback")
                    }
                }
            }

            assertEquals(
                "org:42",
                client.get("/rate-limit-key") {
                    header("DD-API-KEY", created.key)
                }.body(),
            )
            assertEquals(
                "fallback",
                client.get("/rate-limit-key") {
                    header("DD-API-KEY", "invalid")
                }.body(),
            )
            assertEquals("fallback", client.get("/rate-limit-key").body())
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

    private fun prepareApiKeyTable() {
        Database.connect(
            "jdbc:h2:mem:test_dd_feature_registry;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction {
            exec("DROP ALL OBJECTS")
            SchemaUtils.create(DdApiKeys)
        }
    }
}
