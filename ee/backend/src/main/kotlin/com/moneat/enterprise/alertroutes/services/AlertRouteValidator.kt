// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.alerts.models.AlertPriority
import com.moneat.alerts.models.IncidentSeverity
import com.moneat.enterprise.alertroutes.commands.AlertRouteCommand
import com.moneat.enterprise.alertroutes.commands.AlertRouteConditionInput
import com.moneat.enterprise.alertroutes.commands.AlertRouteSpecification
import com.moneat.enterprise.alertroutes.commands.AlertRouteTargetInput
import com.moneat.enterprise.alertroutes.commands.CreateAlertRouteCommand
import com.moneat.enterprise.alertroutes.commands.DeleteAlertRouteCommand
import com.moneat.enterprise.alertroutes.commands.ReorderAlertRoutesCommand
import com.moneat.enterprise.alertroutes.commands.UpdateAlertRouteCommand
import com.moneat.enterprise.alertroutes.context.AlertRouteMetadataPolicy
import com.moneat.enterprise.alertroutes.evaluation.AlertRouteTemplateRenderer
import com.moneat.enterprise.alertroutes.models.AlertRouteConditionOperator
import com.moneat.enterprise.alertroutes.models.AlertRouteContextField
import com.moneat.enterprise.alertroutes.models.AlertRouteIncidentMode
import com.moneat.enterprise.alertroutes.models.AlertRoutePagingMode
import com.moneat.enterprise.alertroutes.models.AlertRouteTargetKind

/** Structural validation applied before any alert-route mutation touches storage. */
internal object AlertRouteValidator {
    private val KEY_PATTERN = Regex("^[a-z0-9][a-z0-9_-]*$")
    private const val MAX_KEY_LENGTH = 160
    private const val MAX_NAME_LENGTH = 200
    private const val MAX_DESCRIPTION_LENGTH = 2_000
    private const val MAX_CONDITION_GROUPS = 20
    private const val MAX_CONDITIONS_PER_GROUP = 20
    private const val MAX_CONDITION_VALUES = 50
    private const val MAX_GROUPING_KEYS = 8
    private const val MAX_TARGETS = 20
    private const val MAX_TEMPLATE_LENGTH = 2_000
    private const val MAX_INCIDENT_TYPE_LENGTH = 120
    private const val MAX_WINDOW_SECONDS = 86_400
    private const val MAX_GRACE_SECONDS = 86_400
    private val REFERENCE_SCOPES = setOf("alert", "metadata", "ownership", "episode")

    fun validate(command: AlertRouteCommand) {
        when (command) {
            is CreateAlertRouteCommand -> validateSpecification(command.specification)
            is UpdateAlertRouteCommand -> {
                validateExpectedRevision(command.expectedRevision)
                validateSpecification(command.specification)
            }
            is DeleteAlertRouteCommand -> validateExpectedRevision(command.expectedRevision)
            is ReorderAlertRoutesCommand -> {
                require(command.orderedRoutes.isNotEmpty()) { "Reorder requires at least one alert route" }
                require(command.orderedRoutes.map { it.routeId }.distinct().size == command.orderedRoutes.size) {
                    "Reorder must not repeat an alert route"
                }
                command.orderedRoutes.forEach { validateExpectedRevision(it.expectedRevision) }
            }
        }
    }

    fun validateSpecification(specification: AlertRouteSpecification) {
        val key = specification.key.trim()
        require(key.isNotEmpty() && key.length <= MAX_KEY_LENGTH) { "Alert route key is required" }
        require(KEY_PATTERN.matches(key)) {
            "Alert route key may only contain lowercase letters, digits, hyphens, and underscores"
        }
        val name = specification.name.trim()
        require(name.isNotEmpty() && name.length <= MAX_NAME_LENGTH) { "Alert route name is required" }
        require((specification.description?.length ?: 0) <= MAX_DESCRIPTION_LENGTH) {
            "Alert route description is too long"
        }
        validateConditions(specification)
        validatePaging(specification)
        validateGrouping(specification)
        validateIncident(specification)
        validateRecovery(specification)
    }

    private fun validateExpectedRevision(expectedRevision: Int) {
        require(expectedRevision > 0) { "Expected revision must be positive" }
    }

    private fun validateConditions(specification: AlertRouteSpecification) {
        require(specification.conditionGroups.size <= MAX_CONDITION_GROUPS) {
            "Alert route has too many condition groups"
        }
        specification.conditionGroups.forEach { group ->
            require(group.conditions.isNotEmpty()) { "Every condition group needs at least one condition" }
            require(group.conditions.size <= MAX_CONDITIONS_PER_GROUP) {
                "Condition group has too many conditions"
            }
            group.conditions.forEach(::validateCondition)
        }
    }

