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

package com.moneat.logs.services

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogEntryFilterEvaluatorTest {
    private val evaluator = LogEntryFilterEvaluator()
    private val entry = mapOf(
        "message" to "Checkout timeout on shard a",
        "body" to "request failed with status 503",
        "service" to "api",
        "environment" to "prod",
        "host" to "host-1",
        "container_name" to "worker-api",
        "team" to "payments",
        "duration" to "125",
        "status_code" to "503"
    )

    @Test
    fun `matches treats blank and parsed queries as expected`() {
        assertTrue(evaluator.matches("", entry))
        assertTrue(evaluator.matches("service:api timeout", entry))
        assertFalse(evaluator.matches("service:worker timeout", entry))
    }

    @Test
    fun `evaluate covers text field existence and boolean nodes`() {
        assertTrue(evaluator.evaluate(LogQueryParser.QueryNode.FullTextNode("timeout"), entry))
        assertFalse(evaluator.evaluate(LogQueryParser.QueryNode.FullTextNode("missing"), entry))
        assertTrue(evaluator.evaluate(LogQueryParser.QueryNode.TermNode("checkout"), entry))
        assertFalse(evaluator.evaluate(LogQueryParser.QueryNode.TermNode("absent"), entry))

        assertTrue(evaluator.evaluate(LogQueryParser.QueryNode.ExistsNode("host"), entry))
        assertFalse(evaluator.evaluate(LogQueryParser.QueryNode.ExistsNode("missing"), entry))
        assertTrue(evaluator.evaluate(LogQueryParser.QueryNode.TagExistsNode("team"), entry))
        assertFalse(evaluator.evaluate(LogQueryParser.QueryNode.TagExistsNode("missing"), entry))

        val serviceNode = LogQueryParser.QueryNode.FieldNode("service", "api")
        val prodNode = LogQueryParser.QueryNode.FieldNode("environment", "prod")
        val workerNode = LogQueryParser.QueryNode.FieldNode("service", "worker")
        assertTrue(evaluator.evaluate(LogQueryParser.QueryNode.AndNode(serviceNode, prodNode), entry))
        assertFalse(evaluator.evaluate(LogQueryParser.QueryNode.AndNode(serviceNode, workerNode), entry))
        assertTrue(evaluator.evaluate(LogQueryParser.QueryNode.OrNode(workerNode, prodNode), entry))
        assertTrue(evaluator.evaluate(LogQueryParser.QueryNode.NotNode(workerNode), entry))
    }

    @Test
    fun `evaluate covers field wildcard nodes`() {
        assertTrue(evaluator.evaluate(LogQueryParser.QueryNode.FieldNode("service", "API"), entry))
        assertFalse(evaluator.evaluate(LogQueryParser.QueryNode.FieldNode("missing", "value"), entry))
        assertTrue(evaluator.evaluate(LogQueryParser.QueryNode.FieldNode("service", "api", true), entry))
        assertFalse(evaluator.evaluate(LogQueryParser.QueryNode.FieldNode("service", "a?i", true), entry))
        assertFalse(evaluator.evaluate(LogQueryParser.QueryNode.FieldNode("service", "web-*", true), entry))
    }

    @Test
    fun `evaluate covers range and comparison nodes`() {
        assertTrue(evaluator.evaluate(LogQueryParser.QueryNode.RangeNode("duration", "100", "200"), entry))
        assertFalse(evaluator.evaluate(LogQueryParser.QueryNode.RangeNode("duration", "200", "300"), entry))
        assertFalse(evaluator.evaluate(LogQueryParser.QueryNode.RangeNode("missing", "100", "200"), entry))
        assertFalse(evaluator.evaluate(LogQueryParser.QueryNode.RangeNode("duration", "low", "200"), entry))
        assertFalse(evaluator.evaluate(LogQueryParser.QueryNode.RangeNode("duration", "100", "high"), entry))

        assertTrue(evaluator.evaluate(LogQueryParser.QueryNode.ComparisonNode("status_code", ">", "500"), entry))
        assertTrue(evaluator.evaluate(LogQueryParser.QueryNode.ComparisonNode("status_code", ">=", "503"), entry))
        assertTrue(evaluator.evaluate(LogQueryParser.QueryNode.ComparisonNode("duration", "<", "200"), entry))
        assertTrue(evaluator.evaluate(LogQueryParser.QueryNode.ComparisonNode("duration", "<=", "125"), entry))
        assertFalse(evaluator.evaluate(LogQueryParser.QueryNode.ComparisonNode("duration", "=", "125"), entry))
        assertFalse(evaluator.evaluate(LogQueryParser.QueryNode.ComparisonNode("duration", ">", "slow"), entry))
        assertFalse(evaluator.evaluate(LogQueryParser.QueryNode.ComparisonNode("missing", ">", "10"), entry))
    }
}
