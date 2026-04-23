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

internal suspend fun checkFreshLlmDataCount(): Long {
    val query =
        """
        SELECT count() as cnt
        FROM llm_generations
        WHERE project_id IN ($P1, $P2, $P3)
            AND timestamp >= now() - INTERVAL 12 HOUR
        """.trimIndent()
    return suspendRunCatching {
        val response = ClickHouseClient.execute(query)
        if (response.status.value !in 200..299) {
            0L
        } else {
            response.bodyAsText().trim().toLongOrNull() ?: 0L
        }
    }.getOrElse {
        logger.warn { "Failed to check fresh LLM demo data (non-fatal): ${it.message}" }
        0L
    }
}
internal suspend fun purgeLlmDemoData() {
    suspendRunCatching {
        ClickHouseClient.execute("ALTER TABLE llm_generations DELETE WHERE project_id IN ($P1, $P2, $P3)")
    }.onFailure { logger.warn { "Purge llm_generations failed (non-fatal): ${it.message}" } }

    // SummingMergeTree materialized rows need explicit cleanup.
    suspendRunCatching {
        ClickHouseClient.execute("ALTER TABLE llm_generations_hourly_mv DELETE WHERE project_id IN ($P1, $P2, $P3)")
    }.onFailure { logger.warn { "Purge llm_generations_hourly_mv failed (non-fatal): ${it.message}" } }
}
internal suspend fun reseedLlmGenerations() {
    val sql =
        """
        INSERT INTO llm_generations (
            generation_id,
            project_id,
            trace_id,
            span_id,
            parent_span_id,
            timestamp,
            duration_ms,
            name,
            model,
            provider,
            type,
            input,
            output,
            input_tokens,
            output_tokens,
            total_tokens,
            cost_usd,
            temperature,
            max_tokens,
            top_p,
            status,
            error_message,
            status_code,
            user_id,
            session_id,
            environment,
            release,
            tags,
            metadata,
            received_at
        )
        SELECT
            generateUUIDv4() AS generation_id,
            CASE intDiv(number, 4) % 3
                WHEN 0 THEN $P1
                WHEN 1 THEN $P2
                ELSE $P3
            END AS project_id,
            concat('demo-trace-', toString(intDiv(number, 4))) AS trace_id,
            concat('demo-span-', toString(number)) AS span_id,
            CASE
                WHEN number % 4 = 0 THEN ''
                ELSE concat('demo-span-', toString(number - 1))
            END AS parent_span_id,
            now64(3) - INTERVAL (intDiv(number, 4) % 168) HOUR + INTERVAL ((number % 4) * 3) SECOND AS timestamp,
            CASE number % 4
                WHEN 0 THEN 220 + ((number * 11) % 280)
                WHEN 1 THEN 80 + ((number * 7) % 180)
                WHEN 2 THEN 340 + ((number * 13) % 900)
                ELSE 120 + ((number * 17) % 240)
            END AS duration_ms,
            CASE number % 4
                WHEN 0 THEN 'agent.plan'
                WHEN 1 THEN 'retriever.search'
                WHEN 2 THEN 'chat.generate'
                ELSE 'tool.call'
            END AS name,
            CASE intDiv(number, 4) % 4
                WHEN 0 THEN 'gpt-4o-mini'
                WHEN 1 THEN 'gpt-4o'
                WHEN 2 THEN 'claude-3-5-sonnet'
                ELSE 'gemini-1.5-pro'
            END AS model,
            CASE intDiv(number, 4) % 4
                WHEN 0 THEN 'openai'
                WHEN 1 THEN 'openai'
                WHEN 2 THEN 'anthropic'
                ELSE 'google'
            END AS provider,
            CASE number % 4
                WHEN 0 THEN 'agent'
                WHEN 1 THEN 'retriever'
                WHEN 2 THEN 'chat'
                ELSE 'tool_call'
            END AS type,
            concat(
                '{"messages":[{"role":"user","content":"Demo request #',
                toString(intDiv(number, 4)),
                '"}],"step":',
                toString(number % 4),
                '}'
            ) AS input,
            CASE
                WHEN number % 37 = 0 AND number % 4 = 2 THEN ''
                ELSE concat(
                    '{"text":"Demo response for trace ',
                    toString(intDiv(number, 4)),
                    ', step ',
                    toString(number % 4),
                    '"}'
                )
            END AS output,
            CASE number % 4
                WHEN 0 THEN 90 + (number % 70)
                WHEN 1 THEN 120 + (number % 80)
                WHEN 2 THEN 220 + (number % 140)
                ELSE 60 + (number % 45)
            END AS input_tokens,
            CASE
                WHEN number % 37 = 0 AND number % 4 = 2 THEN 0
                ELSE
                    CASE number % 4
                        WHEN 0 THEN 40 + (number % 30)
                        WHEN 1 THEN 55 + (number % 35)
                        WHEN 2 THEN 110 + (number % 70)
                        ELSE 24 + (number % 20)
                    END
            END AS output_tokens,
            (
                CASE number % 4
                    WHEN 0 THEN 90 + (number % 70)
                    WHEN 1 THEN 120 + (number % 80)
                    WHEN 2 THEN 220 + (number % 140)
                    ELSE 60 + (number % 45)
                END
                +
                CASE
                    WHEN number % 37 = 0 AND number % 4 = 2 THEN 0
                    ELSE
                        CASE number % 4
                            WHEN 0 THEN 40 + (number % 30)
                            WHEN 1 THEN 55 + (number % 35)
                            WHEN 2 THEN 110 + (number % 70)
                            ELSE 24 + (number % 20)
                        END
                END
            ) AS total_tokens,
            (
                (
                    CASE number % 4
                        WHEN 0 THEN 90 + (number % 70)
                        WHEN 1 THEN 120 + (number % 80)
                        WHEN 2 THEN 220 + (number % 140)
                        ELSE 60 + (number % 45)
                    END
                ) * 0.00000035
                +
                (
                    CASE
                        WHEN number % 37 = 0 AND number % 4 = 2 THEN 0
                        ELSE
                            CASE number % 4
                                WHEN 0 THEN 40 + (number % 30)
                                WHEN 1 THEN 55 + (number % 35)
                                WHEN 2 THEN 110 + (number % 70)
                                ELSE 24 + (number % 20)
                            END
                    END
                ) * 0.0000011
            ) AS cost_usd,
            CASE number % 4
                WHEN 0 THEN toFloat32(0.2)
                WHEN 1 THEN toFloat32(0.0)
                WHEN 2 THEN toFloat32(0.7)
                ELSE toFloat32(0.1)
            END AS temperature,
            CASE number % 4
                WHEN 0 THEN toUInt32(256)
                WHEN 1 THEN toUInt32(192)
                WHEN 2 THEN toUInt32(512)
                ELSE toUInt32(96)
            END AS max_tokens,
            CASE number % 4
                WHEN 0 THEN toFloat32(0.95)
                WHEN 1 THEN toFloat32(1.0)
                WHEN 2 THEN toFloat32(0.9)
                ELSE toFloat32(1.0)
            END AS top_p,
            CASE
                WHEN number % 37 = 0 AND number % 4 = 2 THEN 'error'
                ELSE 'success'
            END AS status,
            CASE
                WHEN number % 37 = 0 AND number % 4 = 2 THEN 'Model provider timeout'
                ELSE ''
            END AS error_message,
            CASE
                WHEN number % 37 = 0 AND number % 4 = 2 THEN toUInt16(504)
                ELSE toUInt16(200)
            END AS status_code,
            concat('demo-user-', toString(intDiv(number, 4) % 120)) AS user_id,
            concat('demo-session-', toString(intDiv(number, 4))) AS session_id,
            'production' AS environment,
            CASE intDiv(number, 4) % 3
                WHEN 0 THEN '1.3.0'
                WHEN 1 THEN '2.1.0'
                ELSE '3.0.1'
            END AS release,
            map(
                'demo', 'true',
                'trace_index', toString(intDiv(number, 4)),
                'workflow', CASE number % 4 WHEN 0 THEN 'planner' WHEN 1 THEN 'retriever' WHEN 2 THEN 'generator' ELSE 'tool' END
            ) AS tags,
            concat(
                '{"source":"demo_reseeder","trace_step":',
                toString(number % 4),
                '}'
            ) AS metadata,
            timestamp AS received_at
        FROM numbers(800)
        """.trimIndent()
    suspendRunCatching { ClickHouseClient.execute(sql) }
        .onFailure { logger.warn { "Reseed llm_generations failed (non-fatal): ${it.message}" } }
}
