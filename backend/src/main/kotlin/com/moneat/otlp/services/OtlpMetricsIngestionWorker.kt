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

package com.moneat.otlp.services

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class OtlpMetricsIngestionWorker(
    queueKey: String,
    dlqKey: String,
    workerCount: Int,
    private val metricsService: OtlpMetricsService = OtlpMetricsService(),
) : OtlpIngestionWorkerBase(
    queueKey,
    dlqKey,
    workerCount,
    "OtlpMetricsIngestionWorker",
    "metrics",
) {
    override suspend fun processMessage(workerId: Int, payload: String) {
        try {
            val batch = metricsService.decodeBatch(payload)
            metricsService.insertBatch(batch)
            logger.debug {
                "OTLP metrics worker $workerId inserted ${batch.metrics.size} metrics " +
                    "for org ${batch.organizationId}"
            }
        } catch (e: Exception) {
            logger.error(e) {
                "OTLP metrics worker $workerId failed to process batch, sending to DLQ"
            }
            pushToDlq(workerId, payload)
        }
    }
}
