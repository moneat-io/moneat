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

import com.moneat.config.ClickHouseClient
import com.moneat.monitor.models.CatalogSecurityFinding
import com.moneat.security.signals.SecuritySignals
import com.moneat.security.signals.SignalSource
import com.moneat.security.signals.SignalStatus
import com.moneat.utils.suspendRunCatching
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val resourceSecurityLogger = KotlinLogging.logger {}
private val resourceSecurityJson = Json { ignoreUnknownKeys = true }

private const val MAX_VULNERABILITY_SIGNALS = 5_000
private const val MAX_TOP_FINDINGS = 5
private const val MAX_SECURITY_ROWS = 5_000
private const val SEVERITY_RANK_CRITICAL = 4
private const val SEVERITY_RANK_HIGH = 3
private const val SEVERITY_RANK_MEDIUM = 2
private const val SEVERITY_RANK_LOW = 1

/** The dimension a security aggregate is keyed by, so the catalog can join it to the right resource kind. */
enum class SecurityScope { HOST, IMAGE, SERVICE }

/** Per-resource vulnerability roll-up: severity counts plus the most severe findings to surface. */
data class ResourceVulnAggregate(
    val scope: SecurityScope,
    val key: String,
    val critical: Int,
    val high: Int,
    val medium: Int,
    val low: Int,
    val topFindings: List<CatalogSecurityFinding>,
)

/** Distinct SBOM component count for one resource dimension/key. */
data class ResourceComponentCount(
    val scope: SecurityScope,
    val key: String,
    val components: Int,
)

/** One compliance posture result for a resource, rolled up per framework. */
data class ResourceComplianceRow(
    val resourceType: String,
    val resourceName: String,
    val framework: String,
    val passed: Boolean,
)

data class ResourceSecuritySnapshot(
    val vulnerabilities: List<ResourceVulnAggregate> = emptyList(),
    val components: List<ResourceComponentCount> = emptyList(),
    val compliance: List<ResourceComplianceRow> = emptyList(),
) {
    fun isEmpty(): Boolean = vulnerabilities.isEmpty() && components.isEmpty() && compliance.isEmpty()
}

/**
 * Reads real per-resource security posture for the resource catalog: open vulnerability findings and
 * their severities from the signals store, SBOM component counts and compliance findings from the
 * evidence store. Every value is sourced; there is no synthesized security data.
 */
fun interface ResourceSecurityReader {
    suspend fun read(organizationIds: List<Int>): ResourceSecuritySnapshot
}

/** Returns nothing — used where the catalog runs without a security data source (tests, core-only). */
val NoopResourceSecurityReader: ResourceSecurityReader = ResourceSecurityReader { ResourceSecuritySnapshot() }

/**
 * Default reader. Vulnerability severities are read from the deduplicated security signals (Postgres),
 * where the severity was resolved against the advisory database at ingest time; SBOM components and
 * compliance posture are read from ClickHouse. Each source is read independently and degrades to empty
 * if its store is unavailable, so the catalog stays honest rather than failing.
 */
class DefaultResourceSecurityReader : ResourceSecurityReader {
    override suspend fun read(organizationIds: List<Int>): ResourceSecuritySnapshot {
        if (organizationIds.isEmpty()) return ResourceSecuritySnapshot()
        return ResourceSecuritySnapshot(
            vulnerabilities = readVulnerabilities(organizationIds),
            components = readComponentCounts(organizationIds),
            compliance = readCompliance(organizationIds),
        )
    }

