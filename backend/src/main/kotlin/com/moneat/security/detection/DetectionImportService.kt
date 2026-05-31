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

/**
 * Imports detection rules from **Sigma** YAML and installs **starter-pack** templates. Both paths build
 * a [CreateDetectionRuleRequest] and hand it to [DetectionRuleService.create], which runs it through
 * [RuleQueryCompiler] before persisting — so an imported or templated rule is validated and sandboxed
 * exactly like a hand-authored one (org injection, column whitelist, caps, no raw SQL) and is never a
 * bypass. A rule that does not compile is rejected, not stored. All created rules are `enabled = false`.
 */
class DetectionImportService(
    private val ruleService: DetectionRuleService = DetectionRuleService(),
    private val importer: SigmaImporter = SigmaImporter(),
) {

    /**
     * Map and create each Sigma document for [organizationId]. Each document is independent: one that
     * cannot be mapped (unsupported construct) or compiled (whitelist/window failure) yields a per-item
     * error and never aborts the others. Errors carry only the DSL/import message, never ClickHouse text.
     */
    fun importSigma(organizationId: Int, documents: List<String>): SigmaImportResponse {
        val results = documents.mapIndexed { index, document ->
            importOne(organizationId, index, document)
        }
        return SigmaImportResponse(
            createdCount = results.count { it.ruleId != null },
            errorCount = results.count { it.error != null },
            results = results,
        )
    }

    private fun importOne(organizationId: Int, index: Int, document: String): SigmaImportItemResult {
        val mapped = try {
            importer.toRuleRequest(document)
        } catch (e: SigmaImportException) {
            return SigmaImportItemResult(index = index, error = e.message ?: "Could not map Sigma rule")
        } catch (e: StackOverflowError) {
            // Defence in depth: the compiler caps nesting and rejects oversized conditions before
            // recursing, but a malformed/over-nested document must still degrade to a per-item error
            // rather than an uncaught Error that fails the whole batch (500 / fork crash).
            return SigmaImportItemResult(index = index, error = "Could not map Sigma rule: condition too complex")
        }
        return try {
            // forceDisabled = true so an imported rule can never arrive pre-enabled regardless of mapping.
            val created = ruleService.create(organizationId, mapped.request.copy(enabled = false))
            SigmaImportItemResult(index = index, title = mapped.title, ruleId = created.id)
        } catch (e: DetectionCompileException) {
            SigmaImportItemResult(index = index, title = mapped.title, error = e.message ?: "Rule did not compile")
        }
    }

    /** The installable starter-pack templates (metadata only; no rule is created). */
    fun listTemplates(): DetectionTemplateListResponse = DetectionStarterPack.list()

    /**
     * Install one starter template as a new **disabled** rule for [organizationId], via the same
     * compiler-gated create path. Returns the created rule, or `null` if [templateId] is unknown.
     *
     * @throws DetectionCompileException if the (Moneat-authored) template somehow fails to compile — a
     *   bug we want surfaced loudly rather than a silently-skipped install.
     */
    fun installTemplate(organizationId: Int, templateId: String): DetectionRuleResponse? {
        val template = DetectionStarterPack.find(templateId) ?: return null
        return ruleService.create(organizationId, template.request.copy(enabled = false))
    }
}
