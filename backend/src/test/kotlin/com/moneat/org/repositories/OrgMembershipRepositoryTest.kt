// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package com.moneat.org.repositories

import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrgMembershipRepositoryTest {

    companion object {
        private const val MEMBER_EMAIL = "user@test.com"
    }

    private var db: Database? = null
    private lateinit var repository: OrgMembershipRepository

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_org_membership_repo;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships)
        repository = OrgMembershipRepositoryImpl()
    }

    private fun insertOrg(name: String = "Test Org"): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    private fun insertUser(email: String): Int =
        transaction {
            Users.insert {
                it[Users.email] = email
                it[password_hash] = "hashed"
            } get Users.id
        }

    private fun insertMembership(orgId: Int, userId: Int, role: String): Int =
        transaction {
            Memberships.insert {
                it[organization_id] = orgId
                it[user_id] = userId
                it[Memberships.role] = role
            } get Memberships.id
        }

    @Test
    fun getMembersReturnsAllMembersForOrg() {
        val orgId = insertOrg()
        val userId1 = insertUser("user1@test.com")
        val userId2 = insertUser("user2@test.com")
        insertMembership(orgId, userId1, "owner")
        insertMembership(orgId, userId2, "member")

        val members = repository.getMembers(orgId)

        assertEquals(2, members.size)
        assertTrue(members.any { it.email == "user1@test.com" && it.role == "owner" })
        assertTrue(members.any { it.email == "user2@test.com" && it.role == "member" })
    }

    @Test
    fun getMembersReturnsEmptyForOrgWithNoMembers() {
        val orgId = insertOrg()
        assertEquals(0, repository.getMembers(orgId).size)
    }

    @Test
    fun getMembersExcludesMembersFromOtherOrgs() {
        val org1 = insertOrg("Org 1")
        val org2 = insertOrg("Org 2")
        val userId = insertUser(MEMBER_EMAIL)
        insertMembership(org1, userId, "owner")
        insertMembership(org2, userId, "member")

        assertEquals(1, repository.getMembers(org1).size)
        assertEquals(1, repository.getMembers(org2).size)
    }

    @Test
    fun getMemberRoleReturnsRoleForExistingMember() {
        val orgId = insertOrg()
        val userId = insertUser("owner@test.com")
        insertMembership(orgId, userId, "owner")

        assertEquals("owner", repository.getMemberRole(orgId, userId))
    }

    @Test
    fun getMemberRoleReturnsNullForNonMember() {
        val orgId = insertOrg()
        val userId = insertUser("outsider@test.com")
        assertNull(repository.getMemberRole(orgId, userId))
    }

    @Test
    fun updateMemberRoleUpdatesSuccessfully() {
        val orgId = insertOrg()
        val userId = insertUser(MEMBER_EMAIL)
        insertMembership(orgId, userId, "member")

        val updated = repository.updateMemberRole(orgId, userId, "admin")

        assertEquals(1, updated)
        assertEquals("admin", repository.getMemberRole(orgId, userId))
    }

    @Test
    fun updateMemberRoleReturnsZeroForNonMember() {
        val orgId = insertOrg()
        val result = repository.updateMemberRole(orgId, 99999, "admin")
        assertEquals(0, result)
    }

    @Test
    fun removeMemberDeletesSuccessfully() {
        val orgId = insertOrg()
        val userId = insertUser(MEMBER_EMAIL)
        insertMembership(orgId, userId, "member")

        val deleted = repository.removeMember(orgId, userId)

        assertEquals(1, deleted)
        assertFalse(repository.isMember(orgId, userId))
    }

    @Test
    fun removeMemberReturnsZeroForNonMember() {
        val orgId = insertOrg()
        assertEquals(0, repository.removeMember(orgId, 99999))
    }

    @Test
    fun isMemberReturnsTrueForMember() {
        val orgId = insertOrg()
        val userId = insertUser("member@test.com")
        insertMembership(orgId, userId, "member")
        assertTrue(repository.isMember(orgId, userId))
    }

    @Test
    fun isMemberReturnsFalseForNonMember() {
        val orgId = insertOrg()
        val userId = insertUser("outsider@test.com")
        assertFalse(repository.isMember(orgId, userId))
    }

    @Test
    fun getOwnerCountReturnsCorrectCount() {
        val orgId = insertOrg()
        val owner1 = insertUser("owner1@test.com")
        val owner2 = insertUser("owner2@test.com")
        val member = insertUser("member@test.com")
        insertMembership(orgId, owner1, "owner")
        insertMembership(orgId, owner2, "owner")
        insertMembership(orgId, member, "member")

        assertEquals(2, repository.getOwnerCount(orgId))
    }

    @Test
    fun getOwnerCountReturnsZeroForNoOwners() {
        val orgId = insertOrg()
        assertEquals(0, repository.getOwnerCount(orgId))
    }

    @Test
    fun getMembersPopulatesUserFields() {
        val orgId = insertOrg()
        val userId = transaction {
            Users.insert {
                it[Users.email] = "named@test.com"
                it[password_hash] = "hashed"
                it[Users.name] = "Named User"
            } get Users.id
        }
        insertMembership(orgId, userId, "admin")

        val members = repository.getMembers(orgId)
        val member = members.single()
        assertNotNull(member)
        assertEquals("named@test.com", member.email)
        assertEquals("Named User", member.name)
        assertEquals("admin", member.role)
        assertEquals(userId, member.userId)
    }
}
