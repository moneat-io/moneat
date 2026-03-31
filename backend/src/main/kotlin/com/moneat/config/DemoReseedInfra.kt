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

import io.ktor.client.statement.bodyAsText
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

private val infraDemoTables =
    listOf(
        "k8s_resources",
        "dbm_queries",
        "debugger_logs",
        "debugger_diagnostics",
        "ndm_devices",
        "ndm_traps",
        "ndm_flows",
        "network_paths",
        "sbom_packages",
    )

internal suspend fun checkFreshInfraDataCount(): Long {
    val query =
        """
        SELECT count() as cnt
        FROM k8s_resources
        WHERE organization_id = $ORG1
            AND collected_at >= now() - INTERVAL 2 HOUR
        """.trimIndent()
    return runCatching {
        val response = ClickHouseClient.execute(query)
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) return 0
        body.trim().toLongOrNull() ?: 0
    }.getOrElse {
        logger.warn { "Failed to check fresh infra demo data (non-fatal): ${it.message}" }
        0
    }
}

internal suspend fun purgeInfraDemoData() {
    for (table in infraDemoTables) {
        runCatching {
            ClickHouseClient.execute(
                "ALTER TABLE $table DELETE WHERE organization_id = $ORG1"
            )
        }.onFailure { logger.warn { "Purge $table failed (non-fatal): ${it.message}" } }
    }
}
// ── Kubernetes Demo Data ──────────────────────────────────────────────

