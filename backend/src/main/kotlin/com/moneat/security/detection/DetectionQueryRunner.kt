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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private val runnerJson = Json { ignoreUnknownKeys = true }

private const val LOG_SQL_MAX_LEN = 200
private const val LOG_BODY_MAX_LEN = 300

/** One aggregated group returned by a compiled detection query. */
data class DetectionGroupRow(
    /** group-by column name → value (aliases resolved back to the author's column names). */
    val groupValues: Map<String, String>,
    val count: Long,
)

/**
 * Executes compiled detection aggregations against ClickHouse, reusing [ClickHouseClient.execute]
 * (which itself caps SELECT execution time) and the project's sanitized-error convention: the raw
 * ClickHouse body is logged server-side and wrapped in a [ClickHouseQueryException] whose client-facing
 * message is generic, so no SQL/schema text leaks. The compiled SQL already carries org scoping and
 * resource caps from [RuleQueryCompiler]; this runner adds no SQL of its own.
 */
class DetectionQueryRunner(
    private val execute: suspend (String) -> String = { sql ->
        val resp = ClickHouseClient.execute("$sql FORMAT JSONEachRow")
        val body = resp.bodyAsText()
        if (resp.isClickHouseError(body)) {
            logger.error {
                "ClickHouse error in detection query. SQL: ${sql.take(LOG_SQL_MAX_LEN)} " +
                    "Body: ${body.take(LOG_BODY_MAX_LEN)}"
            }
            throw ClickHouseQueryException(
                isTimeout = false,
                internalDetail = "Detection query failed: ${body.take(LOG_BODY_MAX_LEN)}",
            )
        }
        body
    },
) {

    /** Run [compiled] and map each JSONEachRow line back to a [DetectionGroupRow]. */
    suspend fun run(compiled: CompiledRuleQuery): List<DetectionGroupRow> {
        val body = execute(compiled.sql)
        return parseRows(body, compiled)
    }

    /** Run an arbitrary org-scoped, capped aggregation [sql] with explicit alias→column mapping. */
    suspend fun runRaw(sql: String, aliasToColumn: Map<String, String>): List<DetectionGroupRow> {
        val body = execute(sql)
        return body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line -> parseLine(line, aliasToColumn) }
            .toList()
    }

    private fun parseRows(body: String, compiled: CompiledRuleQuery): List<DetectionGroupRow> {
        val aliasToColumn = compiled.groupByAliases.zip(compiled.groupByColumns).toMap()
        return body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line -> parseLine(line, aliasToColumn) }
            .toList()
    }

    private fun parseLine(line: String, aliasToColumn: Map<String, String>): DetectionGroupRow? {
        val obj = runCatching { runnerJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
        val count = obj["match_count"]?.let { (it as? JsonPrimitive)?.longOrNull } ?: 0L
        val values = aliasToColumn.entries.associate { (alias, column) ->
            column to (obj[alias]?.stringValue() ?: "")
        }
        return DetectionGroupRow(groupValues = values, count = count)
    }
}

private fun kotlinx.serialization.json.JsonElement.stringValue(): String =
    (this as? JsonPrimitive)?.content ?: toString()

internal fun JsonObject.matchCount(): Long =
    this["match_count"]?.let { (it as? JsonPrimitive)?.longOrNull } ?: 0L
