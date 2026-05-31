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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Moneat's OSS **starter pack** of detection rules — installable templates that give a fresh tenant a
 * working baseline without authoring anything. Templates are code resources (no table); installing one
 * creates a normal, **disabled** detection rule through [DetectionRuleService.create], so every template
 * is compiled and sandboxed by [RuleQueryCompiler] exactly like a hand-authored or imported rule.
 *
 * Each template's [request] is conservative on purpose (tight windows, meaningful thresholds) to keep
 * day-one noise low; an operator reviews the preview, then enables it.
 */
@Serializable
data class DetectionTemplateSummary(
    val id: String,
    val name: String,
    val description: String,
    val severity: String,
    val type: String,
    val tags: List<String> = emptyList(),
)

@Serializable
data class DetectionTemplateListResponse(
    val templates: List<DetectionTemplateSummary>,
    @SerialName("total_count") val totalCount: Int,
)

/** A starter template: a stable [id] plus the request the install path feeds to the compiler. */
data class StarterTemplate(
    val id: String,
    val request: CreateDetectionRuleRequest,
) {
    fun summary(): DetectionTemplateSummary = DetectionTemplateSummary(
        id = id,
        name = request.name,
        description = request.description,
        severity = request.severity,
        type = request.type,
        tags = request.tags,
    )
}

/**
 * The bundled starter rules. Filters are written in the
 * [LogQueryParser][com.moneat.logs.services.LogQueryParser] language and group only on whitelisted
 * dimensions, so each one compiles cleanly through [RuleQueryCompiler]; a test asserts exactly that.
 */
object DetectionStarterPack {

    private const val WINDOW_5M = 300
    private const val WINDOW_10M = 600
    private const val WINDOW_15M = 900

    /** All templates, in display order. */
    val templates: List<StarterTemplate> = listOf(
        failedAuthBruteForce(),
        newSourceLogin(),
        privilegeEscalationSpike(),
        suspiciousProcessExec(),
        sensitiveFileAccess(),
        outboundToSuspicious(),
    )

    private val byId: Map<String, StarterTemplate> = templates.associateBy { it.id }

    fun list(): DetectionTemplateListResponse =
        DetectionTemplateListResponse(
            templates = templates.map { it.summary() },
            totalCount = templates.size,
        )

    fun find(id: String): StarterTemplate? = byId[id]

    /** The starter requests to seed (disabled) for a demo/bootstrap org. */
    fun seedRequests(): List<CreateDetectionRuleRequest> = templates.map { it.request }

    // ── Templates ─────────────────────────────────────────────────────────

    private fun failedAuthBruteForce() = StarterTemplate(
        id = "failed-auth-brute-force",
        request = CreateDetectionRuleRequest(
            name = "Failed authentication brute force",
            description = "Repeated failed logins from the same host within a short window, indicating " +
                "password guessing or credential stuffing.",
            filter = "(*:\"failed password\" OR *:\"authentication failure\" OR *:\"invalid user\")",
            groupBy = listOf("host"),
            windowSeconds = WINDOW_5M,
            type = DetectionRuleType.THRESHOLD.wire,
            thresholdCount = 8,
            severity = "high",
            signalTitle = "Failed-auth brute force on {host}",
            signalMessage = "{host} saw repeated failed authentications in the last 5 minutes.",
            enabled = false,
            tags = listOf("starter-pack", "mitre:T1110", "category:authentication"),
        ),
    )

    private fun newSourceLogin() = StarterTemplate(
        id = "new-source-login",
        request = CreateDetectionRuleRequest(
            name = "Login from a new source",
            description = "A successful login from a source host/user combination not seen before " +
                "(after a learning window), surfacing new-geo / new-machine access.",
            filter = "(*:\"accepted password\" OR *:\"session opened\" OR *:\"login successful\")",
            groupBy = listOf("host", "tags['user']"),
            windowSeconds = WINDOW_15M,
            type = DetectionRuleType.NEW_VALUE.wire,
            thresholdCount = null,
            severity = "medium",
            signalTitle = "New login source {host}",
            signalMessage = "First-seen successful login for {host} / {tags['user']}.",
            enabled = false,
            tags = listOf("starter-pack", "mitre:T1078", "category:authentication"),
        ),
    )

