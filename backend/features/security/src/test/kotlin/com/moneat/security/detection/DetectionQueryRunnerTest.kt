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

package com.moneat.security.detection

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The runner must skip malformed JSONEachRow rows rather than fabricating `0`/empty group values:
 * coerced rows would carry wrong dedup keys and emit bogus signals. A bad row is dropped; valid rows in
 * the same body still parse.
 */
class DetectionQueryRunnerTest {

    private fun runnerOver(body: String) = DetectionQueryRunner(execute = { _ -> body })

    private fun queryGroupingByHost(): CompiledRuleQuery = CompiledRuleQuery(
        sql = "SELECT host AS g0, count() AS match_count FROM x",
        groupByColumns = listOf("host"),
        groupByAliases = listOf("g0"),
        whereClause = "1=1",
        evidenceDescriptor = "table=logs group_by=host window=300s",
        windowSeconds = 300,
    )

    @Test
    fun `rows missing match_count are skipped not coerced to zero`() = runBlocking {
        val body = """
            {"g0":"web-01","match_count":12}
            {"g0":"web-02"}
        """.trimIndent()
        val rows = runnerOver(body).run(queryGroupingByHost())
        assertEquals(1, rows.size, "row without match_count must be dropped, not counted as 0")
        assertEquals("web-01", rows.single().groupValues["host"])
        assertEquals(12L, rows.single().count)
    }

    @Test
    fun `rows missing an expected group alias are skipped`() = runBlocking {
        val body = """
            {"g0":"web-01","match_count":12}
            {"match_count":5}
        """.trimIndent()
        val rows = runnerOver(body).run(queryGroupingByHost())
        assertEquals(1, rows.size, "row missing the group alias must be dropped, not given an empty value")
        assertEquals("web-01", rows.single().groupValues["host"])
    }

    @Test
    fun `non-JSON lines are skipped`() = runBlocking {
        val body = """
            {"g0":"web-01","match_count":12}
            not-json-at-all
            {"g0":"web-03","match_count":4}
        """.trimIndent()
        val rows = runnerOver(body).run(queryGroupingByHost())
        assertEquals(2, rows.size)
        assertEquals(setOf("web-01", "web-03"), rows.map { it.groupValues.getValue("host") }.toSet())
    }

    @Test
    fun `non-numeric match_count is skipped`() = runBlocking {
        val body = """{"g0":"web-01","match_count":"oops"}"""
        val rows = runnerOver(body).run(queryGroupingByHost())
        assertEquals(0, rows.size, "non-numeric match_count must be dropped")
    }
}
