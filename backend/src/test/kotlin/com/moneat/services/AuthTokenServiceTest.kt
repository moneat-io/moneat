@file:Suppress("USELESS_CAST", "UNNECESSARY_NOT_NULL_ASSERTION", "UNNECESSARY_SAFE_CALL")

package com.moneat.services

import com.moneat.models.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.*

class AuthTokenServiceTest {
    private val service = AuthTokenService()

    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_auth_token;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction {
                SchemaUtils.create(Organizations, Users, Memberships, AuthTokens)
            }
            dbInitialized = true
        }

        transaction {
            AuthTokens.deleteAll()
            Memberships.deleteAll()
            Users.deleteAll()
            Organizations.deleteAll()
        }
    }

    private fun seedUser(email: String = "test@example.com"): Int =
        transaction {
            Users.insert {
                it[Users.email] = email
                it[Users.name] = "Test User"
                it[Users.password_hash] = "hashed"
                it[Users.email_verified] = true
            } get Users.id
        }

    private fun seedOrgAndMembership(userId: Int): Int =
        transaction {
            val orgId =
                Organizations.insert {
                    it[name] = "Test Org"
                    it[slug] = "test-org"
                } get Organizations.id
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }
            orgId
        }

    // --- hasScope ---

    @Test
    fun `hasScope returns true when scope present`() {
        assertTrue(service.hasScope(listOf("project:read", "project:write"), "project:read"))
    }

    @Test
    fun `hasScope returns false when scope missing`() {
        assertFalse(service.hasScope(listOf("project:read"), "project:write"))
    }

    @Test
    fun `hasScope returns false for empty scopes`() {
        assertFalse(service.hasScope(emptyList(), "project:read"))
    }

    // --- hasAnyScope ---

    @Test
    fun `hasAnyScope returns true when any scope matches`() {
        assertTrue(service.hasAnyScope(listOf("project:read"), listOf("project:read", "project:write")))
    }

    @Test
    fun `hasAnyScope returns false when no scope matches`() {
        assertFalse(service.hasAnyScope(listOf("project:read"), listOf("org:read")))
    }

    // --- generateToken ---

    @Test
    fun `generateToken creates token with valid scopes`() {
        val userId = seedUser()
        seedOrgAndMembership(userId)

        val result = service.generateToken(userId, "my-token", listOf("project:read"))
        assertNotNull(result.token)
        assertTrue(result.token!!.startsWith("sntrys_"))
        assertEquals("my-token", result.name)
        assertEquals(listOf("project:read"), result.scopes)
    }

    @Test
    fun `generateToken rejects invalid scopes`() {
        val userId = seedUser()
        assertFailsWith<IllegalArgumentException> {
            service.generateToken(userId, "my-token", listOf("invalid:scope"))
        }
    }

    @Test
    fun `generateToken rejects blank name`() {
        val userId = seedUser()
        assertFailsWith<IllegalArgumentException> {
            service.generateToken(userId, "", listOf("project:read"))
        }
    }

    // --- validateToken ---

    @Test
    fun `validateToken returns user info for valid token`() {
        val userId = seedUser()
        seedOrgAndMembership(userId)
        val created = service.generateToken(userId, "test", listOf("project:read"))

        val result = service.validateToken(created.token!!)
        assertNotNull(result)
        assertEquals(userId, result.userId)
        assertEquals(listOf("project:read"), result.scopes)
    }

    @Test
    fun `validateToken returns null for invalid token`() {
        assertNull(service.validateToken("sntrys_invalid_token"))
    }

    @Test
    fun `validateToken returns null for non-sntrys prefix`() {
        assertNull(service.validateToken("plain-token"))
    }

    @Test
    fun `validateToken updates last_used_at`() {
        val userId = seedUser()
        seedOrgAndMembership(userId)
        val created = service.generateToken(userId, "test", listOf("project:read"))

        service.validateToken(created.token!!)

        val tokenRow =
            transaction {
                AuthTokens
                    .selectAll()
                    .where { AuthTokens.id eq created.id }
                    .first()
            }
        assertNotNull(tokenRow[AuthTokens.last_used_at])
    }

    // --- listUserTokens ---

    @Test
    fun `listUserTokens returns all user tokens`() {
        val userId = seedUser()
        seedOrgAndMembership(userId)
        service.generateToken(userId, "token-1", listOf("project:read"))
        service.generateToken(userId, "token-2", listOf("org:read"))

        val tokens = service.listUserTokens(userId)
        assertEquals(2, tokens.size)
        assertTrue(tokens.all { it.token == null }) // Never returns actual token
    }

    @Test
    fun `listUserTokens returns empty for user with no tokens`() {
        val userId = seedUser()
        assertTrue(service.listUserTokens(userId).isEmpty())
    }

    // --- revokeToken ---

    @Test
    fun `revokeToken deletes the token`() {
        val userId = seedUser()
        seedOrgAndMembership(userId)
        val created = service.generateToken(userId, "to-revoke", listOf("project:read"))

        assertTrue(service.revokeToken(userId, created.id))
        assertTrue(service.listUserTokens(userId).isEmpty())
    }

    @Test
    fun `revokeToken returns false for wrong user`() {
        val userId = seedUser("user1@test.com")
        val otherUser = seedUser("user2@test.com")
        seedOrgAndMembership(userId)
        val created = service.generateToken(userId, "token", listOf("project:read"))

        assertFalse(service.revokeToken(otherUser, created.id))
    }

    @Test
    fun `revokeToken returns false for non-existent token`() {
        val userId = seedUser()
        assertFalse(service.revokeToken(userId, 99999))
    }

    // --- updateToken ---

    @Test
    fun `updateToken updates name`() {
        val userId = seedUser()
        seedOrgAndMembership(userId)
        val created = service.generateToken(userId, "old-name", listOf("project:read"))

        assertTrue(service.updateToken(userId, created.id, "new-name", null))
        val tokens = service.listUserTokens(userId)
        assertEquals("new-name", tokens.first().name)
    }

    @Test
    fun `updateToken updates scopes`() {
        val userId = seedUser()
        seedOrgAndMembership(userId)
        val created = service.generateToken(userId, "token", listOf("project:read"))

        assertTrue(service.updateToken(userId, created.id, null, listOf("project:read", "org:read")))
        val tokens = service.listUserTokens(userId)
        assertEquals(listOf("project:read", "org:read"), tokens.first().scopes)
    }

    @Test
    fun `updateToken rejects invalid scopes`() {
        val userId = seedUser()
        seedOrgAndMembership(userId)
        val created = service.generateToken(userId, "token", listOf("project:read"))

        assertFailsWith<IllegalArgumentException> {
            service.updateToken(userId, created.id, null, listOf("bad:scope"))
        }
    }

    @Test
    fun `updateToken returns false for wrong user`() {
        val userId = seedUser("user1@test.com")
        val otherUser = seedUser("user2@test.com")
        seedOrgAndMembership(userId)
        val created = service.generateToken(userId, "token", listOf("project:read"))

        assertFalse(service.updateToken(otherUser, created.id, "renamed", null))
    }

    // --- VALID_SCOPES ---

    @Test
    fun `VALID_SCOPES contains expected scopes`() {
        assertTrue(AuthTokenService.VALID_SCOPES.contains("project:read"))
        assertTrue(AuthTokenService.VALID_SCOPES.contains("releases:write"))
        assertTrue(AuthTokenService.VALID_SCOPES.contains("sourcemaps:write"))
        assertTrue(AuthTokenService.VALID_SCOPES.contains("org:read"))
    }
}
