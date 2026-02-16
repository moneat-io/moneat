package com.moneat.services

import com.moneat.models.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import kotlin.test.*

class AuthServiceEmailVerificationTest {
    private val authService = AuthService()

    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        // Initialize DB connection and schema once per test class
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_email_verification;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction {
                SchemaUtils.create(Users, Organizations, Memberships, UserLegalAcceptances, RefreshTokens)
            }
            dbInitialized = true
        }
        
        // Clean up any existing test data from previous tests
        transaction {
            RefreshTokens.deleteAll()
            UserLegalAcceptances.deleteAll()
            Memberships.deleteAll()
            Users.deleteAll()
            Organizations.deleteAll()
        }
    }

    @Test
    fun `verifyEmail succeeds with valid token`() {
        val token = "valid-token-123"
        val expiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000) // 24 hours from now
        
        transaction {
            insertTestUser(
                email = "test@example.com",
                emailVerified = false,
                emailVerificationToken = token,
                emailVerificationExpiresAt = expiresAt
            )
        }

        val result = authService.verifyEmail(token)
        
        assertTrue(result, "Email verification should succeed")
        
        // Verify database state
        transaction {
            val user = Users.selectAll().where { Users.email eq "test@example.com" }.first()
            assertTrue(user[Users.email_verified], "Email should be marked as verified")
            assertNull(user[Users.email_verification_token], "Verification token should be cleared")
            assertNull(user[Users.email_verification_expires_at], "Expiration should be cleared")
        }
    }

    @Test
    fun `verifyEmail fails with expired token`() {
        val token = "expired-token"
        val expiresAt = System.currentTimeMillis() - 1000 // 1 second ago (expired)
        
        transaction {
            insertTestUser(
                email = "test@example.com",
                emailVerified = false,
                emailVerificationToken = token,
                emailVerificationExpiresAt = expiresAt
            )
        }

        val result = authService.verifyEmail(token)
        
        assertFalse(result, "Email verification should fail with expired token")
        
        // Verify database state unchanged
        transaction {
            val user = Users.selectAll().where { Users.email eq "test@example.com" }.first()
            assertFalse(user[Users.email_verified], "Email should NOT be marked as verified")
            assertEquals(token, user[Users.email_verification_token], "Token should remain")
        }
    }

    @Test
    fun `verifyEmail fails with invalid token`() {
        transaction {
            insertTestUser(
                email = "test@example.com",
                emailVerified = false,
                emailVerificationToken = "real-token",
                emailVerificationExpiresAt = System.currentTimeMillis() + 1000000
            )
        }

        val result = authService.verifyEmail("wrong-token")
        
        assertFalse(result, "Email verification should fail with wrong token")
    }

    @Test
    fun `verifyEmail fails when token is null in database`() {
        transaction {
            insertTestUser(
                email = "test@example.com",
                emailVerified = false,
                emailVerificationToken = null,
                emailVerificationExpiresAt = null
            )
        }

        val result = authService.verifyEmail("any-token")
        
        assertFalse(result, "Email verification should fail when no token exists")
    }

    @Test
    fun `resendVerificationEmail fails for already verified email`() {
        transaction {
            insertTestUser(
                email = "verified@example.com",
                emailVerified = true,
                emailVerificationToken = null,
                emailVerificationExpiresAt = null
            )
        }

        val error = assertFailsWith<IllegalArgumentException> {
            authService.resendVerificationEmail("verified@example.com")
        }
        
        assertTrue(error.message?.contains("already verified", ignoreCase = true) == true)
    }

    private fun insertTestUser(
        email: String,
        emailVerified: Boolean,
        emailVerificationToken: String?,
        emailVerificationExpiresAt: Long?
    ): Int {
        return Users.insert {
            it[Users.email] = email
            it[password_hash] = BCrypt.hashpw("password123", BCrypt.gensalt())
            it[name] = "Test User"
            it[email_verified] = emailVerified
            it[email_verification_token] = emailVerificationToken
            it[email_verification_expires_at] = emailVerificationExpiresAt
            it[onboarding_completed] = true
        }[Users.id]
    }
}
