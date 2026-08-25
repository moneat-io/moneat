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

package com.moneat.ingestion.queue

enum class IngestionPipeline(
    val id: String,
    val workerName: String,
) {
    EVENTS("events", "Event"),
    LOGS("logs", "Log"),
    LLM("llm", "LLM"),
    OTLP_TRACES("otlp-traces", "OTLP trace"),
    OTLP_METRICS("otlp-metrics", "OTLP metrics"),
    ANALYTICS("analytics", "Analytics"),
    DD_TRACES("dd-traces", "Trace"),
    DD_METRICS("dd-metrics", "DD metric"),
    DD_SKETCHES("dd-sketches", "DD sketch"),
    DD_EVENTS("dd-events", "DD event"),
    DD_SERVICE_CHECKS("dd-service-checks", "DD service check"),
    DD_INFRA("dd-infra", "DD infra"),
    DD_ORCHESTRATOR("dd-orchestrator", "Orchestrator"),
    DD_DBM("dd-dbm", "DBM"),
    DD_DEBUGGER("dd-debugger", "Debugger"),
    DD_MISC("dd-misc", "Misc"),
    DD_NDM("dd-ndm", "NDM"),
    DD_SECURITY("dd-security", "Security"),
    SLACK_INBOUND("slack-inbound", "Slack inbound"),
    SLACK_OUTBOUND("slack-outbound", "Slack outbound"),
    CONNECTOR_EVENTS("connector-events", "Connector event"),
    CONNECTOR_IMPORTS("connector-imports", "Connector import");

    val envKey: String = id.uppercase().replace('-', '_')
    val consumerGroup: String = "moneat:$id:workers"

    companion object {
        fun parse(raw: String): IngestionPipeline? =
            entries.firstOrNull { pipeline ->
                pipeline.id.equals(raw.trim(), ignoreCase = true) ||
                    pipeline.name.equals(raw.trim().replace('-', '_'), ignoreCase = true)
            }
    }
}