    private suspend fun readVulnerabilities(organizationIds: List<Int>): List<ResourceVulnAggregate> =
        suspendRunCatching {
            val hostAcc = HashMap<String, VulnAccumulator>()
            val imageAcc = HashMap<String, VulnAccumulator>()
            val serviceAcc = HashMap<String, VulnAccumulator>()
            transaction {
                SecuritySignals
                    .selectAll()
                    .where {
                        (SecuritySignals.organizationId inList organizationIds) and
                            (SecuritySignals.signalSource eq SignalSource.VULNERABILITY.wire) and
                            (SecuritySignals.status neq SignalStatus.ARCHIVED.wire)
                    }
                    .orderBy(SecuritySignals.lastSeen to SortOrder.DESC)
                    .limit(MAX_VULNERABILITY_SIGNALS)
                    .forEach { row ->
                        val bucket = severityBucket(row[SecuritySignals.severity]) ?: return@forEach
                        val entities = decodeEntities(row[SecuritySignals.entities])
                        val finding = CatalogSecurityFinding(
                            id = entities["cve"]?.normalizedOrNull() ?: entities["advisory_id"].orEmpty(),
                            severity = bucket,
                            pkg = entities["package"].orEmpty(),
                            fixedVersion = entities["fix_version"]?.normalizedOrNull(),
                            cvss = entities["cvss"]?.toDoubleOrNull(),
                        )
                        entities["host"]?.normalizedKey()?.let {
                            hostAcc.getOrPut(it) { VulnAccumulator() }.add(bucket, finding)
                        }
                        entities["image_name"]?.normalizedKey()?.let {
                            imageAcc.getOrPut(it) { VulnAccumulator() }.add(bucket, finding)
                        }
                        if (entities["target_type"]?.lowercase() == "service") {
                            entities["target_name"]?.normalizedKey()?.let {
                                serviceAcc.getOrPut(it) { VulnAccumulator() }.add(bucket, finding)
                            }
                        }
                    }
            }
            buildList {
                hostAcc.forEach { (key, acc) -> add(acc.toAggregate(SecurityScope.HOST, key)) }
                imageAcc.forEach { (key, acc) -> add(acc.toAggregate(SecurityScope.IMAGE, key)) }
                serviceAcc.forEach { (key, acc) -> add(acc.toAggregate(SecurityScope.SERVICE, key)) }
            }
        }.getOrElse { error ->
            resourceSecurityLogger.warn(error) { "Resource catalog vulnerability read failed" }
            emptyList()
        }

    private suspend fun readComponentCounts(organizationIds: List<Int>): List<ResourceComponentCount> =
        executeRows(componentCountsSql(organizationIds)).mapNotNull { row ->
            val scope = scopeFromWire(row.s("scope")) ?: return@mapNotNull null
            val key = row.s("k")?.normalizedKey() ?: return@mapNotNull null
            ResourceComponentCount(scope = scope, key = key, components = row.i("c"))
        }

    private suspend fun readCompliance(organizationIds: List<Int>): List<ResourceComplianceRow> =
        executeRows(complianceSql(organizationIds)).mapNotNull { row ->
            val resourceName = row.s("rn")?.normalizedKey() ?: return@mapNotNull null
            val framework = row.s("framework") ?: return@mapNotNull null
            ResourceComplianceRow(
                resourceType = row.s("rt").orEmpty(),
                resourceName = resourceName,
                framework = framework,
                passed = row.i("failed") == 0,
            )
        }

    private suspend fun executeRows(sql: String): List<JsonObject> =
        suspendRunCatching {
            val response = ClickHouseClient.execute(sql)
            if (!response.status.isSuccess()) {
                resourceSecurityLogger.warn { "Resource catalog security query failed: ${response.status}" }
                return@suspendRunCatching emptyList()
            }
            parseJsonEachRow(response.bodyAsText())
        }.getOrElse { error ->
            resourceSecurityLogger.warn(error) { "Resource catalog security query failed" }
            emptyList()
        }

    private fun componentCountsSql(organizationIds: List<Int>): String {
        val db = ClickHouseClient.getDatabase()
        val orgClause = orgIdClause(organizationIds)
        return """
            SELECT 'host' AS scope, lower(host) AS k, toUInt32(uniqExact(package_name, package_version)) AS c
            FROM `$db`.security_package_inventory
            WHERE organization_id IN ($orgClause) AND host != ''
            GROUP BY lower(host)
            UNION ALL
            SELECT 'image' AS scope, lower(image_name) AS k, toUInt32(uniqExact(package_name, package_version)) AS c
            FROM `$db`.security_package_inventory
            WHERE organization_id IN ($orgClause) AND image_name != ''
            GROUP BY lower(image_name)
            UNION ALL
            SELECT 'service' AS scope, lower(target_name) AS k, toUInt32(uniqExact(package_name, package_version)) AS c
            FROM `$db`.security_package_inventory
            WHERE organization_id IN ($orgClause) AND target_type = 'service' AND target_name != ''
            GROUP BY lower(target_name)
            FORMAT JSONEachRow
        """.trimIndent()
    }

