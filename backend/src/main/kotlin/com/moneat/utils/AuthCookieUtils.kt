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

import com.moneat.config.EnvConfig
import io.ktor.http.Cookie
import io.ktor.server.application.ApplicationCall

object AuthCookieUtils {

    private const val AUTH_COOKIE_TTL = 3600 // 1 hour
    private const val DEMO_COOKIE_TTL = 86400 // 24 hours
    private const val REFRESH_COOKIE_TTL = 604800 // 7 days

    private fun isSecure(): Boolean {
        val backendUrl = EnvConfig.get("BACKEND_URL", "https://api.moneat.io")
        return backendUrl.startsWith("https://")
    }

    fun setAuthCookie(
        call: ApplicationCall,
        token: String
    ) {
        val secure = isSecure()
        call.response.cookies.append(
            Cookie(
                name = "auth_token",
                value = token,
                httpOnly = true,
                secure = secure,
                path = "/",
                maxAge = AUTH_COOKIE_TTL,
                extensions = mapOf("SameSite" to if (secure) "Strict" else "Lax")
            )
        )
    }

    fun setDemoCookie(
        call: ApplicationCall,
        token: String
    ) {
        val secure = isSecure()
        call.response.cookies.append(
            Cookie(
                name = "auth_token",
                value = token,
                httpOnly = true,
                secure = secure,
                path = "/",
                maxAge = DEMO_COOKIE_TTL,
                extensions = mapOf("SameSite" to if (secure) "Strict" else "Lax")
            )
        )
    }

    fun setRefreshCookie(
        call: ApplicationCall,
        token: String
    ) {
        val secure = isSecure()
        call.response.cookies.append(
            Cookie(
                name = "refresh_token",
                value = token,
                httpOnly = true,
                secure = secure,
                path = "/auth",
                maxAge = REFRESH_COOKIE_TTL,
                extensions = mapOf("SameSite" to if (secure) "Strict" else "Lax")
            )
        )
    }

    fun clearRefreshCookie(call: ApplicationCall) {
        val secure = isSecure()
        call.response.cookies.append(
            Cookie(
                name = "refresh_token",
                value = "",
                httpOnly = true,
                secure = secure,
                path = "/auth",
                maxAge = 0,
                extensions = mapOf("SameSite" to if (secure) "Strict" else "Lax")
            )
        )
    }

    fun clearAuthCookie(call: ApplicationCall) {
        val secure = isSecure()
        call.response.cookies.append(
            Cookie(
                name = "auth_token",
                value = "",
                httpOnly = true,
                secure = secure,
                path = "/",
                maxAge = 0,
                extensions = mapOf("SameSite" to if (secure) "Strict" else "Lax")
            )
        )
    }
}
