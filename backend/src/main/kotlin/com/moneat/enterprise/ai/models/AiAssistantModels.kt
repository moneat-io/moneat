// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.ai.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AiAssistantStreamRequest(
    val message: String,
    val conversationId: String? = null,
    val projectId: Long? = null,
)

@Serializable
data class AiAssistantConfirmRequest(
    val requestId: String,
    val approve: Boolean = true,
)

@Serializable
data class AiAssistantConfirmResponse(
    val conversationId: String,
    val requestId: String,
    val approved: Boolean,
    val tool: String,
    val toolSummary: String,
    val response: String,
    val nextRequestId: String? = null,
)

@Serializable
data class AssistantToolInvokingEvent(
    val type: String = "tool_invoking",
    val tool: String,
    val args: JsonObject,
)

@Serializable
data class AssistantToolResultEvent(
    val type: String = "tool_result",
    val tool: String,
    val summary: String,
    val isError: Boolean = false,
)

@Serializable
data class AssistantConfirmationNeededEvent(
    val type: String = "confirmation_needed",
    val requestId: String,
    val conversationId: String,
    val tool: String,
    val args: JsonObject,
)

@Serializable
data class AssistantResponseEvent(
    val type: String = "response",
    val content: String,
)

@Serializable
data class AssistantErrorEvent(
    val type: String = "error",
    val error: String,
)

@Serializable
data class AssistantDoneEvent(
    val type: String = "done",
    val conversationId: String,
)
