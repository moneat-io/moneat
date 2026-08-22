// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.commands

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.util.HexFormat

internal object IncidentCommandFingerprint {
    private val json = Json

    fun of(command: IncidentCommand): String {
        val encoded = json.encodeToString(parts(command))
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }

    private fun parts(command: IncidentCommand): List<String?> =
        commonParts(command) +
            when (command) {
                is DeclareIncidentCommand ->
                    listOf(
                        command.title,
                        command.description,
                        command.summary,
                        command.severity,
                        command.mode.wire,
                        command.visibility.wire,
                        command.incidentType,
                        command.initialStatus.wire,
                        command.onCallAlertId?.toString(),
                    )
                is AcceptIncidentCommand -> listOf(command.incidentId.toString())
                is MergeIncidentCommand ->
                    listOf(command.incidentId.toString(), command.sourceIncidentId.toString())
                is UpdateIncidentCommand ->
                    listOf(
                        command.incidentId.toString(),
                        command.title,
                        command.description,
                        command.summary,
                        command.severity,
                        command.mode?.wire,
                        command.visibility?.wire,
                        command.incidentType,
                    )
                is TransitionIncidentCommand ->
                    listOf(command.incidentId.toString(), command.targetStatus.wire, command.note)
                is AssignIncidentRoleCommand ->
                    listOf(command.incidentId.toString(), command.role, command.assigneeUserId.toString())
                is AddIncidentActionCommand ->
                    listOf(command.incidentId.toString(), command.title, command.assigneeUserId?.toString())
                is AddIncidentTimelineEventCommand ->
                    listOf(command.incidentId.toString(), command.eventType, canonicalDetails(command.details))
                is LinkOnCallAlertCommand ->
                    listOf(command.incidentId.toString(), command.alertId.toString())
                is ResolveIncidentCommand -> listOf(command.incidentId.toString(), command.note)
                is CancelIncidentCommand -> listOf(command.incidentId.toString(), command.reason)
                is ReopenIncidentCommand -> listOf(command.incidentId.toString(), command.reason)
            }

    private fun commonParts(command: IncidentCommand): List<String?> =
        listOf(command.type.wire, command.expectedVersion?.toString())

    private fun canonicalDetails(details: Map<String, JsonElement>): String =
        json.encodeToString<JsonElement>(
            JsonObject(details.toSortedMap().mapValues { (_, value) -> value.canonical() }),
        )

    private fun JsonElement.canonical(): JsonElement =
        when (this) {
            is JsonObject -> JsonObject(toSortedMap().mapValues { (_, value) -> value.canonical() })
            is JsonArray -> JsonArray(map { element -> element.canonical() })
            else -> this
        }
}
