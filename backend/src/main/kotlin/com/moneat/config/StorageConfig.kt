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

package com.moneat.config

import mu.KotlinLogging
import java.io.File
import java.io.InputStream
import java.io.OutputStream

private val logger = KotlinLogging.logger {}

/**
 * Abstraction for file storage operations.
 * Implementations can back onto local disk, S3, GCS, or a shared PVC.
 *
 * For Kubernetes multi-replica deployments, replace [LocalStorageProvider]
 * with an S3-compatible implementation or mount a ReadWriteMany PVC.
 */
interface StorageProvider {
    fun write(key: String, data: ByteArray)
    fun read(key: String): ByteArray?
    fun exists(key: String): Boolean
    fun openOutputStream(key: String): OutputStream
    fun openInputStream(key: String): InputStream?
}

/**
 * Default storage provider that writes to the local filesystem.
 * Suitable for single-instance deployments or when backed by a shared volume.
 */
class LocalStorageProvider(private val basePath: String = "./storage") : StorageProvider {

    override fun write(key: String, data: ByteArray) {
        val file = resolve(key)
        file.parentFile.mkdirs()
        file.writeBytes(data)
    }

    override fun read(key: String): ByteArray? {
        val file = resolve(key)
        return if (file.exists()) file.readBytes() else null
    }

    override fun exists(key: String): Boolean = resolve(key).exists()

    override fun openOutputStream(key: String): OutputStream {
        val file = resolve(key)
        file.parentFile.mkdirs()
        return file.outputStream()
    }

    override fun openInputStream(key: String): InputStream? {
        val file = resolve(key)
        return if (file.exists()) file.inputStream() else null
    }

    private fun resolve(key: String): File = File(basePath, key)
}

/**
 * Singleton holder for the active [StorageProvider].
 * Defaults to [LocalStorageProvider]; override via [StorageConfig.initialize] for K8s/S3.
 */
object StorageConfig {
    var provider: StorageProvider = LocalStorageProvider()
        private set

    fun initialize(provider: StorageProvider) {
        this.provider = provider
        logger.info { "Storage provider initialized: ${provider::class.simpleName}" }
    }
}
