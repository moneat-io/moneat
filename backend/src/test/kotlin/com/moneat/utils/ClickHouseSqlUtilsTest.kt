package com.moneat.utils

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClickHouseSqlUtilsTest {
    
    @Test
    fun `escapeSql handles null input`() {
        assertEquals("", ClickHouseSqlUtils.escapeSql(null))
    }
    
    @Test
    fun `escapeSql handles empty string`() {
        assertEquals("", ClickHouseSqlUtils.escapeSql(""))
    }
    
    @Test
    fun `escapeSql handles normal text`() {
        assertEquals("hello world", ClickHouseSqlUtils.escapeSql("hello world"))
    }
    
    @Test
    fun `escapeSql escapes single quotes`() {
        assertEquals("hello\\'world", ClickHouseSqlUtils.escapeSql("hello'world"))
    }
    
    @Test
    fun `escapeSql escapes backslashes`() {
        assertEquals("hello\\\\world", ClickHouseSqlUtils.escapeSql("hello\\world"))
    }
    
    @Test
    fun `escapeSql escapes newlines`() {
        assertEquals("hello\\nworld", ClickHouseSqlUtils.escapeSql("hello\nworld"))
    }
    
    @Test
    fun `escapeSql escapes carriage returns`() {
        assertEquals("hello\\rworld", ClickHouseSqlUtils.escapeSql("hello\rworld"))
    }
    
    @Test
    fun `escapeSql escapes tabs`() {
        assertEquals("hello\\tworld", ClickHouseSqlUtils.escapeSql("hello\tworld"))
    }
    
    @Test
    fun `escapeSql escapes null bytes`() {
        assertEquals("hello\\0world", ClickHouseSqlUtils.escapeSql("hello\u0000world"))
    }
    
    @Test
    fun `escapeSql escapes backspace`() {
        assertEquals("hello\\bworld", ClickHouseSqlUtils.escapeSql("hello\bworld"))
    }
    
    @Test
    fun `escapeSql escapes form feed`() {
        assertEquals("hello\\fworld", ClickHouseSqlUtils.escapeSql("hello\u000Cworld"))
    }
    
    @Test
    fun `escapeSql prevents SQL injection attempt`() {
        val malicious = "'; DROP TABLE users; --"
        val escaped = ClickHouseSqlUtils.escapeSql(malicious)
        assertEquals("\\'; DROP TABLE users; --", escaped)
    }
    
    @Test
    fun `escapeSql handles combined special characters`() {
        val input = "test\n'quoted'\tvalue\\here"
        val expected = "test\\n\\'quoted\\'\\tvalue\\\\here"
        assertEquals(expected, ClickHouseSqlUtils.escapeSql(input))
    }
    
    @Test
    fun `escapeLikePattern escapes wildcards`() {
        assertEquals("test\\%pattern\\_here", ClickHouseSqlUtils.escapeLikePattern("test%pattern_here"))
    }
    
    @Test
    fun `escapeLikePattern escapes SQL special chars and wildcards`() {
        val input = "test'value%with_wildcards"
        val expected = "test\\'value\\%with\\_wildcards"
        assertEquals(expected, ClickHouseSqlUtils.escapeLikePattern(input))
    }
    
    @Test
    fun `validateFieldName accepts valid field names`() {
        ClickHouseSqlUtils.validateFieldName("field_name")
        ClickHouseSqlUtils.validateFieldName("table.column")
        ClickHouseSqlUtils.validateFieldName("field123")
        ClickHouseSqlUtils.validateFieldName("_private")
    }
    
    @Test
    fun `validateFieldName rejects SQL injection attempts`() {
        assertFailsWith<IllegalArgumentException> {
            ClickHouseSqlUtils.validateFieldName("field; DROP TABLE users")
        }
    }
    
    @Test
    fun `validateFieldName rejects field names with spaces`() {
        assertFailsWith<IllegalArgumentException> {
            ClickHouseSqlUtils.validateFieldName("field name")
        }
    }
    
    @Test
    fun `validateFieldName rejects field names with special chars`() {
        assertFailsWith<IllegalArgumentException> {
            ClickHouseSqlUtils.validateFieldName("field@name")
        }
    }
    
    @Test
    fun `validateOperator accepts valid operators`() {
        ClickHouseSqlUtils.validateOperator("=")
        ClickHouseSqlUtils.validateOperator("!=")
        ClickHouseSqlUtils.validateOperator("<")
        ClickHouseSqlUtils.validateOperator(">")
        ClickHouseSqlUtils.validateOperator("<=")
        ClickHouseSqlUtils.validateOperator(">=")
        ClickHouseSqlUtils.validateOperator("LIKE")
        ClickHouseSqlUtils.validateOperator("IN")
        ClickHouseSqlUtils.validateOperator("IS NULL")
    }
    
    @Test
    fun `validateOperator accepts case insensitive operators`() {
        ClickHouseSqlUtils.validateOperator("like")
        ClickHouseSqlUtils.validateOperator("Like")
        ClickHouseSqlUtils.validateOperator("LIKE")
    }
    
    @Test
    fun `validateOperator rejects invalid operators`() {
        assertFailsWith<IllegalArgumentException> {
            ClickHouseSqlUtils.validateOperator("DROP")
        }
    }
    
    @Test
    fun `validateOperator rejects SQL injection in operator`() {
        assertFailsWith<IllegalArgumentException> {
            ClickHouseSqlUtils.validateOperator("= 1; DROP TABLE")
        }
    }
}
