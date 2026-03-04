// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.models

/**
 * Context passed to every MCP tool/resource call.
 * Carries the authenticated org/user from the session.
 */
data class McpContext(
    val organizationId: Int,
    val userId: Int,
    val sessionId: String
)
