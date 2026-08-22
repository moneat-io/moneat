// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents

/** Canonical source-neutral object names used across incident-response contracts. */
enum class IncidentDomainObject(
    val apiName: String,
    val owner: String,
    val lifecycle: String,
) {
    NATIVE_INCIDENT(
        apiName = "native_incident",
        owner = "enterprise incident response",
        lifecycle = "triage through post-incident closure, decline, or terminal merge",
    ),
    FORWARDED_PROVIDER_INCIDENT(
        apiName = "forwarded_provider_incident",
        owner = "AGPL incident-provider passthrough",
        lifecycle = "delivery and provider synchronization only",
    ),
    ALERT_EPISODE(
        apiName = "alert_episode",
        owner = "source-neutral alert lifecycle",
        lifecycle = "firing through resolution or suppression",
    ),
    ON_CALL_ALERT(
        apiName = "on_call_alert",
        owner = "enterprise on-call escalation",
        lifecycle = "triggered through acknowledgement and resolution",
    ),
    ENTERPRISE_ALERT_ROUTE(
        apiName = "enterprise_alert_route",
        owner = "enterprise incident response",
        lifecycle = "ordered first-match selection of paging, grouping, incident creation, and recovery",
    ),
    PROVIDER_ROUTING_RULE(
        apiName = "provider_routing_rule",
        owner = "AGPL incident-provider passthrough",
        lifecycle = "per-provider forwarding selection and priority defaults only",
    ),
}

object IncidentDomainGlossary {
    /**
     * Routing concepts that are frequently confused.
     *
     * [IncidentDomainObject.ENTERPRISE_ALERT_ROUTE] decides native paging, grouping, incident
     * creation, and recovery from a normalized alert context.
     * [IncidentDomainObject.PROVIDER_ROUTING_RULE] only decides whether the open-source passthrough
     * forwards an alert source to a configured external provider. Neither one governs the other.
     */
    val ROUTING_OBJECTS: Set<IncidentDomainObject> =
        setOf(IncidentDomainObject.ENTERPRISE_ALERT_ROUTE, IncidentDomainObject.PROVIDER_ROUTING_RULE)

    fun requireCanonicalApiName(value: String): IncidentDomainObject =
        IncidentDomainObject.entries.firstOrNull { it.apiName == value }
            ?: throw IllegalArgumentException("Unknown incident-domain object: $value")
}
