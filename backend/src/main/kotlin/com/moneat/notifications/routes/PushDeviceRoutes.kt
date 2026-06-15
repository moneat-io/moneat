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

import com.moneat.shared.models.UserDeviceTokens
import com.moneat.shared.services.toUuidOrNull
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.Locale
import kotlin.time.Clock

private const val MIN_DEVICE_TOKEN_LENGTH = 8
private const val MAX_DEVICE_TOKEN_LENGTH = 500
private const val MAX_DEVICE_NAME_LENGTH = 255
private val supportedPushPlatforms = setOf("IOS", "ANDROID", "WEB")

@Serializable
data class RegisterPushDeviceRequest(
    val token: String,
    val platform: String,
    val deviceName: String? = null,
)

@Serializable
data class PushDeviceResponse(
    val id: String,
    val label: String,
    val platform: String,
    val deviceName: String? = null,
    val createdAt: String,
    val lastUsedAt: String,
    val lastActiveAt: String,
)

@Serializable
data class PushDevicesResponse(
    val devices: List<PushDeviceResponse>,
)

fun Route.pushDeviceRoutes() {
    route("/v1/user/push-devices") {
        authenticate("auth-jwt") {
            get {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                val devices =
                    transaction {
                        UserDeviceTokens
                            .selectAll()
                            .where {
                                (UserDeviceTokens.userId eq userId) and
                                    (UserDeviceTokens.enabled eq true)
                            }
                            .orderBy(UserDeviceTokens.lastUsedAt, SortOrder.DESC)
                            .map(::pushDeviceResponse)
                    }
                call.respond(PushDevicesResponse(devices))
            }

            post {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                val request = call.receive<RegisterPushDeviceRequest>()
                val token = request.token.trim()
                val platform = request.platform.trim().uppercase(Locale.US)
                val deviceName = request.deviceName?.trim()?.takeIf { it.isNotBlank() }

                if (token.length !in MIN_DEVICE_TOKEN_LENGTH..MAX_DEVICE_TOKEN_LENGTH) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid push device token"))
                    return@post
                }
                if (platform !in supportedPushPlatforms) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unsupported push platform"))
                    return@post
                }
                if (deviceName != null && deviceName.length > MAX_DEVICE_NAME_LENGTH) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Device name must be 255 characters or less"))
                    return@post
                }

                val now = Clock.System.now()
                val device =
                    transaction {
                        val existing =
                            UserDeviceTokens
                                .selectAll()
                                .where { UserDeviceTokens.deviceToken eq token }
                                .firstOrNull()

                        if (existing == null) {
                            UserDeviceTokens.insert {
                                it[UserDeviceTokens.userId] = userId
                                it[deviceToken] = token
                                it[UserDeviceTokens.platform] = platform
                                it[UserDeviceTokens.deviceName] = deviceName
                                it[enabled] = true
                                it[createdAt] = now
                                it[updatedAt] = now
                                it[lastUsedAt] = now
                            }
                        } else {
                            UserDeviceTokens.update({ UserDeviceTokens.id eq existing[UserDeviceTokens.id] }) {
                                it[UserDeviceTokens.userId] = userId
                                it[UserDeviceTokens.platform] = platform
                                it[UserDeviceTokens.deviceName] = deviceName
                                it[enabled] = true
                                it[updatedAt] = now
                                it[lastUsedAt] = now
                            }
                        }

                        UserDeviceTokens
                            .selectAll()
                            .where {
                                (UserDeviceTokens.userId eq userId) and
                                    (UserDeviceTokens.deviceToken eq token)
                            }
                            .first()
                            .let(::pushDeviceResponse)
                    }

                call.respond(HttpStatusCode.OK, device)
            }

            delete("/{deviceId}") {
                val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                val deviceId =
                    call.parameters["deviceId"]?.toUuidOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid device ID"))

                val deleted =
                    transaction {
                        UserDeviceTokens.deleteWhere {
                            (UserDeviceTokens.userId eq userId) and
                                (UserDeviceTokens.resourceId eq deviceId)
                        }
                    }

                if (deleted == 0) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Push device not found"))
                } else {
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

private fun pushDeviceResponse(row: ResultRow): PushDeviceResponse =
    PushDeviceResponse(
        id = row[UserDeviceTokens.resourceId].toString(),
        label = row[UserDeviceTokens.deviceName] ?: row[UserDeviceTokens.platform],
        platform = row[UserDeviceTokens.platform],
        deviceName = row[UserDeviceTokens.deviceName],
        createdAt = row[UserDeviceTokens.createdAt].toString(),
        lastUsedAt = row[UserDeviceTokens.lastUsedAt].toString(),
        lastActiveAt = row[UserDeviceTokens.lastUsedAt].toString(),
    )
