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

package com.moneat.billing

import com.moneat.billing.repositories.SubscriptionRepository
import com.moneat.billing.services.AdminBillingService
import com.moneat.billing.services.BillingBackgroundService
import com.moneat.billing.services.BillingQuotaService
import com.moneat.billing.services.EntitlementService
import com.moneat.billing.services.PricingTierService
import com.moneat.billing.services.StripeService
import com.moneat.enterprise.EnterpriseModule
import com.moneat.enterprise.FeatureRegistry
import com.moneat.notifications.services.EmailService
import com.moneat.plugins.FeaturesResponse
import com.moneat.plugins.configureSerialization
import com.moneat.shared.repositories.OrganizationRepository
import com.moneat.shared.repositories.OrganizationRepositoryImpl
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.koin.core.KoinApplication
import org.koin.dsl.module
import java.util.ServiceLoader
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BillingFeatureRegistryTest {
    @BeforeTest
    fun resetBefore() {
        FeatureRegistry.resetForTest()
    }

    @AfterTest
    fun resetAfter() {
        FeatureRegistry.resetForTest()
    }

    @Test
    fun `ServiceLoader discovers Billing module from feature resources`() {
        val moduleNames = ServiceLoader.load(EnterpriseModule::class.java)
            .map { module -> module.name }

        assertTrue("Billing" in moduleNames)
    }

    @Test
    fun `Billing module provides feature Koin bindings`() {
        val koinApplication = KoinApplication.init()
            .modules(
                module {
                    single<OrganizationRepository> { OrganizationRepositoryImpl() }
                    single { EmailService() }
                },
                *BillingModule().koinModules().toTypedArray(),
            )

        try {
            assertIs<SubscriptionRepository>(koinApplication.koin.get<SubscriptionRepository>())
            assertIs<PricingTierService>(koinApplication.koin.get<PricingTierService>())
            assertIs<BillingQuotaService>(koinApplication.koin.get<BillingQuotaService>())
            assertIs<EntitlementService>(koinApplication.koin.get<EntitlementService>())
            assertIs<StripeService>(koinApplication.koin.get<StripeService>())
            assertIs<BillingBackgroundService>(koinApplication.koin.get<BillingBackgroundService>())
            assertIs<AdminBillingService>(koinApplication.koin.get<AdminBillingService>())
        } finally {
            koinApplication.close()
        }
    }

    @Test
    fun `features response includes Billing when runtime module is present`() = testApplication {
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
        assertTrue("Billing" in response.modules)
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
