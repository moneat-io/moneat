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

package com.moneat.config

import com.moneat.datadog.security.QueuedActivityDumpEntry
import com.moneat.datadog.security.QueuedComplianceEntry
import com.moneat.datadog.security.QueuedSecurityBatch
import com.moneat.datadog.security.QueuedSecurityEventEntry
import com.moneat.datadog.security.SecurityIngestionService
import com.moneat.utils.suspendRunCatching
import io.ktor.client.statement.bodyAsText
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

// ── Security Demo Data ─────────────────────────────────────────────────
//
// The demo reseed builds typed batches and inserts them through the same
// SecurityIngestionService.insertBatch path the ingestion worker uses, so the
// demo exercises the real agent insert code rather than fabricating rows in SQL.

/** Demo org ids matching the negative-ID convention used across demo tables. */
private val DEMO_ORG_IDS = listOf(-1, -2, -3)

private const val DEMO_EVENT_COUNT = 60
private const val DEMO_FINDING_COUNT = 100
private const val DEMO_DUMP_COUNT = 12

private const val MINUTES_PER_MS = 60_000L
private const val EVENT_SPREAD_MINUTES = 4320L
private const val FINDING_SPREAD_MINUTES = 2880L
private const val DUMP_SPREAD_MINUTES = 1440L
private const val EVENT_SPREAD_STEP = 37L
private const val FINDING_SPREAD_STEP = 61L
private const val DUMP_SPREAD_STEP = 53L

private const val DEMO_AGENT_RULE_VERSION = "7.52.1"
private const val DEMO_ENV = "production"
private const val NS_PER_MS = 1_000_000L

private val EVENT_RULE_IDS = listOf(
    "cws-001",
    "cws-002",
    "cws-003",
    "cws-004",
    "cws-005",
    "cws-006",
    "cws-007",
    "cws-008",
)
private val EVENT_RULE_NAMES = listOf(
    "Sensitive file accessed",
    "Privilege escalation attempt",
    "Suspicious network connection",
    "Container escape attempt",
    "Cryptominer detected",
    "Reverse shell spawned",
    "SSH key modification",
    "Cron job created",
)
private val EVENT_CATEGORIES = listOf("file", "process", "network", "container")
private val EVENT_SEVERITIES = listOf("info", "low", "medium", "high", "critical")
private val EVENT_TYPES = listOf(
    "file_open",
    "process_exec",
    "network_connect",
    "setuid",
    "module_load",
    "ptrace",
)
private val EVENT_PROCESS_NAMES = listOf(
    "sshd",
    "bash",
    "python3",
    "curl",
    "wget",
    "nc",
    "ncat",
    "openssl",
    "nmap",
    "su",
)
private val EVENT_FILE_PATHS = listOf(
    "/etc/passwd",
    "/etc/shadow",
    "/root/.ssh/authorized_keys",
    "/proc/self/mem",
    "/var/run/docker.sock",
    "/etc/crontab",
    "/usr/bin/sudo",
    "/bin/sh",
)
private val EVENT_HOSTS = listOf(
    "prod-web-01",
    "prod-api-01",
    "prod-db-01",
    "prod-worker-01",
    "prod-web-02",
)
private val EVENT_TEAMS = listOf("backend", "frontend", "infra")

private val FINDING_FRAMEWORKS = listOf("CIS", "PCI-DSS", "SOC2", "HIPAA", "NIST")
private val FINDING_RULE_NAMES = listOf(
    "Ensure MFA is enabled",
    "Restrict root account access",
    "Enable audit logging",
    "Encrypt data at rest",
    "Use private subnets",
    "Restrict security group ingress",
    "Enable VPC flow logs",
    "Rotate access keys",
    "Enable CloudTrail",
    "Patch OS vulnerabilities",
    "Disable unused ports",
    "Enable WAF",
    "Use encrypted EBS volumes",
    "Restrict S3 public access",
    "Enable GuardDuty",
    "Use least privilege IAM",
    "Enable Config rules",
    "Use TLS 1.2+",
    "Enable container scanning",
    "Restrict SSH access",
)
private val FINDING_STATUSES = listOf("passed", "failed", "passed", "passed", "skipped")
private val FINDING_RESOURCE_TYPES = listOf(
    "aws_s3_bucket",
    "aws_ec2_instance",
    "aws_iam_user",
    "aws_security_group",
    "k8s_pod",
)
private val FINDING_RESOURCE_NAMES = listOf(
    "prod-bucket-01",
    "prod-web-01",
    "deploy-user",
    "web-sg",
    "api-pod-01",
)

private val DUMP_ACTIVITY_TYPES = listOf("process", "dns", "file", "network")

private fun <T> List<T>.cycle(index: Int): T = this[index % size]

private fun spreadTimestampMs(now: Long, index: Int, step: Long, spreadMinutes: Long): Long =
    now - (index * step % spreadMinutes) * MINUTES_PER_MS

internal suspend fun checkFreshSecurityDataCount(): Long {
    val query = """
        SELECT count() FROM security_events
        WHERE organization_id IN ($P1, $P2, $P3)
            AND timestamp >= now() - INTERVAL 2 HOUR
    """.trimIndent()
    return suspendRunCatching {
        val response = ClickHouseClient.execute(query)
        if (response.status.value !in 200..299) {
            0L
        } else {
            response.bodyAsText().trim().toLongOrNull() ?: 0L
        }
    }.getOrElse {
        logger.warn { "Failed to check fresh security demo data (non-fatal): ${it.message}" }
        0L
    }
}

