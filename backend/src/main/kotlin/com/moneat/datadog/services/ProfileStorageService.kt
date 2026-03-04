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

package com.moneat.datadog.services

import com.moneat.config.EnvConfig
import java.io.ByteArrayOutputStream
import mu.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.GZIPOutputStream

private val logger = KotlinLogging.logger {}

/**
 * Local filesystem storage for pprof profile data.
 * Production deployments should use S3/MinIO via PROFILE_STORAGE_PATH config.
 */
object ProfileStorageService {
    private val storagePath: String by lazy {
        EnvConfig.get(
            "PROFILE_STORAGE_PATH",
            "/var/lib/moneat/profiles"
        )
    }

    fun init() {
        val dir = File(storagePath)
        if (!dir.exists()) {
            dir.mkdirs()
            logger.info { "Created profile storage directory: $storagePath" }
        }
    }

    /**
     * Store a pprof file and return the storage key.
     */
    fun store(
        organizationId: Int,
        data: ByteArray,
    ): String {
        val key = "$organizationId/${UUID.randomUUID()}.pprof.gz"
        val file = File(storagePath, key)
        file.parentFile.mkdirs()
        val bytesToStore = ensureGzip(data)
        file.writeBytes(bytesToStore)
        logger.debug {
            "Stored profile: $key (${bytesToStore.size} bytes, input=${data.size} bytes)"
        }
        return key
    }

    /**
     * Store multiple profile files (pprof, JFR, etc.) under a single
     * directory keyed by profileId. Returns the directory-based storage key.
     */
    fun storeMultiple(
        organizationId: Int,
        profileId: String,
        files: List<Pair<String, ByteArray>>,
    ): String {
        val prefix = "$organizationId/$profileId"
        val dir = File(storagePath, prefix)
        dir.mkdirs()
        var totalStored = 0
        files.forEach { (filename, data) ->
            val target = resolveUniqueFile(dir, sanitizeFilename(filename))
            val bytesToStore = ensureGzip(data)
            target.writeBytes(bytesToStore)
            totalStored += bytesToStore.size
        }
        logger.debug {
            "Stored ${files.size} profile files under $prefix ($totalStored bytes)"
        }
        return prefix
    }

    /**
     * Add files to an existing profile storage directory.
     * Handles migration from legacy single-file storage.
     * Colliding filenames are saved with a unique suffix via [resolveUniqueFile].
     */
    fun storeAdditional(
        storagePrefix: String,
        files: List<Pair<String, ByteArray>>,
    ) {
        val dir = File(storagePath, storagePrefix)
        val backup = dir.resolveSibling("${dir.name}.backup")
        val tempDir = dir.resolveSibling("${dir.name}.migrating")

        // Recover from a previously interrupted migration
        if (!dir.exists() && backup.exists()) {
            Files.move(backup.toPath(), dir.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } else if (!dir.exists() && tempDir.exists()) {
            Files.move(tempDir.toPath(), dir.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        // Clean up stale artifacts from a completed migration
        if (backup.exists()) backup.delete()
        if (tempDir.exists()) tempDir.deleteRecursively()

        if (dir.isFile) {
            // Safely migrate legacy single-file storage into a directory
            val legacyData = dir.readBytes()
            val legacyName = dir.name
            Files.move(dir.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
            try {
                tempDir.mkdirs()
                File(tempDir, legacyName).writeBytes(legacyData)
                Files.move(tempDir.toPath(), dir.toPath(), StandardCopyOption.ATOMIC_MOVE)
                backup.delete()
            } catch (e: Exception) {
                // Restore backup if migration fails
                if (!dir.exists() && backup.exists()) {
                    Files.move(backup.toPath(), dir.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }
                tempDir.deleteRecursively()
                throw e
            }
            logger.debug { "Migrated legacy profile file to directory: $storagePrefix" }
        } else {
            dir.mkdirs()
        }
        files.forEach { (filename, data) ->
            val sanitized = sanitizeFilename(filename)
            val file = resolveUniqueFile(dir, sanitized)
            file.writeBytes(ensureGzip(data))
        }
        logger.debug {
            "Added ${files.size} files to existing profile $storagePrefix"
        }
    }

    /**
     * Read a stored profile file by its storage key.
     * Handles both legacy single-file keys and directory-based keys.
     */
    fun read(storageKey: String): ByteArray? {
        val path = File(storagePath, storageKey)
        if (path.isFile) return path.readBytes()
        if (path.isDirectory) {
            return selectBestFile(path)?.readBytes()
        }
        return null
    }

    /**
     * Delete a stored pprof file.
     */
    fun delete(storageKey: String): Boolean {
        val path = File(storagePath, storageKey)
        if (path.isDirectory) {
            return path.deleteRecursively()
        }
        return path.delete()
    }

    private fun selectBestFile(dir: File): File? {
        val files = dir.listFiles()?.filter { it.isFile } ?: return null
        // Prefer non-delta pprof, then any pprof, then JFR (case-insensitive)
        return files.firstOrNull {
            val n = it.name.lowercase()
            n.contains("pprof") && !n.contains("delta")
        }
            ?: files.firstOrNull { it.name.lowercase().contains("pprof") }
            ?: files.firstOrNull { it.name.lowercase().contains("jfr") }
            ?: files.firstOrNull()
    }

    private fun sanitizeFilename(name: String): String {
        val sanitized = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        if (sanitized.isBlank()) return "profile_${UUID.randomUUID().toString().take(8)}"
        return sanitized
    }

    /**
     * Return a File inside [dir] for the given [name], appending a counter
     * suffix when a file with the same name already exists.
     */
    private fun resolveUniqueFile(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val dotIdx = name.lastIndexOf('.')
        val base = if (dotIdx > 0) name.substring(0, dotIdx) else name
        val ext = if (dotIdx > 0) name.substring(dotIdx) else ""
        var counter = 1
        while (target.exists()) {
            target = File(dir, "${base}_$counter$ext")
            counter++
        }
        return target
    }

    private fun ensureGzip(data: ByteArray): ByteArray {
        if (data.size >= 2 && data[0] == GZIP_MAGIC_0 && data[1] == GZIP_MAGIC_1) {
            return data
        }

        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            gzip.write(data)
        }
        return output.toByteArray()
    }

    private const val GZIP_MAGIC_0: Byte = 0x1f
    private const val GZIP_MAGIC_1: Byte = 0x8b.toByte()
}
