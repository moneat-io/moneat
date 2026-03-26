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

package com.moneat.analytics.services

import java.net.URI
import java.net.URISyntaxException

/**
 * Parses referrer URLs into human-readable source names.
 * Maps known domains to their brand names; everything else returns the hostname.
 */
object ReferrerParser {

    private val sourceMap = mapOf(
        "google" to "Google",
        "bing" to "Bing",
        "yahoo" to "Yahoo",
        "duckduckgo" to "DuckDuckGo",
        "baidu" to "Baidu",
        "yandex" to "Yandex",
        "ecosia" to "Ecosia",
        "facebook" to "Facebook",
        "instagram" to "Instagram",
        "twitter" to "Twitter",
        "x" to "Twitter",
        "linkedin" to "LinkedIn",
        "reddit" to "Reddit",
        "pinterest" to "Pinterest",
        "youtube" to "YouTube",
        "tiktok" to "TikTok",
        "github" to "GitHub",
        "stackoverflow" to "Stack Overflow",
        "producthunt" to "Product Hunt",
        "hackernews" to "Hacker News",
        "news.ycombinator" to "Hacker News",
        "t.co" to "Twitter",
        "l.facebook" to "Facebook",
        "lnkd.in" to "LinkedIn",
        "out.reddit" to "Reddit",
    )

    /**
     * Returns the human-readable source name for a referrer URL.
     * Returns "Direct" for empty/null referrers, or the hostname for unknown domains.
     */
    fun parse(referrer: String?): String {
        if (referrer.isNullOrBlank()) return "Direct"
        val host = try {
            URI(referrer).host?.lowercase() ?: return "Direct"
        } catch (_: URISyntaxException) {
            return "Direct"
        }

        // Check exact match and partial match against known sources
        for ((key, name) in sourceMap) {
            if (host == key || host.contains(".$key.") || host.endsWith(".$key") || host.startsWith("$key.")) {
                return name
            }
        }

        // Return cleaned hostname for unknown referrers
        return host.removePrefix("www.")
    }
}
