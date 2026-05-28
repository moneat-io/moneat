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

package com.moneat.shared.services

import kotlin.uuid.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectIdResolverTest {
    @Test
    fun `resolve returns legacy numeric project IDs without lookup`() {
        var lookupCalls = 0
        val resolver = ProjectIdResolver(
            lookupProjectIdByResourceId = {
                lookupCalls++
                10L
            }
        )

        assertEquals(42L, resolver.resolve("42"))
        assertEquals(0, lookupCalls)
    }

    @Test
    fun `resolve looks up UUID resource IDs and caches results`() {
        val resourceId = Uuid.parse("018f4ce4-3f2a-7a67-a32b-0c1848f62b9d")
        var lookupCalls = 0
        val resolver = ProjectIdResolver(
            lookupProjectIdByResourceId = {
                lookupCalls++
                if (it == resourceId) 99L else null
            }
        )

        assertEquals(99L, resolver.resolve(resourceId.toString()))
        assertEquals(99L, resolver.resolve(resourceId.toString()))
        assertEquals(1, lookupCalls)
    }

    @Test
    fun `resolve returns null for invalid identifiers`() {
        val resolver = ProjectIdResolver(
            lookupProjectIdByResourceId = { error("lookup should not be called") }
        )

        assertNull(resolver.resolve(""))
        assertNull(resolver.resolve("not-a-project-id"))
    }

    @Test
    fun `resourceIdFor looks up and caches project resource IDs`() {
        val resourceId = Uuid.parse("118f4ce4-3f2a-7a67-a32b-0c1848f62b9d")
        var lookupCalls = 0
        val resolver = ProjectIdResolver(
            lookupResourceIdByProjectId = {
                lookupCalls++
                if (it == 17L) resourceId else null
            }
        )

        assertEquals(resourceId.toString(), resolver.resourceIdFor(17L))
        assertEquals(resourceId.toString(), resolver.resourceIdFor(17L))
        assertEquals(1, lookupCalls)
    }
}
