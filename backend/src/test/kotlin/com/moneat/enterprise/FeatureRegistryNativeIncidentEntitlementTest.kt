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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureRegistryNativeIncidentEntitlementTest {
    @AfterTest
    fun reset() {
        FeatureRegistry.resetForTest()
    }

    @Test
    fun `native incident entitlement fails closed without a billing provider`() {
        assertFalse(FeatureRegistry.isNativeIncidentResponseEntitled(1))
        assertFalse(FeatureRegistry.nativeIncidentEntitlementStatus(1).enabled)
    }

    @Test
    fun `native incident availability follows the organization entitlement`() {
        FeatureRegistry.registerForTest(EntitlementModule(enabled = false))
        assertFalse(FeatureRegistry.isNativeIncidentResponseEntitled(42))

        FeatureRegistry.resetForTest()
        FeatureRegistry.registerForTest(EntitlementModule(enabled = true))
        assertTrue(FeatureRegistry.isNativeIncidentResponseEntitled(42))
        assertTrue(FeatureRegistry.nativeIncidentEntitlementStatus(42).enabled)
    }

    private class EntitlementModule(private val enabled: Boolean) :
        EnterpriseModule,
        NativeIncidentEntitlementBridge {
        override val name: String = "Billing"

        override fun registerRoutes(route: Route) = Unit

        override fun startBackgroundJobs(application: Application) = Unit

        override fun stopBackgroundJobs() = Unit

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
}
