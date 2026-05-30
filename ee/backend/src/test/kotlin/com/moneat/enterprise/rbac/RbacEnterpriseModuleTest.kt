// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.rbac

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RbacEnterpriseModuleTest {

    private val module = RbacEnterpriseModule()

    @Test
    fun `is gated behind the cross-cutting advanced_rbac license feature`() {
        assertEquals("Advanced RBAC", module.name)
        assertEquals("advanced_rbac", module.licenseFeature)
    }

    @Test
    fun `defers RBAC decisions to coarse role gates until granular resolution ships`() {
        // null => caller falls back to the coarse ADMIN/MEMBER org-role gates.
        assertNull(module.resolvePermissions(organizationId = 1, userId = 2))
        assertNull(module.hasPermission(organizationId = 1, userId = 2, permission = "workflows:run"))
        assertNull(module.hasPermission(organizationId = 1, userId = 2, permission = "incidents:write"))
    }

    @Test
    fun `lifecycle hooks are inert no-ops in this phase`() {
        // Should not throw without any wiring.
        module.stopBackgroundJobs()
    }
}
