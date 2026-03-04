// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.mcp.tools

import com.moneat.enterprise.mcp.protocol.ToolCallResult
import com.moneat.enterprise.mcp.protocol.ToolContent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val toolJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    prettyPrint = true
}

fun textResult(text: String): ToolCallResult {
    return ToolCallResult(content = listOf(ToolContent(text = text)))
}

fun errorResult(message: String): ToolCallResult {
    return ToolCallResult(
        content = listOf(ToolContent(text = message)),
        isError = true
    )
}

inline fun <reified T> jsonResult(data: T): ToolCallResult {
    val text = toolJson.encodeToString(data)
    return ToolCallResult(content = listOf(ToolContent(text = text)))
}
