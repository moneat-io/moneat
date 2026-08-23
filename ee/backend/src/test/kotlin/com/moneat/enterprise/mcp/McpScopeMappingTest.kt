// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp

import com.moneat.enterprise.EnterpriseModule
import com.moneat.mcp.McpToolContributor
import com.moneat.mcp.McpToolRegistrar
import com.moneat.mcp.auth.McpScopes
import com.moneat.mcp.protocol.McpToolRegistry
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpScopeMappingTest {
    @Test
    fun `all core and enterprise tools have scope mappings`() {
        val registry = McpToolRegistry()
        McpToolRegistrar.registerAll(registry)
        ServiceLoader.load(EnterpriseModule::class.java)
            .filterIsInstance<McpToolContributor>()
            .forEach { contributor -> contributor.contributeTools(registry) }

        val toolsByName = registry.listTools().associateBy { tool -> tool.name }

        assertTrue("get_on_call_alert" in toolsByName)
        assertTrue("list_on_call_alerts" in toolsByName)
        assertEquals(setOf(McpScopes.ORG_READ), toolsByName.getValue("get_on_call_alert").requiredScopes)
        assertEquals(setOf(McpScopes.ORG_READ), toolsByName.getValue("list_on_call_alerts").requiredScopes)
    }
}
