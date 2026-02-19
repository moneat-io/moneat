package com.moneat.services

import com.moneat.models.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.*

class SidebarPreferenceServiceTest {
    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_sidebar_pref;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction {
                SchemaUtils.create(Organizations, Users, Memberships, SidebarPreferenceEvents)
            }
            dbInitialized = true
        }

        transaction {
            SidebarPreferenceEvents.deleteAll()
            Memberships.deleteAll()
            Users.deleteAll()
            Organizations.deleteAll()
        }
    }

    private fun seedMembership(): Triple<Int, Int, Int> = transaction {
        val orgId = Organizations.insert {
            it[name] = "Test Org"
            it[slug] = "test-org"
        } get Organizations.id

        val userId = Users.insert {
            it[email] = "test@example.com"
            it[name] = "Test"
            it[password_hash] = "hash"
            it[email_verified] = true
        } get Users.id

        val memId = Memberships.insert {
            it[user_id] = userId
            it[organization_id] = orgId
            it[role] = "owner"
        } get Memberships.id

        Triple(memId, userId, orgId)
    }

    // --- normalizeHiddenItems ---

    @Test
    fun `normalizeHiddenItems filters unknown keys`() {
        val result = SidebarPreferenceService.normalizeHiddenItems(listOf("issues", "unknown", "logs"))
        assertEquals(listOf("issues", "logs"), result)
    }

    @Test
    fun `normalizeHiddenItems deduplicates`() {
        val result = SidebarPreferenceService.normalizeHiddenItems(listOf("issues", "issues", "logs"))
        assertEquals(listOf("issues", "logs"), result)
    }

    @Test
    fun `normalizeHiddenItems sorts alphabetically`() {
        val result = SidebarPreferenceService.normalizeHiddenItems(listOf("replays", "ai", "logs"))
        assertEquals(listOf("ai", "logs", "replays"), result)
    }

    @Test
    fun `normalizeHiddenItems returns empty for all unknown`() {
        val result = SidebarPreferenceService.normalizeHiddenItems(listOf("unknown1", "unknown2"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `normalizeHiddenItems accepts all configurable items`() {
        val all = listOf("dashboard", "performance", "issues", "logs", "replays",
            "feedback", "releases", "ai", "uptime", "status-pages", "monitoring", "on-call")
        val result = SidebarPreferenceService.normalizeHiddenItems(all)
        assertEquals(all.sorted(), result)
    }

    // --- getPreferences ---

    @Test
    fun `getPreferences returns empty by default`() {
        val (memId, _, _) = seedMembership()
        val prefs = transaction { SidebarPreferenceService.getPreferences(memId) }
        assertTrue(prefs.isEmpty())
    }

    // --- updatePreferences ---

    @Test
    fun `updatePreferences stores normalized items`() {
        val (memId, userId, orgId) = seedMembership()

        val result = transaction {
            SidebarPreferenceService.updatePreferences(
                memId, userId, orgId, listOf("replays", "unknown", "issues"), "test"
            )
        }
        assertEquals(listOf("issues", "replays"), result)

        val stored = transaction { SidebarPreferenceService.getPreferences(memId) }
        assertEquals(listOf("issues", "replays"), stored)
    }

    @Test
    fun `updatePreferences logs event when items change`() {
        val (memId, userId, orgId) = seedMembership()

        transaction { SidebarPreferenceService.updatePreferences(memId, userId, orgId, listOf("issues"), "test") }

        val events = transaction {
            SidebarPreferenceEvents.selectAll()
                .where { SidebarPreferenceEvents.membership_id eq memId }
                .toList()
        }
        assertEquals(1, events.size)
        assertEquals("test", events[0][SidebarPreferenceEvents.event_source])
    }

    @Test
    fun `updatePreferences does not log event when items unchanged`() {
        val (memId, userId, orgId) = seedMembership()

        transaction { SidebarPreferenceService.updatePreferences(memId, userId, orgId, listOf("issues"), "first") }
        transaction { SidebarPreferenceService.updatePreferences(memId, userId, orgId, listOf("issues"), "second") }

        val events = transaction {
            SidebarPreferenceEvents.selectAll()
                .where { SidebarPreferenceEvents.membership_id eq memId }
                .toList()
        }
        assertEquals(1, events.size) // Only one event logged
    }

    @Test
    fun `updatePreferences can clear all hidden items`() {
        val (memId, userId, orgId) = seedMembership()

        transaction {
            SidebarPreferenceService.updatePreferences(memId, userId, orgId, listOf("issues", "logs"), "setup")
        }
        val result = transaction {
            SidebarPreferenceService.updatePreferences(memId, userId, orgId, emptyList(), "clear")
        }
        assertTrue(result.isEmpty())
        assertTrue(transaction { SidebarPreferenceService.getPreferences(memId) }.isEmpty())
    }
}
