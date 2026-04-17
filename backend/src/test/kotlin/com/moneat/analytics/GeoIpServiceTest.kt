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

package com.moneat.analytics

import com.moneat.analytics.services.GeoIpService
import kotlin.test.Test
import kotlin.test.assertEquals

class GeoIpServiceTest {

    private val service = GeoIpService()

    @Test
    fun `resolve returns empty GeoResult when database unavailable`() {
        val r = service.resolve("8.8.8.8")
        assertEquals("", r.countryCode)
        assertEquals("", r.subdivision)
        assertEquals("", r.city)
    }

    @Test
    fun `GeoResult default constructor has empty fields`() {
        val r = GeoIpService.GeoResult()
        assertEquals("", r.countryCode)
        assertEquals("", r.subdivision)
        assertEquals("", r.city)
    }

    @Test
    fun `GeoResult constructor accepts country subdivision city`() {
        val r = GeoIpService.GeoResult("US", "California", "San Francisco")
        assertEquals("US", r.countryCode)
        assertEquals("California", r.subdivision)
        assertEquals("San Francisco", r.city)
    }

    @Test
    fun `resolve is stable for repeated lookups without database`() {
        val a = service.resolve("1.1.1.1")
        val b = service.resolve("9.9.9.9")
        assertEquals(a, b)
        assertEquals(GeoIpService.GeoResult(), a)
    }
}
