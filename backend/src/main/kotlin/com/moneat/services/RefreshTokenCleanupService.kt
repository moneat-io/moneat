// Moneat - Mobile-First Error Monitoring Platform
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

package com.moneat.services

import kotlinx.coroutines.*
import mu.KotlinLogging
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger {}

class RefreshTokenCleanupService {
    private var cleanupJob: Job? = null
    private val refreshTokenService = RefreshTokenService()
    
    fun start(scope: CoroutineScope) {
        logger.info { "Starting refresh token cleanup service" }
        
        cleanupJob = scope.launch {
            while (isActive) {
                try {
                    val deletedCount = refreshTokenService.cleanupExpiredTokens()
                    if (deletedCount > 0) {
                        logger.info { "Cleaned up $deletedCount expired/revoked refresh tokens" }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Error during refresh token cleanup" }
                }
                
                // Run cleanup every 24 hours
                delay(24.hours)
            }
        }
    }
    
    fun stop() {
        logger.info { "Stopping refresh token cleanup service" }
        cleanupJob?.cancel()
    }
}