@Suppress("LongMethod")
internal suspend fun reseedKubernetesData(orgId: String) {
    // Pods (15 across namespaces)
    val podsSql =
        """
        INSERT INTO k8s_resources (
            resource_id, organization_id, uid, resource_type, namespace, name,
            cluster_name, cluster_id, status, tags, labels, annotations,
            resource_version, creation_timestamp, collected_at
        )
        SELECT
            generateUUIDv4(),
            $orgId,
            toString(generateUUIDv4()),
            'Pod',
            arrayElement(['default', 'default', 'kube-system', 'default', 'monitoring',
                'default', 'default', 'kube-system', 'default', 'monitoring',
                'default', 'default', 'kube-system', 'default', 'monitoring'], number + 1),
            arrayElement([
                'api-gateway-7f8d9c-abc12', 'api-gateway-7f8d9c-def34',
                'coredns-5d78c9869d-xk2lp', 'user-service-6b4f8d-gh567',
                'prometheus-server-0', 'order-service-8c3e7a-ij890',
                'product-service-4d9f2b-kl123', 'kube-proxy-mn456',
                'payment-service-5e1a3c-op789', 'grafana-6f2b4d-qr012',
                'inventory-service-7g3c5e-st345', 'cache-service-8h4d6f-uv678',
                'etcd-master-01', 'notification-service-9i5e7g-wx901',
                'alertmanager-0'
            ], number + 1),
            'acme-prod-us-east-1',
            'cluster-001',
            arrayElement([
                'Running', 'Running', 'Running', 'Running', 'Running',
                'Running', 'Running', 'Running', 'CrashLoopBackOff', 'Running',
                'Running', 'Running', 'Running', 'Pending', 'Running'
            ], number + 1),
            map('env', 'production', 'region', 'us-east-1'),
            map('app', arrayElement([
                'api-gateway', 'api-gateway', 'coredns', 'user-service',
                'prometheus', 'order-service', 'product-service', 'kube-proxy',
                'payment-service', 'grafana', 'inventory-service', 'cache-service',
                'etcd', 'notification-service', 'alertmanager'
            ], number + 1)),
            map(),
            toString(1000 + number),
            now() - INTERVAL (number * 24 + 48) HOUR,
            now() - INTERVAL (number % 5) MINUTE
        FROM numbers(15)
        """.trimIndent()
    runCatching { ClickHouseClient.execute(podsSql) }
        .onFailure { logger.warn { "Reseed k8s pods failed (non-fatal): ${it.message}" } }

    // Nodes (3)
    val nodesSql =
        """
        INSERT INTO k8s_resources (
            resource_id, organization_id, uid, resource_type, namespace, name,
            cluster_name, cluster_id, status, tags, labels, annotations,
            resource_version, creation_timestamp, collected_at
        )
        SELECT
            generateUUIDv4(),
            $orgId,
            toString(generateUUIDv4()),
            'Node',
            '',
            arrayElement(['ip-10-0-1-101.ec2.internal', 'ip-10-0-2-102.ec2.internal',
                'ip-10-0-3-103.ec2.internal'], number + 1),
            'acme-prod-us-east-1',
            'cluster-001',
            'Ready',
            map('env', 'production', 'region', 'us-east-1'),
            map('node.kubernetes.io/instance-type',
                arrayElement(['m5.xlarge', 'm5.2xlarge', 'r5.xlarge'], number + 1)),
            map(),
            toString(500 + number),
            now() - INTERVAL 30 DAY,
            now() - INTERVAL (number % 3) MINUTE
        FROM numbers(3)
        """.trimIndent()
    runCatching { ClickHouseClient.execute(nodesSql) }
        .onFailure { logger.warn { "Reseed k8s nodes failed (non-fatal): ${it.message}" } }

    // Services (5)
    val servicesSql =
        """
        INSERT INTO k8s_resources (
            resource_id, organization_id, uid, resource_type, namespace, name,
            cluster_name, cluster_id, status, tags, labels, annotations,
            resource_version, creation_timestamp, collected_at
        )
        SELECT
            generateUUIDv4(),
            $orgId,
            toString(generateUUIDv4()),
            'Service',
            'default',
            arrayElement(['api-gateway-svc', 'user-service-svc', 'order-service-svc',
                'product-service-svc', 'payment-service-svc'], number + 1),
            'acme-prod-us-east-1',
            'cluster-001',
            'Active',
            map('env', 'production'),
            map('app', arrayElement(['api-gateway', 'user-service', 'order-service',
                'product-service', 'payment-service'], number + 1)),
            map(),
            toString(600 + number),
            now() - INTERVAL 21 DAY,
            now() - INTERVAL (number % 5) MINUTE
        FROM numbers(5)
        """.trimIndent()
    runCatching { ClickHouseClient.execute(servicesSql) }
        .onFailure { logger.warn { "Reseed k8s services failed (non-fatal): ${it.message}" } }

    // Deployments (5)
    val deploymentsSql =
        """
        INSERT INTO k8s_resources (
            resource_id, organization_id, uid, resource_type, namespace, name,
            cluster_name, cluster_id, status, tags, labels, annotations,
            resource_version, creation_timestamp, collected_at
        )
        SELECT
            generateUUIDv4(),
            $orgId,
            toString(generateUUIDv4()),
            'Deployment',
            'default',
            arrayElement(['api-gateway', 'user-service', 'order-service',
                'product-service', 'payment-service'], number + 1),
            'acme-prod-us-east-1',
            'cluster-001',
            'Available',
            map('env', 'production'),
            map('app', arrayElement(['api-gateway', 'user-service', 'order-service',
                'product-service', 'payment-service'], number + 1)),
            map(),
            toString(700 + number),
            now() - INTERVAL 14 DAY,
            now() - INTERVAL (number % 5) MINUTE
        FROM numbers(5)
        """.trimIndent()
    runCatching { ClickHouseClient.execute(deploymentsSql) }
        .onFailure { logger.warn { "Reseed k8s deployments failed (non-fatal): ${it.message}" } }

    // DaemonSets (2)
    val daemonsetsSql =
        """
        INSERT INTO k8s_resources (
            resource_id, organization_id, uid, resource_type, namespace, name,
            cluster_name, cluster_id, status, tags, labels, annotations,
            resource_version, creation_timestamp, collected_at
        )
        SELECT
            generateUUIDv4(),
            $orgId,
            toString(generateUUIDv4()),
            'DaemonSet',
            'kube-system',
            arrayElement(['kube-proxy', 'datadog-agent'], number + 1),
            'acme-prod-us-east-1',
            'cluster-001',
            'Available',
            map('env', 'production'),
            map('app', arrayElement(['kube-proxy', 'datadog-agent'], number + 1)),
            map(),
            toString(800 + number),
            now() - INTERVAL 28 DAY,
            now() - INTERVAL (number % 3) MINUTE
        FROM numbers(2)
        """.trimIndent()
    runCatching { ClickHouseClient.execute(daemonsetsSql) }
        .onFailure { logger.warn { "Reseed k8s daemonsets failed (non-fatal): ${it.message}" } }

    // ReplicaSets (5)
    val replicasetsSql =
        """
        INSERT INTO k8s_resources (
            resource_id, organization_id, uid, resource_type, namespace, name,
            cluster_name, cluster_id, status, tags, labels, annotations,
            resource_version, creation_timestamp, collected_at
        )
        SELECT
            generateUUIDv4(),
            $orgId,
            toString(generateUUIDv4()),
            'ReplicaSet',
            'default',
            arrayElement(['api-gateway-7f8d9c', 'user-service-6b4f8d',
                'order-service-8c3e7a', 'product-service-4d9f2b',
                'payment-service-5e1a3c'], number + 1),
            'acme-prod-us-east-1',
            'cluster-001',
            'Available',
            map('env', 'production'),
            map('app', arrayElement(['api-gateway', 'user-service', 'order-service',
                'product-service', 'payment-service'], number + 1)),
            map(),
            toString(900 + number),
            now() - INTERVAL 7 DAY,
            now() - INTERVAL (number % 5) MINUTE
        FROM numbers(5)
        """.trimIndent()
    runCatching { ClickHouseClient.execute(replicasetsSql) }
        .onFailure { logger.warn { "Reseed k8s replicasets failed (non-fatal): ${it.message}" } }

    logger.info { "Kubernetes demo data reseed complete" }
}

