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

package com.moneat.org

import com.moneat.billing.services.PricingTierService
import com.moneat.config.EnvConfig
import com.moneat.enterprise.EnterpriseModule
import com.moneat.enterprise.FeatureRegistry
import com.moneat.notifications.services.EmailService
import com.moneat.org.repositories.OrgInvitationRepository
import com.moneat.org.repositories.OrgMembershipRepository
import com.moneat.org.services.AdminService
import com.moneat.org.services.OrgInvitationService
import com.moneat.org.services.OrgMembershipService
import com.moneat.plugins.FeaturesResponse
import com.moneat.plugins.configureSerialization
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

class OrgFeatureRegistryTest {
    @BeforeTest
    fun resetBefore() {
        FeatureRegistry.resetForTest()
    }

    @AfterTest
    fun resetAfter() {
        FeatureRegistry.resetForTest()
    }

    @Test
    fun `ServiceLoader discovers Organization module from feature resources`() {
        val moduleNames = ServiceLoader.load(EnterpriseModule::class.java)
            .map { module -> module.name }

        assertTrue("Organization" in moduleNames)
    }

    @Test
    fun `Organization module provides feature Koin bindings`() {
        val koinApplication = KoinApplication.init()
            .modules(
                module {
                    single { EmailService() }
                    single { PricingTierService() }
                },
                *OrgModule().koinModules().toTypedArray(),
            )

        try {
            assertIs<OrgMembershipRepository>(koinApplication.koin.get<OrgMembershipRepository>())
            assertIs<OrgInvitationRepository>(koinApplication.koin.get<OrgInvitationRepository>())
            assertIs<OrgMembershipService>(koinApplication.koin.get<OrgMembershipService>())
            assertIs<OrgInvitationService>(koinApplication.koin.get<OrgInvitationService>())
            assertIs<AdminService>(koinApplication.koin.get<AdminService>())
        } finally {
            koinApplication.close()
        }
    }

    @Test
    fun `features response includes Organization when runtime module is present`() = testApplication {
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
        assertTrue("Organization" in response.modules)
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
}
