// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.workflows.services

import com.moneat.enterprise.sso.support.EnterpriseTestDatabaseHelper
import com.moneat.enterprise.workflows.crypto.ConnectionCredentialCipher
import com.moneat.enterprise.workflows.models.WorkflowConnectionGroups
import com.moneat.enterprise.workflows.models.WorkflowConnections
import com.moneat.workflows.WorkflowConnectionReference
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionVaultServiceTest {

    companion object {
        private var db: Database? = null
    }

    private val cipher =
        ConnectionCredentialCipher(
            activeKeyId = "v1",
            keksByKeyId = mapOf("v1" to ConnectionCredentialCipher.deriveKek("test-connection-kek-aaaaaaaaaaaa"))
        )
    private val service = ConnectionVaultService(cipher)

    @BeforeEach
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_ee_workflow_connections;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        EnterpriseTestDatabaseHelper.resetSchema(WorkflowConnections, WorkflowConnectionGroups)
    }

    @AfterEach
    fun clearDbRef() {
        TransactionManager.defaultDatabase = null
    }

    @Test
    fun `creates a connection and never exposes the secret in summaries`() {
        runBlocking {
            val created = service.createConnection(
                organizationId = 1,
                type = "slack",
                name = "prod-alerts",
                identifierTags = mapOf("env" to "prod"),
                secret = "xoxb-secret-123456",
                createdBy = 7
            )
            assertEquals("slack", created.type)
            assertEquals("3456", created.lastFour)

            val listed = service.listConnections(1)
            assertEquals(1, listed.size)
            assertEquals("prod-alerts", listed.first().name)
            assertEquals("3456", listed.first().lastFour)
            assertEquals(mapOf("env" to "prod"), listed.first().identifierTags)
        }
    }

    @Test
    fun `resolveSecret returns the plaintext for a direct connection reference`() {
        runBlocking {
            val created = service.createConnection(1, "pagerduty", "pd", emptyMap(), "pd-routing-key-abc", null)
            val resolved = service.resolveSecret(
                organizationId = 1,
                reference = WorkflowConnectionReference.Connection(created.id),
                runScope = emptyMap()
            )
            assertEquals("pd-routing-key-abc", resolved?.secret)
            assertEquals("pagerduty", resolved?.type)
        }
    }

    @Test
    fun `rotating a connection changes the secret and last four`() {
        runBlocking {
            val created = service.createConnection(1, "slack", "rotate-me", emptyMap(), "old-secret-0000", null)
            val rotated = service.rotateConnection(1, created.id, "new-secret-9999")
            assertEquals("9999", rotated?.lastFour)
            val resolved = service.resolveSecret(1, WorkflowConnectionReference.Connection(created.id), emptyMap())
            assertEquals("new-secret-9999", resolved?.secret)
        }
    }

    @Test
    fun `rejects a duplicate connection name in the same org`() {
        runBlocking {
            service.createConnection(1, "slack", "dupe", emptyMap(), "secret-1111", null)
            assertFailsWith<IllegalArgumentException> {
                service.createConnection(1, "slack", "dupe", emptyMap(), "secret-2222", null)
            }
        }
    }

    @Test
    fun `deletes a connection`() {
        runBlocking {
            val created = service.createConnection(1, "slack", "delete-me", emptyMap(), "secret-3333", null)
            assertTrue(service.deleteConnection(1, created.id))
            assertNull(service.getConnection(1, created.id))
        }
    }

    @Test
    fun `scopes connections to their organization`() {
        runBlocking {
            val created = service.createConnection(1, "slack", "org1", emptyMap(), "secret-4444", null)
            assertNull(service.getConnection(2, created.id))
            assertNull(service.resolveSecret(2, WorkflowConnectionReference.Connection(created.id), emptyMap()))
            assertTrue(service.listConnections(2).isEmpty())
        }
    }

    @Test
    fun `resolves a connection group by matching identifier tags against the run scope`() {
        runBlocking {
            val prod = service.createConnection(1, "pagerduty", "pd-prod", mapOf("env" to "prod"), "key-prod", null)
            val staging =
                service.createConnection(1, "pagerduty", "pd-staging", mapOf("env" to "staging"), "key-staging", null)
            val group = service.createGroup(
                organizationId = 1,
                name = "pd-by-env",
                connectionType = "pagerduty",
                memberConnectionIds = listOf(prod.id, staging.id),
                selectionStrategy = "first_match",
                createdBy = null
            )

            val resolvedStaging = service.resolveSecret(
                1,
                WorkflowConnectionReference.Group(group.id),
                runScope = mapOf("env" to "staging")
            )
            assertEquals("key-staging", resolvedStaging?.secret)

            val resolvedNone = service.resolveSecret(
                1,
                WorkflowConnectionReference.Group(group.id),
                runScope = mapOf("env" to "dev")
            )
            assertNull(resolvedNone)
        }
    }

    @Test
    fun `rejects unsupported connection group selection strategies`() {
        runBlocking {
            val created = service.createConnection(1, "webhook", "primary", emptyMap(), "secret-5555", null)
            assertFailsWith<IllegalArgumentException> {
                service.createGroup(
                    organizationId = 1,
                    name = "unsupported-strategy",
                    connectionType = "webhook",
                    memberConnectionIds = listOf(created.id),
                    selectionStrategy = "random",
                    createdBy = null
                )
            }
        }
    }
}
