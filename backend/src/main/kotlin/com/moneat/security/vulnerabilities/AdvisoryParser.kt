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

package com.moneat.security.vulnerabilities

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlin.time.Instant

object AdvisoryParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun parseDocument(raw: String): List<AdvisoryRecord> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return emptyList()
        return when (val root = json.parseToJsonElement(trimmed)) {
            is JsonArray -> root.flatMap { parseElement(it) }
            is JsonObject -> parseElement(root)
            else -> emptyList()
        }
    }

    fun parseNdjson(raw: String): List<AdvisoryRecord> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .flatMap { parseDocument(it) }
            .toList()

    private fun parseElement(element: JsonElement): List<AdvisoryRecord> {
        val obj = element as? JsonObject ?: return emptyList()
        return when {
            obj["affected"] != null -> parseOsv(obj)
            obj["vulnerabilities"] != null || obj["ghsa_id"] != null -> parseGhsa(obj)
            else -> emptyList()
        }
    }

    private fun parseOsv(root: JsonObject): List<AdvisoryRecord> {
        val advisoryId = root.s("id")
        if (advisoryId.isBlank()) return emptyList()
        val cveId = firstCve(root.a("aliases"))
        val cvssScore = root.obj("database_specific")?.d("cvss_score") ?: root.d("cvss_score")
        val severity = severityFrom(cvssScore, root.obj("database_specific")?.s("severity"))
        val link = "https://osv.dev/vulnerability/$advisoryId"
        val withdrawn = root["withdrawn"] != null
        return root.a("affected").mapNotNull { affectedElement ->
            val affected = affectedElement as? JsonObject ?: return@mapNotNull null
            val pkg = affected.obj("package") ?: return@mapNotNull null
            val name = SbomParser.clean(pkg.s("name"))
            if (name.isBlank()) return@mapNotNull null
            val ecosystem = SbomParser.normalizeEcosystem(pkg.s("ecosystem"))
            val ranges = parseOsvRanges(affected.a("ranges"))
            val fixedVersion = ranges.firstOrNull { !it.fixed.isNullOrBlank() }?.fixed
            AdvisoryRecord(
                source = "osv",
                advisoryId = advisoryId,
                cveId = cveId,
                packageName = name,
                ecosystem = ecosystem,
                packageType = ecosystem,
                affectedRanges = ranges,
                affectedVersions = affected.a("versions").mapNotNull { it.stringOrNull() },
                fixedVersion = fixedVersion,
                severity = severity,
                cvssScore = cvssScore,
                link = link,
                summary = root.s("summary").ifBlank { root.s("details") }.take(SUMMARY_MAX_LENGTH),
                rawAdvisory = root.toString(),
                withdrawn = withdrawn,
                publishedAt = root.instant("published"),
                modifiedAt = root.instant("modified"),
            )
        }
    }

    private fun parseOsvRanges(ranges: List<JsonElement>): List<AdvisoryRange> {
        val parsed = mutableListOf<AdvisoryRange>()
        ranges.forEach { rangeElement ->
            val range = rangeElement as? JsonObject ?: return@forEach
            val type = range.s("type").takeIf { it.isNotBlank() }
            val events = range.a("events")
            var introduced: String? = null
            events.forEach { eventElement ->
                val event = eventElement as? JsonObject ?: return@forEach
                val nextIntroduced = event.s("introduced").takeIf { it.isNotBlank() }
                val fixed = event.s("fixed").takeIf { it.isNotBlank() }
                val lastAffected = event.s("last_affected").takeIf { it.isNotBlank() }
                if (nextIntroduced != null) introduced = nextIntroduced
                if (fixed != null) {
                    parsed.add(AdvisoryRange(type = type, introduced = introduced ?: "0", fixed = fixed))
                    introduced = null
                }
                if (lastAffected != null) {
                    parsed.add(
                        AdvisoryRange(
                            type = type,
                            introduced = introduced ?: "0",
                            lastAffected = lastAffected,
                        )
                    )
                    introduced = null
                }
            }
            if (introduced != null) {
                parsed.add(AdvisoryRange(type = type, introduced = introduced))
            }
        }
        return parsed
    }

    private fun parseGhsa(root: JsonObject): List<AdvisoryRecord> {
        val advisoryId = root.s("ghsa_id").ifBlank { root.s("id") }
        if (advisoryId.isBlank()) return emptyList()
        val cveId = root.s("cve_id").takeIf { it.isNotBlank() } ?: firstCve(root.a("identifiers"))
        val cvssScore = root.obj("cvss")?.d("score") ?: root.d("cvss_score")
        val severity = severityFrom(cvssScore, root.s("severity"))
        val vulnerabilities = root.a("vulnerabilities")
        return vulnerabilities.mapNotNull { vulnerabilityElement ->
            val vulnerability = vulnerabilityElement as? JsonObject ?: return@mapNotNull null
            val pkg = vulnerability.obj("package") ?: return@mapNotNull null
            val name = SbomParser.clean(pkg.s("name"))
            if (name.isBlank()) return@mapNotNull null
            val ecosystem = SbomParser.normalizeEcosystem(pkg.s("ecosystem"))
            val fixedVersion = vulnerability.obj("first_patched_version")?.s("identifier")?.takeIf {
                it.isNotBlank()
            }
            AdvisoryRecord(
                source = "ghsa",
                advisoryId = advisoryId,
                cveId = cveId,
                packageName = name,
                ecosystem = ecosystem,
                packageType = ecosystem,
                affectedRanges = listOf(
                    AdvisoryRange(
                        type = "SEMVER",
                        vulnerableRange = vulnerability.s("vulnerable_version_range").ifBlank { null },
                        fixed = fixedVersion,
                    )
                ),
                affectedVersions = emptyList(),
                fixedVersion = fixedVersion,
                severity = severity,
                cvssScore = cvssScore,
                link = root.s("permalink").ifBlank { "https://github.com/advisories/$advisoryId" },
                summary = root.s("summary").ifBlank { root.s("description") }.take(SUMMARY_MAX_LENGTH),
                rawAdvisory = root.toString(),
                withdrawn = root["withdrawn_at"] != null,
                publishedAt = root.instant("published_at"),
                modifiedAt = root.instant("updated_at"),
            )
        }
    }

    private fun severityFrom(score: Double?, fallback: String?): String {
        if (score != null) {
            return when {
                score >= CRITICAL_CVSS -> "critical"
                score >= HIGH_CVSS -> "high"
                score >= MEDIUM_CVSS -> "medium"
                score > 0.0 -> "low"
                else -> "info"
            }
        }
        return when (fallback?.trim()?.lowercase()) {
            "critical" -> "critical"
            "high" -> "high"
            "moderate", "medium" -> "medium"
            "low" -> "low"
            else -> "medium"
        }
    }

    private fun firstCve(values: List<JsonElement>): String? =
        values.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonObject -> element.s("value").ifBlank { element.s("id") }
                else -> null
            }
        }.firstOrNull { it.startsWith("CVE-", ignoreCase = true) }

    private fun JsonObject.s(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

    private fun JsonObject.d(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.obj(key: String): JsonObject? =
        this[key]?.let { it as? JsonObject }

    private fun JsonObject.a(key: String): List<JsonElement> =
        (this[key] as? JsonArray)?.jsonArray ?: emptyList()

    private fun JsonObject.instant(key: String): Instant? =
        s(key).takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private const val SUMMARY_MAX_LENGTH = 2_000
    private const val CRITICAL_CVSS = 9.0
    private const val HIGH_CVSS = 7.0
    private const val MEDIUM_CVSS = 4.0
}
