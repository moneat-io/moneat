package com.moneat.utils

import io.ktor.http.*
import io.ktor.server.application.*
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
