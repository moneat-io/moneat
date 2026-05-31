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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ThreatIntelServiceTest {

    @Test
    fun `entity values are enriched from the cached snapshot`() {
        var loads = 0
        val provider = ThreatIntelProvider {
            loads++
            snapshot("203.0.113.66")
        }
        val service = ThreatIntelService(provider = provider, ttl = 15.minutes, clock = { NOW })

        val first = service.enrich(mapOf("destination_ip" to "203.0.113.66"))
        val second = service.enrich(mapOf("destination_ip" to "203.0.113.66"))

        assertEquals(1, loads)
        assertEquals("command_and_control", first.single().threatType)
        assertEquals(first, second)
    }

    @Test
    fun `warm enrich uses cached indicator index`() {
        val indicators = SingleIterationIndicators(
            listOf(
                ThreatIntelIndicator(
                    type = "ip",
                    value = "203.0.113.66",
                    threatType = "command_and_control",
                    confidence = 70,
                ),
            )
        )
        val service = ThreatIntelService(
            provider = ThreatIntelProvider { snapshot(indicators) },
            ttl = 15.minutes,
            clock = { NOW },
        )

        val first = service.enrich(mapOf("destination_ip" to "203.0.113.66"))
        val second = service.enrich(mapOf("destination_ip" to "203.0.113.66"))

        assertEquals(first, second)
        assertEquals(1, indicators.iterations)
    }

    @Test
    fun `snapshot load failure is non-blocking`() {
        val service = ThreatIntelService(
            provider = ThreatIntelProvider { error("feed unavailable") },
            ttl = 15.minutes,
            clock = { NOW },
        )

        assertTrue(service.enrich(mapOf("destination_ip" to "203.0.113.66")).isEmpty())
    }

    private fun snapshot(ip: String): ThreatIntelSnapshot =
        ThreatIntelSnapshot(
            feeds = listOf(
                ThreatIntelFeed(
                    name = "local",
                    source = "seed",
                    updatedAt = "2026-05-31T00:00:00Z",
                    indicators = listOf(
                        ThreatIntelIndicator(
                            type = "ip",
                            value = ip,
                            threatType = "command_and_control",
                            confidence = 70,
                        ),
                    ),
                ),
            ),
        )

    private fun snapshot(indicators: List<ThreatIntelIndicator>): ThreatIntelSnapshot =
        ThreatIntelSnapshot(
            feeds = listOf(
                ThreatIntelFeed(
                    name = "local",
                    source = "seed",
                    updatedAt = "2026-05-31T00:00:00Z",
                    indicators = indicators,
                ),
            ),
        )

    private class SingleIterationIndicators(
        private val values: List<ThreatIntelIndicator>,
    ) : AbstractList<ThreatIntelIndicator>() {
        var iterations = 0
            private set

        override val size: Int
            get() = values.size

        override fun get(index: Int): ThreatIntelIndicator = values[index]

        override fun iterator(): Iterator<ThreatIntelIndicator> {
            iterations++
            check(iterations == 1) { "cached snapshot indicators were rescanned" }
            return values.iterator()
        }
    }

    companion object {
        private val NOW = Instant.parse("2026-05-31T00:00:00Z")
    }
}
