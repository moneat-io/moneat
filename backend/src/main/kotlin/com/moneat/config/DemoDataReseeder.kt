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
import org.jetbrains.exposed.v1.jdbc.selectAll
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

    // Log reseed tuning
    private const val LOG_SEED_ROWS = 300
    private const val LOG_BUCKET_1_MAX = 80
    private const val LOG_BUCKET_2_MAX = 160
    private const val LOG_BUCKET_3_MAX = 240
    private const val LOG_BUCKET_4_BASE_MINUTES = 60

    suspend fun reseedIfNeeded() {
        if (!EnvConfig.Demo.enabled) return

        try {
            val freshCoreCount = checkFreshDataCount()
            val freshLlmCount = checkFreshLlmDataCount()
            val freshAnalyticsCount = checkFreshAnalyticsDataCount()
            val freshLogsCount = checkFreshLogsCount()
            val freshDatadogCount = checkFreshDatadogCount()
            val demoDashboardCount = countDemoDashboards()

            val hasFreshCore = freshCoreCount > 0
            val hasFreshLlm = freshLlmCount > 0
            val hasFreshAnalytics = freshAnalyticsCount > 0
            val hasFreshLogs = freshLogsCount > 0
            val hasFreshDatadog = freshDatadogCount > 0
            val hasEnoughDashboards = demoDashboardCount >= 4

            if (hasFreshCore && hasFreshLlm && hasFreshAnalytics && hasFreshLogs && hasFreshDatadog && hasEnoughDashboards) {
                logger.info {
                    "Demo data looks fresh ($freshCoreCount recent core events, $freshLlmCount recent LLM generations, " +
                        "$freshAnalyticsCount recent analytics events, $freshLogsCount recent logs, " +
                        "$freshDatadogCount recent Datadog spans, $demoDashboardCount demo dashboards), skipping reseed"
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

            if (freshAnalyticsCount > 0) {
                logger.info { "Analytics demo data is fresh ($freshAnalyticsCount recent events), skipping analytics reseed" }
            } else {
                logger.info { "Analytics demo data is stale or missing, reseeding..." }
                purgeAnalyticsDemoData()
                reseedAnalyticsEvents()
            }

            if (freshLogsCount > 0) {
                logger.info { "Log demo data is fresh ($freshLogsCount recent logs), skipping logs reseed" }
            } else {
                logger.info { "Log demo data is stale or missing, reseeding..." }
                purgeLogsDemoData()
                reseedLogs()
            }

            if (freshDatadogCount > 0) {
                logger.info { "Datadog demo data is fresh ($freshDatadogCount recent spans), skipping Datadog reseed" }
            } else {
                logger.info { "Datadog demo data is stale or missing, reseeding..." }
                purgeDatadogDemoData()
                reseedDatadogData()
            }

            if (demoDashboardCount >= 4) {
                logger.info { "Demo dashboards are present ($demoDashboardCount), skipping dashboard reseed" }
            } else {
                logger.info { "Demo dashboards missing or incomplete ($demoDashboardCount found), reseeding..." }
                seedDemoDashboards()
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

    private fun countDemoDashboards(): Long =
        runCatching {
            transaction {
                Dashboards.selectAll()
                    .where { (Dashboards.orgId eq DEMO_ORG_ID) and (Dashboards.createdBy eq DEMO_USER_ID) }
                    .count()
            }
        }.getOrElse {
            logger.warn { "Failed to count demo dashboards (non-fatal): ${it.message}" }
            0L
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

    private suspend fun checkFreshLogsCount(): Long {
        val query =
            """
            SELECT count() as cnt
            FROM logs
            WHERE project_id IN ($P1, $P2, $P3)
                AND timestamp >= now() - INTERVAL 2 HOUR
            """.trimIndent()
        return runCatching {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299) return 0
            body.trim().toLongOrNull() ?: 0
        }.getOrElse {
            logger.warn { "Failed to check fresh logs demo data (non-fatal): ${it.message}" }
            0
        }
    }

    private suspend fun purgeLogsDemoData() {
        runCatching {
            ClickHouseClient.execute("ALTER TABLE logs DELETE WHERE project_id IN ($P1, $P2, $P3)")
        }.onFailure { logger.warn { "Purge logs failed (non-fatal): ${it.message}" } }
    }

    private suspend fun reseedLogs() {
        val msgCase =
            """
                CASE number % 8
                    WHEN 0 THEN concat(
                        'HTTP GET /api/products completed in ', toString(45 + number % 200), 'ms with status 200')
                    WHEN 1 THEN concat(
                        'HTTP POST /api/orders completed in ', toString(123 + number % 300), 'ms with status 201')
                    WHEN 2 THEN concat(
                        'User user', toString(number % 50), '@example.com authenticated successfully')
                    WHEN 3 THEN concat('Cache miss for key: product:', toString(100 + number % 900))
                    WHEN 4 THEN concat(
                        'Rate limit approaching for IP 192.168.1.',
                        toString(number % 254), ': ', toString(950 + number % 50), '/1000 requests')
                    WHEN 5 THEN concat(
                        'Database connection timeout after ', toString(30 + number % 30),
                        's for query: SELECT * FROM orders')
                    WHEN 6 THEN concat(
                        'Payment processing failed for order ORD-',
                        toString(10000 + number % 90000), ': card_declined')
                    ELSE concat(
                        'Redis command executed: GET product:', toString(number % 500),
                        ' in ', toString(2 + number % 20), 'ms')
                END
            """.trimIndent()
        val tagsServiceCase =
            "CASE number % 5 WHEN 0 THEN 'api-server' WHEN 1 THEN 'auth-service' " +
                "WHEN 2 THEN 'payment-processor' WHEN 3 THEN 'notification-service' ELSE 'cache-service' END"
        val tagsEnvCase = "CASE number % 7 WHEN 0 THEN 'staging' ELSE 'production' END"
        val sql =
            """
            INSERT INTO logs (
                log_id, project_id, timestamp, received_at, level, message, body,
                service, environment, host, source, trace_id, span_id, tags,
                container_name, container_id, container_image, resource_attributes
            )
            SELECT
                generateUUIDv4() AS log_id,
                CASE number % 3 WHEN 0 THEN $P1 WHEN 1 THEN $P2 ELSE $P3 END AS project_id,
                now64(3) - INTERVAL (
                    CASE
                        WHEN number < $LOG_BUCKET_1_MAX THEN number % 10
                        WHEN number < $LOG_BUCKET_2_MAX THEN 10 + (number % 20)
                        WHEN number < $LOG_BUCKET_3_MAX THEN 30 + (number % 30)
                        ELSE $LOG_BUCKET_4_BASE_MINUTES + (number % 60)
                    END * 60 + number % 60
                ) SECOND AS timestamp,
                now64(3) AS received_at,
                CASE (number * 7 + 3) % 100
                    WHEN 0 THEN 'debug'
                    WHEN 1 THEN 'debug'
                    WHEN 2 THEN 'debug'
                    WHEN 3 THEN 'debug'
                    WHEN 4 THEN 'debug'
                    WHEN 5 THEN 'error'
                    WHEN 6 THEN 'error'
                    WHEN 7 THEN 'error'
                    WHEN 8 THEN 'error'
                    WHEN 9 THEN 'error'
                    WHEN 10 THEN 'error'
                    WHEN 11 THEN 'error'
                    WHEN 12 THEN 'error'
                    WHEN 13 THEN 'error'
                    WHEN 14 THEN 'error'
                    WHEN 15 THEN 'warn'
                    WHEN 16 THEN 'warn'
                    WHEN 17 THEN 'warn'
                    WHEN 18 THEN 'warn'
                    WHEN 19 THEN 'warn'
                    WHEN 20 THEN 'warn'
                    WHEN 21 THEN 'warn'
                    WHEN 22 THEN 'warn'
                    WHEN 23 THEN 'warn'
                    WHEN 24 THEN 'warn'
                    WHEN 25 THEN 'warn'
                    WHEN 26 THEN 'warn'
                    WHEN 27 THEN 'warn'
                    WHEN 28 THEN 'warn'
                    WHEN 29 THEN 'warn'
                    WHEN 30 THEN 'warn'
                    WHEN 31 THEN 'warn'
                    WHEN 32 THEN 'warn'
                    WHEN 33 THEN 'warn'
                    WHEN 34 THEN 'warn'
                    WHEN 35 THEN 'warn'
                    WHEN 36 THEN 'warn'
                    WHEN 37 THEN 'warn'
                    WHEN 38 THEN 'warn'
                    WHEN 39 THEN 'warn'
                    ELSE 'info'
                END AS level,
                $msgCase AS message,
                $msgCase AS body,
                CASE number % 5
                    WHEN 0 THEN 'api-server'
                    WHEN 1 THEN 'auth-service'
                    WHEN 2 THEN 'payment-processor'
                    WHEN 3 THEN 'notification-service'
                    ELSE 'cache-service'
                END AS service,
                CASE number % 7
                    WHEN 0 THEN 'staging'
                    ELSE 'production'
                END AS environment,
                CASE number % 5
                    WHEN 0 THEN 'api-prod-1'
                    WHEN 1 THEN 'api-prod-2'
                    WHEN 2 THEN 'api-prod-3'
                    WHEN 3 THEN 'worker-prod-1'
                    ELSE 'worker-prod-2'
                END AS host,
                'sdk' AS source,
                lower(hex(generateUUIDv4())) AS trace_id,
                substring(lower(hex(generateUUIDv4())), 1, 16) AS span_id,
                map(
                    'service', $tagsServiceCase,
                    'environment', $tagsEnvCase,
                    'version', concat('1.', toString(number % 5), '.', toString(number % 10))
                ) AS tags,
                '' AS container_name,
                '' AS container_id,
                '' AS container_image,
                map() AS resource_attributes
            FROM numbers($LOG_SEED_ROWS)
            """.trimIndent()
        runCatching { ClickHouseClient.execute(sql) }
            .onFailure { logger.warn { "Reseed logs failed (non-fatal): ${it.message}" } }
    }

    // ── Datadog Agent Demo Data ─────────────────────────────────────────────

    private const val ORG1 = "toUInt64(-1)"

    private suspend fun checkFreshDatadogCount(): Long {
        val query =
            """
            SELECT count() as cnt
            FROM apm_spans
            WHERE organization_id = $ORG1
                AND start >= now() - INTERVAL 2 HOUR
            """.trimIndent()
        return runCatching {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299) return 0
            body.trim().toLongOrNull() ?: 0
        }.getOrElse {
            logger.warn { "Failed to check fresh Datadog demo data (non-fatal): ${it.message}" }
            0
        }
    }

    private suspend fun purgeDatadogDemoData() {
        val tables = listOf(
            "apm_spans", "trace_stats", "profiles", "infra_events",
            "service_checks", "processes", "containers", "network_connections"
        )
        for (table in tables) {
            runCatching {
                ClickHouseClient.execute("ALTER TABLE $table DELETE WHERE organization_id = $ORG1")
            }.onFailure { logger.warn { "Purge $table failed (non-fatal): ${it.message}" } }
        }
        // PostgreSQL hosts
        runCatching {
            transaction {
                exec("DELETE FROM hosts WHERE organization_id = -1")
            }
        }.onFailure { logger.warn { "Purge hosts failed (non-fatal): ${it.message}" } }
    }

    @Suppress("LongMethod")
    private suspend fun reseedDatadogData() {
        // Hosts (PostgreSQL)
        runCatching {
            transaction {
                val hostData = listOf(
                    listOf("prod-web-01", "Ubuntu 22.04", "linux", "Intel Xeon E5-2686 v4", "8", "16384000", "7.52.1"),
                    listOf("prod-web-02", "Ubuntu 22.04", "linux", "Intel Xeon E5-2686 v4", "8", "16384000", "7.52.1"),
                    listOf("prod-api-01", "Ubuntu 22.04", "linux", "AMD EPYC 7R13", "16", "32768000", "7.52.1"),
                    listOf("prod-db-01", "Ubuntu 22.04", "linux", "AMD EPYC 7R13", "32", "65536000", "7.52.1"),
                    listOf("prod-cache-01", "Ubuntu 22.04", "linux", "Intel Xeon E5-2686 v4", "4", "8192000", "7.52.1"),
                    listOf("prod-worker-01", "Ubuntu 22.04", "linux", "AMD EPYC 7R13", "8", "16384000", "7.52.1"),
                )
                for (h in hostData) {
                    exec(
                        """
                        INSERT INTO hosts (organization_id, hostname, os, platform, processor, cpu_cores, memory_total_kb, agent_version, gohai, tags, first_seen_at, last_seen_at)
                        VALUES (-1, '${h[0]}', '${h[1]}', '${h[2]}', '${h[3]}', ${h[4]}, ${h[5]}, '${h[6]}',
                            '{}', '{"env":"production","service":"acme-shopping"}', NOW() - INTERVAL '14 days', NOW() - INTERVAL '30 seconds')
                        ON CONFLICT (organization_id, hostname) DO UPDATE SET last_seen_at = NOW() - INTERVAL '30 seconds'
                        """.trimIndent()
                    )
                }
            }
        }.onFailure { logger.warn { "Reseed hosts failed (non-fatal): ${it.message}" } }

        // APM Spans — generate 20 traces with child spans using ClickHouse numbers()
        val services = listOf("api-gateway", "user-service", "product-service", "order-service", "payment-service")
        val resources = listOf(
            "GET /api/v1/products", "POST /api/v1/orders", "GET /api/v1/users/{id}",
            "POST /api/v1/checkout", "GET /api/v1/cart"
        )
        val hosts = listOf("prod-web-01", "prod-api-01", "prod-worker-01")

        // Root spans
        val rootSpansSql =
            """
            INSERT INTO apm_spans (
                span_id, trace_id, parent_id, organization_id, name, service,
                resource, type, start, duration, error, meta, metrics, host, env, version
            )
            SELECT
                reinterpretAsUInt64(sipHash64(number, 1)),
                reinterpretAsUInt64(sipHash64(number, 0)),
                0,
                $ORG1,
                'http.request',
                arrayElement(['api-gateway', 'api-gateway', 'user-service', 'order-service', 'payment-service'], number % 5 + 1),
                arrayElement(['GET /api/v1/products', 'POST /api/v1/orders', 'GET /api/v1/users/{id}', 'POST /api/v1/checkout', 'GET /api/v1/cart'], number % 5 + 1),
                'web',
                now() - INTERVAL (number * 37 % 120) MINUTE,
                20000000 + (sipHash64(number, 2) % 480000000),
                if(number % 7 = 0, 1, 0),
                map('http.method', arrayElement(['GET', 'POST', 'GET', 'POST', 'GET'], number % 5 + 1),
                    'http.url', arrayElement(['GET /api/v1/products', 'POST /api/v1/orders', 'GET /api/v1/users/{id}', 'POST /api/v1/checkout', 'GET /api/v1/cart'], number % 5 + 1),
                    'http.status_code', if(number % 7 = 0, '500', '200')),
                map('_sample_rate', 1.0),
                arrayElement(['prod-web-01', 'prod-web-02', 'prod-api-01'], number % 3 + 1),
                'production',
                '1.3.0'
            FROM numbers(20)
            """.trimIndent()
        runCatching { ClickHouseClient.execute(rootSpansSql) }
            .onFailure { logger.warn { "Reseed root spans failed (non-fatal): ${it.message}" } }

        // Child spans (3 per root trace = 60 more spans)
        val childSpansSql =
            """
            INSERT INTO apm_spans (
                span_id, trace_id, parent_id, organization_id, name, service,
                resource, type, start, duration, error, meta, metrics, host, env, version
            )
            SELECT
                reinterpretAsUInt64(sipHash64(number, 10 + number % 3)),
                reinterpretAsUInt64(sipHash64(intDiv(number, 3), 0)),
                reinterpretAsUInt64(sipHash64(intDiv(number, 3), 1)),
                $ORG1,
                arrayElement(['http.request', 'postgresql.query', 'redis.command'], number % 3 + 1),
                arrayElement(['user-service', 'postgres', 'cache-service', 'product-service', 'order-service', 'inventory-service'], number % 6 + 1),
                arrayElement(['SELECT * FROM users WHERE id = ?', 'GET cache:product:*', 'POST /api/v1/orders', 'GET /api/v1/products', 'POST /api/v1/checkout', 'worker.process'], number % 6 + 1),
                arrayElement(['web', 'sql', 'cache', 'web', 'web', 'worker'], number % 6 + 1),
                now() - INTERVAL (intDiv(number, 3) * 37 % 120) MINUTE + INTERVAL (number % 3 + 1) * 5 SECOND,
                2000000 + (sipHash64(number, 20) % 100000000),
                0,
                map('component', arrayElement(['user-service', 'postgres', 'cache-service', 'product-service', 'order-service', 'inventory-service'], number % 6 + 1)),
                map('_sample_rate', 1.0),
                arrayElement(['prod-api-01', 'prod-db-01', 'prod-cache-01', 'prod-api-01', 'prod-api-01', 'prod-worker-01'], number % 6 + 1),
                'production',
                '1.3.0'
            FROM numbers(60)
            """.trimIndent()
        runCatching { ClickHouseClient.execute(childSpansSql) }
            .onFailure { logger.warn { "Reseed child spans failed (non-fatal): ${it.message}" } }

        // Profiles
        val profilesSql =
            """
            INSERT INTO profiles (
                profile_id, organization_id, host, service, env, version,
                runtime, language, profile_type, start_time, end_time, duration_ns,
                storage_key, tags, size_bytes
            )
            SELECT
                generateUUIDv4(),
                $ORG1,
                arrayElement(['prod-web-01', 'prod-api-01', 'prod-worker-01', 'prod-web-02', 'prod-api-01'], number % 5 + 1),
                arrayElement(['api-gateway', 'user-service', 'order-service', 'product-service', 'inventory-service'], number % 5 + 1),
                'production',
                '1.3.0',
                'go1.21',
                'go',
                arrayElement(['cpu', 'heap', 'allocs', 'goroutine', 'block'], number % 5 + 1),
                now() - INTERVAL (number * 47 % 1440) MINUTE,
                now() - INTERVAL (number * 47 % 1440) MINUTE + INTERVAL 60 SECOND,
                60000000000,
                concat('-1/', toString(generateUUIDv4()), '.pprof.gz'),
                map('service', arrayElement(['api-gateway', 'user-service', 'order-service', 'product-service', 'inventory-service'], number % 5 + 1),
                    'env', 'production'),
                50000 + sipHash64(number, 30) % 450000
            FROM numbers(15)
            """.trimIndent()
        runCatching { ClickHouseClient.execute(profilesSql) }
            .onFailure { logger.warn { "Reseed profiles failed (non-fatal): ${it.message}" } }

        // Infrastructure Events
        val eventsSql =
            """
            INSERT INTO infra_events (
                event_id, organization_id, title, text, timestamp, priority, host,
                tags, alert_type, aggregation_key, source_type_name, device_name
            )
            SELECT
                generateUUIDv4(),
                $ORG1,
                arrayElement([
                    'Deployment started: api-gateway v1.3.0',
                    'Deployment completed: api-gateway v1.3.0',
                    'High memory usage on prod-db-01',
                    'Auto-scaling triggered: order-service',
                    'SSL certificate renewed: *.acme.com',
                    'Database backup completed',
                    'Rate limiting activated: /api/v1/search',
                    'Pod restart: payment-service-7f8d9c',
                    'Cache eviction spike on prod-cache-01',
                    'Deployment rolled back: user-service v1.2.9'
                ], number % 10 + 1),
                arrayElement([
                    'Rolling deployment initiated for api-gateway. 4 pods updating.',
                    'All pods healthy. Zero-downtime deployment successful.',
                    'Memory utilization at 87%. Consider scaling or optimizing queries.',
                    'CPU above 80% for 5 minutes. Scaling from 3 to 5 replicas.',
                    'Certificate auto-renewed via Let''s Encrypt. Valid until 2026-05-25.',
                    'Full backup of prod-db-01 completed. Size: 42.3GB, Duration: 12m34s.',
                    'Request rate exceeded 1000/min threshold from 203.0.113.42.',
                    'Container OOMKilled. Memory limit: 512Mi. Peak usage: 498Mi.',
                    'Redis evicted 15,000 keys in last 5 minutes. maxmemory-policy: allkeys-lru.',
                    'Health check failures exceeded threshold. Automatic rollback to v1.2.8.'
                ], number % 10 + 1),
                now() - INTERVAL (number * 7) HOUR,
                'normal',
                arrayElement(['prod-web-01', 'prod-web-01', 'prod-db-01', 'prod-api-01', 'prod-web-01', 'prod-db-01', 'prod-web-01', 'prod-api-01', 'prod-cache-01', 'prod-api-01'], number % 10 + 1),
                map('env', 'production'),
                arrayElement(['info', 'success', 'warning', 'warning', 'info', 'info', 'warning', 'error', 'warning', 'error'], number % 10 + 1),
                '',
                arrayElement(['deployment', 'deployment', 'system', 'kubernetes', 'cert-manager', 'backup', 'api-gateway', 'kubernetes', 'redis', 'deployment'], number % 10 + 1),
                ''
            FROM numbers(10)
            """.trimIndent()
        runCatching { ClickHouseClient.execute(eventsSql) }
            .onFailure { logger.warn { "Reseed infra_events failed (non-fatal): ${it.message}" } }

        // Service Checks (8 check types × 6 hosts)
        val checksSql =
            """
            INSERT INTO service_checks (
                check_id, organization_id, check_name, host, status, timestamp, tags, message
            )
            SELECT
                generateUUIDv4(),
                $ORG1,
                arrayElement(['datadog.agent.up', 'http.can_connect', 'postgres.can_connect', 'redis.can_ping', 'disk.check', 'ntp.offset', 'tls.cert_expiry', 'http.can_connect'], number % 8 + 1),
                arrayElement(['prod-web-01', 'prod-web-02', 'prod-api-01', 'prod-db-01', 'prod-cache-01', 'prod-worker-01'], intDiv(number, 8) % 6 + 1),
                arrayElement(['ok', 'ok', 'ok', 'ok', 'warning', 'ok', 'ok', 'critical'], number % 8 + 1),
                now() - INTERVAL (number % 60) MINUTE,
                map('env', 'production'),
                arrayElement([
                    'Agent is reporting normally',
                    'HTTP connection successful (200)',
                    'PostgreSQL connection established',
                    'Redis PONG received in 0.3ms',
                    'Disk usage at 82% on /dev/sda1',
                    'NTP offset: +12ms',
                    'Certificate valid for 89 days',
                    'Connection refused on port 8443'
                ], number % 8 + 1)
            FROM numbers(48)
            """.trimIndent()
        runCatching { ClickHouseClient.execute(checksSql) }
            .onFailure { logger.warn { "Reseed service_checks failed (non-fatal): ${it.message}" } }

        // Processes
        val processesSql =
            """
            INSERT INTO processes (
                process_id, organization_id, host, pid, name, command, user,
                cpu_percent, mem_rss, mem_vms, state, thread_count, open_fd_count,
                tags, timestamp
            )
            SELECT
                generateUUIDv4(),
                $ORG1,
                arrayElement(['prod-web-01', 'prod-web-02', 'prod-api-01', 'prod-db-01', 'prod-cache-01', 'prod-worker-01'], intDiv(number, 7) % 6 + 1),
                1000 + number * 100,
                arrayElement(['nginx', 'api-gateway', 'user-service', 'postgres', 'redis-server', 'datadog-agent', 'containerd'], number % 7 + 1),
                arrayElement([
                    '/usr/sbin/nginx -g daemon off;',
                    '/app/api-gateway serve --port 8080',
                    'java -jar /app/user-service.jar',
                    '/usr/lib/postgresql/15/bin/postgres -D /var/lib/postgresql/15/main',
                    'redis-server *:6379',
                    '/opt/datadog-agent/bin/agent/agent run',
                    '/usr/bin/containerd'
                ], number % 7 + 1),
                arrayElement(['root', 'appuser', 'appuser', 'postgres', 'redis', 'dd-agent', 'root'], number % 7 + 1),
                0.5 + (sipHash64(number, 40) % 4000) / 100.0,
                10485760 + sipHash64(number, 41) % 2000000000,
                20971520 + sipHash64(number, 42) % 4000000000,
                'running',
                1 + sipHash64(number, 43) % 48,
                3 + sipHash64(number, 44) % 253,
                map('env', 'production'),
                now() - INTERVAL (number % 30) MINUTE
            FROM numbers(42)
            """.trimIndent()
        runCatching { ClickHouseClient.execute(processesSql) }
            .onFailure { logger.warn { "Reseed processes failed (non-fatal): ${it.message}" } }

        // Containers
        val containersSql =
            """
            INSERT INTO containers (
                container_id_hash, organization_id, host, container_id, name, image, state,
                cpu_percent, mem_usage, mem_limit, net_rx_bytes, net_tx_bytes,
                tags, timestamp
            )
            SELECT
                generateUUIDv4(),
                $ORG1,
                arrayElement(['prod-web-01', 'prod-web-02', 'prod-api-01', 'prod-db-01', 'prod-cache-01', 'prod-worker-01'], intDiv(number, 7) % 6 + 1),
                substring(toString(sipHash64(number, 50)), 1, 12),
                arrayElement(['api-gateway', 'user-service', 'product-service', 'order-service', 'payment-service', 'nginx-ingress', 'datadog-agent'], number % 7 + 1),
                arrayElement(['acme/api-gateway:1.3.0', 'acme/user-service:1.2.8', 'acme/product-service:1.4.1', 'acme/order-service:2.0.3', 'acme/payment-service:1.1.5', 'nginx/nginx-ingress:3.4.0', 'datadog/agent:7.52.1'], number % 7 + 1),
                'running',
                0.5 + (sipHash64(number, 51) % 6000) / 100.0,
                268435456 + sipHash64(number, 52) % 3500000000,
                4294967296,
                1048576 + sipHash64(number, 53) % 500000000,
                524288 + sipHash64(number, 54) % 250000000,
                map('env', 'production', 'service', arrayElement(['api-gateway', 'user-service', 'product-service', 'order-service', 'payment-service', 'nginx-ingress', 'datadog-agent'], number % 7 + 1)),
                now() - INTERVAL (number % 30) MINUTE
            FROM numbers(42)
            """.trimIndent()
        runCatching { ClickHouseClient.execute(containersSql) }
            .onFailure { logger.warn { "Reseed containers failed (non-fatal): ${it.message}" } }

        // Network Connections
        val connSql =
            """
            INSERT INTO network_connections (
                connection_id, organization_id, host, pid, local_addr, local_port,
                remote_addr, remote_port, protocol, family, direction,
                bytes_sent, bytes_recv, tags, timestamp
            )
            SELECT
                generateUUIDv4(),
                $ORG1,
                arrayElement(['prod-web-01', 'prod-api-01', 'prod-api-01', 'prod-web-02', 'prod-worker-01', 'prod-worker-01', 'prod-web-01', 'prod-web-02'], number % 8 + 1),
                1000 + number * 111,
                arrayElement(['prod-web-01', 'prod-api-01', 'prod-api-01', 'prod-web-02', 'prod-worker-01', 'prod-worker-01', 'prod-web-01', 'prod-web-02'], number % 8 + 1),
                arrayElement([8080, 8080, 8080, 8080, 8080, 8080, 443, 443], number % 8 + 1),
                arrayElement(['prod-api-01', 'prod-db-01', 'prod-cache-01', 'prod-api-01', 'prod-db-01', 'prod-cache-01', '0.0.0.0', '0.0.0.0'], number % 8 + 1),
                arrayElement([8080, 5432, 6379, 8080, 5432, 6379, 0, 0], number % 8 + 1),
                'tcp',
                'IPv4',
                arrayElement(['outgoing', 'outgoing', 'outgoing', 'outgoing', 'outgoing', 'outgoing', 'incoming', 'incoming'], number % 8 + 1),
                10240 + sipHash64(number, 60) % 104857600,
                10240 + sipHash64(number, 61) % 104857600,
                map('env', 'production'),
                now() - INTERVAL (number % 30) MINUTE
            FROM numbers(8)
            """.trimIndent()
        runCatching { ClickHouseClient.execute(connSql) }
            .onFailure { logger.warn { "Reseed network_connections failed (non-fatal): ${it.message}" } }

        logger.info { "Datadog agent demo data reseed complete" }
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
                    groupBy = listOf(GroupByDef("started", GroupByType.TIME, "1 HOUR")),
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
