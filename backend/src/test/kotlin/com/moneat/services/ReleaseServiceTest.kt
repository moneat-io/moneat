package com.moneat.services

import com.moneat.events.services.ReleaseService
import com.moneat.shared.models.ArtifactBundles
import com.moneat.shared.models.FileBlobs
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.ReleaseFiles
import com.moneat.shared.models.Releases
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.*

class ReleaseServiceTest {
    private val service = ReleaseService()

    companion object {
        private var db: org.jetbrains.exposed.v1.jdbc.Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_release_service;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        // Ensure schema exists (idempotent in H2) and clean between tests
        transaction {
            SchemaUtils.drop(
                ArtifactBundles,
                FileBlobs,
                ReleaseFiles,
                Releases,
                Projects,
                Memberships,
                Organizations,
                Users
            )
            SchemaUtils.create(
                Users,
                Organizations,
                Memberships,
                Projects,
                Releases,
                ReleaseFiles,
                FileBlobs,
                ArtifactBundles
            )
        }
    }

    private fun seedOrgAndProject(
        orgSlug: String = "test-org",
        projectSlug: String = "test-project"
    ): Pair<Int, Long> =
        transaction {
            val orgId =
                Organizations.insert {
                    it[name] = "Test Org"
                    it[slug] = orgSlug
                } get Organizations.id

            val projectId =
                Projects.insert {
                    it[organization_id] = orgId
                    it[name] = "Test Project"
                    it[slug] = projectSlug
                } get Projects.id

            orgId to projectId
        }

    private fun seedUser(orgId: Int): Int =
        transaction {
            val userId =
                Users.insert {
                    it[email] = "user@test.com"
                    it[password_hash] = "hashed"
                    it[email_verified] = true
                } get Users.id

            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }

