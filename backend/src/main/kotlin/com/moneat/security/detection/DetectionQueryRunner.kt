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

package com.moneat.security.detection

import com.moneat.config.ClickHouseClient
import com.moneat.config.ClickHouseQueryException
import com.moneat.config.isClickHouseError
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}
private val runnerJson = Json { ignoreUnknownKeys = true }

private const val QUERY_FINGERPRINT_LEN = 12

/**
 * Short, stable fingerprint of a compiled query for logs. A SHA-256 prefix correlates failures and
 * malformed rows back to a query without ever writing the SQL itself — which embeds user-authored
 * filter literals and tenant-scoping — to the log.
 */
internal fun queryFingerprint(sql: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(sql.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(QUERY_FINGERPRINT_LEN)

/** One aggregated group returned by a compiled detection query. */
data class DetectionGroupRow(
    /** group-by column name → value (aliases resolved back to the author's column names). */
    val groupValues: Map<String, String>,
    val count: Long,
)

/**
 * Executes compiled detection aggregations against ClickHouse, reusing [ClickHouseClient.execute]
 * (which itself caps SELECT execution time) and the project's sanitized-error convention. On a
 * ClickHouse error only a query fingerprint is logged — never the SQL or the response body, both of
 * which embed user-authored filter literals and backend schema/error text — and the surfaced
 * [ClickHouseQueryException] carries a generic detail, so no SQL/schema/tenant data leaks to logs or
 * clients. The compiled SQL already carries org scoping and resource caps from [RuleQueryCompiler];
 * this runner adds no SQL of its own.
 */
class DetectionQueryRunner(
    private val execute: suspend (String) -> String = { sql ->
        val resp = ClickHouseClient.execute("$sql FORMAT JSONEachRow")
        val body = resp.bodyAsText()
        if (resp.isClickHouseError(body)) {
            logger.error { "ClickHouse error in detection query (fingerprint=${queryFingerprint(sql)})" }
            throw ClickHouseQueryException(isTimeout = false, internalDetail = "Detection query failed")
        }
        body
    },
) {

    /** Run [compiled] and map each JSONEachRow line back to a [DetectionGroupRow]. */
    suspend fun run(compiled: CompiledRuleQuery): List<DetectionGroupRow> {
        val body = execute(compiled.sql)
        val aliasToColumn = compiled.groupByAliases.zip(compiled.groupByColumns).toMap()
        return parseRows(body, aliasToColumn, queryFingerprint(compiled.sql))
    }

    /** Run an arbitrary org-scoped, capped aggregation [sql] with explicit alias→column mapping. */
    suspend fun runRaw(sql: String, aliasToColumn: Map<String, String>): List<DetectionGroupRow> {
        val body = execute(sql)
        return parseRows(body, aliasToColumn, queryFingerprint(sql))
    }

    private fun parseRows(
        body: String,
        aliasToColumn: Map<String, String>,
        fingerprint: String,
    ): List<DetectionGroupRow> =
        body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line -> parseLine(line, aliasToColumn, fingerprint) }
            .toList()

    /**
     * Parses one JSONEachRow line into a [DetectionGroupRow], or returns `null` to **skip** a malformed
     * row. A row is dropped (with a fingerprint-only warning) when the line is not valid JSON, when
     * `match_count` is missing or non-numeric, or when any expected group alias is absent. Coercing such
     * rows to `0`/empty values would fabricate signals with wrong dedup keys, so they are skipped rather
     * than crashing the whole evaluation.
     */
    private fun parseLine(line: String, aliasToColumn: Map<String, String>, fingerprint: String): DetectionGroupRow? {
        val obj = runCatching { runnerJson.parseToJsonElement(line).jsonObject }.getOrNull()
            ?: return skip(fingerprint, "non-JSON row")
        val count = obj["match_count"]?.let { (it as? JsonPrimitive)?.longOrNull }
            ?: return skip(fingerprint, "missing/invalid match_count")
        val values = mutableMapOf<String, String>()
        for ((alias, column) in aliasToColumn) {
            val value = obj[alias]?.let { (it as? JsonPrimitive)?.content }
                ?: return skip(fingerprint, "missing group alias")
            values[column] = value
        }
        return DetectionGroupRow(groupValues = values, count = count)
    }

    private fun skip(fingerprint: String, reason: String): DetectionGroupRow? {
        logger.warn { "Skipping malformed detection row ($reason, fingerprint=$fingerprint)" }
        return null
    }
}
