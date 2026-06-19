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

private const val DEFAULT_LOG_LEVEL = "info"
private const val DATADOG_AGENT_FORMAT = "datadog_agent"
private const val TAG_CATEGORY = "category"
private const val TAG_CODE_FILEPATH = "code.filepath"
private const val TAG_CODE_FUNCTION = "code.function"
private const val TAG_DD_AGENT_COMPONENT = "datadog.agent.component"
private const val TAG_DD_AGENT_CALLER = "datadog.agent.caller"
private const val TAG_LOG_FORMAT = "log.format"
private const val AGENT_LINE_COMPONENT_GROUP = 1
private const val AGENT_LINE_LEVEL_GROUP = 2
private const val AGENT_LINE_CALLER_GROUP = 3
private const val AGENT_LINE_MESSAGE_GROUP = 4
private const val AGENT_CALLER_FILEPATH_GROUP = 1
private const val AGENT_CALLER_FUNCTION_GROUP = 2

private val agentPipeLineRegex = Regex(
    "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} UTC \\| " +
        "([A-Z][A-Z0-9_-]*) \\| ([A-Z]+) \\| (?:\\(([^)]+)\\) \\| )?(.*)$"
)
private val agentCallerRegex = Regex("^(.+?)(?::\\d+)?(?: in (.+))?$")

private val knownLevels = mapOf(
    "trace" to "trace",
    "debug" to "debug",
    "info" to "info",
    "informational" to "info",
    "notice" to "info",
    "warn" to "warn",
    "warning" to "warn",
    "error" to "error",
    "err" to "error",
    "fatal" to "fatal",
    "critical" to "fatal",
    "crit" to "fatal",
    "panic" to "fatal",
    "alert" to "fatal",
    "emergency" to "fatal",
    "emerg" to "fatal"
)

private val levelRank = mapOf(
    "trace" to 0,
    "debug" to 1,
    "info" to 2,
    "warn" to 3,
    "error" to 4,
    "fatal" to 5
)

data class LogLineClassification(
    val level: String? = null,
    val message: String,
    val body: String? = null,
    val tags: Map<String, String> = emptyMap()
)

private data class ParsedAgentLogLine(
    val component: String,
    val level: String,
    val caller: String,
    val message: String
)

object LogLineClassifier {

    fun classify(message: String): LogLineClassification {
        val agentLine = parseAgentPipeLine(message) ?: return LogLineClassification(message = message)
        return LogLineClassification(
            level = agentLine.level,
            message = agentLine.message.ifBlank { message },
            body = message,
            tags = agentLineTags(agentLine)
        )
    }

    fun normalizeLevel(level: String?): String? {
        return knownLevels[level?.trim()?.lowercase()]
    }

    fun resolveLevel(
        envelopeLevel: String,
        classifiedLevel: String?
    ): String {
        if (classifiedLevel == null) return envelopeLevel
        if (envelopeLevel == DEFAULT_LOG_LEVEL) return classifiedLevel
        return if (levelRank.getValue(classifiedLevel) > levelRank.getValue(envelopeLevel)) {
            classifiedLevel
        } else {
            envelopeLevel
        }
    }

    private fun parseAgentPipeLine(message: String): ParsedAgentLogLine? {
        val match = agentPipeLineRegex.matchEntire(message.trim()) ?: return null
        val component = match.groupValues[AGENT_LINE_COMPONENT_GROUP].lowercase()
        val level = normalizeLevel(match.groupValues[AGENT_LINE_LEVEL_GROUP]) ?: return null
        val caller = match.groupValues[AGENT_LINE_CALLER_GROUP]
        val cleanMessage = match.groupValues[AGENT_LINE_MESSAGE_GROUP]
        return ParsedAgentLogLine(
            component = component,
            level = level,
            caller = caller,
            message = cleanMessage
        )
    }

    private fun agentLineTags(agentLine: ParsedAgentLogLine): Map<String, String> {
        val tags = mutableMapOf(
            TAG_CATEGORY to agentLine.component,
            TAG_DD_AGENT_COMPONENT to agentLine.component,
            TAG_LOG_FORMAT to DATADOG_AGENT_FORMAT
        )
        if (agentLine.caller.isNotBlank()) {
            tags[TAG_DD_AGENT_CALLER] = agentLine.caller
            addCallerTags(tags, agentLine.caller)
        }
        return tags
    }

    private fun addCallerTags(
        tags: MutableMap<String, String>,
        caller: String
    ) {
        val match = agentCallerRegex.matchEntire(caller) ?: return
        val filepath = match.groupValues[AGENT_CALLER_FILEPATH_GROUP]
        val function = match.groupValues[AGENT_CALLER_FUNCTION_GROUP]
        if (filepath.isNotBlank()) {
            tags[TAG_CODE_FILEPATH] = filepath
        }
        if (function.isNotBlank()) {
            tags[TAG_CODE_FUNCTION] = function
        }
    }
}
