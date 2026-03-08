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

package com.moneat.utils

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthCookieUtilsTest {

    private fun cookieTest(
        handler: suspend (ApplicationCall) -> Unit,
        assertions: (String) -> Unit
    ) = testApplication {
        // Disable auto-loading of application.conf module
        environment {
            config =
                io.ktor.server.config
                    .MapApplicationConfig()
        }
        routing {
            get("/test") {
                handler(call)
                call.respondText("ok")
            }
        }
        val response = client.get("/test")
        val cookies = response.headers.getAll(HttpHeaders.SetCookie)
        assertNotNull(cookies, "Expected Set-Cookie header")
        val cookie = cookies.first { it.contains("auth_token") }
        assertions(cookie)
    }

    @Test
    fun `setAuthCookie sets cookie with correct name and value`() =
        cookieTest(
            handler = { call -> AuthCookieUtils.setAuthCookie(call, "test-jwt-token") },
            assertions = { cookie -> assertTrue(cookie.contains("auth_token=test-jwt-token")) }
        )

    @Test
    fun `setAuthCookie sets httpOnly flag`() =
        cookieTest(
            handler = { call -> AuthCookieUtils.setAuthCookie(call, "test-token") },
            assertions = { cookie -> assertTrue(cookie.lowercase().contains("httponly"), "Cookie should be httpOnly") }
        )

    @Test
    fun `setAuthCookie sets path to root`() =
        cookieTest(
            handler = { call -> AuthCookieUtils.setAuthCookie(call, "test-token") },
            assertions = { cookie -> assertTrue(cookie.contains("Path=/"), "Cookie path should be /") }
        )

    @Test
    fun `setAuthCookie sets 1 hour max age`() =
        cookieTest(
            handler = { call -> AuthCookieUtils.setAuthCookie(call, "test-token") },
            assertions = { cookie -> assertTrue(cookie.contains("Max-Age=3600"), "Cookie should have 1 hour max age") }
        )

    @Test
    fun `setDemoCookie sets 24 hour max age`() =
        cookieTest(
            handler = { call -> AuthCookieUtils.setDemoCookie(call, "demo-token") },
            assertions = { cookie ->
                assertTrue(
                    cookie.contains("Max-Age=86400"),
                    "Demo cookie should have 24 hour max age"
                )
            }
        )

    @Test
    fun `clearAuthCookie expires the cookie`() =
        testApplication {
            environment {
                config =
                    io.ktor.server.config
                        .MapApplicationConfig()
            }
            routing {
                get("/test") {
                    AuthCookieUtils.clearAuthCookie(call)
                    call.respondText("ok")
                }
            }
            val response = client.get("/test")
            val cookies = response.headers.getAll(HttpHeaders.SetCookie)
            assertNotNull(cookies, "Expected Set-Cookie header")
            val cookie = cookies.first { it.contains("auth_token") }
            // Cookie with maxAge=0 should have an expiration in the past
            // Ktor renders maxAge=0 as an Expires header with epoch date
            assertTrue(
                cookie.contains("Max-Age=0") ||
                    cookie.lowercase().contains("expires=") ||
                    cookie.contains("auth_token=;") || cookie.contains("auth_token= ;"),
                "Cleared cookie should be expired but got: $cookie"
            )
        }

    @Test
    fun `clearAuthCookie sets empty value`() =
        cookieTest(
            handler = { call -> AuthCookieUtils.clearAuthCookie(call) },
            assertions = { cookie ->
                assertTrue(
                    cookie.contains("auth_token=;") || cookie.contains("auth_token= ;"),
                    "Cleared cookie should have empty value"
                )
            }
        )

    @Test
    fun `setAuthCookie uses Strict SameSite for HTTPS backend`() =
        cookieTest(
            handler = { call -> AuthCookieUtils.setAuthCookie(call, "test-token") },
            assertions = { cookie ->
                assertTrue(
                    cookie.contains("SameSite=Strict"),
                    "HTTPS backend should use Strict SameSite"
                )
            }
        )
}
