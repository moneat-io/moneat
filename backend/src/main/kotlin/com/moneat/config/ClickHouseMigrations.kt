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

import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.server.application.Application
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * ClickHouse migration system similar to Flyway for PostgreSQL.
 * Manages versioned schema migrations with checksum validation.
 */
object ClickHouseMigrations {
    private const val MIGRATIONS_TABLE = "schema_migrations"
    private const val MIGRATIONS_PATH = "db/clickhouse_migration"

    data class Migration(
        val version: Int,
        val description: String,
        val filename: String,
        val sql: String,
        val checksum: String
    )

    data class AppliedMigration(
        val version: Int,
        val description: String,
        val checksum: String,
        val appliedAt: String
    )

    /**
     * Runs all pending migrations on application startup.
     */
    suspend fun migrate(logger: org.slf4j.Logger) {
        try {
            // Create migrations tracking table if it doesn't exist
            createMigrationsTable()

            // Load all migration files
            val migrations = loadMigrations()
            if (migrations.isEmpty()) {
                logger.info("No ClickHouse migrations found")
                return
            }

            // Get already applied migrations
            val appliedMigrations = getAppliedMigrations()
            val appliedVersions = appliedMigrations.associate { it.version to it.checksum }

            // Validate checksums for existing migrations
            validateChecksums(migrations, appliedMigrations, logger)

            // Find pending migrations
            val pendingMigrations = migrations.filter { it.version !in appliedVersions.keys }

            if (pendingMigrations.isEmpty()) {
                logger.info("ClickHouse schema is up to date (${migrations.size} migrations applied)")
                return
            }

            // Apply pending migrations
            logger.info("Applying ${pendingMigrations.size} ClickHouse migration(s)...")
            for (migration in pendingMigrations) {
                applyMigration(migration, logger)
            }

            logger.info("ClickHouse migrations complete (${migrations.size} total migrations)")
        } catch (e: Exception) {
            logger.error("ClickHouse migration failed: ${e.message}", e)
            throw RuntimeException("ClickHouse migration failed", e)
        }
    }

    private suspend fun createMigrationsTable() {
        val sql =
            """
            CREATE TABLE IF NOT EXISTS $MIGRATIONS_TABLE (
                version UInt32,
                description String,
                checksum String,
                applied_at DateTime64(3, 'UTC') DEFAULT now64(3)
            ) ENGINE = MergeTree()
            ORDER BY version
            """.trimIndent()

        ClickHouseClient.execute(sql)
    }

    private fun loadMigrations(): List<Migration> {
        val classLoader = Thread.currentThread().contextClassLoader
        val url =
            classLoader.getResource(MIGRATIONS_PATH)
                ?: throw IllegalStateException("Migrations directory not found: $MIGRATIONS_PATH")

        val uri = url.toURI()
        val migrationPaths =
            if (uri.scheme == "jar") {
                // Running from JAR - use FileSystem to read resources
                val env = mapOf<String, Any>()
                FileSystems.newFileSystem(uri, env).use { fs ->
                    val migrationsPath = fs.getPath("/$MIGRATIONS_PATH")
                    Files
                        .walk(migrationsPath, 1)
                        .filter { it.isRegularFile() && it.name.matches(Regex("^V\\d+__.+\\.sql$")) }
                        .map { path ->
                            val name = path.nameWithoutExtension
                            val parts = name.split("__", limit = 2)
                            val version = parts[0].substring(1).toInt()
                            val description = parts.getOrNull(1) ?: ""
                            val sql = path.readText()
                            val checksum = calculateChecksum(sql)
                            Migration(version, description, path.name, sql, checksum)
                        }.toList()
                }
            } else {
                // Running from filesystem (development)
                val migrationsDir = File(uri)
                if (!migrationsDir.exists() || !migrationsDir.isDirectory) {
                    throw IllegalStateException("Migrations directory not found: ${migrationsDir.absolutePath}")
                }

                val migrationFiles =
                    migrationsDir.listFiles { file ->
                        file.isFile && file.name.matches(Regex("^V\\d+__.+\\.sql$"))
                    } ?: emptyArray()

                migrationFiles.map { file ->
                    val name = file.nameWithoutExtension
                    val parts = name.split("__", limit = 2)
                    val version = parts[0].substring(1).toInt()
                    val description = parts.getOrNull(1) ?: ""
                    val sql = file.readText()
                    val checksum = calculateChecksum(sql)
                    Migration(version, description, file.name, sql, checksum)
                }
            }

        return migrationPaths.sortedBy { it.version }
    }

