package com.moneat.services

import com.moneat.models.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.*

class OrgMembershipServiceTest {
    private val service = OrgMembershipService()

    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_org_membership;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction {
                SchemaUtils.create(Users, Organizations, Memberships)
            }
            dbInitialized = true
        }

        transaction {
            Memberships.deleteAll()
            Users.deleteAll()
            Organizations.deleteAll()
        }
    }

    private fun insertOrg(name: String = "Test Org"): Int = transaction {
        Organizations.insert {
            it[Organizations.name] = name
            it[slug] = name.lowercase().replace(" ", "-")
        } get Organizations.id
    }

    private fun insertUser(email: String, name: String? = null): Int = transaction {
        Users.insert {
            it[Users.email] = email
            it[password_hash] = "hashed"
            it[Users.name] = name
            it[email_verified] = true
        } get Users.id
    }

    private fun insertMembership(orgId: Int, userId: Int, role: String): Int = transaction {
        Memberships.insert {
            it[organization_id] = orgId
            it[user_id] = userId
            it[Memberships.role] = role
        } get Memberships.id
    }

    @Test
    fun `getMembers returns all members of org`() {
        val orgId = insertOrg()
        val userId1 = insertUser("user1@test.com", "User One")
        val userId2 = insertUser("user2@test.com", "User Two")
        insertMembership(orgId, userId1, "owner")
        insertMembership(orgId, userId2, "member")

        val members = service.getMembers(orgId)

        assertEquals(2, members.size)
        assertTrue(members.any { it.email == "user1@test.com" && it.role == "owner" })
        assertTrue(members.any { it.email == "user2@test.com" && it.role == "member" })
    }

    @Test
    fun `getMembers returns empty list for org with no members`() {
        val orgId = insertOrg()
        val members = service.getMembers(orgId)
        assertEquals(0, members.size)
    }

    @Test
    fun `getMemberRole returns role for existing member`() {
        val orgId = insertOrg()
        val userId = insertUser("owner@test.com")
        insertMembership(orgId, userId, "owner")

        val role = service.getMemberRole(orgId, userId)
        assertEquals("owner", role)
    }

    @Test
    fun `getMemberRole returns null for non-member`() {
        val orgId = insertOrg()
        val userId = insertUser("outsider@test.com")

        val role = service.getMemberRole(orgId, userId)
        assertNull(role)
    }

    @Test
    fun `isMember returns true for member`() {
        val orgId = insertOrg()
        val userId = insertUser("member@test.com")
        insertMembership(orgId, userId, "member")

        assertTrue(service.isMember(orgId, userId))
    }

    @Test
    fun `isMember returns false for non-member`() {
        val orgId = insertOrg()
        val userId = insertUser("outsider@test.com")

        assertFalse(service.isMember(orgId, userId))
    }

    @Test
    fun `requireRole succeeds when user has sufficient role`() {
        val orgId = insertOrg()
        val userId = insertUser("admin@test.com")
        insertMembership(orgId, userId, "admin")

        // Should not throw
        service.requireRole(orgId, userId, OrgRole.ADMIN)
        service.requireRole(orgId, userId, OrgRole.MEMBER)
    }

    @Test
    fun `requireRole throws when user has insufficient role`() {
        val orgId = insertOrg()
        val userId = insertUser("member@test.com")
        insertMembership(orgId, userId, "member")

        assertFailsWith<IllegalStateException> {
            service.requireRole(orgId, userId, OrgRole.ADMIN)
        }
    }

    @Test
    fun `requireRole throws for non-member`() {
        val orgId = insertOrg()
        val userId = insertUser("outsider@test.com")

        assertFailsWith<IllegalStateException> {
            service.requireRole(orgId, userId, OrgRole.MEMBER)
        }
    }

    @Test
    fun `updateMemberRole updates role successfully`() {
        val orgId = insertOrg()
        val ownerId = insertUser("owner@test.com")
        val memberId = insertUser("member@test.com")
        insertMembership(orgId, ownerId, "owner")
        insertMembership(orgId, memberId, "member")

        val result = service.updateMemberRole(orgId, memberId, "admin", ownerId)
        assertTrue(result)
        assertEquals("admin", service.getMemberRole(orgId, memberId))
    }

    @Test
    fun `updateMemberRole prevents admin from assigning owner role`() {
        val orgId = insertOrg()
        val ownerId = insertUser("owner@test.com")
        val adminId = insertUser("admin@test.com")
        val memberId = insertUser("member@test.com")
        insertMembership(orgId, ownerId, "owner")
        insertMembership(orgId, adminId, "admin")
        insertMembership(orgId, memberId, "member")

        assertFailsWith<IllegalStateException> {
            service.updateMemberRole(orgId, memberId, "owner", adminId)
        }
    }

    @Test
    fun `updateMemberRole prevents admin from modifying owner`() {
        val orgId = insertOrg()
        val ownerId = insertUser("owner@test.com")
        val adminId = insertUser("admin@test.com")
        insertMembership(orgId, ownerId, "owner")
        insertMembership(orgId, adminId, "admin")

        assertFailsWith<IllegalStateException> {
            service.updateMemberRole(orgId, ownerId, "admin", adminId)
        }
    }

    @Test
    fun `updateMemberRole prevents changing last owner`() {
        val orgId = insertOrg()
        val ownerId = insertUser("owner@test.com")
        insertMembership(orgId, ownerId, "owner")

        assertFailsWith<Exception> {
            service.updateMemberRole(orgId, ownerId, "admin", ownerId)
        }
    }

    @Test
    fun `removeMember removes member successfully`() {
        val orgId = insertOrg()
        val ownerId = insertUser("owner@test.com")
        val memberId = insertUser("member@test.com")
        insertMembership(orgId, ownerId, "owner")
        insertMembership(orgId, memberId, "member")

        val result = service.removeMember(orgId, memberId, ownerId)
        assertTrue(result)
        assertFalse(service.isMember(orgId, memberId))
    }

    @Test
    fun `removeMember prevents removing owner`() {
        val orgId = insertOrg()
        val ownerId = insertUser("owner@test.com")
        val adminId = insertUser("admin@test.com")
        insertMembership(orgId, ownerId, "owner")
        insertMembership(orgId, adminId, "admin")

        assertFailsWith<Exception> {
            service.removeMember(orgId, ownerId, adminId)
        }
    }

    @Test
    fun `removeMember prevents admin from removing another admin`() {
        val orgId = insertOrg()
        val ownerId = insertUser("owner@test.com")
        val admin1Id = insertUser("admin1@test.com")
        val admin2Id = insertUser("admin2@test.com")
        insertMembership(orgId, ownerId, "owner")
        insertMembership(orgId, admin1Id, "admin")
        insertMembership(orgId, admin2Id, "admin")

        assertFailsWith<IllegalStateException> {
            service.removeMember(orgId, admin2Id, admin1Id)
        }
    }

    @Test
    fun `OrgRole fromString parses valid roles`() {
        assertEquals(OrgRole.MEMBER, OrgRole.fromString("member"))
        assertEquals(OrgRole.ADMIN, OrgRole.fromString("admin"))
        assertEquals(OrgRole.OWNER, OrgRole.fromString("owner"))
    }

    @Test
    fun `OrgRole fromString is case insensitive`() {
        assertEquals(OrgRole.ADMIN, OrgRole.fromString("Admin"))
        assertEquals(OrgRole.ADMIN, OrgRole.fromString("ADMIN"))
    }

    @Test
    fun `OrgRole levels are ordered correctly`() {
        assertTrue(OrgRole.MEMBER.level < OrgRole.ADMIN.level)
        assertTrue(OrgRole.ADMIN.level < OrgRole.OWNER.level)
    }
}
