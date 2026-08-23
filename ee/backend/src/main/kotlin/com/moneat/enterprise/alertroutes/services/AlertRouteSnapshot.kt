// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.enterprise.alertroutes.commands.AlertRouteCommand
import com.moneat.enterprise.alertroutes.commands.AlertRouteSpecification
import com.moneat.enterprise.alertroutes.commands.CreateAlertRouteCommand
import com.moneat.enterprise.alertroutes.commands.DeleteAlertRouteCommand
import com.moneat.enterprise.alertroutes.commands.ReorderAlertRoutesCommand
import com.moneat.enterprise.alertroutes.commands.UpdateAlertRouteCommand
import com.moneat.enterprise.alertroutes.models.AlertRouteChangeType
import com.moneat.enterprise.alertroutes.models.AlertRouteDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Builds the audited JSON snapshot stored with every alert-route revision. */
internal object AlertRouteSnapshot {
    fun of(
        command: AlertRouteCommand,
        route: AlertRouteDefinition,
        changeType: AlertRouteChangeType,
    ): Map<String, JsonElement> =
        mapOf(
            "changeType" to JsonPrimitive(changeType.wire),
            "revision" to JsonPrimitive(route.revision),
            "origin" to JsonPrimitive(command.actor.origin),
            "route" to route(route),
        ) + commandDetails(command)

    private fun route(route: AlertRouteDefinition): JsonElement =
        JsonObject(
            mapOf(
                "id" to JsonPrimitive(route.resourceId.toString()),
                "key" to JsonPrimitive(route.key),
                "name" to JsonPrimitive(route.name),
                "description" to (route.description?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull),
                "enabled" to JsonPrimitive(route.enabled),
                "position" to JsonPrimitive(route.position),
                "revision" to JsonPrimitive(route.revision),
                "conditionGroups" to
                    JsonArray(
                        route.conditionGroups.map { group ->
                            JsonArray(
                                group.conditions.map { condition ->
                                    JsonObject(
                                        mapOf(
                                            "field" to JsonPrimitive(condition.field.wire),
                                            "metadataKey" to
                                                (condition.metadataKey?.let(::JsonPrimitive)
                                                    ?: kotlinx.serialization.json.JsonNull),
                                            "operator" to JsonPrimitive(condition.operator.wire),
                                            "values" to JsonArray(condition.values.map(::JsonPrimitive)),
                                        ),
                                    )
                                },
                            )
                        },
                    ),
                "paging" to
                    JsonObject(
                        mapOf(
                            "mode" to JsonPrimitive(route.paging.mode.wire),
                            "targets" to
                                JsonArray(
                                    route.paging.targets.map { target ->
                                        JsonObject(
                                            mapOf(
                                                "kind" to JsonPrimitive(target.kind.wire),
                                                "escalationPolicyId" to
                                                    (target.escalationPolicyResourceId?.let {
                                                        JsonPrimitive(it.toString())
                                                    } ?: kotlinx.serialization.json.JsonNull),
                                                "teamId" to
                                                    (target.teamResourceId?.let { JsonPrimitive(it.toString()) }
                                                        ?: kotlinx.serialization.json.JsonNull),
                                                "ownershipSource" to
                                                    (target.ownershipSource?.let { JsonPrimitive(it.wire) }
                                                        ?: kotlinx.serialization.json.JsonNull),
                                            ),
                                        )
                                    },
                                ),
                        ),
                    ),
                "grouping" to
                    JsonObject(
                        mapOf(
                            "behavior" to JsonPrimitive(route.grouping.behavior.wire),
                            "keys" to JsonArray(route.grouping.keys.map(::JsonPrimitive)),
                            "windowKind" to JsonPrimitive(route.grouping.windowKind.wire),
                            "windowSeconds" to JsonPrimitive(route.grouping.windowSeconds),
                        ),
                    ),
                "incident" to
                    JsonObject(
                        mapOf(
                            "create" to JsonPrimitive(route.incident.create),
                            "mode" to JsonPrimitive(route.incident.mode.wire),
                            "titleTemplate" to
                                (route.incident.titleTemplate?.let(::JsonPrimitive)
                                    ?: kotlinx.serialization.json.JsonNull),
                            "summaryTemplate" to
                                (route.incident.summaryTemplate?.let(::JsonPrimitive)
                                    ?: kotlinx.serialization.json.JsonNull),
                            "severity" to
                                (route.incident.severity?.let(::JsonPrimitive)
                                    ?: kotlinx.serialization.json.JsonNull),
                            "incidentType" to
                                (route.incident.incidentType?.let(::JsonPrimitive)
                                    ?: kotlinx.serialization.json.JsonNull),
                        ),
                    ),
                "recovery" to
                    JsonObject(
                        mapOf(
                            "cancelEscalations" to JsonPrimitive(route.recovery.cancelEscalations),
                            "declineUnacceptedTriage" to
                                JsonPrimitive(route.recovery.declineUnacceptedTriage),
                            "graceSeconds" to JsonPrimitive(route.recovery.graceSeconds),
                        ),
                    ),
            ),
        )

