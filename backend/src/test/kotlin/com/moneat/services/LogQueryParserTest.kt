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

@file:Suppress("USELESS_CAST", "UNNECESSARY_NOT_NULL_ASSERTION")

package com.moneat.services

import kotlin.test.*

/**
 * Comprehensive tests for Datadog-compatible log query parser.
 *
 * Tests cover:
 * - Boolean operators (AND, OR, NOT)
 * - Attribute search with @ prefix
 * - Reserved attributes (service, status, host, etc.)
 * - Full-text search with *: prefix
 * - Wildcards (* and ?)
 * - Numerical ranges [X TO Y]
 * - Tag search
 * - Special character escaping
 * - Quoted strings
 * - Complex nested queries
 * - Edge cases and error handling
 */
class LogQueryParserTest {

    private val parser = LogQueryParser()

    // Helper function that mimics the SQL escape function
    private fun escapeSql(str: String): String {
        return str.replace("'", "''")
    }

    @Test
    fun `simple text search should create full-text condition`() {
        val result = parser.parse("error")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.FullTextNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("hasTokenCaseInsensitive"))
        assertTrue(sql.contains("error"))
    }

    @Test
    fun `AND operator should create AndNode`() {
        val result = parser.parse("error AND timeout")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.AndNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("AND"))
        assertTrue(sql.contains("error"))
        assertTrue(sql.contains("timeout"))
    }

    @Test
    fun `OR operator should create OrNode`() {
        val result = parser.parse("error OR warning")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.OrNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains(" OR "))
        assertTrue(sql.contains("error"))
        assertTrue(sql.contains("warning"))
    }

    @Test
    fun `NOT operator with minus sign should create NotNode`() {
        val result = parser.parse("-error")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.NotNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("NOT"))
        assertTrue(sql.contains("error"))
    }

    @Test
    fun `attribute search with @ prefix should create FieldNode`() {
        val result = parser.parse("@http.status_code:200")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.FieldNode)

        val node = result.rootNode as LogQueryParser.QueryNode.FieldNode
        assertEquals("http.status_code", node.field)
        assertEquals("200", node.value)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("http.status_code"))
        assertTrue(sql.contains("200"))
    }

    @Test
    fun `reserved attribute service should work without @ prefix`() {
        val result = parser.parse("service:web-app")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.FieldNode)

        val node = result.rootNode as LogQueryParser.QueryNode.FieldNode
        assertEquals("service", node.field)
        assertEquals("web-app", node.value)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("service"))
        assertTrue(sql.contains("web-app"))
    }

    @Test
    fun `reserved attribute status should map to level`() {
        val result = parser.parse("status:error")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.FieldNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("level"))
        assertTrue(sql.contains("error"))
    }

    @Test
    fun `reserved attributes host source environment should work`() {
        val queries = mapOf(
            "host:server1" to "host",
            "source:nginx" to "source",
            "environment:production" to "environment"
        )

        queries.forEach { (query, expectedField) ->
            val result = parser.parse(query)
            assertNotNull(result.rootNode, "Failed to parse: $query")
            val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
            assertTrue(sql.contains(expectedField), "Expected field $expectedField in SQL for query: $query")
        }
    }

    @Test
    fun `full-text search with asterisk prefix should search all fields`() {
        val result = parser.parse("*:authentication")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.FullTextNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("message") && sql.contains("body"))
        assertTrue(sql.contains("authentication"))
    }

    @Test
    fun `wildcard asterisk should convert to LIKE pattern`() {
        val result = parser.parse("service:web*")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.FieldNode)

        val node = result.rootNode as LogQueryParser.QueryNode.FieldNode
        assertTrue(node.isWildcard)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("ILIKE"))
        assertTrue(sql.contains("web%"))
    }

    @Test
    fun `wildcard question mark should convert to underscore`() {
        val result = parser.parse("host:server?")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.FieldNode)

        val node = result.rootNode as LogQueryParser.QueryNode.FieldNode
        assertTrue(node.isWildcard)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("ILIKE"))
        assertTrue(sql.contains("server_"))
    }

    @Test
    fun `numerical range syntax should create RangeNode`() {
        val result = parser.parse("@http.status_code:[200 TO 299]")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.RangeNode)

        val node = result.rootNode as LogQueryParser.QueryNode.RangeNode
        assertEquals("http.status_code", node.field)
        assertEquals("200", node.min)
        assertEquals("299", node.max)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains(">=") && sql.contains("<="))
        assertTrue(sql.contains("200") && sql.contains("299"))
    }

    @Test
    fun `quoted strings should be treated as exact match`() {
        val result = parser.parse("\"hello world\"")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.FullTextNode)

        val node = result.rootNode as LogQueryParser.QueryNode.FullTextNode
        assertEquals("hello world", node.term)
        assertFalse(node.isWildcard)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("hello world"))
    }

    @Test
    fun `quoted field value should work`() {
        val result = parser.parse("@message:\"HTTP 500 error\"")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.FieldNode)

        val node = result.rootNode as LogQueryParser.QueryNode.FieldNode
        assertEquals("message", node.field)
        // Note: The quoted value parsing needs to handle this
    }

    @Test
    fun `escaped special characters should work`() {
        val result = parser.parse("message:hello\\:world")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.FieldNode)

        val node = result.rootNode as LogQueryParser.QueryNode.FieldNode
        assertEquals("message", node.field)
        assertTrue(node.value.contains("world"))
    }

    @Test
    fun `complex query with multiple operators`() {
        val result = parser.parse("service:web-app AND (status:error OR status:warning) AND -timeout")
        assertNotNull(result.rootNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("service"))
        assertTrue(sql.contains("level")) // status maps to level
        assertTrue(sql.contains("AND"))
        assertTrue(sql.contains("OR"))
        assertTrue(sql.contains("NOT"))
    }

    @Test
    fun `implicit AND for consecutive terms`() {
        val result = parser.parse("error timeout database")
        assertNotNull(result.rootNode)

        // Should be treated as: error AND timeout AND database
        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("error"))
        assertTrue(sql.contains("timeout"))
        assertTrue(sql.contains("database"))
    }

    @Test
    fun `tag search with colon syntax`() {
        val result = parser.parse("env:production")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.FieldNode)

        val node = result.rootNode as LogQueryParser.QueryNode.FieldNode
        assertEquals("env", node.field)
        assertEquals("production", node.value)
    }

    @Test
    fun `multiple tags with AND`() {
        val result = parser.parse("env:production AND region:us-east-1")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.AndNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("env"))
        assertTrue(sql.contains("production"))
        assertTrue(sql.contains("region"))
        assertTrue(sql.contains("us-east-1"))
    }

    @Test
    fun `empty query should return null node`() {
        val result = parser.parse("")
        assertNull(result.rootNode)
    }

    @Test
    fun `whitespace-only query should return null node`() {
        val result = parser.parse("   ")
        assertNull(result.rootNode)
    }

    @Test
    fun `query with only operators should handle gracefully`() {
        val result = parser.parse("AND OR")
        // Should not crash - may return null or parse as text
        assertNotNull(result)
    }

    @Test
    fun `unclosed quote should handle gracefully`() {
        val result = parser.parse("\"hello world")
        assertNotNull(result)
        // Should parse as much as possible
    }

    @Test
    fun `unclosed parenthesis should handle gracefully`() {
        val result = parser.parse("(error AND timeout")
        assertNotNull(result)
        // Should parse as much as possible
    }

    @Test
    fun `nested parentheses should work`() {
        val result = parser.parse("((error AND timeout) OR (warning AND database))")
        assertNotNull(result.rootNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("error"))
        assertTrue(sql.contains("timeout"))
        assertTrue(sql.contains("warning"))
        assertTrue(sql.contains("database"))
        assertTrue(sql.contains("OR"))
        assertTrue(sql.contains("AND"))
    }

    @Test
    fun `trace_id and span_id fields should work`() {
        val queries = listOf(
            "trace_id:abc123",
            "span_id:xyz789",
            "@trace_id:abc123"
        )

        queries.forEach { query ->
            val result = parser.parse(query)
            assertNotNull(result.rootNode, "Failed to parse: $query")
            val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
            assertTrue(sql.isNotEmpty(), "SQL should not be empty for: $query")
        }
    }

    @Test
    fun `container fields should work`() {
        val result = parser.parse("container_name:nginx")
        assertNotNull(result.rootNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("container_name"))
        assertTrue(sql.contains("nginx"))
    }

    @Test
    fun `mixed wildcards and exact match`() {
        val result = parser.parse("service:web* AND environment:production")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.AndNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("ILIKE") || sql.contains("web%"))
        assertTrue(sql.contains("environment"))
        assertTrue(sql.contains("production"))
    }

    @Test
    fun `SQL injection attempt should be escaped`() {
        val result = parser.parse("message:'; DROP TABLE logs; --")
        assertNotNull(result.rootNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        // Should escape the single quote
        assertTrue(sql.contains("''") || !sql.contains("DROP TABLE"))
    }

    @Test
    fun `attribute search in tags should check both tags and resource_attributes`() {
        val result = parser.parse("@custom.field:value123")
        assertNotNull(result.rootNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        // Should check both maps
        assertTrue(sql.contains("tags") && sql.contains("resource_attributes"))
        assertTrue(sql.contains("custom.field"))
        assertTrue(sql.contains("value123"))
    }

    @Test
    fun `full-text wildcard search should work`() {
        val result = parser.parse("*:auth*")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.FullTextNode)

        val node = result.rootNode as LogQueryParser.QueryNode.FullTextNode
        assertTrue(node.isWildcard)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("ILIKE"))
        assertTrue(sql.contains("auth%"))
    }

    @Test
    fun `complex real-world query example 1`() {
        // Real Datadog-style query
        val result = parser.parse(
            "service:api-gateway AND status:error AND @http.status_code:[500 TO 599] AND -@user.id:test*"
        )
        assertNotNull(result.rootNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("service"))
        assertTrue(sql.contains("api-gateway"))
        assertTrue(sql.contains("level")) // status -> level
        assertTrue(sql.contains("http.status_code"))
        assertTrue(sql.contains("500"))
        assertTrue(sql.contains("599"))
        assertTrue(sql.contains("NOT"))
        assertTrue(sql.contains("user.id"))
    }

    @Test
    fun `complex real-world query example 2`() {
        // Search for authentication failures
        val result = parser.parse("(authentication OR login) AND (failed OR error) AND environment:production")
        assertNotNull(result.rootNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("authentication"))
        assertTrue(sql.contains("login"))
        assertTrue(sql.contains("failed"))
        assertTrue(sql.contains("error"))
        assertTrue(sql.contains("environment"))
        assertTrue(sql.contains("production"))
    }

    @Test
    fun `operator precedence - OR before AND`() {
        // a AND b OR c should parse as (a AND b) OR c
        val result = parser.parse("error AND timeout OR warning")
        assertNotNull(result.rootNode)

        // Check that it's an OR at the top level
        assertTrue(result.rootNode is LogQueryParser.QueryNode.OrNode)
    }

    @Test
    fun `parentheses override precedence`() {
        // a AND (b OR c)
        val result = parser.parse("error AND (timeout OR warning)")
        assertNotNull(result.rootNode)

        // Top level should be AND
        assertTrue(result.rootNode is LogQueryParser.QueryNode.AndNode)
    }

    @Test
    fun `null node should produce safe default SQL`() {
        val sql = parser.toClickHouseSql(null, ::escapeSql)
        assertEquals("1=1", sql)
    }

    @Test
    fun `level field should work as synonym for status`() {
        val result = parser.parse("level:error")
        assertNotNull(result.rootNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("level"))
        assertTrue(sql.contains("error"))
    }

    @Test
    fun `message field search should work`() {
        val result = parser.parse("@message:\"connection refused\"")
        assertNotNull(result.rootNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("message"))
    }

    @Test
    fun `body field search should work`() {
        val result = parser.parse("@body:stacktrace")
        assertNotNull(result.rootNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("body"))
        assertTrue(sql.contains("stacktrace"))
    }

    // ==========================================
    // Numerical operator tests
    // ==========================================

    @Test
    fun `numerical greater than operator`() {
        val result = parser.parse("@http.response_time:>100")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.ComparisonNode)

        val node = result.rootNode as LogQueryParser.QueryNode.ComparisonNode
        assertEquals("http.response_time", node.field)
        assertEquals(">", node.operator)
        assertEquals("100", node.value)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("> 100"), "SQL should contain '> 100': $sql")
    }

    @Test
    fun `numerical greater than or equal operator`() {
        val result = parser.parse("@http.response_time:>=200")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.ComparisonNode)

        val node = result.rootNode as LogQueryParser.QueryNode.ComparisonNode
        assertEquals(">=", node.operator)
        assertEquals("200", node.value)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains(">= 200"), "SQL should contain '>= 200': $sql")
    }

    @Test
    fun `numerical less than operator`() {
        val result = parser.parse("@http.response_time:<500")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.ComparisonNode)

        val node = result.rootNode as LogQueryParser.QueryNode.ComparisonNode
        assertEquals("<", node.operator)
        assertEquals("500", node.value)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("< 500"), "SQL should contain '< 500': $sql")
    }

    @Test
    fun `numerical less than or equal operator`() {
        val result = parser.parse("@http.response_time:<=1000")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.ComparisonNode)

        val node = result.rootNode as LogQueryParser.QueryNode.ComparisonNode
        assertEquals("<=", node.operator)
        assertEquals("1000", node.value)
    }

    @Test
    fun `numerical comparison on tag field uses toFloat64OrNull`() {
        val result = parser.parse("@http.response_time:>100")
        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("toFloat64OrNull"), "SQL should use toFloat64OrNull for tag comparison: $sql")
        assertTrue(sql.contains("tags") && sql.contains("resource_attributes"))
    }

    @Test
    fun `numerical comparison combined with other conditions`() {
        val result = parser.parse("service:api AND @http.response_time:>500")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.AndNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("service"))
        assertTrue(sql.contains("> 500"))
    }

    // ==========================================
    // Existence check tests
    // ==========================================

    @Test
    fun `existence check for tag attribute`() {
        val result = parser.parse("@http.status_code:*")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.ExistsNode)

        val node = result.rootNode as LogQueryParser.QueryNode.ExistsNode
        assertEquals("http.status_code", node.field)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("has(tags,") || sql.contains("has(resource_attributes,"), "SQL should use has(): $sql")
    }

    @Test
    fun `non-existence check with negation`() {
        val result = parser.parse("-@http.status_code:*")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.NotNode)

        val notNode = result.rootNode as LogQueryParser.QueryNode.NotNode
        assertTrue(notNode.node is LogQueryParser.QueryNode.ExistsNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("NOT"), "SQL should contain NOT: $sql")
        assertTrue(sql.contains("has("), "SQL should contain has(): $sql")
    }

    @Test
    fun `existence check for top-level field`() {
        val result = parser.parse("service:*")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.ExistsNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("service IS NOT NULL"), "SQL should check IS NOT NULL: $sql")
        assertTrue(sql.contains("service != ''"), "SQL should check non-empty: $sql")
    }

    @Test
    fun `existence check combined with other query`() {
        val result = parser.parse("@custom_field:* AND service:web")
        assertNotNull(result.rootNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("has("))
        assertTrue(sql.contains("service"))
    }

    // ==========================================
    // tags: syntax tests
    // ==========================================

    @Test
    fun `tags colon MY_TAG should check tag key existence`() {
        val result = parser.parse("tags:urgent")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.TagExistsNode)

        val node = result.rootNode as LogQueryParser.QueryNode.TagExistsNode
        assertEquals("urgent", node.tagKey)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("has(tags, 'urgent')"), "SQL should check tag key existence: $sql")
    }

    @Test
    fun `tags colon with AND operator`() {
        val result = parser.parse("tags:important AND service:web")
        assertNotNull(result.rootNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("has(tags, 'important')"))
        assertTrue(sql.contains("service"))
    }

    // ==========================================
    // Grouped field value tests
    // ==========================================

    @Test
    fun `grouped field values with OR`() {
        val result = parser.parse("env:(prod OR staging)")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.OrNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("env") || sql.contains("tags"), "SQL should reference env field: $sql")
        assertTrue(sql.contains("prod"), "SQL should contain 'prod': $sql")
        assertTrue(sql.contains("staging"), "SQL should contain 'staging': $sql")
        assertTrue(sql.contains(" OR "), "SQL should contain OR: $sql")
    }

    @Test
    fun `grouped field values with three values`() {
        val result = parser.parse("service:(api OR web OR worker)")
        assertNotNull(result.rootNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("api"))
        assertTrue(sql.contains("web"))
        assertTrue(sql.contains("worker"))
    }

    @Test
    fun `grouped field values with attribute prefix`() {
        val result = parser.parse("@env:(production OR staging)")
        assertNotNull(result.rootNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("production"))
        assertTrue(sql.contains("staging"))
    }

    @Test
    fun `grouped field values combined with AND`() {
        val result = parser.parse("env:(prod OR staging) AND status:error")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.AndNode)
    }

    @Test
    fun `single value in grouped syntax`() {
        val result = parser.parse("env:(prod)")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.FieldNode)
    }

    // ==========================================
    // Full-text exact phrase tests
    // ==========================================

    @Test
    fun `full-text exact phrase search`() {
        val result = parser.parse("*:\"connection refused\"")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.FullTextNode)

        val node = result.rootNode as LogQueryParser.QueryNode.FullTextNode
        assertEquals("connection refused", node.term)
        assertFalse(node.isWildcard)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        // Should use ILIKE for phrase matching, not hasTokenCaseInsensitive
        assertTrue(sql.contains("ILIKE"), "Phrase search should use ILIKE: $sql")
        assertTrue(sql.contains("connection refused"), "SQL should contain the phrase: $sql")
    }

    @Test
    fun `quoted phrase full-text uses ILIKE not hasToken`() {
        val result = parser.parse("\"hello world\"")
        assertNotNull(result.rootNode)

        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        assertTrue(sql.contains("ILIKE '%hello world%'"), "Quoted phrase should use ILIKE substring: $sql")
        assertFalse(sql.contains("hasTokenCaseInsensitive"), "Quoted phrase should not use hasToken: $sql")
    }

    // ==========================================
    // Literal wildcards in quotes tests
    // ==========================================

    @Test
    fun `wildcards inside quotes should be treated literally`() {
        val result = parser.parse("\"*test*\"")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.FullTextNode)

        val node = result.rootNode as LogQueryParser.QueryNode.FullTextNode
        assertEquals("*test*", node.term)
        assertFalse(node.isWildcard, "Wildcards inside quotes should not be treated as wildcards")
    }

    @Test
    fun `question mark inside quotes should be literal`() {
        val result = parser.parse("\"what?\"")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.FullTextNode)

        val node = result.rootNode as LogQueryParser.QueryNode.FullTextNode
        assertFalse(node.isWildcard)
        assertEquals("what?", node.term)
    }

    @Test
    fun `quoted field value with wildcards should be literal`() {
        val result = parser.parse("@message:\"*test*\"")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.FieldNode)

        val node = result.rootNode as LogQueryParser.QueryNode.FieldNode
        assertFalse(node.isWildcard, "Quoted field value wildcards should be literal")
        assertEquals("*test*", node.value)
    }

    @Test
    fun `trailing colon with empty value should be treated as text`() {
        // "auth-service:" — empty value after colon, should become free text search for "auth-service"
        val result = parser.parse("auth-service:")
        assertNotNull(result.rootNode)
        assertTrue(
            result.rootNode is LogQueryParser.QueryNode.FullTextNode,
            "Trailing colon should produce FullTextNode, got: ${result.rootNode}"
        )
        val node = result.rootNode as LogQueryParser.QueryNode.FullTextNode
        assertEquals("auth-service", node.term)
    }

    @Test
    fun `trailing colon in OR expression should not crash`() {
        // "(service:api OR auth-service:)" — auth-service: has empty value
        val result = parser.parse("(service:api OR auth-service:)")
        assertNotNull(result.rootNode)
        // Should parse without exception — the OR should still work
        val sql = parser.toClickHouseSql(result.rootNode!!, ::escapeSql)
        assertTrue(sql.contains("service"), "Should contain service condition")
    }

    @Test
    fun `wildcard field search should produce wildcard FieldNode`() {
        val result = parser.parse("host:api-prod*")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.FieldNode)
        val node = result.rootNode as LogQueryParser.QueryNode.FieldNode
        assertEquals("host", node.field)
        assertEquals("api-prod*", node.value)
        assertTrue(node.isWildcard, "Should be wildcard")
    }

    @Test
    fun `negated field search should produce NotNode`() {
        val result = parser.parse("-host:api-prod-3")
        assertNotNull(result.rootNode)
        assertTrue(
            result.rootNode is LogQueryParser.QueryNode.NotNode,
            "Should be NotNode, got: ${result.rootNode}"
        )
    }

    @Test
    fun `message field search with wildcard should work`() {
        val result = parser.parse("message:Rate*")
        assertNotNull(result.rootNode)
        assertTrue(result.rootNode is LogQueryParser.QueryNode.FieldNode)
        val node = result.rootNode as LogQueryParser.QueryNode.FieldNode
        assertEquals("message", node.field)
        assertEquals("Rate*", node.value)
        assertTrue(node.isWildcard)

        val sql = parser.toClickHouseSql(result.rootNode!!, ::escapeSql)
        assertTrue(sql.contains("ILIKE"), "Wildcard message search should use ILIKE: $sql")
        assertTrue(sql.contains("Rate%"), "Should convert * to %: $sql")
    }
}
