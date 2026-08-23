// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.evaluation

import com.moneat.enterprise.alertroutes.context.AlertRouteContext
import com.moneat.enterprise.alertroutes.context.AlertRouteEpisodeIdentity
import java.security.MessageDigest
import kotlin.uuid.Uuid

data class AlertGroupingTupleEntry(val reference: String, val value: String)

data class CanonicalAlertGroupIdentity(
    val hash: String,
    val entries: List<AlertGroupingTupleEntry>,
    val singleton: Boolean,
)

/** Collision-safe, length-framed identity shared by preview and durable grouping. */
object CanonicalAlertGrouping {
    fun resolve(routeId: Uuid, references: List<String>, context: AlertRouteContext): CanonicalAlertGroupIdentity {
        val resolved = references.mapNotNull { reference ->
            context.reference(reference)?.let { AlertGroupingTupleEntry(reference, it) }
        }
        val singleton = resolved.isEmpty()
        val entries =
            if (singleton) {
                listOf(AlertGroupingTupleEntry(SINGLETON_REFERENCE, context.episode.singletonIdentity()))
            } else {
                resolved
            }
        val framed = buildString {
            appendFramed(routeId.toString())
            entries.forEach {
                appendFramed(it.reference)
                appendFramed(it.value)
            }
        }
        return CanonicalAlertGroupIdentity(sha256(framed), entries, singleton)
    }

    private fun AlertRouteEpisodeIdentity.singletonIdentity(): String =
        resourceId?.takeIf { it.isNotBlank() } ?: key?.takeIf { it.isNotBlank() } ?: "unknown:$sequence:$status"

    private fun StringBuilder.appendFramed(value: String) {
        append(value.length).append(':').append(value)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private const val SINGLETON_REFERENCE = "episode.identity"
}
