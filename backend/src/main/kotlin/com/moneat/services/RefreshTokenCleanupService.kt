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
