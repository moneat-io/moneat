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

@file:Suppress("MagicNumber")

package com.moneat.config

import com.moneat.utils.suspendRunCatching
import io.ktor.client.statement.bodyAsText
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

// ── User Feedback Demo Data ────────────────────────────────────────────
//
// ClickHouse `user_feedback` for the three demo projects (-1 Android / -2 iOS / -3 RN),
// inserted relative to now() so the Feedback list (which reads `user_feedback FINAL` filtered
// by project + status) renders fresh. Was previously only seeded by the one-time CH migration
// V7, whose rows age out via the table's 90-day TTL.

internal suspend fun checkFreshFeedbackCount(): Long {
    val query = """
        SELECT count() FROM user_feedback
        WHERE project_id IN ($P1, $P2, $P3)
            AND timestamp >= now() - INTERVAL 7 DAY
    """.trimIndent()
    return suspendRunCatching {
        val response = ClickHouseClient.execute(query)
        if (response.status.value !in 200..299) {
            0L
        } else {
            response.bodyAsText().trim().toLongOrNull() ?: 0L
        }
    }.getOrElse {
        logger.warn { "Failed to check fresh feedback demo data (non-fatal): ${it.message}" }
        0L
    }
}

internal suspend fun purgeFeedbackDemoData() {
    suspendRunCatching {
        requireClickHouse2xx(
            ClickHouseClient.execute("ALTER TABLE user_feedback DELETE WHERE project_id IN ($P1, $P2, $P3)"),
            "Purge user_feedback"
        )
    }.onFailure { logger.warn { "Purge user_feedback failed (non-fatal): ${it.message}" } }
}

internal suspend fun reseedFeedback() {
    suspendRunCatching {
        requireClickHouse2xx(ClickHouseClient.execute(buildFeedbackInsertSql()), "Reseed user_feedback")
    }.onFailure { logger.warn { "Reseed user_feedback failed (non-fatal): ${it.message}" } }
    logger.info { "User feedback demo data reseed complete" }
}

private fun buildFeedbackInsertSql(): String =
    """
        INSERT INTO user_feedback (
            feedback_id, project_id, timestamp, received_at, message, contact_email, name, url,
            associated_event_id, replay_id, environment, release, platform, user_id, user_email,
            user_username, user_ip_address, sdk_name, sdk_version, tags, status, updated_at
        )
        SELECT
            generateUUIDv4(),
            arrayElement([$P1, $P2, $P3], number % 3 + 1),
            now64(3) - INTERVAL (number * 67 % 20160) MINUTE,
            now64(3) - INTERVAL (number * 67 % 20160) MINUTE,
            arrayElement([
                'Checkout keeps spinning after I tap Pay. Had to retry three times.',
                'Love the new product gallery, but images take a while to load on mobile data.',
                'App crashed when I applied a promo code at checkout.',
                'Search results feel way more relevant now, nice work!',
                'Cannot update my shipping address - the Save button does nothing.',
                'Push notifications for order updates stopped arriving last week.',
                'The dark theme is gorgeous. Please make it the default.',
                'Got logged out randomly while browsing my cart.',
                'Filtering by size resets the page to the top every time.',
                'Payment failed but I was still charged - very stressful.',
                'Wishlist sync between my phone and tablet is finally working.',
                'Fonts are tiny on the order confirmation screen.'
            ], number % 12 + 1),
            concat('user', toString(number % 90), '@example.com'),
            arrayElement([
                'Jordan Lee', 'Sam Rivera', 'Alex Chen', 'Priya Nair', 'Diego Santos',
                'Mia Kowalski', 'Tom Becker', 'Aisha Khan', 'Noah Wright', 'Lena Fischer'
            ], number % 10 + 1),
            arrayElement([
                'https://shop.acme.com/checkout',
                'https://shop.acme.com/cart',
                'https://shop.acme.com/product/SKU-1042',
                'https://shop.acme.com/orders',
                'https://shop.acme.com/account'
            ], number % 5 + 1),
            '',
            '',
            if(number % 8 = 0, 'staging', 'production'),
            arrayElement(['1.3.0', '2.1.0', '3.0.1'], number % 3 + 1),
            arrayElement(['android', 'ios', 'react-native'], number % 3 + 1),
            concat('user', toString(number % 90), '@example.com'),
            concat('user', toString(number % 90), '@example.com'),
            concat('user', toString(number % 90)),
            '',
            arrayElement(['sentry.java.android', 'sentry.cocoa', 'sentry.javascript.react-native'], number % 3 + 1),
            arrayElement(['7.14.0', '8.21.0', '5.33.1'], number % 3 + 1),
            map(
                'sentiment',
                arrayElement(['negative', 'negative', 'positive', 'neutral'], number % 4 + 1)
            ),
            arrayElement(['unresolved', 'unresolved', 'unresolved', 'resolved', 'resolved', 'archived'], number % 6 + 1),
            now64(3)
        FROM numbers(48)
    """.trimIndent()
