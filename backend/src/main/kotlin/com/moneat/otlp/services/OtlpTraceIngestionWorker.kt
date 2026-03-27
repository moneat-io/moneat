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

import io.lettuce.core.RedisException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import mu.KotlinLogging
import java.io.IOException
import java.sql.SQLException

private val logger = KotlinLogging.logger {}

class OtlpTraceIngestionWorker(
    queueKey: String,
    dlqKey: String,
    workerCount: Int,
    private val traceService: OtlpTraceService = OtlpTraceService(),
) : OtlpIngestionWorkerBase(
    queueKey,
    dlqKey,
    workerCount,
    "OtlpTraceIngestionWorker",
    "trace",
) {
    override suspend fun processMessage(workerId: Int, payload: String) {
        val batch =
            try {
                traceService.decodeBatch(payload)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SerializationException) {
                logTraceDecodeFailure(workerId, payload, e)
                return
            } catch (e: IOException) {
                logTraceDecodeFailure(workerId, payload, e)
                return
            } catch (e: IllegalStateException) {
                logTraceDecodeFailure(workerId, payload, e)
                return
            } catch (e: IllegalArgumentException) {
                logTraceDecodeFailure(workerId, payload, e)
                return
            }
        try {
            traceService.insertBatch(batch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            logTraceInsertFailure(workerId, payload, e)
            return
        } catch (e: SQLException) {
            logTraceInsertFailure(workerId, payload, e)
            return
        } catch (e: RedisException) {
            logTraceInsertFailure(workerId, payload, e)
            return
        } catch (e: IllegalStateException) {
            logTraceInsertFailure(workerId, payload, e)
            return
        } catch (e: IllegalArgumentException) {
            logTraceInsertFailure(workerId, payload, e)
            return
        }
        try {
            // Tracked: https://github.com/moneat-io/moneat/issues/275 — org→project resolution + project-scoped error tracking for extracted span exceptions.
            val exceptions = OtlpErrorExtractor.extractExceptions(batch.spans)
            if (exceptions.isNotEmpty()) {
                logger.debug {
                    "OTLP trace worker $workerId extracted ${exceptions.size} exceptions " +
                        "from ${batch.spans.size} spans (org ${batch.organizationId})"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            logger.error(e) {
                "OTLP trace worker $workerId failed after insert (exception extraction)"
            }
        } catch (e: IllegalArgumentException) {
            logger.error(e) {
                "OTLP trace worker $workerId failed after insert (exception extraction)"
            }
        }
        logger.debug {
            "OTLP trace worker $workerId inserted ${batch.spans.size} spans " +
                "for org ${batch.organizationId}"
        }
    }

    private fun logTraceDecodeFailure(
        workerId: Int,
        payload: String,
        e: Throwable,
    ) {
        logger.error(e) {
            "OTLP trace worker $workerId failed to decode batch, sending to DLQ"
        }
        pushToDlq(workerId, payload)
    }

    private fun logTraceInsertFailure(
        workerId: Int,
        payload: String,
        e: Throwable,
    ) {
        logger.error(e) {
            "OTLP trace worker $workerId failed to insert batch, sending to DLQ"
        }
        pushToDlq(workerId, payload)
    }
}
