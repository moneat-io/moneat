// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.auth

import com.moneat.auth.services.AuthTokenService
import com.moneat.shared.models.Memberships
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

/**
 * MCP authentication provider using existing API token validation.
 * Validates bearer tokens or query-parameter tokens for MCP sessions.
 */
object McpAuthProvider {

    private val authTokenService = AuthTokenService()

    /**
     * Validates an MCP token and returns (organizationId, userId) or null.
     * Accepts either a bearer token or a raw API token string.
     */
    fun validate(token: String?): McpAuthResult? {
        if (token.isNullOrBlank()) {
            logger.debug { "MCP auth: no token provided" }
            return null
        }

        val cleanToken = token.removePrefix("Bearer ").trim()
        if (cleanToken.isBlank()) {
            return null
        }

        val validation = authTokenService.validateToken(cleanToken)
        if (validation == null) {
            logger.warn { "MCP auth: invalid token" }
            return null
        }

        val orgId = getOrgId(validation.userId)
        if (orgId == null) {
            logger.warn { "MCP auth: no org for user ${validation.userId}" }
            return null
        }

        return McpAuthResult(
            organizationId = orgId,
            userId = validation.userId
        )
    }

    private fun getOrgId(userId: Int): Int? {
        return runCatching {
            transaction {
                val orgIds = Memberships
                    .selectAll()
                    .where { Memberships.user_id eq userId }
                    .map { it[Memberships.organization_id] }
                    .distinct()

                orgIds.singleOrNull() // reject ambiguous multi-org mapping
            }
        }.getOrElse { ex ->
            logger.warn(ex) { "MCP auth: failed org lookup for user $userId" }
            null
        }
    }
}

data class McpAuthResult(
    val organizationId: Int,
    val userId: Int
)
