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

package com.moneat.auth

import com.auth0.jwt.interfaces.DecodedJWT
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond

data class CurrentOrgContext(
    val userId: Int,
    val orgId: Int,
    val orgRole: String?,
)

fun JWTPrincipal.currentUserIdOrNull(): Int? =
    payload.getClaim("userId").asInt()

fun JWTPrincipal.currentOrgIdOrNull(): Int? =
    payload.getClaim("orgId").asInt()

fun JWTPrincipal.currentOrgContextOrNull(): CurrentOrgContext? {
    val userId = currentUserIdOrNull() ?: return null
    val orgId = currentOrgIdOrNull() ?: return null

    return CurrentOrgContext(
        userId = userId,
        orgId = orgId,
        orgRole = payload.getClaim("orgRole").asString(),
    )
}

fun DecodedJWT.currentUserIdOrNull(): Int? =
    getClaim("userId").asInt()

fun DecodedJWT.currentOrgIdOrNull(): Int? =
    getClaim("orgId").asInt()

fun DecodedJWT.currentOrgContextOrNull(): CurrentOrgContext? {
    val userId = currentUserIdOrNull() ?: return null
    val orgId = currentOrgIdOrNull() ?: return null

    return CurrentOrgContext(
        userId = userId,
        orgId = orgId,
        orgRole = getClaim("orgRole").asString(),
    )
}

fun ApplicationCall.currentOrgContextOrNull(): CurrentOrgContext? =
    principal<JWTPrincipal>()?.currentOrgContextOrNull()

fun ApplicationCall.currentOrgIdOrNull(): Int? =
    currentOrgContextOrNull()?.orgId

suspend fun ApplicationCall.requireCurrentOrg(
    errorMessage: String = "Invalid token",
): CurrentOrgContext? {
    val context = currentOrgContextOrNull()
    if (context == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse(errorMessage))
    }
    return context
}
