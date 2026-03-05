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

import com.moneat.shared.models.Systems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
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

        job =
            CoroutineScope(Dispatchers.Default).launch {
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
        val downCount =
            transaction {
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

        // Note: Systems are marked as "up" by the Datadog agent intake when they send data
    }
}
