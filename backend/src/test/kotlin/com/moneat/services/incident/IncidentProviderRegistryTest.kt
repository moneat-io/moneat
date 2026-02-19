package com.moneat.services.incident

import com.moneat.models.IncidentEvent
import com.moneat.models.ProviderConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncidentProviderRegistryTest {
    @Test
    fun `register stores provider and exposes its type`() {
        val provider = object : IncidentProvider {
            override val providerType: String = "test-provider-${System.nanoTime()}"

            override suspend fun sendAlert(event: IncidentEvent, config: ProviderConfig): Result<String> {
                return Result.success("ok")
            }

            override suspend fun resolveAlert(deduplicationKey: String, config: ProviderConfig): Result<String> {
                return Result.success("ok")
            }

            override suspend fun testConnection(config: ProviderConfig): Result<Boolean> {
                return Result.success(true)
            }
        }

        IncidentProviderRegistry.register(provider)

        assertEquals(provider, IncidentProviderRegistry.getProvider(provider.providerType))
        assertTrue(provider.providerType in IncidentProviderRegistry.getProviderTypes())
    }
}
