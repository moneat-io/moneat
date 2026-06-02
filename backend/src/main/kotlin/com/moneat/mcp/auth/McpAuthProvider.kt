// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package com.moneat.mcp.auth

import com.moneat.auth.services.AuthTokenService
import com.moneat.mcp.services.McpApiKeyService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.Base64

private val logger = KotlinLogging.logger {}

private const val SENTRY_TOKEN_PREFIX = "sntrys_"

private val authJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private val mcpApiKeyGrantedScopes = AuthTokenService.VALID_SCOPES - setOf(
    McpScopes.WORKFLOW_READ,
    McpScopes.WORKFLOW_WRITE,
    McpScopes.WORKFLOW_RUN,
)

/**
 * MCP authentication provider using existing API token validation.
 * Requires bearer tokens for MCP requests.
 */
object McpAuthProvider {

    private val authTokenService = AuthTokenService()
    private val mcpApiKeyService = McpApiKeyService()

    /**
     * Validates an MCP Authorization header and resolves a concrete organization.
     * Sentry-style token payload org slugs are authoritative. Legacy or
     * unparseable tokens fall back to single-organization membership only.
     */
    fun validateAuthorization(authorization: String?): McpAuthResult? {
        if (authorization.isNullOrBlank()) {
            logger.debug { "MCP auth: no token provided" }
            return null
        }

        if (!authorization.startsWith("Bearer ")) {
            logger.warn { "MCP auth: Authorization header must use Bearer scheme" }
            return null
        }

        val cleanToken = authorization.removePrefix("Bearer ").trim()
        if (cleanToken.isBlank()) {
            return null
        }

        val mcpValidation = mcpApiKeyService.validateKey(cleanToken)
        if (mcpValidation != null) {
            return McpAuthResult(
                organizationId = mcpValidation.organizationId,
                userId = mcpValidation.userId,
                tokenId = -mcpValidation.keyId,
                mcpApiKeyId = mcpValidation.keyId,
                scopes = mcpApiKeyGrantedScopes,
                allowedTools = mcpValidation.enabledTools,
                allowedResources = mcpValidation.enabledResources,
            )
        }

        val validation = authTokenService.validateToken(cleanToken)
        if (validation == null) {
            logger.warn { "MCP auth: invalid token" }
            return null
        }

        val orgId = resolveOrganizationId(cleanToken, validation.userId)
        if (orgId == null) {
            logger.warn { "MCP auth: failed org resolution for user ${validation.userId}" }
            return null
        }

        return McpAuthResult(
            organizationId = orgId,
            userId = validation.userId,
            tokenId = validation.tokenId,
            scopes = validation.scopes.toSet()
        )
    }

    private fun resolveOrganizationId(token: String, userId: Int): Int? {
        val tokenOrgSlug = parseOrgSlug(token)
        if (tokenOrgSlug != null) {
            return getMemberOrgIdBySlug(userId, tokenOrgSlug)
        }
        return getSingleMembershipOrgId(userId)
    }

    private fun parseOrgSlug(token: String): String? {
        if (!token.startsWith(SENTRY_TOKEN_PREFIX)) return null
        val payload = token.removePrefix(SENTRY_TOKEN_PREFIX).substringBefore('_')
        if (payload.isBlank()) return null
        return runCatching {
            val decoded = Base64.getDecoder().decode(payload).toString(Charsets.UTF_8)
            authJson.decodeFromString<SentryTokenPayload>(decoded).org?.takeIf { it.isNotBlank() }
        }.getOrElse { ex ->
            logger.debug(ex) { "MCP auth: could not parse sentry token payload" }
            null
        }
    }

    private fun getMemberOrgIdBySlug(userId: Int, orgSlug: String): Int? {
        return runCatching {
            transaction {
                (Memberships innerJoin Organizations)
                    .selectAll()
                    .where {
                        (Memberships.user_id eq userId) and (Organizations.slug eq orgSlug)
                    }
                    .firstOrNull()
                    ?.get(Memberships.organization_id)
            }
        }.getOrElse { ex ->
            logger.warn(ex) { "MCP auth: failed org slug lookup for user $userId" }
            null
        }
    }

    private fun getSingleMembershipOrgId(userId: Int): Int? {
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

@Serializable
private data class SentryTokenPayload(
    val org: String? = null
)

data class McpAuthResult(
    val organizationId: Int,
    val userId: Int,
    val tokenId: Int,
    val scopes: Set<String>,
    val mcpApiKeyId: Int? = null,
    val allowedTools: Set<String>? = null,
    val allowedResources: Set<String>? = null,
)
