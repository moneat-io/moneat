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
import com.moneat.models.Memberships
import com.moneat.models.Organizations
import com.moneat.models.RefreshTokens
import com.moneat.models.Users
import io.ktor.server.config.ApplicationConfig
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RefreshTokenServiceTest {
    private val refreshTokenService = RefreshTokenService()
    private val jwtSecret = ApplicationConfig("application.conf").property("jwt.secret").getString()

    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_refresh_tokens;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction {
                SchemaUtils.create(Users, Organizations, Memberships, RefreshTokens)
            }
            dbInitialized = true
        }

        transaction {
            RefreshTokens.deleteAll()
            Memberships.deleteAll()
            Users.deleteAll()
            Organizations.deleteAll()
        }
    }

    @Test
    fun `generateRefreshToken persists hashed token and access token claims`() {
        val (userId, orgId) = createUserWithMembership()

        val response =
            refreshTokenService.generateRefreshToken(
                userId = userId,
                email = "user@test.com",
                orgId = orgId,
                orgRole = "owner"
            )

        assertTrue(response.refreshToken.isNotBlank())
        assertTrue(response.accessToken.isNotBlank())
        assertEquals(3600, response.expiresIn)

        transaction {
            val tokens = RefreshTokens.selectAll().toList()
            assertEquals(1, tokens.size)
            val row = tokens.first()
            assertNotEquals(response.refreshToken, row[RefreshTokens.token_hash])
            assertEquals(64, row[RefreshTokens.token_hash].length)
            assertEquals(false, row[RefreshTokens.revoked])
        }

        val decoded = JWT.require(Algorithm.HMAC256(jwtSecret)).build().verify(response.accessToken)
        assertEquals(userId, decoded.getClaim("userId").asInt())
        assertEquals(orgId, decoded.getClaim("orgId").asInt())
        assertEquals("owner", decoded.getClaim("orgRole").asString())
    }

    @Test
    fun `validateAndRotate revokes old token and issues new token`() {
        val (userId, orgId) = createUserWithMembership()
        val first = refreshTokenService.generateRefreshToken(userId, "user@test.com", orgId, "owner")

        val rotated = refreshTokenService.validateAndRotate(first.refreshToken)
        assertNotNull(rotated)
        assertNotEquals(first.refreshToken, rotated.refreshToken)
        assertNotEquals(first.accessToken, rotated.accessToken)

        transaction {
            val rows = RefreshTokens.selectAll().toList()
            assertEquals(2, rows.size)
            val revokedCount = rows.count { it[RefreshTokens.revoked] }
            val activeCount = rows.count { !it[RefreshTokens.revoked] }
            assertEquals(1, revokedCount)
            assertEquals(1, activeCount)
            val revokedRow = rows.first { it[RefreshTokens.revoked] }
            assertNotNull(revokedRow[RefreshTokens.last_used_at])
        }

        assertNull(refreshTokenService.validateAndRotate(first.refreshToken))
    }

    @Test
    fun `validateAndRotate returns null for expired token`() {
        val (userId, orgId) = createUserWithMembership()
        val generated = refreshTokenService.generateRefreshToken(userId, "user@test.com", orgId, "owner")

        transaction {
            RefreshTokens.update { it[expires_at] = System.currentTimeMillis() - 1_000 }
        }

        val result = refreshTokenService.validateAndRotate(generated.refreshToken)
        assertNull(result)
    }

    @Test
    fun `revokeAllUserTokens revokes only target user tokens`() {
        val (userOne, orgOne) = createUserWithMembership(email = "one@test.com")
        val (userTwo, orgTwo) = createUserWithMembership(email = "two@test.com")

        refreshTokenService.generateRefreshToken(userOne, "one@test.com", orgOne, "owner")
        refreshTokenService.generateRefreshToken(userOne, "one@test.com", orgOne, "owner")
        refreshTokenService.generateRefreshToken(userTwo, "two@test.com", orgTwo, "owner")

        val updated = refreshTokenService.revokeAllUserTokens(userOne)
        assertEquals(2, updated)

        transaction {
            val userOneRows = RefreshTokens.selectAll().where { RefreshTokens.user_id eq userOne }.toList()
            val userTwoRows = RefreshTokens.selectAll().where { RefreshTokens.user_id eq userTwo }.toList()
            assertTrue(userOneRows.all { it[RefreshTokens.revoked] })
            assertTrue(userTwoRows.all { !it[RefreshTokens.revoked] })
        }
    }

    @Test
    fun `cleanupExpiredTokens deletes expired and revoked rows`() {
        val (userId, orgId) = createUserWithMembership()
        val active = refreshTokenService.generateRefreshToken(userId, "user@test.com", orgId, "owner")
        val revoked = refreshTokenService.generateRefreshToken(userId, "user@test.com", orgId, "owner")
        val expired = refreshTokenService.generateRefreshToken(userId, "user@test.com", orgId, "owner")

        // Touch tokens to derive hashes via service path.
        refreshTokenService.validateAndRotate(revoked.refreshToken)
        transaction {
            val expiredHash = sha256(expired.refreshToken)
            RefreshTokens.update({ RefreshTokens.token_hash eq expiredHash }) {
                it[expires_at] = System.currentTimeMillis() - 1_000
            }
        }

        val deleted = refreshTokenService.cleanupExpiredTokens()
        assertTrue(deleted >= 2)

        transaction {
            val remaining = RefreshTokens.selectAll().toList()
            assertTrue(remaining.any { it[RefreshTokens.token_hash] == sha256(active.refreshToken) })
            assertTrue(remaining.none { it[RefreshTokens.revoked] })
        }
    }

    private fun createUserWithMembership(email: String = "user@test.com"): Pair<Int, Int> =
        transaction {
            val userId =
                Users.insert {
                    it[Users.email] = email
                    it[Users.password_hash] = "hash"
                    it[Users.name] = "Test User"
                    it[Users.email_verified] = true
                } get Users.id

            val orgId =
                Organizations.insert {
                    it[Organizations.name] = "Test Org"
                    it[Organizations.slug] = "test-org-$userId"
                } get Organizations.id

            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }

            userId to orgId
        }

    private fun sha256(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(value.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
