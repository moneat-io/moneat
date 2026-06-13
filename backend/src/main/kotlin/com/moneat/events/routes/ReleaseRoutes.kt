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

package com.moneat.events.routes

import kotlinx.serialization.SerializationException
import java.io.IOException

import com.moneat.auth.services.AuthTokenService
import com.moneat.billing.services.BillingQuotaService
import com.moneat.billing.services.QuotaExceededResponse
import com.moneat.events.models.AssembleArtifactBundleRequest
import com.moneat.events.models.AssembleDifEntry
import com.moneat.events.models.AssembleDifResponseEntry
import com.moneat.events.models.AssembleResponse
import com.moneat.events.models.ChunkUploadParameters
import com.moneat.events.models.CreateReleaseRequest
import com.moneat.events.models.DifObject
import com.moneat.events.models.SentryAuthDetails
import com.moneat.events.models.SentryAuthInfoResponse
import com.moneat.events.models.SentryAuthUser
import com.moneat.events.services.AssembledDif
import com.moneat.events.services.EventService
import com.moneat.events.services.ReleaseService
import com.moneat.plugins.AuthTokenPrincipal
import com.moneat.shared.models.Users
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.toByteArray
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.context.GlobalContext
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}
private const val FAILED_TO_UPLOAD_SOURCE_MAP = "Failed to upload source map"
private const val UPLOAD_FAILED = "Upload failed"
private const val PROJECT_NOT_FOUND = "Project not found"

private data class UploadedSourceMapChunk(
    val checksum: String,
    val bytes: ByteArray
)

private data class UploadedSourceMap(
    val fileName: String,
    val bytes: ByteArray
)

private data class SourceMapQuotaReservation(
    val organizationId: Int?,
    val units: Int,
    val bytes: Long
)

