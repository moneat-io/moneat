// Moneat - Mobile-First Error Monitoring Platform
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
 * Test to verify that has() function calls are generated correctly for ClickHouse.
 * Specifically checking that column names are not quoted.
 */
class LogQueryParserClickHouseCompatTest {
    
    private val parser = LogQueryParser()
    
    private fun escapeSql(str: String): String {
        return str.replace("'", "''")
    }
    
    @Test
    fun `has() calls should not quote column names`() {
        val result = parser.parse("@env:production")
        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        
        println("Generated SQL: $sql")
        
        // The SQL should contain has(tags, 'env') not has('tags', 'env')
        assertTrue(sql.contains("has(tags,"), "Should have unquoted column name 'tags'")
        assertTrue(sql.contains("has(resource_attributes,"), "Should have unquoted column name 'resource_attributes'")
        
        // Should NOT have quoted column names
        assertFalse(sql.contains("has('tags'"), "Should not quote column name 'tags'")
        assertFalse(sql.contains("has('resource_attributes'"), "Should not quote column name 'resource_attributes'")
        
        // The key should be quoted
        assertTrue(sql.contains("'env'"), "Key should be quoted")
    }
    
    @Test
    fun `verify exact has() syntax for multiple fields`() {
        val queries = mapOf(
            "@http.status_code:500" to "http.status_code",
            "@user.id:123" to "user.id",
            "@custom:value" to "custom"
        )
        
        queries.forEach { (query, expectedKey) ->
            val result = parser.parse(query)
            val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
            
            println("Query: $query")
            println("SQL: $sql")
            
            // Should have has(tags, 'key')
            val expectedPattern1 = "has(tags, '$expectedKey')"
            val expectedPattern2 = "has(resource_attributes, '$expectedKey')"
            
            assertTrue(
                sql.contains(expectedPattern1) || sql.contains(expectedPattern2),
                "SQL should contain correct has() call pattern for query: $query\nGenerated SQL: $sql"
            )
        }
    }
    
    @Test
    fun `column references should never be in quotes in has() calls`() {
        val testQueries = listOf(
            "@env:prod",
            "@region:us-east",
            "@http.status_code:[200 TO 299]",
            "service:web AND @custom:value"
        )
        
        testQueries.forEach { query ->
            val result = parser.parse(query)
            val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
            
            println("Testing query: $query")
            println("Generated SQL: $sql")
            
            // Extract all has() calls
            val hasPattern = Regex("""has\(([^,]+),""")
            val matches = hasPattern.findAll(sql)
            
            matches.forEach { match ->
                val firstArg = match.groupValues[1].trim()
                println("  Found has() first argument: $firstArg")
                
                // First argument should be either 'tags' or 'resource_attributes'
                assertTrue(
                    firstArg == "tags" || firstArg == "resource_attributes",
                    "First argument to has() should be 'tags' or 'resource_attributes', got: $firstArg"
                )
                
                // Should NOT be quoted
                assertFalse(
                    firstArg.startsWith("'") || firstArg.startsWith("\""),
                    "Column reference should not be quoted: $firstArg"
                )
            }
        }
    }
}