// ── Database Monitoring Demo Data ──────────────────────────────────────

internal suspend fun reseedDbmData(orgId: String) {
    val queriesSql =
        """
        INSERT INTO dbm_queries (
            query_id, organization_id, db_host, db_system, db_name, db_user,
            query_signature, resource_hash, statement, query_truncated,
            duration_ns, rows_affected, error_code, error_message,
            timestamp, host, env, service, tags
        )
        SELECT
            generateUUIDv4(),
            $orgId,
            arrayElement(['prod-db-01', 'prod-db-01', 'prod-db-01', 'prod-db-02',
                'prod-db-02'], intDiv(number, 4) % 5 + 1),
            'postgresql',
            arrayElement(['acme_users', 'acme_orders', 'acme_products',
                'acme_inventory', 'acme_analytics'], number % 5 + 1),
            'app_readwrite',
            toString(sipHash64(number, 70)),
            toString(sipHash64(number, 71)),
            arrayElement([
                'SELECT u.id, u.email, u.name FROM users u WHERE u.id = $1',
                'SELECT o.*, oi.* FROM orders o JOIN order_items oi ON o.id = oi.order_id WHERE o.user_id = $1 ORDER BY o.created_at DESC LIMIT 50',
                'UPDATE products SET stock_count = stock_count - $1 WHERE id = $2 AND stock_count >= $1',
                'SELECT p.*, c.name as category FROM products p JOIN categories c ON p.category_id = c.id WHERE p.price BETWEEN $1 AND $2 ORDER BY p.popularity DESC',
                'INSERT INTO analytics_events (event_type, user_id, metadata, created_at) VALUES ($1, $2, $3, NOW())',
                'SELECT COUNT(*) as total, DATE_TRUNC(''hour'', created_at) as hour FROM orders WHERE created_at >= NOW() - INTERVAL ''7 days'' GROUP BY hour ORDER BY hour',
                'DELETE FROM sessions WHERE expires_at < NOW()',
                'SELECT i.*, w.name as warehouse FROM inventory i JOIN warehouses w ON i.warehouse_id = w.id WHERE i.product_id = $1',
                'UPDATE users SET last_login_at = NOW(), login_count = login_count + 1 WHERE id = $1',
                'SELECT r.*, u.name as reviewer FROM reviews r JOIN users u ON r.user_id = u.id WHERE r.product_id = $1 ORDER BY r.created_at DESC LIMIT 20',
                'WITH ranked AS (SELECT *, ROW_NUMBER() OVER (PARTITION BY category_id ORDER BY sales DESC) as rn FROM products) SELECT * FROM ranked WHERE rn <= 10',
                'SELECT pg_stat_activity.pid, age(clock_timestamp(), pg_stat_activity.query_start), usename, query FROM pg_stat_activity WHERE state != ''idle'' ORDER BY query_start',
                'ANALYZE orders',
                'VACUUM (VERBOSE) products',
                'SELECT schemaname, tablename, n_live_tup, n_dead_tup, last_autovacuum FROM pg_stat_user_tables ORDER BY n_dead_tup DESC',
                'SELECT c.relname, pg_size_pretty(pg_total_relation_size(c.oid)) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ''public'' ORDER BY pg_total_relation_size(c.oid) DESC',
                'SELECT * FROM pg_stat_user_indexes WHERE idx_scan = 0 AND schemaname = ''public''',
                'CREATE INDEX CONCURRENTLY idx_orders_user_created ON orders (user_id, created_at DESC)',
                'SELECT wait_event_type, wait_event, count(*) FROM pg_stat_activity WHERE state = ''active'' GROUP BY 1, 2 ORDER BY 3 DESC',
                'SELECT * FROM pg_locks WHERE NOT granted'
            ], number % 20 + 1),
            'not_truncated',
            1000000 + sipHash64(number, 72) % 9999000000,
            toInt64(sipHash64(number, 73) % 10000),
            0,
            '',
            now() - INTERVAL (number * 17 % 720) MINUTE,
            arrayElement(['prod-db-01', 'prod-db-02'], number % 2 + 1),
            'production',
            'postgres',
            map('env', 'production')
        FROM numbers(20)
        """.trimIndent()
    runCatching { ClickHouseClient.execute(queriesSql) }
        .onFailure { logger.warn { "Reseed dbm_queries failed (non-fatal): ${it.message}" } }

    logger.info { "DBM demo data reseed complete" }
}

