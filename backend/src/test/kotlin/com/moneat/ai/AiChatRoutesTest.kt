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

package com.moneat.ai

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.models.Users
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiChatRoutesTest {
    private val jwtSecret = "ai-chat-routes-secret"

    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_ai_routes;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction {
                SchemaUtils.create(Users)
            }
            dbInitialized = true
        }

        transaction {
            Users.deleteAll()
            Users.insert {
                it[id] = 101
                it[email] = "non-admin@test.com"
                it[name] = "Non Admin"
                it[password_hash] = "hash"
                it[email_verified] = true
                it[oauth_provider] = "local"
                it[is_admin] = false
            }
        }

        System.setProperty("OPENAI_API_KEY", "test-key")
    }

    @AfterTest
    fun teardown() {
        System.clearProperty("OPENAI_API_KEY")
    }

    @Test
    fun `chat endpoint rejects non-admin user before org lookup`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(Authentication) {
                    jwt("auth-jwt") {
                        verifier(
                            JWT
                                .require(Algorithm.HMAC256(jwtSecret))
                                .withIssuer("moneat")
                                .withAudience("moneat-users")
                                .build()
                        )
                        validate { JWTPrincipal(it.payload) }
                    }
                }
                routing { aiChatRoutes() }
            }

            val response =
                client.post("/v1/ai/chat") {
                    header(HttpHeaders.Authorization, "Bearer ${tokenForUser(101)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"message":"hello"}""")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("admin users"))
        }

    private fun tokenForUser(userId: Int): String {
        return JWT
            .create()
            .withIssuer("moneat")
            .withAudience("moneat-users")
            .withClaim("userId", userId)
            .sign(Algorithm.HMAC256(jwtSecret))
    }
}
