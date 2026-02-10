package com.moneat.services.incident

import com.moneat.models.IncidentEvent
import com.moneat.models.ProviderConfig

/**
 * Provider interface for incident management integrations.
 * Implementations send alerts to external incident management platforms.
 */
interface IncidentProvider {
    /** Unique identifier for this provider type (e.g., "incident_io", "pagerduty") */
    val providerType: String
    
    /**
     * Send an alert to the incident provider.
     * @param event The incident event to send
     * @param config Provider-specific configuration
     * @return Result containing the provider's incident ID on success, or error message on failure
     */
    suspend fun sendAlert(event: IncidentEvent, config: ProviderConfig): Result<String>
    
    /**
     * Resolve an alert with the incident provider.
     * @param deduplicationKey The deduplication key of the alert to resolve
     * @param config Provider-specific configuration
     * @return Result containing success status or error message
     */
    suspend fun resolveAlert(deduplicationKey: String, config: ProviderConfig): Result<String>
    
    /**
     * Test the connection to the incident provider.
     * @param config Provider-specific configuration
     * @return Result containing true if connection successful, false or error otherwise
     */
    suspend fun testConnection(config: ProviderConfig): Result<Boolean>
}
