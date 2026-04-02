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
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

// ── Datadog Agent Demo Data ─────────────────────────────────────────────

internal suspend fun checkFreshDatadogCount(): Long {
    return suspendRunCatching {
        val tablesWithTimeCol =
            listOf(
                Triple("apm_spans", "start", "organization_id"),
                Triple("profiles", "start_time", "organization_id"),
                Triple("service_checks", "timestamp", "organization_id"),
                Triple("containers", "timestamp", "organization_id"),
            )
        var minCount = Long.MAX_VALUE
        for ((table, timeCol, orgCol) in tablesWithTimeCol) {
            val q =
                """
                SELECT count() as cnt
                FROM $table
                WHERE $orgCol = $ORG1
                    AND $timeCol >= now() - INTERVAL 2 HOUR
                """.trimIndent()
            val response = ClickHouseClient.execute(q)
            if (response.status.value !in 200..299) {
                return@suspendRunCatching 0L
            }
            val cnt = response.bodyAsText().trim().toLongOrNull() ?: 0L
            if (cnt < minCount) minCount = cnt
        }
        if (minCount == Long.MAX_VALUE) 0L else minCount
    }.getOrElse {
        logger.warn { "Failed to check fresh Datadog demo data (non-fatal): ${it.message}" }
        0L
    }
}

internal suspend fun purgeDatadogDemoData() {
    val tables =
        listOf(
            "apm_spans",
            "trace_stats",
            "profiles",
            "infra_events",
            "service_checks",
            "processes",
            "containers",
            "network_connections",
        )
    for (table in tables) {
        suspendRunCatching {
            requireClickHouse2xx(
                ClickHouseClient.execute("ALTER TABLE $table DELETE WHERE organization_id = $ORG1"),
                "Purge Datadog $table"
            )
        }.onFailure { logger.warn { "Purge $table failed (non-fatal): ${it.message}" } }
    }
    // PostgreSQL hosts
    suspendRunCatching {
        transaction {
            exec("DELETE FROM hosts WHERE organization_id = -1")
        }
    }.onFailure { logger.warn { "Purge hosts failed (non-fatal): ${it.message}" } }
    cleanDemoProfileFiles()
}

internal suspend fun reseedDatadogData() {
    reseedDatadogHostsPostgres()
    reseedDatadogApmRootSpans()
    reseedDatadogApmChildSpans()
    reseedDatadogProfileRows()
    reseedDatadogInfraEvents()
    reseedDatadogServiceChecks()
    reseedDatadogProcesses()
    reseedDatadogContainers()
    reseedDatadogNetworkConnections()
    logger.info { "Datadog agent demo data reseed complete" }
}

private suspend fun reseedDatadogHostsPostgres() {
    suspendRunCatching {
        transaction {
            val hostData = listOf(
                listOf("prod-web-01", "Ubuntu 22.04", "linux", "Intel Xeon E5-2686 v4", "8", "16384000", "7.52.1"),
                listOf("prod-web-02", "Ubuntu 22.04", "linux", "Intel Xeon E5-2686 v4", "8", "16384000", "7.52.1"),
                listOf("prod-api-01", "Ubuntu 22.04", "linux", "AMD EPYC 7R13", "16", "32768000", "7.52.1"),
                listOf("prod-db-01", "Ubuntu 22.04", "linux", "AMD EPYC 7R13", "32", "65536000", "7.52.1"),
                listOf("prod-cache-01", "Ubuntu 22.04", "linux", "Intel Xeon E5-2686 v4", "4", "8192000", "7.52.1"),
                listOf("prod-worker-01", "Ubuntu 22.04", "linux", "AMD EPYC 7R13", "8", "16384000", "7.52.1"),
            )
            for (h in hostData) {
                exec(
                    """
                    INSERT INTO hosts (organization_id, hostname, os, platform, processor, cpu_cores, memory_total_kb, agent_version, gohai, tags, first_seen_at, last_seen_at)
                    VALUES (-1, '${h[0]}', '${h[1]}', '${h[2]}', '${h[3]}', ${h[4]}, ${h[5]}, '${h[6]}',
                        '{}', '{"env":"production","service":"acme-shopping"}', NOW() - INTERVAL '14 days', NOW() - INTERVAL '30 seconds')
                    ON CONFLICT (organization_id, hostname) DO UPDATE SET last_seen_at = NOW() - INTERVAL '30 seconds'
                    """.trimIndent()
                )
            }
        }
    }.onFailure { logger.warn { "Reseed hosts failed (non-fatal): ${it.message}" } }
}

