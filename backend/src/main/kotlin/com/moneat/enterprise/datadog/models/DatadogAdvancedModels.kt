// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Phase 7: K8s Orchestrator Models ---

@Serializable
data class DdOrchestratorPayload(
    val resources: List<DdK8sResource> = emptyList(),
    @SerialName("cluster_name") val clusterName: String = "",
    @SerialName("cluster_id") val clusterId: String = "",
    val host: String = "",
    val tags: List<String> = emptyList(),
)

@Serializable
data class DdK8sResource(
    val uid: String = "",
    val type: String = "",
    val namespace: String = "",
    val name: String = "",
    val status: String = "",
    val tags: Map<String, String> = emptyMap(),
    val labels: Map<String, String> = emptyMap(),
    val annotations: Map<String, String> = emptyMap(),
    @SerialName("resource_version") val resourceVersion: String = "",
    @SerialName("creation_timestamp") val creationTimestamp: Long? = null,
)

@Serializable
data class DdManifestPayload(
    val manifests: List<DdK8sManifest> = emptyList(),
    @SerialName("cluster_name") val clusterName: String = "",
    val host: String = "",
    val tags: List<String> = emptyList(),
)

@Serializable
data class DdK8sManifest(
    val uid: String = "",
    val type: String = "",
    val namespace: String = "",
    val name: String = "",
    val content: String = "",
    @SerialName("content_type") val contentType: String = "application/json",
)

// --- Phase 7: Database Monitoring (DBM) Models ---

@Serializable
data class DdDbmQueryPayload(
    @SerialName("db_host") val dbHost: String = "",
    @SerialName("db_system") val dbSystem: String = "",
    @SerialName("db_name") val dbName: String = "",
    @SerialName("db_user") val dbUser: String = "",
    val host: String = "",
    val env: String = "",
    val service: String = "",
    val tags: List<String> = emptyList(),
    val rows: List<DdDbmQueryRow> = emptyList(),
)

@Serializable
data class DdDbmQueryRow(
    @SerialName("query_signature") val querySignature: String = "",
    @SerialName("resource_hash") val resourceHash: String = "",
    val statement: String = "",
    @SerialName("query_truncated") val queryTruncated: Boolean = false,
    @SerialName("duration_ns") val durationNs: Long = 0,
    @SerialName("rows_affected") val rowsAffected: Long = 0,
    @SerialName("error_code") val errorCode: Int = 0,
    @SerialName("error_message") val errorMessage: String = "",
    val timestamp: Long? = null,
)

@Serializable
data class DdDbmMetricsPayload(
    @SerialName("db_host") val dbHost: String = "",
    @SerialName("db_system") val dbSystem: String = "",
    val host: String = "",
    val env: String = "",
    val tags: List<String> = emptyList(),
    val rows: List<DdDbmMetricRow> = emptyList(),
)

@Serializable
data class DdDbmMetricRow(
    @SerialName("db_name") val dbName: String = "",
    @SerialName("query_signature") val querySignature: String = "",
    val timestamp: Long? = null,
    val calls: Long = 0,
    @SerialName("total_time_ns") val totalTimeNs: Long = 0,
    val rows: Long = 0,
    @SerialName("shared_blks_hit") val sharedBlksHit: Long = 0,
    @SerialName("shared_blks_read") val sharedBlksRead: Long = 0,
)

@Serializable
data class DdDbmActivityPayload(
    @SerialName("db_host") val dbHost: String = "",
    @SerialName("db_system") val dbSystem: String = "",
    val host: String = "",
    val env: String = "",
    val tags: List<String> = emptyList(),
    val activity: List<DdDbmActivityRow> = emptyList(),
)

@Serializable
data class DdDbmActivityRow(
    @SerialName("db_name") val dbName: String = "",
    @SerialName("db_user") val dbUser: String = "",
    @SerialName("query_signature") val querySignature: String = "",
    val statement: String = "",
    val state: String = "",
    @SerialName("wait_event_type") val waitEventType: String = "",
    @SerialName("wait_event") val waitEvent: String = "",
    @SerialName("blocking_pids") val blockingPids: List<Long> = emptyList(),
    @SerialName("duration_ns") val durationNs: Long = 0,
    val timestamp: Long? = null,
)

// --- Phase 7: Debugger Models ---

