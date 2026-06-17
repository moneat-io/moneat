// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.workflows.services

/**
 * Pure connection-group selection logic, factored out so it can be unit-tested
 * without a database. A connection matches when every one of its identifier tags is
 * present in the run scope with an equal value.
 */
internal object ConnectionGroupResolver {

    /**
     * Pick the first member (in declared order) whose identifier tags all match the
     * run scope. Returns null when no member matches.
     */
    fun firstMatch(
        orderedMemberIds: List<Int>,
        identifierTagsById: Map<Int, Map<String, String>>,
        runScope: Map<String, String>
    ): Int? =
        orderedMemberIds.firstOrNull { id ->
            val tags = identifierTagsById[id] ?: return@firstOrNull false
            tags.all { (key, value) -> runScope[key] == value }
        }
}
