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

package com.moneat.monitor.services

import com.moneat.monitor.models.CatalogChangeEvent
import com.moneat.monitor.models.CatalogMetaItem
import com.moneat.monitor.models.CatalogOwner
import com.moneat.monitor.models.CatalogPosture
import com.moneat.monitor.models.CatalogRelationship
import com.moneat.monitor.models.CatalogResource
import com.moneat.monitor.models.CatalogResourceTelemetry
import com.moneat.monitor.models.CatalogVulnerabilityCounts
import com.moneat.monitor.models.ContainerMetricDataPoint
import com.moneat.monitor.models.HostData
import com.moneat.monitor.models.LatestMetrics
import com.moneat.monitor.models.MetricDataPoint
import com.moneat.monitor.models.ResourceOwnershipClaim
import com.moneat.monitor.models.ResourceTelemetryLine
import com.moneat.monitor.models.ResourceTelemetryMetric
import com.moneat.monitor.models.ResourceTelemetryPoint
import com.moneat.monitor.models.ResourceTelemetryResponse
import com.moneat.monitor.repositories.NoopResourceOwnershipRepository
import com.moneat.monitor.repositories.ResourceOwnershipRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt
import kotlin.time.Instant

private const val DEFAULT_CATALOG_LIMIT = 200
private const val MAX_CATALOG_LIMIT = 500
private const val SERVICE_WARN_ERROR_RATE = 1.0
private const val SERVICE_CRITICAL_ERROR_RATE = 5.0
private const val PERCENT_SCALE = 100.0
private const val ZERO_PERCENT = 0
private const val FULL_PERCENT = 100
private const val BYTES_PER_KIB = 1024.0
private const val CATALOG_UNKNOWN_TIMESTAMP = "1970-01-01T00:00:00.000Z"
private const val CLOUD_ON_PREM = "on-prem"
private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_HOUR = 3600.0
private const val MIN_TELEMETRY_RANGE_SECONDS = 300L
private const val MAX_TELEMETRY_RANGE_SECONDS = 60L * 60 * 24 * 31
private const val RANGE_1H_SECONDS = 3600L
private const val RANGE_6H_SECONDS = 21_600L
private const val RANGE_24H_SECONDS = 86_400L
private const val RANGE_7D_SECONDS = 604_800L
private const val INTERVAL_10S = 10
private const val INTERVAL_1M = 60
private const val INTERVAL_5M = 300
private const val INTERVAL_30M = 1800
private const val INTERVAL_1H = 3600
private const val MAX_RELATIONSHIPS_PER_GROUP = 12
private val APP_LABEL_PREFIXES = listOf("label:app.kubernetes.io/name:", "label:app:")
private const val HEALTH_UNKNOWN = "unknown"
private const val MAX_CHANGES_PER_RESOURCE = 8
private const val CHANGE_KIND_DEPLOY = "deploy"
private const val DEPLOY_ACTOR_FALLBACK = "unknown"

data class ResourceTelemetrySelector(
    val hostId: Int? = null,
    val service: String? = null,
    val containerHost: String? = null,
    val containerName: String? = null,
)

data class ResourceTelemetryRequest(
    val organizationIds: List<Int>,
    val kind: String,
    val selector: ResourceTelemetrySelector,
    val rangeSeconds: Long,
    val demoEpochMs: Long? = null,
)

private data class ResourceRelationshipContext(
    val serviceByName: Map<String, CatalogResource>,
    val hostByName: Map<String, CatalogResource>,
    val containersByHostId: Map<String, List<CatalogResource>>,
    val downstreamByService: Map<String, List<String>>,
    val upstreamByService: Map<String, List<String>>,
)

private data class ServiceEdgeIndex(
    val downstreamByService: Map<String, List<String>>,
    val upstreamByService: Map<String, List<String>>,
)

