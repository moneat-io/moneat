// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.workflows.services

import com.moneat.workflows.WorkflowConnectionReference
import com.moneat.workflows.WorkflowConnectionVault
import com.moneat.workflows.WorkflowPremiumConnectorBridge
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

class PremiumConnectorService(
    private val vault: WorkflowConnectionVault
) : WorkflowPremiumConnectorBridge {
    override suspend fun executeConnectorAction(
        organizationId: Int,
        actionName: String,
        params: Map<String, String>,
        actorUserId: Int?
    ): Map<String, JsonElement> {
        val reference = params.connectionReference()
            ?: throw IllegalArgumentException("Premium connector action requires connection_id or connection_group_id")
        val connection = vault.resolveSecret(organizationId, reference, params)
            ?: throw IllegalArgumentException("Workflow connection not found for connector action")
        return mapOf(
            "connector_action" to JsonPrimitive(actionName),
            "connection_id" to JsonPrimitive(connection.connectionId),
            "connection_type" to JsonPrimitive(connection.type),
            "title" to JsonPrimitive(params["title"].orEmpty()),
            "actor_user_id" to JsonPrimitive(actorUserId ?: 0),
            "prepared" to JsonPrimitive(true)
        )
    }

    private fun Map<String, String>.connectionReference(): WorkflowConnectionReference? =
        this["connection_id"]
            ?.toIntOrNull()
            ?.let { WorkflowConnectionReference.Connection(it) }
            ?: this["connection_group_id"]
                ?.toIntOrNull()
                ?.let { WorkflowConnectionReference.Group(it) }
}
