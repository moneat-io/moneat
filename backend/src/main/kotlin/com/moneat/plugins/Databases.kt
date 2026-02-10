package com.moneat.plugins

import com.moneat.config.configureClickHouseMigrations
import com.moneat.services.SystemStatusTracker
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.sql.Connection

fun Application.configureDatabases() {
    val config = environment.config
    
    try {
        // PostgreSQL connection pool
        val hikariConfig = HikariConfig().apply {
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
        
        // Run Flyway migrations for PostgreSQL
        log.info("Running PostgreSQL migrations...")
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
        
        val migrationsApplied = flyway.migrate()
        log.info("Applied ${migrationsApplied.migrationsExecuted} PostgreSQL migration(s)")
        
        Database.connect(dataSource)
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED
        
        log.info("PostgreSQL database connected")
        
        // Run ClickHouse migrations
        runBlocking {
            try {
                configureClickHouseMigrations()
            } catch (e: Exception) {
                log.error("Failed to run ClickHouse migrations. Make sure ClickHouse is running and accessible.", e)
                throw e
            }
        }
        
        // Register shutdown hook
        environment.monitor.subscribe(ApplicationStopped) {
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