fun Route.releaseRoutes(
    releaseService: ReleaseService = GlobalContext.get().get(),
    authTokenService: AuthTokenService = GlobalContext.get().get(),
    quotaService: BillingQuotaService = GlobalContext.get().get(),
    eventService: EventService = GlobalContext.get().get(),
) {
    // Sentry-compatible auth verification endpoint (used by sentry-cli login/info)
    authenticate("auth-bearer") {
        get("/api/0/") { handleGetApiInfo() }
    }

    // Sentry-compatible release endpoints
    // These use auth-combined to support both JWT and Bearer tokens
    authenticate("auth-combined") {
        route("/api/0/organizations/{orgSlug}/releases") {
            // Create a new release
            // POST /api/0/organizations/{orgSlug}/releases/
            post("/") { handleCreateOrgRelease(releaseService, authTokenService) }

            // Get a specific release
            // GET /api/0/organizations/{orgSlug}/releases/{version}/
            get("/{version}/") { handleGetOrgRelease(authTokenService) }
        }

        // Project-specific release endpoints (more commonly used by Sentry CLI)
        route("/api/0/projects/{orgSlug}/{projectSlug}/releases") {
            // Create a new release for a specific project
            post("/") { handleCreateProjectRelease(releaseService, authTokenService) }

            // List releases for a project
            get("/") { handleListProjectReleases(releaseService, authTokenService) }

            // Upload source maps for a release
            // POST /api/0/projects/{orgSlug}/{projectSlug}/releases/{version}/files/
            post("/{version}/files/") {
                handleUploadSourceMap(releaseService, authTokenService, quotaService, eventService)
            }

            // List files for a release
            get("/{version}/files/") { handleListReleaseFiles(releaseService, authTokenService) }
        }

        // Chunk upload endpoint for sentry-cli
        // GET /api/0/organizations/{orgSlug}/chunk-upload/
        route("/api/0/organizations/{orgSlug}/chunk-upload") {
            get("/") { handleGetChunkUploadParams(releaseService) }

            // POST /api/0/organizations/{orgSlug}/chunk-upload/
            post("/") { handleUploadChunks(releaseService, quotaService) }
        }

        // Artifact bundle assemble endpoint
        // POST /api/0/organizations/{orgSlug}/artifactbundle/assemble/
        route("/api/0/organizations/{orgSlug}/artifactbundle") {
            post("/assemble/") { handleAssembleArtifactBundle(releaseService) }
        }

        // Debug information files (ProGuard mappings, dSYMs) for sentry-cli upload-proguard /
        // the Sentry Android Gradle plugin's uploadSentryProguardMappings task.
        route("/api/0/projects/{orgSlug}/{projectSlug}/files/difs") {
            // GET .../files/difs/?checksums=...  (lets sentry-cli skip already-uploaded difs)
            get("/") { handleListProjectDifs(releaseService, authTokenService) }

            // POST .../files/difs/assemble/
            post("/assemble/") { handleAssembleProjectDifs(releaseService, authTokenService) }
        }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleGetApiInfo() {
    val principal =
        call.principal<AuthTokenPrincipal>()
            ?: run {
                call.respond(HttpStatusCode.Unauthorized)
                return
            }

    val userInfo =
        transaction {
            Users
                .selectAll()
                .where { Users.id eq principal.userId }
                .firstOrNull()
                ?.let { row ->
                    SentryAuthUser(
                        email = row[Users.email],
                        id = row[Users.resource_id].toString()
                    )
                }
        }

    call.respond(
        SentryAuthInfoResponse(
            auth = SentryAuthDetails(scopes = principal.scopes),
            user = userInfo
        )
    )
}

private suspend fun io.ktor.server.routing.RoutingContext.handleCreateOrgRelease(
    releaseService: ReleaseService,
    authTokenService: AuthTokenService,
) {
    val principal =
        call.principal<AuthTokenPrincipal>()
            ?: run {
                call.respond(HttpStatusCode.Unauthorized)
                return
            }

    // Check for releases:write scope
    if (!authTokenService.hasScope(principal.scopes, "releases:write")) {
        call.respond(
            HttpStatusCode.Forbidden,
            ErrorResponse("Missing required scope: releases:write")
        )
        return
    }

    val orgSlug =
        call.parameters["orgSlug"]
            ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing organization slug"))
                return
            }

    val request = call.receive<CreateReleaseRequest>()

    // Get project ID from slug
    // For Sentry compatibility, we support the "projects" field in the request
    val projectSlug =
        request.projects?.firstOrNull()
            ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing project"))
                return
            }

    val projectId =
        releaseService.getProjectBySlug(orgSlug, projectSlug)
            ?: run {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(PROJECT_NOT_FOUND))
                return
            }

    // Verify user has access to this project
    if (!releaseService.hasProjectAccess(principal.userId, projectId)) {
        call.respond(HttpStatusCode.Forbidden)
        return
    }

    try {
        val release = releaseService.createRelease(projectId, request.version, request.ref)
        call.respond(HttpStatusCode.Created, release)
    } catch (e: IllegalArgumentException) {
        // Release might already exist, which is OK for Sentry CLI
        // Return 208 Already Reported or 200 OK
        logger.warn { "Release creation error: ${e.message}" }
        val existingRelease = releaseService.getRelease(projectId, request.version)
        if (existingRelease != null) {
            call.respond(HttpStatusCode.OK, existingRelease)
        } else {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
        }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleGetOrgRelease(
    authTokenService: AuthTokenService,
) {
    val principal =
        call.principal<AuthTokenPrincipal>()
            ?: run {
                call.respond(HttpStatusCode.Unauthorized)
                return
            }

    if (!authTokenService.hasScope(principal.scopes, "releases:read")) {
        call.respond(
            HttpStatusCode.Forbidden,
            ErrorResponse("Missing required scope: releases:read")
        )
        return
    }

    // For this endpoint, we'd need to know which project
    // Sentry CLI typically uses project-specific endpoints
    call.respond(HttpStatusCode.NotImplemented, ErrorResponse("Use project-specific endpoint"))
}

private suspend fun io.ktor.server.routing.RoutingContext.handleCreateProjectRelease(
    releaseService: ReleaseService,
    authTokenService: AuthTokenService,
) {
    val principal =
        call.principal<AuthTokenPrincipal>()
            ?: run {
                call.respond(HttpStatusCode.Unauthorized)
                return
            }

    if (!authTokenService.hasScope(principal.scopes, "releases:write")) {
        call.respond(
            HttpStatusCode.Forbidden,
            ErrorResponse("Missing required scope: releases:write")
        )
        return
    }

    val orgSlug = call.parameters["orgSlug"] ?: return
    val projectSlug = call.parameters["projectSlug"] ?: return

    val projectId =
        releaseService.getProjectBySlug(orgSlug, projectSlug)
            ?: run {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(PROJECT_NOT_FOUND))
                return
            }

    if (!releaseService.hasProjectAccess(principal.userId, projectId)) {
        call.respond(HttpStatusCode.Forbidden)
        return
    }

    val request = call.receive<CreateReleaseRequest>()

    try {
        val release = releaseService.createRelease(projectId, request.version, request.ref)
        call.respond(HttpStatusCode.Created, release)
    } catch (e: IllegalArgumentException) {
        logger.warn { "Release creation error: ${e.message}" }
        val existingRelease = releaseService.getRelease(projectId, request.version)
        if (existingRelease != null) {
            call.respond(HttpStatusCode.OK, existingRelease)
        } else {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
        }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleListProjectReleases(
    releaseService: ReleaseService,
    authTokenService: AuthTokenService,
) {
    val principal =
        call.principal<AuthTokenPrincipal>()
            ?: run {
                call.respond(HttpStatusCode.Unauthorized)
                return
            }

    if (!authTokenService.hasScope(principal.scopes, "releases:read")) {
        call.respond(HttpStatusCode.Forbidden)
        return
    }

    val orgSlug = call.parameters["orgSlug"] ?: return
    val projectSlug = call.parameters["projectSlug"] ?: return

    val projectId =
        releaseService.getProjectBySlug(orgSlug, projectSlug)
            ?: run {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(PROJECT_NOT_FOUND))
                return
            }

    if (!releaseService.hasProjectAccess(principal.userId, projectId)) {
        call.respond(HttpStatusCode.Forbidden)
        return
    }

    val releases = releaseService.listReleases(projectId)
    call.respond(releases)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleUploadSourceMap(
    releaseService: ReleaseService,
    authTokenService: AuthTokenService,
    quotaService: BillingQuotaService,
    eventService: EventService,
) {
    val principal =
        call.principal<AuthTokenPrincipal>()
            ?: run {
                call.respond(HttpStatusCode.Unauthorized)
                return
            }

    if (!authTokenService.hasScope(principal.scopes, "sourcemaps:write")) {
        call.respond(
            HttpStatusCode.Forbidden,
            ErrorResponse("Missing required scope: sourcemaps:write")
        )
        return
    }

    val orgSlug = call.parameters["orgSlug"] ?: return
    val projectSlug = call.parameters["projectSlug"] ?: return
    val version = call.parameters["version"] ?: return

    val projectId =
        releaseService.getProjectBySlug(orgSlug, projectSlug)
            ?: run {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(PROJECT_NOT_FOUND))
                return
            }

    if (!releaseService.hasProjectAccess(principal.userId, projectId)) {
        call.respond(HttpStatusCode.Forbidden)
        return
    }

    val upload = receiveSourceMapUpload() ?: return
    val quotaReservation = reserveSourceMapQuotaOrRespond(
        quotaService = quotaService,
        organizationId = eventService.getOrganizationIdForProject(projectId),
        units = 1,
        bytes = upload.bytes.size.toLong()
    ) ?: return

    uploadSourceMapOrRespond(
        releaseService = releaseService,
        quotaService = quotaService,
        quotaReservation = quotaReservation,
        projectId = projectId,
        version = version,
        upload = upload
    )
}

private suspend fun io.ktor.server.routing.RoutingContext.handleListReleaseFiles(
    releaseService: ReleaseService,
    authTokenService: AuthTokenService,
) {
    val principal =
        call.principal<AuthTokenPrincipal>()
            ?: run {
                call.respond(HttpStatusCode.Unauthorized)
                return
            }

    if (!authTokenService.hasScope(principal.scopes, "sourcemaps:read")) {
        call.respond(HttpStatusCode.Forbidden)
        return
    }

    val orgSlug = call.parameters["orgSlug"] ?: return
    val projectSlug = call.parameters["projectSlug"] ?: return
    val version = call.parameters["version"] ?: return

    val projectId =
        releaseService.getProjectBySlug(orgSlug, projectSlug)
            ?: run {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(PROJECT_NOT_FOUND))
                return
            }

    if (!releaseService.hasProjectAccess(principal.userId, projectId)) {
        call.respond(HttpStatusCode.Forbidden)
        return
    }

    val files = releaseService.listReleaseFiles(projectId, version)
    call.respond(files)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleGetChunkUploadParams(
    releaseService: ReleaseService,
) {
    val principal =
        call.principal<AuthTokenPrincipal>()
            ?: run {
                call.respond(HttpStatusCode.Unauthorized)
                return
            }

    val orgSlug = call.parameters["orgSlug"] ?: return

    if (!releaseService.hasOrgAccess(principal.userId, orgSlug)) {
        call.respond(HttpStatusCode.Forbidden)
        return
    }

    val url = "/organizations/$orgSlug/chunk-upload/"

    call.respond(
        ChunkUploadParameters(
            url = url,
            chunkSize = 8388608, // 8 MiB (power of 2 for CLI compat)
            chunksPerRequest = 64,
            maxFileSize = 2147483648, // 2 GiB
            maxRequestSize = 33554432, // 32 MiB
            concurrency = 8,
            hashAlgorithm = "sha1",
            compression = listOf("gzip"),
            accept =
            listOf(
                "debug_files",
                "release_files",
                "pdbs",
                "sources",
                "bcsymbolmaps",
                "il2cpp",
                "portablepdbs",
                "artifact_bundles",
                "artifact_bundles_v2",
                "proguard",
                "dartsymbolmap"
            )
        )
    )
}

private suspend fun io.ktor.server.routing.RoutingContext.handleUploadChunks(
    releaseService: ReleaseService,
    quotaService: BillingQuotaService,
) {
    val principal =
        call.principal<AuthTokenPrincipal>()
            ?: run {
                call.respond(HttpStatusCode.Unauthorized)
                return
            }

    val orgSlug = call.parameters["orgSlug"] ?: return

    if (!releaseService.hasOrgAccess(principal.userId, orgSlug)) {
        call.respond(HttpStatusCode.Forbidden)
        return
    }

    val orgId = releaseService.getOrganizationIdBySlug(orgSlug)
    val chunks = receiveUploadedSourceMapChunks()
    val totalBytes = chunks.sumOf { it.bytes.size.toLong() }
    val quotaReservation = reserveSourceMapQuotaOrRespond(
        quotaService = quotaService,
        organizationId = orgId,
        units = chunks.size,
        bytes = totalBytes
    ) ?: return

    if (!storeChunksOrRespond(releaseService, quotaService, orgSlug, chunks, quotaReservation)) return

    logger.info { "Stored ${chunks.size} chunks for org $orgSlug" }
    call.respond(HttpStatusCode.OK)
}

private suspend fun io.ktor.server.routing.RoutingContext.receiveSourceMapUpload(): UploadedSourceMap? {
    val multipart = call.receiveMultipart()
    var fileName: String? = null
    var fileBytes: ByteArray? = null
    var filePath: String? = null

    multipart.forEachPart { part ->
        if (part is PartData.FormItem && part.name == "name") {
            filePath = part.value
        }
        if (part is PartData.FileItem) {
            fileName = part.originalFileName ?: "unknown"
            fileBytes = part.provider().toByteArray()
        }
        part.release()
    }

    val finalFileName = filePath ?: fileName
    val uploadedBytes = fileBytes
    if (finalFileName == null || uploadedBytes == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing file or filename"))
        return null
    }
    return UploadedSourceMap(finalFileName, uploadedBytes)
}

private suspend fun io.ktor.server.routing.RoutingContext.receiveUploadedSourceMapChunks():
    List<UploadedSourceMapChunk> {
    val chunks = mutableListOf<UploadedSourceMapChunk>()
    val multipart = call.receiveMultipart()
    multipart.forEachPart { part ->
        if (part is PartData.FileItem) {
            chunks += UploadedSourceMapChunk(
                checksum = part.originalFileName ?: part.name ?: "",
                bytes = sourceMapChunkBytes(part)
            )
        }
        part.release()
    }
    return chunks
}

private suspend fun sourceMapChunkBytes(part: PartData.FileItem): ByteArray {
    val rawBytes = part.provider().toByteArray()
    if (part.name != "file_gzip") return rawBytes

    return suspendRunCatching {
        val bos = ByteArrayOutputStream()
        GZIPInputStream(rawBytes.inputStream()).use { it.copyTo(bos) }
        bos.toByteArray()
    }.getOrElse { _ ->
        rawBytes
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.reserveSourceMapQuotaOrRespond(
    quotaService: BillingQuotaService,
    organizationId: Int?,
    units: Int,
    bytes: Long
): SourceMapQuotaReservation? {
    if (!quotaService.isEnforcementEnabled() || organizationId == null || bytes <= 0) {
        return SourceMapQuotaReservation(null, units, bytes)
    }

    val reservation = quotaService.reserveUnits(organizationId, units, "sourcemap", bytes)
    if (!reservation.allowed) {
        call.respond(
            HttpStatusCode.TooManyRequests,
            QuotaExceededResponse(reason = reservation.reason, usage = reservation.usage)
        )
        return null
    }
    return SourceMapQuotaReservation(organizationId, units, bytes)
}

private suspend fun io.ktor.server.routing.RoutingContext.uploadSourceMapOrRespond(
    releaseService: ReleaseService,
    quotaService: BillingQuotaService,
    quotaReservation: SourceMapQuotaReservation,
    projectId: Long,
    version: String,
    upload: UploadedSourceMap
) {
    try {
        val fileResponse =
            releaseService.uploadSourceMap(
                projectId = projectId,
                version = version,
                fileName = upload.fileName,
                fileContent = upload.bytes
            )
        call.respond(HttpStatusCode.Created, fileResponse)
    } catch (e: IllegalArgumentException) {
        refundReservedSourceMapQuota(quotaService, quotaReservation)
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
    } catch (e: SerializationException) {
        respondSourceMapUploadFailure(e, quotaService, quotaReservation)
    } catch (e: IOException) {
        respondSourceMapUploadFailure(e, quotaService, quotaReservation)
    } catch (e: IllegalStateException) {
        respondSourceMapUploadFailure(e, quotaService, quotaReservation)
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.respondSourceMapUploadFailure(
    error: Exception,
    quotaService: BillingQuotaService,
    quotaReservation: SourceMapQuotaReservation
) {
    refundReservedSourceMapQuota(quotaService, quotaReservation)
    logger.error(error) { FAILED_TO_UPLOAD_SOURCE_MAP }
    call.respond(HttpStatusCode.InternalServerError, ErrorResponse(UPLOAD_FAILED))
}

private suspend fun io.ktor.server.routing.RoutingContext.storeChunksOrRespond(
    releaseService: ReleaseService,
    quotaService: BillingQuotaService,
    orgSlug: String,
    chunks: List<UploadedSourceMapChunk>,
    quotaReservation: SourceMapQuotaReservation
): Boolean {
    return try {
        chunks.forEach { chunk ->
            releaseService.storeChunk(chunk.checksum, chunk.bytes)
        }
        true
    } catch (e: IOException) {
        respondChunkUploadFailure(e, quotaService, orgSlug, quotaReservation)
        false
    } catch (e: IllegalStateException) {
        respondChunkUploadFailure(e, quotaService, orgSlug, quotaReservation)
        false
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.respondChunkUploadFailure(
    error: Exception,
    quotaService: BillingQuotaService,
    orgSlug: String,
    quotaReservation: SourceMapQuotaReservation
) {
    refundReservedSourceMapQuota(quotaService, quotaReservation)
    logger.error(error) { "Failed to store chunks for org $orgSlug" }
    call.respond(HttpStatusCode.InternalServerError, ErrorResponse(UPLOAD_FAILED))
}

private fun refundReservedSourceMapQuota(
    quotaService: BillingQuotaService,
    reservation: SourceMapQuotaReservation
) {
    val organizationId = reservation.organizationId ?: return
    quotaService.refundUnits(organizationId, reservation.units, "sourcemap", reservation.bytes)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleAssembleArtifactBundle(
    releaseService: ReleaseService,
) {
    val principal =
        call.principal<AuthTokenPrincipal>()
            ?: run {
                call.respond(HttpStatusCode.Unauthorized)
                return
            }

    val orgSlug = call.parameters["orgSlug"] ?: return

    val orgId =
        releaseService.getOrganizationIdBySlug(orgSlug)
            ?: run {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Organization not found"))
                return
            }

    if (!releaseService.hasOrgAccess(principal.userId, orgSlug)) {
        call.respond(HttpStatusCode.Forbidden)
        return
    }

    val request = call.receive<AssembleArtifactBundleRequest>()

    if (request.projects.isEmpty()) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("You need to specify at least one project"))
        return
    }

    // Check for missing chunks
    val missingChunks = releaseService.findMissingChunks(request.chunks.toSet())
    if (missingChunks.isNotEmpty()) {
        call.respond(
            AssembleResponse(
                state = "not_found",
                missingChunks = missingChunks
            )
        )
        return
    }

    // Check if already assembled
    val existing = releaseService.getAssembleStatus(orgId, request.checksum)
    if (existing != null) {
        call.respond(
            AssembleResponse(
                state = existing.first,
                detail = existing.second,
                missingChunks = emptyList()
            )
        )
        return
    }

    // Assemble the bundle
    suspendRunCatching {
        releaseService.assembleArtifactBundle(
            orgId = orgId,
            checksum = request.checksum,
            chunks = request.chunks,
            projectSlugs = request.projects,
            version = request.version,
            dist = request.dist
        )

        val status = releaseService.getAssembleStatus(orgId, request.checksum)
        call.respond(
            AssembleResponse(
                state = status?.first ?: "ok",
                detail = status?.second,
                missingChunks = emptyList()
            )
        )
    }.getOrElse { e ->
        logger.error(e) { "Failed to assemble artifact bundle" }
        call.respond(
            AssembleResponse(
                state = "error",
                detail = e.message,
                missingChunks = emptyList()
            )
        )
    }
}

// Resolves and authorizes the {orgSlug}/{projectSlug} project for the DIF endpoints,
// responding with the appropriate error (and returning null) when that fails.
private suspend fun io.ktor.server.routing.RoutingContext.resolveDifProject(
    releaseService: ReleaseService,
    authTokenService: AuthTokenService,
    requiredScope: String,
): Long? {
    val principal =
        call.principal<AuthTokenPrincipal>()
            ?: run {
                call.respond(HttpStatusCode.Unauthorized)
                return null
            }

    if (!authTokenService.hasScope(principal.scopes, requiredScope)) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Missing required scope: $requiredScope"))
        return null
    }

    val orgSlug = call.parameters["orgSlug"] ?: return null
    val projectSlug = call.parameters["projectSlug"] ?: return null

    val projectId =
        releaseService.getProjectBySlug(orgSlug, projectSlug)
            ?: run {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(PROJECT_NOT_FOUND))
                return null
            }

    if (!releaseService.hasProjectAccess(principal.userId, projectId)) {
        call.respond(HttpStatusCode.Forbidden)
        return null
    }

    return projectId
}

private suspend fun io.ktor.server.routing.RoutingContext.handleListProjectDifs(
    releaseService: ReleaseService,
    authTokenService: AuthTokenService,
) {
    val projectId = resolveDifProject(releaseService, authTokenService, "sourcemaps:read") ?: return

    val checksums = call.request.queryParameters.getAll("checksums")?.toSet() ?: emptySet()
    val debugIds = call.request.queryParameters.getAll("debug_id")?.toSet() ?: emptySet()

    val difs = releaseService.listProjectDifs(projectId, checksums, debugIds)
    call.respond(difs.map { it.toDifObject() })
}

private suspend fun io.ktor.server.routing.RoutingContext.handleAssembleProjectDifs(
    releaseService: ReleaseService,
    authTokenService: AuthTokenService,
) {
    val projectId = resolveDifProject(releaseService, authTokenService, "sourcemaps:write") ?: return

    // Body maps each assembled file's SHA-1 checksum to its name, optional debug id, and chunks.
    val request = call.receive<Map<String, AssembleDifEntry>>()

    val response =
        request.mapValues { (checksum, entry) ->
            assembleSingleDif(releaseService, projectId, checksum, entry)
        }

    call.respond(response)
}

private suspend fun assembleSingleDif(
    releaseService: ReleaseService,
    projectId: Long,
    checksum: String,
    entry: AssembleDifEntry,
): AssembleDifResponseEntry {
    val missing = releaseService.findMissingChunks(entry.chunks.toSet())
    if (missing.isNotEmpty()) {
        return AssembleDifResponseEntry(state = "not_found", missingChunks = missing)
    }

    return suspendRunCatching {
        val dif =
            releaseService.assembleProjectDif(
                projectId = projectId,
                checksum = checksum,
                chunks = entry.chunks,
                name = entry.name,
                debugId = entry.debugId
            )
        AssembleDifResponseEntry(state = "ok", dif = dif.toDifObject())
    }.getOrElse { e ->
        logger.error(e) { "Failed to assemble debug file $checksum for project $projectId" }
        AssembleDifResponseEntry(state = "error", detail = e.message)
    }
}

private fun AssembledDif.toDifObject(): DifObject =
    DifObject(
        id = resourceId,
        debugId = debugId,
        objectName = objectName,
        size = size,
        sha1 = checksum,
        dateCreated = dateCreated
    )
