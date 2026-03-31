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

internal suspend fun checkFreshAnalyticsDataCount(): Long {
    val query =
        """
        SELECT count() as cnt
        FROM analytics_events
        WHERE project_id IN ($P1, $P2, $P3)
            AND timestamp >= now() - INTERVAL 7 DAY
        """.trimIndent()
    return suspendRunCatching {
        val response = ClickHouseClient.execute(query)
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) return 0
        body.trim().toLongOrNull() ?: 0
    }.getOrElse {
        logger.warn { "Failed to check fresh analytics demo data (non-fatal): ${it.message}" }
        0
    }
}
internal suspend fun purgeAnalyticsDemoData() {
    suspendRunCatching {
        ClickHouseClient.execute("ALTER TABLE analytics_events DELETE WHERE project_id IN ($P1, $P2, $P3)")
    }.onFailure { logger.warn { "Purge analytics_events failed (non-fatal): ${it.message}" } }

    suspendRunCatching {
        ClickHouseClient.execute("ALTER TABLE analytics_sessions_hourly DELETE WHERE project_id IN ($P1, $P2, $P3)")
    }.onFailure { logger.warn { "Purge analytics_sessions_hourly failed (non-fatal): ${it.message}" } }
}
internal suspend fun reseedAnalyticsEvents() {
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

    suspendRunCatching { ClickHouseClient.execute(sql) }
        .onFailure { logger.warn { "Reseed analytics_events failed (non-fatal): ${it.message}" } }
}
