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

/**
 * Periodically re-inserts demo data so that ClickHouse TTL (90-day default)
 * does not silently delete the one-time seed migrations (V6/V7/V8/V10).
 *
 * Strategy: delete demo-project rows older than 30 days, then re-insert fresh
 * rows relative to now(). Runs once at startup when DEMO_ENABLED=true.
 */
object DemoDataReseeder {

    // Demo project IDs as UInt64 values matching the negative-ID convention
    private const val P1 = "toUInt64(-1)"
    private const val P2 = "toUInt64(-2)"
    private const val P3 = "toUInt64(-3)"

    suspend fun reseedIfNeeded() {
        if (!EnvConfig.Demo.enabled) return

        try {
            val freshCoreCount = checkFreshDataCount()
            val freshLlmCount = checkFreshLlmDataCount()

            if (freshCoreCount > 0 && freshLlmCount > 0) {
                logger.info {
                    "Demo data looks fresh ($freshCoreCount recent core events, $freshLlmCount recent LLM generations), skipping reseed"
                }
                return
            }

            if (freshCoreCount > 0) {
                logger.info { "Core demo data is fresh ($freshCoreCount recent events), skipping core reseed" }
            } else {
                logger.info { "Core demo data is stale or missing, reseeding..." }
                purgeOldDemoData()
                reseedEvents()
                reseedSessions()
                reseedReplays()
            }

            if (freshLlmCount > 0) {
                logger.info { "LLM demo data is fresh ($freshLlmCount recent generations), skipping LLM reseed" }
            } else {
                logger.info { "LLM demo data is stale or missing, reseeding..." }
                purgeLlmDemoData()
                reseedLlmGenerations()
            }

            logger.info { "Demo data reseed complete" }
        } catch (e: Exception) {
            logger.error(e) { "Demo data reseed failed (non-fatal): ${e.message}" }
        }
    }