class ResourceCatalogService(
    private val monitorService: MonitorService,
    private val queryClient: ResourceCatalogQueryClient = ClickHouseResourceCatalogQueryClient(),
    private val ownershipRepository: ResourceOwnershipRepository = NoopResourceOwnershipRepository,
    private val securityReader: ResourceSecurityReader = DefaultResourceSecurityReader(),
) {
    suspend fun listResources(
        organizationIds: List<Int>,
        limit: Int = DEFAULT_CATALOG_LIMIT,
        demoEpochMs: Long? = null,
    ): List<CatalogResource> {
        if (organizationIds.isEmpty()) return emptyList()

        val boundedLimit = limit.coerceIn(1, MAX_CATALOG_LIMIT)
        val hosts = organizationIds.flatMap { monitorService.listHosts(it) }
        val metricsByHost = latestMetricsByHost(organizationIds, hosts, demoEpochMs)

        val resources = buildList {
            addAll(queryClient.listApmServices(organizationIds, boundedLimit).mapNotNull(::serviceResource))
            addAll(hosts.map { host -> hostResource(host, metricsByHost[host.id]) })
            addAll(queryClient.listContainers(organizationIds, boundedLimit).mapNotNull(::containerResource))
            addAll(queryClient.listKubernetesPods(organizationIds, boundedLimit).mapNotNull(::podResource))
            addAll(queryClient.listNetworkDevices(organizationIds, boundedLimit).mapNotNull(::networkDeviceResource))
            addAll(queryClient.listCloudResources(organizationIds, boundedLimit).mapNotNull(::cloudResource))
        }.take(boundedLimit)

        val resourcesWithRelationships = attachRelationships(organizationIds, resources)
        val resourcesWithChanges = attachChanges(organizationIds, resourcesWithRelationships)
        val resourcesWithOwnership = attachOwnership(organizationIds, resourcesWithChanges)
        return attachSecurity(organizationIds, resourcesWithOwnership)
    }

    /**
     * Real security posture for each resource: open vulnerability severity counts and the most severe
     * findings, the SBOM component count, and compliance posture. Vulnerabilities/components join by
     * dimension — hosts by hostname, containers by image, services by name; compliance joins hosts and
     * cloud resources by name. Kinds (or resources) with no security data keep honest-empty fields.
     */
    private suspend fun attachSecurity(
        organizationIds: List<Int>,
        resources: List<CatalogResource>,
    ): List<CatalogResource> {
        if (resources.isEmpty()) return resources
        val snapshot = securityReader.read(organizationIds)
        if (snapshot.isEmpty()) return resources

        val vulnByKey = snapshot.vulnerabilities.associateBy { it.scope to it.key }
        val componentsByKey = snapshot.components.associate { (it.scope to it.key) to it.components }
        val postureByName = snapshot.compliance
            .groupBy { it.resourceName }
            .mapValues { (_, rows) ->
                rows.sortedBy { it.framework }.map { CatalogPosture(label = it.framework, pass = it.passed) }
            }

        return resources.map { resource -> resource.withSecurity(vulnByKey, componentsByKey, postureByName) }
    }

    private fun CatalogResource.withSecurity(
        vulnByKey: Map<Pair<SecurityScope, String>, ResourceVulnAggregate>,
        componentsByKey: Map<Pair<SecurityScope, String>, Int>,
        postureByName: Map<String, List<CatalogPosture>>,
    ): CatalogResource {
        val scopeKey = securityScopeKey()
        val aggregate = scopeKey?.let { vulnByKey[it] }
        val components = scopeKey?.let { componentsByKey[it] } ?: 0
        val posture = compliancePostureKey()?.let { postureByName[it] }.orEmpty()
        if (aggregate == null && components == 0 && posture.isEmpty()) return this
        return copy(
            vulns = aggregate?.toCounts() ?: vulns,
            findings = aggregate?.topFindings ?: findings,
            sbomComponents = if (components > 0) components else sbomComponents,
            posture = posture.ifEmpty { this.posture },
        )
    }

    /** The vulnerability/SBOM join key for this resource, or null when the kind has no security source. */
    private fun CatalogResource.securityScopeKey(): Pair<SecurityScope, String>? =
        when (kind) {
            "host" -> SecurityScope.HOST to hostSecurityKey()
            "container" -> metaValue("Image")?.lowercase()?.let { SecurityScope.IMAGE to it }
            "service" -> SecurityScope.SERVICE to name.lowercase()
            else -> null
        }

    /** The compliance-findings join key (by resource name) for hosts and cloud resources. */
    private fun CatalogResource.compliancePostureKey(): String? =
        when (kind) {
            "host" -> hostSecurityKey()
            "cloud" -> name.lowercase()
            else -> null
        }

    private fun CatalogResource.hostSecurityKey(): String =
        (metaValue("Hostname") ?: name).lowercase()

    private fun CatalogResource.metaValue(label: String): String? =
        metadata.firstOrNull { it.label == label }?.value?.takeIf { it.isNotBlank() }

    private fun ResourceVulnAggregate.toCounts(): CatalogVulnerabilityCounts =
        CatalogVulnerabilityCounts(critical = critical, high = high, medium = medium, low = low)

    /**
     * Real ownership for each resource: a persisted claim wins, otherwise the owning
     * team is derived from a `team:` tag when present. Resources with neither stay
     * unowned (null) rather than carrying a fabricated owner.
     */
    private fun attachOwnership(
        organizationIds: List<Int>,
        resources: List<CatalogResource>,
    ): List<CatalogResource> {
        val claims = HashMap<String, CatalogOwner>()
        for (organizationId in organizationIds) {
            claims.putAll(ownershipRepository.listByOrganization(organizationId))
        }
        return resources.map { resource ->
            val owner = claims[resource.id] ?: teamTagOwner(resource)
            if (owner == null) resource else resource.copy(owner = owner)
        }
    }

    private fun teamTagOwner(resource: CatalogResource): CatalogOwner? {
        val team = resource.tags.firstTagValue("team") ?: return null
        return CatalogOwner(team = team, oncall = "", slack = "", repo = "")
    }

    /** Persist (create or replace) an ownership claim for one catalog resource. */
    suspend fun claimOwnership(organizationId: Int, claim: ResourceOwnershipClaim, actor: String): CatalogOwner? {
        val requestedResource = listResources(listOf(organizationId), limit = MAX_CATALOG_LIMIT)
            .firstOrNull { resource -> resource.id == claim.resourceId }
            ?: return null
        val owner = CatalogOwner(
            team = claim.team,
            oncall = claim.oncall,
            slack = claim.slack,
            repo = claim.repo,
        )
        ownershipRepository.upsert(organizationId, requestedResource.id, owner, actor)
        return owner
    }

    /**
     * Real change history for service resources: deploy events derived from versioned
     * spans (one event per observed version, timestamped at first appearance). Sets each
     * service's most recent deploy as its [CatalogResource.lastChange]. Kinds without a
     * change source keep an empty feed rather than a fabricated one.
     */
    private suspend fun attachChanges(
        organizationIds: List<Int>,
        resources: List<CatalogResource>,
    ): List<CatalogResource> {
        if (resources.none { it.kind == "service" }) return resources
        val rows = queryClient.serviceDeployments(organizationIds)
        if (rows.isEmpty()) return resources

        val changesByService = deploymentChangesByService(rows)

        return resources.map { resource ->
            if (resource.kind != "service") return@map resource
            val changes = changesByService[resource.name.lowercase()].orEmpty()
            if (changes.isEmpty()) resource else resource.copy(changes = changes, lastChange = changes.first().ts)
        }
    }

    private fun deploymentChangesByService(rows: List<JsonObject>): Map<String, List<CatalogChangeEvent>> {
        val changesByService = HashMap<String, MutableList<CatalogChangeEvent>>()
        for (row in rows) {
            val (service, change) = row.toDeployChange() ?: continue
            val changes = changesByService.getOrPut(service.lowercase()) { mutableListOf() }
            if (changes.size < MAX_CHANGES_PER_RESOURCE) {
                changes.add(change)
            }
        }
        return changesByService
    }

    private fun JsonObject.toDeployChange(): Pair<String, CatalogChangeEvent>? {
        val service = s("service") ?: return null
        val version = s("version") ?: return null
        val deployedAt = s("deploy_at") ?: return null
        return service to CatalogChangeEvent(
            ts = deployedAt,
            kind = CHANGE_KIND_DEPLOY,
            summary = "Deployed $version",
            actor = s("deployer") ?: DEPLOY_ACTOR_FALLBACK,
        )
    }

    /**
     * Resolve real dependency edges over the materialized resource set so every related
     * row carries a [CatalogRelationship.targetId] that pivots to an actual catalog
     * resource. Sources: APM service-map edges (service<->service), the container's
     * reported host (container<->host), and the pod's `app` label (pod->service). Kinds
     * with no discoverable edge keep an empty list rather than a fabricated one.
     */
    private suspend fun attachRelationships(
        organizationIds: List<Int>,
        resources: List<CatalogResource>,
    ): List<CatalogResource> {
        val context = relationshipContext(organizationIds, resources)
        return resources.map { resource ->
            val relationships = relationshipsFor(resource, context)
            if (relationships.isEmpty()) resource else resource.copy(relationships = relationships)
        }
    }

    private suspend fun relationshipContext(
        organizationIds: List<Int>,
        resources: List<CatalogResource>,
    ): ResourceRelationshipContext {
        val serviceByName = resources.filter { it.kind == "service" }.associateBy { it.name.lowercase() }
        val hostByName = hostIndex(resources)
        val serviceEdges = serviceEdgeIndex(organizationIds, serviceByName.isNotEmpty())
        return ResourceRelationshipContext(
            serviceByName = serviceByName,
            hostByName = hostByName,
            containersByHostId = containersByHostId(resources, hostByName),
            downstreamByService = serviceEdges.downstreamByService,
            upstreamByService = serviceEdges.upstreamByService,
        )
    }

    private fun hostIndex(resources: List<CatalogResource>): Map<String, CatalogResource> {
        val hostByName = HashMap<String, CatalogResource>()
        for (host in resources.filter { it.kind == "host" }) {
            hostByName.putIfAbsent(host.name.lowercase(), host)
            host.metadata.firstOrNull { it.label == "Hostname" }
                ?.let { hostByName.putIfAbsent(it.value.lowercase(), host) }
        }
        return hostByName
    }

    private fun containersByHostId(
        resources: List<CatalogResource>,
        hostByName: Map<String, CatalogResource>,
    ): Map<String, List<CatalogResource>> {
        val containersByHostId = HashMap<String, MutableList<CatalogResource>>()
        for (container in resources.filter { it.kind == "container" }) {
            val hostName = container.metadata.firstOrNull { it.label == "Host" }?.value?.lowercase() ?: continue
            val host = hostByName[hostName] ?: continue
            containersByHostId.getOrPut(host.id) { mutableListOf() }.add(container)
        }
        return containersByHostId
    }

    private suspend fun serviceEdgeIndex(organizationIds: List<Int>, hasServices: Boolean): ServiceEdgeIndex {
        val downstreamByService = HashMap<String, MutableList<String>>()
        val upstreamByService = HashMap<String, MutableList<String>>()
        if (!hasServices) return ServiceEdgeIndex(downstreamByService, upstreamByService)

        for (edge in queryClient.serviceEdges(organizationIds)) {
            val from = edge.s("from_service") ?: continue
            val to = edge.s("to_service") ?: continue
            val fromKey = from.lowercase()
            val toKey = to.lowercase()
            if (fromKey == toKey) continue
            downstreamByService.getOrPut(fromKey) { mutableListOf() }.add(to)
            upstreamByService.getOrPut(toKey) { mutableListOf() }.add(from)
        }
        return ServiceEdgeIndex(downstreamByService, upstreamByService)
    }

    private fun relationshipsFor(
        resource: CatalogResource,
        context: ResourceRelationshipContext,
    ): List<CatalogRelationship> =
        when (resource.kind) {
            "service" -> serviceRelationships(
                resource,
                context.serviceByName,
                context.downstreamByService,
                context.upstreamByService,
            )
            "container" -> containerHostRelationship(resource, context.hostByName)?.let { listOf(it) }.orEmpty()
            "host" -> context.containersByHostId[resource.id].orEmpty()
                .take(MAX_RELATIONSHIPS_PER_GROUP)
                .map { relationshipTo("Runs", it) }
            "pod" -> podService(resource, context.serviceByName)
                ?.let { listOf(relationshipTo("Part of", it)) }
                .orEmpty()
            else -> emptyList()
        }

    private fun serviceRelationships(
        resource: CatalogResource,
        serviceByName: Map<String, CatalogResource>,
        downstreamByService: Map<String, List<String>>,
        upstreamByService: Map<String, List<String>>,
    ): List<CatalogRelationship> {
        val key = resource.name.lowercase()
        val dependsOn = downstreamByService[key].orEmpty()
            .take(MAX_RELATIONSHIPS_PER_GROUP)
            .map { peerRelationship("Depends on", it, serviceByName) }
        val dependedOnBy = upstreamByService[key].orEmpty()
            .take(MAX_RELATIONSHIPS_PER_GROUP)
            .map { peerRelationship("Depended on by", it, serviceByName) }
        return dependsOn + dependedOnBy
    }

    private fun peerRelationship(
        relation: String,
        peerName: String,
        serviceByName: Map<String, CatalogResource>,
    ): CatalogRelationship {
        val peer = serviceByName[peerName.lowercase()]
        return CatalogRelationship(
            relation = relation,
            name = peerName,
            kind = "service",
            health = peer?.health ?: HEALTH_UNKNOWN,
            targetId = peer?.id,
        )
    }

    private fun relationshipTo(relation: String, target: CatalogResource): CatalogRelationship =
        CatalogRelationship(
            relation = relation,
            name = target.name,
            kind = target.kind,
            health = target.health,
            targetId = target.id,
        )

    /**
     * "Runs on" for a container, off its reported host. The host name is real data even
     * when that host isn't monitored, so the row is always shown; it only resolves a
     * pivotable [CatalogRelationship.targetId] when the host exists in the catalog.
     */
    private fun containerHostRelationship(
        container: CatalogResource,
        hostByName: Map<String, CatalogResource>,
    ): CatalogRelationship? {
        val hostName = container.metadata.firstOrNull { it.label == "Host" }?.value ?: return null
        val host = hostByName[hostName.lowercase()]
        return CatalogRelationship(
            relation = "Runs on",
            name = host?.name ?: hostName,
            kind = "host",
            health = host?.health ?: HEALTH_UNKNOWN,
            targetId = host?.id,
        )
    }

    private fun podService(pod: CatalogResource, serviceByName: Map<String, CatalogResource>): CatalogResource? {
        val appName = pod.tags.firstNotNullOfOrNull { tag ->
            APP_LABEL_PREFIXES.firstNotNullOfOrNull { prefix ->
                if (tag.startsWith(prefix)) tag.removePrefix(prefix).takeIf { it.isNotBlank() } else null
            }
        } ?: return null
        return serviceByName[appName.lowercase()]
    }

    /**
     * Real over-time telemetry for one catalog resource. Dispatches by kind to the
     * underlying store — host/container metric rollups and APM hourly span stats —
     * and only returns metrics whose source produced at least one sample. No history
     * is synthesized; an empty result means the source has no data for the range.
     */
    suspend fun getResourceTelemetry(request: ResourceTelemetryRequest): ResourceTelemetryResponse {
        val boundedRange = request.rangeSeconds.coerceIn(MIN_TELEMETRY_RANGE_SECONDS, MAX_TELEMETRY_RANGE_SECONDS)
        val metrics = if (request.organizationIds.isEmpty()) {
            emptyList()
        } else {
            when (request.kind) {
                "host" -> hostTelemetry(
                    request.organizationIds,
                    request.selector.hostId,
                    boundedRange,
                    request.demoEpochMs,
                )
                "container" -> containerTelemetry(
                    request.organizationIds,
                    request.selector.containerHost,
                    request.selector.containerName,
                    boundedRange,
                    request.demoEpochMs,
                )
                "service" -> serviceTelemetry(request.organizationIds, request.selector.service, boundedRange)
                else -> emptyList()
            }
        }
        return ResourceTelemetryResponse(
            kind = request.kind,
            rangeSeconds = boundedRange,
            intervalSeconds = telemetryInterval(boundedRange),
            metrics = metrics,
        )
    }

    private suspend fun hostTelemetry(
        organizationIds: List<Int>,
        hostId: Int?,
        rangeSeconds: Long,
        demoEpochMs: Long?,
    ): List<ResourceTelemetryMetric> {
        if (hostId == null) return emptyList()
        val authorized = organizationIds.any { orgId -> monitorService.listHosts(orgId).any { it.id == hostId } }
        if (!authorized) return emptyList()
        val nowSeconds = telemetryNowSeconds(demoEpochMs)
        val points = monitorService
            .getHistoricalMetrics(hostId, nowSeconds - rangeSeconds, nowSeconds, null)
            .dataPoints
        fun col(value: (MetricDataPoint) -> Double?): List<Pair<Long, Double?>> =
            points.map { (it.timestamp * MILLIS_PER_SECOND) to value(it) }
        return buildList {
            metricOrNull(
                "cpu",
                "CPU utilization",
                "%",
                listOf(lineOrNull("CPU", col { it.cpuPercent?.toDouble() })),
            )
                ?.let { add(it) }
            metricOrNull(
                "mem",
                "Memory utilization",
                "%",
                listOf(lineOrNull("Memory", col { it.memPercent?.toDouble() })),
            )
                ?.let { add(it) }
            metricOrNull("disk", "Disk usage", "%", listOf(lineOrNull("Disk", col { it.diskPercent?.toDouble() })))
                ?.let { add(it) }
            metricOrNull(
                "load",
                "Load average",
                "",
                listOf(
                    lineOrNull("1m", col { it.load1?.toDouble() }),
                    lineOrNull("5m", col { it.load5?.toDouble() }),
                    lineOrNull("15m", col { it.load15?.toDouble() }),
                ),
            )?.let { add(it) }
            metricOrNull(
                "network",
                "Network throughput",
                "bytes/s",
                listOf(
                    lineOrNull("Received", col { it.netRecvBytes?.toDouble() }),
                    lineOrNull("Sent", col { it.netSentBytes?.toDouble() }),
                ),
            )?.let { add(it) }
        }
    }

    private suspend fun containerTelemetry(
        organizationIds: List<Int>,
        containerHost: String?,
        containerName: String?,
        rangeSeconds: Long,
        demoEpochMs: Long?,
    ): List<ResourceTelemetryMetric> {
        if (containerHost.isNullOrBlank() || containerName.isNullOrBlank()) return emptyList()
        val normalizedContainerHost = containerHost.trim().lowercase()
        val host = organizationIds
            .flatMap { monitorService.listHosts(it) }
            .firstOrNull {
                it.hostname.lowercase() == normalizedContainerHost ||
                    it.displayName?.trim()?.lowercase() == normalizedContainerHost
            }
            ?: return emptyList()
        val nowSeconds = telemetryNowSeconds(demoEpochMs)
        val points = monitorService
            .getContainerHistoricalMetrics(host.id, containerName, nowSeconds - rangeSeconds, nowSeconds, null)
            .dataPoints
        fun col(value: (ContainerMetricDataPoint) -> Double?): List<Pair<Long, Double?>> =
            points.map { (it.timestamp * MILLIS_PER_SECOND) to value(it) }
        return buildList {
            metricOrNull(
                "cpu",
                "CPU utilization",
                "%",
                listOf(lineOrNull("CPU", col { it.cpuPercent?.toDouble() })),
            )
                ?.let { add(it) }
            metricOrNull(
                "mem",
                "Memory utilization",
                "%",
                listOf(
                    lineOrNull(
                        "Memory",
                        col {
                            val used = it.memUsed
                            val limit = it.memLimit
                            if (used != null && limit != null && limit > 0) {
                                used.toDouble() / limit.toDouble() * PERCENT_SCALE
                            } else {
                                null
                            }
                        },
                    ),
                ),
            )?.let { add(it) }
            metricOrNull(
                "network",
                "Network throughput",
                "bytes/s",
                listOf(
                    lineOrNull("Received", col { it.netRecvBytes?.toDouble() }),
                    lineOrNull("Sent", col { it.netSentBytes?.toDouble() }),
                ),
            )?.let { add(it) }
        }
    }

    private suspend fun serviceTelemetry(
        organizationIds: List<Int>,
        service: String?,
        rangeSeconds: Long,
    ): List<ResourceTelemetryMetric> {
        if (service.isNullOrBlank()) return emptyList()
        val rows = queryClient.serviceTelemetrySeries(organizationIds, service, rangeSeconds)
        if (rows.isEmpty()) return emptyList()
        fun rowTsMs(row: JsonObject): Long = (row.d("ts") ?: 0.0).toLong() * MILLIS_PER_SECOND
        val latencyCol = rows.map { rowTsMs(it) to it.d("latency_ms") }
        val throughputCol = rows.map { row -> rowTsMs(row) to row.d("span_count")?.div(SECONDS_PER_HOUR) }
        val errorCol = rows.map { row ->
            val span = row.d("span_count")
            val errors = row.d("error_count") ?: 0.0
            rowTsMs(row) to when {
                span == null -> null
                span > 0 -> errors / span * PERCENT_SCALE
                else -> 0.0
            }
        }
        return buildList {
            metricOrNull(
                "latency",
                "Latency (avg)",
                "ms",
                listOf(lineOrNull("Latency", latencyCol)),
            )?.let { add(it) }
            metricOrNull(
                "throughput",
                "Throughput",
                "req/s",
                listOf(lineOrNull("req/s", throughputCol)),
            )?.let { add(it) }
            metricOrNull("errorRate", "Error rate", "%", listOf(lineOrNull("Errors", errorCol)))?.let { add(it) }
        }
    }

    private fun telemetryNowSeconds(demoEpochMs: Long?): Long =
        (demoEpochMs ?: System.currentTimeMillis()) / MILLIS_PER_SECOND

    private fun telemetryInterval(rangeSeconds: Long): Int =
        when {
            rangeSeconds <= RANGE_1H_SECONDS -> INTERVAL_10S
            rangeSeconds <= RANGE_6H_SECONDS -> INTERVAL_1M
            rangeSeconds <= RANGE_24H_SECONDS -> INTERVAL_5M
            rangeSeconds <= RANGE_7D_SECONDS -> INTERVAL_30M
            else -> INTERVAL_1H
        }

    private fun lineOrNull(name: String, raw: List<Pair<Long, Double?>>): ResourceTelemetryLine? =
        if (raw.any { it.second != null }) {
            ResourceTelemetryLine(name, raw.map { ResourceTelemetryPoint(it.first, it.second) })
        } else {
            null
        }

    private fun metricOrNull(
        key: String,
        label: String,
        unit: String,
        lines: List<ResourceTelemetryLine?>,
    ): ResourceTelemetryMetric? {
        val present = lines.filterNotNull()
        return if (present.isNotEmpty()) ResourceTelemetryMetric(key, label, unit, present) else null
    }

    private suspend fun latestMetricsByHost(
        organizationIds: List<Int>,
        hosts: List<HostData>,
        demoEpochMs: Long?,
    ): Map<Int, LatestMetrics?> =
        organizationIds
            .flatMap { organizationId ->
                val orgHosts = hosts.filter { it.organizationId == organizationId }
                if (orgHosts.isEmpty()) {
                    emptyList()
                } else {
                    monitorService
                        .getLatestMetricsForHosts(orgHosts.map { it.id }, organizationId, demoEpochMs)
                        .entries
                        .map { it.toPair() }
                }
            }
            .toMap()

    private fun hostResource(host: HostData, metrics: LatestMetrics?): CatalogResource {
        val tags = host.tags.toCatalogTags() + "source:host-agent"
        val cloud = tags.cloudFromTags()
        return CatalogResource(
            id = stableId("host", "${host.organizationId}:${host.id}"),
            name = host.displayName ?: host.hostname,
            kind = "host",
            health = host.status.toCatalogHealth(),
            environment = tags.envFromTags(),
            region = tags.regionFromTags(cloud),
            cloud = cloud,
            owner = null,
            tags = tags,
            telemetry = CatalogResourceTelemetry(
                cpuPct = metrics?.cpuPercent?.toPct(),
                memPct = metrics?.memPercent?.toPct()
            ),
            vulns = CatalogVulnerabilityCounts(),
            sbomComponents = 0,
            posture = emptyList(),
            monthlyUsd = 0.0,
            costTrendPct = 0.0,
            costBreakdown = emptyList(),
            relationships = emptyList(),
            changes = emptyList(),
            metadata = hostMetadata(host),
            firstSeen = host.firstSeenAt.toCatalogIso(),
            lastChange = (host.lastSeenAt ?: host.createdAt).toCatalogIso()
        )
    }

    private fun serviceResource(row: JsonObject): CatalogResource? {
        val name = row.s("service") ?: return null
        val errorRate = row.d("error_rate_pct") ?: 0.0
        val spanCount = row.i("span_count")
        return CatalogResource(
            id = organizationScopedId("service", row.s("organization_id"), name),
            name = name,
            kind = "service",
            health = serviceHealth(spanCount, errorRate),
            environment = row.s("env").toEnvironment(),
            region = "global",
            cloud = CLOUD_ON_PREM,
            owner = null,
            tags = listOf("source:apm"),
            telemetry = CatalogResourceTelemetry(
                latencyMs = row.i("latency_ms"),
                errorRatePct = errorRate,
                throughput = row.s("span_count")?.let { "$it spans/24h" }
            ),
            vulns = CatalogVulnerabilityCounts(),
            sbomComponents = 0,
            posture = emptyList(),
            monthlyUsd = 0.0,
            costTrendPct = 0.0,
            costBreakdown = emptyList(),
            relationships = emptyList(),
            changes = emptyList(),
            metadata = listOfNotNull(
                meta("Spans", row.s("span_count")),
                meta("Errors", row.s("error_count"))
            ),
            firstSeen = row.s("last_seen") ?: CATALOG_UNKNOWN_TIMESTAMP,
            lastChange = row.s("last_seen") ?: CATALOG_UNKNOWN_TIMESTAMP
        )
    }

    private fun containerResource(row: JsonObject): CatalogResource? {
        val id = row.s("id") ?: row.s("container_id") ?: return null
        val tags = row.tags() + "source:container"
        val host = row.s("host")
        val memLimit = row.d("mem_limit") ?: 0.0
        val memUsage = row.d("mem_usage") ?: 0.0
        val memPct = if (memLimit > 0) (memUsage / memLimit * PERCENT_SCALE).roundToInt() else null
        val cloud = tags.cloudFromTags()
        return CatalogResource(
            id = organizationScopedId("container", row.s("organization_id"), id),
            name = row.s("name") ?: row.s("image") ?: id,
            kind = "container",
            health = row.s("state").containerHealth(),
            environment = tags.envFromTags(),
            region = tags.regionFromTags(cloud),
            cloud = cloud,
            owner = null,
            tags = tags,
            telemetry = CatalogResourceTelemetry(
                cpuPct = row.d("cpu_percent")?.toPct(),
                memPct = memPct?.coerceIn(ZERO_PERCENT, FULL_PERCENT)
            ),
            vulns = CatalogVulnerabilityCounts(),
            sbomComponents = 0,
            posture = emptyList(),
            monthlyUsd = 0.0,
            costTrendPct = 0.0,
            costBreakdown = emptyList(),
            relationships = emptyList(),
            changes = emptyList(),
            metadata = listOfNotNull(
                meta("Image", row.s("image")),
                meta("State", row.s("state")),
                meta("Host", host)
            ),
            firstSeen = row.s("last_seen") ?: CATALOG_UNKNOWN_TIMESTAMP,
            lastChange = row.s("last_seen") ?: CATALOG_UNKNOWN_TIMESTAMP
        )
    }

    private fun podResource(row: JsonObject): CatalogResource? {
        val id = row.s("id") ?: return null
        val tags = row.tags() + row.labels().map { "label:$it" } + "source:kubernetes"
        val cloud = tags.cloudFromTags()
        return CatalogResource(
            id = organizationScopedId("pod", row.s("organization_id"), id),
            name = row.s("name") ?: id,
            kind = "pod",
            health = row.s("status").podHealth(),
            environment = tags.envFromTags(),
            region = tags.regionFromTags(cloud),
            cloud = cloud,
            owner = null,
            tags = tags,
            telemetry = CatalogResourceTelemetry(),
            vulns = CatalogVulnerabilityCounts(),
            sbomComponents = 0,
            posture = emptyList(),
            monthlyUsd = 0.0,
            costTrendPct = 0.0,
            costBreakdown = emptyList(),
            relationships = emptyList(),
            changes = emptyList(),
            metadata = listOfNotNull(
                meta("Namespace", row.s("namespace")),
                meta("Cluster", row.s("cluster_name")),
                meta("Status", row.s("status"))
            ),
            firstSeen = row.s("first_seen") ?: row.s("last_seen") ?: CATALOG_UNKNOWN_TIMESTAMP,
            lastChange = row.s("last_seen") ?: CATALOG_UNKNOWN_TIMESTAMP
        )
    }

    private fun networkDeviceResource(row: JsonObject): CatalogResource? {
        val id = row.s("id") ?: return null
        val tags = row.tags() + "source:network-device"
        return CatalogResource(
            id = organizationScopedId("network", row.s("organization_id"), id),
            name = row.s("hostname") ?: row.s("ip_address") ?: id,
            kind = "network-device",
            health = row.s("reachability")?.toCatalogHealth() ?: row.s("status").toCatalogHealth(),
            environment = tags.envFromTags(),
            region = tags.regionFromTags(CLOUD_ON_PREM),
            cloud = CLOUD_ON_PREM,
            owner = null,
            tags = tags,
            telemetry = CatalogResourceTelemetry(),
            vulns = CatalogVulnerabilityCounts(),
            sbomComponents = 0,
            posture = emptyList(),
            monthlyUsd = 0.0,
            costTrendPct = 0.0,
            costBreakdown = emptyList(),
            relationships = emptyList(),
            changes = emptyList(),
            metadata = listOfNotNull(
                meta("IP", row.s("ip_address")),
                meta("Vendor", row.s("vendor")),
                meta("Model", row.s("model")),
                meta("OS", row.s("os_version")),
                meta("Type", row.s("device_type"))
            ),
            firstSeen = row.s("last_seen") ?: CATALOG_UNKNOWN_TIMESTAMP,
            lastChange = row.s("last_seen") ?: CATALOG_UNKNOWN_TIMESTAMP
        )
    }

    private fun cloudResource(row: JsonObject): CatalogResource? {
        val id = row.s("id") ?: return null
        val cloud = row.s("provider").toCloudProvider()
        val tags = row.tags() + listOf("source:cloud", "cloud:$cloud")
        return CatalogResource(
            id = organizationScopedId("cloud", row.s("organization_id"), id),
            name = row.s("name") ?: id,
            kind = "cloud",
            health = row.s("health").toCatalogHealth(),
            environment = tags.envFromTags(),
            region = row.s("region") ?: "global",
            cloud = cloud,
            owner = null,
            tags = tags,
            telemetry = CatalogResourceTelemetry(
                cpuPct = row.d("cpu_percent")?.toPct(),
                memPct = row.d("mem_percent")?.toPct()
            ),
            vulns = CatalogVulnerabilityCounts(),
            sbomComponents = 0,
            posture = emptyList(),
            monthlyUsd = row.d("monthly_usd") ?: 0.0,
            costTrendPct = row.d("cost_trend_pct") ?: 0.0,
            costBreakdown = emptyList(),
            relationships = emptyList(),
            changes = emptyList(),
            metadata = cloudMetadata(row),
            firstSeen = row.s("first_seen") ?: row.s("last_seen") ?: CATALOG_UNKNOWN_TIMESTAMP,
            lastChange = row.s("last_seen") ?: CATALOG_UNKNOWN_TIMESTAMP
        )
    }

    private fun hostMetadata(host: HostData): List<CatalogMetaItem> =
        listOfNotNull(
            meta("Hostname", host.hostname),
            meta("OS", host.os),
            meta("Platform", host.platform),
            meta("Architecture", host.arch),
            meta("Processor", host.processor),
            meta("CPU cores", host.cpuCores?.toString()),
            meta("Memory", host.memoryTotalKb?.let { formatKiB(it) }),
            meta("Agent", host.agentVersion)
        )

    private fun cloudMetadata(row: JsonObject): List<CatalogMetaItem> =
        listOfNotNull(
            meta("Account", row.s("account")),
            meta("Type", row.s("resource_type"))
        ) + row.metadataItems()

    private fun serviceHealth(spanCount: Int, errorRatePct: Double): String =
        when {
            spanCount <= 0 -> "unknown"
            errorRatePct >= SERVICE_CRITICAL_ERROR_RATE -> "critical"
            errorRatePct >= SERVICE_WARN_ERROR_RATE -> "warn"
            else -> "healthy"
        }
}

