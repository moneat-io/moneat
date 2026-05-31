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

package com.moneat.security.detection

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val coverageJson = Json { ignoreUnknownKeys = true }

class MitreCoverageService {

    fun coverage(organizationId: Int): DetectionCoverageResponse = transaction {
        val rules = DetectionRules
            .selectAll()
            .where { (DetectionRules.organizationId eq organizationId) and (DetectionRules.enabled eq true) }
            .orderBy(DetectionRules.id, SortOrder.ASC)
            .map { it.toCoverageRule() }
        buildCoverage(rules)
    }

    internal fun buildCoverage(rules: List<CoverageRule>): DetectionCoverageResponse {
        val byTechnique = mutableMapOf<String, MutableList<CoverageRule>>()
        val techniqueTactics = mutableMapOf<String, MutableSet<String>>()
        rules.forEach { rule ->
            val tags = rule.tags.map { it.trim() }
            val tacticTags = tags.mapNotNull { normalizeTactic(it) }.toSet()
            tags.mapNotNull { normalizeTechnique(it) }.forEach { technique ->
                byTechnique.getOrPut(technique) { mutableListOf() }.add(rule)
                techniqueTactics.getOrPut(technique) { mutableSetOf() }.addAll(
                    tacticTags.ifEmpty { TECHNIQUE_TACTICS[technique].orEmpty() }
                )
            }
        }
        val techniques = byTechnique.entries
            .sortedBy { it.key }
            .map { (technique, coveredRules) ->
                MitreTechniqueCoverageResponse(
                    techniqueId = technique,
                    tactics = techniqueTactics[technique].orEmpty().sorted(),
                    ruleCount = coveredRules.distinctBy { it.id }.size,
                    rules = coveredRules.distinctBy { it.id }.map { it.toResponse() },
                )
            }
        val tactics = techniques
            .flatMap { technique -> technique.tactics.map { tactic -> tactic to technique } }
            .groupBy({ it.first }, { it.second })
            .map { (tactic, tacticTechniques) ->
                MitreTacticCoverageResponse(
                    tactic = tactic,
                    techniqueCount = tacticTechniques.distinctBy { it.techniqueId }.size,
                    ruleCount = tacticTechniques.flatMap { it.rules }.distinctBy { it.id }.size,
                )
            }
            .sortedBy { it.tactic }
        return DetectionCoverageResponse(
            enabledRuleCount = rules.size,
            tactics = tactics,
            techniques = techniques,
        )
    }

    private fun ResultRow.toCoverageRule(): CoverageRule =
        CoverageRule(
            id = this[DetectionRules.id].value,
            name = this[DetectionRules.name],
            enabled = this[DetectionRules.enabled],
            tags = decodeStringList(this[DetectionRules.tags]),
        )

    private fun decodeStringList(raw: String): List<String> =
        runCatching { coverageJson.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
}

data class CoverageRule(
    val id: Int,
    val name: String,
    val enabled: Boolean,
    val tags: List<String>,
) {
    fun toResponse(): MitreCoveredRuleResponse =
        MitreCoveredRuleResponse(id = id, name = name, enabled = enabled)
}

private val TECHNIQUE_REGEX = Regex("""^(?:mitre|attack)(?::technique)?:([Tt]\d{4}(?:\.\d{3})?)$""")
private val TACTIC_ID_REGEX = Regex("""^(?:mitre|attack):([Tt][Aa]\d{4})$""")
private val TACTIC_NAME_REGEX = Regex("""^(?:mitre|attack):(?:tactic:)?([a-z][a-z0-9_-]+)$""")

private fun normalizeTechnique(tag: String): String? =
    TECHNIQUE_REGEX.find(tag)?.groupValues?.get(1)?.uppercase()

private fun normalizeTactic(tag: String): String? {
    val tacticId = TACTIC_ID_REGEX.find(tag)?.groupValues?.get(1)?.uppercase()
    if (tacticId != null) return TACTIC_IDS[tacticId]
    val tactic = TACTIC_NAME_REGEX.find(tag)?.groupValues?.get(1)
        ?.lowercase()
        ?.replace('_', '-')
    return tactic?.takeIf { it in TACTIC_NAMES }
}

private val TACTIC_NAMES = setOf(
    "reconnaissance",
    "resource-development",
    "initial-access",
    "execution",
    "persistence",
    "privilege-escalation",
    "defense-evasion",
    "credential-access",
    "discovery",
    "lateral-movement",
    "collection",
    "command-and-control",
    "exfiltration",
    "impact",
)

private val TACTIC_IDS = mapOf(
    "TA0001" to "initial-access",
    "TA0002" to "execution",
    "TA0003" to "persistence",
    "TA0004" to "privilege-escalation",
    "TA0005" to "defense-evasion",
    "TA0006" to "credential-access",
    "TA0007" to "discovery",
    "TA0008" to "lateral-movement",
    "TA0009" to "collection",
    "TA0010" to "exfiltration",
    "TA0011" to "command-and-control",
    "TA0040" to "impact",
    "TA0042" to "resource-development",
    "TA0043" to "reconnaissance",
)

private val TECHNIQUE_TACTICS = mapOf(
    "T1059" to setOf("execution"),
    "T1071" to setOf("command-and-control"),
    "T1078" to setOf("defense-evasion", "initial-access", "persistence", "privilege-escalation"),
    "T1110" to setOf("credential-access"),
    "T1548" to setOf("defense-evasion", "privilege-escalation"),
    "T1552" to setOf("credential-access"),
)