@Serializable
data class DdDebuggerInput(
    val service: String = "",
    val env: String = "",
    val version: String = "",
    @SerialName("debugger_type") val debuggerType: String = "log_probe",
    @SerialName("probe_id") val probeId: String = "",
    @SerialName("probe_location") val probeLocation: String = "",
    val message: String = "",
    val snapshot: String = "",
    val host: String = "",
    val timestamp: Long? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class DdDebuggerDiagnostic(
    val service: String = "",
    val env: String = "",
    @SerialName("runtime_id") val runtimeId: String = "",
    @SerialName("probe_id") val probeId: String = "",
    val status: String = "received",
    @SerialName("error_message") val errorMessage: String = "",
    val host: String = "",
    val timestamp: Long? = null,
    val tags: List<String> = emptyList(),
)

// --- Phase 7: Telemetry Proxy Models ---

@Serializable
data class DdTelemetryPayload(
    @SerialName("api_version") val apiVersion: String = "",
    @SerialName("tracer_time") val tracerTime: Long = 0,
    @SerialName("runtime_id") val runtimeId: String = "",
    @SerialName("seq_id") val seqId: Long = 0,
    val application: DdTelemetryApp? = null,
    val host: DdTelemetryHost? = null,
)

@Serializable
data class DdTelemetryApp(
    @SerialName("service_name") val serviceName: String = "",
    @SerialName("language_name") val languageName: String = "",
    @SerialName("language_version") val languageVersion: String = "",
    @SerialName("tracer_version") val tracerVersion: String = "",
)

@Serializable
data class DdTelemetryHost(
    val hostname: String = "",
    val os: String = "",
)

// --- DBM Metadata & Health Models ---

@Serializable
data class DdDbmMetadataPayload(
    val host: String = "",
    @SerialName("db_system") val dbSystem: String = "",
    @SerialName("schema_json") val schemaJson: String = "",
    @SerialName("explain_plan_hash") val explainPlanHash: String = "",
    @SerialName("explain_plan") val explainPlan: String = "",
    val tags: List<String> = emptyList(),
)

@Serializable
data class DdDbmHealthPayload(
    val host: String = "",
    @SerialName("db_system") val dbSystem: String = "",
    @SerialName("agent_version") val agentVersion: String = "",
    val status: String = "ok",
    @SerialName("checks_run") val checksRun: Int = 0,
    @SerialName("checks_failed") val checksFailed: Int = 0,
    @SerialName("host_name") val hostName: String = "",
    val tags: List<String> = emptyList(),
)

// --- Container Image & SBOM Models ---

@Serializable
data class DdContainerImagePayload(
    @SerialName("image_name") val imageName: String = "",
    @SerialName("image_tag") val imageTag: String = "",
    val digest: String = "",
    val registry: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    val os: String = "",
    val architecture: String = "",
    val layers: Int = 0,
    val tags: List<String> = emptyList(),
)

@Serializable
data class DdSbomPayload(
    val host: String = "",
    @SerialName("container_id") val containerId: String = "",
    @SerialName("image_name") val imageName: String = "",
    val packages: List<DdSbomPackage> = emptyList(),
    val tags: List<String> = emptyList(),
)

@Serializable
data class DdSbomPackage(
    val name: String = "",
    val version: String = "",
    val type: String = "",
    @SerialName("cve_ids") val cveIds: List<String> = emptyList(),
)

// --- Data Streams Models ---

@Serializable
data class DdDataStreamsPayload(
    @SerialName("pipeline_id") val pipelineId: String = "",
    val stats: List<DdDataStreamsEntry> = emptyList(),
    val tags: List<String> = emptyList(),
)

@Serializable
data class DdDataStreamsEntry(
    @SerialName("stage_name") val stageName: String = "",
    @SerialName("latency_ns") val latencyNs: Long = 0,
    @SerialName("payload_size") val payloadSize: Long = 0,
    val direction: String = "in",
)

// --- Symbol DB Models ---

@Serializable
data class DdSymbolDbPayload(
    val service: String = "",
    val env: String = "",
    val language: String = "",
    val version: String = "",
    val symbols: String = "",
)

// --- Pipeline Stats Models ---

@Serializable
data class DdPipelineStatsPayload(
    @SerialName("pipeline_id") val pipelineId: String = "",
    val stats: List<DdPipelineStatEntry> = emptyList(),
    val host: String = "",
)

@Serializable
data class DdPipelineStatEntry(
    @SerialName("stage_name") val stageName: String = "",
    @SerialName("in_count") val inCount: Long = 0,
    @SerialName("out_count") val outCount: Long = 0,
    @SerialName("drop_count") val dropCount: Long = 0,
    @SerialName("error_count") val errorCount: Long = 0,
)

// --- Data Lineage Models ---

@Serializable
data class DdDataLineagePayload(
    @SerialName("run_id") val runId: String = "",
    @SerialName("job_name") val jobName: String = "",
    val namespace: String = "",
    val inputs: List<String> = emptyList(),
    val outputs: List<String> = emptyList(),
    @SerialName("event_type") val eventType: String = "",
    val facets: String = "",
)

// --- Synthetics Models ---

@Serializable
data class DdSyntheticsPayload(
    val results: List<DdSyntheticResult> = emptyList(),
)

@Serializable
data class DdSyntheticResult(
    @SerialName("test_id") val testId: String = "",
    @SerialName("test_name") val testName: String = "",
    @SerialName("test_type") val testType: String = "api",
    val status: String = "passed",
    @SerialName("probe_dc") val probeDc: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("error_message") val errorMessage: String = "",
    val timings: Map<String, Double> = emptyMap(),
    val tags: List<String> = emptyList(),
)

// --- Network Device Monitoring Models ---

@Serializable
data class DdNdmPayload(
    val type: String = "",
    val devices: List<DdNdmDevice> = emptyList(),
    val traps: List<DdNdmTrap> = emptyList(),
    val flows: List<DdNdmFlow> = emptyList(),
    val paths: List<DdNdmPath> = emptyList(),
    val configs: List<DdNdmConfig> = emptyList(),
)

@Serializable
data class DdNdmDevice(
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("ip_address") val ipAddress: String = "",
    val hostname: String = "",
    val vendor: String = "",
    val model: String = "",
    @SerialName("os_version") val osVersion: String = "",
    @SerialName("device_type") val deviceType: String = "",
    val status: String = "unknown",
    val reachability: String = "unknown",
    @SerialName("snmp_version") val snmpVersion: String = "",
    val tags: List<String> = emptyList(),
)

@Serializable
data class DdNdmTrap(
    @SerialName("device_ip") val deviceIp: String = "",
    val oid: String = "",
    val severity: String = "info",
    val message: String = "",
    val variables: Map<String, String> = emptyMap(),
)

@Serializable
data class DdNdmFlow(
    @SerialName("src_ip") val srcIp: String = "",
    @SerialName("dst_ip") val dstIp: String = "",
    @SerialName("src_port") val srcPort: Int = 0,
    @SerialName("dst_port") val dstPort: Int = 0,
    val protocol: String = "",
    val bytes: Long = 0,
    val packets: Long = 0,
    val direction: String = "",
    @SerialName("flow_type") val flowType: String = "netflow",
    val tags: List<String> = emptyList(),
)

@Serializable
data class DdNdmPath(
    val source: String = "",
    val destination: String = "",
    val hops: List<String> = emptyList(),
    @SerialName("hop_rtts") val hopRtts: List<Double> = emptyList(),
    val tags: List<String> = emptyList(),
)

@Serializable
data class DdNdmConfig(
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("config_type") val configType: String = "",
    val content: String = "",
    val tags: List<String> = emptyList(),
)

// --- Security Models ---

@Serializable
data class DdSecurityEventPayload(
    val events: List<DdSecurityEvent> = emptyList(),
)

@Serializable
data class DdSecurityEvent(
    @SerialName("rule_id") val ruleId: String = "",
    @SerialName("rule_name") val ruleName: String = "",
    @SerialName("rule_category") val ruleCategory: String = "",
    val severity: String = "info",
    @SerialName("agent_rule_version") val agentRuleVersion: String = "",
    @SerialName("event_type") val eventType: String = "",
    @SerialName("process_name") val processName: String = "",
    @SerialName("file_path") val filePath: String = "",
    val host: String = "",
    val env: String = "",
    val tags: List<String> = emptyList(),
)

@Serializable
data class DdActivityDumpPayload(
    val dumps: List<DdActivityDump> = emptyList(),
)

@Serializable
data class DdActivityDump(
    @SerialName("activity_type") val activityType: String = "",
    @SerialName("process_name") val processName: String = "",
    val host: String = "",
    @SerialName("duration_ns") val durationNs: Long = 0,
    @SerialName("dump_data") val dumpData: String = "",
    val tags: List<String> = emptyList(),
)

@Serializable
data class DdCompliancePayload(
    val findings: List<DdComplianceFinding> = emptyList(),
)

@Serializable
data class DdComplianceFinding(
    val framework: String = "",
    @SerialName("rule_id") val ruleId: String = "",
    @SerialName("rule_name") val ruleName: String = "",
    val status: String = "passed",
    @SerialName("resource_type") val resourceType: String = "",
    @SerialName("resource_id") val resourceId: String = "",
    @SerialName("resource_name") val resourceName: String = "",
    val tags: List<String> = emptyList(),
)