private fun JsonObject.s(key: String): String? =
    this[key]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }

private fun JsonObject.d(key: String): Double? =
    this[key]?.jsonPrimitive?.doubleOrNull

private fun JsonObject.i(key: String): Int =
    d(key)?.roundToInt() ?: s(key)?.toIntOrNull() ?: 0

private fun JsonObject.tags(): List<String> =
    mapEntries("tags")

private fun JsonObject.labels(): List<String> =
    mapEntries("labels")

private fun JsonObject.metadataItems(): List<CatalogMetaItem> {
    val obj = this["metadata"]?.jsonObject ?: return emptyList()
    return obj.entries.mapNotNull { (entryKey, value) ->
        val entryValue = value.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }
        entryValue?.let { CatalogMetaItem(entryKey, it) }
    }
}

private fun JsonObject.mapEntries(key: String): List<String> {
    val obj = this[key]?.jsonObject ?: return emptyList()
    return obj.entries.mapNotNull { (entryKey, value) ->
        val entryValue = value.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }
        entryValue?.let { "$entryKey:$it" }
    }
}

private fun String?.toCatalogHealth(): String =
    when (this?.lowercase()) {
        "online", "up", "ok", "healthy", "reachable", "success" -> "healthy"
        "warning", "warn", "degraded" -> "warn"
        "offline", "down", "critical", "error", "failed", "unreachable" -> "critical"
        else -> "unknown"
    }

