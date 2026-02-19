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

import kotlin.test.*

/**
 * Additional tests to verify SQL generation produces valid ClickHouse queries.
 */
class LogQueryParserSqlTest {

    private val parser = LogQueryParser()

    private fun escapeSql(str: String): String {
        return str.replace("'", "''")
    }

    @Test
    fun `simple query generates valid SQL`() {
        val result = parser.parse("error")
        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)

        println("Simple query SQL: $sql")

        // Should contain hasTokenCaseInsensitive
        assertTrue(sql.contains("hasTokenCaseInsensitive"))
        // Should search multiple fields
        assertTrue(sql.contains("message") && sql.contains("body"))
    }

    @Test
    fun `attribute search generates valid SQL`() {
        val result = parser.parse("@custom.field:value")
        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)

        println("Attribute search SQL: $sql")

        // Should use has for custom fields
        assertTrue(sql.contains("has("))
        assertTrue(sql.contains("tags") && sql.contains("resource_attributes"))
    }

    @Test
    fun `complex query generates valid SQL`() {
        val result = parser.parse("service:web AND @http.status_code:500")
        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)

        println("Complex query SQL: $sql")

        // Should have AND operator
        assertTrue(sql.contains("AND"))
        // Should have service field
        assertTrue(sql.contains("service"))
        // Should have http.status_code in tags or resource_attributes
        assertTrue(sql.contains("http.status_code"))
    }

    @Test
    fun `has() syntax is correct`() {
        val result = parser.parse("@env:production")
        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)

        println("has() SQL: $sql")

        // Verify the syntax follows the pattern: has(map, key)
        // The function should be called with exactly 2 arguments
        val hasPattern = Regex("""has\([^,]+,\s*'[^']+'\)""")
        val matches = hasPattern.findAll(sql).toList()

        assertTrue(matches.isNotEmpty(), "Should contain has() calls")
        matches.forEach { match ->
            println("  Found: ${match.value}")
            // Verify it has the correct number of arguments
            val commaCount = match.value.count { it == ',' }
            assertEquals(1, commaCount, "has() should have exactly 2 arguments (one comma)")
        }
    }

    @Test
    fun `range query generates valid SQL`() {
        val result = parser.parse("@response_time:[0 TO 100]")
        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)

        println("Range query SQL: $sql")

        // Should have >= and <=
        assertTrue(sql.contains(">=") && sql.contains("<="))
        assertTrue(sql.contains("0") && sql.contains("100"))
    }

    @Test
    fun `SQL does not have syntax errors`() {
        val queries =
            listOf(
                "error",
                "error AND timeout",
                "service:web",
                "@http.status_code:500",
                "status:error AND service:api",
                "(error OR warning) AND service:web",
                "@custom:value",
                "*:search",
                "service:web*"
            )

        queries.forEach { query ->
            val result = parser.parse(query)
            val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)

            println("Query: $query")
            println("  SQL: $sql")

            // Basic syntax checks
            assertFalse(sql.isEmpty(), "SQL should not be empty for: $query")

            // Check for balanced parentheses
            val openCount = sql.count { it == '(' }
            val closeCount = sql.count { it == ')' }
            assertEquals(openCount, closeCount, "Unbalanced parentheses in SQL for query: $query\nSQL: $sql")

            // Check that has() is called correctly if present
            if (sql.contains("has(")) {
                val pattern = Regex("""has\([^)]+\)""")
                val matches = pattern.findAll(sql).toList()
                assertTrue(matches.isNotEmpty(), "has() pattern should match")

                matches.forEach { match ->
                    val call = match.value
                    // Verify it has 2 arguments
                    val argsStr = call.substringAfter("has(").substringBefore(")")
                    val args = argsStr.split(",").map { it.trim() }
                    assertEquals(2, args.size, "has() should have exactly 2 arguments in: $call")

                    // Verify the first argument is a valid map name
                    assertTrue(
                        args[0] in listOf("tags", "resource_attributes"),
                        "First argument should be 'tags' or 'resource_attributes', got: ${args[0]}"
                    )
                }
            }

            // Ensure no deprecated mapContains is used
            assertFalse(sql.contains("mapContains"), "Should use has() instead of mapContains")
        }
    }

    @Test
    fun `comparison operator generates valid SQL`() {
        val queries =
            listOf(
                "@http.response_time:>100",
                "@http.response_time:>=100",
                "@http.response_time:<500",
                "@http.response_time:<=500"
            )

        queries.forEach { query ->
            val result = parser.parse(query)
            val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)

            // Should have toFloat64OrNull for tag comparison
            assertTrue(sql.contains("toFloat64OrNull"), "Should use toFloat64OrNull for: $query\nSQL: $sql")
            // Should have balanced parentheses
            val openCount = sql.count { it == '(' }
            val closeCount = sql.count { it == ')' }
            assertEquals(openCount, closeCount, "Unbalanced parentheses for: $query\nSQL: $sql")
        }
    }

    @Test
    fun `existence check generates valid SQL for tags`() {
        val result = parser.parse("@custom_field:*")
        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)

        assertTrue(sql.contains("has(tags, 'custom_field')"), "Should check tags: $sql")
        assertTrue(sql.contains("has(resource_attributes, 'custom_field')"), "Should check resource_attributes: $sql")
        assertFalse(sql.contains("= '*'"), "Should not compare to literal asterisk: $sql")
    }

    @Test
    fun `existence check generates valid SQL for top-level fields`() {
        val result = parser.parse("service:*")
        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)

        assertTrue(sql.contains("IS NOT NULL"), "Should check IS NOT NULL: $sql")
        assertTrue(sql.contains("!= ''"), "Should check non-empty: $sql")
    }

    @Test
    fun `non-existence check generates valid SQL`() {
        val result = parser.parse("-@custom_field:*")
        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)

        assertTrue(sql.contains("NOT"), "Should have NOT: $sql")
        assertTrue(sql.contains("has("), "Should have has() call: $sql")
    }

    @Test
    fun `tag exists generates valid SQL`() {
        val result = parser.parse("tags:urgent")
        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)

        assertEquals("has(tags, 'urgent')", sql, "Should be simple has() call: $sql")
    }

    @Test
    fun `grouped field values generate valid SQL`() {
        val result = parser.parse("env:(prod OR staging)")
        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)

        assertTrue(sql.contains("prod"), "Should contain 'prod': $sql")
        assertTrue(sql.contains("staging"), "Should contain 'staging': $sql")
        assertTrue(sql.contains(" OR "), "Should contain OR: $sql")

        val openCount = sql.count { it == '(' }
        val closeCount = sql.count { it == ')' }
        assertEquals(openCount, closeCount, "Unbalanced parentheses in SQL: $sql")
    }

    @Test
    fun `phrase search generates ILIKE SQL`() {
        val result = parser.parse("\"connection refused\"")
        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)

        assertTrue(sql.contains("ILIKE"), "Phrase should use ILIKE: $sql")
        assertTrue(sql.contains("%connection refused%"), "Should contain phrase pattern: $sql")
        assertFalse(sql.contains("hasTokenCaseInsensitive"), "Phrase should not use hasToken: $sql")
    }

    @Test
    fun `all new features generate balanced parentheses`() {
        val queries =
            listOf(
                "@http.response_time:>100",
                "@http.status_code:*",
                "-@http.status_code:*",
                "tags:urgent",
                "env:(prod OR staging)",
                "\"connection refused\"",
                "service:api AND @http.response_time:>=500 AND -@custom:*"
            )

        queries.forEach { query ->
            val result = parser.parse(query)
            val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)

            val openCount = sql.count { it == '(' }
            val closeCount = sql.count { it == ')' }
            assertEquals(openCount, closeCount, "Unbalanced parentheses for query: $query\nSQL: $sql")
        }
    }
}
