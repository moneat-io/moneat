// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.commands

import com.moneat.enterprise.alertroutes.models.AlertRouteConditionOperator
import com.moneat.enterprise.alertroutes.models.AlertRouteContextField
import com.moneat.enterprise.alertroutes.models.AlertRouteDefinition
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingBehavior
import com.moneat.enterprise.alertroutes.models.AlertRouteGroupingWindowKind
import com.moneat.enterprise.alertroutes.models.AlertRouteIncidentMode
import com.moneat.enterprise.alertroutes.models.AlertRouteOwnershipSource
import com.moneat.enterprise.alertroutes.models.AlertRoutePagingMode
import com.moneat.enterprise.alertroutes.models.AlertRouteTargetKind
import kotlin.uuid.Uuid

data class AlertRouteActor(
    val organizationId: Int,
    val userId: Int,
    val origin: String,
)

data class AlertRouteConditionInput(
    val field: AlertRouteContextField,
    val operator: AlertRouteConditionOperator,
    val values: List<String> = emptyList(),
    val metadataKey: String? = null,
)

data class AlertRouteConditionGroupInput(
    val conditions: List<AlertRouteConditionInput>,
)

data class AlertRouteTargetInput(
    val kind: AlertRouteTargetKind,
    val escalationPolicyId: Uuid? = null,
    val teamId: Uuid? = null,
    val ownershipSource: AlertRouteOwnershipSource? = null,
)

data class AlertRoutePagingInput(
    val mode: AlertRoutePagingMode = AlertRoutePagingMode.NONE,
    val targets: List<AlertRouteTargetInput> = emptyList(),
)

data class AlertRouteGroupingInput(
    val behavior: AlertRouteGroupingBehavior = AlertRouteGroupingBehavior.DEFAULT,
    val keys: List<String> = emptyList(),
    val windowKind: AlertRouteGroupingWindowKind = AlertRouteGroupingWindowKind.ROLLING,
    val windowSeconds: Int = DEFAULT_GROUPING_WINDOW_SECONDS,
)

data class AlertRouteIncidentInput(
    val create: Boolean = false,
    val mode: AlertRouteIncidentMode = AlertRouteIncidentMode.TRIAGE,
    val titleTemplate: String? = null,
    val summaryTemplate: String? = null,
    val severity: String? = null,
    val incidentType: String? = null,
)

data class AlertRouteRecoveryInput(
    val cancelEscalations: Boolean = false,
    val declineUnacceptedTriage: Boolean = false,
    val graceSeconds: Int = 0,
)

/** Full desired state of one alert route. Updates replace the whole specification. */
data class AlertRouteSpecification(
    val key: String,
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val conditionGroups: List<AlertRouteConditionGroupInput> = emptyList(),
    val paging: AlertRoutePagingInput = AlertRoutePagingInput(),
    val grouping: AlertRouteGroupingInput = AlertRouteGroupingInput(),
    val incident: AlertRouteIncidentInput = AlertRouteIncidentInput(),
    val recovery: AlertRouteRecoveryInput = AlertRouteRecoveryInput(),
)

enum class AlertRouteCommandType(val wire: String) {
    CREATE("CREATE"),
    UPDATE("UPDATE"),
    DELETE("DELETE"),
    REORDER("REORDER"),
}

sealed interface AlertRouteCommand {
    val commandKey: String
    val actor: AlertRouteActor
    val type: AlertRouteCommandType
}

data class CreateAlertRouteCommand(
    override val commandKey: String,
    override val actor: AlertRouteActor,
    val specification: AlertRouteSpecification,
) : AlertRouteCommand {
    override val type = AlertRouteCommandType.CREATE
}

data class UpdateAlertRouteCommand(
    override val commandKey: String,
    override val actor: AlertRouteActor,
    val routeId: Uuid,
    val expectedRevision: Int,
    val specification: AlertRouteSpecification,
) : AlertRouteCommand {
    override val type = AlertRouteCommandType.UPDATE
}

data class DeleteAlertRouteCommand(
    override val commandKey: String,
    override val actor: AlertRouteActor,
    val routeId: Uuid,
    val expectedRevision: Int,
) : AlertRouteCommand {
    override val type = AlertRouteCommandType.DELETE
}

data class AlertRouteOrderEntry(
    val routeId: Uuid,
    val expectedRevision: Int,
)

data class ReorderAlertRoutesCommand(
    override val commandKey: String,
    override val actor: AlertRouteActor,
    val orderedRoutes: List<AlertRouteOrderEntry>,
) : AlertRouteCommand {
    override val type = AlertRouteCommandType.REORDER
}

data class AlertRouteCommandResult(
    val routes: List<AlertRouteDefinition>,
    val replayed: Boolean,
) {
    val route: AlertRouteDefinition? get() = routes.firstOrNull()
}

const val DEFAULT_GROUPING_WINDOW_SECONDS = 900