            userId
        }

    // --- createRelease ---

    @Test
    fun `createRelease creates a new release`() {
        val (_, projectId) = seedOrgAndProject()
        val result = service.createRelease(projectId, "1.0.0", "main")

        assertEquals("1.0.0", result.version)
        assertEquals("main", result.ref)
        assertEquals("test-project", result.projectSlug)
        assertTrue(result.dateCreated.isNotBlank())
    }

    @Test
    fun `createRelease rejects duplicate version`() {
        val (_, projectId) = seedOrgAndProject()
        service.createRelease(projectId, "1.0.0", null)

        assertFailsWith<IllegalArgumentException> {
            service.createRelease(projectId, "1.0.0", null)
        }
    }

    @Test
    fun `createRelease throws for non-existent project`() {
        assertFailsWith<IllegalArgumentException> {
            service.createRelease(99999L, "1.0.0", null)
        }
    }

    // --- upsertReleaseFromEvent ---

    @Test
    fun `upsertReleaseFromEvent creates new auto-detected release`() {
        val (_, projectId) = seedOrgAndProject()
        val now = System.currentTimeMillis()

        service.upsertReleaseFromEvent(projectId, "2.0.0", now)

        val release =
            transaction {
                Releases.selectAll().where { Releases.version eq "2.0.0" }.first()
            }

        assertEquals(1L, release[Releases.event_count])
        assertTrue(release[Releases.is_auto_detected])
        assertEquals(now, release[Releases.first_seen])
        assertEquals(now, release[Releases.last_seen])
    }

    @Test
    fun `upsertReleaseFromEvent increments event count for existing release`() {
        val (_, projectId) = seedOrgAndProject()
        val t1 = System.currentTimeMillis()
        val t2 = t1 + 1000

        service.upsertReleaseFromEvent(projectId, "2.0.0", t1)
        service.upsertReleaseFromEvent(projectId, "2.0.0", t2)

        val release =
            transaction {
                Releases.selectAll().where { Releases.version eq "2.0.0" }.first()
            }

        assertEquals(2L, release[Releases.event_count])
        assertEquals(t2, release[Releases.last_seen])
    }

    @Test
    fun `upsertReleaseFromEvent ignores blank version`() {
        val (_, projectId) = seedOrgAndProject()
        service.upsertReleaseFromEvent(projectId, "", System.currentTimeMillis())

        val count = transaction { Releases.selectAll().count() }
        assertEquals(0L, count)
    }

    // --- getRelease ---

    @Test
    fun `getRelease returns release when it exists`() {
        val (_, projectId) = seedOrgAndProject()
        service.createRelease(projectId, "1.0.0", "main")

        val result = service.getRelease(projectId, "1.0.0")
        assertNotNull(result)
        assertEquals("1.0.0", result.version)
    }

    @Test
    fun `getRelease returns null for non-existent release`() {
        val (_, projectId) = seedOrgAndProject()
        assertNull(service.getRelease(projectId, "nonexistent"))
    }

    // --- listReleases ---

    @Test
    fun `listReleases returns all releases for project`() {
        val (_, projectId) = seedOrgAndProject()
        service.createRelease(projectId, "1.0.0", null)
        service.createRelease(projectId, "2.0.0", null)

        val results = service.listReleases(projectId)
        assertEquals(2, results.size)
    }

    @Test
    fun `listReleases returns empty for project with no releases`() {
        val (_, projectId) = seedOrgAndProject()
        assertTrue(service.listReleases(projectId).isEmpty())
    }

    @Test
    fun `listReleases returns empty for non-existent project`() {
        assertTrue(service.listReleases(99999L).isEmpty())
    }

    // --- hasProjectAccess ---

    @Test
    fun `hasProjectAccess returns true for member`() {
        val (orgId, projectId) = seedOrgAndProject()
        val userId = seedUser(orgId)

        assertTrue(service.hasProjectAccess(userId, projectId))
    }

    @Test
    fun `hasProjectAccess returns false for non-member`() {
        val (_, projectId) = seedOrgAndProject()
        val nonMemberId =
            transaction {
                Users.insert {
                    it[email] = "outsider@test.com"
                    it[password_hash] = "hashed"
                    it[email_verified] = true
                } get Users.id
            }

        assertFalse(service.hasProjectAccess(nonMemberId, projectId))
    }

    // --- getProjectBySlug ---

    @Test
    fun `getProjectBySlug returns project id`() {
        val (_, projectId) = seedOrgAndProject("my-org", "my-project")
        assertEquals(projectId, service.getProjectBySlug("my-org", "my-project"))
    }

    @Test
    fun `getProjectBySlug returns null for wrong org`() {
        seedOrgAndProject("my-org", "my-project")
        assertNull(service.getProjectBySlug("wrong-org", "my-project"))
    }

    // --- getOrganizationIdBySlug ---

    @Test
    fun `getOrganizationIdBySlug returns org id`() {
        val (orgId, _) = seedOrgAndProject("slug-org", "proj")
        assertEquals(orgId, service.getOrganizationIdBySlug("slug-org"))
    }

    @Test
    fun `getOrganizationIdBySlug returns null for missing slug`() {
        assertNull(service.getOrganizationIdBySlug("nonexistent"))
    }

    // --- hasOrgAccess ---

    @Test
    fun `hasOrgAccess returns true for member`() {
        val (orgId, _) = seedOrgAndProject("access-org", "proj")
        val userId = seedUser(orgId)

        assertTrue(service.hasOrgAccess(userId, "access-org"))
    }

    @Test
    fun `hasOrgAccess returns false for non-member`() {
        seedOrgAndProject("access-org", "proj")
        val outsider =
            transaction {
                Users.insert {
                    it[email] = "outsider2@test.com"
                    it[password_hash] = "h"
                    it[email_verified] = true
                } get Users.id
            }

        assertFalse(service.hasOrgAccess(outsider, "access-org"))
    }

    // --- findMissingChunks ---

    @Test
    fun `findMissingChunks returns all when none exist`() {
        val missing = service.findMissingChunks(setOf("aaa", "bbb", "ccc"))
        assertEquals(3, missing.size)
    }

    @Test
    fun `findMissingChunks returns empty set when all exist`() {
        transaction {
            FileBlobs.insert {
                it[checksum] = "abc123"
                it[size] = 100
                it[storage_path] = "/tmp/abc123"
                it[created_at] = System.currentTimeMillis()
            }
        }

        val missing = service.findMissingChunks(setOf("abc123"))
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `findMissingChunks returns empty for empty input`() {
        assertTrue(service.findMissingChunks(emptySet()).isEmpty())
    }

    // --- getAssembleStatus ---

    @Test
    fun `getAssembleStatus returns null when not started`() {
        val (orgId, _) = seedOrgAndProject()
        assertNull(service.getAssembleStatus(orgId, "deadbeef"))
    }

    @Test
    fun `getAssembleStatus returns state when bundle exists`() {
        val (orgId, _) = seedOrgAndProject()
        transaction {
            ArtifactBundles.insert {
                it[organization_id] = orgId
                it[checksum] = "deadbeef"
                it[state] = "ok"
                it[detail] = null
                it[created_at] = System.currentTimeMillis()
            }
        }

        val result = service.getAssembleStatus(orgId, "deadbeef")
        assertNotNull(result)
        assertEquals("ok", result.first)
        assertNull(result.second)
    }
}
