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

package com.moneat.services

import com.moneat.shared.services.CacheService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CacheService delegates to Redis when connected. In unit tests Redis is typically
 * not configured, so isConnected() is false: cached GET returns null, loader is
 * always invoked, and the loader result is returned. SET is skipped.
 * This test verifies the fallback behavior when Redis is unavailable.
 */
class CacheServiceTest {

    @Serializable
    data class CachedData(val id: Int, val name: String)

    @Test
    fun `cached returns loader result when Redis unavailable`() =
        runBlocking {
            var loadCount = 0
            val result =
                CacheService.cached("test:key:1", 60) {
                    loadCount++
                    "loaded-value"
                }

            assertEquals("loaded-value", result)
            assertEquals(1, loadCount)
        }

    @Test
    fun `cached invokes loader on every call when Redis unavailable`() =
        runBlocking {
            var loadCount = 0
            val loader = {
                loadCount++
                "value-$loadCount"
            }

            val r1 = CacheService.cached("test:key:2", 60, loader = loader)
            val r2 = CacheService.cached("test:key:2", 60, loader = loader)

            assertEquals("value-1", r1)
            assertEquals("value-2", r2)
            assertEquals(2, loadCount)
        }

    @Test
    fun `cached works with data classes`() =
        runBlocking {
            val data = CachedData(id = 42, name = "test")

            val result =
                CacheService.cached("test:key:3", 60) {
                    data
                }

            assertEquals(42, result.id)
            assertEquals("test", result.name)
        }

    @Test
    fun `cached returns complex object from loader`() =
        runBlocking {
            val list = listOf(1, 2, 3)

            val result =
                CacheService.cached<List<Int>>("test:key:4", 60) {
                    list
                }

            assertEquals(list, result)
            assertTrue(result.size == 3)
        }
}
