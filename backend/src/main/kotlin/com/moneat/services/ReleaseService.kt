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

package com.moneat.services

import com.moneat.models.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*

class ReleaseService {
    private val dateFormatter = DateTimeFormatter.ISO_INSTANT

    /**
     * Create a new release for a project
     */
    fun createRelease(
        projectId: Long,
        version: String,
        ref: String?
    ): ReleaseResponse {
        val createdAt = System.currentTimeMillis()

        val projectSlug =
            transaction {
                // Check if release already exists
                val existing =
                    Releases
                        .selectAll()
                        .where { (Releases.project_id eq projectId) and (Releases.version eq version) }
                        .firstOrNull()

                if (existing != null) {
                    throw IllegalArgumentException("Release $version already exists for this project")
                }

                // Get project slug for response
                val project =
                    Projects
                        .selectAll()
                        .where { Projects.id eq projectId }
                        .firstOrNull()
                        ?: throw IllegalArgumentException("Project not found")

                // Create release
                Releases.insert {
                    it[project_id] = projectId
                    it[Releases.version] = version
                    it[Releases.ref] = ref
                    it[created_at] = createdAt
                }

                project[Projects.slug]
            }

        return ReleaseResponse(
            version = version,
            ref = ref,
            projectSlug = projectSlug,
            dateCreated = formatTimestamp(createdAt)
        )
    }

    /**
     * Upload a source map file to a release
     */
    fun uploadSourceMap(
        projectId: Long,
        version: String,
        fileName: String,
        fileContent: ByteArray
    ): SourceMapFileResponse {
        val createdAt = System.currentTimeMillis()

        return transaction {
            // Find the release
            val release =
                Releases
                    .selectAll()
                    .where { (Releases.project_id eq projectId) and (Releases.version eq version) }
                    .firstOrNull()
                    ?: throw IllegalArgumentException("Release $version not found")

            val releaseId = release[Releases.id]

            // Create storage directory if it doesn't exist
            val storageDir = File("./storage/sourcemaps/$projectId/$version")
            storageDir.mkdirs()

            // Generate unique filename to prevent overwrites
            val uniqueFileName = "${UUID.randomUUID()}_${fileName.replace("/", "_")}"
            val storagePath = "${storageDir.path}/$uniqueFileName"

            // Write file to disk
            File(storagePath).writeBytes(fileContent)

            // Save file metadata to database
            val fileId =
                ReleaseFiles.insert {
                    it[release_id] = releaseId
                    it[name] = fileName
                    it[file_path] = fileName
                    it[storage_path] = storagePath
                    it[file_type] = if (fileName.endsWith(".map")) "source_map" else "source_file"
                    it[ReleaseFiles.created_at] = createdAt
                }[ReleaseFiles.id]

            SourceMapFileResponse(
                id = fileId,
                name = fileName,
                dateCreated = formatTimestamp(createdAt)
            )
        }
    }

    /**
     * Auto-detect and upsert release from an incoming event.
     * Creates the release if it doesn't exist, or updates last_seen and event_count.
     */
    fun upsertReleaseFromEvent(
        projectId: Long,
        version: String,
        eventTimestampMs: Long
    ) {
        if (version.isBlank()) return

        transaction {
            val existing =
                Releases
                    .selectAll()
                    .where { (Releases.project_id eq projectId) and (Releases.version eq version) }
                    .firstOrNull()

            if (existing != null) {
                val currentFirstSeen = existing[Releases.first_seen]
                Releases.update(
                    where = { (Releases.project_id eq projectId) and (Releases.version eq version) }
                ) {
                    it[last_seen] = eventTimestampMs
                    it[event_count] = Releases.event_count + 1
                    if (currentFirstSeen == null) {
                        it[first_seen] = eventTimestampMs
                    }
                }
            } else {
                Releases.insert {
                    it[Releases.project_id] = projectId
                    it[Releases.version] = version
                    it[Releases.ref] = null
                    it[Releases.created_at] = eventTimestampMs
                    it[Releases.first_seen] = eventTimestampMs
                    it[Releases.last_seen] = eventTimestampMs
                    it[Releases.event_count] = 1
                    it[Releases.is_auto_detected] = true
                }
            }
        }
    }

