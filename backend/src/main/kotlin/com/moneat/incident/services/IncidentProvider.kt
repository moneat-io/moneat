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

package com.moneat.incident.services

import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.incident.models.ProviderConfig

/**
 * Provider interface for incident management integrations.
 * Implementations send alerts to external incident management platforms.
 */
interface IncidentProvider {
    /** Unique identifier for this provider type (e.g., "incident_io", "pagerduty") */
    val providerType: String

    /**
     * Send an alert to the incident provider.
     * @param event The alert lifecycle event to send
     * @param config Provider-specific configuration
     * @return Result containing the provider's incident ID on success, or error message on failure
     */
    suspend fun sendAlert(
        event: AlertLifecycleEvent,
        config: ProviderConfig
    ): Result<String>

    /**
     * Resolve an alert with the incident provider.
     * @param deduplicationKey The deduplication key of the alert to resolve
     * @param config Provider-specific configuration
     * @return Result containing success status or error message
     */
    suspend fun resolveAlert(
        deduplicationKey: String,
        config: ProviderConfig
    ): Result<String>

    /**
     * Test the connection to the incident provider.
     * @param config Provider-specific configuration
     * @return Result containing true if connection successful, false or error otherwise
     */
    suspend fun testConnection(config: ProviderConfig): Result<Boolean>
}
