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

package com.moneat.services.incident

import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.incident.models.ProviderConfig
import com.moneat.incident.services.IncidentProvider
import com.moneat.incident.services.IncidentProviderRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncidentProviderRegistryTest {
    @Test
    fun `register stores provider and exposes its type`() {
        val provider =
            object : IncidentProvider {
                override val providerType: String = "test-provider-${System.nanoTime()}"

                override suspend fun sendAlert(
                    event: AlertLifecycleEvent,
                    config: ProviderConfig
                ): Result<String> {
                    return Result.success("ok")
                }

                override suspend fun resolveAlert(
                    deduplicationKey: String,
                    config: ProviderConfig
                ): Result<String> {
                    return Result.success("ok")
                }

                override suspend fun testConnection(config: ProviderConfig): Result<Boolean> {
                    return Result.success(true)
                }
            }

        IncidentProviderRegistry.register(provider)
        try {
            assertEquals(provider, IncidentProviderRegistry.getProvider(provider.providerType))
            assertTrue(provider.providerType in IncidentProviderRegistry.getProviderTypes())
        } finally {
            IncidentProviderRegistry.unregister(provider)
        }
    }

    @Test
    fun `unregister removes only the provider instance that is still registered`() {
        val providerType = "test-provider-${System.nanoTime()}"
        val first = TestIncidentProvider(providerType)
        val replacement = TestIncidentProvider(providerType)
        IncidentProviderRegistry.register(first)
        IncidentProviderRegistry.register(replacement)

        IncidentProviderRegistry.unregister(first)
        assertEquals(replacement, IncidentProviderRegistry.getProvider(providerType))

        IncidentProviderRegistry.unregister(replacement)
        assertEquals(null, IncidentProviderRegistry.getProvider(providerType))
    }

    private class TestIncidentProvider(override val providerType: String) : IncidentProvider {
        override suspend fun sendAlert(event: AlertLifecycleEvent, config: ProviderConfig): Result<String> =
            Result.success("ok")

        override suspend fun resolveAlert(deduplicationKey: String, config: ProviderConfig): Result<String> =
            Result.success("ok")

        override suspend fun testConnection(config: ProviderConfig): Result<Boolean> = Result.success(true)
    }
}