// ── Debugger Demo Data ─────────────────────────────────────────────────

internal suspend fun reseedDebuggerData(orgId: String) {
    // Debugger logs (15 entries)
    val logsSql =
        """
        INSERT INTO debugger_logs (
            log_id, organization_id, service, env, version, debugger_type,
            probe_id, probe_location, message, snapshot, host, timestamp, tags
        )
        SELECT
            generateUUIDv4(),
            $orgId,
            arrayElement(['api-gateway', 'user-service', 'order-service',
                'product-service', 'payment-service'], number % 5 + 1),
            'production',
            '1.3.0',
            arrayElement(['log_probe', 'snapshot', 'log_probe', 'metric_probe',
                'span_decoration', 'log_probe', 'snapshot', 'log_probe',
                'metric_probe', 'log_probe', 'snapshot', 'log_probe',
                'span_decoration', 'log_probe', 'snapshot'], number + 1),
            concat('probe-', toString(100 + number)),
            arrayElement([
                'UserController.java:142', 'OrderService.java:87',
                'PaymentProcessor.java:203', 'ProductSearch.java:56',
                'AuthMiddleware.java:31', 'CacheManager.java:98',
                'DatabasePool.java:167', 'RateLimiter.java:44',
                'NotificationSender.java:72', 'InventoryCheck.java:115',
                'SessionManager.java:89', 'WebSocketHandler.java:63',
                'MetricsCollector.java:28', 'ConfigLoader.java:51',
                'HealthCheck.java:19'
            ], number + 1),
            arrayElement([
                'User login attempt: userId=4521, ip=203.0.113.42',
                'Order total calculated: orderId=ORD-8834, amount=249.99',
                'Payment gateway response: txnId=TXN-7721, status=approved',
                'Search query executed: q="wireless headphones", results=47',
                'JWT token validated: sub=user-4521, exp=2026-03-01',
                'Cache miss: key=product:8834, ttl=3600',
                'Connection pool stats: active=12, idle=8, max=30',
                'Rate limit check: endpoint=/api/v1/search, remaining=847/1000',
                'Email notification queued: template=order_confirmed, recipient=user@example.com',
                'Stock level check: productId=PRD-445, available=23',
                'Session created: sessionId=sess-abc123, userId=4521',
                'WebSocket connection established: clientId=ws-789',
                'Metric recorded: http.request.duration=45ms',
                'Config reloaded: keys_updated=3, source=consul',
                'Health check passed: db=ok, cache=ok, queue=ok'
            ], number + 1),
            '',
            arrayElement(['prod-web-01', 'prod-api-01', 'prod-worker-01',
                'prod-web-02', 'prod-api-01'], number % 5 + 1),
            now() - INTERVAL (number * 23 % 360) MINUTE,
            map('env', 'production')
        FROM numbers(15)
        """.trimIndent()
    runCatching { ClickHouseClient.execute(logsSql) }
        .onFailure { logger.warn { "Reseed debugger_logs failed (non-fatal): ${it.message}" } }

    // Debugger diagnostics (10 probe statuses)
    val diagSql =
        """
        INSERT INTO debugger_diagnostics (
            diagnostic_id, organization_id, service, env, runtime_id,
            probe_id, status, error_message, host, timestamp, tags
        )
        SELECT
            generateUUIDv4(),
            $orgId,
            arrayElement(['api-gateway', 'user-service', 'order-service',
                'product-service', 'payment-service'], number % 5 + 1),
            'production',
            concat('runtime-', toString(sipHash64(number, 80) % 1000)),
            concat('probe-', toString(100 + number)),
            arrayElement([
                'installed', 'emitting', 'installed', 'emitting', 'received',
                'installed', 'error', 'emitting', 'installed', 'blocked'
            ], number + 1),
            arrayElement([
                '', '', '', '', '',
                '', 'Probe bytecode verification failed: unsupported class version',
                '', '', 'Rate limit exceeded: max 5 probes per service'
            ], number + 1),
            arrayElement(['prod-web-01', 'prod-api-01', 'prod-worker-01',
                'prod-web-02', 'prod-api-01'], number % 5 + 1),
            now() - INTERVAL (number * 11 % 180) MINUTE,
            map('env', 'production')
        FROM numbers(10)
        """.trimIndent()
    runCatching { ClickHouseClient.execute(diagSql) }
        .onFailure { logger.warn { "Reseed debugger_diagnostics failed (non-fatal): ${it.message}" } }

    logger.info { "Debugger demo data reseed complete" }
}

