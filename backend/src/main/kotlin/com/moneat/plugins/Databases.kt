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

package com.moneat.plugins

import com.moneat.config.configureClickHouseMigrations
import com.moneat.services.SystemStatusTracker
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection

private const val CLICKHOUSE_MIGRATION_LOCK_KEY = 8675309L
private const val CLICKHOUSE_MIGRATION_LOCK_WAIT_TIMEOUT_MS = 120_000L
private const val CLICKHOUSE_MIGRATION_LOCK_POLL_INTERVAL_MS = 1_000L

private fun tryAcquireAdvisoryLock(connection: Connection, lockKey: Long): Boolean =
    connection.createStatement().use { statement ->
        statement.executeQuery("SELECT pg_try_advisory_lock($lockKey)").use { rs ->
            rs.next() && rs.getBoolean(1)
        }
    }

private fun releaseAdvisoryLock(connection: Connection, lockKey: Long) {
    connection.createStatement().use { statement ->
        statement.execute("SELECT pg_advisory_unlock($lockKey)")
    }
}

private fun waitForAdvisoryLockRelease(connection: Connection, lockKey: Long): Boolean {
    val startMs = System.currentTimeMillis()

    while (System.currentTimeMillis() - startMs < CLICKHOUSE_MIGRATION_LOCK_WAIT_TIMEOUT_MS) {
        if (tryAcquireAdvisoryLock(connection, lockKey)) {
            releaseAdvisoryLock(connection, lockKey)
            return true
        }
        Thread.sleep(CLICKHOUSE_MIGRATION_LOCK_POLL_INTERVAL_MS)
    }

    return false
}

private fun verifyCriticalColumnsPresent(dataSource: HikariDataSource) {
    val requiredColumns =
        setOf(
            "users.phone_number",
            "subscriptions.pending_meter_batch_id",
            "subscriptions.pending_meter_batch_units",
        )

    val existingColumns =
        buildSet {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        """
                        SELECT table_name, column_name
                        FROM information_schema.columns
                        WHERE (table_name = 'users' AND column_name = 'phone_number')
                           OR (table_name = 'subscriptions' AND column_name IN ('pending_meter_batch_id', 'pending_meter_batch_units'))
                        """.trimIndent()
                    ).use { rs ->
                        while (rs.next()) {
                            add("${rs.getString(1)}.${rs.getString(2)}")
                        }
                    }
                }
            }
        }

    val missing = requiredColumns - existingColumns
    if (missing.isNotEmpty()) {
        throw IllegalStateException("PostgreSQL schema missing critical columns: ${missing.joinToString(", ")}")
    }
}

fun Application.configureDatabases() {
    val config = environment.config

    try {
        // PostgreSQL connection pool
        val hikariConfig =
            HikariConfig().apply {
                jdbcUrl = config.property("database.postgres.url").getString()
                driverClassName = config.property("database.postgres.driver").getString()
                username = config.property("database.postgres.user").getString()
                password = config.property("database.postgres.password").getString()
                maximumPoolSize = config.property("database.postgres.maxPoolSize").getString().toInt()
                minimumIdle = 5
                connectionTimeout = 10000
                leakDetectionThreshold = 30000
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
                validate()
            }

        val dataSource = HikariDataSource(hikariConfig)

        // Skip Flyway migrations for H2 test database
        val isTestDatabase = hikariConfig.jdbcUrl.contains("jdbc:h2:mem")

        if (!isTestDatabase) {
            // Run Flyway migrations for PostgreSQL
            log.info("Running PostgreSQL migrations...")
            val flyway =
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .validateMigrationNaming(true)
                    .baselineOnMigrate(true)
                    .load()

            val migrationsApplied = flyway.migrate()
            log.info("Applied ${migrationsApplied.migrationsExecuted} PostgreSQL migration(s)")

            val flywayInfo = flyway.info()
            val resolvedMigrations = flywayInfo.all().size
            val appliedMigrations = flywayInfo.applied().size
            if (resolvedMigrations == 0 || appliedMigrations == 0) {
                throw IllegalStateException(
                    "Flyway resolved=$resolvedMigrations applied=$appliedMigrations. " +
                        "Database is not in a valid migrated state."
                )
            }

            verifyCriticalColumnsPresent(dataSource)
        } else {
            log.info("Test database detected (H2), skipping PostgreSQL migrations")
        }

        Database.connect(dataSource)
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED

        log.info("PostgreSQL database connected")

        // Run ClickHouse migrations only if ClickHouse is configured
        if (!isTestDatabase) {
            dataSource.connection.use { conn ->
                conn.autoCommit = true

                // Use PostgreSQL advisory lock to prevent concurrent ClickHouse migrations
                // across multiple application instances (e.g. K8s pods starting simultaneously)
                if (tryAcquireAdvisoryLock(conn, CLICKHOUSE_MIGRATION_LOCK_KEY)) {
                    try {
                        runBlocking {
                            try {
                                configureClickHouseMigrations()
                            } catch (e: Exception) {
                                log.error("Failed to run ClickHouse migrations.", e)
                                throw e
                            }
                            // Reseed demo data if stale (prevents ClickHouse TTL from deleting demo rows)
                            try {
                                com.moneat.config.DemoDataReseeder
                                    .reseedIfNeeded()
                            } catch (e: Exception) {
                                log.warn("Demo data reseed failed (non-fatal)", e)
                            }
                        }
                    } finally {
                        releaseAdvisoryLock(conn, CLICKHOUSE_MIGRATION_LOCK_KEY)
                    }
                } else {
                    log.info("Another instance is running ClickHouse migrations, waiting for completion")
                    val migrationsFinished = waitForAdvisoryLockRelease(conn, CLICKHOUSE_MIGRATION_LOCK_KEY)
                    if (!migrationsFinished) {
                        throw IllegalStateException(
                            "Timed out waiting ${CLICKHOUSE_MIGRATION_LOCK_WAIT_TIMEOUT_MS}ms " +
                                "for ClickHouse migration lock release"
                        )
                    }
                    log.info("ClickHouse migrations completed by another instance")
                }
            }
        }

        // Register shutdown hook
        monitor.subscribe(ApplicationStopping) {
            log.info("Stopping background services...")
            SystemStatusTracker.stop()
        }
    } catch (e: Exception) {
        if (e.message?.contains("ClickHouse") == true) {
            // Already logged above
            throw e
        }
        log.error("Failed to configure databases. Make sure PostgreSQL is running and accessible.", e)
        throw e
    }
}
