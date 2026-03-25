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

package com.moneat.datadog.routes

import com.moneat.datadog.decompression.DecompressionService
import com.moneat.datadog.services.DatadogJfrFlamegraphService
import com.moneat.datadog.services.DatadogPprofFlamegraphService
import com.moneat.datadog.services.ProfileIngestionService
import com.moneat.datadog.services.ProfileStorageService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200

fun Route.profileDashboardRoutes() {
    authenticate("auth-jwt") {
        route("/v1/profiles") {
            // GET /v1/dd/profiles - list profiles
            get {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal?.payload
                    ?.getClaim("orgId")?.asInt()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "Invalid token")
                    )
                val service = call.parameters["service"]
                val profileType = call.parameters["type"]
                val source = call.parameters["source"]
                val limit = (
                    call.parameters["limit"]
                        ?.toIntOrNull() ?: DEFAULT_LIMIT
                    )
                    .coerceAtMost(MAX_LIMIT)
                val offset = call.parameters["offset"]
                    ?.toIntOrNull() ?: 0

                val result = ProfileIngestionService.listProfiles(
                    orgId,
                    service,
                    profileType,
                    source,
                    limit,
                    offset
                )
                call.respond(result)
            }

            // GET /v1/profiles/{profileId}/download - pprof
            get("/{profileId}/download") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal?.payload
                    ?.getClaim("orgId")?.asInt()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "Invalid token")
                    )
                val profileId = call.parameters["profileId"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Missing profileId")
                    )

                val meta = ProfileIngestionService.getProfileMeta(
                    orgId,
                    profileId
                )
                if (meta == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Profile not found")
                    )
                    return@get
                }

                val data = ProfileStorageService.read(meta.storageKey)
                if (data == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Profile data not found")
                    )
                    return@get
                }

                val normalizedType = meta.profileType.lowercase()
                val downloadData = if (normalizedType == "jfr") {
                    runCatching {
                        DecompressionService.decompress(data, null)
                    }.getOrElse { data }
                } else {
                    data
                }

                val fileName = profileDownloadFilename(
                    profileId = profileId,
                    profileType = normalizedType,
                    data = downloadData
                )
                call.response.headers.append(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"$fileName\""
                )

                call.respondBytes(
                    downloadData,
                    ContentType.Application.OctetStream,
                    HttpStatusCode.OK,
                )
            }

            // GET /v1/profiles/{profileId}/flamegraph
            get("/{profileId}/flamegraph") {
                val principal = call.principal<JWTPrincipal>()
                val orgId = principal?.payload
                    ?.getClaim("orgId")?.asInt()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "Invalid token")
                    )
                val profileId = call.parameters["profileId"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Missing profileId")
                    )

                val meta =
                    ProfileIngestionService.getProfileMeta(
                        orgId,
                        profileId
                    )
                if (meta == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Profile not found")
                    )
                    return@get
                }

                val data = ProfileStorageService.read(meta.storageKey)
                if (data == null) {
                    call.respondText(
                        emptyFlamegraph().toString(),
                        ContentType.Application.Json,
                    )
                    return@get
                }

                val frames = when (meta.source) {
                    "sentry" -> parseSentryProfileToFrames(data)
                    "datadog" -> {
                        val isJfrType = meta.profileType.equals("jfr", ignoreCase = true)
                        val isJfrPayload =
                            DatadogPprofFlamegraphService.isLikelyJfrPayload(data)
                        if (isJfrType || isJfrPayload) {
                            DatadogJfrFlamegraphService.parseToFrames(data)
                        } else {
                            DatadogPprofFlamegraphService.parseToFrames(data)
                        }
                    }
                    else -> emptyFlamegraph()
                }

                call.respondText(
                    frames.toString(),
                    ContentType.Application.Json,
                )
            }
        }
    }
}

private fun emptyFlamegraph(): JsonObject = buildJsonObject {
    put("frames", buildJsonArray {})
}

private val profileJson = Json { ignoreUnknownKeys = true }

private fun profileDownloadFilename(
    profileId: String,
    profileType: String,
    data: ByteArray,
): String {
    val ext = when {
        profileType == "jfr" -> "jfr"
        isGzip(data) -> "pprof.gz"
        else -> "pprof"
    }
    return "profile-$profileId.$ext"
}

private fun isGzip(data: ByteArray): Boolean {
    return data.size >= 2 &&
        data[0] == 0x1f.toByte() &&
        data[1] == 0x8b.toByte()
}

/**
 * Parse a Sentry profile JSON (frames[], stacks[], samples[])
 * into FlamegraphFrame[] tree format.
 */
private fun parseSentryProfileToFrames(
    data: ByteArray,
): Map<String, Any> {
    return try {
        val root = profileJson.parseToJsonElement(
            String(data)
        ).jsonObject
        val profileObj = root["profile"]?.jsonObject
            ?: return buildJsonObject {
                put("frames", buildJsonArray {})
            }

        val frames = profileObj["frames"]?.jsonArray
            ?: return buildJsonObject {
                put("frames", buildJsonArray {})
            }
        val stacks = profileObj["stacks"]?.jsonArray
            ?: return buildJsonObject {
                put("frames", buildJsonArray {})
            }
        val samples = profileObj["samples"]?.jsonArray
            ?: return buildJsonObject {
                put("frames", buildJsonArray {})
            }

        data class MutableFrame(
            val name: String,
            var value: Int = 0,
            val children: MutableMap<String, MutableFrame> =
                mutableMapOf(),
        )

        val rootFrame = MutableFrame("(root)")

        for (sample in samples) {
            val stackIdx = sample.jsonObject["stack_id"]
                ?.jsonPrimitive?.int ?: continue
            if (stackIdx >= stacks.size) continue
            val stack = stacks[stackIdx].jsonArray

            // Walk from bottom (root) to top (leaf)
            var current = rootFrame
            for (i in stack.size - 1 downTo 0) {
                val frameIdx = stack[i].jsonPrimitive.int
                if (frameIdx >= frames.size) continue
                val frameObj = frames[frameIdx].jsonObject
                val fn = frameObj["function"]
                    ?.jsonPrimitive?.content ?: "unknown"
                val module = frameObj["module"]
                    ?.jsonPrimitive?.content
                val name = if (module != null) {
                    "$module.$fn"
                } else {
                    fn
                }
                current = current.children.getOrPut(name) {
                    MutableFrame(name)
                }
                current.value++
            }
        }

        fun toJson(f: MutableFrame): JsonObject = buildJsonObject {
            put("name", f.name)
            put("value", f.value)
            put(
                "children",
                buildJsonArray {
                    for (child in f.children.values.sortedByDescending {
                        it.value
                    }) {
                        add(toJson(child))
                    }
                }
            )
        }

        buildJsonObject {
            put(
                "frames",
                buildJsonArray {
                    for (child in rootFrame.children.values
                        .sortedByDescending { it.value }) {
                        add(toJson(child))
                    }
                }
            )
        }
    } catch (e: Exception) {
        buildJsonObject { put("frames", buildJsonArray {}) }
    }
}
