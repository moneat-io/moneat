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

import com.moneat.config.ClickHouseClient
import com.moneat.utils.TimeConstants.MILLIS_PER_SECOND_LONG
import com.moneat.utils.suspendRunCatching
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

private const val APM_TRACES_FINAL_TABLE = "apm_traces_final"
private const val APM_TRACE_SUMMARIES_TABLE = "apm_trace_summaries"

private const val DEFAULT_INTERVAL_SECONDS = 600L
private const val DEFAULT_REFRESH_WINDOW_HOURS = 2
private const val DEFAULT_BACKFILL_WINDOW_HOURS = 50
private const val DEFAULT_LOOKBACK_HOURS = 2
private const val DEFAULT_MAX_EXECUTION_SECONDS = 540

/**
 * Periodically finalizes per-trace rows from apm_trace_summaries into apm_traces_final so the APM
 * dashboard can read one finalized row per trace (plain columns) instead of re-aggregating with
 * argMinMerge on every request. See V45__add_apm_traces_final.sql.
 *
 * Each run re-finalizes a small recent window (idempotent via ReplacingMergeTree(finalized_at)), which
 * keeps the leading edge fresh and absorbs late-arriving spans. On process start it first finalizes a
 * wider window so history (and any gap from downtime) is covered. The heavy argMinMerge aggregation
 * runs here, off the dashboard read path.
 */
