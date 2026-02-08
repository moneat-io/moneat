package com.moneat.plugins

import com.moneat.services.SystemStatusTracker
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.sql.Connection

fun Application.configureDatabases() {
    val config = environment.config
    
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
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        validate()
    }
    
    val dataSource = HikariDataSource(hikariConfig)
    
    // Run Flyway migrations
    log.info("Running database migrations...")
    val flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true) // Allow migrations on existing databases
        .load()
    
    val migrationsApplied = flyway.migrate()
    log.info("Applied ${migrationsApplied.migrationsExecuted} database migration(s)")
    
    val database = Database.connect(dataSource)
    TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_REPEATABLE_READ
    
    log.info("PostgreSQL database connected")
    
    // Register shutdown hook
    environment.monitor.subscribe(ApplicationStopped) {
        log.info("Stopping background services...")
        SystemStatusTracker.stop()
    }
}
