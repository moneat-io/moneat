package com.moneat.services

import kotlin.test.*

/**
 * Tests to verify ClickHouse Enum8 type compatibility for level and source fields.
 */
class LogQueryParserEnumTest {
    
    private val parser = LogQueryParser()
    
    private fun escapeSql(str: String): String {
        return str.replace("'", "''")
    }
    
    @Test
    fun `level field should use toString() for comparison`() {
        val result = parser.parse("level:error")
        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        
        // Should use toString(level) instead of level directly
        assertTrue(sql.contains("toString(level)"), "level should be wrapped with toString(): $sql")
        assertFalse(sql.matches(Regex("""level\s*=""")), "Should not have unwrapped level comparison")
    }
    
    @Test
    fun `status field should map to level and use toString()`() {
        val result = parser.parse("status:error")
        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        
        // Should map status to level and wrap with toString()
        assertTrue(sql.contains("toString(level)"), "status should map to toString(level): $sql")
        assertFalse(sql.contains("status"), "Should not contain 'status' keyword: $sql")
    }
    
    @Test
    fun `source field should use toString() for comparison`() {
        val result = parser.parse("source:sdk")
        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        
        // Should use toString(source) instead of source directly
        assertTrue(sql.contains("toString(source)"), "source should be wrapped with toString(): $sql")
    }
    
    @Test
    fun `level wildcard should use toString() with ILIKE`() {
        val result = parser.parse("level:err*")
        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        
        // Should use toString(level) with ILIKE
        assertTrue(sql.contains("toString(level)"), "level should be wrapped with toString(): $sql")
        assertTrue(sql.contains("ILIKE"), "Wildcard should use ILIKE: $sql")
    }
    
    @Test
    fun `complex query with level should use toString()`() {
        val result = parser.parse("service:web AND level:error")
        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        
        // Service should be direct, level should use toString()
        assertTrue(sql.contains("service ="), "Should have service comparison: $sql")
        assertTrue(sql.contains("toString(level)"), "level should be wrapped with toString(): $sql")
    }
    
    @Test
    fun `OR query with multiple level values should use toString()`() {
        val result = parser.parse("level:error OR level:fatal")
        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        
        // Both level comparisons should use toString()
        val toStringCount = Regex("""toString\(level\)""").findAll(sql).count()
        assertEquals(2, toStringCount, "Should have two toString(level) calls: $sql")
    }
    
    @Test
    fun `negation with level should use toString()`() {
        val result = parser.parse("-level:debug")
        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        
        // Should use toString(level) in NOT condition
        assertTrue(sql.contains("toString(level)"), "level should be wrapped with toString(): $sql")
        assertTrue(sql.contains("NOT"), "Should have NOT operator: $sql")
    }
    
    @Test
    fun `non-enum fields should not use toString()`() {
        val result = parser.parse("service:web AND host:prod-1")
        val sql = parser.toClickHouseSql(result.rootNode, ::escapeSql)
        
        // service and host are String fields, not Enum8
        assertFalse(sql.contains("toString(service)"), "service should not be wrapped: $sql")
        assertFalse(sql.contains("toString(host)"), "host should not be wrapped: $sql")
        
        // But they should still be compared
        assertTrue(sql.contains("service ="), "Should have service comparison: $sql")
        assertTrue(sql.contains("host ="), "Should have host comparison: $sql")
    }
}
