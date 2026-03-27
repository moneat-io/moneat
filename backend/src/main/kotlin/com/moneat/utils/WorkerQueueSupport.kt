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
    delay(errorDelayMs)
}
