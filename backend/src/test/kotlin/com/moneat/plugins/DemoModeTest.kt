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

package com.moneat.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.config.EnvConfig
import io.ktor.client.request.cookie
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class DemoModeTest {
    private companion object {
        const val JWT_SECRET = "demo-mode-test-secret"
    }

    @Test
    fun `demo auth cookie does not block signup`() {
        withJwtSecret {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    configureDemoModeRestrictions()
                    routing {
                        post("/auth/signup") {
                            call.respond(HttpStatusCode.Created)
                        }
                    }
                }

                val response =
                    client.post("/auth/signup") {
                        cookie("auth_token", createDemoToken())
                    }

                assertEquals(HttpStatusCode.Created, response.status)
            }
        }
    }

    @Test
    fun `demo auth cookie still blocks ordinary writes`() {
        withJwtSecret {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    configureDemoModeRestrictions()
                    routing {
                        post("/v1/projects") {
                            call.respond(HttpStatusCode.Created)
                        }
                    }
                }

                val response =
                    client.post("/v1/projects") {
                        cookie("auth_token", createDemoToken())
                    }

                assertEquals(HttpStatusCode.Forbidden, response.status)
            }
        }
    }

    private fun createDemoToken(): String =
        JWT
            .create()
            .withClaim("isDemo", true)
            .withClaim("userId", EnvConfig.Demo.USER_ID)
            .withClaim("email", EnvConfig.Demo.USER_EMAIL)
            .sign(Algorithm.HMAC256(JWT_SECRET))

    private fun <T> withJwtSecret(block: () -> T): T {
        val previousSecret = System.getProperty("JWT_SECRET")
        System.setProperty("JWT_SECRET", JWT_SECRET)
        return try {
            block()
        } finally {
            if (previousSecret == null) {
                System.clearProperty("JWT_SECRET")
            } else {
                System.setProperty("JWT_SECRET", previousSecret)
            }
        }
    }
}