private suspend fun reseedDatadogApmRootSpans() {
    val rootSpansSql =
        """
        INSERT INTO apm_spans (
            span_id, trace_id, parent_id, organization_id, name, service,
            resource, type, start, duration, error, meta, metrics, host, env, version
        )
        SELECT
            reinterpretAsUInt64(sipHash64(number, 1)),
            reinterpretAsUInt64(sipHash64(number, 0)),
            0,
            $ORG1,
            'http.request',
            arrayElement(['api-gateway', 'api-gateway', 'user-service', 'order-service', 'payment-service'], number % 5 + 1),
            arrayElement(['GET /api/v1/products', 'POST /api/v1/orders', 'GET /api/v1/users/{id}', 'POST /api/v1/checkout', 'GET /api/v1/cart'], number % 5 + 1),
            'web',
            now() - INTERVAL (number * 37 % 120) MINUTE,
            20000000 + (sipHash64(number, 2) % 480000000),
            if(number % 7 = 0, 1, 0),
            map('http.method', arrayElement(['GET', 'POST', 'GET', 'POST', 'GET'], number % 5 + 1),
                'http.url', arrayElement(['GET /api/v1/products', 'POST /api/v1/orders', 'GET /api/v1/users/{id}', 'POST /api/v1/checkout', 'GET /api/v1/cart'], number % 5 + 1),
                'http.status_code', if(number % 7 = 0, '500', '200')),
            map('_sample_rate', 1.0),
            arrayElement(['prod-web-01', 'prod-web-02', 'prod-api-01'], number % 3 + 1),
            'production',
            '1.3.0'
        FROM numbers(20)
        """.trimIndent()
    suspendRunCatching { ClickHouseClient.execute(rootSpansSql) }
        .onFailure { logger.warn { "Reseed root spans failed (non-fatal): ${it.message}" } }
}

private suspend fun reseedDatadogApmChildSpans() {
    val childSpansSql =
        """
        INSERT INTO apm_spans (
            span_id, trace_id, parent_id, organization_id, name, service,
            resource, type, start, duration, error, meta, metrics, host, env, version
        )
        SELECT
            reinterpretAsUInt64(sipHash64(number, 10 + number % 3)),
            reinterpretAsUInt64(sipHash64(intDiv(number, 3), 0)),
            reinterpretAsUInt64(sipHash64(intDiv(number, 3), 1)),
            $ORG1,
            arrayElement(['http.request', 'postgresql.query', 'redis.command'], number % 3 + 1),
            arrayElement(['user-service', 'postgres', 'cache-service', 'product-service', 'order-service', 'inventory-service'], number % 6 + 1),
            arrayElement(['SELECT * FROM users WHERE id = ?', 'GET cache:product:*', 'POST /api/v1/orders', 'GET /api/v1/products', 'POST /api/v1/checkout', 'worker.process'], number % 6 + 1),
            arrayElement(['web', 'sql', 'cache', 'web', 'web', 'worker'], number % 6 + 1),
            now() - INTERVAL (intDiv(number, 3) * 37 % 120) MINUTE + INTERVAL (number % 3 + 1) * 5 SECOND,
            2000000 + (sipHash64(number, 20) % 100000000),
            0,
            map('component', arrayElement(['user-service', 'postgres', 'cache-service', 'product-service', 'order-service', 'inventory-service'], number % 6 + 1)),
            map('_sample_rate', 1.0),
            arrayElement(['prod-api-01', 'prod-db-01', 'prod-cache-01', 'prod-api-01', 'prod-api-01', 'prod-worker-01'], number % 6 + 1),
            'production',
            '1.3.0'
        FROM numbers(60)
        """.trimIndent()
    suspendRunCatching { ClickHouseClient.execute(childSpansSql) }
        .onFailure { logger.warn { "Reseed child spans failed (non-fatal): ${it.message}" } }
}

