// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EscalationPathValidatorTest {
    @Test
    fun `normalizes implicit start and accepts bounded delivery modes`() {
        val path = EscalationPath(
            nodes = listOf(
                EscalationPathNode(
                    id = "page-primary",
                    delivery = EscalationPathNode.DELIVERY_PARALLEL,
                    targets = listOf(target()),
                    nextNodeId = "page-secondary",
                ),
                EscalationPathNode(
                    id = "page-secondary",
                    delivery = EscalationPathNode.DELIVERY_ROUND_ROBIN,
                    targets = listOf(target("ON_CALL_SCHEDULE")),
                    waitMinutes = 5,
                    onAcknowledgement = EscalationPathNode.ACK_NEXT,
                ),
            ),
        )

        assertEquals("page-primary", EscalationPathValidator.validate(path).startNodeId)
    }

    @Test
    fun `accepts bounded priority and working hours branches`() {
        val path = EscalationPath(
            startNodeId = "branch",
            nodes = listOf(
                EscalationPathNode(
                    id = "branch",
                    kind = EscalationPathNode.NODE_KIND_BRANCH,
                    branches = listOf(
                        EscalationPathBranch("PRIORITY", "IN", "P0", "urgent"),
                        EscalationPathBranch("WORKING_HOURS", "EQ", "OUT", "after-hours"),
                    ),
                ),
                EscalationPathNode("urgent", targets = listOf(target()), nextNodeId = "after-hours"),
                EscalationPathNode("after-hours", targets = listOf(target("TEAM")), stop = true),
            ),
        )

        EscalationPathValidator.validate(path)
    }

    @Test
    fun `rejects cycles, empty levels, and unreachable nodes`() {
        assertFailsWith<EscalationPathValidationException> {
            EscalationPathValidator.validate(
                EscalationPath(
                    nodes = listOf(
                        EscalationPathNode("a", targets = listOf(target()), nextNodeId = "b"),
                        EscalationPathNode("b", targets = listOf(target()), nextNodeId = "a"),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            EscalationPathValidator.validate(EscalationPath(nodes = listOf(EscalationPathNode("empty"))))
        }
        assertFailsWith<IllegalArgumentException> {
            EscalationPathValidator.validate(
                EscalationPath(
                    nodes = listOf(
                        EscalationPathNode("start", targets = listOf(target()), stop = true),
                        EscalationPathNode("unused", targets = listOf(target()), stop = true),
                    ),
                ),
            )
        }
    }

    private fun target(type: String = EscalationPathTargetType.USER) =
        EscalationPathTarget(type, "00000000-0000-0000-0000-000000000001")
}
