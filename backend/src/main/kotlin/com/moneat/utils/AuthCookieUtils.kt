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

import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.*

object AuthCookieUtils {
    fun setAuthCookie(call: ApplicationCall, token: String) {
        val isSecure = call.request.origin.scheme == "https"
        call.response.cookies.append(
            Cookie(
                name = "auth_token",
                value = token,
                httpOnly = true,
                secure = isSecure,
                path = "/",
                maxAge = 3600, // 1 hour, matches JWT expiration
                extensions = mapOf("SameSite" to if (isSecure) "Strict" else "Lax")
            )
        )
    }

    fun setDemoCookie(call: ApplicationCall, token: String) {
        val isSecure = call.request.origin.scheme == "https"
        call.response.cookies.append(
            Cookie(
                name = "auth_token",
                value = token,
                httpOnly = true,
                secure = isSecure,
                path = "/",
                maxAge = 86400, // 24 hours, matches demo JWT expiration
                extensions = mapOf("SameSite" to if (isSecure) "Strict" else "Lax")
            )
        )
    }

    fun clearAuthCookie(call: ApplicationCall) {
        val isSecure = call.request.origin.scheme == "https"
        call.response.cookies.append(
            Cookie(
                name = "auth_token",
                value = "",
                httpOnly = true,
                secure = isSecure,
                path = "/",
                maxAge = 0,
                extensions = mapOf("SameSite" to if (isSecure) "Strict" else "Lax")
            )
        )
    }
}
