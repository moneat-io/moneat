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
import io.ktor.events.*
import io.ktor.server.application.*
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection

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
                    .baselineOnMigrate(true)
                    .load()

            val migrationsApplied = flyway.migrate()
            log.info("Applied ${migrationsApplied.migrationsExecuted} PostgreSQL migration(s)")
        } else {
            log.info("Test database detected (H2), skipping PostgreSQL migrations")
        }

        Database.connect(dataSource)
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED

        log.info("PostgreSQL database connected")

        // Run ClickHouse migrations only if ClickHouse is configured
        if (!isTestDatabase) {
            runBlocking {
                try {
                    configureClickHouseMigrations()
                } catch (e: Exception) {
                    log.error("Failed to run ClickHouse migrations. Make sure ClickHouse is running and accessible.", e)
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