// ── Network Device Monitoring Demo Data ────────────────────────────────

@Suppress("LongMethod")
internal suspend fun reseedNdmData(orgId: String) {
    // Network devices (8)
    val devicesSql =
        """
        INSERT INTO ndm_devices (
            device_id_hash, organization_id, device_id, ip_address, hostname,
            vendor, model, os_version, device_type, status, reachability,
            snmp_version, tags, collected_at
        )
        SELECT
            generateUUIDv4(),
            $orgId,
            concat('device-', toString(number + 1)),
            arrayElement([
                '10.0.1.1', '10.0.1.2', '10.0.2.1', '10.0.2.2',
                '10.0.3.1', '10.0.3.2', '10.0.4.1', '10.0.4.2'
            ], number + 1),
            arrayElement([
                'core-sw-01', 'core-sw-02', 'dist-sw-east-01', 'dist-sw-west-01',
                'edge-fw-01', 'edge-fw-02', 'wifi-ap-floor1', 'wifi-ap-floor2'
            ], number + 1),
            arrayElement([
                'Cisco', 'Cisco', 'Juniper', 'Juniper',
                'Palo Alto', 'Palo Alto', 'Aruba', 'Aruba'
            ], number + 1),
            arrayElement([
                'Catalyst 9300', 'Catalyst 9300', 'EX4300', 'EX4300',
                'PA-3260', 'PA-3260', 'AP-535', 'AP-535'
            ], number + 1),
            arrayElement([
                'IOS-XE 17.9.4', 'IOS-XE 17.9.4', 'Junos 23.2R1',
                'Junos 23.2R1', 'PAN-OS 11.1.2', 'PAN-OS 11.1.2',
                'ArubaOS 8.10', 'ArubaOS 8.10'
            ], number + 1),
            arrayElement([
                'switch', 'switch', 'switch', 'switch',
                'firewall', 'firewall', 'access_point', 'access_point'
            ], number + 1),
            arrayElement(['up', 'up', 'up', 'up', 'up', 'up', 'up', 'down'], number + 1),
            arrayElement([
                'reachable', 'reachable', 'reachable', 'reachable',
                'reachable', 'reachable', 'reachable', 'unreachable'
            ], number + 1),
            'v2c',
            map('env', 'production', 'site', 'dc-east-1'),
            now() - INTERVAL (number % 10) MINUTE
        FROM numbers(8)
        """.trimIndent()
    runCatching { ClickHouseClient.execute(devicesSql) }
        .onFailure { logger.warn { "Reseed ndm_devices failed (non-fatal): ${it.message}" } }

    // SNMP traps (12)
    val trapsSql =
        """
        INSERT INTO ndm_traps (
            trap_id, organization_id, device_ip, oid, severity,
            message, variables, received_at
        )
        SELECT
            generateUUIDv4(),
            $orgId,
            arrayElement([
                '10.0.1.1', '10.0.1.2', '10.0.2.1', '10.0.2.2',
                '10.0.3.1', '10.0.3.2', '10.0.1.1', '10.0.2.1',
                '10.0.3.1', '10.0.4.2', '10.0.1.1', '10.0.3.2'
            ], number + 1),
            arrayElement([
                '1.3.6.1.6.3.1.1.5.3', '1.3.6.1.6.3.1.1.5.4',
                '1.3.6.1.4.1.9.9.43.2.0.1', '1.3.6.1.6.3.1.1.5.3',
                '1.3.6.1.4.1.25461.2.1.3.2.0.1',
                '1.3.6.1.4.1.25461.2.1.3.2.0.2',
                '1.3.6.1.2.1.47.2.0.1', '1.3.6.1.6.3.1.1.5.4',
                '1.3.6.1.4.1.25461.2.1.3.2.0.3',
                '1.3.6.1.6.3.1.1.5.3', '1.3.6.1.2.1.10.166.3.0.1',
                '1.3.6.1.4.1.25461.2.1.3.2.0.1'
            ], number + 1),
            arrayElement([
                'warning', 'info', 'critical', 'warning',
                'critical', 'warning', 'info', 'info',
                'warning', 'critical', 'info', 'critical'
            ], number + 1),
            arrayElement([
                'Interface GigabitEthernet1/0/24 link down',
                'Interface GigabitEthernet1/0/24 link up',
                'Configuration changed by admin via SSH',
                'Interface xe-0/0/12 link down - fiber removed',
                'Threat detected: command-and-control traffic blocked',
                'GlobalProtect: VPN tunnel established from 203.0.113.50',
                'Fan tray 2 RPM below threshold',
                'Interface xe-0/0/12 link up',
                'IPS signature match: CVE-2024-21762 exploit attempt',
                'Access point wifi-ap-floor2 unreachable',
                'MPLS LSP path change detected on tunnel0',
                'HA failover: primary to secondary firewall'
            ], number + 1),
            map('ifIndex', toString(number + 1)),
            now() - INTERVAL (number * 31 % 480) MINUTE
        FROM numbers(12)
        """.trimIndent()
    runCatching { ClickHouseClient.execute(trapsSql) }
        .onFailure { logger.warn { "Reseed ndm_traps failed (non-fatal): ${it.message}" } }

    // Network flows (20)
    val flowsSql =
        """
        INSERT INTO ndm_flows (
            flow_id, organization_id, src_ip, dst_ip, src_port, dst_port,
            protocol, bytes, packets, direction, flow_type, tags, sampled_at
        )
        SELECT
            generateUUIDv4(),
            $orgId,
            arrayElement([
                '10.0.1.50', '10.0.1.51', '10.0.2.30', '10.0.1.50',
                '203.0.113.10', '10.0.2.30', '10.0.1.50', '10.0.3.20',
                '10.0.1.51', '10.0.2.30', '203.0.113.15', '10.0.1.50',
                '10.0.2.30', '10.0.3.20', '10.0.1.51', '203.0.113.22',
                '10.0.1.50', '10.0.2.30', '10.0.3.20', '10.0.1.51'
            ], number + 1),
            arrayElement([
                '10.0.2.30', '10.0.3.20', '10.0.1.50', '10.0.3.20',
                '10.0.1.50', '10.0.3.20', '203.0.113.10', '10.0.1.51',
                '10.0.2.30', '10.0.1.51', '10.0.1.51', '10.0.2.30',
                '10.0.1.51', '10.0.1.50', '10.0.3.20', '10.0.1.50',
                '10.0.3.20', '10.0.1.50', '10.0.2.30', '10.0.2.30'
            ], number + 1),
            toUInt16(10000 + sipHash64(number, 90) % 55535),
            arrayElement([
                toUInt16(443), toUInt16(8080), toUInt16(5432), toUInt16(6379),
                toUInt16(80), toUInt16(9090), toUInt16(443), toUInt16(8080),
                toUInt16(5432), toUInt16(6379), toUInt16(443), toUInt16(8080),
                toUInt16(5432), toUInt16(6379), toUInt16(9090), toUInt16(80),
                toUInt16(443), toUInt16(8080), toUInt16(5432), toUInt16(6379)
            ], number + 1),
            arrayElement(['TCP', 'TCP', 'TCP', 'TCP', 'TCP',
                'TCP', 'TCP', 'TCP', 'TCP', 'TCP',
                'TCP', 'TCP', 'TCP', 'TCP', 'UDP',
                'TCP', 'TCP', 'TCP', 'TCP', 'TCP'], number + 1),
            toUInt64(1024 + sipHash64(number, 91) % 104857600),
            toUInt64(10 + sipHash64(number, 92) % 100000),
            arrayElement(['ingress', 'egress'], number % 2 + 1),
            arrayElement(['netflow', 'sflow', 'netflow'], number % 3 + 1),
            map('env', 'production'),
            now() - INTERVAL (number * 13 % 360) MINUTE
        FROM numbers(20)
        """.trimIndent()
    runCatching { ClickHouseClient.execute(flowsSql) }
        .onFailure { logger.warn { "Reseed ndm_flows failed (non-fatal): ${it.message}" } }

    // Network paths (6)
    val pathsSql =
        """
        INSERT INTO network_paths (
            path_id, organization_id, source, destination, hops, hop_rtts,
            tags, collected_at
        )
        SELECT
            generateUUIDv4(),
            $orgId,
            arrayElement([
                '10.0.1.50', '10.0.1.50', '10.0.2.30',
                '10.0.1.50', '10.0.3.20', '10.0.2.30'
            ], number + 1),
            arrayElement([
                '10.0.2.30', '10.0.3.20', '10.0.3.20',
                '203.0.113.10', '10.0.1.50', '203.0.113.10'
            ], number + 1),
            arrayElement([
                ['10.0.1.1', '10.0.2.1', '10.0.2.30'],
                ['10.0.1.1', '10.0.2.1', '10.0.3.1', '10.0.3.20'],
                ['10.0.2.1', '10.0.3.1', '10.0.3.20'],
                ['10.0.1.1', '10.0.3.1', '203.0.113.1', '203.0.113.10'],
                ['10.0.3.1', '10.0.2.1', '10.0.1.1', '10.0.1.50'],
                ['10.0.2.1', '10.0.3.1', '203.0.113.1', '203.0.113.10']
            ], number + 1),
            arrayElement([
                [0.5, 1.2, 0.8],
                [0.5, 1.1, 2.3, 1.5],
                [0.6, 1.8, 0.9],
                [0.4, 1.5, 8.2, 12.1],
                [0.7, 1.3, 0.9, 0.5],
                [0.6, 1.9, 7.8, 11.4]
            ], number + 1),
            map('env', 'production'),
            now() - INTERVAL (number * 47 % 360) MINUTE
        FROM numbers(6)
        """.trimIndent()
    runCatching { ClickHouseClient.execute(pathsSql) }
        .onFailure { logger.warn { "Reseed network_paths failed (non-fatal): ${it.message}" } }

    logger.info { "NDM demo data reseed complete" }
}

