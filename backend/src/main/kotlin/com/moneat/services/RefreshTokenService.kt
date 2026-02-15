package com.moneat.services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.config.EnvConfig
import com.moneat.models.RefreshTokens
import com.moneat.models.Users
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

data class RefreshTokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long = 3600 // 1 hour in seconds
)

class RefreshTokenService {
    private val jwtSecret = EnvConfig.get("JWT_SECRET")
    private val jwtIssuer = EnvConfig.get("JWT_ISSUER", "moneat-api")
    private val jwtAudience = EnvConfig.get("JWT_AUDIENCE", "moneat-dashboard")
    
    companion object {
        private const val REFRESH_TOKEN_LENGTH = 64
        private const val REFRESH_TOKEN_EXPIRY_DAYS = 30L
        private const val ACCESS_TOKEN_EXPIRY_HOURS = 1L
        
        private fun hashToken(token: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(token.toByteArray())
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
        
        private fun generateRandomToken(): String {
            val random = SecureRandom()
            val bytes = ByteArray(REFRESH_TOKEN_LENGTH)
            random.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
    
    /**
     * Generate a new refresh token for a user
     */
    fun generateRefreshToken(userId: Int, email: String, orgId: Int, orgRole: String): RefreshTokenResponse {
        val refreshToken = generateRandomToken()
        val tokenHash = hashToken(refreshToken)
        val now = System.currentTimeMillis()
        val expiresAt = now + (REFRESH_TOKEN_EXPIRY_DAYS * 24 * 60 * 60 * 1000)
        
        transaction {
            RefreshTokens.insert {
                it[RefreshTokens.user_id] = userId
                it[RefreshTokens.token_hash] = tokenHash
                it[RefreshTokens.created_at] = now
                it[RefreshTokens.expires_at] = expiresAt
                it[RefreshTokens.last_used_at] = null
                it[revoked] = false
            }
        }
        
        val accessToken = generateAccessToken(userId, email, orgId, orgRole)
        
        return RefreshTokenResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = ACCESS_TOKEN_EXPIRY_HOURS * 3600
        )
    }
    
    /**
     * Validate a refresh token and rotate it (issue new access + refresh tokens)
     */
    fun validateAndRotate(token: String): RefreshTokenResponse? {
        val tokenHash = hashToken(token)
        
        return transaction {
            // Find the refresh token
            val tokenRow = RefreshTokens
                .selectAll()
                .where { (RefreshTokens.token_hash eq tokenHash) and (RefreshTokens.revoked eq false) }
                .firstOrNull()
                ?: return@transaction null
            
            val expiresAt = tokenRow[RefreshTokens.expires_at]
            val userId = tokenRow[RefreshTokens.user_id]
            
            // Check if expired
            if (expiresAt < System.currentTimeMillis()) {
                return@transaction null
            }
            
            // Get user email and membership (SECURITY: Never trust client-supplied orgId/orgRole)
            val user = Users.selectAll().where { Users.id eq userId }.firstOrNull()
                ?: return@transaction null
            val email = user[Users.email]
            
            // Get user's actual organization membership from database
            val membership = com.moneat.models.Memberships.selectAll()
                .where { com.moneat.models.Memberships.user_id eq userId }
                .firstOrNull()
                ?: return@transaction null
            
            val orgId = membership[com.moneat.models.Memberships.organization_id]
            val orgRole = membership[com.moneat.models.Memberships.role]
            
            // Revoke old refresh token
            RefreshTokens.update({ RefreshTokens.token_hash eq tokenHash }) {
                it[revoked] = true
                it[last_used_at] = System.currentTimeMillis()
            }
            
            // Generate new refresh token
            generateRefreshToken(userId, email, orgId, orgRole)
        }
    }
    
    /**
     * Revoke all refresh tokens for a user (for logout or security)
     */
    fun revokeAllUserTokens(userId: Int): Int {
        return transaction {
            RefreshTokens.update({ (RefreshTokens.user_id eq userId) and (RefreshTokens.revoked eq false) }) {
                it[revoked] = true
            }
        }
    }
    
    /**
     * Clean up expired refresh tokens (to be run periodically)
     */
    fun cleanupExpiredTokens(): Int {
        val now = System.currentTimeMillis()
        return transaction {
            RefreshTokens.deleteWhere { 
                (expires_at less now) or (revoked eq true)
            }
        }
    }
    
    /**
     * Generate a JWT access token
     */
    private fun generateAccessToken(userId: Int, email: String, orgId: Int, orgRole: String): String {
        return JWT.create()
            .withAudience(jwtAudience)
            .withIssuer(jwtIssuer)
            .withClaim("userId", userId)
            .withClaim("email", email)
            .withClaim("orgId", orgId)
            .withClaim("orgRole", orgRole)
            .withExpiresAt(Date(System.currentTimeMillis() + (ACCESS_TOKEN_EXPIRY_HOURS * 3600000)))
            .sign(Algorithm.HMAC256(jwtSecret))
    }
}
