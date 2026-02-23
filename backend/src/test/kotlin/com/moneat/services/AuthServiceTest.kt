package com.moneat.services

import com.moneat.auth.services.AuthService
import com.moneat.events.models.LoginRequest
import com.moneat.events.models.SignupRequest
import com.moneat.shared.models.EmailsSent
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrgInvitations
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.RefreshTokens
import com.moneat.shared.models.SsoConfigurations
import com.moneat.shared.models.UserLegalAcceptances
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthServiceTest {
    private val authService = AuthService()

    companion object {
        private var db: Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_auth_service;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        // Ensure schema exists (idempotent in H2) and clean between tests
        transaction {
            SchemaUtils.drop(
                OrgInvitations,
                SsoConfigurations,
                EmailsSent,
                RefreshTokens,
                UserLegalAcceptances,
                Memberships,
                Organizations,
                Users
            )
            SchemaUtils.create(
                Users,
                Organizations,
                Memberships,
                UserLegalAcceptances,
                RefreshTokens,
                EmailsSent,
                SsoConfigurations,
                OrgInvitations
            )
        }
    }

    private fun insertTestUser(
        email: String = "test@example.com",
        password: String = "password123",
        emailVerified: Boolean = true,
        onboardingCompleted: Boolean = false
    ): Int =
        transaction {
            val userId =
                Users.insert {
                    it[Users.email] = email
                    it[password_hash] = BCrypt.hashpw(password, BCrypt.gensalt())
                    it[name] = "Test User"
                    it[email_verified] = emailVerified
                    it[Users.onboarding_completed] = onboardingCompleted
                } get Users.id

            val orgId =
                Organizations.insert {
                    it[name] = "Org for $email"
                    it[slug] = "org-${email.replace("@", "-at-").replace(".", "-")}"
                } get Organizations.id

            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }

            userId
        }

    @Test
    fun `login returns null for non-existent user`() {
        val result = authService.login(LoginRequest(email = "nobody@test.com", password = "pass"))
        assertNull(result)
    }

    @Test
    fun `login returns null for wrong password`() {
        insertTestUser(email = "user@test.com", password = "correct")
        val result = authService.login(LoginRequest(email = "user@test.com", password = "wrong"))
        assertNull(result)
    }

    @Test
    fun `login returns AuthResponse for valid credentials`() {
        insertTestUser(email = "user@test.com", password = "correct")
        val result = authService.login(LoginRequest(email = "user@test.com", password = "correct"))
        assertNotNull(result)
        assertNotNull(result.token)
        assertTrue(result.token.isNotBlank())
    }

    @Test
    fun `login is case insensitive for email`() {
        insertTestUser(email = "user@test.com", password = "pass")
        val result = authService.login(LoginRequest(email = "User@Test.com", password = "pass"))
        assertNotNull(result)
    }

    @Test
    fun `signup creates new user`() {
        val result =
            authService.signup(
                SignupRequest(
                    email = "newuser@test.com",
                    password = "StrongPass123!",
                    name = "New User",
                    acceptTerms = true,
                    acceptPrivacy = true,
                    termsVersion = "2026-02-08",
                    privacyVersion = "2026-02-08"
                )
            )
        assertNotNull(result)
        assertNotNull(result.token)

        // Verify user exists in database
        transaction {
            val user = Users.selectAll().where { Users.email eq "newuser@test.com" }.singleOrNull()
            assertNotNull(user)
            assertEquals("New User", user[Users.name])
            assertFalse(user[Users.email_verified])
        }
    }

    @Test
    fun `signup rejects duplicate email`() {
        insertTestUser(email = "existing@test.com")

        assertFailsWith<Exception> {
            authService.signup(
                SignupRequest(
                    email = "existing@test.com",
                    password = "StrongPass123!",
                    name = "Duplicate",
                    acceptTerms = true,
                    acceptPrivacy = true,
                    termsVersion = "2026-02-08",
                    privacyVersion = "2026-02-08"
                )
            )
        }
    }

    @Test
    fun `verifyEmail returns false for invalid token`() {
        val result = authService.verifyEmail("nonexistent-token")
        assertFalse(result)
    }

    @Test
    fun `verifyEmail returns false for expired token`() {
        val expiredTime = System.currentTimeMillis() - 1000 // already expired
        transaction {
            Users.insert {
                it[email] = "expired@test.com"
                it[password_hash] = "hashed"
                it[email_verified] = false
                it[email_verification_token] = "expired-token"
                it[email_verification_expires_at] = expiredTime
            }
        }

        val result = authService.verifyEmail("expired-token")
        assertFalse(result)
    }

    @Test
    fun `requestPasswordReset returns true for existing user`() {
        insertTestUser(email = "reset@test.com")
        val result = authService.requestPasswordReset("reset@test.com")
        assertTrue(result)

        // Verify reset token was set
        transaction {
            val user = Users.selectAll().where { Users.email eq "reset@test.com" }.first()
            assertNotNull(user[Users.password_reset_token])
            assertNotNull(user[Users.password_reset_expires_at])
        }
    }

    @Test
    fun `requestPasswordReset returns false for non-existent user`() {
        val result = authService.requestPasswordReset("nobody@test.com")
        assertFalse(result)
    }

    @Test
    fun `resetPassword fails with invalid token`() {
        val result = authService.resetPassword("bad-token", "NewPassword123!")
        assertFalse(result)
    }

    @Test
    fun `generateDemoToken returns valid token`() {
        val token = authService.generateDemoToken()
        assertNotNull(token)
        assertTrue(token.isNotBlank())
    }
}
