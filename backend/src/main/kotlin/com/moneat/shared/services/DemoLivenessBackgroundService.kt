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

import com.moneat.config.EnvConfig
import com.moneat.utils.TimeConstants.MILLIS_PER_SECOND_LONG
import com.moneat.utils.suspendRunCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

private const val DEFAULT_INTERVAL_SECONDS = 60L
private const val DEMO_ORG_ID = -1L
private const val DEFAULT_DOWN_HOST = "prod-worker-01"

/**
 * Keeps demo infrastructure looking alive. Demo data is only seeded relative to now() at startup, but
 * host liveness in the infrastructure views is derived from `last_seen_at` being within the last 5
 * minutes (see DatadogHostService.rowToDdHostInfo). Without a real agent sending heartbeats, every demo
 * host falls to DOWN a few minutes after boot. This demo-only job re-stamps `last_seen_at` (and the
 * agent `status`) on a short interval so the demo stays realistic, leaving [downHostname] stale on
 * purpose so the fleet shows one DOWN host. Only runs when DEMO_ENABLED=true.
 *
 * The demo gate and the DB write are injected so the loop logic is unit-testable without a database.
 */
class DemoLivenessBackgroundService(
    private val intervalSeconds: Long = DEFAULT_INTERVAL_SECONDS,
    /** One host intentionally left DOWN for a realistic fleet. Matches the demo host seeded by DemoReseedDatadog. */
    private val downHostname: String = DEFAULT_DOWN_HOST,
    private val demoEnabled: () -> Boolean = { EnvConfig.Demo.enabled },
    private val refresh: suspend (downHostname: String) -> Unit = { host -> defaultRefresh(host) },
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (!demoEnabled()) {
            logger.debug { "Demo liveness job disabled (DEMO_ENABLED=false)" }
            return
        }
        if (job?.isActive == true) {
            logger.warn { "Demo liveness job already running; ignoring duplicate start()" }
            return
        }
        logger.info { "Starting demo liveness job (refreshing demo host heartbeats every ${intervalSeconds}s)" }
        job =
            scope.launch {
                while (isActive) {
                    refreshOnce()
                    delay(intervalSeconds * MILLIS_PER_SECOND_LONG)
                }
            }
    }

    fun stop() {
        job?.cancel()
    }

    /** Run a single heartbeat refresh. Exposed for tests and for the loop body. */
    internal suspend fun refreshOnce() {
        suspendRunCatching { refresh(downHostname) }
            .onFailure { e ->
                logger.warn { "Demo host heartbeat refresh failed (non-fatal): ${e.message}" }
            }
    }

    companion object {
        /** UPDATE that re-stamps every demo host except the one intentionally left DOWN. */
        internal fun heartbeatSql(downHostname: String): String =
            """
            UPDATE hosts
            SET last_seen_at = NOW(), status = 'up'
            WHERE organization_id = $DEMO_ORG_ID AND hostname <> '$downHostname'
            """.trimIndent()

        private suspend fun defaultRefresh(downHostname: String) {
            withContext(Dispatchers.IO) {
                transaction { exec(heartbeatSql(downHostname)) }
            }
        }
    }
}
