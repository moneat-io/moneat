// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.workflows.services

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConnectionGroupResolverTest {

    @Test
    fun `picks the first member whose tags all match the run scope`() {
        val result = ConnectionGroupResolver.firstMatch(
            orderedMemberIds = listOf(10, 20),
            identifierTagsById = mapOf(
                10 to mapOf("env" to "prod"),
                20 to mapOf("env" to "staging")
            ),
            runScope = mapOf("env" to "staging", "region" to "us-east-1")
        )
        assertEquals(20, result)
    }

    @Test
    fun `honors declared member order on multiple matches`() {
        val result = ConnectionGroupResolver.firstMatch(
            orderedMemberIds = listOf(20, 10),
            identifierTagsById = mapOf(
                10 to emptyMap(),
                20 to emptyMap()
            ),
            runScope = mapOf("env" to "prod")
        )
        assertEquals(20, result)
    }

    @Test
    fun `requires every tag to match`() {
        val result = ConnectionGroupResolver.firstMatch(
            orderedMemberIds = listOf(10),
            identifierTagsById = mapOf(10 to mapOf("env" to "prod", "team" to "payments")),
            runScope = mapOf("env" to "prod", "team" to "search")
        )
        assertNull(result)
    }

    @Test
    fun `returns null when no member matches`() {
        val result = ConnectionGroupResolver.firstMatch(
            orderedMemberIds = listOf(10, 20),
            identifierTagsById = mapOf(
                10 to mapOf("env" to "prod"),
                20 to mapOf("env" to "staging")
            ),
            runScope = mapOf("env" to "dev")
        )
        assertNull(result)
    }

    @Test
    fun `an untagged member matches any scope`() {
        val result = ConnectionGroupResolver.firstMatch(
            orderedMemberIds = listOf(10),
            identifierTagsById = mapOf(10 to emptyMap()),
            runScope = emptyMap()
        )
        assertEquals(10, result)
    }
}
