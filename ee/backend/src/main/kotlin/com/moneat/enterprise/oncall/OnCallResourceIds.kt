// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall

import com.moneat.enterprise.oncall.models.AlertPriorities
import com.moneat.enterprise.oncall.models.BusinessHours
import com.moneat.enterprise.oncall.models.OnCallAlertTimeline
import com.moneat.enterprise.oncall.models.OnCallAlerts
import com.moneat.enterprise.oncall.models.OnCallIncidentTimeline
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.enterprise.oncall.models.OnCallOverrides
import com.moneat.enterprise.oncall.models.UserDeviceTokens
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.OnCallSchedules
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.SlackUserMappings
import com.moneat.shared.models.Users
import com.moneat.shared.services.resolveScopedIntResourceId
import com.moneat.shared.services.toUuidOrNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll

internal fun organizationResourceId(organizationId: Int): String =
    Organizations
        .selectAll()
        .where { Organizations.id eq organizationId }
        .singleOrNull()
        ?.get(Organizations.resource_id)
        ?.toString()
        ?: missingResourceId("organization", organizationId)

internal fun userResourceId(userId: Int): String =
    Users
        .selectAll()
        .where { Users.id eq userId }
        .singleOrNull()
        ?.get(Users.resource_id)
        ?.toString()
        ?: missingResourceId("user", userId)

internal fun userResourceIdOrNull(userId: Int?): String? = userId?.let(::userResourceId)

internal fun userResourceIds(userIds: Collection<Int>): Map<Int, String> {
    if (userIds.isEmpty()) return emptyMap()
    return Users
        .selectAll()
        .where { Users.id inList userIds.distinct() }
        .associate { row -> row[Users.id] to row[Users.resource_id].toString() }
}

internal fun <T> Map<Int, T>.requireValue(id: Int, label: String): T =
    this[id] ?: missingResourceId(label, id)

internal fun scheduleResourceId(scheduleId: Int): String =
    OnCallSchedules
        .selectAll()
        .where { OnCallSchedules.id eq scheduleId }
        .singleOrNull()
        ?.get(OnCallSchedules.resourceId)
        ?.toString()
        ?: missingResourceId("on-call schedule", scheduleId)

internal fun scheduleResourceIds(scheduleIds: Collection<Int>): Map<Int, String> {
    if (scheduleIds.isEmpty()) return emptyMap()
    return OnCallSchedules
        .selectAll()
        .where { OnCallSchedules.id inList scheduleIds.distinct() }
        .associate { row -> row[OnCallSchedules.id].value to row[OnCallSchedules.resourceId].toString() }
}

internal fun overrideResourceId(overrideId: Int): String =
    OnCallOverrides
        .selectAll()
        .where { OnCallOverrides.id eq overrideId }
        .singleOrNull()
        ?.get(OnCallOverrides.resourceId)
        ?.toString()
        ?: missingResourceId("on-call override", overrideId)

internal fun incidentResourceId(incidentId: Int): String =
    OnCallIncidents
        .selectAll()
        .where { OnCallIncidents.id eq incidentId }
        .singleOrNull()
        ?.get(OnCallIncidents.resourceId)
        ?.toString()
        ?: missingResourceId("on-call incident", incidentId)

internal fun incidentResourceIdOrNull(incidentId: Int?): String? = incidentId?.let(::incidentResourceId)

internal fun incidentResourceIds(incidentIds: Collection<Int>): Map<Int, String> {
    if (incidentIds.isEmpty()) return emptyMap()
    return OnCallIncidents
        .selectAll()
        .where { OnCallIncidents.id inList incidentIds.distinct() }
        .associate { row -> row[OnCallIncidents.id].value to row[OnCallIncidents.resourceId].toString() }
}

internal fun alertResourceId(alertId: Int): String =
    OnCallAlerts
        .selectAll()
        .where { OnCallAlerts.id eq alertId }
        .singleOrNull()
        ?.get(OnCallAlerts.resourceId)
        ?.toString()
        ?: missingResourceId("on-call alert", alertId)

internal fun alertResourceIdOrNull(alertId: Int?): String? = alertId?.let(::alertResourceId)

internal fun alertIdForResource(organizationId: Int, alertResourceId: String): Int? {
    val resourceId = alertResourceId.toUuidOrNull() ?: return null
    return resolveScopedIntResourceId(
        table = OnCallAlerts,
        resourceIdColumn = OnCallAlerts.resourceId,
        scopeColumn = OnCallAlerts.organizationId,
        scopeId = organizationId,
        resourceId = resourceId,
    )
}

internal fun escalationPolicyResourceId(policyId: Int): String =
    EscalationPolicies
        .selectAll()
        .where { EscalationPolicies.id eq policyId }
        .singleOrNull()
        ?.get(EscalationPolicies.resourceId)
        ?.toString()
        ?: missingResourceId("escalation policy", policyId)

internal fun escalationPolicyResourceIdOrNull(policyId: Int?): String? =
    policyId?.let(::escalationPolicyResourceId)

internal fun escalationPolicyResourceIds(policyIds: Collection<Int>): Map<Int, String> {
    if (policyIds.isEmpty()) return emptyMap()
    return EscalationPolicies
        .selectAll()
        .where { EscalationPolicies.id inList policyIds.distinct() }
        .associate { row -> row[EscalationPolicies.id].value to row[EscalationPolicies.resourceId].toString() }
}

internal fun alertTimelineResourceId(eventId: Int): String =
    OnCallAlertTimeline
        .selectAll()
        .where { OnCallAlertTimeline.id eq eventId }
        .singleOrNull()
        ?.get(OnCallAlertTimeline.resourceId)
        ?.toString()
        ?: missingResourceId("on-call alert timeline event", eventId)

internal fun incidentTimelineResourceId(eventId: Int): String =
    OnCallIncidentTimeline
        .selectAll()
        .where { OnCallIncidentTimeline.id eq eventId }
        .singleOrNull()
        ?.get(OnCallIncidentTimeline.resourceId)
        ?.toString()
        ?: missingResourceId("on-call incident timeline event", eventId)

internal fun deviceTokenResourceId(tokenId: Int): String =
    UserDeviceTokens
        .selectAll()
        .where { UserDeviceTokens.id eq tokenId }
        .singleOrNull()
        ?.get(UserDeviceTokens.resourceId)
        ?.toString()
        ?: missingResourceId("user device token", tokenId)

internal fun alertPriorityResourceId(priorityId: Int): String =
    AlertPriorities
        .selectAll()
        .where { AlertPriorities.id eq priorityId }
        .singleOrNull()
        ?.get(AlertPriorities.resourceId)
        ?.toString()
        ?: missingResourceId("alert priority", priorityId)

internal fun businessHoursResourceId(businessHoursId: Int): String =
    BusinessHours
        .selectAll()
        .where { BusinessHours.id eq businessHoursId }
        .singleOrNull()
        ?.get(BusinessHours.resourceId)
        ?.toString()
        ?: missingResourceId("business hours", businessHoursId)

internal fun slackUserMappingResourceId(mappingId: Int): String =
    SlackUserMappings
        .selectAll()
        .where { SlackUserMappings.id eq mappingId }
        .singleOrNull()
        ?.get(SlackUserMappings.resourceId)
        ?.toString()
        ?: missingResourceId("Slack user mapping", mappingId)

private fun missingResourceId(resourceType: String, id: Int): Nothing =
    error("Missing resource_id for $resourceType internal id $id")
