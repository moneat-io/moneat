// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.analytics.services

/**
 * Parses User-Agent strings into browser, OS, and device type.
 * Lightweight regex-based parser — no external dependency required.
 */
object UserAgentService {

    data class ParsedUA(
        val browser: String,
        val browserVersion: String,
        val os: String,
        val osVersion: String,
        val deviceType: String,
    )

    fun parse(ua: String?): ParsedUA {
        if (ua.isNullOrBlank()) return ParsedUA("Unknown", "", "Unknown", "", "Desktop")
        val browser = detectBrowser(ua)
        val os = detectOS(ua)
        val deviceType = detectDeviceType(ua)
        return ParsedUA(
            browser = browser.first,
            browserVersion = browser.second,
            os = os.first,
            osVersion = os.second,
            deviceType = deviceType,
        )
    }

    private fun detectBrowser(ua: String): Pair<String, String> {
        val patterns = listOf(
            "Edg(?:e|A|iOS)?/(\\S+)" to "Edge",
            "OPR/(\\S+)" to "Opera",
            "Vivaldi/(\\S+)" to "Vivaldi",
            "YaBrowser/(\\S+)" to "Yandex Browser",
            "SamsungBrowser/(\\S+)" to "Samsung Internet",
            "UCBrowser/(\\S+)" to "UC Browser",
            "CriOS/(\\S+)" to "Chrome",
            "FxiOS/(\\S+)" to "Firefox",
            "Chrome/(\\S+)" to "Chrome",
            "Firefox/(\\S+)" to "Firefox",
            "Version/(\\S+).*Safari" to "Safari",
            "Safari/(\\S+)" to "Safari",
            "MSIE (\\S+)" to "IE",
            "Trident/.*rv:(\\S+)" to "IE",
        )
        for ((pattern, name) in patterns) {
            val match = Regex(pattern).find(ua)
            if (match != null) {
                val version = match.groupValues[1].substringBefore(" ")
                return name to majorVersion(version)
            }
        }
        return "Other" to ""
    }

    private fun detectOS(ua: String): Pair<String, String> {
        return when {
            ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iPod") -> {
                val v = Regex("OS (\\d+[_.]\\d+)").find(ua)?.groupValues?.get(1)?.replace('_', '.') ?: ""
                "iOS" to v
            }
            ua.contains("Android") -> {
                val v = Regex("Android (\\d+\\.?\\d*)").find(ua)?.groupValues?.get(1) ?: ""
                "Android" to v
            }
            ua.contains("Windows") -> {
                val v = Regex("Windows NT (\\d+\\.\\d+)").find(ua)?.groupValues?.get(1) ?: ""
                "Windows" to mapWindowsVersion(v)
            }
            ua.contains("Mac OS X") || ua.contains("Macintosh") -> {
                val v = Regex("Mac OS X (\\d+[_.]\\d+)").find(ua)?.groupValues?.get(1)?.replace('_', '.') ?: ""
                "macOS" to v
            }
            ua.contains("Linux") -> "Linux" to ""
            ua.contains("CrOS") -> "Chrome OS" to ""
            else -> "Other" to ""
        }
    }

    private fun detectDeviceType(ua: String): String {
        return when {
            ua.contains("Tablet") || ua.contains("iPad") -> "Tablet"
            ua.contains("Mobile") || ua.contains("iPhone") || ua.contains("Android") && !ua.contains("Tablet") -> "Mobile"
            else -> "Desktop"
        }
    }

    private fun majorVersion(version: String): String {
        return version.split(".").firstOrNull() ?: version
    }

    private fun mapWindowsVersion(ntVersion: String): String {
        return when (ntVersion) {
            "10.0" -> "10+"
            "6.3" -> "8.1"
            "6.2" -> "8"
            "6.1" -> "7"
            "6.0" -> "Vista"
            "5.1" -> "XP"
            else -> ntVersion
        }
    }
}