    /**
     * Get a release by version
     */
    fun getRelease(
        projectId: Long,
        version: String
    ): ReleaseResponse? {
        return transaction {
            val release =
                Releases
                    .selectAll()
                    .where { (Releases.project_id eq projectId) and (Releases.version eq version) }
                    .firstOrNull()
                    ?: return@transaction null

            val project =
                Projects
                    .selectAll()
                    .where { Projects.id eq projectId }
                    .firstOrNull()
                    ?: return@transaction null

            ReleaseResponse(
                version = release[Releases.version],
                ref = release[Releases.ref],
                projectSlug = project[Projects.slug],
                dateCreated = formatTimestamp(release[Releases.created_at])
            )
        }
    }

    /**
     * List all releases for a project
     */
    fun listReleases(projectId: Long): List<ReleaseResponse> {
        return transaction {
            val project =
                Projects
                    .selectAll()
                    .where { Projects.id eq projectId }
                    .firstOrNull()
                    ?: return@transaction emptyList()

            Releases
                .selectAll()
                .where { Releases.project_id eq projectId }
                .orderBy(Releases.created_at, SortOrder.DESC)
                .map { row ->
                    ReleaseResponse(
                        version = row[Releases.version],
                        ref = row[Releases.ref],
                        projectSlug = project[Projects.slug],
                        dateCreated = formatTimestamp(row[Releases.created_at])
                    )
                }
        }
    }

    /**
     * List source map files for a release
     */
    fun listReleaseFiles(
        projectId: Long,
        version: String
    ): List<SourceMapFileResponse> {
        return transaction {
            val release =
                Releases
                    .selectAll()
                    .where { (Releases.project_id eq projectId) and (Releases.version eq version) }
                    .firstOrNull()
                    ?: return@transaction emptyList()

            val releaseId = release[Releases.id]

            ReleaseFiles
                .selectAll()
                .where { ReleaseFiles.release_id eq releaseId }
                .orderBy(ReleaseFiles.created_at, SortOrder.DESC)
                .map { row ->
                    SourceMapFileResponse(
                        id = row[ReleaseFiles.id],
                        name = row[ReleaseFiles.name],
                        dateCreated = formatTimestamp(row[ReleaseFiles.created_at])
                    )
                }
        }
    }

    /**
     * Check if user has access to a project
     */
    fun hasProjectAccess(
        userId: Int,
        projectId: Long
    ): Boolean {
        return transaction {
            val project =
                Projects
                    .selectAll()
                    .where { Projects.id eq projectId }
                    .firstOrNull()
                    ?: return@transaction false

            val orgId = project[Projects.organization_id]

            Memberships
                .selectAll()
                .where { (Memberships.user_id eq userId) and (Memberships.organization_id eq orgId) }
                .count() > 0
        }
    }

    /**
     * Get project ID by organization slug and project slug
     */
    fun getProjectBySlug(
        orgSlug: String,
        projectSlug: String
    ): Long? {
        return transaction {
            val org =
                Organizations
                    .selectAll()
                    .where { Organizations.slug eq orgSlug }
                    .firstOrNull()
                    ?: return@transaction null

            val orgId = org[Organizations.id]

            Projects
                .selectAll()
                .where { (Projects.organization_id eq orgId) and (Projects.slug eq projectSlug) }
                .firstOrNull()
                ?.get(Projects.id)
        }
    }

    /**
     * Format timestamp to ISO-8601 string
     */
    private fun formatTimestamp(timestamp: Long): String {
        return Instant
            .ofEpochMilli(timestamp)
            .atOffset(ZoneOffset.UTC)
            .format(dateFormatter)
    }

    /**
     * Store a file chunk by its SHA1 checksum.
     */
    fun storeChunk(
        checksum: String,
        data: ByteArray
    ) {
        val storageDir = File("./storage/chunks")
        storageDir.mkdirs()

        val storagePath = "${storageDir.path}/$checksum"
        File(storagePath).writeBytes(data)

        transaction {
            val existing =
                FileBlobs
                    .selectAll()
                    .where { FileBlobs.checksum eq checksum }
                    .firstOrNull()

            if (existing == null) {
                FileBlobs.insert {
                    it[FileBlobs.checksum] = checksum
                    it[FileBlobs.size] = data.size.toLong()
                    it[FileBlobs.storage_path] = storagePath
                    it[FileBlobs.created_at] = System.currentTimeMillis()
                }
            }
        }
    }

