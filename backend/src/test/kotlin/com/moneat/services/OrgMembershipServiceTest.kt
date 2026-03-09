package com.moneat.services

import com.moneat.org.repositories.OrgMembershipRepository
import com.moneat.org.repositories.models.OrgMemberRow
import com.moneat.org.services.OrgMembershipService
import com.moneat.org.services.OrgRole
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrgMembershipServiceTest {
    private val repository = mockk<OrgMembershipRepository>()
    private val service = OrgMembershipService(repository)

    @Test
    fun `getMembers returns all members of org`() {
        every { repository.getMembers(1) } returns listOf(
            OrgMemberRow(userId = 1, email = "user1@test.com", name = "User One", role = "owner"),
            OrgMemberRow(userId = 2, email = "user2@test.com", name = "User Two", role = "member")
        )
        val members = service.getMembers(1)
        assertEquals(2, members.size)
        assertTrue(members.any { it.email == "user1@test.com" && it.role == "owner" })
        assertTrue(members.any { it.email == "user2@test.com" && it.role == "member" })
    }

    @Test
    fun `getMembers returns empty list for org with no members`() {
        every { repository.getMembers(1) } returns emptyList()
        assertEquals(0, service.getMembers(1).size)
    }

    @Test
    fun `getMemberRole returns role for existing member`() {
        every { repository.getMemberRole(1, 10) } returns "owner"
        assertEquals("owner", service.getMemberRole(1, 10))
    }

    @Test
    fun `getMemberRole returns null for non-member`() {
        every { repository.getMemberRole(1, 99) } returns null
        assertNull(service.getMemberRole(1, 99))
    }

    @Test
    fun `isMember returns true for member`() {
        every { repository.isMember(1, 10) } returns true
        assertTrue(service.isMember(1, 10))
    }

    @Test
    fun `isMember returns false for non-member`() {
        every { repository.isMember(1, 99) } returns false
        assertFalse(service.isMember(1, 99))
    }

    @Test
    fun `requireRole succeeds when user has sufficient role`() {
        every { repository.getMemberRole(1, 10) } returns "admin"
        service.requireRole(1, 10, OrgRole.ADMIN)
        service.requireRole(1, 10, OrgRole.MEMBER)
    }

    @Test
    fun `requireRole throws when user has insufficient role`() {
        every { repository.getMemberRole(1, 20) } returns "member"
        assertFailsWith<IllegalStateException> { service.requireRole(1, 20, OrgRole.ADMIN) }
    }

    @Test
    fun `requireRole throws for non-member`() {
        every { repository.getMemberRole(1, 99) } returns null
        assertFailsWith<IllegalStateException> { service.requireRole(1, 99, OrgRole.MEMBER) }
    }

    @Test
    fun `updateMemberRole updates role successfully`() {
        val orgId = 1; val ownerId = 1; val memberId = 2
        every { repository.getMemberRole(orgId, ownerId) } returns "owner"
        every { repository.getMemberRole(orgId, memberId) } returns "member"
        every { repository.updateMemberRole(orgId, memberId, "admin") } returns 1
        assertTrue(service.updateMemberRole(orgId, memberId, "admin", ownerId))
        verify { repository.updateMemberRole(orgId, memberId, "admin") }
    }

    @Test
    fun `updateMemberRole prevents admin from assigning owner role`() {
        val orgId = 1; val adminId = 2; val memberId = 3
        every { repository.getMemberRole(orgId, adminId) } returns "admin"
        every { repository.getMemberRole(orgId, memberId) } returns "member"
        assertFailsWith<IllegalStateException> {
            service.updateMemberRole(orgId, memberId, "owner", adminId)
        }
    }

    @Test
    fun `updateMemberRole prevents admin from modifying owner`() {
        val orgId = 1; val adminId = 2; val ownerId = 3
        every { repository.getMemberRole(orgId, adminId) } returns "admin"
        every { repository.getMemberRole(orgId, ownerId) } returns "owner"
        assertFailsWith<IllegalStateException> {
            service.updateMemberRole(orgId, ownerId, "admin", adminId)
        }
    }

    @Test
    fun `updateMemberRole prevents changing last owner`() {
        val orgId = 1; val ownerId = 1
        every { repository.getMemberRole(orgId, ownerId) } returns "owner"
        every { repository.getOwnerCount(orgId) } returns 1
        assertFailsWith<Exception> {
            service.updateMemberRole(orgId, ownerId, "admin", ownerId)
        }
    }

    @Test
    fun `removeMember removes member successfully`() {
        val orgId = 1; val ownerId = 1; val memberId = 2
        every { repository.getMemberRole(orgId, ownerId) } returns "owner"
        every { repository.getMemberRole(orgId, memberId) } returns "member"
        every { repository.removeMember(orgId, memberId) } returns 1
        assertTrue(service.removeMember(orgId, memberId, ownerId))
        verify { repository.removeMember(orgId, memberId) }
    }

    @Test
    fun `removeMember prevents removing owner`() {
        val orgId = 1; val adminId = 2; val ownerId = 3
        every { repository.getMemberRole(orgId, adminId) } returns "admin"
        every { repository.getMemberRole(orgId, ownerId) } returns "owner"
        assertFailsWith<Exception> { service.removeMember(orgId, ownerId, adminId) }
    }

    @Test
    fun `removeMember prevents admin from removing another admin`() {
        val orgId = 1; val admin1Id = 2; val admin2Id = 3
        every { repository.getMemberRole(orgId, admin1Id) } returns "admin"
        every { repository.getMemberRole(orgId, admin2Id) } returns "admin"
        assertFailsWith<IllegalStateException> { service.removeMember(orgId, admin2Id, admin1Id) }
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
