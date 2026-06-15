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

package com.moneat.notifications.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.shared.models.UserDeviceTokens
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import io.ktor.client.request.delete
import io.ktor.client.request.get
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PushDeviceRoutesTest {
    private val jwtSecret =
        ByteArray(32)
            .also { SecureRandom().nextBytes(it) }
            .let { Base64.getEncoder().encodeToString(it) }

    companion object {
        private var db: Database? = null
        private const val USER_ID = 1
        private const val DEVICE_TOKEN = "ExponentPushToken[settings-test-token]"
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_push_device_routes;MODE=MYSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Users, UserDeviceTokens)
        transaction {
            Users.insert {
                it[id] = USER_ID
                it[email] = "push@example.com"
                it[password_hash] = "hash"
                it[email_verified] = true
            }
        }
    }

    @Test
    fun `register list and delete push device`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            installAuth()
            routing {
                pushDeviceRoutes()
            }
        }

        val register =
            client.post("/v1/user/push-devices") {
                header(HttpHeaders.Authorization, "Bearer ${token(USER_ID)}")
                contentType(ContentType.Application.Json)
                setBody("""{"token":"$DEVICE_TOKEN","platform":"ios","deviceName":"Alder iPhone"}""")
            }

        assertEquals(HttpStatusCode.OK, register.status)
        val registerBody = register.bodyAsText()
        assertTrue(registerBody.contains("\"platform\":\"IOS\""))
        assertTrue(registerBody.contains("\"label\":\"Alder iPhone\""))
        assertTrue(registerBody.contains("\"lastActiveAt\""))
        assertTrue(registerBody.contains("Alder iPhone"))
        assertFalse(registerBody.contains(DEVICE_TOKEN))
        val deviceId = Json.parseToJsonElement(registerBody).jsonObject["id"]!!.jsonPrimitive.content

        val list =
            client.get("/v1/user/push-devices") {
                header(HttpHeaders.Authorization, "Bearer ${token(USER_ID)}")
            }
        assertEquals(HttpStatusCode.OK, list.status)
        assertTrue(list.bodyAsText().contains(deviceId))

        val delete =
            client.delete("/v1/user/push-devices/$deviceId") {
                header(HttpHeaders.Authorization, "Bearer ${token(USER_ID)}")
            }
        assertEquals(HttpStatusCode.NoContent, delete.status)
    }

    private fun token(userId: Int): String =
        JWT.create()
            .withClaim("userId", userId)
            .sign(Algorithm.HMAC256(jwtSecret))

    private fun io.ktor.server.application.Application.installAuth() {
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(JWT.require(Algorithm.HMAC256(jwtSecret)).build())
                validate { credential -> JWTPrincipal(credential.payload) }
            }
        }
    }
}