    private suspend fun checkFreshDataCount(): Long {
        val query =
            """
            SELECT count() as cnt
            FROM events
            WHERE project_id IN ($P1, $P2, $P3)
                AND timestamp >= now() - INTERVAL 7 DAY
            """.trimIndent()
        val response = ClickHouseClient.execute(query)
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) return 0
        return body.trim().toLongOrNull() ?: 0
    }

    private suspend fun checkFreshLlmDataCount(): Long {
        val query =
            """
            SELECT count() as cnt
            FROM llm_generations
            WHERE project_id IN ($P1, $P2, $P3)
                AND timestamp >= now() - INTERVAL 12 HOUR
            """.trimIndent()
        val response = ClickHouseClient.execute(query)
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) return 0
        return body.trim().toLongOrNull() ?: 0
    }

    private suspend fun purgeOldDemoData() {
        val tables =
            listOf(
                "events" to "project_id",
                "sessions" to "project_id",
                "spans" to "project_id",
                "replay_events" to "project_id",
                "replay_segments" to "project_id"
            )
        for ((table, col) in tables) {
            val query = "ALTER TABLE $table DELETE WHERE $col IN ($P1, $P2, $P3)"
            runCatching { ClickHouseClient.execute(query) }
                .onFailure { logger.warn { "Purge $table failed (non-fatal): ${it.message}" } }
        }
        // Also purge issues materialized from demo events
        runCatching {
            ClickHouseClient.execute("ALTER TABLE issues DELETE WHERE project_id IN ($P1, $P2, $P3)")
        }
    }

    private suspend fun purgeLlmDemoData() {
        runCatching {
            ClickHouseClient.execute("ALTER TABLE llm_generations DELETE WHERE project_id IN ($P1, $P2, $P3)")
        }.onFailure { logger.warn { "Purge llm_generations failed (non-fatal): ${it.message}" } }

        // SummingMergeTree materialized rows need explicit cleanup.
        runCatching {
            ClickHouseClient.execute("ALTER TABLE llm_generations_hourly_mv DELETE WHERE project_id IN ($P1, $P2, $P3)")
        }.onFailure { logger.warn { "Purge llm_generations_hourly_mv failed (non-fatal): ${it.message}" } }
    }

    private suspend fun reseedEvents() {
        // Re-run the core patterns from V6 seed migration
        val statements =
            listOf(
                // Android NullPointerException events (project -1)
                """
            INSERT INTO events (
                event_id, project_id, issue_id, timestamp, received_at, event_type,
                platform, level, message, exception_type, exception_value,
                stack_trace, environment, release, user_id, user_email,
                device_model, os_name, os_version, fingerprint
            )
            SELECT
                generateUUIDv4(), $P1, 'demo-issue-android-1',
                now() - INTERVAL (number % 168) HOUR, now() - INTERVAL (number % 168) HOUR,
                'error', 'android', 'error',
                'Attempt to invoke virtual method on a null object reference',
                'java.lang.NullPointerException',
                'Attempt to invoke virtual method on a null object reference',
                'at com.acme.shopping.ui.ProductDetailFragment.updateUI(ProductDetailFragment.kt:87)',
                'production', '1.3.0',
                toString(1000 + (number % 100)),
                concat('user', toString(number % 89), '@example.com'),
                arrayElement(['Samsung Galaxy S23', 'Google Pixel 8', 'OnePlus 11'], number % 3 + 1),
                'Android',
                arrayElement(['14', '13', '12'], number % 3 + 1),
                ['NullPointerException', 'ProductDetailFragment', 'updateUI']
            FROM numbers(50)
            """,
                // iOS NSInvalidArgumentException events (project -2)
                """
            INSERT INTO events (
                event_id, project_id, issue_id, timestamp, received_at, event_type,
                platform, level, message, exception_type, exception_value,
                stack_trace, environment, release, user_id, user_email,
                device_model, os_name, os_version, fingerprint
            )
            SELECT
                generateUUIDv4(), $P2, 'demo-issue-ios-1',
                now() - INTERVAL (number % 168) HOUR, now() - INTERVAL (number % 168) HOUR,
                'error', 'ios', 'error',
                'Invalid argument passed to method',
                'NSInvalidArgumentException',
                'Invalid argument passed to method',
                'at -[UIViewController presentViewController:animated:completion:]',
                'production', '2.1.0',
                toString(2000 + (number % 80)),
                concat('iosuser', toString(number % 50), '@example.com'),
                arrayElement(['iPhone 15 Pro', 'iPhone 14', 'iPad Air'], number % 3 + 1),
                'iOS',
                arrayElement(['17.2', '17.0', '16.6'], number % 3 + 1),
                ['NSInvalidArgumentException', 'UIViewController', 'presentViewController']
            FROM numbers(40)
            """,
                // React Native TypeError events (project -3)
                """
            INSERT INTO events (
                event_id, project_id, issue_id, timestamp, received_at, event_type,
                platform, level, message, exception_type, exception_value,
                stack_trace, environment, release, user_id, user_email,
                device_model, os_name, os_version, fingerprint
            )
            SELECT
                generateUUIDv4(), $P3, 'demo-issue-rn-1',
                now() - INTERVAL (number % 192) HOUR, now() - INTERVAL (number % 192) HOUR,
                'error', 'react-native', 'error',
                'Cannot read property of undefined',
                'TypeError',
                'Cannot read property of undefined',
                'at HomeScreen.render (HomeScreen.js:42)',
                'production', '3.0.1',
                toString(3000 + (number % 60)),
                concat('rnuser', toString(number % 40), '@example.com'),
                arrayElement(['Samsung Galaxy S23', 'Google Pixel 8', 'iPhone 15'], number % 3 + 1),
                arrayElement(['Android', 'iOS'], number % 2 + 1),
                arrayElement(['14', '17.2'], number % 2 + 1),
                ['TypeError', 'HomeScreen', 'render']
            FROM numbers(30)
            """,
                // Transaction events (across all 3 projects)
                """
            INSERT INTO events (
                event_id, project_id, issue_id, timestamp, received_at, event_type,
                platform, level, transaction_name, transaction_op, duration_ms,
                environment, release, user_id, contexts
            )
            SELECT
                generateUUIDv4(),
                CASE number % 3 WHEN 0 THEN $P1 WHEN 1 THEN $P2 ELSE $P3 END,
                '',
                now() - INTERVAL (number % 168) HOUR, now() - INTERVAL (number % 168) HOUR,
                'transaction',
                arrayElement(['android', 'ios', 'react-native'], number % 3 + 1),
                'info',
                arrayElement(['app.launch', 'checkout.complete', 'search.query', 'profile.load'], number % 4 + 1),
                arrayElement(['ui.load', 'http.client', 'navigation'], number % 3 + 1),
                50 + (number * 37) % 2000,
                'production',
                arrayElement(['1.3.0', '2.1.0', '3.0.1'], number % 3 + 1),
                toString(1000 + (number % 100)),
                concat('{"trace":{"trace_id":"', toString(generateUUIDv4()), '","status":"ok"}}')
            FROM numbers(100)
            """
            )

        for (sql in statements) {
            runCatching { ClickHouseClient.execute(sql.trimIndent()) }
                .onFailure { logger.warn { "Reseed events statement failed (non-fatal): ${it.message}" } }
        }
    }

    private suspend fun reseedSessions() {
        val sql =
            """
            INSERT INTO sessions (session_id, project_id, started, duration_ms, status, errors, release, environment, user_id, received_at)
            SELECT
                generateUUIDv4(),
                CASE number % 3 WHEN 0 THEN $P1 WHEN 1 THEN $P2 ELSE $P3 END,
                now() - INTERVAL (number * 2) HOUR,
                1000 + (number * 123) % 300000,
                CASE WHEN number % 20 = 0 THEN 'crashed' WHEN number % 10 = 0 THEN 'exited' ELSE 'ok' END,
                if(number % 20 = 0, 1, 0),
                arrayElement(['1.3.0', '2.1.0', '3.0.1'], number % 3 + 1),
                'production',
                toString(1000 + (number % 100)),
                now() - INTERVAL (number * 2) HOUR
            FROM numbers(80)
            """.trimIndent()
        runCatching { ClickHouseClient.execute(sql) }
            .onFailure { logger.warn { "Reseed sessions failed (non-fatal): ${it.message}" } }
    }

    private suspend fun reseedReplays() {
        val replayEventsSql =
            """
            INSERT INTO replay_events (
                replay_id, project_id, segment_id, timestamp, replay_start_timestamp,
                urls, error_ids, trace_ids, environment, release, platform,
                user_id, user_email, user_username, browser_name, browser_version,
                os_name, os_version, activity, tags
            )
            SELECT
                toUUID(concat(
                    'aaaaaaaa-bbbb-cccc-dddd-',
                    lpad(toString(number), 12, '0')
                )),
                CASE number % 3 WHEN 0 THEN $P1 WHEN 1 THEN $P2 ELSE $P3 END,
                0,
                now() - INTERVAL (number * 4) HOUR,
                now() - INTERVAL (number * 4 + 1) HOUR,
                ['com.acme.shopping://home', 'com.acme.shopping://product'],
                [],
                [],
                'production',
                arrayElement(['1.3.0', '2.1.0', '3.0.1'], number % 3 + 1),
                arrayElement(['android', 'ios', 'react-native'], number % 3 + 1),
                toString(1000 + (number % 50)),
                concat('user', toString(number % 50), '@example.com'),
                concat('User ', toString(number % 50)),
                '', '', 
                arrayElement(['Android', 'iOS'], number % 2 + 1),
                arrayElement(['14', '17.2'], number % 2 + 1),
                50 + (number % 50),
                '{}'
            FROM numbers(20)
            """.trimIndent()
        runCatching { ClickHouseClient.execute(replayEventsSql) }
            .onFailure { logger.warn { "Reseed replay_events failed (non-fatal): ${it.message}" } }

        // Seed replay_segments with valid rrweb recording data so the replay viewer can render them.
        // Each replay gets one segment containing a Meta event (type 4) and a FullSnapshot (type 2).
        val segmentsSql =
            """
            INSERT INTO replay_segments (
                replay_id, project_id, segment_id, timestamp, recording_data
            )
            SELECT
                toUUID(concat(
                    'aaaaaaaa-bbbb-cccc-dddd-',
                    lpad(toString(number), 12, '0')
                )) as replay_id,
                CASE number % 3 WHEN 0 THEN $P1 WHEN 1 THEN $P2 ELSE $P3 END as project_id,
                0 as segment_id,
                now() - INTERVAL (number * 4) HOUR as timestamp,
                concat(
                    '[',
                    '{"type":4,"data":{"href":"https://demo.acme-shopping.com/","width":1280,"height":720},"timestamp":',
                    toString(toInt64(toUnixTimestamp64Milli(now() - INTERVAL (number * 4 + 1) HOUR))),
                    '},',
                    '{"type":2,"data":{"node":{"type":0,"childNodes":[',
                      '{"type":1,"name":"html","publicId":"","systemId":"","childNodes":[],"id":2},',
                      '{"type":2,"tagName":"html","attributes":{"lang":"en"},"childNodes":[',
                        '{"type":2,"tagName":"head","attributes":{},"childNodes":[',
                          '{"type":2,"tagName":"title","attributes":{},"childNodes":[',
                            '{"type":3,"textContent":"Acme Shopping","id":5}',
                          '],"id":4}',
                        '],"id":3},',
                        '{"type":2,"tagName":"body","attributes":{"class":"app-root"},"childNodes":[',
                          '{"type":2,"tagName":"div","attributes":{"id":"app","class":"container"},"childNodes":[',
                            '{"type":2,"tagName":"header","attributes":{"class":"navbar"},"childNodes":[',
                              '{"type":2,"tagName":"h1","attributes":{},"childNodes":[',
                                '{"type":3,"textContent":"Acme Shopping","id":9}',
                              '],"id":8},',
                              '{"type":2,"tagName":"nav","attributes":{},"childNodes":[',
                                '{"type":2,"tagName":"a","attributes":{"href":"/products"},"childNodes":[',
                                  '{"type":3,"textContent":"Products","id":12}',
                                '],"id":11},',
                                '{"type":2,"tagName":"a","attributes":{"href":"/cart"},"childNodes":[',
                                  '{"type":3,"textContent":"Cart (', toString(number % 5), ')","id":14}',
                                '],"id":13}',
                              '],"id":10}',
                            '],"id":7},',
                            '{"type":2,"tagName":"main","attributes":{"class":"content"},"childNodes":[',
                              '{"type":2,"tagName":"div","attributes":{"class":"product-grid"},"childNodes":[',
                                '{"type":2,"tagName":"div","attributes":{"class":"product-card"},"childNodes":[',
                                  '{"type":2,"tagName":"h3","attributes":{},"childNodes":[',
                                    '{"type":3,"textContent":"Premium Widget","id":19}',
                                  '],"id":18},',
                                  '{"type":2,"tagName":"p","attributes":{"class":"price"},"childNodes":[',
                                    '{"type":3,"textContent":"$', toString(19 + number * 10), '.99","id":21}',
                                  '],"id":20},',
                                  '{"type":2,"tagName":"button","attributes":{"class":"btn-primary"},"childNodes":[',
                                    '{"type":3,"textContent":"Add to Cart","id":23}',
                                  '],"id":22}',
                                '],"id":17}',
                              '],"id":16}',
                            '],"id":15}',
                          '],"id":6}',
                        '],"id":24}',
                      '],"id":1}',
                    ']},"initialOffset":{"left":0,"top":0}},"timestamp":',
                    toString(toInt64(toUnixTimestamp64Milli(now() - INTERVAL (number * 4 + 1) HOUR)) + 50),
                    '},',
                    '{"type":3,"data":{"source":1,"positions":[{"x":640,"y":360,"id":6,"timeOffset":0}]},"timestamp":',
                    toString(toInt64(toUnixTimestamp64Milli(now() - INTERVAL (number * 4 + 1) HOUR)) + 2000),
                    '},',
                    '{"type":3,"data":{"source":1,"positions":[{"x":', toString(200 + number * 30 % 800),
                    ',"y":', toString(200 + number * 20 % 400),
                    ',"id":6,"timeOffset":0}]},"timestamp":',
                    toString(toInt64(toUnixTimestamp64Milli(now() - INTERVAL (number * 4 + 1) HOUR)) + 5000),
                    '},',
                    '{"type":3,"data":{"source":2,"type":2,"id":22,"x":', toString(400 + number * 10 % 300),
                    ',"y":', toString(350 + number * 5 % 200),
                    '},"timestamp":',
                    toString(toInt64(toUnixTimestamp64Milli(now() - INTERVAL (number * 4 + 1) HOUR)) + 8000),
                    '},',
                    '{"type":3,"data":{"source":5,"text":"', toString((number % 5) + 1),
                    '","isChecked":false,"id":14},"timestamp":',
                    toString(toInt64(toUnixTimestamp64Milli(now() - INTERVAL (number * 4 + 1) HOUR)) + 8500),
                    '}',
                    ']'
                ) as recording_data
            FROM numbers(20)
            """.trimIndent()
        runCatching { ClickHouseClient.execute(segmentsSql) }
            .onFailure { logger.warn { "Reseed replay_segments failed (non-fatal): ${it.message}" } }
    }

    private suspend fun reseedLlmGenerations() {
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
        runCatching { ClickHouseClient.execute(sql) }
            .onFailure { logger.warn { "Reseed llm_generations failed (non-fatal): ${it.message}" } }
    }
}
