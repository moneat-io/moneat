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

package com.moneat.routes

import com.moneat.models.AssembleArtifactBundleRequest
import com.moneat.models.AssembleResponse
import com.moneat.models.ChunkUploadParameters
import com.moneat.models.CreateReleaseRequest
import com.moneat.models.SentryAuthDetails
import com.moneat.models.SentryAuthInfoResponse
import com.moneat.models.SentryAuthUser
import com.moneat.models.Users
import com.moneat.plugins.AuthTokenPrincipal
import com.moneat.services.AuthTokenService
import com.moneat.services.ReleaseService
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.*
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.toByteArray
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

private val logger = KotlinLogging.logger {}

fun Route.releaseRoutes() {
    val releaseService = ReleaseService()
    val authTokenService = AuthTokenService()
    val logger = KotlinLogging.logger {}

    // Sentry-compatible auth verification endpoint (used by sentry-cli login/info)
    authenticate("auth-bearer") {
        get("/api/0/") {
            val principal = call.principal<AuthTokenPrincipal>()
                ?: run {
                    call.respond(HttpStatusCode.Unauthorized)
                    return@get
                }

            val userInfo = transaction {
                Users.selectAll()
                    .where { Users.id eq principal.userId }
                    .firstOrNull()
                    ?.let { row ->
                        SentryAuthUser(
                            email = row[Users.email],
                            id = row[Users.id].toString()
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
    }

    // Sentry-compatible release endpoints
    // These use auth-combined to support both JWT and Bearer tokens
    authenticate("auth-combined") {
        route("/api/0/organizations/{orgSlug}/releases") {
            // Create a new release
            // POST /api/0/organizations/{orgSlug}/releases/
            post("/") {
                val principal = call.principal<AuthTokenPrincipal>()
                    ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post
                    }

                // Check for releases:write scope
                if (!authTokenService.hasScope(principal.scopes, "releases:write")) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("Missing required scope: releases:write")
                    )
                    return@post
                }

                val orgSlug = call.parameters["orgSlug"]
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing organization slug"))
                        return@post
                    }

                val request = call.receive<CreateReleaseRequest>()

                // Get project ID from slug
                // For Sentry compatibility, we support the "projects" field in the request
                val projectSlug = request.projects?.firstOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing project"))
                        return@post
                    }

                val projectId = releaseService.getProjectBySlug(orgSlug, projectSlug)
                    ?: run {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
                        return@post
                    }

                // Verify user has access to this project
                if (!releaseService.hasProjectAccess(principal.userId, projectId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@post
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

            // Get a specific release
            // GET /api/0/organizations/{orgSlug}/releases/{version}/
            get("/{version}/") {
                val principal = call.principal<AuthTokenPrincipal>()
                    ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }

                if (!authTokenService.hasScope(principal.scopes, "releases:read")) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("Missing required scope: releases:read")
                    )
                    return@get
                }

                // For this endpoint, we'd need to know which project
                // Sentry CLI typically uses project-specific endpoints
                call.respond(HttpStatusCode.NotImplemented, ErrorResponse("Use project-specific endpoint"))
            }
        }

        // Project-specific release endpoints (more commonly used by Sentry CLI)
        route("/api/0/projects/{orgSlug}/{projectSlug}/releases") {
            // Create a new release for a specific project
            post("/") {
                val principal = call.principal<AuthTokenPrincipal>()
                    ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post
                    }

                if (!authTokenService.hasScope(principal.scopes, "releases:write")) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("Missing required scope: releases:write")
                    )
                    return@post
                }

                val orgSlug = call.parameters["orgSlug"] ?: return@post
                val projectSlug = call.parameters["projectSlug"] ?: return@post

                val projectId = releaseService.getProjectBySlug(orgSlug, projectSlug)
                    ?: run {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
                        return@post
                    }

                if (!releaseService.hasProjectAccess(principal.userId, projectId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@post
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

            // List releases for a project
            get("/") {
                val principal = call.principal<AuthTokenPrincipal>()
                    ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }

                if (!authTokenService.hasScope(principal.scopes, "releases:read")) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                val orgSlug = call.parameters["orgSlug"] ?: return@get
                val projectSlug = call.parameters["projectSlug"] ?: return@get

                val projectId = releaseService.getProjectBySlug(orgSlug, projectSlug)
                    ?: run {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
                        return@get
                    }

                if (!releaseService.hasProjectAccess(principal.userId, projectId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                val releases = releaseService.listReleases(projectId)
                call.respond(releases)
            }

            // Upload source maps for a release
            // POST /api/0/projects/{orgSlug}/{projectSlug}/releases/{version}/files/
            post("/{version}/files/") {
                val principal = call.principal<AuthTokenPrincipal>()
                    ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post
                    }

                if (!authTokenService.hasScope(principal.scopes, "sourcemaps:write")) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("Missing required scope: sourcemaps:write")
                    )
                    return@post
                }

                val orgSlug = call.parameters["orgSlug"] ?: return@post
                val projectSlug = call.parameters["projectSlug"] ?: return@post
                val version = call.parameters["version"] ?: return@post

                val projectId = releaseService.getProjectBySlug(orgSlug, projectSlug)
                    ?: run {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
                        return@post
                    }

                if (!releaseService.hasProjectAccess(principal.userId, projectId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@post
                }

                // Handle multipart file upload
                val multipart = call.receiveMultipart()
                var fileName: String? = null
                var fileBytes: ByteArray? = null
                var filePath: String? = null

                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FormItem -> {
                            if (part.name == "name") {
                                filePath = part.value
                            }
                        }
                        is PartData.FileItem -> {
                            fileName = part.originalFileName ?: "unknown"
                            fileBytes = part.provider().toByteArray()
                        }
                        else -> {}
                    }
                    part.dispose()
                }

                val finalFileName = filePath ?: fileName
                if (finalFileName == null || fileBytes == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing file or filename"))
                    return@post
                }

                try {
                    val fileResponse = releaseService.uploadSourceMap(
                        projectId = projectId,
                        version = version,
                        fileName = finalFileName,
                        fileContent = fileBytes
                    )
                    call.respond(HttpStatusCode.Created, fileResponse)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to upload source map" }
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Upload failed"))
                }
            }

            // List files for a release
            get("/{version}/files/") {
                val principal = call.principal<AuthTokenPrincipal>()
                    ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }

                if (!authTokenService.hasScope(principal.scopes, "sourcemaps:read")) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                val orgSlug = call.parameters["orgSlug"] ?: return@get
                val projectSlug = call.parameters["projectSlug"] ?: return@get
                val version = call.parameters["version"] ?: return@get

                val projectId = releaseService.getProjectBySlug(orgSlug, projectSlug)
                    ?: run {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
                        return@get
                    }

                if (!releaseService.hasProjectAccess(principal.userId, projectId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                val files = releaseService.listReleaseFiles(projectId, version)
                call.respond(files)
            }
        }

        // Chunk upload endpoint for sentry-cli
        // GET /api/0/organizations/{orgSlug}/chunk-upload/
        route("/api/0/organizations/{orgSlug}/chunk-upload") {
            get("/") {
                val principal = call.principal<AuthTokenPrincipal>()
                    ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }

                val orgSlug = call.parameters["orgSlug"] ?: return@get

                if (!releaseService.hasOrgAccess(principal.userId, orgSlug)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
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
                        accept = listOf(
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

            // POST /api/0/organizations/{orgSlug}/chunk-upload/
            post("/") {
                val principal = call.principal<AuthTokenPrincipal>()
                    ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post
                    }

                val orgSlug = call.parameters["orgSlug"] ?: return@post

                if (!releaseService.hasOrgAccess(principal.userId, orgSlug)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@post
                }

                val multipart = call.receiveMultipart()
                var chunksProcessed = 0

                multipart.forEachPart { part ->
                    if (part is PartData.FileItem) {
                        val checksum = part.originalFileName ?: part.name ?: ""
                        val rawBytes = part.provider().toByteArray()

                        // Handle gzip-compressed chunks
                        val bytes = if (part.name == "file_gzip") {
                            try {
                                val bos = ByteArrayOutputStream()
                                GZIPInputStream(rawBytes.inputStream()).use { it.copyTo(bos) }
                                bos.toByteArray()
                            } catch (e: Exception) {
                                rawBytes
                            }
                        } else {
                            rawBytes
                        }

                        releaseService.storeChunk(checksum, bytes)
                        chunksProcessed++
                    }
                    part.dispose()
                }

                logger.info { "Stored $chunksProcessed chunks for org $orgSlug" }
                call.respond(HttpStatusCode.OK)
            }
        }

        // Artifact bundle assemble endpoint
        // POST /api/0/organizations/{orgSlug}/artifactbundle/assemble/
        route("/api/0/organizations/{orgSlug}/artifactbundle") {
            post("/assemble/") {
                val principal = call.principal<AuthTokenPrincipal>()
                    ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post
                    }

                val orgSlug = call.parameters["orgSlug"] ?: return@post

                val orgId = releaseService.getOrganizationIdBySlug(orgSlug)
                    ?: run {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Organization not found"))
                        return@post
                    }

                if (!releaseService.hasOrgAccess(principal.userId, orgSlug)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@post
                }

                val request = call.receive<AssembleArtifactBundleRequest>()

                if (request.projects.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("You need to specify at least one project"))
                    return@post
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
                    return@post
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
                    return@post
                }

                // Assemble the bundle
                try {
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
                } catch (e: Exception) {
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
        }
    }
}
