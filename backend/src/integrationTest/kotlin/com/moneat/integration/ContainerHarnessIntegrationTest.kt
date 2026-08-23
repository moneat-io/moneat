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

import com.moneat.alerts.models.AlertEpisodes
import com.moneat.enterprise.alertroutes.commands.AlertGroupPolicy
import com.moneat.enterprise.alertroutes.commands.AlertRouteActor
import com.moneat.enterprise.alertroutes.commands.AlertRouteGroupingInput
import com.moneat.enterprise.alertroutes.commands.AlertRoutePagingInput
import com.moneat.enterprise.alertroutes.commands.AlertRoutePolicy
import com.moneat.enterprise.alertroutes.commands.AlertRouteSpecification
import com.moneat.enterprise.alertroutes.commands.AlertRouteTargetInput
import com.moneat.enterprise.alertroutes.commands.CreateAlertRouteCommand
import com.moneat.enterprise.alertroutes.context.AlertRouteContext
import com.moneat.enterprise.alertroutes.context.AlertRouteEpisodeIdentity
import com.moneat.enterprise.alertroutes.evaluation.AlertRouteEvaluator
import com.moneat.enterprise.alertroutes.models.AlertRoutePagingMode
import com.moneat.enterprise.alertroutes.models.AlertRouteTargetKind
import com.moneat.enterprise.alertroutes.services.AlertGroupService
import com.moneat.enterprise.alertroutes.services.AlertRouteCommandService
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import mu.KotlinLogging
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlin.test.assertEquals
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
        lateinit var postgres: PostgreSQLContainer
        lateinit var clickhouse: GenericContainer<*>
        lateinit var redis: GenericContainer<*>

        @BeforeAll
        @JvmStatic
        fun setupContainers() {
            logger.info { "Starting PostgreSQL container..." }
            postgres =
                PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                    .withDatabaseName("moneat_test")
                    .withUsername("test")
                    .withPassword("test")
            postgres.start()
            logger.info { "PostgreSQL started at ${postgres.jdbcUrl}" }

            logger.info { "Starting ClickHouse container..." }
            clickhouse =
                GenericContainer(DockerImageName.parse("clickhouse/clickhouse-server:26.1-alpine"))
                    .withExposedPorts(8123, 9000)
                    .withEnv("CLICKHOUSE_DB", "moneat_test")
                    .withEnv("CLICKHOUSE_USER", "test")
                    .withEnv("CLICKHOUSE_PASSWORD", "test")
            clickhouse.start()
            logger.info { "ClickHouse started at http://${clickhouse.host}:${clickhouse.getMappedPort(8123)}" }

            logger.info { "Starting Redis container..." }
            redis =
                GenericContainer(DockerImageName.parse("redis:8-alpine"))
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
            val flyway =
                Flyway
                    .configure()
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
        val flyway =
            Flyway
                .configure()
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
    fun `postgres serializes concurrent first alert groups and paging claims`() {
        val seed = seedAlertGroupScenario()
        val route = AlertRouteCommandService(policy = AlertRoutePolicy.allowForTests()).execute(
            CreateAlertRouteCommand(
                commandKey = "postgres-route-${Uuid.random()}",
                actor = AlertRouteActor(seed.organizationId, seed.userId, "INTEGRATION_TEST"),
                specification = AlertRouteSpecification(
                    key = "postgres-group-${Uuid.random()}",
                    name = "PostgreSQL concurrent grouping",
                    paging = AlertRoutePagingInput(
                        mode = AlertRoutePagingMode.FIRST_EPISODE_PER_GROUP,
                        targets = listOf(
                            AlertRouteTargetInput(
                                kind = AlertRouteTargetKind.ESCALATION_POLICY,
                                escalationPolicyId = seed.escalationPolicyId,
                            ),
                        ),
                    ),
                    grouping = AlertRouteGroupingInput(keys = listOf("metadata.service")),
                ),
            ),
        ).route!!
        val service = AlertGroupService(AlertGroupPolicy.allowForTests())
        val now = Instant.parse("2026-08-23T12:00:00Z")
        val contexts = seed.episodeIds.map { episodeId -> alertGroupContext(seed.organizationId, episodeId) }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(contexts.size)

        try {
            val groups = contexts.mapIndexed { index, context ->
                executor.submit<Uuid> {
                    start.await()
                    val decision = AlertRouteEvaluator().evaluate(listOf(route), context).decision!!
                    service.recordFiring(context, decision, seed.episodeIds[index], now = now).id
                }
            }.also { start.countDown() }.map { it.get() }

            assertEquals(1, groups.toSet().size)
            val group = service.get(seed.organizationId, groups.first())
            assertEquals(2, group.members.size)

            val pagingStart = CountDownLatch(1)
            val pagingClaims = seed.episodeIds.mapIndexed { index, episodeId ->
                executor.submit<Boolean> {
                    pagingStart.await()
                    service.claimPaging(seed.organizationId, group.id, episodeId, "postgres-page-$index")
                }
            }.also { pagingStart.countDown() }.map { it.get() }
            assertEquals(1, pagingClaims.count { it })
        } finally {
            executor.shutdownNow()
        }
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

    private fun seedAlertGroupScenario(): AlertGroupIntegrationSeed = transaction {
        val suffix = Uuid.random().toString()
        val userId = Users.insert {
            it[email] = "postgres-alert-group-$suffix@example.test"
            it[password_hash] = "x"
            it[name] = "PostgreSQL Alert Group"
        }[Users.id]
        val organizationId = Organizations.insert {
            it[name] = "PostgreSQL Alert Group"
            it[slug] = "postgres-alert-group-$suffix"
        }[Organizations.id]
        Memberships.insert {
            it[user_id] = userId
            it[Memberships.organization_id] = organizationId
            it[role] = "owner"
        }
        val now = Clock.System.now()
        val escalationPolicyId = Uuid.random()
        EscalationPolicies.insert {
            it[resourceId] = escalationPolicyId
            it[EscalationPolicies.organizationId] = organizationId
            it[name] = "PostgreSQL concurrent paging"
            it[repeatCount] = 1
            it[createdAt] = now
            it[updatedAt] = now
        }
        val episodeIds = List(2) { index ->
            val resourceId = Uuid.random()
            AlertEpisodes.insert {
                it[AlertEpisodes.resourceId] = resourceId
                it[AlertEpisodes.organizationId] = organizationId
                it[sourceName] = "DASHBOARD_ALERT"
                it[deduplicationKey] = "postgres-concurrent-$suffix-$index"
                it[title] = "Checkout latency"
                it[description] = "Latency above threshold"
                it[priority] = "P1"
                it[episodeSeq] = 1
                it[episodeKey] = "postgres-concurrent-$suffix-$index#1"
                it[status] = "OPEN"
                it[openedAt] = now
                it[lastSeenAt] = now
                it[createdAt] = now
                it[updatedAt] = now
            }
            resourceId
        }
        AlertGroupIntegrationSeed(organizationId, userId, escalationPolicyId, episodeIds)
    }

    private fun alertGroupContext(organizationId: Int, episodeId: Uuid) =
        AlertRouteContext(
            organizationId = organizationId,
            source = "DASHBOARD_ALERT",
            status = "FIRING",
            priority = "P1",
            title = "Checkout latency",
            description = "Latency above threshold",
            deduplicationKey = episodeId.toString(),
            url = "",
            episode = AlertRouteEpisodeIdentity(episodeId.toString(), "$episodeId#1", 1, "OPEN"),
            metadata = mapOf("service" to "checkout"),
        )
}

private data class AlertGroupIntegrationSeed(
    val organizationId: Int,
    val userId: Int,
    val escalationPolicyId: Uuid,
    val episodeIds: List<Uuid>,
)