private fun String?.containerHealth(): String =
    when (this?.lowercase()) {
        "running", "healthy", "up" -> "healthy"
        "restarting", "paused", "created" -> "warn"
        "exited", "dead", "error", "failed" -> "critical"
        else -> "unknown"
    }

private fun String?.podHealth(): String =
    when (this?.lowercase()) {
        "running", "succeeded" -> "healthy"
        "pending", "unknown" -> "warn"
        "failed" -> "critical"
        else -> "unknown"
    }

private fun String?.toEnvironment(): String =
    when (this?.lowercase()) {
        "prod", "production" -> "prod"
        "stage", "staging" -> "staging"
        "dev", "development", "test", "testing", "local" -> "dev"
        else -> "prod"
    }

private fun List<String>.envFromTags(): String {
    val raw = firstTagValue("env")
        ?: firstTagValue("environment")
        ?: firstTagValue("deployment.environment")
    return raw.toEnvironment()
}

private fun List<String>.cloudFromTags(): String =
    when ((firstTagValue("cloud") ?: firstTagValue("cloud.provider"))?.lowercase()) {
        "aws" -> "aws"
        "gcp", "google_cloud", "google-cloud" -> "gcp"
        "azure" -> "azure"
        else -> CLOUD_ON_PREM
    }

private fun List<String>.regionFromTags(cloud: String): String =
    firstTagValue("region")
        ?: firstTagValue("cloud.region")
        ?: firstTagValue("datacenter")
        ?: firstTagValue("dc")
        ?: firstTagValue("availability_zone")
        ?: firstTagValue("availability-zone")
        ?: firstTagValue("az")
        ?: if (cloud == CLOUD_ON_PREM) "local" else "global"

