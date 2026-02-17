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

package com.moneat.services

import com.moneat.config.EnvConfig
import com.moneat.models.AuthTokenResponse
import com.moneat.models.AuthTokens
import com.moneat.models.Memberships
import com.moneat.models.Organizations
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.and
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

private val logger = KotlinLogging.logger {}

class AuthTokenService {
    private val secureRandom = SecureRandom()
    
    companion object {
        // Supported permission scopes
        val VALID_SCOPES = setOf(
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
    }
    
    /**
     * Build a sentry-cli compatible token string.
     * Format: sntrys_{base64_payload}_{base64_secret}
     * Payload JSON: {"iat": <epoch>, "url": "<backend_url>", "region_url": "<backend_url>", "org": "<org_slug>"}
     */
    private fun buildSentryToken(orgSlug: String, secretBytes: ByteArray): String {
        val backendUrl = EnvConfig.get("BACKEND_URL", "https://api.moneat.io")
        val iat = System.currentTimeMillis() / 1000  // Use Long instead of Double to avoid scientific notation
        val payloadJson = """{"iat":$iat,"url":"$backendUrl","region_url":"$backendUrl","org":"$orgSlug"}"""
        val payloadEncoded = Base64.getEncoder().encodeToString(payloadJson.toByteArray())
        val secretEncoded = Base64.getEncoder().withoutPadding().encodeToString(secretBytes)
        return "${TOKEN_PREFIX}${payloadEncoded}_${secretEncoded}"
    }
    
    /**
     * Generate a new authentication token for a user
     */
    fun generateToken(userId: Int, name: String, scopes: List<String>, expiresInDays: Int? = null): AuthTokenResponse {
        // Validate scopes
        val invalidScopes = scopes.filter { it !in VALID_SCOPES }
        if (invalidScopes.isNotEmpty()) {
            throw IllegalArgumentException("Invalid scopes: ${invalidScopes.joinToString()}")
        }
        
        if (name.isBlank()) {
            throw IllegalArgumentException("Token name cannot be blank")
        }
        
        // Look up the user's org slug for the token payload
        val orgSlug = transaction {
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
        val expiresAt = expiresInDays?.let {
            Clock.System.now().plus(it * 24 * 60 * 60, DateTimeUnit.SECOND)
        }
        
        val createdAt = Clock.System.now()
        
        val tokenId = transaction {
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
        logger.warn { "!!! validateToken called: length=${token.length}, starts with sntrys_=${token.startsWith(TOKEN_PREFIX)}" }
        if (!token.startsWith(TOKEN_PREFIX)) {
            logger.warn { "!!! Token doesn't start with prefix" }
            return null
        }
        
        val tokenHash = hashToken(token)
        logger.warn { "!!! Token hash: $tokenHash" }
        
        return transaction {
            val tokenRow = AuthTokens.selectAll()
                .where { AuthTokens.token_hash eq tokenHash }
                .firstOrNull()
            if (tokenRow == null) {
                logger.warn { "!!! No token found in DB for hash: $tokenHash" }
                return@transaction null
            }
            logger.warn { "!!! Token found in DB!" }
            
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
    fun hasScope(tokenScopes: List<String>, requiredScope: String): Boolean {
        return requiredScope in tokenScopes
    }
    
    /**
     * Check if a token has any of the required scopes
     */
    fun hasAnyScope(tokenScopes: List<String>, requiredScopes: List<String>): Boolean {
        return requiredScopes.any { it in tokenScopes }
    }
    
    /**
     * List all tokens for a user
     */
    fun listUserTokens(userId: Int): List<AuthTokenResponse> {
        return transaction {
            AuthTokens.selectAll()
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
    fun revokeToken(userId: Int, tokenId: Int): Boolean {
        return transaction {
            // Verify the token belongs to the user before revoking
            val tokenExists = AuthTokens.selectAll()
                .where { (AuthTokens.id eq tokenId) and (AuthTokens.user_id eq userId) }
                .empty().not()
            if (!tokenExists) return@transaction false
            
            AuthTokens.deleteWhere { id eq tokenId } > 0
        }
    }
    
    /**
     * Update token name and/or scopes
     */
    fun updateToken(userId: Int, tokenId: Int, name: String?, scopes: List<String>?): Boolean {
        // Validate scopes if provided
        scopes?.let {
            val invalidScopes = it.filter { scope -> scope !in VALID_SCOPES }
            if (invalidScopes.isNotEmpty()) {
                throw IllegalArgumentException("Invalid scopes: ${invalidScopes.joinToString()}")
            }
        }
        
        return transaction {
            // Verify the token belongs to the user before updating
            val tokenExists = AuthTokens.selectAll()
                .where { (AuthTokens.id eq tokenId) and (AuthTokens.user_id eq userId) }
                .empty().not()
            if (!tokenExists) return@transaction false
            
            AuthTokens.update({ AuthTokens.id eq tokenId }) {
                name?.let { newName -> it[AuthTokens.name] = newName }
                scopes?.let { newScopes -> it[AuthTokens.scopes] = newScopes }
            }
            
            true
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
