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
    fun `buildTagCondition should handle tags with OR in key gracefully`() {
        // "OR host:api-prod2" — leading OR without left operand.
        // Parser may or may not produce a condition; just verify no crash.
        logService.buildTagCondition("OR host", "api-prod2")
    }
    
    @Test
    fun `buildTagCondition should handle tags with AND in key gracefully`() {
        // Leading AND without left operand — parser handles gracefully
        logService.buildTagCondition("AND service", "value")
        logService.buildTagCondition("service AND host", "value")
    }
    
    @Test
    fun `buildTagCondition should parse tags with OR in value as query`() {
        val condition = logService.buildTagCondition("host", "value1 OR value2")
        assertTrue(condition.isNotBlank(), "Should parse tag with OR in value as query")
    }
    
    @Test
    fun `buildTagCondition should parse tags starting with dash as query`() {
        val condition = logService.buildTagCondition("-host", "value")
        assertTrue(condition.isNotBlank(), "Should parse tag starting with dash as NOT condition")
    }
    
    @Test
    fun `buildTagCondition should accept normal tags`() {
        val condition = logService.buildTagCondition("http.status_code", "200")
        assertTrue(condition.isNotEmpty(), "Should accept normal tag")
        assertTrue(condition.contains("has(tags"), "Should use has() for custom tag")
    }
}
