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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureRegistryNativeIncidentRolloutTest {
    @AfterTest
    fun reset() {
        FeatureRegistry.resetForTest()
        System.clearProperty(FEATURE_FLAG_ENVIRONMENT_VARIABLE)
    }

    @Test
    fun `native incident rollout fails closed without module or provider`() {
        System.setProperty(FEATURE_FLAG_ENVIRONMENT_VARIABLE, "staging")

        val noModule = FeatureRegistry.nativeIncidentRolloutStatus(1)
        assertFalse(noModule.enabled)
        assertEquals("staging", noModule.environment)
        assertEquals(NativeIncidentRolloutState.MODULE_UNAVAILABLE, noModule.state)

        FeatureRegistry.registerForTest(TestModule("On-Call"))
        val noProvider = FeatureRegistry.nativeIncidentRolloutStatus(1)
        assertFalse(noProvider.enabled)
        assertEquals(NativeIncidentRolloutState.PROVIDER_UNAVAILABLE, noProvider.state)
    }

    @Test
    fun `native incident rollout delegates organization and environment to provider`() {
        System.setProperty(FEATURE_FLAG_ENVIRONMENT_VARIABLE, "development")
        val provider = RecordingRolloutModule()
        FeatureRegistry.registerForTest(TestModule("On-Call"), provider)

        val status = FeatureRegistry.nativeIncidentRolloutStatus(42)

        assertTrue(status.enabled)
        assertEquals(42 to "development", provider.lastRequest)
        assertEquals(NativeIncidentRolloutState.ENABLED, status.state)
    }

    @Test
    fun `native incident availability requires rollout and paid entitlement independently`() {
        System.setProperty(FEATURE_FLAG_ENVIRONMENT_VARIABLE, "production")
        FeatureRegistry.registerForTest(
            TestModule("On-Call"),
            RecordingRolloutModule(),
            EntitlementModule(enabled = false),
        )
        assertFalse(FeatureRegistry.isNativeIncidentResponseEnabled(42))

        FeatureRegistry.resetForTest()
        FeatureRegistry.registerForTest(
            TestModule("On-Call"),
            RecordingRolloutModule(),
            EntitlementModule(enabled = true),
        )
        assertTrue(FeatureRegistry.isNativeIncidentResponseEnabled(42))
    }

    private open class TestModule(override val name: String) : EnterpriseModule {
        override fun registerRoutes(route: Route) = Unit

        override fun startBackgroundJobs(application: Application) = Unit

        override fun stopBackgroundJobs() = Unit
    }

    private class RecordingRolloutModule :
        TestModule("Feature Flags"),
        NativeIncidentRolloutBridge {
        var lastRequest: Pair<Int, String>? = null

        override fun status(organizationId: Int, environment: String): NativeIncidentRolloutStatus {
            lastRequest = organizationId to environment
            return NativeIncidentRolloutStatus(
                enabled = true,
                environment = environment,
                state = NativeIncidentRolloutState.ENABLED,
            )
        }
    }

    private class EntitlementModule(private val enabled: Boolean) :
        TestModule("Billing"),
        NativeIncidentEntitlementBridge {
        override fun status(organizationId: Int): NativeIncidentEntitlementStatus =
            NativeIncidentEntitlementStatus(enabled = enabled, plan = "TEST")

        override fun consume(
            organizationId: Int,
            quotaKey: NativeIncidentQuotaKey,
            quantity: Long,
            idempotencyKey: String,
        ): NativeIncidentQuotaDecision =
            NativeIncidentQuotaDecision(
                allowed = enabled,
                status = NativeIncidentQuotaStatus(quotaKey, limit = 1, used = 0),
            )

        override fun reconcile(
            organizationId: Int,
            quotaKey: NativeIncidentQuotaKey,
            authoritativeUsage: Long,
            idempotencyKey: String,
        ): NativeIncidentQuotaDecision = consume(organizationId, quotaKey, 1, idempotencyKey)
    }

    private companion object {
        const val FEATURE_FLAG_ENVIRONMENT_VARIABLE = "MONEAT_FEATURE_FLAG_ENVIRONMENT"
    }
}