internal suspend fun purgeSecurityDemoData() {
    for (table in listOf("security_events", "compliance_findings", "security_dumps")) {
        suspendRunCatching {
            requireClickHouse2xx(
                ClickHouseClient.execute(
                    "ALTER TABLE $table DELETE WHERE organization_id IN ($P1, $P2, $P3)"
                ),
                "Purge $table"
            )
        }.onFailure { logger.warn { "Purge $table failed (non-fatal): ${it.message}" } }
    }
}

private fun buildSecurityEventEntry(now: Long, index: Int): QueuedSecurityEventEntry =
    QueuedSecurityEventEntry(
        ruleId = EVENT_RULE_IDS.cycle(index),
        ruleName = EVENT_RULE_NAMES.cycle(index),
        ruleCategory = EVENT_CATEGORIES.cycle(index),
        severity = EVENT_SEVERITIES.cycle(index),
        agentRuleVersion = DEMO_AGENT_RULE_VERSION,
        eventType = EVENT_TYPES.cycle(index),
        processName = EVENT_PROCESS_NAMES.cycle(index),
        filePath = EVENT_FILE_PATHS.cycle(index),
        host = EVENT_HOSTS.cycle(index),
        env = DEMO_ENV,
        tags = mapOf("env" to DEMO_ENV, "team" to EVENT_TEAMS.cycle(index)),
        timestampMs = spreadTimestampMs(now, index, EVENT_SPREAD_STEP, EVENT_SPREAD_MINUTES),
    )

private fun buildComplianceEntry(now: Long, index: Int): QueuedComplianceEntry =
    QueuedComplianceEntry(
        framework = FINDING_FRAMEWORKS.cycle(index),
        ruleId = "rule-${index % FINDING_RULE_NAMES.size + 1}",
        ruleName = FINDING_RULE_NAMES.cycle(index),
        status = FINDING_STATUSES.cycle(index),
        resourceType = FINDING_RESOURCE_TYPES.cycle(index),
        resourceId = "res-${index % FINDING_RESOURCE_NAMES.size}",
        resourceName = FINDING_RESOURCE_NAMES.cycle(index),
        tags = mapOf("env" to DEMO_ENV),
        timestampMs = spreadTimestampMs(now, index, FINDING_SPREAD_STEP, FINDING_SPREAD_MINUTES),
    )

private fun buildDumpEntry(now: Long, index: Int): QueuedActivityDumpEntry =
    QueuedActivityDumpEntry(
        activityType = DUMP_ACTIVITY_TYPES.cycle(index),
        processName = EVENT_PROCESS_NAMES.cycle(index),
        host = EVENT_HOSTS.cycle(index),
        durationNs = (index + 1) * NS_PER_MS,
        dumpData = "",
        tags = mapOf("env" to DEMO_ENV),
        timestampMs = spreadTimestampMs(now, index, DUMP_SPREAD_STEP, DUMP_SPREAD_MINUTES),
    )

/**
 * Build per-org batches so each [QueuedSecurityBatch] carries a single organization id (matching
 * the agent ingest shape) while the generated rows are spread across the demo orgs round-robin.
 */
private fun <T> buildBatchesPerOrg(
    count: Int,
    build: (index: Int) -> T,
    assign: (orgId: Int, entries: List<T>) -> QueuedSecurityBatch,
): List<QueuedSecurityBatch> {
    val byOrg = DEMO_ORG_IDS.associateWith { mutableListOf<T>() }
    for (index in 0 until count) {
        val orgId = DEMO_ORG_IDS[index % DEMO_ORG_IDS.size]
        byOrg.getValue(orgId).add(build(index))
    }
    return byOrg.entries
        .filter { it.value.isNotEmpty() }
        .map { (orgId, entries) -> assign(orgId, entries) }
}

private fun buildSecurityEventBatches(now: Long): List<QueuedSecurityBatch> =
    buildBatchesPerOrg(
        count = DEMO_EVENT_COUNT,
        build = { index -> buildSecurityEventEntry(now, index) },
        assign = { orgId, entries -> QueuedSecurityBatch(orgId, "events", events = entries) },
    )

private fun buildComplianceBatches(now: Long): List<QueuedSecurityBatch> =
    buildBatchesPerOrg(
        count = DEMO_FINDING_COUNT,
        build = { index -> buildComplianceEntry(now, index) },
        assign = { orgId, entries -> QueuedSecurityBatch(orgId, "findings", findings = entries) },
    )

private fun buildDumpBatches(now: Long): List<QueuedSecurityBatch> =
    buildBatchesPerOrg(
        count = DEMO_DUMP_COUNT,
        build = { index -> buildDumpEntry(now, index) },
        assign = { orgId, entries -> QueuedSecurityBatch(orgId, "dumps", dumps = entries) },
    )

private suspend fun insertDemoBatches(label: String, batches: List<QueuedSecurityBatch>): Result<Unit> =
    suspendRunCatching {
        batches.forEach { SecurityIngestionService.insertBatch(it) }
    }.onFailure {
        logger.warn { "Reseed $label failed (non-fatal): ${it.message}" }
    }

internal suspend fun reseedSecurityData() {
    val now = System.currentTimeMillis()

    val eventsResult = insertDemoBatches("security_events", buildSecurityEventBatches(now))
    val complianceResult = insertDemoBatches("compliance_findings", buildComplianceBatches(now))
    val dumpsResult = insertDemoBatches("security_dumps", buildDumpBatches(now))

    if (eventsResult.isSuccess && complianceResult.isSuccess && dumpsResult.isSuccess) {
        logger.info { "Security demo data reseed complete" }
    }
}
