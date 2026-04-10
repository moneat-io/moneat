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

package com.moneat.auth.services

import com.moneat.config.EnvConfig
import com.moneat.events.models.AuthTokenResponse
import com.moneat.shared.models.AuthTokens
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import kotlin.time.Clock
import com.moneat.utils.TimeConstants.MILLIS_PER_SECOND_LONG

class AuthTokenService {
    private val secureRandom = SecureRandom()

    companion object {
        // Supported permission scopes
        val VALID_SCOPES =
            setOf(
                "project:read",
                "project:write",
                "releases:read",
                "releases:write",
                "sourcemaps:read",
                "sourcemaps:write",
                "event:read",
                "org:read"
            )

        // sentry-cli compatible org auth token format: sntrys_{base64_payload}_{base64_secret}
        private const val TOKEN_PREFIX = "sntrys_"
        private const val TOKEN_LENGTH = 32 // 32 bytes = 256 bits
        private const val SECONDS_PER_DAY = 86_400
    }

    /**
     * Build a sentry-cli compatible token string.
     * Format: sntrys_{base64_payload}_{base64_secret}
     * Payload JSON: {"iat": <epoch>, "url": "<backend_url>", "region_url": "<backend_url>", "org": "<org_slug>"}
     */
    private fun buildSentryToken(
        orgSlug: String,
        secretBytes: ByteArray
    ): String {
        val backendUrl = EnvConfig.get("BACKEND_URL", "https://api.moneat.io")
        // Use Long instead of Double to avoid scientific notation
        val iat = System.currentTimeMillis() / MILLIS_PER_SECOND_LONG
        val payloadJson = """{"iat":$iat,"url":"$backendUrl","region_url":"$backendUrl","org":"$orgSlug"}"""
        val payloadEncoded = Base64.getEncoder().encodeToString(payloadJson.toByteArray())
        val secretEncoded = Base64.getEncoder().withoutPadding().encodeToString(secretBytes)
        return "${TOKEN_PREFIX}${payloadEncoded}_$secretEncoded"
    }

    /**
     * Generate a new authentication token for a user
     */
    fun generateToken(
        userId: Int,
        name: String,
        scopes: List<String>,
        expiresInDays: Int? = null
    ): AuthTokenResponse {
        // Validate scopes
        val invalidScopes = scopes.filter { it !in VALID_SCOPES }
        if (invalidScopes.isNotEmpty()) {
            throw IllegalArgumentException("Invalid scopes: ${invalidScopes.joinToString()}")
        }

        if (name.isBlank()) {
            throw IllegalArgumentException("Token name cannot be blank")
        }

        // Look up the user's org slug for the token payload
        val orgSlug =
            transaction {
                (Memberships innerJoin Organizations)
                    .selectAll()
                    .where { Memberships.user_id eq userId }
                    .firstOrNull()
                    ?.get(Organizations.slug)
            } ?: "default"

        // Generate secure random secret
        val tokenBytes = ByteArray(TOKEN_LENGTH)
        secureRandom.nextBytes(tokenBytes)
        val tokenValue = buildSentryToken(orgSlug, tokenBytes)

        // Hash the token for storage
        val tokenHash = hashToken(tokenValue)

        // Calculate expiration if specified
        val expiresAt =
            expiresInDays?.let {
                Clock.System.now().plus(it * SECONDS_PER_DAY, DateTimeUnit.SECOND)
            }

        val createdAt = Clock.System.now()

        val tokenId =
            transaction {
                AuthTokens.insert {
                    it[user_id] = userId
                    it[token_hash] = tokenHash
                    it[AuthTokens.name] = name
                    it[AuthTokens.scopes] = scopes
                    it[AuthTokens.expires_at] = expiresAt
                    it[AuthTokens.created_at] = createdAt
                    it[last_used_at] = null
                }[AuthTokens.id]
            }

        return AuthTokenResponse(
            id = tokenId,
            name = name,
            token = tokenValue, // Only returned on creation
            scopes = scopes,
            lastUsedAt = null,
            expiresAt = expiresAt?.toString(),
            createdAt = createdAt.toString()
        )
    }