    private fun commandDetails(command: AlertRouteCommand): Map<String, JsonElement> =
        when (command) {
            is CreateAlertRouteCommand -> mapOf("specification" to specification(command.specification))
            is UpdateAlertRouteCommand ->
                mapOf(
                    "routeId" to JsonPrimitive(command.routeId.toString()),
                    "expectedRevision" to JsonPrimitive(command.expectedRevision),
                    "specification" to specification(command.specification),
                )
            is DeleteAlertRouteCommand ->
                mapOf(
                    "routeId" to JsonPrimitive(command.routeId.toString()),
                    "expectedRevision" to JsonPrimitive(command.expectedRevision),
                )
            is ReorderAlertRoutesCommand ->
                mapOf(
                    "order" to
                        JsonArray(
                            command.orderedRoutes.map { entry ->
                                JsonObject(
                                    mapOf(
                                        "routeId" to JsonPrimitive(entry.routeId.toString()),
                                        "expectedRevision" to JsonPrimitive(entry.expectedRevision),
                                    ),
                                )
                            },
                        ),
                )
        }

    private fun specification(specification: AlertRouteSpecification): JsonElement =
        JsonObject(
            mapOf(
                "key" to JsonPrimitive(specification.key),
                "name" to JsonPrimitive(specification.name),
                "description" to
                    (specification.description?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull),
                "enabled" to JsonPrimitive(specification.enabled),
                "conditionGroups" to conditionGroups(specification),
                "paging" to paging(specification),
                "grouping" to grouping(specification),
                "incident" to incident(specification),
                "recovery" to recovery(specification),
            ),
        )

    private fun conditionGroups(specification: AlertRouteSpecification): JsonElement =
        JsonArray(
            specification.conditionGroups.map { group ->
                JsonArray(
                    group.conditions.map { condition ->
                        JsonObject(
                            mapOf(
                                "field" to JsonPrimitive(condition.field.wire),
                                "metadataKey" to
                                    (condition.metadataKey?.let(::JsonPrimitive)
                                        ?: kotlinx.serialization.json.JsonNull),
                                "operator" to JsonPrimitive(condition.operator.wire),
                                "values" to JsonArray(condition.values.map(::JsonPrimitive)),
                            ),
                        )
                    },
                )
            },
        )

    private fun paging(specification: AlertRouteSpecification): JsonElement =
        JsonObject(
            mapOf(
                "mode" to JsonPrimitive(specification.paging.mode.wire),
                "targets" to
                    JsonArray(
                        specification.paging.targets.map { target ->
                            JsonObject(
                                mapOf(
                                    "kind" to JsonPrimitive(target.kind.wire),
                                    "escalationPolicyId" to
                                        (target.escalationPolicyId?.let { JsonPrimitive(it.toString()) }
                                            ?: kotlinx.serialization.json.JsonNull),
                                    "teamId" to
                                        (target.teamId?.let { JsonPrimitive(it.toString()) }
                                            ?: kotlinx.serialization.json.JsonNull),
                                    "ownershipSource" to
                                        (target.ownershipSource?.let { JsonPrimitive(it.wire) }
                                            ?: kotlinx.serialization.json.JsonNull),
                                ),
                            )
                        },
                    ),
            ),
        )

    private fun grouping(specification: AlertRouteSpecification): JsonElement =
        JsonObject(
            mapOf(
                "behavior" to JsonPrimitive(specification.grouping.behavior.wire),
                "keys" to JsonArray(specification.grouping.keys.map(::JsonPrimitive)),
                "windowKind" to JsonPrimitive(specification.grouping.windowKind.wire),
                "windowSeconds" to JsonPrimitive(specification.grouping.windowSeconds),
            ),
        )

    private fun incident(specification: AlertRouteSpecification): JsonElement =
        JsonObject(
            mapOf(
                "create" to JsonPrimitive(specification.incident.create),
                "mode" to JsonPrimitive(specification.incident.mode.wire),
                "titleTemplate" to
                    (specification.incident.titleTemplate?.let(::JsonPrimitive)
                        ?: kotlinx.serialization.json.JsonNull),
                "summaryTemplate" to
                    (specification.incident.summaryTemplate?.let(::JsonPrimitive)
                        ?: kotlinx.serialization.json.JsonNull),
                "severity" to
                    (specification.incident.severity?.let(::JsonPrimitive)
                        ?: kotlinx.serialization.json.JsonNull),
                "incidentType" to
                    (specification.incident.incidentType?.let(::JsonPrimitive)
                        ?: kotlinx.serialization.json.JsonNull),
            ),
        )

    private fun recovery(specification: AlertRouteSpecification): JsonElement =
        JsonObject(
            mapOf(
                "cancelEscalations" to JsonPrimitive(specification.recovery.cancelEscalations),
                "declineUnacceptedTriage" to
                    JsonPrimitive(specification.recovery.declineUnacceptedTriage),
                "graceSeconds" to JsonPrimitive(specification.recovery.graceSeconds),
            ),
        )
}
