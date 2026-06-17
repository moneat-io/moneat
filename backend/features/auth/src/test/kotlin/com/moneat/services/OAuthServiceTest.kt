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

import com.moneat.auth.services.OAuthService
import com.moneat.auth.services.OAuthUserData
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.respond
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OAuthServiceTest {
    companion object {
        private var db: Database? = null
    }

    @Test
    fun `generateGitHubAuthUrl uses configured base url and encoded redirect`() {
        MockHttpServer { exchange ->
            exchange.respond(404, """{"error":"not used"}""")
        }.use { server ->
            withProperties(
                mapOf(
                    "GITHUB_OAUTH_CLIENT_ID" to "client-123",
                    "GITHUB_OAUTH_CLIENT_SECRET" to "secret-123",
                    "BACKEND_URL" to "https://api.test.local",
                    "FRONTEND_URL" to "https://api.test.local",
                    "GITHUB_OAUTH_BASE_URL" to server.baseUrl
                )
            ) {
                val service = OAuthService()
                val url = service.generateGitHubAuthUrl("state value")
                assertTrue(url.startsWith("${server.baseUrl}/login/oauth/authorize?"))
                assertTrue(url.contains("client_id=client-123"))
                assertTrue(url.contains("redirect_uri=https%3A%2F%2Fapi.test.local%2Fauth%2Fgithub%2Fcallback"))
                assertTrue(url.contains("state=state+value") || url.contains("state=state%20value"))
            }
        }
    }

    @Test
    fun `handleGitHubCallback prefers user email when present`() {
        MockHttpServer { exchange ->
            when (exchange.requestURI.path) {
                "/login/oauth/access_token" -> {
                    exchange.respond(
                        200,
                        """{"access_token":"token-1","token_type":"bearer","scope":"user:email"}"""
                    )
                }

                "/user" -> {
                    exchange.respond(
                        200,
                        """{"id":42,"login":"octocat","email":"User@Test.com","name":"Octo Cat"}"""
                    )
                }

                "/user/emails" -> {
                    exchange.respond(200, """[]""")
                }

                else -> {
                    exchange.respond(404, """{"error":"not found"}""")
                }
            }
        }.use { server ->
            withProperties(
                mapOf(
                    "GITHUB_OAUTH_CLIENT_ID" to "client-123",
                    "GITHUB_OAUTH_CLIENT_SECRET" to "secret-123",
                    "FRONTEND_URL" to "https://api.test.local",
                    "GITHUB_OAUTH_BASE_URL" to server.baseUrl,
                    "GITHUB_API_BASE_URL" to server.baseUrl
                )
            ) {
                val service = OAuthService()
                val user = runBlocking { service.handleGitHubCallback("code-1") }
                assertEquals("github", user.provider)
                assertEquals("42", user.providerId)
                assertEquals("user@test.com", user.email)
                assertEquals("Octo Cat", user.name)
                assertTrue(user.emailVerified)
            }
        }
    }

    @Test
    fun `findOrCreateOAuthUser logs in existing provider user with public ids`() {
        setupOAuthDatabase()
        val existing = seedOAuthUser(email = "existing@test.com", providerId = "provider-1")

        withOAuthProperties {
            val response = OAuthService().findOrCreateOAuthUser(
                OAuthUserData(
                    provider = "github",
                    providerId = "provider-1",
                    email = "existing@test.com",
                    name = "Existing User",
                    emailVerified = true,
                )
            )

            assertTrue(response.token.isNotBlank())
            assertEquals("existing@test.com", response.user.email)
            assertEquals(existing.userResourceId, response.user.id)
            assertEquals(existing.organizationResourceId, response.user.orgId)
            assertTrue(response.user.id.contains("-"))
            assertTrue(response.user.orgId.orEmpty().contains("-"))
        }
    }

    @Test
    fun `findOrCreateOAuthUser links existing password user to provider`() {
        setupOAuthDatabase()
        val existing = seedOAuthUser(
            email = "link@test.com",
            provider = null,
            providerId = null,
            emailVerified = false,
        )

        withOAuthProperties {
            val response = OAuthService().findOrCreateOAuthUser(
                OAuthUserData(
                    provider = "github",
                    providerId = "provider-2",
                    email = "link@test.com",
                    name = "Linked User",
                    emailVerified = true,
                )
            )

            assertEquals(existing.userResourceId, response.user.id)
            transaction {
                val linked = Users
                    .selectAll()
                    .where { Users.id eq existing.userId }
                    .first()
                assertEquals("github", linked[Users.oauth_provider])
                assertEquals("provider-2", linked[Users.oauth_provider_id])
                assertTrue(linked[Users.email_verified])
            }
        }
    }

    @Test
    fun `findOrCreateOAuthUser rejects email registered with another provider`() {
        setupOAuthDatabase()
        seedOAuthUser(email = "conflict@test.com", provider = "google", providerId = "google-1")

        withOAuthProperties {
            val error = assertFailsWith<IllegalArgumentException> {
                OAuthService().findOrCreateOAuthUser(
                    OAuthUserData(
                        provider = "github",
                        providerId = "provider-3",
                        email = "conflict@test.com",
                        name = "Conflict User",
                        emailVerified = true,
                    )
                )
            }

            assertTrue(error.message.orEmpty().contains("google"))
        }
    }

    @Test
    fun `handleGitHubCallback falls back to primary verified email`() {
        MockHttpServer { exchange ->
            when (exchange.requestURI.path) {
                "/login/oauth/access_token" -> {
                    exchange.respond(
                        200,
                        """{"access_token":"token-2","token_type":"bearer","scope":"user:email"}"""
                    )
                }

                "/user" -> {
                    exchange.respond(200, """{"id":77,"login":"fallback","email":null,"name":null}""")
                }

                "/user/emails" -> {
                    exchange.respond(
                        200,
                        """
                    [
                      {"email":"secondary@test.com","primary":false,"verified":true,"visibility":null},
                      {"email":"primary@test.com","primary":true,"verified":true,"visibility":"public"}
                    ]
                        """.trimIndent()
                    )
                }

                else -> {
                    exchange.respond(404, """{"error":"not found"}""")
                }
            }
        }.use { server ->
            withProperties(
                mapOf(
                    "GITHUB_OAUTH_CLIENT_ID" to "client-123",
                    "GITHUB_OAUTH_CLIENT_SECRET" to "secret-123",
                    "FRONTEND_URL" to "https://api.test.local",
                    "GITHUB_OAUTH_BASE_URL" to server.baseUrl,
                    "GITHUB_API_BASE_URL" to server.baseUrl
                )
            ) {
                val service = OAuthService()
                val user = runBlocking { service.handleGitHubCallback("code-2") }
                assertEquals("primary@test.com", user.email)
                assertEquals("fallback", user.name)
                assertTrue(user.emailVerified)
            }
        }
    }

    @Test
    fun `handleGitHubCallback fails when no verified email exists`() {
        MockHttpServer { exchange ->
            when (exchange.requestURI.path) {
                "/login/oauth/access_token" -> {
                    exchange.respond(
                        200,
                        """{"access_token":"token-3","token_type":"bearer","scope":"user:email"}"""
                    )
                }

                "/user" -> {
                    exchange.respond(200, """{"id":88,"login":"noemail","email":null,"name":"No Email"}""")
                }

                "/user/emails" -> {
                    exchange.respond(
                        200,
                        """
                    [
                      {"email":"not-verified@test.com","primary":true,"verified":false,"visibility":null}
                    ]
                        """.trimIndent()
                    )
                }

                else -> {
                    exchange.respond(404, """{"error":"not found"}""")
                }
            }
        }.use { server ->
            withProperties(
                mapOf(
                    "GITHUB_OAUTH_CLIENT_ID" to "client-123",
                    "GITHUB_OAUTH_CLIENT_SECRET" to "secret-123",
                    "FRONTEND_URL" to "https://api.test.local",
                    "GITHUB_OAUTH_BASE_URL" to server.baseUrl,
                    "GITHUB_API_BASE_URL" to server.baseUrl
                )
            ) {
                val service = OAuthService()
                assertFailsWith<IllegalArgumentException> {
                    runBlocking { service.handleGitHubCallback("code-3") }
                }
            }
        }
    }

    private fun setupOAuthDatabase() {
        db = db ?: Database.connect(
            url = "jdbc:h2:mem:moneat_oauth_service;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships)
    }

    private fun seedOAuthUser(
        email: String,
        provider: String? = "github",
        providerId: String? = "provider-id",
        emailVerified: Boolean = true,
    ): OAuthFixture =
        transaction {
            val organizationId = Organizations.insert {
                it[name] = "OAuth Org"
                it[slug] = "oauth-org-${email.substringBefore('@')}"
            } get Organizations.id
            val organizationResourceId = Organizations
                .selectAll()
                .where { Organizations.id eq organizationId }
                .first()[Organizations.resource_id]
                .toString()
            val userId = Users.insert {
                it[Users.email] = email
                it[password_hash] = "password-hash"
                it[name] = "OAuth User"
                it[email_verified] = emailVerified
                it[onboarding_completed] = true
                it[oauth_provider] = provider
                it[oauth_provider_id] = providerId
            } get Users.id
            val userResourceId = Users
                .selectAll()
                .where { Users.id eq userId }
                .first()[Users.resource_id]
                .toString()
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = organizationId
                it[role] = "admin"
            }

            OAuthFixture(
                userId = userId,
                userResourceId = userResourceId,
                organizationResourceId = organizationResourceId,
            )
        }

    private fun <T> withOAuthProperties(block: () -> T): T =
        withProperties(
            mapOf(
                "FRONTEND_URL" to "https://dashboard.test.local",
                "BACKEND_URL" to "https://api.test.local",
            ),
            block,
        )

    private fun <T> withProperties(
        properties: Map<String, String>,
        block: () -> T
    ): T {
        val previous = properties.keys.associateWith { System.getProperty(it) }
        properties.forEach { (k, v) -> System.setProperty(k, v) }
        return try {
            block()
        } finally {
            previous.forEach { (k, v) ->
                if (v == null) {
                    System.clearProperty(k)
                } else {
                    System.setProperty(k, v)
                }
            }
        }
    }

    private data class OAuthFixture(
        val userId: Int,
        val userResourceId: String,
        val organizationResourceId: String,
    )
}