private fun String?.toCloudProvider(): String =
    when (this?.lowercase()) {
        "aws" -> "aws"
        "gcp", "google_cloud", "google-cloud" -> "gcp"
        "azure" -> "azure"
        else -> CLOUD_ON_PREM
    }

private fun List<String>.firstTagValue(key: String): String? =
    firstOrNull { it.startsWith("$key:") }?.substringAfter(":")?.takeIf { it.isNotBlank() }

private fun Map<String, String>.toCatalogTags(): List<String> =
    entries
        .sortedBy { it.key }
        .mapNotNull { (key, value) ->
            val cleanKey = key.takeIf { it.isNotBlank() }
            val cleanValue = value.takeIf { it.isNotBlank() }
            if (cleanKey == null || cleanValue == null) null else "$cleanKey:$cleanValue"
        }

private fun Float.toPct(): Int =
    roundToInt().coerceIn(ZERO_PERCENT, FULL_PERCENT)

private fun Double.toPct(): Int =
    roundToInt().coerceIn(ZERO_PERCENT, FULL_PERCENT)

private fun Instant.toCatalogIso(): String {
    val text = toString()
    return if (text.endsWith("Z") && !text.contains(".")) {
        text.removeSuffix("Z") + ".000Z"
    } else {
        text
    }
}

private fun stableId(prefix: String, value: String): String {
    val slug = value
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9_.:-]+"), "-")
        .trim('-')
        .ifBlank { "unknown" }
    return "$prefix:$slug"
}

private fun organizationScopedId(prefix: String, organizationId: String?, value: String): String =
    stableId(prefix, "${organizationId ?: "unknown-org"}:$value")

private fun meta(label: String, value: String?): CatalogMetaItem? =
    value?.takeIf { it.isNotBlank() }?.let { CatalogMetaItem(label, it) }

private fun formatKiB(value: Long): String {
    val mib = value / BYTES_PER_KIB
    return if (mib >= BYTES_PER_KIB) {
        "%.1f GiB".format(mib / BYTES_PER_KIB)
    } else {
        "%.0f MiB".format(mib)
    }
}
