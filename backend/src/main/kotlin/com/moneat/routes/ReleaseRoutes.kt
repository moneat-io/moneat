package com.moneat.routes

import com.moneat.models.CreateReleaseRequest
import com.moneat.plugins.AuthTokenPrincipal
import com.moneat.services.AuthTokenService
import com.moneat.services.ReleaseService
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun Route.releaseRoutes() {
    val releaseService = ReleaseService()
    val authTokenService = AuthTokenService()
    
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
                        mapOf("error" to "Missing required scope: releases:write")
                    )
                    return@post
                }
                
                val orgSlug = call.parameters["orgSlug"]
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing organization slug"))
                        return@post
                    }
                
                val request = call.receive<CreateReleaseRequest>()
                
                // Get project ID from slug
                // For Sentry compatibility, we support the "projects" field in the request
                val projectSlug = request.projects?.firstOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing project"))
                        return@post
                    }
                
                val projectId = releaseService.getProjectBySlug(orgSlug, projectSlug)
                    ?: run {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Project not found"))
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
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
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
                        mapOf("error" to "Missing required scope: releases:read")
                    )
                    return@get
                }
                
                // For this endpoint, we'd need to know which project
                // Sentry CLI typically uses project-specific endpoints
                call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "Use project-specific endpoint"))
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
                        mapOf("error" to "Missing required scope: releases:write")
                    )
                    return@post
                }
                
                val orgSlug = call.parameters["orgSlug"] ?: return@post
                val projectSlug = call.parameters["projectSlug"] ?: return@post
                
                val projectId = releaseService.getProjectBySlug(orgSlug, projectSlug)
                    ?: run {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Project not found"))
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
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
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
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Project not found"))
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
                        mapOf("error" to "Missing required scope: sourcemaps:write")
                    )
                    return@post
                }
                
                val orgSlug = call.parameters["orgSlug"] ?: return@post
                val projectSlug = call.parameters["projectSlug"] ?: return@post
                val version = call.parameters["version"] ?: return@post
                
                val projectId = releaseService.getProjectBySlug(orgSlug, projectSlug)
                    ?: run {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Project not found"))
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
                            fileBytes = part.streamProvider().readBytes()
                        }
                        else -> {}
                    }
                    part.dispose()
                }
                
                val finalFileName = filePath ?: fileName
                if (finalFileName == null || fileBytes == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing file or filename"))
                    return@post
                }
                
                try {
                    val fileResponse = releaseService.uploadSourceMap(
                        projectId = projectId,
                        version = version,
                        fileName = finalFileName,
                        fileContent = fileBytes!!
                    )
                    call.respond(HttpStatusCode.Created, fileResponse)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to upload source map" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Upload failed"))
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
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Project not found"))
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
    }
}