    private suspend fun getAppliedMigrations(): List<AppliedMigration> {
        val query =
            """
            SELECT version, description, checksum, 
                   formatDateTime(applied_at, '%Y-%m-%d %H:%i:%S', 'UTC') as applied_at
            FROM $MIGRATIONS_TABLE
            ORDER BY version
            FORMAT JSONEachRow
            """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (body.isBlank()) return emptyList()

            body.trim().lines().map { line ->
                val json = Json.parseToJsonElement(line).jsonObject
                AppliedMigration(
                    version = json["version"]?.jsonPrimitive?.int ?: 0,
                    description = json["description"]?.jsonPrimitive?.content ?: "",
                    checksum = json["checksum"]?.jsonPrimitive?.content ?: "",
                    appliedAt = json["applied_at"]?.jsonPrimitive?.content ?: ""
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun validateChecksums(
        migrations: List<Migration>,
        appliedMigrations: List<AppliedMigration>,
        logger: org.slf4j.Logger
    ) {
        for (applied in appliedMigrations) {
            val migration = migrations.find { it.version == applied.version }
            if (migration == null) {
                logger.warn("Applied migration V${applied.version} not found in migration files")
                continue
            }

            if (migration.checksum != applied.checksum) {
                throw IllegalStateException(
                    "Checksum mismatch for migration V${migration.version}__${migration.description}. " +
                        "Migration files must not be modified after being applied."
                )
            }
        }
    }

    private suspend fun applyMigration(
        migration: Migration,
        logger: org.slf4j.Logger
    ) {
        logger.info("Applying V${migration.version}__${migration.description}...")

        // Split SQL into individual statements (ClickHouse doesn't support multi-statement via HTTP)
        val statements = splitSqlStatements(migration.sql)

        for ((index, statement) in statements.withIndex()) {
            if (statement.isBlank()) continue

            val response = ClickHouseClient.execute(statement)
            val body = response.bodyAsText()

            if (!response.status.isSuccess() || body.trimStart().startsWith("Code:")) {
                throw RuntimeException(
                    "Migration V${migration.version} failed at statement ${index + 1}/${statements.size}: $body"
                )
            }
        }

        // Record the migration
        val insertSql =
            """
            INSERT INTO $MIGRATIONS_TABLE (version, description, checksum)
            VALUES (${migration.version}, '${escapeSql(migration.description)}', '${migration.checksum}')
            """.trimIndent()

        val recordResponse = ClickHouseClient.execute(insertSql)
        val recordBody = recordResponse.bodyAsText()

        if (!recordResponse.status.isSuccess() || recordBody.trimStart().startsWith("Code:")) {
            throw RuntimeException("Failed to record migration V${migration.version}: $recordBody")
        }

        logger.info("Applied V${migration.version}__${migration.description}")
    }

    /**
     * Split SQL file into individual statements.
     * Handles multi-line statements and comments.
     */
    private fun splitSqlStatements(sql: String): List<String> {
        val statements = mutableListOf<String>()
        val currentStatement = StringBuilder()

        for (line in sql.lines()) {
            val trimmed = line.trim()

            // Skip single-line comments
            if (trimmed.startsWith("--")) {
                continue
            }

            // Skip empty lines
            if (trimmed.isEmpty()) {
                continue
            }

            currentStatement.append(line).append("\n")

            // Check if statement is complete (ends with semicolon)
            if (trimmed.endsWith(";")) {
                statements.add(currentStatement.toString().trim())
                currentStatement.clear()
            }
        }

        // Add any remaining statement
        if (currentStatement.isNotBlank()) {
            statements.add(currentStatement.toString().trim())
        }

        return statements
    }

    private fun calculateChecksum(content: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(content.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun escapeSql(text: String): String {
        return text.replace("\\", "\\\\").replace("'", "\\'")
    }
}

/**
 * Extension function to configure ClickHouse migrations on application startup.
 */
suspend fun Application.configureClickHouseMigrations() {
    val log = LoggerFactory.getLogger("ClickHouseMigrations")
    log.info("Running ClickHouse migrations...")
    ClickHouseMigrations.migrate(log)
}
