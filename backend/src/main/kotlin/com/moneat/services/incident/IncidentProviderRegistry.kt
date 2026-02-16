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
