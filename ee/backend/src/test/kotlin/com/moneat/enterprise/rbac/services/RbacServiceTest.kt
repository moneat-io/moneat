// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.rbac.services

import com.moneat.enterprise.rbac.models.RbacRoleAssignments
import com.moneat.enterprise.rbac.models.RbacRoles
import com.moneat.enterprise.sso.support.EnterpriseTestDatabaseHelper
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RbacServiceTest {

    companion object {
        private var db: Database? = null
    }

    private val service = RbacService()

    @BeforeEach
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_ee_rbac;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
    fun `an unassigned user resolves to null so callers fall back to coarse gates`() {
        assertNull(service.resolvePermissions(organizationId = 1, userId = 42))
        assertNull(service.hasPermission(1, 42, "workflows:run"))
    }

    @Test
    fun `resolvePermissions unions the keys of every assigned role`() {
        val reader = service.createRole(1, "reader", listOf("workflows:read"), null)
        val runner = service.createRole(1, "runner", listOf("workflows:run", "workflows:read"), null)
        service.assignRole(1, reader.id, 42)
        service.assignRole(1, runner.id, 42)
        assertEquals(setOf("workflows:read", "workflows:run"), service.resolvePermissions(1, 42))
    }

    @Test
    fun `hasPermission is true for a granted key and false for a missing one`() {
        val role = service.createRole(1, "runner", listOf("workflows:run"), null)
        service.assignRole(1, role.id, 42)
        assertEquals(true, service.hasPermission(1, 42, "workflows:run"))
        assertEquals(false, service.hasPermission(1, 42, "workflows:write"))
    }

    @Test
    fun `a role with no permissions still governs the user as an empty grant`() {
        val role = service.createRole(1, "empty", emptyList(), null)
        service.assignRole(1, role.id, 42)
        assertEquals(emptySet(), service.resolvePermissions(1, 42))
        assertEquals(false, service.hasPermission(1, 42, "workflows:read"))
    }

    @Test
    fun `roles and resolution are scoped to the organization`() {
        val role = service.createRole(1, "reader", listOf("workflows:read"), null)
        service.assignRole(1, role.id, 42)
        assertNull(service.resolvePermissions(2, 42))
        assertTrue(service.listRoles(2).isEmpty())
    }

    @Test
    fun `createRole rejects a duplicate name in the same org`() {
        service.createRole(1, "dupe", listOf("workflows:read"), null)
        assertFailsWith<IllegalArgumentException> {
            service.createRole(1, "dupe", listOf("workflows:run"), null)
        }
    }

    @Test
    fun `updateRole replaces the permission set`() {
        val role = service.createRole(1, "role", listOf("workflows:read"), null)
        val updated = service.updateRole(1, role.id, name = null, permissions = listOf("workflows:run"))
        assertEquals(listOf("workflows:run"), updated?.permissions)
    }

    @Test
    fun `assignRole is idempotent and returns null for an unknown role`() {
        val role = service.createRole(1, "role", listOf("workflows:read"), null)
        val first = service.assignRole(1, role.id, 42)
        val second = service.assignRole(1, role.id, 42)
        assertEquals(first?.id, second?.id)
        assertEquals(1, service.listAssignments(1, role.id).size)
        assertNull(service.assignRole(1, roleId = 9999, userId = 42))
    }

    @Test
    fun `unassignRole removes the grant and resolution reverts to null`() {
        val role = service.createRole(1, "role", listOf("workflows:read"), null)
        service.assignRole(1, role.id, 42)
        assertTrue(service.unassignRole(1, role.id, 42))
        assertNull(service.resolvePermissions(1, 42))
    }

    @Test
    fun `deleteRole removes the role`() {
        val role = service.createRole(1, "role", listOf("workflows:read"), null)
        assertTrue(service.deleteRole(1, role.id))
        assertNull(service.getRole(1, role.id))
    }
}
