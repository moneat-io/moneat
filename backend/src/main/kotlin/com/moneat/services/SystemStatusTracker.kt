package com.moneat.services

import com.moneat.models.Systems
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Background service that checks system health status.
 * Marks systems as "down" if they haven't reported metrics in the last 5 minutes.
 */
object SystemStatusTracker {
    private var job: Job? = null
    private const val CHECK_INTERVAL_SECONDS = 30L
    private val DOWN_THRESHOLD = 5.minutes
    
    fun start() {
        if (job != null && job?.isActive == true) {
            logger.warn { "SystemStatusTracker already running" }
            return
        }
        
        job = CoroutineScope(Dispatchers.Default).launch {
            logger.info { "SystemStatusTracker started" }
            
            while (isActive) {
                try {
                    updateSystemStatuses()
                } catch (e: Exception) {
                    logger.error(e) { "Error updating system statuses: ${e.message}" }
                }
                
                delay(CHECK_INTERVAL_SECONDS.seconds)
            }
        }
    }
    
    fun stop() {
        job?.cancel()
        job = null
        logger.info { "SystemStatusTracker stopped" }
    }
    
    private fun updateSystemStatuses() {
        val now = Clock.System.now()
        val threshold = now - DOWN_THRESHOLD
        
        // Mark systems as down if last_seen_at is older than threshold
        val downCount = transaction {
            Systems.update({
                (Systems.last_seen_at less threshold) and (Systems.status eq "up")
            }) {
                it[Systems.status] = "down"
                it[Systems.updated_at] = now
            }
        }
        
        if (downCount > 0) {
            logger.info { "Marked $downCount system(s) as down" }
        }
        
        // Note: Systems are marked as "up" in MonitorService.ingestMetrics when they send data
    }
}
