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

package com.moneat.ingestion.queue

import io.lettuce.core.RedisClient
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals

class IngestionQueueAdmissionIntegrationTest {
    @Test
    fun `atomic admission never exceeds configured stream capacity`() {
        val redisContainer = GenericContainer<Nothing>(DockerImageName.parse(REDIS_IMAGE)).apply {
            withExposedPorts(REDIS_PORT)
        }
        redisContainer.start()
        val redisUri = "redis://${redisContainer.host}:${redisContainer.getMappedPort(REDIS_PORT)}"
        val client = RedisClient.create(redisUri)
        try {
            assertAtomicCapacity(client)
        } finally {
            client.shutdown()
            redisContainer.stop()
        }
    }

    private fun assertAtomicCapacity(client: RedisClient) {
        val spec = queueSpec(capacity = 5)
        val results = admitConcurrently(client, spec)

        assertEquals(5, results.count { it != null })
        client.connect().use { connection ->
            assertEquals(5L, connection.sync().xlen(spec.streamKey))
        }
    }

    private fun admitConcurrently(
        client: RedisClient,
        spec: IngestionQueueSpec,
    ): List<String?> {
        val executor = Executors.newFixedThreadPool(12)
        return try {
            executor.invokeAll(
                (1..50).map { index -> admissionTask(client, spec, index) }
            ).map { future -> future.get() }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun admissionTask(
        client: RedisClient,
        spec: IngestionQueueSpec,
        index: Int,
    ): Callable<String?> =
        Callable {
            client.connect().use { connection ->
                IngestionQueueClient.admit(
                    connection.sync(),
                    spec,
                    "payload-$index",
                    System.currentTimeMillis(),
                )
            }
        }

    private fun queueSpec(capacity: Long): IngestionQueueSpec =
        IngestionQueueSpec(
            pipeline = IngestionPipeline.LOGS,
            workerCount = 1,
            streamKey = "integration:logs:stream",
            dlqStreamKey = "integration:logs:dlq:stream",
            consumerGroup = "integration:logs:workers",
            batchSize = 10,
            claimIdleMs = 1_000,
            maxDeliveries = 3,
            readTimeoutMs = 100,
            maxPendingEntries = capacity,
            dlqStreamMaxLen = 100,
        )

    private companion object {
        const val REDIS_IMAGE = "redis:7.2-alpine"
        const val REDIS_PORT = 6379
    }
}
