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
        lifecycle = "triage through post-incident closure",
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
}

object IncidentDomainGlossary {
    fun requireCanonicalApiName(value: String): IncidentDomainObject =
        IncidentDomainObject.entries.firstOrNull { it.apiName == value }
            ?: throw IllegalArgumentException("Unknown incident-domain object: $value")
}
