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
import com.moneat.ingestion.queue.IngestionPipeline
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.lettuce.core.Limit
import io.lettuce.core.Range
import io.lettuce.core.api.sync.RedisCommands
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
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
    private val registeredStreamMeters = ConcurrentHashMap<String, Unit>()
    private val ingestionCapacityValues = ConcurrentHashMap<String, AtomicLong>()
    private val registeredIngestionCapacityMeters = ConcurrentHashMap<String, Unit>()
    private val registeredIngestionQueueMeters = ConcurrentHashMap<String, Unit>()
    private val workerLastSuccessSeconds = ConcurrentHashMap<String, AtomicLong>()
    private val workerLastSuccessMeters = ConcurrentHashMap<String, Unit>()
    private val dependencyHealthValues = ConcurrentHashMap<String, AtomicLong>()
    private val dependencyLastSuccessSeconds = ConcurrentHashMap<String, AtomicLong>()
    private val dependencyHealthMeters = ConcurrentHashMap<String, Unit>()
    private val backgroundJobLastSuccessSeconds = ConcurrentHashMap<String, AtomicLong>()
    private val backgroundJobLastSuccessMeters = ConcurrentHashMap<String, Unit>()
    private val systemMetricsBound = AtomicBoolean(false)
    private val queueModeMetricsBound = AtomicBoolean(false)
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

        bindIngestionQueueModeMetrics()
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

    fun recordWorkerQueueLoopFailure(workerName: String, workerId: Int, cause: Throwable) {
        counter(
            WORKER_QUEUE_LOOP_FAILURES,
            "Redis queue loop failures in background ingestion workers.",
            tags(
                "worker" to workerName.normalizedLabelValue(),
                "worker_id" to workerId.toString(),
                "exception" to cause.metricExceptionName()
            )
        ).increment()
    }

    fun registerWorkerQueues(
        workerName: String,
        queueKey: String,
        dlqKey: String,
        consumerGroup: String? = null,
    ) {
        registerQueue(workerName, queueKey, "primary", consumerGroup)
        registerQueue(workerName, dlqKey, "dlq")
        registerDlq(workerName, dlqKey)
    }

    fun registerWorkerStream(
        workerName: String,
        streamKey: String,
        streamType: String,
        consumerGroup: String? = null,
    ) {
        val normalizedWorkerName = workerName.normalizedLabelValue()
        val normalizedStreamType = streamType.normalizedLabelValue()
        val baseKey = "$normalizedWorkerName|$streamKey|${consumerGroup.orEmpty()}|$normalizedStreamType"
        registeredStreamMeters.computeIfAbsent(baseKey) {
            Gauge.builder(WORKER_STREAM_OLDEST_MESSAGE_AGE_SECONDS, streamKey) { key ->
                readStreamOldestMessageAgeSeconds(key, consumerGroup)
            }
                .description("Age in seconds of the oldest pending or unconsumed Redis stream message.")
                .tags(
                    tags(
                        "worker" to normalizedWorkerName,
                        "stream_key" to streamKey,
                        "stream_type" to normalizedStreamType,
                        "consumer_group" to consumerGroup.orEmpty()
                    )
                )
                .register(registry)
            if (consumerGroup != null) {
                Gauge.builder(WORKER_STREAM_PENDING_MESSAGES, streamKey) { key ->
                    readStreamPendingMessages(key, consumerGroup)
                }
                    .description("Pending Redis stream messages in the configured ingestion consumer group.")
                    .tags(
                        tags(
                            "worker" to normalizedWorkerName,
                            "stream_key" to streamKey,
                            "consumer_group" to consumerGroup
                        )
                    )
                    .register(registry)
            }
            Unit
        }
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

    fun registerQueue(
        workerName: String,
        queueKey: String,
        queueType: String,
        consumerGroup: String? = null,
    ) {
        val normalizedWorkerName = workerName.normalizedLabelValue()
        val normalizedQueueType = queueType.normalizedLabelValue()
        val meterKey = "$normalizedWorkerName|$queueKey|$normalizedQueueType"
        registeredQueueMeters.computeIfAbsent(meterKey) {
            Gauge.builder(WORKER_QUEUE_DEPTH, queueKey) { key -> readQueueDepth(key, consumerGroup) }
                .description("Current Redis queue depth, using pending plus lag for stream consumer groups.")
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

    fun recordIngestionAdmission(pipeline: IngestionPipeline, outcome: String) {
        counter(
            INGESTION_ADMISSION,
            "Ingestion queue admission attempts by pipeline and outcome.",
            tags(
                "pipeline" to pipeline.id,
                "outcome" to outcome,
            ),
        ).increment()
    }

    fun recordIngestionDlqPush(pipeline: IngestionPipeline, status: String) {
        counter(
            INGESTION_DLQ_PUSHES,
            "Ingestion dead-letter queue growth by pipeline and result.",
            tags(
                "pipeline" to pipeline.id,
                "status" to status,
            ),
        ).increment()
    }

    fun registerIngestionQueueCapacity(pipeline: IngestionPipeline, capacity: Long) {
        val pipelineId = pipeline.id
        val capacityValue = ingestionCapacityValues.computeIfAbsent(pipelineId) { AtomicLong(capacity) }
        capacityValue.set(capacity)
        registeredIngestionCapacityMeters.computeIfAbsent(pipelineId) {
            Gauge.builder(INGESTION_QUEUE_CAPACITY, capacityValue) { value -> value.get().toDouble() }
                .description("Configured maximum entries accepted by an ingestion queue.")
                .tags(tags("pipeline" to pipelineId))
                .register(registry)
            Unit
        }
    }

    fun registerIngestionQueue(
        pipeline: IngestionPipeline,
        streamKey: String,
        dlqStreamKey: String,
        consumerGroup: String,
        capacity: Long,
    ) {
        registerIngestionQueueCapacity(pipeline, capacity)
        registeredIngestionQueueMeters.computeIfAbsent(pipeline.id) {
            Gauge.builder(INGESTION_QUEUE_DEPTH, streamKey) { key ->
                readStreamBacklogMessages(key, consumerGroup)
            }
                .description("Current pending and unconsumed entries in an ingestion queue.")
                .tags(tags("pipeline" to pipeline.id))
                .register(registry)
            Gauge.builder(INGESTION_QUEUE_OLDEST_MESSAGE_AGE_SECONDS, streamKey) { key ->
                readStreamOldestMessageAgeSeconds(key, consumerGroup)
            }
                .description("Age in seconds of the oldest pending or unconsumed ingestion message.")
                .tags(tags("pipeline" to pipeline.id))
                .register(registry)
            Gauge.builder(INGESTION_DLQ_DEPTH, dlqStreamKey) { key -> readQueueDepth(key) }
                .description("Current retained entries in an ingestion dead-letter queue.")
                .tags(tags("pipeline" to pipeline.id))
                .register(registry)
            Unit
        }
    }

    fun recordDatadogMetricPayloadQueued(metricRows: Int) {
        counter(
            DD_METRIC_PAYLOADS_QUEUED,
            "Datadog metric payloads queued for background insertion.",
            emptyList()
        ).increment()
        counter(
            DD_METRIC_POINTS_QUEUED,
            "Datadog metric points queued for background insertion.",
            emptyList()
        ).increment(metricRows.coerceAtLeast(0).toDouble())
    }

    fun recordDatadogMetricInsert(
        mode: String,
        status: String,
        payloadCount: Int,
        rowCount: Int,
        durationSeconds: Double,
        cause: Throwable? = null,
    ) {
        val normalizedMode = mode.normalizedLabelValue()
        val normalizedStatus = status.normalizedLabelValue()
        val exception = cause?.metricExceptionName() ?: "none"
        val insertTags = tags(
            "mode" to normalizedMode,
            "status" to normalizedStatus,
            "exception" to exception
        )

        counter(
            DD_METRIC_INSERT_CHUNKS,
            "Datadog metric ClickHouse insert chunks by mode and result.",
            insertTags
        ).increment()
        datadogMetricInsertTimer(insertTags).record(durationSeconds.toNanos(), TimeUnit.NANOSECONDS)
        datadogMetricInsertSummary(DD_METRIC_INSERT_PAYLOADS, "payloads", insertTags)
            .record(payloadCount.coerceAtLeast(0).toDouble())
        datadogMetricInsertSummary(DD_METRIC_INSERT_ROWS, "rows", insertTags)
            .record(rowCount.coerceAtLeast(0).toDouble())
    }

    fun recordDatadogMetricInsertFallback(
        payloadCount: Int,
        rowCount: Int,
        cause: Throwable?,
    ) {
        val fallbackTags = tags("exception" to (cause?.metricExceptionName() ?: "none"))
        counter(
            DD_METRIC_INSERT_FALLBACKS,
            "Datadog metric chunks that fell back from combined insert to per-payload insert.",
            fallbackTags
        ).increment()
        datadogMetricInsertSummary(DD_METRIC_FALLBACK_PAYLOADS, "payloads", fallbackTags)
            .record(payloadCount.coerceAtLeast(0).toDouble())
        datadogMetricInsertSummary(DD_METRIC_FALLBACK_ROWS, "rows", fallbackTags)
            .record(rowCount.coerceAtLeast(0).toDouble())
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

    fun recordClickHouseQueryError(operation: String, code: String) {
        counter(
            CLICKHOUSE_QUERY_ERRORS,
            "ClickHouse query responses that returned a ClickHouse error body.",
            tags(
                "operation" to operation.normalizedLabelValue(),
                "code" to code.normalizedLabelValue()
            )
        ).increment()
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

    fun recordWorkflowExecutionStarted(trigger: String) {
        workflowExecutionCounter(trigger, "started").increment()
    }

    fun recordWorkflowExecutionCompleted(
        trigger: String,
        durationSeconds: Double
    ) {
        workflowExecutionCounter(trigger, "completed").increment()
        workflowExecutionDurationTimer(trigger).record(durationSeconds.toNanos(), TimeUnit.NANOSECONDS)
    }

    fun recordWorkflowExecutionFailed(trigger: String) {
        workflowExecutionCounter(trigger, "failed").increment()
    }

    fun recordWorkflowRateLimited(trigger: String) {
        counter(
            WORKFLOW_RATE_LIMITED,
            "Workflow runs skipped because the per-workflow rate limit was reached.",
            tags("trigger" to trigger.normalizedLabelValue())
        ).increment()
    }

    fun recordNativeIncidentRolloutDecision(surface: String, outcome: String) {
        counter(
            NATIVE_INCIDENT_ROLLOUT_DECISIONS,
            "Native incident operations gated by rollout state, grouped by surface and outcome.",
            tags(
                "surface" to surface,
                "outcome" to outcome,
            ),
        ).increment()
    }

    fun scrape(): String = registry.scrape()

    fun resetForTest() {
        closeableBinders.forEach { binder ->
            runCatching { binder.close() }
        }
        closeableBinders.clear()
        systemMetricsBound.set(false)
        registryRef.getAndSet(newRegistry()).close()
        registeredDlqs.clear()
        registeredDlqMeters.clear()
        registeredQueueMeters.clear()
        registeredStreamMeters.clear()
        ingestionCapacityValues.clear()
        registeredIngestionCapacityMeters.clear()
        registeredIngestionQueueMeters.clear()
        workerLastSuccessSeconds.clear()
        workerLastSuccessMeters.clear()
        dependencyHealthValues.clear()
        dependencyLastSuccessSeconds.clear()
        dependencyHealthMeters.clear()
        backgroundJobLastSuccessSeconds.clear()
        backgroundJobLastSuccessMeters.clear()
        queueModeMetricsBound.set(false)
    }

    private fun bindMeter(binder: MeterBinder) {
        binder.bindTo(registry)
    }

    private fun counter(name: String, description: String, tags: Iterable<Tag>): Counter =
        Counter.builder(name)
            .description(description)
            .tags(tags)
            .register(registry)

    @Suppress("MagicNumber")
    private fun datadogMetricInsertTimer(tags: Iterable<Tag>): Timer =
        Timer.builder(DD_METRIC_INSERT_DURATION)
            .description("Datadog metric ClickHouse insert duration by insert mode and result.")
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
                Duration.ofSeconds(30)
            )
            .tags(tags)
            .register(registry)

    private fun datadogMetricInsertSummary(name: String, unit: String, tags: Iterable<Tag>): DistributionSummary =
        DistributionSummary.builder(name)
            .description("Datadog metric insert batch $unit by insert mode and result.")
            .baseUnit(unit)
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

    private fun workflowExecutionCounter(
        trigger: String,
        status: String
    ): Counter =
        counter(
            WORKFLOW_EXECUTIONS,
            "Workflow executions by trigger and lifecycle status.",
            tags(
                "trigger" to trigger.normalizedLabelValue(),
                "status" to status
            )
        )

    @Suppress("MagicNumber")
    private fun workflowExecutionDurationTimer(trigger: String): Timer =
        Timer.builder(WORKFLOW_EXECUTION_DURATION)
            .description("Workflow execution duration from run creation to completion, by trigger.")
            .serviceLevelObjectives(
                Duration.ofMillis(50),
                Duration.ofMillis(250),
                Duration.ofMillis(500),
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                Duration.ofMinutes(5),
                Duration.ofMinutes(15)
            )
            .tags(tags("trigger" to trigger.normalizedLabelValue()))
            .register(registry)

    private fun bindIngestionQueueModeMetrics() {
        if (!queueModeMetricsBound.compareAndSet(false, true)) return
        Gauge.builder(INGESTION_QUEUE_MODE, Unit) { 1.0 }
            .description("Current ingestion queue mode for this process.")
            .tags(tags("backend" to "redis_streams"))
            .register(registry)
    }

    private fun readQueueDepth(queueKey: String, consumerGroup: String? = null): Double {
        if (!RedisConfig.isMonitoringConnected()) return Double.NaN
        if (consumerGroup != null) return readStreamBacklogMessages(queueKey, consumerGroup)
        return runCatching { RedisConfig.withMonitoringSync { it.xlen(queueKey).toDouble() } }
            .recoverCatching { RedisConfig.withMonitoringSync { it.llen(queueKey).toDouble() } }
            .getOrElse { Double.NaN }
    }

    private fun readStreamBacklogMessages(streamKey: String, consumerGroup: String): Double =
        runCatching {
            RedisConfig.withMonitoringSync { redis ->
                val pendingMessages = redis.xpending(streamKey, consumerGroup).count
                val lagMessages = redis.xinfoGroups(streamKey)
                    .asSequence()
                    .mapNotNull { parseStreamGroupInfo(it) }
                    .firstOrNull { group -> group.name == consumerGroup }
                    ?.lag
                    ?: 0L
                (pendingMessages + lagMessages).toDouble()
            }
        }.getOrElse { error ->
            if (error.isMissingRedisStreamError()) 0.0 else Double.NaN
        }

    private fun readStreamPendingMessages(streamKey: String, consumerGroup: String): Double {
        if (!RedisConfig.isMonitoringConnected()) return Double.NaN
        return runCatching {
            RedisConfig.withMonitoringSync { it.xpending(streamKey, consumerGroup).count.toDouble() }
        }
            .getOrElse { Double.NaN }
    }

    private fun readStreamOldestMessageAgeSeconds(streamKey: String, consumerGroup: String?): Double {
        if (!RedisConfig.isMonitoringConnected()) return Double.NaN
        return runCatching {
            RedisConfig.withMonitoringSync { redis ->
                readStreamOldestMessageAgeSeconds(redis, streamKey, consumerGroup)
            }
        }.getOrElse { error ->
            if (error.isMissingRedisStreamError()) 0.0 else Double.NaN
        }
    }

    private fun readStreamOldestMessageAgeSeconds(
        redis: RedisCommands<String, String>,
        streamKey: String,
        consumerGroup: String?,
    ): Double {
        if (consumerGroup == null) return readOldestStreamEntryAgeSeconds(redis, streamKey)

        val pending = redis.xpending(streamKey, consumerGroup)
        if (pending.count > 0) return streamIdAgeSeconds(pending.messageIds.lower.value)

        val group = redis.xinfoGroups(streamKey)
            .asSequence()
            .mapNotNull(::parseStreamGroupInfo)
            .firstOrNull { info -> info.name == consumerGroup }
        if (group == null) return 0.0
        val lag = group.lag ?: return readOldestStreamEntryAgeSeconds(redis, streamKey)
        if (lag <= 0 || group.lastDeliveredId == null) return 0.0

        val firstUnconsumed = redis
            .xrange(streamKey, Range.create(group.lastDeliveredId, "+"), Limit.from(2))
            .firstOrNull { message -> message.id != group.lastDeliveredId }
            ?: return 0.0
        return streamIdAgeSeconds(firstUnconsumed.id)
    }

    private fun readOldestStreamEntryAgeSeconds(
        redis: RedisCommands<String, String>,
        streamKey: String
    ): Double {
        val first = redis
            .xrange(streamKey, Range.create("-", "+"), Limit.from(1))
            .firstOrNull()
            ?: return 0.0
        return streamIdAgeSeconds(first.id)
    }

    private fun streamIdAgeSeconds(id: String): Double {
        val createdAtMs = id.substringBefore('-').toLongOrNull() ?: return Double.NaN
        val ageMs = System.currentTimeMillis() - createdAtMs
        return ageMs.coerceAtLeast(0L).toDouble() / MILLIS_PER_SECOND
    }

    private fun parseStreamGroupInfo(group: Any?): StreamGroupInfo? =
        when (group) {
            is List<*> -> parseStreamGroupListInfo(group)
            is Map<*, *> -> parseStreamGroupMapInfo(group)
            else -> null
        }

    private fun parseStreamGroupListInfo(group: List<*>): StreamGroupInfo? {
        var name: String? = null
        var lag: Long? = null
        var lastDeliveredId: String? = null
        for (index in 0 until group.lastIndex step XINFO_GROUP_FIELD_STEP) {
            when (group[index]?.toString()) {
                "name" -> name = group[index + XINFO_GROUP_VALUE_OFFSET]?.toString()
                "lag" -> lag = group[index + XINFO_GROUP_VALUE_OFFSET].toLongOrNullValue()
                "last-delivered-id" -> lastDeliveredId = group[index + XINFO_GROUP_VALUE_OFFSET]?.toString()
            }
        }
        return name?.let { StreamGroupInfo(it, lag, lastDeliveredId) }
    }

    private fun parseStreamGroupMapInfo(group: Map<*, *>): StreamGroupInfo? {
        val name = group["name"]?.toString() ?: return null
        return StreamGroupInfo(name, group["lag"].toLongOrNullValue(), group["last-delivered-id"]?.toString())
    }

    private fun Any?.toLongOrNullValue(): Long? =
        when (this) {
            is Number -> toLong()
            is String -> toLongOrNull()
            else -> toString().toLongOrNull()
        }

    private fun tags(vararg pairs: Pair<String, String>): Iterable<Tag> =
        pairs.map { (key, value) -> Tag.of(key.micrometerTagName(), value.normalizedLabelValue()) }

    private fun String.micrometerTagName(): String =
        replace(Regex("[^a-zA-Z0-9_]"), "_")
            .let { normalized ->
                if (normalized.firstOrNull()?.isDigit() == true) "_$normalized" else normalized
            }

    private fun String.normalizedLabelValue(): String = trim().ifBlank { "unknown" }

    private fun Throwable.isMissingRedisStreamError(): Boolean =
        message?.let {
            it.contains("NOGROUP", ignoreCase = true) ||
                it.contains("no such key", ignoreCase = true)
        } == true || cause?.isMissingRedisStreamError() == true

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

    private data class StreamGroupInfo(
        val name: String,
        val lag: Long?,
        val lastDeliveredId: String? = null,
    )

    private fun newRegistry(): PrometheusMeterRegistry =
        PrometheusMeterRegistry(PrometheusConfig.DEFAULT).also { registry ->
            registry.config().commonTags("application", APPLICATION_TAG_VALUE)
        }

    private const val APPLICATION_TAG_VALUE = "moneat-backend"
    private const val WORKER_MESSAGES_PROCESSED = "moneat_worker_messages_processed"
    private const val WORKER_LAST_SUCCESS_TIMESTAMP_SECONDS = "moneat_worker_last_success_timestamp_seconds"
    private const val WORKER_PROCESSING_FAILURES = "moneat_worker_processing_failures"
    private const val WORKER_QUEUE_LOOP_FAILURES = "moneat_worker_queue_loop_failures"
    private const val WORKER_DLQ_PUSHES = "moneat_worker_dlq_pushes"
    private const val WORKER_DLQ_DEPTH = "moneat_worker_dlq_depth"
    private const val WORKER_QUEUE_DEPTH = "moneat_worker_queue_depth"
    private const val WORKER_STREAM_PENDING_MESSAGES = "moneat_worker_stream_pending_messages"
    private const val WORKER_STREAM_OLDEST_MESSAGE_AGE_SECONDS =
        "moneat_worker_stream_oldest_message_age_seconds"
    private const val INGESTION_QUEUE_MODE = "moneat_ingestion_queue_mode"
    private const val INGESTION_ADMISSION = "moneat_ingestion_admission"
    private const val INGESTION_QUEUE_CAPACITY = "moneat_ingestion_queue_capacity_entries"
    private const val INGESTION_QUEUE_DEPTH = "moneat_ingestion_queue_depth"
    private const val INGESTION_QUEUE_OLDEST_MESSAGE_AGE_SECONDS =
        "moneat_ingestion_queue_oldest_message_age_seconds"
    private const val INGESTION_DLQ_DEPTH = "moneat_ingestion_dlq_depth"
    private const val INGESTION_DLQ_PUSHES = "moneat_ingestion_dlq_pushes"
    private const val DD_METRIC_PAYLOADS_QUEUED = "moneat_datadog_metric_payloads_queued"
    private const val DD_METRIC_POINTS_QUEUED = "moneat_datadog_metric_points_queued"
    private const val DD_METRIC_INSERT_CHUNKS = "moneat_datadog_metric_insert_chunks"
    private const val DD_METRIC_INSERT_DURATION = "moneat_datadog_metric_insert_duration"
    private const val DD_METRIC_INSERT_PAYLOADS = "moneat_datadog_metric_insert_payloads"
    private const val DD_METRIC_INSERT_ROWS = "moneat_datadog_metric_insert_rows"
    private const val DD_METRIC_INSERT_FALLBACKS = "moneat_datadog_metric_insert_fallbacks"
    private const val DD_METRIC_FALLBACK_PAYLOADS = "moneat_datadog_metric_fallback_payloads"
    private const val DD_METRIC_FALLBACK_ROWS = "moneat_datadog_metric_fallback_rows"
    private const val CLICKHOUSE_REQUESTS = "moneat_clickhouse_requests"
    private const val CLICKHOUSE_REQUEST_TIMEOUTS = "moneat_clickhouse_request_timeouts"
    private const val CLICKHOUSE_REQUEST_DURATION = "moneat_clickhouse_request_duration"
    private const val CLICKHOUSE_QUERY_ERRORS = "moneat_clickhouse_query_errors"
    private const val DEPENDENCY_HEALTH = "moneat_dependency_health"
    private const val DEPENDENCY_LAST_SUCCESS_TIMESTAMP_SECONDS = "moneat_dependency_last_success_timestamp_seconds"
    private const val DEPENDENCY_HEALTH_CHECKS = "moneat_dependency_health_checks"
    private const val DEPENDENCY_HEALTH_CHECK_DURATION = "moneat_dependency_health_check_duration"
    private const val BACKGROUND_JOB_RUNS = "moneat_background_job_runs"
    private const val BACKGROUND_JOB_DURATION = "moneat_background_job_duration"
    private const val BACKGROUND_JOB_LAST_SUCCESS_TIMESTAMP_SECONDS =
        "moneat_background_job_last_success_timestamp_seconds"
    private const val DATASOURCE_QUERY_FAILURES = "moneat_datasource_query_failures"
    private const val WORKFLOW_EXECUTIONS = "moneat_workflow_executions"
    private const val WORKFLOW_EXECUTION_DURATION = "moneat_workflow_execution_duration"
    private const val WORKFLOW_RATE_LIMITED = "moneat_workflow_rate_limited"
    private const val NATIVE_INCIDENT_ROLLOUT_DECISIONS = "moneat_native_incident_rollout_decisions"
    private const val NANOS_PER_SECOND = 1_000_000_000
    private const val MILLIS_PER_SECOND = 1_000
    private const val HEALTHY_VALUE = 1L
    private const val UNHEALTHY_VALUE = 0L
    private const val XINFO_GROUP_FIELD_STEP = 2
    private const val XINFO_GROUP_VALUE_OFFSET = 1
}
