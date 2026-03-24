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

package com.moneat.events.services

import com.moneat.config.ClickHouseClient
import com.moneat.shared.models.Projects
import io.ktor.http.isSuccess
import mu.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

/**
 * One-time backfill: copies existing rows from ClickHouse `spans` table into `apm_spans`
 * with the proper field mapping and organization_id resolution from PostgreSQL.
 */
object SentrySpanBackfill {

    suspend fun run() {
        val mapping = loadProjectOrgMapping()
        if (mapping.isEmpty()) {
            logger.info { "No projects found, skipping spans backfill" }
            return
        }

        val db = ClickHouseClient.getDatabase()
        val orgExpr = buildOrgMappingExpression(mapping)

        val insert = """
            INSERT INTO `$db`.apm_spans (
                span_id, span_id_high,
                trace_id, trace_id_high,
                parent_id, parent_id_high,
                organization_id,
                name, service, resource, type,
                start, duration, error,
                meta, metrics, host, env, version,
                trace_id_hex, span_id_hex, parent_id_hex, source
            )
            SELECT
                reinterpretAsUInt64(reverse(unhex(lpad(span_id, 16, '0')))),
                0,
                reinterpretAsUInt64(reverse(unhex(substring(lpad(trace_id, 32, '0'), 17, 16)))),
                reinterpretAsUInt64(reverse(unhex(substring(lpad(trace_id, 32, '0'), 1, 16)))),
                reinterpretAsUInt64(reverse(unhex(lpad(parent_span_id, 16, '0')))),
                0,
                $orgExpr,
                '' AS name,
                '' AS service,
                description AS resource,
                op AS type,
                toDateTime64(start_timestamp, 9, 'UTC') AS start,
                toUInt64((duration_ms) * 1000000) AS duration,
                toUInt8(status != 'ok' AND status != '' AND status != 'cancelled') AS error,
                mapConcat(
                    tags,
                    map(
                        'sentry.transaction_id', toString(transaction_id),
                        'sentry.project_id', toString(project_id)
                    )
                ) AS meta,
                map() AS metrics,
                '' AS host,
                '' AS env,
                '' AS version,
                trace_id AS trace_id_hex,
                span_id AS span_id_hex,
                parent_span_id AS parent_id_hex,
                'sentry' AS source
            FROM `$db`.spans
            WHERE project_id IN (${mapping.keys.joinToString(",")})
        """.trimIndent()

        logger.info { "Starting spans -> apm_spans backfill for ${mapping.size} projects..." }
        val response = ClickHouseClient.execute(insert)
        if (response.status.isSuccess()) {
            logger.info { "Spans backfill completed successfully" }
        } else {
            logger.error { "Spans backfill failed: HTTP ${response.status}" }
        }
    }

    private fun loadProjectOrgMapping(): Map<Long, Int> =
        transaction {
            Projects.selectAll().associate {
                it[Projects.id] to it[Projects.organization_id]
            }
        }

    private fun buildOrgMappingExpression(mapping: Map<Long, Int>): String {
        if (mapping.size == 1) {
            return mapping.values.first().toString()
        }
        val cases = mapping.entries.joinToString(", ") { (pid, oid) ->
            "project_id = $pid, $oid"
        }
        return "multiIf($cases, NULL)"
    }
}
