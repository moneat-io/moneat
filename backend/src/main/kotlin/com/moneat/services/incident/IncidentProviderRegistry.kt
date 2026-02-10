package com.moneat.services.incident

import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for incident provider implementations.
 * Providers register themselves at initialization time.
 */
object IncidentProviderRegistry {
    private val providers = ConcurrentHashMap<String, IncidentProvider>()
    
    /**
     * Register a provider implementation.
     */
    fun register(provider: IncidentProvider) {
        providers[provider.providerType] = provider
    }
    
    /**
     * Get a provider by type.
     * @return The provider implementation, or null if not registered
     */
    fun getProvider(providerType: String): IncidentProvider? {
        return providers[providerType]
    }
    
    /**
     * Get all registered provider types.
     */
    fun getProviderTypes(): Set<String> {
        return providers.keys.toSet()
    }
}
