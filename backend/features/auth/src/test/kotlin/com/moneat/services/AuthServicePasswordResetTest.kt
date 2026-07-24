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

import com.moneat.auth.repositories.UserRepositoryImpl
import com.moneat.auth.services.AuthService
import com.moneat.shared.models.EmailsSent
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.RefreshTokens
import com.moneat.shared.models.UserLegalAcceptances
import com.moneat.shared.models.Users
import com.moneat.shared.repositories.MembershipRepositoryImpl
import com.moneat.shared.repositories.OrganizationRepositoryImpl
import com.moneat.testsupport.TestDatabaseHelper
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthServicePasswordResetTest {
    private val authService = AuthService(
        UserRepositoryImpl(),
        MembershipRepositoryImpl(),
        OrganizationRepositoryImpl()
    )

    companion object {
        private var db: org.jetbrains.exposed.v1.jdbc.Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        // Initialize DB connection and schema once per test class
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_password_reset;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }

        // Clean up any existing test data from previous tests
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Memberships,
            UserLegalAcceptances,
            RefreshTokens,
            EmailsSent
        )
    }

    @Test
    fun `resetPassword succeeds with valid token`() {
        val token = "valid-reset-token"
        val expiresAt = System.currentTimeMillis() + (60 * 60 * 1000) // 1 hour from now
        val oldPassword = "oldpassword123"
        val newPassword = "newpassword456"

        val userId =
            transaction {
                insertTestUser(
                    email = "reset@example.com",
                    password = oldPassword,
                    passwordResetToken = token,
                    passwordResetExpiresAt = expiresAt
                )
            }

        transaction {
            val now = System.currentTimeMillis()
            RefreshTokens.insert {
                it[RefreshTokens.user_id] = userId
                it[RefreshTokens.token_hash] = "a".repeat(64)
                it[RefreshTokens.created_at] = now
                it[RefreshTokens.expires_at] = now + (30L * 24 * 60 * 60 * 1000)
                it[RefreshTokens.last_used_at] = null
                it[RefreshTokens.revoked] = false
            }
        }

        val result = authService.resetPassword(token, newPassword)

        assertTrue(result, "Password reset should succeed")

        // Verify password was changed and token cleared
        transaction {
            val user = Users.selectAll().where { Users.id eq userId }.first()
            val newHash = user[Users.password_hash]

            assertTrue(BCrypt.checkpw(newPassword, newHash), "New password should be set")
            assertFalse(BCrypt.checkpw(oldPassword, newHash), "Old password should not work")
            assertNull(user[Users.password_reset_token], "Reset token should be cleared")
            assertNull(user[Users.password_reset_expires_at], "Expiration should be cleared")

            val refreshToken = RefreshTokens.selectAll().where { RefreshTokens.user_id eq userId }.single()
            assertTrue(refreshToken[RefreshTokens.revoked], "Existing sessions should be revoked")
        }
    }

    @Test
    fun `resetPassword fails with expired token`() {
        val token = "expired-reset-token"
        val expiresAt = System.currentTimeMillis() - 1000 // 1 second ago (expired)
        val oldPassword = "oldpassword123"

        val userId =
            transaction {
                insertTestUser(
                    email = "expired@example.com",
                    password = oldPassword,
                    passwordResetToken = token,
                    passwordResetExpiresAt = expiresAt
                )
            }

        val result = authService.resetPassword(token, "newpassword456")

        assertFalse(result, "Password reset should fail with expired token")

        // Verify password unchanged
        transaction {
            val user = Users.selectAll().where { Users.id eq userId }.first()
            assertTrue(BCrypt.checkpw(oldPassword, user[Users.password_hash]), "Password should remain unchanged")
        }
    }

    @Test
    fun `resetPassword fails with invalid token`() {
        transaction {
            insertTestUser(
                email = "test@example.com",
                password = "password123",
                passwordResetToken = "real-token",
                passwordResetExpiresAt = System.currentTimeMillis() + 1000000
            )
        }

        val result = authService.resetPassword("wrong-token", "newpassword456")

        assertFalse(result, "Password reset should fail with wrong token")
    }

    @Test
    fun `resetPassword rejects short passwords`() {
        val token = "valid-token"
        val expiresAt = System.currentTimeMillis() + 1000000

        transaction {
            insertTestUser(
                email = "test@example.com",
                password = "oldpassword123",
                passwordResetToken = token,
                passwordResetExpiresAt = expiresAt
            )
        }

        val error =
            assertFailsWith<IllegalArgumentException> {
                authService.resetPassword(token, "short")
            }

        assertTrue(error.message?.contains("at least 8 characters", ignoreCase = true) == true)
    }

    @Test
    fun `resetPassword fails when token is null in database`() {
        transaction {
            insertTestUser(
                email = "test@example.com",
                password = "password123",
                passwordResetToken = null,
                passwordResetExpiresAt = null
            )
        }

        val result = authService.resetPassword("any-token", "newpassword123")

        assertFalse(result, "Password reset should fail when no token exists")
    }

    @Test
    fun `requestPasswordReset generates token with 1 hour expiry`() {
        transaction {
            insertTestUser(
                email = "request@example.com",
                password = "password123",
                passwordResetToken = null,
                passwordResetExpiresAt = null
            )
        }

        val timeBefore = System.currentTimeMillis()
        authService.requestPasswordReset("request@example.com")
        val timeAfter = System.currentTimeMillis()

        // Note: This may fail if email service is not available, which is okay for unit tests
        // The important part is that the token is set in the database

        transaction {
            val user = Users.selectAll().where { Users.email eq "request@example.com" }.first()
            assertNotNull(user[Users.password_reset_token], "Reset token should be generated")

            val expiresAt = user[Users.password_reset_expires_at]
            assertNotNull(expiresAt, "Expiration should be set")

            // Token should expire in approximately 1 hour (60 * 60 * 1000 ms)
            val expectedExpiry = timeBefore + (60 * 60 * 1000)
            assertTrue(expiresAt >= expectedExpiry - 1000, "Expiry should be ~1 hour from now (lower bound)")
            assertTrue(
                expiresAt <= timeAfter + (60 * 60 * 1000) + 1000,
                "Expiry should be ~1 hour from now (upper bound)"
            )
        }
    }

    private fun insertTestUser(
        email: String,
        password: String,
        passwordResetToken: String?,
        passwordResetExpiresAt: Long?
    ): Int {
        return Users.insert {
            it[Users.email] = email
            it[password_hash] = BCrypt.hashpw(password, BCrypt.gensalt())
            it[name] = "Test User"
            it[email_verified] = true
            it[password_reset_token] = passwordResetToken
            it[password_reset_expires_at] = passwordResetExpiresAt
            it[onboarding_completed] = true
        }[Users.id]
    }
}