    private fun complianceSql(organizationIds: List<Int>): String {
        val db = ClickHouseClient.getDatabase()
        val orgClause = orgIdClause(organizationIds)
        return """
            SELECT
                lower(resource_type) AS rt,
                lower(resource_name) AS rn,
                framework,
                toUInt32(countIf(status = 'failed')) AS failed,
                toUInt32(count()) AS total
            FROM `$db`.compliance_findings
            WHERE organization_id IN ($orgClause) AND resource_name != ''
            GROUP BY rt, rn, framework
            ORDER BY rn
            LIMIT $MAX_SECURITY_ROWS
            FORMAT JSONEachRow
        """.trimIndent()
    }

    private fun orgIdClause(organizationIds: List<Int>): String =
        organizationIds.joinToString(", ") { "toUInt64($it)" }

    private fun decodeEntities(raw: String): Map<String, String> =
        runCatching {
            resourceSecurityJson.parseToJsonElement(raw).jsonObject.mapValues { (_, value) ->
                (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
            }
        }.getOrDefault(emptyMap())

    private class VulnAccumulator {
        private var critical = 0
        private var high = 0
        private var medium = 0
        private var low = 0
        private val findings = ArrayList<CatalogSecurityFinding>()

        fun add(bucket: String, finding: CatalogSecurityFinding) {
            when (bucket) {
                "critical" -> critical++
                "high" -> high++
                "medium" -> medium++
                "low" -> low++
            }
            findings.add(finding)
        }

        fun toAggregate(scope: SecurityScope, key: String): ResourceVulnAggregate =
            ResourceVulnAggregate(
                scope = scope,
                key = key,
                critical = critical,
                high = high,
                medium = medium,
                low = low,
                topFindings = findings
                    .sortedByDescending { severityRank(it.severity) }
                    .take(MAX_TOP_FINDINGS),
            )
    }
}

private fun severityBucket(value: String?): String? =
    when (value?.lowercase()) {
        "critical" -> "critical"
        "high" -> "high"
        "medium" -> "medium"
        "low" -> "low"
        else -> null
    }

private fun severityRank(value: String): Int =
    when (value) {
        "critical" -> SEVERITY_RANK_CRITICAL
        "high" -> SEVERITY_RANK_HIGH
        "medium" -> SEVERITY_RANK_MEDIUM
        "low" -> SEVERITY_RANK_LOW
        else -> 0
    }

private fun scopeFromWire(value: String?): SecurityScope? =
    when (value) {
        "host" -> SecurityScope.HOST
        "image" -> SecurityScope.IMAGE
        "service" -> SecurityScope.SERVICE
        else -> null
    }

private fun String.normalizedOrNull(): String? = trim().takeIf { it.isNotBlank() }

private fun String.normalizedKey(): String? = trim().lowercase().takeIf { it.isNotBlank() }

private fun JsonObject.s(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.i(key: String): Int =
    this[key]?.jsonPrimitive?.intOrNull
        ?: this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        ?: 0

private fun parseJsonEachRow(body: String): List<JsonObject> =
    body
        .lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            try {
                resourceSecurityJson.parseToJsonElement(line).jsonObject
            } catch (error: SerializationException) {
                resourceSecurityLogger.warn(error) { "Skipping invalid resource catalog security row" }
                null
            } catch (error: IllegalArgumentException) {
                resourceSecurityLogger.warn(error) { "Skipping invalid resource catalog security row" }
                null
            }
        }
        .toList()