// ── SBOM Demo Data ─────────────────────────────────────────────────────

internal suspend fun reseedSbomData(orgId: String) {
    val sbomSql =
        """
        INSERT INTO sbom_packages (
            package_id, organization_id, host, container_id, image_name,
            package_name, package_version, package_type, cve_ids, tags,
            collected_at
        )
        SELECT
            generateUUIDv4(),
            $orgId,
            arrayElement(['prod-web-01', 'prod-api-01', 'prod-db-01',
                'prod-worker-01', 'prod-web-02'], number % 5 + 1),
            substring(toString(sipHash64(number, 100)), 1, 12),
            arrayElement([
                'acme/api-gateway:1.3.0', 'acme/user-service:1.2.8',
                'acme/order-service:2.0.3', 'acme/product-service:1.4.1',
                'acme/payment-service:1.1.5'
            ], number % 5 + 1),
            arrayElement([
                'openssl', 'libcurl', 'zlib', 'glibc', 'libpq',
                'jackson-databind', 'spring-core', 'netty-handler', 'log4j-core', 'guava',
                'express', 'lodash', 'axios', 'jsonwebtoken', 'pg',
                'numpy', 'requests', 'cryptography', 'pillow', 'django',
                'golang.org/x/crypto', 'github.com/gin-gonic/gin',
                'google.golang.org/grpc', 'github.com/lib/pq',
                'github.com/prometheus/client_golang'
            ], number + 1),
            arrayElement([
                '3.1.4', '8.4.0', '1.3.1', '2.38', '16.1',
                '2.15.3', '6.1.4', '4.1.100', '2.20.0', '32.1.3',
                '4.18.2', '4.17.21', '1.6.7', '9.0.2', '8.11.3',
                '1.26.3', '2.31.0', '41.0.7', '10.2.0', '5.0.1',
                'v0.17.0', 'v1.9.1', 'v1.60.1', 'v1.10.9', 'v1.18.0'
            ], number + 1),
            arrayElement([
                'deb', 'deb', 'deb', 'deb', 'deb',
                'jar', 'jar', 'jar', 'jar', 'jar',
                'npm', 'npm', 'npm', 'npm', 'npm',
                'pip', 'pip', 'pip', 'pip', 'pip',
                'go', 'go', 'go', 'go', 'go'
            ], number + 1),
            arrayElement([
                ['CVE-2024-5535', 'CVE-2024-4603'],
                ['CVE-2024-2398'],
                emptyArrayString(),
                ['CVE-2023-6246', 'CVE-2023-6779'],
                emptyArrayString(),
                ['CVE-2023-35116'],
                emptyArrayString(),
                ['CVE-2023-44487'],
                emptyArrayString(),
                emptyArrayString(),
                emptyArrayString(),
                ['CVE-2021-23337'],
                emptyArrayString(),
                ['CVE-2022-23529'],
                emptyArrayString(),
                emptyArrayString(),
                ['CVE-2024-35195'],
                ['CVE-2024-26130'],
                emptyArrayString(),
                ['CVE-2024-27351'],
                ['CVE-2024-45337', 'CVE-2024-45338'],
                emptyArrayString(),
                emptyArrayString(),
                emptyArrayString(),
                emptyArrayString()
            ], number + 1),
            map('env', 'production'),
            now() - INTERVAL (number * 19 % 720) MINUTE
        FROM numbers(25)
        """.trimIndent()
    runCatching { ClickHouseClient.execute(sbomSql) }
        .onFailure { logger.warn { "Reseed sbom_packages failed (non-fatal): ${it.message}" } }

    logger.info { "SBOM demo data reseed complete" }
}
