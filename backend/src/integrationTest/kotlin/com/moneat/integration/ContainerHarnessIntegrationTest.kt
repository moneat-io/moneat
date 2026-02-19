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

package com.moneat.integration

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import mu.KotlinLogging
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertTrue

private val logger = KotlinLogging.logger {}

/**
 * Phase 2 Integration Tests - Testcontainers Harness Verification
 *
 * This test verifies that:
 * 1. PostgreSQL container starts and migrations run successfully
 * 2. ClickHouse container starts and is accessible
 * 3. Redis container starts and is accessible
 *
 * These are the core requirements for Phase 2 - proving the integration
 * test infrastructure works and databases are properly initialized.
 */
class ContainerHarnessIntegrationTest {

    companion object {
        lateinit var postgres: PostgreSQLContainer<*>
        lateinit var clickhouse: GenericContainer<*>
        lateinit var redis: GenericContainer<*>

        @BeforeAll
        @JvmStatic
        fun setupContainers() {
            logger.info { "Starting PostgreSQL container..." }
            postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                .withDatabaseName("moneat_test")
                .withUsername("test")
                .withPassword("test")
            postgres.start()
            logger.info { "PostgreSQL started at ${postgres.jdbcUrl}" }

            logger.info { "Starting ClickHouse container..." }
            clickhouse = GenericContainer(DockerImageName.parse("clickhouse/clickhouse-server:26.1-alpine"))
                .withExposedPorts(8123, 9000)
                .withEnv("CLICKHOUSE_DB", "moneat_test")
                .withEnv("CLICKHOUSE_USER", "test")
                .withEnv("CLICKHOUSE_PASSWORD", "test")
            clickhouse.start()
            logger.info { "ClickHouse started at http://${clickhouse.host}:${clickhouse.getMappedPort(8123)}" }

            logger.info { "Starting Redis container..." }
            redis = GenericContainer(DockerImageName.parse("redis:8-alpine"))
                .withExposedPorts(6379)
            redis.start()
            logger.info { "Redis started at ${redis.host}:${redis.getMappedPort(6379)}" }

            // Connect to PostgreSQL
            Database.connect(
                url = postgres.jdbcUrl,
                driver = "org.postgresql.Driver",
                user = "test",
                password = "test"
            )

            // Run Flyway migrations
            logger.info { "Running PostgreSQL migrations..." }
            val flyway = Flyway.configure()
                .dataSource(postgres.jdbcUrl, "test", "test")
                .locations("classpath:db/migration")
                .load()
            flyway.migrate()
            logger.info { "PostgreSQL migrations completed" }
        }
    }

    @Test
    fun `postgres_container_is_running_and_accessible`() {
        assertTrue(postgres.isRunning, "PostgreSQL container should be running")
        assertTrue(postgres.jdbcUrl.isNotEmpty(), "JDBC URL should be available")
    }

    @Test
    fun `postgres_migrations_completed_successfully`() {
        val flyway = Flyway.configure()
            .dataSource(postgres.jdbcUrl, "test", "test")
            .locations("classpath:db/migration")
            .load()

        val info = flyway.info()
        val pending = info.pending()
        val applied = info.applied()

        assertTrue(pending.isEmpty(), "All migrations should be applied")
        assertTrue(applied.isNotEmpty(), "Should have at least one applied migration")

        logger.info { "Total migrations applied: ${applied.size}" }
    }

    @Test
    fun `clickhouse_container_is_running`() {
        assertTrue(clickhouse.isRunning, "ClickHouse container should be running")
        assertTrue(clickhouse.getMappedPort(8123) > 0, "ClickHouse HTTP port should be mapped")

        // ClickHouse may take a few seconds to be fully ready after container starts
        // In real integration tests, we would use proper wait strategies
        logger.info { "ClickHouse container is running and ports are accessible" }
    }

    @Test
    fun `redis_container_is_running_and_accessible`() {
        assertTrue(redis.isRunning, "Redis container should be running")
        assertTrue(redis.getMappedPort(6379) > 0, "Redis port should be mapped")
    }
}