private suspend fun reseedDatadogProfileRows() {
    val demoProfileIds = (1..DEMO_PROFILE_COUNT).map { n ->
        "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    }
    ensureDemoProfileRows(demoProfileIds)
}

private suspend fun reseedDatadogInfraEvents() {
    val eventsSql =
        """
        INSERT INTO infra_events (
            event_id, organization_id, title, text, timestamp, priority, host,
            tags, alert_type, aggregation_key, source_type_name, device_name
        )
        SELECT
            generateUUIDv4(),
            $ORG1,
            arrayElement([
                'Deployment started: api-gateway v1.3.0',
                'Deployment completed: api-gateway v1.3.0',
                'High memory usage on prod-db-01',
                'Auto-scaling triggered: order-service',
                'SSL certificate renewed: *.acme.com',
                'Database backup completed',
                'Rate limiting activated: /api/v1/search',
                'Pod restart: payment-service-7f8d9c',
                'Cache eviction spike on prod-cache-01',
                'Deployment rolled back: user-service v1.2.9'
            ], number % 10 + 1),
            arrayElement([
                'Rolling deployment initiated for api-gateway. 4 pods updating.',
                'All pods healthy. Zero-downtime deployment successful.',
                'Memory utilization at 87%. Consider scaling or optimizing queries.',
                'CPU above 80% for 5 minutes. Scaling from 3 to 5 replicas.',
                'Certificate auto-renewed via Let''s Encrypt. Valid until 2026-05-25.',
                'Full backup of prod-db-01 completed. Size: 42.3GB, Duration: 12m34s.',
                'Request rate exceeded 1000/min threshold from 203.0.113.42.',
                'Container OOMKilled. Memory limit: 512Mi. Peak usage: 498Mi.',
                'Redis evicted 15,000 keys in last 5 minutes. maxmemory-policy: allkeys-lru.',
                'Health check failures exceeded threshold. Automatic rollback to v1.2.8.'
            ], number % 10 + 1),
            now() - INTERVAL (number * 7) HOUR,
            'normal',
            arrayElement(['prod-web-01', 'prod-web-01', 'prod-db-01', 'prod-api-01', 'prod-web-01', 'prod-db-01', 'prod-web-01', 'prod-api-01', 'prod-cache-01', 'prod-api-01'], number % 10 + 1),
            map('env', 'production'),
            arrayElement(['info', 'success', 'warning', 'warning', 'info', 'info', 'warning', 'error', 'warning', 'error'], number % 10 + 1),
            '',
            arrayElement(['deployment', 'deployment', 'system', 'kubernetes', 'cert-manager', 'backup', 'api-gateway', 'kubernetes', 'redis', 'deployment'], number % 10 + 1),
            ''
        FROM numbers(10)
        """.trimIndent()
    suspendRunCatching { ClickHouseClient.execute(eventsSql) }
        .onFailure { logger.warn { "Reseed infra_events failed (non-fatal): ${it.message}" } }
}

private suspend fun reseedDatadogServiceChecks() {
    val checksSql =
        """
        INSERT INTO service_checks (
            check_id, organization_id, check_name, host, status, timestamp, tags, message
        )
        SELECT
            generateUUIDv4(),
            $ORG1,
            arrayElement(['datadog.agent.up', 'http.can_connect', 'postgres.can_connect', 'redis.can_ping', 'disk.check', 'ntp.offset', 'tls.cert_expiry', 'http.can_connect'], number % 8 + 1),
            arrayElement(['prod-web-01', 'prod-web-02', 'prod-api-01', 'prod-db-01', 'prod-cache-01', 'prod-worker-01'], intDiv(number, 8) % 6 + 1),
            arrayElement(['ok', 'ok', 'ok', 'ok', 'warning', 'ok', 'ok', 'critical'], number % 8 + 1),
            now() - INTERVAL (number % 60) MINUTE,
            map('env', 'production'),
            arrayElement([
                'Agent is reporting normally',
                'HTTP connection successful (200)',
                'PostgreSQL connection established',
                'Redis PONG received in 0.3ms',
                'Disk usage at 82% on /dev/sda1',
                'NTP offset: +12ms',
                'Certificate valid for 89 days',
                'Connection refused on port 8443'
            ], number % 8 + 1)
        FROM numbers(48)
        """.trimIndent()
    suspendRunCatching { ClickHouseClient.execute(checksSql) }
        .onFailure { logger.warn { "Reseed service_checks failed (non-fatal): ${it.message}" } }
}

private suspend fun reseedDatadogProcesses() {
    val processesSql =
        """
        INSERT INTO processes (
            process_id, organization_id, host, pid, name, command, user,
            cpu_percent, mem_rss, mem_vms, state, thread_count, open_fd_count,
            tags, timestamp
        )
        SELECT
            generateUUIDv4(),
            $ORG1,
            arrayElement(['prod-web-01', 'prod-web-02', 'prod-api-01', 'prod-db-01', 'prod-cache-01', 'prod-worker-01'], intDiv(number, 7) % 6 + 1),
            1000 + number * 100,
            arrayElement(['nginx', 'api-gateway', 'user-service', 'postgres', 'redis-server', 'datadog-agent', 'containerd'], number % 7 + 1),
            arrayElement([
                '/usr/sbin/nginx -g daemon off;',
                '/app/api-gateway serve --port 8080',
                'java -jar /app/user-service.jar',
                '/usr/lib/postgresql/15/bin/postgres -D /var/lib/postgresql/15/main',
                'redis-server *:6379',
                '/opt/datadog-agent/bin/agent/agent run',
                '/usr/bin/containerd'
            ], number % 7 + 1),
            arrayElement(['root', 'appuser', 'appuser', 'postgres', 'redis', 'dd-agent', 'root'], number % 7 + 1),
            0.5 + (sipHash64(number, 40) % 4000) / 100.0,
            10485760 + sipHash64(number, 41) % 2000000000,
            20971520 + sipHash64(number, 42) % 4000000000,
            'running',
            1 + sipHash64(number, 43) % 48,
            3 + sipHash64(number, 44) % 253,
            map('env', 'production'),
            now() - INTERVAL (number % 30) MINUTE
        FROM numbers(42)
        """.trimIndent()
    suspendRunCatching { ClickHouseClient.execute(processesSql) }
        .onFailure { logger.warn { "Reseed processes failed (non-fatal): ${it.message}" } }
}

private suspend fun reseedDatadogContainers() {
    val containersSql =
        """
        INSERT INTO containers (
            container_id_hash, organization_id, host, container_id, name, image, state,
            cpu_percent, mem_usage, mem_limit, net_rx_bytes, net_tx_bytes,
            tags, timestamp
        )
        SELECT
            generateUUIDv4(),
            $ORG1,
            arrayElement(['prod-web-01', 'prod-web-02', 'prod-api-01', 'prod-db-01', 'prod-cache-01', 'prod-worker-01'], intDiv(number, 7) % 6 + 1),
            substring(toString(sipHash64(number, 50)), 1, 12),
            arrayElement(['api-gateway', 'user-service', 'product-service', 'order-service', 'payment-service', 'nginx-ingress', 'datadog-agent'], number % 7 + 1),
            arrayElement(['acme/api-gateway:1.3.0', 'acme/user-service:1.2.8', 'acme/product-service:1.4.1', 'acme/order-service:2.0.3', 'acme/payment-service:1.1.5', 'nginx/nginx-ingress:3.4.0', 'datadog/agent:7.52.1'], number % 7 + 1),
            'running',
            0.5 + (sipHash64(number, 51) % 6000) / 100.0,
            268435456 + sipHash64(number, 52) % 3500000000,
            4294967296,
            1048576 + sipHash64(number, 53) % 500000000,
            524288 + sipHash64(number, 54) % 250000000,
            map('env', 'production', 'service', arrayElement(['api-gateway', 'user-service', 'product-service', 'order-service', 'payment-service', 'nginx-ingress', 'datadog-agent'], number % 7 + 1)),
            now() - INTERVAL (number % 30) MINUTE
        FROM numbers(42)
        """.trimIndent()
    suspendRunCatching { ClickHouseClient.execute(containersSql) }
        .onFailure { logger.warn { "Reseed containers failed (non-fatal): ${it.message}" } }
}

private suspend fun reseedDatadogNetworkConnections() {
    val connSql =
        """
        INSERT INTO network_connections (
            connection_id, organization_id, host, pid, local_addr, local_port,
            remote_addr, remote_port, protocol, family, direction,
            bytes_sent, bytes_recv, tags, timestamp
        )
        SELECT
            generateUUIDv4(),
            $ORG1,
            arrayElement(['prod-web-01', 'prod-api-01', 'prod-api-01', 'prod-web-02', 'prod-worker-01', 'prod-worker-01', 'prod-web-01', 'prod-web-02'], number % 8 + 1),
            1000 + number * 111,
            arrayElement(['prod-web-01', 'prod-api-01', 'prod-api-01', 'prod-web-02', 'prod-worker-01', 'prod-worker-01', 'prod-web-01', 'prod-web-02'], number % 8 + 1),
            arrayElement([8080, 8080, 8080, 8080, 8080, 8080, 443, 443], number % 8 + 1),
            arrayElement(['prod-api-01', 'prod-db-01', 'prod-cache-01', 'prod-api-01', 'prod-db-01', 'prod-cache-01', '0.0.0.0', '0.0.0.0'], number % 8 + 1),
            arrayElement([8080, 5432, 6379, 8080, 5432, 6379, 0, 0], number % 8 + 1),
            'tcp',
            'IPv4',
            arrayElement(['outgoing', 'outgoing', 'outgoing', 'outgoing', 'outgoing', 'outgoing', 'incoming', 'incoming'], number % 8 + 1),
            10240 + sipHash64(number, 60) % 104857600,
            10240 + sipHash64(number, 61) % 104857600,
            map('env', 'production'),
            now() - INTERVAL (number % 30) MINUTE
        FROM numbers(8)
        """.trimIndent()
    suspendRunCatching { ClickHouseClient.execute(connSql) }
        .onFailure { logger.warn { "Reseed network_connections failed (non-fatal): ${it.message}" } }
}
