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

package com.moneat.apm.models

import kotlinx.serialization.Serializable

@Serializable
data class ApmEnvPill(
    val label: String,
    val chart: Int,
)

@Serializable
data class ApmCatalogSummary(
    val total: Int,
    val alerting: Int,
    val degraded: Int,
)

@Serializable
data class ApmServiceCatalogRow(
    val name: String,
    val type: String,
    val status: String,
    val env: List<ApmEnvPill>,
    val rps: Double,
    val spark: List<Int>,
    val p95Ms: Long,
    val p99Ms: Long,
    val errorRateLabel: String,
    val errorBarPct: Int,
    val errorLevel: String,
    val apdex: String,
    val apdexTone: String,
    val lastDeploy: String,
    val team: String,
    val language: String? = null,
    val sources: List<String> = emptyList(),
)

@Serializable
data class ApmServiceCatalogResponse(
    val services: List<ApmServiceCatalogRow>,
    val summary: ApmCatalogSummary,
)

@Serializable
data class ApmStatDeltaSpec(
    val value: String,
    val direction: String,
    val tone: String? = null,
)

@Serializable
data class ApmKpiSpec(
    val label: String,
    val value: String,
    val valueTone: String? = null,
    val delta: ApmStatDeltaSpec? = null,
)

@Serializable
data class ApmLatencyPoint(
    val t: String,
    val p50: Long,
    val p90: Long,
    val p95: Long,
    val p99: Long,
)

@Serializable
data class ApmThroughputPoint(
    val t: String,
    val rps: Double,
    val errors: Double? = null,
)

@Serializable
data class ApmGaugeRow(
    val label: String,
    val valueText: String,
    val pct: Int,
    val level: String,
)

@Serializable
data class ApmErrorBar(
    val h: Int,
    val level: String,
)

@Serializable
data class ApmResourceRow(
    val slug: String,
    val method: String,
    val name: String,
    val rps: Double,
    val p50Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
    val errorRateLabel: String,
    val errorBarPct: Int,
    val errorLevel: String,
    val timePct: Int,
    val status: String,
)

@Serializable
data class ApmDependencyRow(
    val name: String,
    val type: String,
    val tone: String,
    val rps: String,
    val p95: String,
    val err: String,
    val errWarn: Boolean = false,
)

@Serializable
data class ApmDeploymentRow(
    val version: String,
    val `when`: String,
    val initials: String,
    val rps: String,
    val errorRate: String,
    val p95: String,
    val status: String,
    val current: Boolean = false,
    val trendBad: Boolean = false,
)

@Serializable
data class ApmPodRow(
    val pod: String,
    val node: String,
    val cpu: Int,
    val mem: String,
    val restarts: Int,
    val tone: String,
    val state: String,
)

@Serializable
data class ApmErrorRow(
    val severity: String,
    val title: String,
    val sub: String,
    val chips: List<String>,
    val events: String,
    val users: String? = null,
    val unhandled: Boolean = false,
)

@Serializable
data class ApmTraceRow(
    val time: String,
    val traceId: String,
    val resource: String? = null,
    val method: String? = null,
    val httpStatus: Int,
    val durationMs: Long,
    val spans: Int? = null,
    val bucket: String? = null,
)

@Serializable
data class ApmServiceDetail(
    val name: String,
    val type: String,
    val status: String,
    val runtime: String,
    val team: String,
    val version: String,
    val deployedAgo: String,
    val sources: List<String>,
    val kpis: List<ApmKpiSpec>,
    val latency: List<ApmLatencyPoint>,
    val latencyThresholdMs: Long,
    val latencyThresholdLabel: String,
    val deployAt: String,
    val throughput: List<ApmThroughputPoint>,
    val errorBars: List<ApmErrorBar>,
    val p95ByResource: List<ApmGaugeRow>,
    val resources: List<ApmResourceRow>,
    val upstream: List<ApmDependencyRow>,
    val downstream: List<ApmDependencyRow>,
    val depInsight: String,
    val deployments: List<ApmDeploymentRow>,
    val podMemory: List<ApmGaugeRow>,
    val pods: List<ApmPodRow>,
    val errors: List<ApmErrorRow>,
    val traces: List<ApmTraceRow>,
)

@Serializable
data class ApmDistBar(
    val h: Int,
    val band: String,
)

@Serializable
data class ApmDistMarker(
    val label: String,
    val left: Int,
    val p99: Boolean = false,
)

@Serializable
data class ApmWaterfallRow(
    val op: String,
    val desc: String,
    val left: Int,
    val width: Int,
    val label: String,
    val tone: String,
    val indent: Int? = null,
    val selected: Boolean = false,
)

@Serializable
data class ApmResourceExemplar(
    val traceId: String,
    val httpStatus: Int,
    val durationLabel: String,
    val rows: List<ApmWaterfallRow>,
)

@Serializable
data class ApmResourceDetail(
    val serviceName: String,
    val method: String,
    val path: String,
    val status: String,
    val kind: String,
    val topDependency: String,
    val kpis: List<ApmKpiSpec>,
    val latency: List<ApmLatencyPoint>,
    val latencyThresholdMs: Long,
    val latencyThresholdLabel: String,
    val deployAt: String,
    val throughput: List<ApmThroughputPoint>,
    val distribution: List<ApmDistBar>,
    val distMarkers: List<ApmDistMarker>,
    val distAxis: List<String>,
    val whereTimeSpent: List<ApmGaugeRow>,
    val whereInsight: String,
    val exemplar: ApmResourceExemplar,
    val slowTraces: List<ApmTraceRow>,
    val errors: List<ApmErrorRow>,
)
