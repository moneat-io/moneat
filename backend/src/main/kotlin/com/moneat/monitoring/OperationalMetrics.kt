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

package com.moneat.monitoring

import com.moneat.config.RedisConfig
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.MeterBinder
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.logging.LogbackMetrics
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object OperationalMetrics {
    private val registryRef = AtomicReference(newRegistry())
    private val registeredDlqs = ConcurrentHashMap<String, String>()
    private val registeredDlqMeters = ConcurrentHashMap<String, Unit>()
    private val registeredQueueMeters = ConcurrentHashMap<String, Unit>()
    private val workerLastSuccessSeconds = ConcurrentHashMap<String, AtomicLong>()
    private val workerLastSuccessMeters = ConcurrentHashMap<String, Unit>()
    private val dependencyHealthValues = ConcurrentHashMap<String, AtomicLong>()
    private val dependencyLastSuccessSeconds = ConcurrentHashMap<String, AtomicLong>()
    private val dependencyHealthMeters = ConcurrentHashMap<String, Unit>()
    private val backgroundJobLastSuccessSeconds = ConcurrentHashMap<String, AtomicLong>()
    private val backgroundJobLastSuccessMeters = ConcurrentHashMap<String, Unit>()
    private val systemMetricsBound = AtomicBoolean(false)
    private val closeableBinders = CopyOnWriteArrayList<AutoCloseable>()

    val registry: PrometheusMeterRegistry
        get() = registryRef.get()

    fun bindSystemMetrics() {
        if (!systemMetricsBound.compareAndSet(false, true)) return

        listOf(
            ClassLoaderMetrics(),
            JvmMemoryMetrics(),
            JvmThreadMetrics(),
            ProcessorMetrics(),
            UptimeMetrics(),
            FileDescriptorMetrics(),
            LogbackMetrics()
        ).forEach(::bindMeter)

        JvmGcMetrics()
            .also { binder ->
                binder.bindTo(registry)
                closeableBinders.add(binder)
            }
    }

    fun recordWorkerProcessingFailure(workerName: String, workerId: Int, cause: Throwable) {
        recordWorkerMessageOutcome(workerName, workerId, "failure")
        counter(
            WORKER_PROCESSING_FAILURES,
            "Worker message processing failures, before DLQ handling.",
            tags(
                "worker" to workerName.normalizedLabelValue(),
                "worker_id" to workerId.toString(),
                "exception" to cause.metricExceptionName()
            )
        ).increment()
    }

    fun recordWorkerMessageProcessed(workerName: String, workerId: Int) {
        recordWorkerMessageOutcome(workerName, workerId, "success")
        workerLastSuccessGauge(workerName, workerId).set(currentEpochSeconds())
    }

    fun recordWorkerBrpopFailure(workerName: String, workerId: Int, cause: Throwable) {
        counter(
            WORKER_BRPOP_FAILURES,
            "Redis BRPOP loop failures in background ingestion workers.",
            tags(
                "worker" to workerName.normalizedLabelValue(),
                "worker_id" to workerId.toString(),
                "exception" to cause.metricExceptionName()
            )
        ).increment()
    }

    fun registerWorkerQueues(workerName: String, queueKey: String, dlqKey: String) {
        registerQueue(workerName, queueKey, "primary")
        registerQueue(workerName, dlqKey, "dlq")
        registerDlq(workerName, dlqKey)
    }

    fun registerDlq(workerName: String, dlqKey: String) {
        val normalizedWorkerName = workerName.normalizedLabelValue()
        registeredDlqs.putIfAbsent(dlqKey, normalizedWorkerName)
        registeredDlqMeters.computeIfAbsent(dlqKey) {
            Gauge.builder(WORKER_DLQ_DEPTH, dlqKey) { key -> readQueueDepth(key) }
                .description("Current Redis dead-letter queue depth for registered worker queues.")
                .tags(tags("worker" to normalizedWorkerName, "dlq_key" to dlqKey))
                .register(registry)
            Unit
        }
    }

    fun registerQueue(workerName: String, queueKey: String, queueType: String) {
        val normalizedWorkerName = workerName.normalizedLabelValue()
        val normalizedQueueType = queueType.normalizedLabelValue()
        val meterKey = "$normalizedWorkerName|$queueKey|$normalizedQueueType"
        registeredQueueMeters.computeIfAbsent(meterKey) {
            Gauge.builder(WORKER_QUEUE_DEPTH, queueKey) { key -> readQueueDepth(key) }
                .description("Current Redis queue depth for registered worker queues.")
                .tags(
                    tags(
                        "worker" to normalizedWorkerName,
                        "queue_key" to queueKey,
                        "queue_type" to normalizedQueueType
                    )
                )
                .register(registry)
            Unit
        }
    }

    fun recordDlqPush(workerName: String, dlqKey: String, status: String) {
        registerDlq(workerName, dlqKey)
        counter(
            WORKER_DLQ_PUSHES,
            "Dead-letter queue push attempts by worker and result status.",
            tags(
                "worker" to workerName.normalizedLabelValue(),
                "dlq_key" to dlqKey,
                "status" to status
            )
        ).increment()
    }

    fun recordClickHouseRequest(operation: String, status: String, durationSeconds: Double) {
        val tags = tags(
            "operation" to operation.normalizedLabelValue(),
            "status" to status.normalizedLabelValue()
        )
        counter(
            CLICKHOUSE_REQUESTS,
            "ClickHouse HTTP request attempts by operation and result status.",
            tags
        ).increment()
        clickHouseDurationTimer(tags).record(durationSeconds.toNanos(), TimeUnit.NANOSECONDS)
    }

    fun recordClickHouseRequestFailure(operation: String, cause: Throwable, durationSeconds: Double) {
        val exceptionName = cause.metricExceptionName()
        recordClickHouseRequest(operation, exceptionName, durationSeconds)
        if (cause.isTimeoutLike()) {
            counter(
                CLICKHOUSE_REQUEST_TIMEOUTS,
                "ClickHouse HTTP requests that failed due to timeout.",
                tags(
                    "operation" to operation.normalizedLabelValue(),
                    "exception" to exceptionName
                )
            ).increment()
        }
    }

    fun recordDependencyHealth(
        dependency: String,
        healthy: Boolean,
        durationSeconds: Double,
        cause: Throwable? = null
    ) {
        val normalizedDependency = dependency.normalizedLabelValue()
        val status = if (healthy) "success" else "failure"
        dependencyHealthGauge(normalizedDependency).set(if (healthy) HEALTHY_VALUE else UNHEALTHY_VALUE)
        if (healthy) {
            dependencyLastSuccessGauge(normalizedDependency).set(currentEpochSeconds())
        }

        counter(
            DEPENDENCY_HEALTH_CHECKS,
            "Dependency health check attempts by dependency and result.",
            tags(
                "dependency" to normalizedDependency,
                "status" to status,
                "exception" to (cause?.metricExceptionName() ?: "none")
            )
        ).increment()
        dependencyHealthCheckTimer(
            tags(
                "dependency" to normalizedDependency,
                "status" to status
            )
        ).record(durationSeconds.toNanos(), TimeUnit.NANOSECONDS)
    }

    fun recordBackgroundJobRun(
        jobName: String,
        success: Boolean,
        durationSeconds: Double,
        cause: Throwable? = null
    ) {
        val normalizedJobName = jobName.normalizedLabelValue()
        val status = if (success) "success" else "failure"
        if (success) {
            backgroundJobLastSuccessGauge(normalizedJobName).set(currentEpochSeconds())
        }

        counter(
            BACKGROUND_JOB_RUNS,
            "Background job run attempts by job and result.",
            tags(
                "job" to normalizedJobName,
                "status" to status,
                "exception" to (cause?.metricExceptionName() ?: "none")
            )
        ).increment()
        backgroundJobDurationTimer(
            tags(
                "job" to normalizedJobName,
                "status" to status
            )
        ).record(durationSeconds.toNanos(), TimeUnit.NANOSECONDS)
    }

    suspend fun recordTimedBackgroundJobRun(jobName: String, block: suspend () -> Unit) {
        val startedAt = System.nanoTime()
        try {
            block()
            recordBackgroundJobRun(jobName, success = true, elapsedSecondsSince(startedAt))
        } catch (cause: Throwable) {
            recordBackgroundJobRun(jobName, success = false, elapsedSecondsSince(startedAt), cause)
            throw cause
        }
    }

    fun recordDatasourceQueryFailure(
        sourceType: String,
        operation: String,
        status: String? = null,
        cause: Throwable? = null
    ) {
        counter(
            DATASOURCE_QUERY_FAILURES,
            "Custom datasource query failures by source type, operation, and failure reason.",
            tags(
                "source_type" to sourceType.normalizedLabelValue(),
                "operation" to operation.normalizedLabelValue(),
                "failure" to (status ?: cause?.metricExceptionName() ?: "unknown").normalizedLabelValue()
            )
        ).increment()
    }

    fun scrape(): String = registry.scrape()

    internal fun resetForTest() {
        closeableBinders.forEach { binder ->
            runCatching { binder.close() }
        }
        closeableBinders.clear()
        systemMetricsBound.set(false)
        registryRef.getAndSet(newRegistry()).close()
        registeredDlqs.clear()
        registeredDlqMeters.clear()
        registeredQueueMeters.clear()
        workerLastSuccessSeconds.clear()
        workerLastSuccessMeters.clear()
        dependencyHealthValues.clear()
        dependencyLastSuccessSeconds.clear()
        dependencyHealthMeters.clear()
        backgroundJobLastSuccessSeconds.clear()
        backgroundJobLastSuccessMeters.clear()
    }

    private fun bindMeter(binder: MeterBinder) {
        binder.bindTo(registry)
    }

    private fun counter(name: String, description: String, tags: Iterable<Tag>): Counter =
        Counter.builder(name)
            .description(description)
            .tags(tags)
            .register(registry)

    private fun recordWorkerMessageOutcome(workerName: String, workerId: Int, status: String) {
        counter(
            WORKER_MESSAGES_PROCESSED,
            "Worker messages processed by worker and result status.",
            tags(
                "worker" to workerName.normalizedLabelValue(),
                "worker_id" to workerId.toString(),
                "status" to status
            )
        ).increment()
    }

    private fun workerLastSuccessGauge(workerName: String, workerId: Int): AtomicLong {
        val normalizedWorkerName = workerName.normalizedLabelValue()
        val key = "$normalizedWorkerName|$workerId"
        val value = workerLastSuccessSeconds.computeIfAbsent(key) { AtomicLong(0L) }
        workerLastSuccessMeters.computeIfAbsent(key) {
            Gauge.builder(WORKER_LAST_SUCCESS_TIMESTAMP_SECONDS, value) { timestamp -> timestamp.get().toDouble() }
                .description("Unix timestamp of the last successfully processed worker message.")
                .tags(tags("worker" to normalizedWorkerName, "worker_id" to workerId.toString()))
                .register(registry)
            Unit
        }
        return value
    }

    private fun dependencyHealthGauge(dependency: String): AtomicLong {
        val value = dependencyHealthValues.computeIfAbsent(dependency) { AtomicLong(UNHEALTHY_VALUE) }
        registerDependencyMeters(dependency)
        return value
    }

    private fun dependencyLastSuccessGauge(dependency: String): AtomicLong {
        val value = dependencyLastSuccessSeconds.computeIfAbsent(dependency) { AtomicLong(0L) }
        registerDependencyMeters(dependency)
        return value
    }

    private fun registerDependencyMeters(dependency: String) {
        dependencyHealthMeters.computeIfAbsent(dependency) {
            val healthValue = dependencyHealthValues.computeIfAbsent(dependency) { AtomicLong(UNHEALTHY_VALUE) }
            val lastSuccess = dependencyLastSuccessSeconds.computeIfAbsent(dependency) { AtomicLong(0L) }
            Gauge.builder(DEPENDENCY_HEALTH, healthValue) { value -> value.get().toDouble() }
                .description("Dependency health from the latest readiness check, 1 for healthy and 0 for unhealthy.")
                .tags(tags("dependency" to dependency))
                .register(registry)
            Gauge.builder(DEPENDENCY_LAST_SUCCESS_TIMESTAMP_SECONDS, lastSuccess) { value -> value.get().toDouble() }
                .description("Unix timestamp of the latest successful dependency health check.")
                .tags(tags("dependency" to dependency))
                .register(registry)
            Unit
        }
    }

    private fun backgroundJobLastSuccessGauge(jobName: String): AtomicLong {
        val value = backgroundJobLastSuccessSeconds.computeIfAbsent(jobName) { AtomicLong(0L) }
        backgroundJobLastSuccessMeters.computeIfAbsent(jobName) {
            Gauge.builder(BACKGROUND_JOB_LAST_SUCCESS_TIMESTAMP_SECONDS, value) { timestamp ->
                timestamp.get().toDouble()
            }
                .description("Unix timestamp of the latest successful background job run.")
                .tags(tags("job" to jobName))
                .register(registry)
            Unit
        }
        return value
    }

    @Suppress("MagicNumber")
    private fun clickHouseDurationTimer(tags: Iterable<Tag>): Timer =
        Timer.builder(CLICKHOUSE_REQUEST_DURATION)
            .description("ClickHouse HTTP request duration by operation and result status.")
            .serviceLevelObjectives(
                Duration.ofMillis(5),
                Duration.ofMillis(10),
                Duration.ofMillis(25),
                Duration.ofMillis(50),
                Duration.ofMillis(100),
                Duration.ofMillis(250),
                Duration.ofMillis(500),
                Duration.ofSeconds(1),
                Duration.ofMillis(2500),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                Duration.ofSeconds(60)
            )
            .tags(tags)
            .register(registry)

    @Suppress("MagicNumber")
    private fun dependencyHealthCheckTimer(tags: Iterable<Tag>): Timer =
        Timer.builder(DEPENDENCY_HEALTH_CHECK_DURATION)
            .description("Dependency health check duration by dependency and result status.")
            .serviceLevelObjectives(
                Duration.ofMillis(5),
                Duration.ofMillis(10),
                Duration.ofMillis(25),
                Duration.ofMillis(50),
                Duration.ofMillis(100),
                Duration.ofMillis(250),
                Duration.ofMillis(500),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5)
            )
            .tags(tags)
            .register(registry)

    @Suppress("MagicNumber")
    private fun backgroundJobDurationTimer(tags: Iterable<Tag>): Timer =
        Timer.builder(BACKGROUND_JOB_DURATION)
            .description("Background job run duration by job and result status.")
            .serviceLevelObjectives(
                Duration.ofMillis(10),
                Duration.ofMillis(50),
                Duration.ofMillis(100),
                Duration.ofMillis(250),
                Duration.ofMillis(500),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                Duration.ofSeconds(60)
            )
            .tags(tags)
            .register(registry)

    private fun readQueueDepth(queueKey: String): Double {
        if (!RedisConfig.isConnected()) return Double.NaN
        return runCatching { RedisConfig.sync().llen(queueKey).toDouble() }
            .getOrElse { Double.NaN }
    }

    private fun tags(vararg pairs: Pair<String, String>): Iterable<Tag> =
        pairs.map { (key, value) -> Tag.of(key.micrometerTagName(), value.normalizedLabelValue()) }

    private fun String.micrometerTagName(): String =
        replace(Regex("[^a-zA-Z0-9_]"), "_")
            .let { normalized ->
                if (normalized.firstOrNull()?.isDigit() == true) "_$normalized" else normalized
            }

    private fun String.normalizedLabelValue(): String = trim().ifBlank { "unknown" }

    private fun Throwable.metricExceptionName(): String =
        this::class.simpleName ?: javaClass.simpleName.ifBlank { "Throwable" }

    private fun Throwable.isTimeoutLike(): Boolean =
        this is HttpRequestTimeoutException ||
            this is java.net.SocketTimeoutException ||
            message?.contains("timeout", ignoreCase = true) == true ||
            cause?.isTimeoutLike() == true

    private fun elapsedSecondsSince(startedAt: Long): Double =
        (System.nanoTime() - startedAt).toDouble() / NANOS_PER_SECOND

    private fun Double.toNanos(): Long {
        val sanitized = if (isFinite()) coerceAtLeast(0.0) else 0.0
        return (sanitized * NANOS_PER_SECOND).toLong()
    }

    private fun currentEpochSeconds(): Long = System.currentTimeMillis() / MILLIS_PER_SECOND

    private fun newRegistry(): PrometheusMeterRegistry =
        PrometheusMeterRegistry(PrometheusConfig.DEFAULT).also { registry ->
            registry.config().commonTags("application", APPLICATION_TAG_VALUE)
        }

    private const val APPLICATION_TAG_VALUE = "moneat-backend"
    private const val WORKER_MESSAGES_PROCESSED = "moneat_worker_messages_processed"
    private const val WORKER_LAST_SUCCESS_TIMESTAMP_SECONDS = "moneat_worker_last_success_timestamp_seconds"
    private const val WORKER_PROCESSING_FAILURES = "moneat_worker_processing_failures"
    private const val WORKER_BRPOP_FAILURES = "moneat_worker_brpop_failures"
    private const val WORKER_DLQ_PUSHES = "moneat_worker_dlq_pushes"
    private const val WORKER_DLQ_DEPTH = "moneat_worker_dlq_depth"
    private const val WORKER_QUEUE_DEPTH = "moneat_worker_queue_depth"
    private const val CLICKHOUSE_REQUESTS = "moneat_clickhouse_requests"
    private const val CLICKHOUSE_REQUEST_TIMEOUTS = "moneat_clickhouse_request_timeouts"
    private const val CLICKHOUSE_REQUEST_DURATION = "moneat_clickhouse_request_duration"
    private const val DEPENDENCY_HEALTH = "moneat_dependency_health"
    private const val DEPENDENCY_LAST_SUCCESS_TIMESTAMP_SECONDS = "moneat_dependency_last_success_timestamp_seconds"
    private const val DEPENDENCY_HEALTH_CHECKS = "moneat_dependency_health_checks"
    private const val DEPENDENCY_HEALTH_CHECK_DURATION = "moneat_dependency_health_check_duration"
    private const val BACKGROUND_JOB_RUNS = "moneat_background_job_runs"
    private const val BACKGROUND_JOB_DURATION = "moneat_background_job_duration"
    private const val BACKGROUND_JOB_LAST_SUCCESS_TIMESTAMP_SECONDS =
        "moneat_background_job_last_success_timestamp_seconds"
    private const val DATASOURCE_QUERY_FAILURES = "moneat_datasource_query_failures"
    private const val NANOS_PER_SECOND = 1_000_000_000
    private const val MILLIS_PER_SECOND = 1_000
    private const val HEALTHY_VALUE = 1L
    private const val UNHEALTHY_VALUE = 0L
}
