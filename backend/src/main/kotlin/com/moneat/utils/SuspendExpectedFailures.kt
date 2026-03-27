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

import io.lettuce.core.RedisException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import mu.KLogger
import java.io.IOException
import java.sql.SQLException

/**
 * Runs [block] and returns [fallback] on expected I/O, SQL, JSON, or contract failures.
 * [CancellationException] is never swallowed (coroutine cancellation must propagate).
 *
 * Ktor [io.ktor.client.plugins.ResponseException] extends [IllegalStateException] and is covered
 * by the [IllegalStateException] branch when [block] performs HTTP calls.
 */
suspend inline fun <T> recoverOnExpectedFailures(
    logger: KLogger,
    operation: String,
    fallback: T,
    crossinline block: suspend () -> T,
): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    logger.error(e) { "$operation failed" }
    fallback
} catch (e: SQLException) {
    logger.error(e) { "$operation failed" }
    fallback
} catch (e: SerializationException) {
    logger.error(e) { "$operation failed" }
    fallback
} catch (e: IllegalStateException) {
    logger.error(e) { "$operation failed" }
    fallback
} catch (e: IllegalArgumentException) {
    logger.error(e) { "$operation failed" }
    fallback
} catch (e: RedisException) {
    logger.error(e) { "$operation failed" }
    fallback
}
