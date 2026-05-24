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

package com.moneat.utils

import com.moneat.config.RedisConfig
import com.moneat.monitoring.OperationalMetrics
import kotlinx.coroutines.delay
import mu.KLogger

/**
 * Logs a Redis BRPOP loop failure and backs off. Use after [catch] for
 * [io.lettuce.core.RedisException] or [java.io.IOException].
 */
suspend fun brpopLoopBackoff(
    logger: KLogger,
    workerId: Int,
    scopeLabel: String,
    errorDelayMs: Long,
    e: Throwable,
) {
    logger.error(e) { "$scopeLabel worker $workerId error in BRPOP loop" }
    OperationalMetrics.recordWorkerBrpopFailure(scopeLabel, workerId, e)
    delay(errorDelayMs)
}

/**
 * Logs a processing failure and pushes the payload to a Redis dead-letter
 * queue. DLQ write errors are caught and logged so they never propagate.
 */
@Suppress("TooGenericExceptionCaught")
fun pushToDlq(
    logger: KLogger,
    dlqKey: String,
    payload: String,
    workerId: Int,
    workerName: String,
    cause: Throwable,
) {
    logger.error(cause) {
        "$workerName worker $workerId failed, pushing to DLQ"
    }
    OperationalMetrics.recordWorkerProcessingFailure(workerName, workerId, cause)
    runCatching {
        RedisConfig.sync().rpush(dlqKey, payload)
    }.onSuccess {
        OperationalMetrics.recordDlqPush(workerName, dlqKey, "success")
    }.onFailure { dlqErr ->
        OperationalMetrics.recordDlqPush(workerName, dlqKey, "failure")
        logger.error(dlqErr) {
            "Failed to write to DLQ for worker $workerId, dlqKey=$dlqKey"
        }
    }
}