    /**
     * Find which chunks from the given set are not yet uploaded.
     */
    fun findMissingChunks(checksums: Set<String>): List<String> {
        if (checksums.isEmpty()) return emptyList()
        return transaction {
            val existing =
                FileBlobs
                    .selectAll()
                    .where { FileBlobs.checksum inList checksums }
                    .map { it[FileBlobs.checksum] }
                    .toSet()
            checksums.filter { it !in existing }
        }
    }

    /**
     * Get the organization ID from slug.
     */
    fun getOrganizationIdBySlug(orgSlug: String): Int? {
        return transaction {
            Organizations
                .selectAll()
                .where { Organizations.slug eq orgSlug }
                .firstOrNull()
                ?.get(Organizations.id)
        }
    }

    /**
     * Check if a user is a member of the organization.
     */
    fun hasOrgAccess(
        userId: Int,
        orgSlug: String
    ): Boolean {
        return transaction {
            val org =
                Organizations
                    .selectAll()
                    .where { Organizations.slug eq orgSlug }
                    .firstOrNull() ?: return@transaction false

            Memberships
                .selectAll()
                .where { (Memberships.user_id eq userId) and (Memberships.organization_id eq org[Organizations.id]) }
                .count() > 0
        }
    }

    /**
     * Get the assembly status for a checksum, or null if not started.
     */
    fun getAssembleStatus(
        orgId: Int,
        checksum: String
    ): Pair<String, String?>? {
        return transaction {
            ArtifactBundles
                .selectAll()
                .where { (ArtifactBundles.organization_id eq orgId) and (ArtifactBundles.checksum eq checksum) }
                .firstOrNull()
                ?.let { Pair(it[ArtifactBundles.state], it[ArtifactBundles.detail]) }
        }
    }

    /**
     * Assemble chunks into an artifact bundle.
     * Concatenates chunk files in order and stores the result.
     */
    fun assembleArtifactBundle(
        orgId: Int,
        checksum: String,
        chunks: List<String>,
        @Suppress("UNUSED_PARAMETER") projectSlugs: List<String>,
        version: String?,
        dist: String?
    ) {
        val storageDir = File("./storage/artifact_bundles/$orgId")
        storageDir.mkdirs()
        val storagePath = "${storageDir.path}/$checksum"

        // Concatenate all chunks
        val outputFile = File(storagePath)
        outputFile.outputStream().use { out ->
            for (chunk in chunks) {
                val chunkFile = File("./storage/chunks/$chunk")
                if (chunkFile.exists()) {
                    chunkFile.inputStream().use { it.copyTo(out) }
                }
            }
        }

        // Verify checksum
        val digest = MessageDigest.getInstance("SHA-1")
        outputFile.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val computedChecksum = digest.digest().joinToString("") { "%02x".format(it) }

        val state = if (computedChecksum == checksum) "ok" else "error"
        val detail = if (state == "error") "Checksum mismatch" else null

        transaction {
            val existing =
                ArtifactBundles
                    .selectAll()
                    .where { (ArtifactBundles.organization_id eq orgId) and (ArtifactBundles.checksum eq checksum) }
                    .firstOrNull()

            if (existing != null) {
                ArtifactBundles.update(
                    where = { (ArtifactBundles.organization_id eq orgId) and (ArtifactBundles.checksum eq checksum) }
                ) {
                    it[ArtifactBundles.state] = state
                    it[ArtifactBundles.detail] = detail
                    it[ArtifactBundles.storage_path] = storagePath
                }
            } else {
                ArtifactBundles.insert {
                    it[ArtifactBundles.organization_id] = orgId
                    it[ArtifactBundles.checksum] = checksum
                    it[ArtifactBundles.state] = state
                    it[ArtifactBundles.detail] = detail
                    it[ArtifactBundles.version] = version
                    it[ArtifactBundles.dist] = dist
                    it[ArtifactBundles.storage_path] = storagePath
                    it[ArtifactBundles.created_at] = System.currentTimeMillis()
                }
            }
        }
    }
}
