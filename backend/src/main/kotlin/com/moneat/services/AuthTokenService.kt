package com.moneat.services

import com.moneat.models.AuthTokenResponse
import com.moneat.models.AuthTokens
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

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
        
        private const val TOKEN_PREFIX = "moneat_"
        private const val TOKEN_LENGTH = 32 // 32 bytes = 256 bits
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
        
        // Generate secure random token
        val tokenBytes = ByteArray(TOKEN_LENGTH)
        secureRandom.nextBytes(tokenBytes)
        val tokenValue = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        
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
                it[AuthTokens.scopes] = scopes.joinToString(",")
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
     * Validate a token and return user ID and scopes if valid
     */
    fun validateToken(token: String): TokenValidationResult? {
        if (!token.startsWith(TOKEN_PREFIX)) {
            return null
        }
        
        val tokenHash = hashToken(token)
        
        return transaction {
            val tokenRow = AuthTokens.selectAll()
                .where { AuthTokens.token_hash eq tokenHash }
                .firstOrNull()
                ?: return@transaction null
            
            // Check if token is expired
            val expiresAt = tokenRow[AuthTokens.expires_at]
            if (expiresAt != null && expiresAt < Clock.System.now()) {
                return@transaction null
            }
            
            val userId = tokenRow[AuthTokens.user_id]
            val scopes = tokenRow[AuthTokens.scopes].split(",").filter { it.isNotEmpty() }
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
                        scopes = row[AuthTokens.scopes].split(",").filter { it.isNotEmpty() },
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
                scopes?.let { newScopes -> it[AuthTokens.scopes] = newScopes.joinToString(",") }
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
