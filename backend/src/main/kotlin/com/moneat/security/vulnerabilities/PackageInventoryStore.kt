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

package com.moneat.security.vulnerabilities

import com.moneat.config.ClickHouseClient
import com.moneat.config.ClickHouseQueryException
import com.moneat.config.isClickHouseError
import com.moneat.utils.ClickHouseSqlUtils.escapeLikePattern
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import com.moneat.utils.ClickHouseSqlUtils.mapToSqlMap
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging

private val inventoryLogger = KotlinLogging.logger {}

interface PackageInventoryStore {
    suspend fun insert(records: List<SbomInventoryRecord>)
    suspend fun list(orgId: Int, filters: InventoryFilters): VulnerabilityInventoryResponse
    suspend fun countPackages(orgId: Int): Long
}

data class InventoryFilters(
    val search: String? = null,
    val packageName: String? = null,
    val target: String? = null,
    val limit: Int = 100,
    val offset: Int = 0,
)

class ClickHousePackageInventoryStore(
    private val databaseProvider: () -> String = { ClickHouseClient.getDatabase() },
) : PackageInventoryStore {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun insert(records: List<SbomInventoryRecord>) {
        if (records.isEmpty()) return
        val rows = records.joinToString(",\n") { record ->
            """(
                generateUUIDv4(),
                toUUID('${escapeSql(record.uploadId.toString())}'),
                ${record.organizationId},
                '${escapeSql(record.source.wire)}',
                '${escapeSql(record.format.wire)}',
                '${escapeSql(record.targetType)}',
                '${escapeSql(record.targetName)}',
                '${escapeSql(record.host)}',
                '${escapeSql(record.containerId)}',
                '${escapeSql(record.imageName)}',
                '${escapeSql(record.packageName)}',
                '${escapeSql(record.packageVersion)}',
                '${escapeSql(record.packageType)}',
                '${escapeSql(record.ecosystem)}',
                '${escapeSql(record.purl)}',
                ${arrayToSql(record.licenses)},
                '${escapeSql(record.supplier)}',
                '${escapeSql(record.bomRef)}',
                ${mapToSqlMap(record.tags)},
                fromUnixTimestamp64Milli(${record.collectedAtMs}),
                now64(3)
            )"""
        }
        val sql = """
            INSERT INTO `${databaseProvider()}`.security_package_inventory (
                inventory_id, upload_id, organization_id, source, format, target_type, target_name,
                host, container_id, image_name, package_name, package_version, package_type, ecosystem,
                purl, licenses, supplier, bom_ref, tags, collected_at, ingested_at
            ) VALUES $rows
        """.trimIndent()
        val response = ClickHouseClient.execute(sql)
        val body = response.bodyAsText()
        if (response.isClickHouseError(body)) {
            inventoryLogger.error { "ClickHouse inventory insert failed: ${body.take(LOG_BODY_MAX_LENGTH)}" }
            throw ClickHouseQueryException(false, "Security package inventory insert failed")
        }
    }

    override suspend fun list(orgId: Int, filters: InventoryFilters): VulnerabilityInventoryResponse {
        val conditions = inventoryConditions(orgId, filters)
        val where = conditions.joinToString(" AND ")
        val total = countRows(where)
        val rows = ClickHouseClient.executeWithFormat(
            """
            SELECT
                package_name,
                package_version,
                package_type,
                ecosystem,
                purl,
                target_type,
                target_name,
                host,
                image_name,
                container_id,
                formatDateTime(max(collected_at), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') AS last_seen,
                toUInt32(0) AS finding_count
            FROM `${databaseProvider()}`.security_package_inventory
            WHERE $where
            GROUP BY
                package_name, package_version, package_type, ecosystem, purl,
                target_type, target_name, host, image_name, container_id
            ORDER BY max(collected_at) DESC
            LIMIT ${filters.limit} OFFSET ${filters.offset}
            """.trimIndent(),
            "JSONEachRow",
        )
        return VulnerabilityInventoryResponse(
            inventory = parseRows(rows).map { it.toInventoryItem() },
            totalCount = total,
        )
    }

    override suspend fun countPackages(orgId: Int): Long =
        countRows("organization_id = toUInt64($orgId)")

    private suspend fun countRows(where: String): Long {
        val body = ClickHouseClient.executeWithFormat(
            """
            SELECT count() AS total_count
            FROM (
                SELECT
                    package_name,
                    package_version,
                    package_type,
                    ecosystem,
                    purl,
                    target_type,
                    target_name,
                    host,
                    image_name,
                    container_id
                FROM `${databaseProvider()}`.security_package_inventory
                WHERE $where
                GROUP BY
                    package_name, package_version, package_type, ecosystem, purl,
                    target_type, target_name, host, image_name, container_id
            )
            """.trimIndent(),
            "JSONEachRow",
        )
        return parseRows(body).firstOrNull()?.long("total_count") ?: 0L
    }

    private fun inventoryConditions(orgId: Int, filters: InventoryFilters): List<String> {
        val conditions = mutableListOf("organization_id = toUInt64($orgId)")
        filters.packageName?.takeIf { it.isNotBlank() }?.let {
            conditions.add("package_name = '${escapeSql(it)}'")
        }
        filters.target?.takeIf { it.isNotBlank() }?.let {
            val escaped = escapeSql(it)
            conditions.add("(target_name = '$escaped' OR host = '$escaped' OR image_name = '$escaped')")
        }
        filters.search?.takeIf { it.isNotBlank() }?.let {
            val escaped = escapeLikePattern(it)
            conditions.add(
                "(package_name ILIKE '%$escaped%' OR package_version ILIKE '%$escaped%' " +
                    "OR target_name ILIKE '%$escaped%' OR host ILIKE '%$escaped%' OR image_name ILIKE '%$escaped%')"
            )
        }
        return conditions
    }

    private fun parseRows(body: String): List<JsonObject> =
        body.trim().lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() }
            .toList()

    private fun JsonObject.toInventoryItem(): VulnerabilityInventoryItem =
        VulnerabilityInventoryItem(
            packageName = string("package_name"),
            packageVersion = string("package_version"),
            packageType = string("package_type"),
            ecosystem = string("ecosystem"),
            purl = string("purl"),
            targetType = string("target_type"),
            targetName = string("target_name"),
            host = string("host"),
            imageName = string("image_name"),
            containerId = string("container_id"),
            lastSeen = string("last_seen"),
            findingCount = int("finding_count"),
        )

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: 0

    private fun arrayToSql(values: List<String>): String {
        if (values.isEmpty()) return "[]"
        return values.joinToString(prefix = "[", postfix = "]") { "'${escapeSql(it)}'" }
    }

    companion object {
        private const val LOG_BODY_MAX_LENGTH = 500
    }
}
