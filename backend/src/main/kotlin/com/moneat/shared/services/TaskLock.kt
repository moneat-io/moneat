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

package com.moneat.shared.services

import kotlinx.serialization.SerializationException
import java.io.IOException

import com.moneat.utils.suspendRunCatching
import mu.KotlinLogging
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.toJavaDuration

private val logger = KotlinLogging.logger {}

object TaskLock {
    private lateinit var lockProvider: LockProvider

    fun initialize(lockProvider: LockProvider) {
        this.lockProvider = lockProvider
    }

    suspend fun <T> tryWithLock(
        name: String,
        lockAtMostFor: Duration,
        lockAtLeastFor: Duration = Duration.ZERO,
        block: suspend () -> T
    ): T? {
        val config = LockConfiguration(
            Instant.now(),
            name,
            lockAtMostFor.toJavaDuration(),
            lockAtLeastFor.toJavaDuration()
        )
        val lock = try {
            lockProvider.lock(config)
        } catch (e: SerializationException) {
            logger.error(e) { "Failed to acquire lock '$name'" }
            return null
        } catch (e: IOException) {
            logger.error(e) { "Failed to acquire lock '$name'" }
            return null
        } catch (e: IllegalStateException) {
            logger.error(e) { "Failed to acquire lock '$name'" }
            return null
        } catch (e: IllegalArgumentException) {
            logger.error(e) { "Failed to acquire lock '$name'" }
            return null
        }
        if (lock.isEmpty) {
            logger.debug { "Skipping '$name' - locked by another instance" }
            return null
        }
        return try {
            suspendRunCatching {
                block()
            }.getOrElse { e ->
                logger.error(e) { "Error in locked task '$name'" }
                null
            }
        } finally {
            try {
                lock.get().unlock()
            } catch (e: SerializationException) {
                logger.error(e) { "Failed to unlock '$name'" }
            } catch (e: IOException) {
                logger.error(e) { "Failed to unlock '$name'" }
            } catch (e: IllegalStateException) {
                logger.error(e) { "Failed to unlock '$name'" }
            } catch (e: IllegalArgumentException) {
                logger.error(e) { "Failed to unlock '$name'" }
            }
        }
    }
}
