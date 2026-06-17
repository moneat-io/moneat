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

package com.moneat.mcp.auth

import com.moneat.auth.services.AuthTokenService
import com.moneat.mcp.services.McpApiKeyService
import com.moneat.shared.models.AuthTokens
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.McpApiKeys
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock

class McpAuthProviderTest {
    companion object {
        private var db: Database? = null
    }

    @BeforeEach
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_mcp_auth;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships, AuthTokens, McpApiKeys)
    }

    @Test
    fun `multi-org token resolves by embedded org slug`() {
        val (userId, orgId) = seedUserInOrg("primary")
        val token = AuthTokenService()
            .generateToken(
                userId = userId,
                orgId = orgId,
                name = "MCP",
                scopes = listOf("project:read"),
            )
            .token ?: error("Token missing")
        addMembership(userId, "secondary")

        val auth = McpAuthProvider.validateAuthorization("Bearer $token")

        assertNotNull(auth)
        assertEquals(orgId, auth.organizationId)
        assertEquals(setOf("project:read"), auth.scopes)
    }

    @Test
    fun `ambiguous token without org context is rejected`() {
        val (userId, _) = seedUserInOrg("primary")
        addMembership(userId, "secondary")
        val token = "sntrys_not-base64_payload"
        insertToken(userId, token, listOf("project:read"))

        val auth = McpAuthProvider.validateAuthorization("Bearer $token")

        assertNull(auth)
    }

    @Test
    fun `non bearer authorization is rejected`() {
        val (userId, orgId) = seedUserInOrg("primary")
        val token = AuthTokenService()
            .generateToken(
                userId = userId,
                orgId = orgId,
                name = "MCP",
                scopes = listOf("project:read"),
            )
            .token ?: error("Token missing")

        val auth = McpAuthProvider.validateAuthorization(token)

        assertNull(auth)
    }

    @Test
    fun `mcp api keys do not inherit workflow scopes`() {
        val (userId, orgId) = seedUserInOrg("primary")
        val key = McpApiKeyService()
            .createKey(
                organizationId = orgId,
                userId = userId,
                name = "MCP",
                enabledTools = listOf("list_workflows"),
                enabledResources = emptyList(),
            )
            .key

        val auth = McpAuthProvider.validateAuthorization("Bearer $key")

        assertNotNull(auth)
        assertEquals(orgId, auth.organizationId)
        assertEquals(setOf("list_workflows"), auth.allowedTools)
        assertFalse(McpScopes.WORKFLOW_READ in auth.scopes)
        assertFalse(McpScopes.WORKFLOW_WRITE in auth.scopes)
        assertFalse(McpScopes.WORKFLOW_RUN in auth.scopes)
    }

    private fun seedUserInOrg(slug: String): Pair<Int, Int> =
        transaction {
            val orgId = Organizations.insert {
                it[name] = slug
                it[Organizations.slug] = slug
            }[Organizations.id]
            val userId = Users.insert {
                it[email] = "$slug@test.com"
                it[password_hash] = "hash"
                it[email_verified] = true
            }[Users.id]
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }
            userId to orgId
        }

    private fun addMembership(userId: Int, slug: String): Int =
        transaction {
            val orgId = Organizations.insert {
                it[name] = slug
                it[Organizations.slug] = slug
            }[Organizations.id]
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "member"
            }
            orgId
        }

    private fun insertToken(userId: Int, token: String, scopes: List<String>) {
        transaction {
            AuthTokens.insert {
                it[user_id] = userId
                it[token_hash] = hashToken(token)
                it[name] = "MCP"
                it[AuthTokens.scopes] = scopes
                it[created_at] = Clock.System.now()
            }
        }
    }

    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