    private fun validateCondition(condition: AlertRouteConditionInput) {
        if (condition.field == AlertRouteContextField.METADATA) {
            val key = condition.metadataKey?.trim()
            require(!key.isNullOrEmpty()) { "Metadata conditions require a metadata key" }
            require(key in AlertRouteMetadataPolicy.APPROVED_KEYS) { "Metadata key $key is not approved for routing" }
        } else {
            require(condition.metadataKey == null) {
                "Metadata key is only valid on metadata conditions"
            }
        }
        require(!condition.operator.priorityOnly || condition.field == AlertRouteContextField.PRIORITY) {
            "Operator ${condition.operator.wire} is only valid on the priority field"
        }
        if (condition.operator.requiresValues) {
            require(condition.values.isNotEmpty()) { "Operator ${condition.operator.wire} requires at least one value" }
            require(condition.values.size <= MAX_CONDITION_VALUES) { "Condition has too many comparison values" }
            require(condition.values.none { it.isBlank() }) { "Condition values cannot be blank" }
        } else {
            require(condition.values.isEmpty()) { "Operator ${condition.operator.wire} does not take values" }
        }
        if (condition.operator == AlertRouteConditionOperator.AT_LEAST_AS_SEVERE_AS) {
            condition.values.forEach { value ->
                require(AlertPriority.fromString(value) != null) {
                    "Invalid alert priority: $value"
                }
            }
        }
    }

    private fun validatePaging(specification: AlertRouteSpecification) {
        val paging = specification.paging
        require(paging.targets.size <= MAX_TARGETS) { "Alert route has too many paging targets" }
        if (paging.mode == AlertRoutePagingMode.NONE) return
        require(paging.targets.isNotEmpty()) { "Paging mode ${paging.mode.wire} requires at least one target" }
        paging.targets.forEach(::validateTarget)
    }

    private fun validateTarget(target: AlertRouteTargetInput) {
        when (target.kind) {
            AlertRouteTargetKind.ESCALATION_POLICY ->
                require(target.escalationPolicyId != null) { "Escalation policy targets require an escalation policy" }
            AlertRouteTargetKind.TEAM ->
                require(target.teamId != null) { "Team targets require a team" }
            AlertRouteTargetKind.OWNERSHIP_DERIVED ->
                require(target.ownershipSource != null) { "Ownership targets require an ownership source" }
        }
    }

    private fun validateGrouping(specification: AlertRouteSpecification) {
        val grouping = specification.grouping
        require(grouping.keys.size <= MAX_GROUPING_KEYS) { "Alert route has too many grouping keys" }
        require(grouping.keys.all { it == canonicalReference(it) }) {
            "Grouping keys must use canonical lowercase references without surrounding whitespace"
        }
        require(grouping.keys.map(::canonicalReference).distinct().size == grouping.keys.size) {
            "Grouping keys must be unique"
        }
        grouping.keys.forEach(::validateReference)
        require(grouping.windowSeconds in 1..MAX_WINDOW_SECONDS) {
            "Grouping window must be between 1 and $MAX_WINDOW_SECONDS seconds"
        }
    }

    private fun canonicalReference(reference: String): String = reference.trim().lowercase()

    private fun validateIncident(specification: AlertRouteSpecification) {
        val incident = specification.incident
        require((incident.titleTemplate?.length ?: 0) <= MAX_TEMPLATE_LENGTH) { "Incident title template is too long" }
        require((incident.summaryTemplate?.length ?: 0) <= MAX_TEMPLATE_LENGTH) {
            "Incident summary template is too long"
        }
        require((incident.incidentType?.length ?: 0) <= MAX_INCIDENT_TYPE_LENGTH) {
            "Incident type is too long"
        }
        incident.severity?.let {
            require(IncidentSeverity.fromString(it) != null) { "Invalid incident severity: $it" }
        }
        if (incident.create && incident.mode == AlertRouteIncidentMode.ACTIVE) {
            require(!incident.severity.isNullOrBlank()) { "Incident creation requires a severity" }
        }
        templateReferences(incident.titleTemplate, incident.summaryTemplate).forEach(::validateReference)
    }

    private fun validateRecovery(specification: AlertRouteSpecification) {
        require(specification.recovery.graceSeconds in 0..MAX_GRACE_SECONDS) {
            "Recovery grace must be between 0 and $MAX_GRACE_SECONDS seconds"
        }
    }

    private fun templateReferences(vararg templates: String?): List<String> =
        templates.flatMap { template -> AlertRouteTemplateRenderer.references(template) }

    private fun validateReference(reference: String) {
        val trimmed = reference.trim()
        require(trimmed.isNotEmpty()) { "Context reference cannot be blank" }
        val parts = trimmed.split('.', limit = 2)
        val scope = parts.first().lowercase()
        require(scope in REFERENCE_SCOPES) { "Unknown context reference scope: $trimmed" }
        val leaf = parts.getOrNull(1)
        require(!leaf.isNullOrBlank()) { "Context reference $trimmed is missing a field" }
        when (scope) {
            "metadata" ->
                require(leaf in AlertRouteMetadataPolicy.APPROVED_KEYS) {
                    "Metadata key $leaf is not approved for routing"
                }
            "alert" ->
                require(
                    AlertRouteContextField.fromWire(leaf)?.takeIf { it != AlertRouteContextField.METADATA } != null,
                ) { "Unknown alert field: $leaf" }
            "ownership" ->
                require(leaf.lowercase() in OWNERSHIP_LEAVES) { "Unknown ownership field: $leaf" }
            else ->
                require(leaf.lowercase() in EPISODE_LEAVES) { "Unknown episode field: $leaf" }
        }
    }

    private val OWNERSHIP_LEAVES = setOf("service", "team", "catalog", "catalog_resource")
    private val EPISODE_LEAVES = setOf("id", "resource_id", "key", "sequence", "status")
}
