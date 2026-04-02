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

import com.moneat.utils.suspendRunCatching
import io.ktor.client.statement.bodyAsText
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

// ── Security Demo Data ─────────────────────────────────────────────────

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
            ClickHouseClient.execute(
                "ALTER TABLE $table DELETE WHERE organization_id IN ($P1, $P2, $P3)"
            )
        }.onFailure { logger.warn { "Purge $table failed (non-fatal): ${it.message}" } }
    }
}

private fun buildSecurityEventsInsertSql(): String =
    """
        INSERT INTO security_events (
            event_id, organization_id, rule_id, rule_name, rule_category,
            severity, agent_rule_version, event_type, process_name,
            file_path, host, env, tags, timestamp
        )
        SELECT
            generateUUIDv4(),
            arrayElement([$P1, $P2, $P3], number % 3 + 1),
            arrayElement([
                'cws-001', 'cws-002', 'cws-003', 'cws-004', 'cws-005',
                'cws-006', 'cws-007', 'cws-008'
            ], number % 8 + 1),
            arrayElement([
                'Sensitive file accessed', 'Privilege escalation attempt',
                'Suspicious network connection', 'Container escape attempt',
                'Cryptominer detected', 'Reverse shell spawned',
                'SSH key modification', 'Cron job created'
            ], number % 8 + 1),
            arrayElement(['file', 'process', 'network', 'container'], number % 4 + 1),
            arrayElement(['info', 'low', 'medium', 'high', 'critical'], number % 5 + 1),
            '7.52.1',
            arrayElement([
                'file_open', 'process_exec', 'network_connect',
                'setuid', 'module_load', 'ptrace'
            ], number % 6 + 1),
            arrayElement([
                'sshd', 'bash', 'python3', 'curl', 'wget',
                'nc', 'ncat', 'openssl', 'nmap', 'su'
            ], number % 10 + 1),
            arrayElement([
                '/etc/passwd', '/etc/shadow', '/root/.ssh/authorized_keys',
                '/proc/self/mem', '/var/run/docker.sock',
                '/etc/crontab', '/usr/bin/sudo', '/bin/sh'
            ], number % 8 + 1),
            arrayElement([
                'prod-web-01', 'prod-api-01', 'prod-db-01',
                'prod-worker-01', 'prod-web-02'
            ], number % 5 + 1),
            'production',
            map('env', 'production', 'team', arrayElement(['backend', 'frontend', 'infra'], number % 3 + 1)),
            now() - INTERVAL (number * 37 % 4320) MINUTE
        FROM numbers(60)
    """.trimIndent()

private fun buildComplianceFindingsInsertSql(): String =
    """
        INSERT INTO compliance_findings (
            finding_id, organization_id, framework, rule_id, rule_name,
            status, resource_type, resource_id, resource_name, tags, evaluated_at
        )
        SELECT
            generateUUIDv4(),
            arrayElement([$P1, $P2, $P3], number % 3 + 1),
            arrayElement(['CIS', 'PCI-DSS', 'SOC2', 'HIPAA', 'NIST'], number % 5 + 1),
            concat('rule-', toString(number % 20 + 1)),
            arrayElement([
                'Ensure MFA is enabled', 'Restrict root account access',
                'Enable audit logging', 'Encrypt data at rest',
                'Use private subnets', 'Restrict security group ingress',
                'Enable VPC flow logs', 'Rotate access keys',
                'Enable CloudTrail', 'Patch OS vulnerabilities',
                'Disable unused ports', 'Enable WAF',
                'Use encrypted EBS volumes', 'Restrict S3 public access',
                'Enable GuardDuty', 'Use least privilege IAM',
                'Enable Config rules', 'Use TLS 1.2+',
                'Enable container scanning', 'Restrict SSH access'
            ], number % 20 + 1),
            arrayElement(['passed', 'failed', 'passed', 'passed', 'skipped'], number % 5 + 1),
            arrayElement(['aws_s3_bucket', 'aws_ec2_instance', 'aws_iam_user',
                'aws_security_group', 'k8s_pod'], number % 5 + 1),
            concat('res-', toString(sipHash64(number, 42) % 1000)),
            arrayElement([
                'prod-bucket-01', 'prod-web-01', 'deploy-user',
                'web-sg', 'api-pod-01'
            ], number % 5 + 1),
            map('env', 'production'),
            now() - INTERVAL (number * 61 % 2880) MINUTE
        FROM numbers(100)
    """.trimIndent()

internal suspend fun reseedSecurityData() {
    val eventsResult =
        suspendRunCatching {
            requireClickHouse2xx(
                ClickHouseClient.execute(buildSecurityEventsInsertSql()),
                "Reseed security_events"
            )
        }
    eventsResult.onFailure {
        logger.warn { "Reseed security_events failed (non-fatal): ${it.message}" }
    }

    val complianceResult =
        suspendRunCatching {
            requireClickHouse2xx(
                ClickHouseClient.execute(buildComplianceFindingsInsertSql()),
                "Reseed compliance_findings"
            )
        }
    complianceResult.onFailure {
        logger.warn { "Reseed compliance_findings failed (non-fatal): ${it.message}" }
    }

    if (eventsResult.isSuccess && complianceResult.isSuccess) {
        logger.info { "Security demo data reseed complete" }
    }
}
