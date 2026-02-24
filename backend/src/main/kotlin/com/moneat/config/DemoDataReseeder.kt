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

import com.moneat.dashboards.models.AggFunction
import com.moneat.dashboards.models.DashboardWidgets
import com.moneat.dashboards.models.Dashboards
import com.moneat.dashboards.models.FilterDef
import com.moneat.dashboards.models.FilterOp
import com.moneat.dashboards.models.GroupByDef
import com.moneat.dashboards.models.GroupByType
import com.moneat.dashboards.models.MetricDef
import com.moneat.dashboards.models.OrderByDef
import com.moneat.dashboards.models.QueryDsl
import com.moneat.dashboards.models.TimeRangeDef
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Periodically re-inserts demo data so that ClickHouse TTL (90-day default)
 * does not silently delete the one-time seed migrations (V6/V7/V8/V10/V12).
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
            val freshAnalyticsCount = checkFreshAnalyticsDataCount()

            if (freshCoreCount > 0 && freshLlmCount > 0 && freshAnalyticsCount > 0) {
                logger.info {
                    "Demo data looks fresh ($freshCoreCount recent core events, $freshLlmCount recent LLM generations, $freshAnalyticsCount recent analytics events), skipping reseed"
                }
                seedDemoDashboards()
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

            if (freshAnalyticsCount > 0) {
                logger.info { "Analytics demo data is fresh ($freshAnalyticsCount recent events), skipping analytics reseed" }
            } else {
                logger.info { "Analytics demo data is stale or missing, reseeding..." }
                purgeAnalyticsDemoData()
                reseedAnalyticsEvents()
            }

            logger.info { "Demo data reseed complete" }

            // Always reseed dashboards (PostgreSQL, not subject to ClickHouse TTL)
            seedDemoDashboards()
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

    private suspend fun checkFreshAnalyticsDataCount(): Long {
        val query =
            """
            SELECT count() as cnt
            FROM analytics_events
            WHERE project_id IN ($P1, $P2, $P3)
                AND timestamp >= now() - INTERVAL 7 DAY
            """.trimIndent()
        return runCatching {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299) return 0
            body.trim().toLongOrNull() ?: 0
        }.getOrElse {
            logger.warn { "Failed to check fresh analytics demo data (non-fatal): ${it.message}" }
            0
        }
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

    private suspend fun purgeAnalyticsDemoData() {
        runCatching {
            ClickHouseClient.execute("ALTER TABLE analytics_events DELETE WHERE project_id IN ($P1, $P2, $P3)")
        }.onFailure { logger.warn { "Purge analytics_events failed (non-fatal): ${it.message}" } }

        runCatching {
            ClickHouseClient.execute("ALTER TABLE analytics_sessions_hourly DELETE WHERE project_id IN ($P1, $P2, $P3)")
        }.onFailure { logger.warn { "Purge analytics_sessions_hourly failed (non-fatal): ${it.message}" } }
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

    private suspend fun reseedAnalyticsEvents() {
        val sql =
            """
            INSERT INTO analytics_events (
                event_id,
                project_id,
                session_id,
                event_name,
                hostname,
                pathname,
                referrer,
                referrer_source,
                utm_source,
                utm_medium,
                utm_campaign,
                country_code,
                browser,
                browser_version,
                os,
                device_type,
                screen_width,
                props,
                timestamp
            )
            SELECT
                generateUUIDv4() AS event_id,
                CASE intDiv(number, 5) % 3
                    WHEN 0 THEN $P1
                    WHEN 1 THEN $P2
                    ELSE $P3
                END AS project_id,
                concat('sess-', toString(intDiv(number, 5) % 500)) AS session_id,
                CASE
                    WHEN number % 20 < 17 THEN 'pageview'
                    WHEN number % 20 = 17 THEN 'signup_click'
                    WHEN number % 20 = 18 THEN 'add_to_cart'
                    ELSE 'purchase'
                END AS event_name,
                'demo.moneat.io' AS hostname,
                CASE number % 12
                    WHEN 0  THEN '/'
                    WHEN 1  THEN '/'
                    WHEN 2  THEN '/'
                    WHEN 3  THEN '/pricing'
                    WHEN 4  THEN '/docs'
                    WHEN 5  THEN '/docs/getting-started'
                    WHEN 6  THEN '/blog'
                    WHEN 7  THEN '/blog/why-moneat'
                    WHEN 8  THEN '/features'
                    WHEN 9  THEN '/login'
                    WHEN 10 THEN '/signup'
                    ELSE '/about'
                END AS pathname,
                CASE intDiv(number, 5) % 10
                    WHEN 0 THEN ''
                    WHEN 1 THEN ''
                    WHEN 2 THEN ''
                    WHEN 3 THEN ''
                    WHEN 4 THEN 'https://www.google.com/'
                    WHEN 5 THEN 'https://www.google.com/'
                    WHEN 6 THEN 'https://github.com/'
                    WHEN 7 THEN 'https://news.ycombinator.com/'
                    WHEN 8 THEN 'https://twitter.com/'
                    ELSE 'https://dev.to/'
                END AS referrer,
                CASE intDiv(number, 5) % 10
                    WHEN 0 THEN 'Direct'
                    WHEN 1 THEN 'Direct'
                    WHEN 2 THEN 'Direct'
                    WHEN 3 THEN 'Direct'
                    WHEN 4 THEN 'Google'
                    WHEN 5 THEN 'Google'
                    WHEN 6 THEN 'GitHub'
                    WHEN 7 THEN 'Hacker News'
                    WHEN 8 THEN 'Twitter'
                    ELSE 'Dev.to'
                END AS referrer_source,
                CASE intDiv(number, 5) % 15
                    WHEN 0 THEN 'newsletter'
                    WHEN 1 THEN 'producthunt'
                    ELSE ''
                END AS utm_source,
                CASE intDiv(number, 5) % 15
                    WHEN 0 THEN 'email'
                    WHEN 1 THEN 'social'
                    ELSE ''
                END AS utm_medium,
                CASE intDiv(number, 5) % 15
                    WHEN 0 THEN 'feb-launch'
                    WHEN 1 THEN 'ph-launch'
                    ELSE ''
                END AS utm_campaign,
                CASE intDiv(number, 5) % 12
                    WHEN 0  THEN 'US'
                    WHEN 1  THEN 'US'
                    WHEN 2  THEN 'US'
                    WHEN 3  THEN 'GB'
                    WHEN 4  THEN 'DE'
                    WHEN 5  THEN 'FR'
                    WHEN 6  THEN 'CA'
                    WHEN 7  THEN 'AU'
                    WHEN 8  THEN 'IN'
                    WHEN 9  THEN 'BR'
                    WHEN 10 THEN 'JP'
                    ELSE 'NL'
                END AS country_code,
                CASE intDiv(number, 5) % 8
                    WHEN 0 THEN 'Chrome'
                    WHEN 1 THEN 'Chrome'
                    WHEN 2 THEN 'Chrome'
                    WHEN 3 THEN 'Firefox'
                    WHEN 4 THEN 'Safari'
                    WHEN 5 THEN 'Safari'
                    WHEN 6 THEN 'Edge'
                    ELSE 'Arc'
                END AS browser,
                CASE intDiv(number, 5) % 8
                    WHEN 0 THEN '121.0'
                    WHEN 1 THEN '120.0'
                    WHEN 2 THEN '119.0'
                    WHEN 3 THEN '122.0'
                    WHEN 4 THEN '17.3'
                    WHEN 5 THEN '17.2'
                    WHEN 6 THEN '121.0'
                    ELSE '1.0'
                END AS browser_version,
                CASE intDiv(number, 5) % 6
                    WHEN 0 THEN 'macOS'
                    WHEN 1 THEN 'Windows'
                    WHEN 2 THEN 'Windows'
                    WHEN 3 THEN 'Linux'
                    WHEN 4 THEN 'iOS'
                    ELSE 'Android'
                END AS os,
                CASE intDiv(number, 5) % 6
                    WHEN 4 THEN 'Mobile'
                    WHEN 5 THEN 'Mobile'
                    ELSE 'Desktop'
                END AS device_type,
                CASE intDiv(number, 5) % 6
                    WHEN 4 THEN toUInt16(390)
                    WHEN 5 THEN toUInt16(412)
                    ELSE toUInt16(1440 + (intDiv(number, 5) % 3) * 80)
                END AS screen_width,
                CASE
                    WHEN number % 20 = 18 THEN map('plan', CASE number % 3 WHEN 0 THEN 'pro' WHEN 1 THEN 'team' ELSE 'enterprise' END)
                    WHEN number % 20 = 19 THEN map('value', toString(29 + (number % 5) * 20))
                    ELSE map()
                END AS props,
                now64(3) - INTERVAL (intDiv(number, 8) % 720) HOUR
                         - INTERVAL (number % 3600) SECOND AS timestamp
            FROM numbers(3000)
            """.trimIndent()

        runCatching { ClickHouseClient.execute(sql) }
            .onFailure { logger.warn { "Reseed analytics_events failed (non-fatal): ${it.message}" } }
    }

    // ── Demo Dashboard Seeding ─────────────────────────────────────────────

    private const val DEMO_ORG_ID = -1L
    private const val DEMO_USER_ID = -1L

    private fun seedDemoDashboards() {
        try {
            transaction {
                // Purge existing demo dashboards (cascade deletes widgets)
                Dashboards.deleteWhere {
                    (orgId eq DEMO_ORG_ID) and (createdBy eq DEMO_USER_ID)
                }

                seedErrorOverviewDashboard()
                seedPerformanceDashboard()
                seedLlmMonitoringDashboard()
                seedWebAnalyticsDashboard()
            }
            logger.info { "Demo dashboards seeded successfully" }
        } catch (e: Exception) {
            logger.warn { "Demo dashboard seeding failed (non-fatal): ${e.message}" }
        }
    }

    private fun insertDashboard(title: String, description: String): Long {
        val now = Clock.System.now()
        return Dashboards.insert {
            it[orgId] = DEMO_ORG_ID
            it[projectId] = null
            it[folderId] = null
            it[Dashboards.title] = title
            it[Dashboards.description] = description
            it[layoutType] = "grid"
            it[isDefault] = false
            it[variables] = "[]"
            it[createdBy] = DEMO_USER_ID
            it[createdAt] = now
            it[updatedAt] = now
        } get Dashboards.id
    }

    private fun insertWidget(
        dashId: Long,
        title: String,
        type: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        queries: List<QueryDsl>,
        display: Map<String, String> = emptyMap(),
        order: Int = 0
    ) {
        val now = Clock.System.now()
        DashboardWidgets.insert {
            it[dashboardId] = dashId
            it[DashboardWidgets.title] = title
            it[widgetType] = type
            it[gridX] = x
            it[gridY] = y
            it[gridW] = w
            it[gridH] = h
            it[queryConfig] = if (queries.isNotEmpty()) json.encodeToString(queries.first()) else "{}"
            it[queryConfigs] = json.encodeToString(queries)
            it[displayConfig] = if (display.isEmpty()) "{}" else json.encodeToString(display)
            it[sortOrder] = order
            it[createdAt] = now
            it[updatedAt] = now
        }
    }

    private val defaultTimeRange = TimeRangeDef("now-7d", "now")

    // ── Error Overview Dashboard ───────────────────────────────────────────

    private fun seedErrorOverviewDashboard() {
        val id = insertDashboard(
            "Error Overview",
            "Cross-platform error monitoring across Android, iOS, and React Native"
        )
        var row = 0

        // Section: Error Trends
        insertWidget(id, "Error Trends", "section", 0, row, 12, 1, emptyList(), order = 0)
        row += 1

        // Errors over time by platform
        insertWidget(
            id, "Errors Over Time", "timeseries", 0, row, 8, 4,
            listOf(
                QueryDsl(
                    dataSource = "events",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "errors")),
                    groupBy = listOf(
                        GroupByDef("timestamp", GroupByType.TIME, "1 HOUR"),
                        GroupByDef("platform", GroupByType.FIELD)
                    ),
                    filters = listOf(FilterDef("level", FilterOp.EQ, "error")),
                    timeRange = defaultTimeRange,
                    limit = 1000
                )
            ),
            order = 1
        )

        // Total errors stat
        insertWidget(
            id, "Total Errors", "stat", 8, row, 2, 2,
            listOf(
                QueryDsl(
                    dataSource = "events",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "total")),
                    filters = listOf(FilterDef("level", FilterOp.EQ, "error")),
                    timeRange = defaultTimeRange
                )
            ),
            order = 2
        )

        // Unique affected users stat
        insertWidget(
            id, "Affected Users", "stat", 10, row, 2, 2,
            listOf(
                QueryDsl(
                    dataSource = "events",
                    metrics = listOf(MetricDef(AggFunction.UNIQ, "user_id", "users")),
                    filters = listOf(FilterDef("level", FilterOp.EQ, "error")),
                    timeRange = defaultTimeRange
                )
            ),
            order = 3
        )

        // Top error types bar
        insertWidget(
            id, "Top Error Types", "bar", 8, row + 2, 4, 2,
            listOf(
                QueryDsl(
                    dataSource = "events",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                    groupBy = listOf(GroupByDef("exception_type", GroupByType.FIELD)),
                    filters = listOf(FilterDef("level", FilterOp.EQ, "error")),
                    orderBy = OrderByDef("count", "desc"),
                    timeRange = defaultTimeRange,
                    limit = 5
                )
            ),
            order = 4
        )
        row += 4

        // Section: Error Details
        insertWidget(id, "Error Details", "section", 0, row, 12, 1, emptyList(), order = 5)
        row += 1

        // Recent errors table
        insertWidget(
            id, "Recent Errors", "table", 0, row, 8, 4,
            listOf(
                QueryDsl(
                    dataSource = "events",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                    groupBy = listOf(
                        GroupByDef("exception_type", GroupByType.FIELD),
                        GroupByDef("exception_value", GroupByType.FIELD)
                    ),
                    filters = listOf(FilterDef("level", FilterOp.EQ, "error")),
                    orderBy = OrderByDef("count", "desc"),
                    timeRange = defaultTimeRange,
                    limit = 20
                )
            ),
            order = 6
        )

        // Errors by platform donut
        insertWidget(
            id, "Errors by Platform", "donut", 8, row, 4, 4,
            listOf(
                QueryDsl(
                    dataSource = "events",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                    groupBy = listOf(GroupByDef("platform", GroupByType.FIELD)),
                    filters = listOf(FilterDef("level", FilterOp.EQ, "error")),
                    timeRange = defaultTimeRange
                )
            ),
            order = 7
        )
    }

    // ── Performance Dashboard ──────────────────────────────────────────────

    private fun seedPerformanceDashboard() {
        val id = insertDashboard(
            "Performance",
            "Transaction performance and session monitoring"
        )
        var row = 0

        // Section: Transactions
        insertWidget(id, "Transactions", "section", 0, row, 12, 1, emptyList(), order = 0)
        row += 1

        // Transaction count over time
        insertWidget(
            id, "Transactions Over Time", "timeseries", 0, row, 8, 4,
            listOf(
                QueryDsl(
                    dataSource = "events",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "transactions")),
                    groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, "1 HOUR")),
                    filters = listOf(FilterDef("event_type", FilterOp.EQ, "transaction")),
                    timeRange = defaultTimeRange,
                    limit = 1000
                )
            ),
            order = 1
        )

        // Transaction count stat
        insertWidget(
            id, "Total Transactions", "stat", 8, row, 2, 2,
            listOf(
                QueryDsl(
                    dataSource = "events",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "total")),
                    filters = listOf(FilterDef("event_type", FilterOp.EQ, "transaction")),
                    timeRange = defaultTimeRange
                )
            ),
            order = 2
        )

        // Unique transaction users
        insertWidget(
            id, "Unique Users", "stat", 10, row, 2, 2,
            listOf(
                QueryDsl(
                    dataSource = "events",
                    metrics = listOf(MetricDef(AggFunction.UNIQ, "user_id", "users")),
                    filters = listOf(FilterDef("event_type", FilterOp.EQ, "transaction")),
                    timeRange = defaultTimeRange
                )
            ),
            order = 3
        )

        // Transactions by platform
        insertWidget(
            id, "Transactions by Platform", "bar", 8, row + 2, 4, 2,
            listOf(
                QueryDsl(
                    dataSource = "events",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                    groupBy = listOf(GroupByDef("platform", GroupByType.FIELD)),
                    filters = listOf(FilterDef("event_type", FilterOp.EQ, "transaction")),
                    timeRange = defaultTimeRange
                )
            ),
            order = 4
        )
        row += 4

        // Section: Sessions
        insertWidget(id, "Sessions", "section", 0, row, 12, 1, emptyList(), order = 5)
        row += 1

        // Sessions over time
        insertWidget(
            id, "Sessions Over Time", "timeseries", 0, row, 6, 4,
            listOf(
                QueryDsl(
                    dataSource = "sessions",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "sessions")),
                    groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, "1 HOUR")),
                    timeRange = defaultTimeRange,
                    limit = 1000
                )
            ),
            order = 6
        )

        // Total sessions stat
        insertWidget(
            id, "Total Sessions", "stat", 6, row, 3, 2,
            listOf(
                QueryDsl(
                    dataSource = "sessions",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "total")),
                    timeRange = defaultTimeRange
                )
            ),
            order = 7
        )

        // Unique session users
        insertWidget(
            id, "Unique Session Users", "stat", 9, row, 3, 2,
            listOf(
                QueryDsl(
                    dataSource = "sessions",
                    metrics = listOf(MetricDef(AggFunction.UNIQ, "user_id", "users")),
                    timeRange = defaultTimeRange
                )
            ),
            order = 8
        )
    }

    // ── LLM Monitoring Dashboard ───────────────────────────────────────────

    private fun seedLlmMonitoringDashboard() {
        val id = insertDashboard(
            "LLM Monitoring",
            "AI/LLM generation tracking — usage, latency, cost, and model breakdown"
        )
        var row = 0

        // Section: Usage Overview
        insertWidget(id, "Usage Overview", "section", 0, row, 12, 1, emptyList(), order = 0)
        row += 1

        // Generations over time
        insertWidget(
            id, "Generations Over Time", "timeseries", 0, row, 6, 4,
            listOf(
                QueryDsl(
                    dataSource = "llm_generations",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "generations")),
                    groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, "1 HOUR")),
                    timeRange = defaultTimeRange,
                    limit = 1000
                )
            ),
            order = 1
        )

        // Total generations stat
        insertWidget(
            id, "Total Generations", "stat", 6, row, 2, 2,
            listOf(
                QueryDsl(
                    dataSource = "llm_generations",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "total")),
                    timeRange = defaultTimeRange
                )
            ),
            order = 2
        )

        // Total tokens stat
        insertWidget(
            id, "Total Tokens", "stat", 8, row, 2, 2,
            listOf(
                QueryDsl(
                    dataSource = "llm_generations",
                    metrics = listOf(MetricDef(AggFunction.SUM, "total_tokens", "tokens")),
                    timeRange = defaultTimeRange
                )
            ),
            order = 3
        )

        // Total cost stat
        insertWidget(
            id, "Total Cost", "stat", 10, row, 2, 2,
            listOf(
                QueryDsl(
                    dataSource = "llm_generations",
                    metrics = listOf(MetricDef(AggFunction.SUM, "cost_usd", "cost")),
                    timeRange = defaultTimeRange
                )
            ),
            mapOf("unit" to "currency_usd"),
            order = 4
        )

        // Avg latency stat
        insertWidget(
            id, "Avg Latency", "stat", 6, row + 2, 2, 2,
            listOf(
                QueryDsl(
                    dataSource = "llm_generations",
                    metrics = listOf(MetricDef(AggFunction.AVG, "duration_ms", "avg_ms")),
                    timeRange = defaultTimeRange
                )
            ),
            mapOf("unit" to "ms"),
            order = 5
        )

        // P95 latency stat
        insertWidget(
            id, "P95 Latency", "stat", 8, row + 2, 2, 2,
            listOf(
                QueryDsl(
                    dataSource = "llm_generations",
                    metrics = listOf(MetricDef(AggFunction.P95, "duration_ms", "p95_ms")),
                    timeRange = defaultTimeRange
                )
            ),
            mapOf("unit" to "ms"),
            order = 6
        )

        // Error rate stat
        insertWidget(
            id, "Error Generations", "stat", 10, row + 2, 2, 2,
            listOf(
                QueryDsl(
                    dataSource = "llm_generations",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "errors")),
                    filters = listOf(FilterDef("status", FilterOp.EQ, "error")),
                    timeRange = defaultTimeRange
                )
            ),
            order = 7
        )
        row += 4

        // Section: Model Breakdown
        insertWidget(id, "Model Breakdown", "section", 0, row, 12, 1, emptyList(), order = 8)
        row += 1

        // Generations by model
        insertWidget(
            id, "Generations by Model", "bar", 0, row, 4, 4,
            listOf(
                QueryDsl(
                    dataSource = "llm_generations",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                    groupBy = listOf(GroupByDef("model", GroupByType.FIELD)),
                    orderBy = OrderByDef("count", "desc"),
                    timeRange = defaultTimeRange,
                    limit = 10
                )
            ),
            order = 9
        )

        // Avg latency by model
        insertWidget(
            id, "Avg Latency by Model", "bar", 4, row, 4, 4,
            listOf(
                QueryDsl(
                    dataSource = "llm_generations",
                    metrics = listOf(MetricDef(AggFunction.AVG, "duration_ms", "avg_ms")),
                    groupBy = listOf(GroupByDef("model", GroupByType.FIELD)),
                    orderBy = OrderByDef("avg_ms", "desc"),
                    timeRange = defaultTimeRange,
                    limit = 10
                )
            ),
            mapOf("unit" to "ms"),
            order = 10
        )

        // Generations by provider donut
        insertWidget(
            id, "Generations by Provider", "donut", 8, row, 4, 4,
            listOf(
                QueryDsl(
                    dataSource = "llm_generations",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                    groupBy = listOf(GroupByDef("provider", GroupByType.FIELD)),
                    timeRange = defaultTimeRange
                )
            ),
            order = 11
        )
    }

    // ── Web Analytics Dashboard ────────────────────────────────────────────

    private fun seedWebAnalyticsDashboard() {
        val id = insertDashboard(
            "Web Analytics",
            "Website traffic, pageviews, and visitor demographics"
        )
        var row = 0

        // Section: Traffic Overview
        insertWidget(id, "Traffic Overview", "section", 0, row, 12, 1, emptyList(), order = 0)
        row += 1

        // Pageviews over time
        insertWidget(
            id, "Pageviews Over Time", "timeseries", 0, row, 8, 4,
            listOf(
                QueryDsl(
                    dataSource = "analytics_events",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "pageviews")),
                    groupBy = listOf(GroupByDef("timestamp", GroupByType.TIME, "1 HOUR")),
                    filters = listOf(FilterDef("event_name", FilterOp.EQ, "pageview")),
                    timeRange = defaultTimeRange,
                    limit = 1000
                )
            ),
            order = 1
        )

        // Total pageviews stat
        insertWidget(
            id, "Total Pageviews", "stat", 8, row, 2, 2,
            listOf(
                QueryDsl(
                    dataSource = "analytics_events",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "total")),
                    filters = listOf(FilterDef("event_name", FilterOp.EQ, "pageview")),
                    timeRange = defaultTimeRange
                )
            ),
            order = 2
        )

        // Unique sessions stat
        insertWidget(
            id, "Unique Sessions", "stat", 10, row, 2, 2,
            listOf(
                QueryDsl(
                    dataSource = "analytics_events",
                    metrics = listOf(MetricDef(AggFunction.UNIQ, "session_id", "sessions")),
                    timeRange = defaultTimeRange
                )
            ),
            order = 3
        )

        // Events by type bar
        insertWidget(
            id, "Events by Type", "bar", 8, row + 2, 4, 2,
            listOf(
                QueryDsl(
                    dataSource = "analytics_events",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                    groupBy = listOf(GroupByDef("event_name", GroupByType.FIELD)),
                    orderBy = OrderByDef("count", "desc"),
                    timeRange = defaultTimeRange,
                    limit = 10
                )
            ),
            order = 4
        )
        row += 4

        // Section: Breakdown
        insertWidget(id, "Breakdown", "section", 0, row, 12, 1, emptyList(), order = 5)
        row += 1

        // Top pages bar
        insertWidget(
            id, "Top Pages", "bar", 0, row, 6, 4,
            listOf(
                QueryDsl(
                    dataSource = "analytics_events",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "views")),
                    groupBy = listOf(GroupByDef("pathname", GroupByType.FIELD)),
                    filters = listOf(FilterDef("event_name", FilterOp.EQ, "pageview")),
                    orderBy = OrderByDef("views", "desc"),
                    timeRange = defaultTimeRange,
                    limit = 10
                )
            ),
            order = 6
        )

        // Traffic by country donut
        insertWidget(
            id, "Traffic by Country", "donut", 6, row, 3, 4,
            listOf(
                QueryDsl(
                    dataSource = "analytics_events",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                    groupBy = listOf(GroupByDef("country_code", GroupByType.FIELD)),
                    orderBy = OrderByDef("count", "desc"),
                    timeRange = defaultTimeRange,
                    limit = 10
                )
            ),
            order = 7
        )

        // Traffic by device type donut
        insertWidget(
            id, "Traffic by Device", "donut", 9, row, 3, 4,
            listOf(
                QueryDsl(
                    dataSource = "analytics_events",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                    groupBy = listOf(GroupByDef("device_type", GroupByType.FIELD)),
                    timeRange = defaultTimeRange
                )
            ),
            order = 8
        )
        row += 4

        // Traffic by browser bar
        insertWidget(
            id, "Traffic by Browser", "bar", 0, row, 6, 4,
            listOf(
                QueryDsl(
                    dataSource = "analytics_events",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                    groupBy = listOf(GroupByDef("browser", GroupByType.FIELD)),
                    orderBy = OrderByDef("count", "desc"),
                    timeRange = defaultTimeRange,
                    limit = 10
                )
            ),
            order = 9
        )

        // Traffic by OS bar
        insertWidget(
            id, "Traffic by OS", "bar", 6, row, 6, 4,
            listOf(
                QueryDsl(
                    dataSource = "analytics_events",
                    metrics = listOf(MetricDef(AggFunction.COUNT, alias = "count")),
                    groupBy = listOf(GroupByDef("os", GroupByType.FIELD)),
                    orderBy = OrderByDef("count", "desc"),
                    timeRange = defaultTimeRange,
                    limit = 10
                )
            ),
            order = 10
        )
    }
}
