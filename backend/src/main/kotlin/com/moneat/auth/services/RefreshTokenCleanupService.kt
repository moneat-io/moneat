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

package com.moneat.auth.services

import kotlinx.serialization.SerializationException
import java.io.IOException

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger {}

fun interface RefreshTokenCleaner {
    fun cleanupExpiredTokens(): Int
}

class RefreshTokenCleanupService(
    private val refreshTokenCleaner: RefreshTokenCleaner =
        RefreshTokenCleaner {
            RefreshTokenService().cleanupExpiredTokens()
        },
    private val cleanupInterval: Duration = 24.hours
) {
    private var cleanupJob: Job? = null

    fun start(scope: CoroutineScope) {
        logger.info { "Starting refresh token cleanup service" }

        cleanupJob =
            scope.launch {
                while (isActive) {
                    try {
                        val deletedCount = refreshTokenCleaner.cleanupExpiredTokens()
                        if (deletedCount > 0) {
                            logger.info { "Cleaned up $deletedCount expired/revoked refresh tokens" }
                        }
                    } catch (e: SerializationException) {
                        logger.error(e) { "Error during refresh token cleanup" }
                    } catch (e: IOException) {
                        logger.error(e) { "Error during refresh token cleanup" }
                    } catch (e: IllegalStateException) {
                        logger.error(e) { "Error during refresh token cleanup" }
                    } catch (e: IllegalArgumentException) {
                        logger.error(e) { "Error during refresh token cleanup" }
                    }

                    // Run cleanup periodically
                    delay(cleanupInterval)
                }
            }
    }

    fun stop() {
        logger.info { "Stopping refresh token cleanup service" }
        cleanupJob?.cancel()
    }
}
