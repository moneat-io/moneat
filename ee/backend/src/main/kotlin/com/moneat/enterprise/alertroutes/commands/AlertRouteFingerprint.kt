// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.commands

import java.security.MessageDigest
import java.util.HexFormat

/** Stable hash of a command's request payload, used to distinguish replays from key reuse. */
internal object AlertRouteFingerprint {
    private const val SEPARATOR = ""

    fun of(command: AlertRouteCommand): String {
        val parts = listOf(command.type.wire) + commandParts(command)
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(parts.joinToString(SEPARATOR) { it.orEmpty() }.toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }

    private fun commandParts(command: AlertRouteCommand): List<String?> =
        when (command) {
            is CreateAlertRouteCommand -> specificationParts(command.specification)
            is UpdateAlertRouteCommand ->
                listOf(command.routeId.toString(), command.expectedRevision.toString()) +
                    specificationParts(command.specification)
            is DeleteAlertRouteCommand -> listOf(command.routeId.toString(), command.expectedRevision.toString())
            is ReorderAlertRoutesCommand ->
                command.orderedRoutes.flatMap { listOf(it.routeId.toString(), it.expectedRevision.toString()) }
        }

    private fun specificationParts(specification: AlertRouteSpecification): List<String?> =
        listOf(
            specification.key,
            specification.name,
            specification.description,
            specification.enabled.toString(),
            conditionParts(specification),
            pagingParts(specification),
            groupingParts(specification),
            incidentParts(specification),
            recoveryParts(specification),
        )

    private fun conditionParts(specification: AlertRouteSpecification): String =
        specification.conditionGroups.joinToString(";") { group ->
            group.conditions.joinToString(",") { condition ->
                listOf(
                    condition.field.wire,
                    condition.metadataKey.orEmpty(),
                    condition.operator.wire,
                    condition.values.joinToString("+"),
                ).joinToString(":")
            }
        }

    private fun pagingParts(specification: AlertRouteSpecification): String =
        specification.paging.mode.wire +
            specification.paging.targets.joinToString(",") { target ->
                listOf(
                    target.kind.wire,
                    target.escalationPolicyId?.toString().orEmpty(),
                    target.teamId?.toString().orEmpty(),
                    target.ownershipSource?.wire.orEmpty(),
                ).joinToString(":")
            }

    private fun groupingParts(specification: AlertRouteSpecification): String =
        listOf(
            specification.grouping.behavior.wire,
            specification.grouping.keys.joinToString("+"),
            specification.grouping.windowKind.wire,
            specification.grouping.windowSeconds.toString(),
        ).joinToString(":")

    private fun incidentParts(specification: AlertRouteSpecification): String =
        listOf(
            specification.incident.create.toString(),
            specification.incident.mode.wire,
            specification.incident.titleTemplate.orEmpty(),
            specification.incident.summaryTemplate.orEmpty(),
            specification.incident.severity.orEmpty(),
            specification.incident.incidentType.orEmpty(),
        ).joinToString(":")

    private fun recoveryParts(specification: AlertRouteSpecification): String =
        listOf(
            specification.recovery.cancelEscalations.toString(),
            specification.recovery.declineUnacceptedTriage.toString(),
            specification.recovery.graceSeconds.toString(),
        ).joinToString(":")
}
