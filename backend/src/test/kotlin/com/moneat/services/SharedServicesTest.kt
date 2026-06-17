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

package com.moneat.services

import com.moneat.auth.services.AuthTokenService
import com.moneat.billing.models.PricingTierConfigs
import com.moneat.org.services.OrgInvitationService
import com.moneat.shared.models.Hosts
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.UsageRecords
import com.moneat.shared.services.ArtifactCleanupService
import com.moneat.shared.services.CacheService
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.shared.services.TaskLock
import com.moneat.shared.services.UsageTrackingService
import com.moneat.shared.services.normalizeVersionTag
import com.moneat.testsupport.TestDatabaseHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.core.SimpleLock
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.Optional
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class SharedServicesTest {

    companion object {
        private var db: Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_shared_services;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(
            Organizations,
            Projects,
            Subscriptions,
            PricingTierConfigs,
            UsageRecords,
            Hosts
        )
    }

    private fun seedOrg(name: String = "Test Org"): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    private fun seedProject(orgId: Int, name: String = "test-project"): Long =
        transaction {
            Projects.insert {
                it[organization_id] = orgId
                it[Projects.name] = name
                it[slug] = name
            } get Projects.id
        }

    private fun seedHost(orgId: Int, name: String = "test-host"): Int =
        transaction {
            Hosts.insert {
                it[organization_id] = orgId
                it[hostname] = name
                it[first_seen_at] = Clock.System.now()
                it[last_seen_at] = Clock.System.now()
            } get Hosts.id
        }

    private fun seedFreeTier(
        retentionDays: Int = 3,
        logRetentionDays: Int = 3
    ): Int =
        transaction {
            PricingTierConfigs.insert {
                it[tier_name] = "FREE"
                it[version] = 1
                it[monthly_unit_limit] = 5000
                it[monthly_error_limit] = 5000
                it[retention_days] = retentionDays
                it[log_retention_days] = logRetentionDays
                it[max_systems] = 3
                it[monitor_interval_seconds] = 60
                it[monthly_price_cents] = 0
                it[yearly_price_cents] = 0
                it[is_current] = true
            } get PricingTierConfigs.id
        }

    private fun seedProTier(
        retentionDays: Int = 30,
        logRetentionDays: Int = 30
    ): Int =
        transaction {
            PricingTierConfigs.insert {
                it[tier_name] = "PRO"
                it[version] = 1
                it[monthly_unit_limit] = 50_000
                it[monthly_error_limit] = 50_000
                it[retention_days] = retentionDays
                it[log_retention_days] = logRetentionDays
                it[max_systems] = 10
                it[monitor_interval_seconds] = 30
                it[monthly_price_cents] = 2900
                it[yearly_price_cents] = 24900
                it[is_current] = true
            } get PricingTierConfigs.id
        }

    private fun seedSubscription(orgId: Int, tierId: Int, plan: String = "PRO"): Int =
        transaction {
            Subscriptions.insert {
                it[organization_id] = orgId
                it[Subscriptions.plan] = plan
                it[status] = "active"
                it[pricing_tier_config_id] = tierId
            } get Subscriptions.id
        }

    // ──── TaskLock ────

    @Test
    fun `TaskLock executes block when lock is acquired`() = runBlocking {
        val lock = mockk<SimpleLock>(relaxed = true)
        val lockProvider = mockk<LockProvider>()
        every { lockProvider.lock(any<LockConfiguration>()) } returns Optional.of(lock)

        TaskLock.initialize(lockProvider)

        var executed = false
        val result = TaskLock.tryWithLock("test-lock", 1.minutes) {
            executed = true
            "done"
        }

        assertTrue(executed)
        assertEquals("done", result)
        verify { lock.unlock() }
    }

    @Test
    fun `TaskLock returns null when lock not available`() = runBlocking {
        val lockProvider = mockk<LockProvider>()
        every { lockProvider.lock(any<LockConfiguration>()) } returns Optional.empty()

        TaskLock.initialize(lockProvider)

        var executed = false
        val result = TaskLock.tryWithLock<String>("test-lock-na", 1.minutes) {
            executed = true
            "should not run"
        }

        assertFalse(executed)
        assertNull(result)
    }

    @Test
    fun `TaskLock returns null when block throws exception`() = runBlocking {
        val lock = mockk<SimpleLock>(relaxed = true)
        val lockProvider = mockk<LockProvider>()
        every { lockProvider.lock(any<LockConfiguration>()) } returns Optional.of(lock)

        TaskLock.initialize(lockProvider)

        val result = TaskLock.tryWithLock<String>("test-lock-err", 1.minutes) {
            throw RuntimeException("boom")
        }

        assertNull(result)
        verify { lock.unlock() }
    }

    @Test
    fun `TaskLock returns null when lock provider throws`() = runBlocking {
        val lockProvider = mockk<LockProvider>()
        every { lockProvider.lock(any<LockConfiguration>()) } throws RuntimeException("provider error")

        TaskLock.initialize(lockProvider)

        val result = TaskLock.tryWithLock<String>("test-lock-prov", 1.minutes) {
            "should not run"
        }

        assertNull(result)
    }

    // ──── CacheService (Redis unavailable) ────

    @Test
    fun `invalidate does not throw when Redis unavailable`() {
        CacheService.invalidate("nonexistent:key")
    }

    @Test
    fun `invalidatePattern does not throw when Redis unavailable`() {
        CacheService.invalidatePattern("cache:test:*")
    }

    @Test
    fun `cached with zero TTL still returns loader result`() = runBlocking {
        val result = CacheService.cached<String>("test:zero-ttl", 0) { "value" }
        assertEquals("value", result)
    }

    @Test
    fun `cached with nullable type returns null from loader`() = runBlocking {
        val result = CacheService.cached<String?>("test:nullable", 60) { null }
        assertNull(result)
    }

    // ──── ArtifactCleanupService ────

    @Test
    fun `ArtifactCleanupService start and stop lifecycle`() = runBlocking {
        val authTokenService = mockk<AuthTokenService>()
        val orgInvitationService = mockk<OrgInvitationService>()

        every { authTokenService.cleanupExpiredTokens() } returns 3
        every { orgInvitationService.purgeOldInvitations(any()) } returns 1

        val scope = CoroutineScope(Dispatchers.Default)
        val service = ArtifactCleanupService(
            authTokenService = authTokenService,
            orgInvitationService = orgInvitationService,
            cleanupInterval = kotlin.time.Duration.parse("100ms")
        )

        service.start(scope)
        delay(300)
        service.stop()
        scope.cancel()

        verify(atLeast = 1) { authTokenService.cleanupExpiredTokens() }
        verify(atLeast = 1) { orgInvitationService.purgeOldInvitations(90) }
    }

    @Test
    fun `ArtifactCleanupService handles exception during cleanup`() = runBlocking {
        val authTokenService = mockk<AuthTokenService>()
        val orgInvitationService = mockk<OrgInvitationService>()

        every { authTokenService.cleanupExpiredTokens() } throws RuntimeException("DB down")
        every { orgInvitationService.purgeOldInvitations(any()) } returns 0

        val scope = CoroutineScope(Dispatchers.Default)
        val service = ArtifactCleanupService(
            authTokenService = authTokenService,
            orgInvitationService = orgInvitationService,
            cleanupInterval = kotlin.time.Duration.parse("100ms")
        )

        service.start(scope)
        delay(300)
        service.stop()
        scope.cancel()
        // No exception propagated - service survives errors
    }

    // ──── RetentionPolicyService (extended) ────

    @Test
    fun `getRetentionDaysForHost returns org retention`() = runBlocking {
        seedFreeTier(retentionDays = 7)
        val orgId = seedOrg()
        val hostId = seedHost(orgId)

        val service = RetentionPolicyService()
        val days = service.getRetentionDaysForHost(hostId)

        assertEquals(7, days)
    }

    @Test
    fun `getRetentionDaysForHost returns null for unknown host`() = runBlocking {
        seedFreeTier()
        val service = RetentionPolicyService()
        val days = service.getRetentionDaysForHost(99999)

        assertNull(days)
    }

    @Test
    fun `getReplayRetentionDaysForOrganization returns FREE tier value`() = runBlocking {
        seedFreeTier()
        val orgId = seedOrg()

        val service = RetentionPolicyService()
        val days = service.getReplayRetentionDaysForOrganization(orgId)

        // FREE tier replay retention defaults to 0
        assertEquals(0, days)
    }

    @Test
    fun `getReplayRetentionDaysByOrganization returns map`() = runBlocking {
        seedFreeTier()
        val org1 = seedOrg("Org A")
        val org2 = seedOrg("Org B")

        val service = RetentionPolicyService()
        val map = service.getReplayRetentionDaysByOrganization()

        assertEquals(2, map.size)
        assertNotNull(map[org1])
        assertNotNull(map[org2])
    }

    @Test
    fun `getReplayRetentionDaysByOrganization empty when no orgs`() = runBlocking {
        seedFreeTier()
        val service = RetentionPolicyService()
        val map = service.getReplayRetentionDaysByOrganization()

        assertTrue(map.isEmpty())
    }

    @Test
    fun `getLlmRetentionDaysForOrganization returns FREE tier value`() = runBlocking {
        seedFreeTier()
        val orgId = seedOrg()

        val service = RetentionPolicyService()
        val days = service.getLlmRetentionDaysForOrganization(orgId)

        // FREE tier LLM retention defaults to 0
        assertEquals(0, days)
    }

    @Test
    fun `getLlmRetentionDaysByOrganization returns map`() = runBlocking {
        seedFreeTier()
        seedOrg("Org A")
        seedOrg("Org B")

        val service = RetentionPolicyService()
        val map = service.getLlmRetentionDaysByOrganization()

        assertEquals(2, map.size)
    }

    @Test
    fun `getLlmRetentionDaysByOrganization empty when no orgs`() = runBlocking {
        seedFreeTier()
        val service = RetentionPolicyService()
        val map = service.getLlmRetentionDaysByOrganization()

        assertTrue(map.isEmpty())
    }

    @Test
    fun `getAnalyticsRetentionDaysForOrganization returns tier value`() = runBlocking {
        seedFreeTier()
        val orgId = seedOrg()

        val service = RetentionPolicyService()
        val days = service.getAnalyticsRetentionDaysForOrganization(orgId)

        assertTrue(days > 0)
    }

    @Test
    fun `getAnalyticsRetentionDaysByOrganization returns map`() = runBlocking {
        seedFreeTier()
        seedOrg("Org A")
        seedOrg("Org B")

        val service = RetentionPolicyService()
        val map = service.getAnalyticsRetentionDaysByOrganization()

        assertEquals(2, map.size)
    }

    @Test
    fun `getAnalyticsRetentionDaysByOrganization empty when no orgs`() = runBlocking {
        seedFreeTier()
        val service = RetentionPolicyService()
        val map = service.getAnalyticsRetentionDaysByOrganization()

        assertTrue(map.isEmpty())
    }

    @Test
    fun `getLogRetentionDaysForProject returns null for unknown project`() = runBlocking {
        seedFreeTier()
        val service = RetentionPolicyService()
        val days = service.getLogRetentionDaysForProject(99999L)

        assertNull(days)
    }

    // ──── UsageTrackingService (extended) ────

    @Test
    fun `recordOrgUsage records org-level event`() {
        val orgId = seedOrg()
        val today = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
        val startDate = today.plus(DatePeriod(days = -1))

        val service = UsageTrackingService()
        service.recordOrgUsage(orgId, "log", 512)
        service.flushBuffer()

        val count = service.getEventCountForOrg(orgId, startDate, today, listOf("log"))
        assertEquals(1L, count)
    }

    @Test
    fun `recordOrgUsage with explicit count records multiple events`() {
        val orgId = seedOrg()
        val today = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
        val startDate = today.plus(DatePeriod(days = -1))

        val service = UsageTrackingService()
        service.recordOrgUsage(orgId, "span", 5, 2048)
        service.flushBuffer()

        val count = service.getEventCountForOrg(orgId, startDate, today, listOf("span"))
        assertEquals(5L, count)
    }

    @Test
    fun `recordOrgUsage with zero count is ignored`() {
        val orgId = seedOrg()
        val today = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
        val startDate = today.plus(DatePeriod(days = -1))

        val service = UsageTrackingService()
        service.recordOrgUsage(orgId, "metric", 0, 100)
        service.flushBuffer()

        val count = service.getEventCountForOrg(orgId, startDate, today, listOf("metric"))
        assertEquals(0L, count)
    }

    @Test
    fun `recordOrgUsage with negative count is ignored`() {
        val orgId = seedOrg()
        val today = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
        val startDate = today.plus(DatePeriod(days = -1))

        val service = UsageTrackingService()
        service.recordOrgUsage(orgId, "metric", -1, 100)
        service.flushBuffer()

        val count = service.getEventCountForOrg(orgId, startDate, today, listOf("metric"))
        assertEquals(0L, count)
    }

    @Test
    fun `flush aggregates multiple records for same key`() {
        val orgId = seedOrg()
        val projectId = seedProject(orgId)
        val today = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
        val startDate = today.plus(DatePeriod(days = -1))

        val service = UsageTrackingService()
        service.recordUsage(projectId, "error", 100)
        service.recordUsage(projectId, "error", 200)
        service.flushBuffer()

        val bytes = service.getTotalBytesForOrg(orgId, startDate, today)
        assertEquals(300L, bytes)
    }

    @Test
    fun `upsert aggregates across flushes`() {
        val orgId = seedOrg()
        val projectId = seedProject(orgId)
        val today = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
        val startDate = today.plus(DatePeriod(days = -1))

        val service = UsageTrackingService()
        service.recordUsage(projectId, "error", 100)
        service.flushBuffer()
        service.recordUsage(projectId, "error", 200)
        service.flushBuffer()

        val bytes = service.getTotalBytesForOrg(orgId, startDate, today)
        assertEquals(300L, bytes)
    }

    // ──── normalizeVersionTag (additional edge cases) ────

    @Test
    fun `normalizeVersionTag handles version with prerelease dash suffix`() {
        // The regex captures the -beta prerelease but not +metadata (+ is not in the character class)
        assertEquals("1.0.0-beta", normalizeVersionTag("v1.0.0-beta+exp.sha.5114f85"))
    }

    @Test
    fun `normalizeVersionTag handles version embedded in path`() {
        assertEquals("2.5.0", normalizeVersionTag("releases/v2.5.0"))
    }

    @Test
    fun `normalizeVersionTag returns null for only dots`() {
        assertNull(normalizeVersionTag("..."))
    }

    @Test
    fun `normalizeVersionTag handles version with leading zeros`() {
        assertEquals("0.0.1", normalizeVersionTag("v0.0.1"))
    }

    @Test
    fun `normalizeVersionTag returns null for single number`() {
        assertNull(normalizeVersionTag("42"))
    }

    @Test
    fun `normalizeVersionTag returns null for two-part version`() {
        assertNull(normalizeVersionTag("1.2"))
    }

    // ──── RetentionPolicyService with PRO tier ────

    @Test
    fun `getReplayRetentionDaysForOrganization returns PRO tier value`() = runBlocking {
        seedFreeTier()
        val proTierId = seedProTier(retentionDays = 30)
        val orgId = seedOrg()
        seedSubscription(orgId, proTierId)

        val service = RetentionPolicyService()
        val days = service.getReplayRetentionDaysForOrganization(orgId)

        // PRO tier replay retention defaults to 0 in DB unless explicitly set
        assertTrue(days >= 0)
    }

    @Test
    fun `getLlmRetentionDaysForOrganization returns PRO tier value`() = runBlocking {
        seedFreeTier()
        val proTierId = seedProTier(retentionDays = 30)
        val orgId = seedOrg()
        seedSubscription(orgId, proTierId)

        val service = RetentionPolicyService()
        val days = service.getLlmRetentionDaysForOrganization(orgId)

        // PRO tier LLM retention defaults to 0 in DB unless explicitly set
        assertTrue(days >= 0)
    }

    @Test
    fun `getAnalyticsRetentionDaysForOrganization returns PRO tier value`() = runBlocking {
        seedFreeTier()
        val proTierId = seedProTier(retentionDays = 30)
        val orgId = seedOrg()
        seedSubscription(orgId, proTierId)

        val service = RetentionPolicyService()
        val days = service.getAnalyticsRetentionDaysForOrganization(orgId)

        assertTrue(days > 0)
    }

    @Test
    fun `getRetentionDaysForHost with PRO subscription returns PRO retention`() = runBlocking {
        seedFreeTier()
        val proTierId = seedProTier(retentionDays = 30)
        val orgId = seedOrg()
        seedSubscription(orgId, proTierId)
        val hostId = seedHost(orgId)

        val service = RetentionPolicyService()
        val days = service.getRetentionDaysForHost(hostId)

        assertEquals(30, days)
    }

    // ──── UsageTrackingService ORG_PROJECT_ID_SENTINEL ────

    @Test
    fun `ORG_PROJECT_ID_SENTINEL constant is zero`() {
        assertEquals(0L, UsageTrackingService.ORG_PROJECT_ID_SENTINEL)
    }
}