    private fun privilegeEscalationSpike() = StarterTemplate(
        id = "privilege-escalation-spike",
        request = CreateDetectionRuleRequest(
            name = "Privilege escalation spike",
            description = "A burst of sudo / su / privilege-escalation activity on a host, which can " +
                "indicate an attacker elevating access.",
            filter = "(*:sudo OR *:\"su:\" OR *:\"COMMAND=\" OR *:pkexec) AND -level:debug",
            groupBy = listOf("host"),
            windowSeconds = WINDOW_10M,
            type = DetectionRuleType.THRESHOLD.wire,
            thresholdCount = 15,
            severity = "high",
            signalTitle = "Privilege-escalation spike on {host}",
            signalMessage = "{host} had an unusual volume of privilege-escalation events.",
            enabled = false,
            tags = listOf("starter-pack", "mitre:T1548", "category:privilege_escalation"),
        ),
    )

    private fun suspiciousProcessExec() = StarterTemplate(
        id = "suspicious-process-exec",
        request = CreateDetectionRuleRequest(
            name = "Suspicious process execution",
            description = "Execution of tools commonly abused for download-and-execute or reverse " +
                "shells (nc, ncat, curl|bash, python -c, base64 -d).",
            filter = "(*:\"nc -e\" OR *:ncat OR *:\"curl | bash\" OR *:\"python -c\" OR *:\"base64 -d\" " +
                "OR *:\"/dev/tcp/\")",
            groupBy = listOf("host", "service"),
            windowSeconds = WINDOW_10M,
            type = DetectionRuleType.THRESHOLD.wire,
            thresholdCount = 1,
            severity = "critical",
            signalTitle = "Suspicious process on {host}",
            signalMessage = "{host} executed a process matching a known download/exec pattern.",
            enabled = false,
            tags = listOf("starter-pack", "mitre:T1059", "category:execution"),
        ),
    )

    private fun sensitiveFileAccess() = StarterTemplate(
        id = "sensitive-file-access",
        request = CreateDetectionRuleRequest(
            name = "Sensitive / secret file access",
            description = "Reads of credential and secret material (/etc/shadow, SSH keys, cloud " +
                "credential files, .env), which can indicate credential theft.",
            filter = "(*:\"/etc/shadow\" OR *:\"id_rsa\" OR *:\"authorized_keys\" OR *:\".aws/credentials\" " +
                "OR *:\".env\" OR *:\"private key\")",
            groupBy = listOf("host"),
            windowSeconds = WINDOW_15M,
            type = DetectionRuleType.THRESHOLD.wire,
            thresholdCount = 1,
            severity = "high",
            signalTitle = "Sensitive file access on {host}",
            signalMessage = "{host} accessed credential or secret material.",
            enabled = false,
            tags = listOf("starter-pack", "mitre:T1552", "category:credential_access"),
        ),
    )

    private fun outboundToSuspicious() = StarterTemplate(
        id = "outbound-to-suspicious",
        request = CreateDetectionRuleRequest(
            name = "Outbound to suspicious destination",
            description = "Connections to ports / patterns associated with C2 or data exfiltration " +
                "(raw IP egress, uncommon high ports, known tooling user-agents). Pairs with threat " +
                "enrichment when available.",
            filter = "(*:\"outbound connection\" OR *:\"connect to\" OR *:\"egress\") AND " +
                "(*:\":4444\" OR *:\":1337\" OR *:\":9001\" OR *:\"user-agent: curl\")",
            groupBy = listOf("host", "service"),
            windowSeconds = WINDOW_10M,
            type = DetectionRuleType.THRESHOLD.wire,
            thresholdCount = 3,
            severity = "medium",
            signalTitle = "Suspicious outbound from {host}",
            signalMessage = "{host} made repeated connections to a suspicious destination pattern.",
            enabled = false,
            tags = listOf("starter-pack", "mitre:T1071", "category:command_and_control"),
        ),
    )
}
