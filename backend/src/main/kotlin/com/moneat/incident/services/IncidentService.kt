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
import com.moneat.alerts.models.AlertPriority
import com.moneat.alerts.models.AlertSource
import com.moneat.alerts.models.AlertStatus
import com.moneat.config.EnvConfig
import com.moneat.enterprise.FeatureRegistry
import com.moneat.incident.models.IncidentEventLog
import com.moneat.incident.models.IncidentProviderConfigs
import com.moneat.incident.models.IncidentRoutingRules
import com.moneat.incident.models.ProviderConfig
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.EscalationPolicyAlertSources
import com.moneat.utils.suspendRunCatching
import com.moneat.workflows.services.AlertResolvedWorkflowEvent
import com.moneat.workflows.services.WorkflowService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.time.Clock

/**
 * Middleware service for dispatching incident alerts to configured providers.
 * Handles routing rule lookup, alert priority resolution, and event logging.
 */
class IncidentService(
    private val workflowService: WorkflowService = WorkflowService()
) {
    private val logger = LoggerFactory.getLogger(IncidentService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val AUTO_RESOLVABLE_SOURCES =
            setOf(
                AlertSource.HOST_ALERT,
                AlertSource.HOST_DOWN,
                AlertSource.UPTIME_MONITOR,
                AlertSource.DASHBOARD_ALERT
            )
    }

    /**
     * Fire an alert to all enabled incident providers for the organization.
     * Priority is resolved in order: per-monitor override > routing rule default > skip
     */
    suspend fun fireAlert(
        event: AlertLifecycleEvent,
        publishWorkflow: Boolean = true
    ) {
        // Check if native on-call is enabled
        val onCallEnabled = EnvConfig.get("ONCALL_ENABLED", "false").toBoolean()

        if (onCallEnabled) {
            suspendRunCatching {
                triggerNativeEscalation(event)
            }.getOrElse { e ->
                logger.error("Error triggering native escalation", e)
            }
        }

        if (publishWorkflow) {
            suspendRunCatching {
                workflowService.publishAlertTriggered(event)
            }.getOrElse { e ->
                logger.error("Error publishing alert-triggered workflow", e)
            }
        }

        suspendRunCatching {
            val configs = getEnabledProviderConfigs(event.organizationId)
            if (configs.isEmpty()) {
                logger.debug("No enabled incident providers for org ${event.organizationId}")
                return
            }

            configs.forEach { config ->
                suspendRunCatching {
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
                }.getOrElse { e ->
                    logger.error("Error processing alert for provider ${config.name}", e)
                    logEvent(config, event, success = false, errorMessage = e.message ?: "Unknown error")
                }
            }
        }.getOrElse { e ->
            logger.error("Error firing alert", e)
        }
    }

    /**
     * Resolve an alert with all enabled incident providers.
     */
    suspend fun resolveAlert(
        organizationId: Int,
        source: AlertSource,
        deduplicationKey: String,
        title: String = "Alert resolved",
        description: String = "Moneat resolved alert $deduplicationKey",
        moneatUrl: String = "",
        publishWorkflow: Boolean = true
    ) {
        if (publishWorkflow) {
            suspendRunCatching {
                workflowService.publishAlertResolved(
                    AlertResolvedWorkflowEvent(
                        organizationId = organizationId,
                        source = source.name,
                        deduplicationKey = deduplicationKey,
                        title = title,
                        description = description,
                        moneatUrl = moneatUrl,
                    )
                )
            }.getOrElse { e ->
                logger.error("Error publishing alert-resolved workflow", e)
            }
        }

        suspendRunCatching {
            val configs = getEnabledProviderConfigs(organizationId)
            if (configs.isEmpty()) {
                return
            }

            configs.forEach { config ->
                suspendRunCatching {
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
                            logResolveEvent(
                                config,
                                organizationId,
                                source,
                                deduplicationKey,
                                success = true,
                                providerIncidentId = incidentId
                            )
                        },
                        onFailure = { error ->
                            logger.error("Failed to resolve alert with ${config.name}: ${error.message}", error)
                            logResolveEvent(
                                config,
                                organizationId,
                                source,
                                deduplicationKey,
                                success = false,
                                errorMessage = error.message
                            )
                        }
                    )
                }.getOrElse { e ->
                    logger.error("Error resolving alert for provider ${config.name}", e)
                    logResolveEvent(
                        config,
                        organizationId,
                        source,
                        deduplicationKey,
                        success = false,
                        errorMessage = e.message
                    )
                }
            }
        }.getOrElse { e ->
            logger.error("Error resolving alert", e)
        }
    }

    /**
     * Resolve only alert sources with deterministic clear signals.
     */
    suspend fun autoResolveAlert(
        organizationId: Int,
        source: AlertSource,
        deduplicationKey: String,
        title: String = "Alert resolved",
        description: String = "Moneat resolved alert $deduplicationKey",
        moneatUrl: String = "",
        publishWorkflow: Boolean = true
    ) {
        if (!isAutoResolvableSource(source)) {
            logger.warn("Skipping auto-resolve for source without clear signal: $source")
            return
        }

        resolveAlert(organizationId, source, deduplicationKey, title, description, moneatUrl, publishWorkflow)
    }

    internal fun isAutoResolvableSource(source: AlertSource): Boolean {
        return source in AUTO_RESOLVABLE_SOURCES
    }

    /**
     * Get alert priority from per-monitor override or routing rule.
     * Returns null if no priority is configured (alert should be skipped).
     */
    fun resolveAlertPriority(
        providerConfigId: Int,
        alertSource: AlertSource,
        monitorPriorityOverride: String?
    ): AlertPriority? {
        // First check per-monitor override
        monitorPriorityOverride?.let {
            return AlertPriority.fromString(it)
        }

        // Fall back to routing rule
        return transaction {
            IncidentRoutingRules
                .selectAll()
                .where {
                    (IncidentRoutingRules.providerConfigId eq providerConfigId) and
                        (IncidentRoutingRules.alertSource eq alertSource.name) and
                        IncidentRoutingRules.alertType.isNull()
                }.firstOrNull()
                ?.let { row ->
                    AlertPriority.fromString(row[IncidentRoutingRules.alertPriority])
                }
        }
    }

    private fun getEnabledProviderConfigs(organizationId: Int): List<ProviderConfig> {
        return transaction {
            IncidentProviderConfigs
                .selectAll()
                .where {
                    (IncidentProviderConfigs.organizationId eq organizationId) and
                        (IncidentProviderConfigs.enabled eq true)
                }.map { row ->
                    ProviderConfig(
                        id = row[IncidentProviderConfigs.id].value,
                        organizationId = row[IncidentProviderConfigs.organizationId],
                        providerType = row[IncidentProviderConfigs.providerType],
                        name = row[IncidentProviderConfigs.name],
                        apiKey = row[IncidentProviderConfigs.apiKey],
                        configJson =
                        suspendRunCatching {
                            val jsonStr = row[IncidentProviderConfigs.configJson]
                            json.parseToJsonElement(jsonStr).jsonObject
                        }.getOrElse { _ ->
                            buildJsonObject {}
                        },
                        enabled = row[IncidentProviderConfigs.enabled]
                    )
                }
        }
    }

    private fun shouldRouteAlert(
        providerConfigId: Int,
        source: AlertSource
    ): Boolean {
        return transaction {
            IncidentRoutingRules
                .selectAll()
                .where {
                    (IncidentRoutingRules.providerConfigId eq providerConfigId) and
                        (IncidentRoutingRules.alertSource eq source.name)
                }.count() > 0
        }
    }

    private fun logEvent(
        config: ProviderConfig,
        event: AlertLifecycleEvent,
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
                it[IncidentEventLog.alertPriority] = event.priority.wire
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
                it[IncidentEventLog.alertPriority] = "N/A"
                it[IncidentEventLog.incidentStatus] = AlertStatus.RESOLVED.name
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

    /**
     * Trigger native on-call escalation engine if configured.
     */
    private suspend fun triggerNativeEscalation(event: AlertLifecycleEvent) {
        val bridge = FeatureRegistry.getOnCallBridge()
        if (bridge == null) {
            logger.debug("On-call enterprise module not loaded — skipping native escalation")
            return
        }
        suspendRunCatching {
            // Check if organization has an escalation policy for this alert source
            val escalationPolicyId = getEscalationPolicyForSource(event.organizationId, event.source)

            if (escalationPolicyId == null) {
                logger.debug("No escalation policy configured for org ${event.organizationId}, source ${event.source}")
                return
            }

            val priority = bridge.resolvePriority(event.organizationId, event.priority.wire)
            if (priority == null) {
                logger.warn("Could not resolve alert priority ${event.priority.wire}")
                return
            }

            // Check if we should escalate based on business hours
            val shouldEscalate = bridge.shouldEscalate(event.organizationId, priority.priority)

            if (!shouldEscalate) {
                logger.debug("Alert deferred: outside business hours for priority ${priority.priority}")
                return
            }

            // Trigger escalation
            val incidentId =
                bridge.triggerEscalation(
                    organizationId = event.organizationId,
                    escalationPolicyId = escalationPolicyId,
                    title = event.title,
                    description = event.description,
                    priority = priority.priority,
                    alertSource = event.source.name,
                    deduplicationKey = event.deduplicationKey,
                    metadata =
                    if (event.metadata.isNotEmpty()) {
                        kotlinx.serialization.json.Json.encodeToString(
                            kotlinx.serialization.serializer(),
                            event.metadata
                        )
                    } else {
                        null
                    }
                )

            if (incidentId != null) {
                logger.info("Native escalation triggered for incident $incidentId")
            }
        }.getOrElse { e ->
            logger.error("Error triggering native escalation", e)
        }
    }

    /**
     * Get the escalation policy ID for a given alert source.
     * Uses per-source routing when configured, otherwise falls back to first policy.
     */
    private fun getEscalationPolicyForSource(
        organizationId: Int,
        source: AlertSource
    ): Int? {
        return transaction {
            logger.debug("Resolving escalation policy for org {} source {}", organizationId, source.name)
            // Prefer source-specific routing
            EscalationPolicyAlertSources
                .select(EscalationPolicyAlertSources.escalationPolicyId)
                .where {
                    (EscalationPolicyAlertSources.organizationId eq organizationId) and
                        (EscalationPolicyAlertSources.alertSource eq source.name)
                }
                .firstOrNull()
                ?.get(EscalationPolicyAlertSources.escalationPolicyId)
                ?: run {
                    // Fall back to first escalation policy for this org
                    EscalationPolicies
                        .select(EscalationPolicies.id)
                        .where { EscalationPolicies.organizationId eq organizationId }
                        .limit(1)
                        .singleOrNull()
                        ?.get(EscalationPolicies.id)
                        ?.value
                }
        }
    }
}
