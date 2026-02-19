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

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.config.EnvConfig
import com.moneat.models.RefreshTokens
import com.moneat.models.Users
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

data class RefreshTokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long = 3600 // 1 hour in seconds
)

class RefreshTokenService {
    private val config =
        io.ktor.server.config
            .ApplicationConfig("application.conf")
    private val jwtSecret = config.property("jwt.secret").getString()
    private val jwtIssuer = config.property("jwt.issuer").getString()
    private val jwtAudience = config.property("jwt.audience").getString()

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
    fun generateRefreshToken(
        userId: Int,
        email: String,
        orgId: Int,
        orgRole: String
    ): RefreshTokenResponse {
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
            val tokenRow =
                RefreshTokens
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
            val user =
                Users.selectAll().where { Users.id eq userId }.firstOrNull()
                    ?: return@transaction null
            val email = user[Users.email]

            // Get user's actual organization membership from database
            val membership =
                com.moneat.models.Memberships
                    .selectAll()
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
    private fun generateAccessToken(
        userId: Int,
        email: String,
        orgId: Int,
        orgRole: String
    ): String {
        val isDemoIdentity =
            userId.toLong() == EnvConfig.Demo.USER_ID ||
                email.equals(EnvConfig.Demo.USER_EMAIL, ignoreCase = true)
        val effectiveRole = if (isDemoIdentity) "viewer" else orgRole
        val issuedAt = Date()

        val jwtBuilder =
            JWT
                .create()
                .withAudience(jwtAudience)
                .withIssuer(jwtIssuer)
                .withJWTId(UUID.randomUUID().toString())
                .withIssuedAt(issuedAt)
                .withClaim("userId", userId)
                .withClaim("email", email)
                .withClaim("orgId", orgId)
                .withClaim("orgRole", effectiveRole)
                .withExpiresAt(Date(issuedAt.time + (ACCESS_TOKEN_EXPIRY_HOURS * 3600000)))

        if (isDemoIdentity) {
            jwtBuilder
                .withClaim("isDemo", true)
                .withClaim("demoEpochMs", EnvConfig.Demo.epochMs)
        }

        return jwtBuilder.sign(Algorithm.HMAC256(jwtSecret))
    }
}
