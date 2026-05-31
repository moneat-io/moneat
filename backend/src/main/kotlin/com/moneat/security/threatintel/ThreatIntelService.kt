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

package com.moneat.security.threatintel

import kotlinx.serialization.json.Json
import mu.KotlinLogging
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

private const val SEED_RESOURCE = "/security/threat_intel_seed.json"
private const val HASH_MD5_LEN = 32
private const val HASH_SHA1_LEN = 40
private const val HASH_SHA256_LEN = 64
private const val MAX_CONFIDENCE = 100

class ThreatIntelService(
    private val provider: ThreatIntelProvider = ResourceThreatIntelProvider(),
    private val ttl: Duration = DEFAULT_TTL,
    private val clock: () -> Instant = { Clock.System.now() },
) {
    companion object {
        val DEFAULT_TTL: Duration = 15.minutes
    }

    @Volatile
    private var cache: CachedSnapshot? = null

    fun enrich(entities: Map<String, String>): List<ThreatIntelEnrichmentResponse> {
        val snapshot = cachedSnapshotOrNull() ?: return emptyList()
        val candidates = extractCandidates(entities)
        if (candidates.isEmpty()) return emptyList()
        return candidates.flatMap { candidate ->
            snapshot.indicatorsByKey[IndicatorKey(candidate.type, candidate.normalizedValue)]
                .orEmpty()
                .map { indexed -> candidate.toResponse(indexed) }
        }
    }

    private fun cachedSnapshotOrNull(): CachedSnapshot? {
        val now = clock()
        val current = cache
        if (current != null && current.expiresAt > now) return current
        return runCatching {
            val snapshot = provider.load()
            CachedSnapshot(snapshot.indexIndicators(), now + ttl).also { cache = it }
        }.getOrElse { error ->
            logger.warn { "Threat intelligence snapshot load failed: ${error.message}" }
            current
        }
    }

    private fun ThreatIntelIndicator.normalized(): String =
        if (type == "hash") value.lowercase() else value.lowercase().trimEnd('.')

    private fun ThreatIntelSnapshot.indexIndicators(): Map<IndicatorKey, List<IndexedIndicator>> =
        feeds
            .flatMap { feed ->
                val metadata = feed.toMetadata()
                feed.indicators.map { indicator ->
                    IndexedIndicator(metadata, IndicatorKey(indicator.type, indicator.normalized()), indicator)
                }
            }
            .groupBy { it.key }

    private fun ThreatIntelFeed.toMetadata(): ThreatIntelFeedMetadata =
        ThreatIntelFeedMetadata(
            name = name,
            source = source,
            updatedAt = updatedAt,
        )

    private fun ThreatCandidate.toResponse(indexed: IndexedIndicator): ThreatIntelEnrichmentResponse =
        ThreatIntelEnrichmentResponse(
            entityKey = entityKey,
            entityValue = entityValue,
            indicatorType = indexed.indicator.type,
            feedName = indexed.feed.name,
            source = indexed.feed.source,
            threatType = indexed.indicator.threatType,
            confidence = indexed.indicator.confidence.coerceIn(0, MAX_CONFIDENCE),
            reference = indexed.indicator.reference,
            updatedAt = indexed.feed.updatedAt,
        )

    private data class CachedSnapshot(
        val indicatorsByKey: Map<IndicatorKey, List<IndexedIndicator>>,
        val expiresAt: Instant,
    )

    private data class IndicatorKey(
        val type: String,
        val normalizedValue: String,
    )

    private data class ThreatIntelFeedMetadata(
        val name: String,
        val source: String,
        val updatedAt: String,
    )

    private data class IndexedIndicator(
        val feed: ThreatIntelFeedMetadata,
        val key: IndicatorKey,
        val indicator: ThreatIntelIndicator,
    )
}

fun interface ThreatIntelProvider {
    fun load(): ThreatIntelSnapshot
}

class ResourceThreatIntelProvider(
    private val resourcePath: String = SEED_RESOURCE,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ThreatIntelProvider {
    override fun load(): ThreatIntelSnapshot {
        val bytes = requireNotNull(javaClass.getResourceAsStream(resourcePath)) {
            "Threat intelligence resource not found"
        }.use { it.readBytes() }
        return json.decodeFromString<ThreatIntelSnapshot>(bytes.decodeToString())
    }
}

private data class ThreatCandidate(
    val entityKey: String,
    val entityValue: String,
    val type: String,
    val normalizedValue: String,
)

private val IPV4_REGEX = Regex("""^(?:25[0-5]|2[0-4]\d|1?\d?\d)(?:\.(?:25[0-5]|2[0-4]\d|1?\d?\d)){3}$""")
private const val DOMAIN_PATTERN =
    """^(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[A-Za-z]{2,63}$"""

private val DOMAIN_REGEX = Regex(DOMAIN_PATTERN)
private val HASH_REGEX = Regex("""^[A-Fa-f0-9]+$""")

private fun extractCandidates(entities: Map<String, String>): List<ThreatCandidate> =
    entities.mapNotNull { (key, rawValue) ->
        val value = rawValue.trim().trimEnd('.')
        val lower = value.lowercase()
        when {
            value.matches(IPV4_REGEX) -> ThreatCandidate(key, rawValue, "ip", lower)
            value.matches(DOMAIN_REGEX) -> ThreatCandidate(key, rawValue, "domain", lower)
            isHash(lower) -> ThreatCandidate(key, rawValue, "hash", lower)
            else -> null
        }
    }

private fun isHash(value: String): Boolean =
    value.length in setOf(HASH_MD5_LEN, HASH_SHA1_LEN, HASH_SHA256_LEN) && value.matches(HASH_REGEX)
