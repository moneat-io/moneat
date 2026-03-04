// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.analytics.services

import java.net.URI

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
        } catch (_: Exception) {
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
