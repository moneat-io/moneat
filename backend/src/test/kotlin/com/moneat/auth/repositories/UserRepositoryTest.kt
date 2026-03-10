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

package com.moneat.auth.repositories

import com.moneat.shared.models.Users
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.SsoConfigurations
import com.moneat.testsupport.TestDatabaseHelper
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserRepositoryTest {

    companion object {
        private const val FIND_EMAIL = "find@test.com"
        private const val RESET_TOKEN = "reset-tok"
    }

    private var db: Database? = null
    private lateinit var repository: UserRepository

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_user_repo;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships, SsoConfigurations)
        repository = UserRepositoryImpl()
    }

    private fun createUser(
        email: String = "user@test.com",
        passwordHash: String = "hash",
        name: String? = null,
        emailVerified: Boolean = false,
        emailVerificationToken: String? = null,
        emailVerificationExpiresAt: Long? = null
    ): Int = repository.create(
        email = email,
        passwordHash = passwordHash,
        name = name,
        emailVerified = emailVerified,
        emailVerificationToken = emailVerificationToken,
        emailVerificationExpiresAt = emailVerificationExpiresAt
    )

    // ============ create + findById ============

    @Test
    fun `create inserts user and findById returns correct fields`() {
        val userId = createUser(
            email = "alice@test.com",
            passwordHash = "hashed",
            name = "Alice",
            emailVerified = false,
            emailVerificationToken = "tok123",
            emailVerificationExpiresAt = 9999L
        )
        val user = repository.findById(userId)!!
        assertEquals("alice@test.com", user.email)
        assertEquals("hashed", user.passwordHash)
        assertEquals("Alice", user.name)
        assertFalse(user.emailVerified)
        assertEquals("tok123", user.emailVerificationToken)
        assertEquals(9999L, user.emailVerificationExpiresAt)
    }

    @Test
    fun `findById returns null for unknown id`() {
        assertNull(repository.findById(99999))
    }

    // ============ findByEmail ============

    @Test
    fun `findByEmail returns user for existing email`() {
        createUser(email = FIND_EMAIL)
        val found = repository.findByEmail(FIND_EMAIL)
        assertNotNull(found)
        assertEquals(FIND_EMAIL, found.email)
    }

    @Test
    fun `findByEmail returns null for unknown email`() {
        assertNull(repository.findByEmail("nobody@test.com"))
    }

    // ============ existsByEmail ============

    @Test
    fun `existsByEmail returns true when email exists`() {
        createUser(email = "exists@test.com")
        assertTrue(repository.existsByEmail("exists@test.com"))
    }

    @Test
    fun `existsByEmail returns false when email absent`() {
        assertFalse(repository.existsByEmail("absent@test.com"))
    }

    // ============ findByEmailVerificationToken ============

    @Test
    fun `findByEmailVerificationToken returns user for matching token`() {
        createUser(
            email = "tok@test.com",
            emailVerificationToken = "my-token",
            emailVerificationExpiresAt = 99999L
        )
        val found = repository.findByEmailVerificationToken("my-token")
        assertNotNull(found)
        assertEquals("tok@test.com", found.email)
    }

    @Test
    fun `findByEmailVerificationToken returns null for unknown token`() {
        assertNull(repository.findByEmailVerificationToken("bad-token"))
    }

    // ============ updateEmailVerified + clearEmailVerificationToken ============

    @Test
    fun `updateEmailVerified sets email_verified to true`() {
        val userId = createUser(
            email = "verify@test.com",
            emailVerified = false,
            emailVerificationToken = "tok",
            emailVerificationExpiresAt = 99L
        )
        repository.updateEmailVerified(userId, true)
        assertTrue(repository.findById(userId)!!.emailVerified)
    }

    @Test
    fun `clearEmailVerificationToken nulls the token and expiry`() {
        val userId = createUser(
            email = "clear@test.com",
            emailVerificationToken = "clearme",
            emailVerificationExpiresAt = 99L
        )
        repository.clearEmailVerificationToken(userId)
        val updated = repository.findById(userId)!!
        assertNull(updated.emailVerificationToken)
        assertNull(updated.emailVerificationExpiresAt)
    }

    // ============ updateVerificationToken ============

    @Test
    fun `updateVerificationToken replaces existing token`() {
        val userId = createUser(
            email = "retok@test.com",
            emailVerificationToken = "old-tok",
            emailVerificationExpiresAt = 1L
        )
        repository.updateVerificationToken(userId, "new-tok", 5000L)
        val found = repository.findByEmailVerificationToken("new-tok")
        assertNotNull(found)
        assertEquals(5000L, found.emailVerificationExpiresAt)
    }

    // ============ password reset ============

    @Test
    fun `updatePasswordResetToken sets reset token and expiry`() {
        val userId = createUser(email = "reset@test.com", emailVerified = true)
        repository.updatePasswordResetToken(userId, RESET_TOKEN, 8000L)
        val found = repository.findByPasswordResetToken(RESET_TOKEN)
        assertNotNull(found)
        assertEquals(8000L, found.passwordResetExpiresAt)
    }

    @Test
    fun `clearPasswordResetToken nulls the reset token`() {
        val userId = createUser(email = "clearreset@test.com", emailVerified = true)
        repository.updatePasswordResetToken(userId, RESET_TOKEN, 9000L)
        repository.clearPasswordResetToken(userId)
        val updated = repository.findById(userId)!!
        assertNull(updated.passwordResetToken)
        assertNull(updated.passwordResetExpiresAt)
    }

    // ============ updatePassword ============

    @Test
    fun `updatePassword replaces password hash`() {
        val userId = createUser(email = "pw@test.com", passwordHash = "old-hash")
        repository.updatePassword(userId, "new-hash")
        assertEquals("new-hash", repository.findById(userId)!!.passwordHash)
    }

    // ============ updateOnboardingCompleted ============

    @Test
    fun `updateOnboardingCompleted sets onboarding flag to true`() {
        val userId = createUser(email = "onboard@test.com", emailVerified = true)
        assertFalse(repository.findById(userId)!!.onboardingCompleted)
        repository.updateOnboardingCompleted(userId)
        assertTrue(repository.findById(userId)!!.onboardingCompleted)
    }

    // ============ requiresSsoForEmail ============

    @Test
    fun `requiresSsoForEmail returns false when user has no membership`() {
        createUser(email = "nosso@test.com")
        assertFalse(repository.requiresSsoForEmail("nosso@test.com"))
    }

    @Test
    fun `requiresSsoForEmail returns false when org has no SSO config requiring SSO`() {
        val userId = createUser(email = "user@domain.com")
        val orgId = org.jetbrains.exposed.v1.jdbc.transactions.transaction {
            val oid = Organizations.insert {
                it[name] = "Org"
                it[slug] = "org"
            } get Organizations.id
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = oid
                it[role] = "owner"
            }
            SsoConfigurations.insert {
                it[organizationId] = oid
                it[providerType] = "saml"
                it[isEnabled] = true
                it[requireSso] = false
                it[emailDomain] = "domain.com"
            }
            oid
        }
        assertFalse(repository.requiresSsoForEmail("user@domain.com"))
        // suppress unused variable warning
        orgId.let {}
    }

    @Test
    fun `requiresSsoForEmail returns true when org has enabled requireSso config matching domain`() {
        val userId = createUser(email = "sso@company.com")
        org.jetbrains.exposed.v1.jdbc.transactions.transaction {
            val oid = Organizations.insert {
                it[name] = "Company"
                it[slug] = "company"
            } get Organizations.id
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = oid
                it[role] = "member"
            }
            SsoConfigurations.insert {
                it[organizationId] = oid
                it[providerType] = "saml"
                it[isEnabled] = true
                it[requireSso] = true
                it[emailDomain] = "company.com"
            }
        }
        assertTrue(repository.requiresSsoForEmail("sso@company.com"))
    }
}
