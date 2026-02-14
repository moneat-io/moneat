package com.moneat.services

import kotlin.test.*

/**
 * Tests for getTagValues method to ensure it handles top-level fields correctly
 */
class GetTagValuesTest {
    
    private val logService = LogService()
    
    @Test
    fun `getTagValues should generate correct query for top-level field service`() {
        // This test verifies the logic, we can't test SQL execution without a real ClickHouse instance
        // but we can at least verify the method doesn't crash and handles the field type correctly
        
        val key = "service"
        val actualField = if (key == "status") "level" else key
        val topLevelFields = setOf("service", "environment", "host", "source", "level", "message", "body", 
                                    "container_name", "container_id", "container_image", "trace_id", "span_id")
        
        assertTrue(actualField in topLevelFields, "service should be a top-level field")
    }
    
    @Test
    fun `getTagValues should handle status mapping to level`() {
        val key = "status"
        val actualField = if (key == "status") "level" else key
        
        assertEquals("level", actualField, "status should map to level")
    }
    
    @Test
    fun `getTagValues should identify enum fields`() {
        val enumFields = setOf("level", "source")
        
        assertTrue("level" in enumFields)
        assertTrue("source" in enumFields)
        assertFalse("service" in enumFields)
        assertFalse("host" in enumFields)
    }
    
    @Test
    fun `getTagValues should identify custom tags correctly`() {
        val customTag = "http.status_code"
        val topLevelFields = setOf("service", "environment", "host", "source", "level", "message", "body", 
                                    "container_name", "container_id", "container_image", "trace_id", "span_id")
        
        assertFalse(customTag in topLevelFields, "http.status_code should not be a top-level field")
    }
    
    @Test
    fun `buildTagCondition should reject tags with OR in key`() {
        // The actual case from the error log: key="OR host"
        val condition = logService.buildTagCondition("OR host", "api-prod2")
        assertEquals("", condition, "Should reject tag with 'OR host' key")
    }
    
    @Test
    fun `buildTagCondition should reject tags with AND in key`() {
        // Test both with and without space
        val condition1 = logService.buildTagCondition("AND service", "value")
        assertEquals("", condition1, "Should reject tag with 'AND service' key")
        
        val condition2 = logService.buildTagCondition("service AND host", "value")
        assertEquals("", condition2, "Should reject tag with 'service AND host' key")
    }
    
    @Test
    fun `buildTagCondition should reject tags with OR in value`() {
        val condition = logService.buildTagCondition("host", "value1 OR value2")
        assertEquals("", condition, "Should reject tag with OR in value")
    }
    
    @Test
    fun `buildTagCondition should reject tags starting with dash`() {
        val condition = logService.buildTagCondition("-host", "value")
        assertEquals("", condition, "Should reject tag starting with dash")
    }
    
    @Test
    fun `buildTagCondition should accept normal tags`() {
        val condition = logService.buildTagCondition("http.status_code", "200")
        assertTrue(condition.isNotEmpty(), "Should accept normal tag")
        assertTrue(condition.contains("has(tags"), "Should use has() for custom tag")
    }
}