    /**
     * Validate a token and return user ID and scopes if valid.
     */
    fun validateToken(token: String): TokenValidationResult? {
        if (!token.startsWith(TOKEN_PREFIX)) {
            return null
        }

        val tokenHash = hashToken(token)

        return transaction {
            val tokenRow =
                AuthTokens
                    .selectAll()
                    .where { AuthTokens.token_hash eq tokenHash }
                    .firstOrNull()
            if (tokenRow == null) {
                return@transaction null
            }

            // Check if token is expired
            val expiresAt = tokenRow[AuthTokens.expires_at]
            if (expiresAt != null && expiresAt < Clock.System.now()) {
                return@transaction null
            }

            val userId = tokenRow[AuthTokens.user_id]
            val scopes = tokenRow[AuthTokens.scopes]
            val tokenId = tokenRow[AuthTokens.id]

            // Update last_used_at timestamp
            AuthTokens.update({ AuthTokens.id eq tokenId }) {
                it[last_used_at] = Clock.System.now()
            }

            TokenValidationResult(userId, scopes, tokenId)
        }
    }

    /**
     * Check if a token has a required scope
     */
    fun hasScope(
        tokenScopes: List<String>,
        requiredScope: String
    ): Boolean {
        return requiredScope in tokenScopes
    }

    /**
     * Check if a token has any of the required scopes
     */
    fun hasAnyScope(
        tokenScopes: List<String>,
        requiredScopes: List<String>
    ): Boolean {
        return requiredScopes.any { it in tokenScopes }
    }

    /**
     * List all tokens for a user
     */
    fun listUserTokens(userId: Int): List<AuthTokenResponse> {
        return transaction {
            AuthTokens
                .selectAll()
                .where { AuthTokens.user_id eq userId }
                .orderBy(AuthTokens.created_at, SortOrder.DESC)
                .map { row ->
                    AuthTokenResponse(
                        id = row[AuthTokens.id],
                        name = row[AuthTokens.name],
                        token = null, // Never return the actual token
                        scopes = row[AuthTokens.scopes],
                        lastUsedAt = row[AuthTokens.last_used_at]?.toString(),
                        expiresAt = row[AuthTokens.expires_at]?.toString(),
                        createdAt = row[AuthTokens.created_at].toString()
                    )
                }
        }
    }

    /**
     * Revoke a token
     */
    fun revokeToken(
        userId: Int,
        tokenId: Int
    ): Boolean {
        return transaction {
            // Verify the token belongs to the user before revoking
            val tokenExists =
                AuthTokens
                    .selectAll()
                    .where { (AuthTokens.id eq tokenId) and (AuthTokens.user_id eq userId) }
                    .empty()
                    .not()
            if (!tokenExists) return@transaction false

            AuthTokens.deleteWhere { id eq tokenId } > 0
        }
    }

    /**
     * Update token name and/or scopes
     */
    fun updateToken(
        userId: Int,
        tokenId: Int,
        name: String?,
        scopes: List<String>?
    ): Boolean {
        // Validate scopes if provided
        scopes?.let {
            val invalidScopes = it.filter { scope -> scope !in VALID_SCOPES }
            if (invalidScopes.isNotEmpty()) {
                throw IllegalArgumentException("Invalid scopes: ${invalidScopes.joinToString()}")
            }
        }

        return transaction {
            // Verify the token belongs to the user before updating
            val tokenExists =
                AuthTokens
                    .selectAll()
                    .where { (AuthTokens.id eq tokenId) and (AuthTokens.user_id eq userId) }
                    .empty()
                    .not()
            if (!tokenExists) return@transaction false

            AuthTokens.update({ AuthTokens.id eq tokenId }) {
                name?.let { newName -> it[AuthTokens.name] = newName }
                scopes?.let { newScopes -> it[AuthTokens.scopes] = newScopes }
            }

            true
        }
    }

    /**
     * Delete expired auth tokens. Call periodically to prevent unbounded accumulation.
     * Only deletes tokens that have an expires_at set and are past expiry.
     */
    fun cleanupExpiredTokens(): Int {
        return transaction {
            val now = Clock.System.now()
            AuthTokens.deleteWhere {
                AuthTokens.expires_at.isNotNull() and (AuthTokens.expires_at less now)
            }
        }
    }

    /**
     * Hash a token using SHA256
     */
    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(token.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Result of token validation
 */
data class TokenValidationResult(
    val userId: Int,
    val scopes: List<String>,
    val tokenId: Int
)
