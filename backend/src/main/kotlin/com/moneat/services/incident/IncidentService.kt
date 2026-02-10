package com.moneat.services.incident

import com.moneat.models.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import kotlinx.datetime.Clock

/**
 * Middleware service for dispatching incident alerts to configured providers.
 * Handles routing rule lookup, severity resolution, and event logging.
 */
class IncidentService {
    private val logger = LoggerFactory.getLogger(IncidentService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    /**
     * Fire an alert to all enabled incident providers for the organization.
     * Severity is resolved in order: per-monitor override > routing rule default > skip
     */
    suspend fun fireAlert(event: IncidentEvent) {
        try {
            val configs = getEnabledProviderConfigs(event.organizationId)
            if (configs.isEmpty()) {
                logger.debug("No enabled incident providers for org ${event.organizationId}")
                return
            }
            
            configs.forEach { config ->
                try {
                    // Check if we should route this alert
                    val shouldRoute = shouldRouteAlert(config.id, event.source)
                    if (!shouldRoute) {
                        logger.debug("Skipping alert for provider ${config.name}: no routing rule for ${event.source}")
                        return@forEach
                    }
                    
                    // Get the provider implementation
                    val provider = IncidentProviderRegistry.getProvider(config.providerType)
                    if (provider == null) {
                        logger.error("Provider type ${config.providerType} not registered")
                        logEvent(config, event, success = false, errorMessage = "Provider not registered")
                        return@forEach
                    }
                    
                    // Send the alert
                    val result = provider.sendAlert(event, config)
                    
                    result.fold(
                        onSuccess = { incidentId ->
                            logger.info("Alert sent to ${config.name}: $incidentId")
                            logEvent(config, event, success = true, providerIncidentId = incidentId)
                        },
                        onFailure = { error ->
                            logger.error("Failed to send alert to ${config.name}: ${error.message}", error)
                            logEvent(config, event, success = false, errorMessage = error.message ?: "Unknown error")
                        }
                    )
                } catch (e: Exception) {
                    logger.error("Error processing alert for provider ${config.name}", e)
                    logEvent(config, event, success = false, errorMessage = e.message ?: "Unknown error")
                }
            }
        } catch (e: Exception) {
            logger.error("Error firing alert", e)
        }
    }
    
    /**
     * Resolve an alert with all enabled incident providers.
     */
    suspend fun resolveAlert(organizationId: Int, source: AlertSource, deduplicationKey: String) {
        try {
            val configs = getEnabledProviderConfigs(organizationId)
            if (configs.isEmpty()) {
                return
            }
            
            configs.forEach { config ->
                try {
                    // Check if this provider has a routing rule for this source
                    val shouldRoute = shouldRouteAlert(config.id, source)
                    if (!shouldRoute) {
                        logger.debug("Skipping resolve for provider ${config.name}: no routing rule for $source")
                        return@forEach
                    }
                    
                    val provider = IncidentProviderRegistry.getProvider(config.providerType)
                    if (provider == null) {
                        logger.error("Provider type ${config.providerType} not registered")
                        return@forEach
                    }
                    
                    val result = provider.resolveAlert(deduplicationKey, config)
                    
                    result.fold(
                        onSuccess = { incidentId ->
                            logger.info("Alert resolved with ${config.name}: $incidentId")
                            logResolveEvent(config, organizationId, source, deduplicationKey, success = true, providerIncidentId = incidentId)
                        },
                        onFailure = { error ->
                            logger.error("Failed to resolve alert with ${config.name}: ${error.message}", error)
                            logResolveEvent(config, organizationId, source, deduplicationKey, success = false, errorMessage = error.message)
                        }
                    )
                } catch (e: Exception) {
                    logger.error("Error resolving alert for provider ${config.name}", e)
                    logResolveEvent(config, organizationId, source, deduplicationKey, success = false, errorMessage = e.message)
                }
            }
        } catch (e: Exception) {
            logger.error("Error resolving alert", e)
        }
    }
    
    /**
     * Get incident severity from per-monitor override or routing rule.
     * Returns null if no severity is configured (alert should be skipped).
     */
    fun resolveIncidentSeverity(
        providerConfigId: Int,
        alertSource: AlertSource,
        monitorSeverityOverride: String?
    ): IncidentSeverity? {
        // First check per-monitor override
        monitorSeverityOverride?.let {
            return IncidentSeverity.fromString(it)
        }
        
        // Fall back to routing rule
        return transaction {
            IncidentRoutingRules.selectAll().where {
                (IncidentRoutingRules.providerConfigId eq providerConfigId) and
                (IncidentRoutingRules.alertSource eq alertSource.name) and
                IncidentRoutingRules.alertType.isNull()
            }.firstOrNull()?.let { row ->
                IncidentSeverity.fromString(row[IncidentRoutingRules.incidentSeverity])
            }
        }
    }
    
    private fun getEnabledProviderConfigs(organizationId: Int): List<ProviderConfig> {
        return transaction {
            IncidentProviderConfigs.selectAll().where {
                (IncidentProviderConfigs.organizationId eq organizationId) and
                (IncidentProviderConfigs.enabled eq true)
            }.map { row ->
                ProviderConfig(
                    id = row[IncidentProviderConfigs.id].value,
                    organizationId = row[IncidentProviderConfigs.organizationId],
                    providerType = row[IncidentProviderConfigs.providerType],
                    name = row[IncidentProviderConfigs.name],
                    apiKey = row[IncidentProviderConfigs.apiKey],
                    configJson = try {
                        json.parseToJsonElement(row[IncidentProviderConfigs.configJson]).jsonObject
                    } catch (e: Exception) {
                        buildJsonObject {}
                    },
                    enabled = row[IncidentProviderConfigs.enabled]
                )
            }
        }
    }
    
    private fun shouldRouteAlert(providerConfigId: Int, source: AlertSource): Boolean {
        return transaction {
            IncidentRoutingRules.selectAll()
                .where {
                    (IncidentRoutingRules.providerConfigId eq providerConfigId) and
                    (IncidentRoutingRules.alertSource eq source.name)
                }.count() > 0
        }
    }
    
    private fun logEvent(
        config: ProviderConfig,
        event: IncidentEvent,
        success: Boolean,
        providerIncidentId: String? = null,
        errorMessage: String? = null
    ) {
        transaction {
            IncidentEventLog.insert {
                it[IncidentEventLog.organizationId] = event.organizationId
                it[IncidentEventLog.providerConfigId] = config.id
                it[IncidentEventLog.alertSource] = event.source.name
                it[IncidentEventLog.deduplicationKey] = event.deduplicationKey
                it[IncidentEventLog.incidentSeverity] = event.severity.name
                it[IncidentEventLog.incidentStatus] = event.status.name
                it[IncidentEventLog.title] = event.title
                it[IncidentEventLog.description] = event.description
                it[IncidentEventLog.providerIncidentId] = providerIncidentId
                it[IncidentEventLog.success] = success
                it[IncidentEventLog.errorMessage] = errorMessage
                it[IncidentEventLog.metadata] = json.encodeToString(kotlinx.serialization.serializer(), event.metadata)
                it[IncidentEventLog.createdAt] = Clock.System.now()
            }
        }
    }
    
    private fun logResolveEvent(
        config: ProviderConfig,
        organizationId: Int,
        source: AlertSource,
        deduplicationKey: String,
        success: Boolean,
        providerIncidentId: String? = null,
        errorMessage: String? = null
    ) {
        transaction {
            IncidentEventLog.insert {
                it[IncidentEventLog.organizationId] = organizationId
                it[IncidentEventLog.providerConfigId] = config.id
                it[IncidentEventLog.alertSource] = source.name
                it[IncidentEventLog.deduplicationKey] = deduplicationKey
                it[IncidentEventLog.incidentSeverity] = "N/A"
                it[IncidentEventLog.incidentStatus] = IncidentStatus.RESOLVED.name
                it[IncidentEventLog.title] = "Alert Resolved"
                it[IncidentEventLog.description] = null
                it[IncidentEventLog.providerIncidentId] = providerIncidentId
                it[IncidentEventLog.success] = success
                it[IncidentEventLog.errorMessage] = errorMessage
                it[IncidentEventLog.metadata] = null
                it[IncidentEventLog.createdAt] = Clock.System.now()
            }
        }
    }
}
