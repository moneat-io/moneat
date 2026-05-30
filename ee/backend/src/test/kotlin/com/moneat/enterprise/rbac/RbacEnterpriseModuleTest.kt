// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.rbac

import com.moneat.enterprise.rbac.models.RbacRoleAssignments
import com.moneat.enterprise.rbac.models.RbacRoles
import com.moneat.enterprise.rbac.services.RbacService
import com.moneat.enterprise.sso.support.EnterpriseTestDatabaseHelper
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RbacEnterpriseModuleTest {

    companion object {
        private var db: Database? = null
    }

    private val module = RbacEnterpriseModule()
    private val seed = RbacService()

    @BeforeEach
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_ee_rbac_module;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        EnterpriseTestDatabaseHelper.resetSchema(RbacRoles, RbacRoleAssignments)
    }

    @AfterEach
    fun clearDbRef() {
        TransactionManager.defaultDatabase = null
    }

    @Test
    fun `is gated behind the cross-cutting advanced_rbac license feature`() {
        assertEquals("Advanced RBAC", module.name)
        assertEquals("advanced_rbac", module.licenseFeature)
    }

    @Test
    fun `defers to coarse role gates for users without a granular assignment`() {
        // null => caller falls back to the coarse ADMIN/MEMBER org-role gates.
        assertNull(module.resolvePermissions(organizationId = 1, userId = 2))
        assertNull(module.hasPermission(organizationId = 1, userId = 2, permission = "workflows:run"))
    }

    @Test
    fun `resolves granular permissions for an assigned user`() {
        val role = seed.createRole(1, "runner", listOf("workflows:run"), null)
        seed.assignRole(1, role.id, 2)
        assertEquals(setOf("workflows:run"), module.resolvePermissions(1, 2))
        assertEquals(true, module.hasPermission(1, 2, "workflows:run"))
        assertEquals(false, module.hasPermission(1, 2, "incidents:write"))
    }

    @Test
    fun `lifecycle hooks are inert no-ops in this phase`() {
        // Should not throw without any wiring.
        assertTrue(module.licenseFeature.isNotBlank())
        module.stopBackgroundJobs()
    }
}
