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

package com.moneat.events.services

import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceQueryScopeTest {
    @Test
    fun `projectIdClause casts mixed demo service ids to Int64`() {
        val clause = ServiceQueryScope.services(listOf(-1L, -2L, 3L)).projectIdClause()

        assertEquals("toInt64(project_id) IN (-1, -2, 3)", clause)
    }
}
