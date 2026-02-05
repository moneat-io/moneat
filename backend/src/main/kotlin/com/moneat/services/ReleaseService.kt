package com.moneat.services

import com.moneat.models.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*

class ReleaseService {
    private val dateFormatter = DateTimeFormatter.ISO_INSTANT
    
    /**
     * Create a new release for a project
     */
    fun createRelease(projectId: Long, version: String, ref: String?): ReleaseResponse {
        val createdAt = System.currentTimeMillis()
        
        val projectSlug = transaction {
            // Check if release already exists
            val existing = Releases.selectAll()
                .where { (Releases.project_id eq projectId) and (Releases.version eq version) }
                .firstOrNull()
            
            if (existing != null) {
                throw IllegalArgumentException("Release $version already exists for this project")
            }
            
            // Get project slug for response
            val project = Projects.selectAll()
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
            val release = Releases.selectAll()
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
            val fileId = ReleaseFiles.insert {
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
    fun upsertReleaseFromEvent(projectId: Long, version: String, eventTimestampMs: Long) {
        if (version.isBlank()) return
        
        transaction {
            val existing = Releases.selectAll()
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
    fun getRelease(projectId: Long, version: String): ReleaseResponse? {
        return transaction {
            val release = Releases.selectAll()
                .where { (Releases.project_id eq projectId) and (Releases.version eq version) }
                .firstOrNull()
                ?: return@transaction null
            
            val project = Projects.selectAll()
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
            val project = Projects.selectAll()
                .where { Projects.id eq projectId }
                .firstOrNull()
                ?: return@transaction emptyList()
            
            Releases.selectAll()
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
    fun listReleaseFiles(projectId: Long, version: String): List<SourceMapFileResponse> {
        return transaction {
            val release = Releases.selectAll()
                .where { (Releases.project_id eq projectId) and (Releases.version eq version) }
                .firstOrNull()
                ?: return@transaction emptyList()
            
            val releaseId = release[Releases.id]
            
            ReleaseFiles.selectAll()
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
    fun hasProjectAccess(userId: Int, projectId: Long): Boolean {
        return transaction {
            val project = Projects.selectAll()
                .where { Projects.id eq projectId }
                .firstOrNull()
                ?: return@transaction false
            
            val orgId = project[Projects.organization_id]
            
            Memberships.selectAll()
                .where { (Memberships.user_id eq userId) and (Memberships.organization_id eq orgId) }
                .count() > 0
        }
    }
    
    /**
     * Get project ID by organization slug and project slug
     */
    fun getProjectBySlug(orgSlug: String, projectSlug: String): Long? {
        return transaction {
            val org = Organizations.selectAll()
                .where { Organizations.slug eq orgSlug }
                .firstOrNull()
                ?: return@transaction null
            
            val orgId = org[Organizations.id]
            
            Projects.selectAll()
                .where { (Projects.organization_id eq orgId) and (Projects.slug eq projectSlug) }
                .firstOrNull()
                ?.get(Projects.id)
        }
    }
    
    /**
     * Format timestamp to ISO-8601 string
     */
    private fun formatTimestamp(timestamp: Long): String {
        return Instant.ofEpochMilli(timestamp)
            .atOffset(ZoneOffset.UTC)
            .format(dateFormatter)
    }
}
