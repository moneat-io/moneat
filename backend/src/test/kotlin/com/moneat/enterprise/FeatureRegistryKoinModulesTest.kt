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

package com.moneat.enterprise

import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val FEATURE_VALUE = "feature-owned-binding"
private const val FEATURE_QUALIFIER = "featureRegistryTestValue"

class FeatureRegistryKoinModulesTest {
    @BeforeTest
    fun resetBefore() {
        FeatureRegistry.resetForTest()
    }

    @AfterTest
    fun resetAfter() {
        FeatureRegistry.resetForTest()
    }

    @Test
    fun `registry exposes Koin modules declared by feature modules`() {
        FeatureRegistry.registerForTest(TestKoinFeatureModule())

        val koinApplication = KoinApplication.init().modules(FeatureRegistry.koinModules())

        try {
            assertEquals(
                FEATURE_VALUE,
                koinApplication.koin.get<String>(qualifier = named(FEATURE_QUALIFIER)),
            )
        } finally {
            koinApplication.close()
        }
    }
}

private class TestKoinFeatureModule : EnterpriseModule {
    override val name: String = "Test Feature"

    override fun registerRoutes(route: Route) = Unit

    override fun koinModules(): List<Module> =
        listOf(
            module {
                single(named(FEATURE_QUALIFIER)) { FEATURE_VALUE }
            }
        )

    override fun startBackgroundJobs(application: Application) = Unit

    override fun stopBackgroundJobs() = Unit
}
