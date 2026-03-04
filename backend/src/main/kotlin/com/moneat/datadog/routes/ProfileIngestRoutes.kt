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

import com.moneat.billing.services.BillingQuotaService
import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.datadog.decompression.DecompressionService
import com.moneat.datadog.models.DdProfileEvent
import com.moneat.datadog.services.DatadogPprofFlamegraphService
import com.moneat.datadog.services.ProfileIngestionService
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

internal data class ProfileUploadFilePart(
    val partName: String?,
    val fileName: String,
    val data: ByteArray,
)

fun Route.profileIngestRoutes(
    quotaService: BillingQuotaService = BillingQuotaService(),
) {
    route("/dd/profiling/v1") {
        // POST /dd/profiling/v1/input - multipart pprof upload
        post("/input") {
            val organizationId = DatadogAuthMiddleware.authenticate(call)
                ?: return@post

            var event: DdProfileEvent? = null
            val fileParts = mutableListOf<ProfileUploadFilePart>()

            val multipart = call.receiveMultipart()
            multipart.forEachPart { part ->
                val partName = part.name
                when {
                    // event can arrive as FormItem or FileItem (when agent sets Content-Type: application/json)
                    partName == "event" && part is PartData.FormItem -> {
                        runCatching {
                            event = json.decodeFromString<DdProfileEvent>(part.value)
                        }.onFailure { e ->
                            logger.warn { "Failed to parse profiling event JSON: ${e.message}" }
                        }
                    }
                    partName == "event" && part is PartData.FileItem -> {
                        runCatching {
                            val bytes = part.provider().toByteArray()
                            event = json.decodeFromString<DdProfileEvent>(bytes.decodeToString())
                        }.onFailure { e ->
                            logger.warn { "Failed to parse profiling event JSON (file part): ${e.message}" }
                        }
                    }
                    part is PartData.FileItem && partName != "event" -> {
                        val rawBytes = part.provider().toByteArray()
                        val bytes = runCatching {
                            DecompressionService.decompress(
                                rawBytes,
                                part.headers["Content-Encoding"]
                            )
                        }.getOrElse { e ->
                            logger.warn { "Failed to decompress profile part: ${e.message}" }
                            rawBytes
                        }
                        val filename = part.originalFileName ?: partName ?: ""
                        fileParts += ProfileUploadFilePart(
                            partName = partName,
                            fileName = filename,
                            data = bytes,
                        )
                    }
                }
                part.dispose()
            }

            // Filter to parseable profile files (pprof or JFR)
            val profileParts = fileParts.filter { part ->
                DatadogPprofFlamegraphService.isSupportedPprof(part.data) ||
                    DatadogPprofFlamegraphService.isLikelyJfrPayload(part.data) ||
                    isLikelyPprofName(part.fileName) ||
                    isLikelyPprofName(part.partName.orEmpty()) ||
                    isLikelyJfrName(part.fileName) ||
                    isLikelyJfrName(part.partName.orEmpty())
            }.ifEmpty { fileParts }

            val selectedProfile = selectProfilePart(profileParts)
            if (selectedProfile == null) {
                logger.warn {
                    "Profile upload rejected for org $organizationId: no parseable profile data (pprof/JFR) " +
                        "(event=${event != null}, files=${fileParts.size}, " +
                        "file_names=${fileParts.map { it.fileName }})"
                }
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Missing profile data (pprof/JFR)")
                )
                return@post
            }

            val totalBytes = profileParts.sumOf { it.data.size }.toLong()
            val reservation = quotaService.reserveUnits(
                organizationId = organizationId,
                requestedUnits = 1,
                eventType = "dd_profile",
                requestedBytes = totalBytes,
            )
            if (!reservation.allowed) {
                call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "quota_exceeded"))
                return@post
            }

            // If event is missing synthesize a minimal one so ingestion still works
            val resolvedEvent = event ?: DdProfileEvent()
            val profileType = inferProfileType(selectedProfile)

            val profileId = ProfileIngestionService.ingestProfile(
                organizationId = organizationId,
                event = resolvedEvent,
                fileParts = profileParts.map { it.fileName to it.data },
                profileType = profileType,
            )

            call.respond(
                HttpStatusCode.OK,
                mapOf("profile_id" to profileId)
            )
        }
    }
}

internal fun selectPprofPart(
    fileParts: List<ProfileUploadFilePart>,
): ProfileUploadFilePart? {
    if (fileParts.isEmpty()) return null

    val namedPprof = fileParts.filter {
        isLikelyPprofName(it.fileName) || isLikelyPprofName(it.partName.orEmpty())
    }

    namedPprof.firstOrNull {
        DatadogPprofFlamegraphService.isSupportedPprof(it.data)
    }?.let { return it }

    return fileParts.firstOrNull {
        DatadogPprofFlamegraphService.isSupportedPprof(it.data)
    }
}

internal fun selectProfilePart(
    fileParts: List<ProfileUploadFilePart>,
): ProfileUploadFilePart? {
    selectPprofPart(fileParts)?.let { return it }

    fileParts.firstOrNull {
        DatadogPprofFlamegraphService.isLikelyJfrPayload(it.data)
    }?.let { return it }

    fileParts.firstOrNull {
        isLikelyJfrName(it.fileName) || isLikelyJfrName(it.partName.orEmpty())
    }?.let { return it }

    return null
}

internal fun inferProfileType(part: ProfileUploadFilePart): String {
    if (DatadogPprofFlamegraphService.isLikelyJfrPayload(part.data)) {
        return "jfr"
    }
    return inferProfileTypeFromFilename(part.fileName)
}

internal fun inferProfileTypeFromFilename(filename: String): String {
    val normalized = filename.lowercase()
    return when {
        "heap" in normalized -> "heap"
        "alloc" in normalized -> "alloc"
        "mutex" in normalized -> "mutex"
        "block" in normalized -> "block"
        "goroutine" in normalized -> "goroutine"
        "wall" in normalized -> "wall"
        "jfr" in normalized -> "jfr"
        else -> "cpu"
    }
}

private fun isLikelyPprofName(value: String): Boolean {
    val normalized = value.lowercase()
    return normalized.contains("pprof") ||
        normalized.endsWith(".pb") ||
        normalized.endsWith(".pb.gz")
}

private fun isLikelyJfrName(value: String): Boolean {
    val normalized = value.lowercase()
    return normalized.contains("jfr") ||
        normalized.endsWith(".jfr") ||
        normalized.endsWith(".jfr.gz")
}
