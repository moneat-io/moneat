// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.evaluation

import com.moneat.enterprise.alertroutes.context.AlertRouteContext

/** A template rendered against the normalized alert context. */
data class RenderedAlertRouteTemplate(
    val text: String?,
    val unresolvedReferences: List<String>,
)

/**
 * Renders `{{ alert.title }}`-style references from the normalized alert context.
 *
 * Unresolved references render as an empty string and are reported so preview can explain them.
 */
object AlertRouteTemplateRenderer {
    private val PLACEHOLDER = Regex("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*}}")

    fun render(template: String?, context: AlertRouteContext): RenderedAlertRouteTemplate {
        if (template.isNullOrBlank()) return RenderedAlertRouteTemplate(null, emptyList())
        val unresolved = linkedSetOf<String>()
        val text =
            PLACEHOLDER.replace(template) { match ->
                val reference = match.groupValues[1]
                context.reference(reference) ?: run {
                    unresolved.add(reference)
                    ""
                }
            }
        return RenderedAlertRouteTemplate(text.trim().takeIf { it.isNotEmpty() }, unresolved.toList())
    }

    /** Reference paths used by a template, in first-use order. */
    fun references(template: String?): List<String> {
        if (template.isNullOrBlank()) return emptyList()
        return PLACEHOLDER
            .findAll(template)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }
}
