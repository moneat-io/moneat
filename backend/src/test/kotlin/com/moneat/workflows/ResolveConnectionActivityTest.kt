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

package com.moneat.workflows

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResolveConnectionActivityTest {

    @Test
    fun `returns null when the enterprise vault is absent`() {
        runBlocking {
            val subject = ResolveConnectionActivity { null }

            val result =
                subject.resolve(
                    organizationId = 7,
                    reference = WorkflowConnectionReference.Connection(connectionId = 42),
                    runScope = mapOf("env" to "prod")
                )

            assertNull(result)
        }
    }

    @Test
    fun `delegates resolution to the licensed vault`() {
        runBlocking {
            val expected =
                WorkflowResolvedConnection(
                    connectionId = 42,
                    resourceId = "2fbdf82a-fd86-4f91-91f4-880c3757a103",
                    type = "webhook",
                    secret = "resolved-secret"
                )
            val vault = RecordingWorkflowConnectionVault(expected)
            val subject = ResolveConnectionActivity { vault }
            val reference = WorkflowConnectionReference.Group(groupId = 5)
            val runScope = mapOf("env" to "prod")

            val result = subject.resolve(organizationId = 7, reference = reference, runScope = runScope)

            assertEquals(expected, result)
            assertEquals(Triple(7, reference, runScope), vault.lastResolve)
        }
    }
}

private class RecordingWorkflowConnectionVault(
    private val result: WorkflowResolvedConnection
) : WorkflowConnectionVault {
    var lastResolve: Triple<Int, WorkflowConnectionReference, Map<String, String>>? = null

    override suspend fun listConnections(organizationId: Int): List<WorkflowConnectionSummary> =
        emptyList()

    override suspend fun getConnection(
        organizationId: Int,
        connectionId: Int
    ): WorkflowConnectionSummary? = null

    override suspend fun resolveConnectionId(
        organizationId: Int,
        connectionResourceId: String
    ): Int? = null

    override suspend fun createConnection(
        organizationId: Int,
        type: String,
        name: String,
        identifierTags: Map<String, String>,
        secret: String,
        createdBy: Int?
    ): WorkflowConnectionSummary {
        throw UnsupportedOperationException("Not used by ResolveConnectionActivityTest")
    }

    override suspend fun rotateConnection(
        organizationId: Int,
        connectionId: Int,
        secret: String
    ): WorkflowConnectionSummary? = null

    override suspend fun deleteConnection(organizationId: Int, connectionId: Int): Boolean = false

    override suspend fun listGroups(organizationId: Int): List<WorkflowConnectionGroupSummary> =
        emptyList()

    override suspend fun resolveGroupId(
        organizationId: Int,
        groupResourceId: String
    ): Int? = null

    override suspend fun createGroup(
        organizationId: Int,
        name: String,
        connectionType: String,
        memberConnectionIds: List<Int>,
        selectionStrategy: String,
        createdBy: Int?
    ): WorkflowConnectionGroupSummary {
        throw UnsupportedOperationException("Not used by ResolveConnectionActivityTest")
    }

    override suspend fun deleteGroup(organizationId: Int, groupId: Int): Boolean = false

    override suspend fun resolveSecret(
        organizationId: Int,
        reference: WorkflowConnectionReference,
        runScope: Map<String, String>
    ): WorkflowResolvedConnection? {
        lastResolve = Triple(organizationId, reference, runScope)
        return result
    }
}
