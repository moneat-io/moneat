// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.workflows.services

import com.moneat.workflows.WorkflowConnectionReference
import com.moneat.workflows.WorkflowConnectionVault
import com.moneat.workflows.WorkflowPremiumConnectorBridge
import com.moneat.shared.services.userResourceId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
        val reference = params.connectionReference(organizationId)
            ?: throw IllegalArgumentException("Premium connector action requires connection_id or connection_group_id")
        val connection = vault.resolveSecret(organizationId, reference, params)
            ?: throw IllegalArgumentException("Workflow connection not found for connector action")
        return mapOf(
            "connector_action" to JsonPrimitive(actionName),
            "connection_id" to JsonPrimitive(connection.resourceId),
            "connection_type" to JsonPrimitive(connection.type),
            "title" to JsonPrimitive(params["title"].orEmpty()),
            "actor_user_id" to (actorUserId?.let { JsonPrimitive(userResourceId(it)) } ?: JsonNull),
            "prepared" to JsonPrimitive(true)
        )
    }

    private suspend fun Map<String, String>.connectionReference(organizationId: Int): WorkflowConnectionReference? {
        this["connection_id"]?.let { connectionResourceId ->
            val connectionId = vault.resolveConnectionId(organizationId, connectionResourceId)
                ?: throw IllegalArgumentException("Workflow connection not found for connector action")
            return WorkflowConnectionReference.Connection(connectionId)
        }
        this["connection_group_id"]?.let { groupResourceId ->
            val groupId = vault.resolveGroupId(organizationId, groupResourceId)
                ?: throw IllegalArgumentException("Workflow connection group not found for connector action")
            return WorkflowConnectionReference.Group(groupId)
        }
        return null
    }
}