class TraceFinalizerBackgroundService(
    private val enabled: Boolean,
    private val intervalSeconds: Long,
    private val refreshWindowHours: Int,
    private val backfillWindowHours: Int,
    private val lookbackHours: Int,
    private val maxExecutionSeconds: Int,
) {
    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()

    init {
        // Fail fast on misconfiguration rather than letting the loop spin (delay(0)) or emit
        // nonsensical windows.
        require(intervalSeconds > 0) { "traceFinalizer.intervalSeconds must be > 0, got $intervalSeconds" }
        require(refreshWindowHours > 0) {
            "traceFinalizer.refreshWindowHours must be > 0, got $refreshWindowHours"
        }
        require(backfillWindowHours >= refreshWindowHours) {
            "traceFinalizer.backfillWindowHours ($backfillWindowHours) must be >= refreshWindowHours " +
                "($refreshWindowHours)"
        }
        require(lookbackHours >= 0) { "traceFinalizer.lookbackHours must be >= 0, got $lookbackHours" }
        require(maxExecutionSeconds > 0) {
            "traceFinalizer.maxExecutionSeconds must be > 0, got $maxExecutionSeconds"
        }
    }

    private var finalizeJob: Job? = null

    @Volatile
    private var startupBackfillDone = false

    fun start(scope: CoroutineScope) {
        if (!enabled) {
            logger.info { "Trace finalizer background job is disabled by config" }
            return
        }
        if (finalizeJob?.isActive == true) {
            logger.warn { "Trace finalizer already running; ignoring duplicate start()" }
            return
        }
        finalizeJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    suspendRunCatching {
                        runOnce()
                    }.onFailure { e ->
                        logger.error(e) { "Trace finalization run failed" }
                    }
                    delay(intervalSeconds * MILLIS_PER_SECOND_LONG)
                }
            }
    }

    fun stop() {
        finalizeJob?.cancel()
    }

    /**
     * One-shot finalization of the last [emitHours] from apm_trace_summaries into apm_traces_final.
     * Used by demo seeding so the traces dashboard has data without waiting for the scheduled run.
     */
    suspend fun finalizeRecent(emitHours: Int) = finalizeWindow(emitHours)

    internal suspend fun runOnce() {
        if (!ClickHouseClient.isInitialized()) {
            logger.debug { "Trace finalization skipped: ClickHouse is not initialized" }
            return
        }
        // First run after startup covers history + heals any gap from downtime; later runs only
        // refresh the recent window (cheap, keeps the leading edge fresh, absorbs late spans).
        val windowHours = if (!startupBackfillDone) backfillWindowHours else refreshWindowHours
        finalizeWindow(windowHours)
        if (!startupBackfillDone) {
            startupBackfillDone = true
            logger.info { "Trace finalizer startup backfill complete (${backfillWindowHours}h)" }
        }
    }

    /**
     * Finalize traces whose start falls within the last [emitHours]. Summaries are scanned a little
     * further back ([lookbackHours]) so a trace straddling the lower edge resolves its true start and
     * is correctly excluded (rather than re-finalized with a partial, newer-versioned row).
     */
    private suspend fun finalizeWindow(emitHours: Int) {
        val scanHours = emitHours + lookbackHours
        val sql = """
            INSERT INTO `$clickhouseDb`.$APM_TRACES_FINAL_TABLE
                (organization_id, trace_bucket, trace_id_canonical, root_service, root_resource,
                 root_name, source, env, trace_start, duration_ns, span_count, error_count, has_error)
            SELECT
                organization_id,
                toStartOfHour(trace_start) AS trace_bucket,
                trace_id_canonical,
                root_service, root_resource, root_name, source, env,
                trace_start, duration_ns, span_count, error_count, has_error
            FROM (
                SELECT
                    organization_id,
                    trace_id_canonical,
                    argMinMerge(root_service_state) AS root_service,
                    argMinMerge(root_resource_state) AS root_resource,
                    argMinMerge(root_name_state) AS root_name,
                    argMinMerge(source_state) AS source,
                    any(env) AS env,
                    minMerge(trace_start_state) AS trace_start,
                    toUInt64(greatest(
                        0,
                        toUnixTimestamp64Nano(maxMerge(trace_end_state)) -
                            toUnixTimestamp64Nano(minMerge(trace_start_state))
                    )) AS duration_ns,
                    toUInt32(sumMerge(span_count_state)) AS span_count,
                    toUInt32(sumMerge(error_count_state)) AS error_count,
                    toUInt8(sumMerge(error_count_state) > 0) AS has_error
                FROM `$clickhouseDb`.$APM_TRACE_SUMMARIES_TABLE
                WHERE bucket_start >= toStartOfHour(now() - INTERVAL $scanHours HOUR)
                GROUP BY organization_id, trace_id_canonical
            )
            WHERE toStartOfHour(trace_start) >= toStartOfHour(now() - INTERVAL $emitHours HOUR)
            SETTINGS max_execution_time = $maxExecutionSeconds
        """.trimIndent()
        ClickHouseClient.executeLongRunning(sql, operation = "trace_finalize")
    }

    companion object {
        /**
         * Build the service from application config, applying defaults for any missing keys. Used by
         * production wiring; tests construct the class directly with explicit values.
         */
        fun fromConfig(
            config: ApplicationConfig = ApplicationConfig("application.conf"),
        ): TraceFinalizerBackgroundService =
            TraceFinalizerBackgroundService(
                enabled = config.propertyOrNull("traceFinalizer.enabled")
                    ?.getString()?.toBooleanStrictOrNull() ?: true,
                intervalSeconds = config.propertyOrNull("traceFinalizer.intervalSeconds")
                    ?.getString()?.toLongOrNull() ?: DEFAULT_INTERVAL_SECONDS,
                refreshWindowHours = config.propertyOrNull("traceFinalizer.refreshWindowHours")
                    ?.getString()?.toIntOrNull() ?: DEFAULT_REFRESH_WINDOW_HOURS,
                backfillWindowHours = config.propertyOrNull("traceFinalizer.backfillWindowHours")
                    ?.getString()?.toIntOrNull() ?: DEFAULT_BACKFILL_WINDOW_HOURS,
                lookbackHours = config.propertyOrNull("traceFinalizer.lookbackHours")
                    ?.getString()?.toIntOrNull() ?: DEFAULT_LOOKBACK_HOURS,
                maxExecutionSeconds = config.propertyOrNull("traceFinalizer.maxExecutionSeconds")
                    ?.getString()?.toIntOrNull() ?: DEFAULT_MAX_EXECUTION_SECONDS,
            )
    }
}
